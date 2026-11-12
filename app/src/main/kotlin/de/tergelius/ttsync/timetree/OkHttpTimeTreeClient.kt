package de.tergelius.ttsync.timetree

import de.tergelius.ttsync.TtLog
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Cookie
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * The real [TimeTreeClient] (DESIGN.md section 3). [baseUrl] is replaceable so tests can point it
 * at `mockwebserver3`; [nowMillis] (wall clock) only dates `Retry-After` and cookie expiry;
 * [maxPages] is lowered only by tests.
 *
 * Request rules on top of the [TimeTreeClient] contract:
 * - Every request sends `X-Timetreea` and `Content-Type: application/json`; data requests add the
 *   session as a manual `Cookie: _session_id=…` header. Login requests carry no cookie.
 * - A successful (2xx) response that sets a new `_session_id` is saved through
 *   [TtSessionStore.onSessionRotated]. A cookie set by an error response (an auth failure, or a
 *   proxy's 5xx page) is worthless and ignored, as is an expired or empty one (a deletion).
 * - A data call without a stored session (none, or an empty one) logs in first (that is the run's
 *   one login, and the request after it counts as "right after login").
 * - Auth failure = HTTP 401 on any data call, or 403 on `/calendars`. The first one in a run logs in
 *   again and retries once. The same failure right after that login is [TtError.AuthRejected]; a
 *   later one in the same run is [TtError.Transient], so the next run starts with a fresh budget.
 * - `-495` (any status) and HTTP 429 are [TtError.RateLimited] and put the [pacer] into back-off.
 * - 403/404 on a calendar's labels or events is [TtError.AccessLost]; 5xx and 408 are
 *   [TtError.Transient]; any other status is [TtError.ApiChanged].
 * - Every data response body of a run (errors too, with `_http<code>` in the name) goes to
 *   [rawSink]. Login bodies, all headers and [calendarsInteractive] never do, and nothing secret is
 *   logged.
 * - **Concurrency.** A run and a screen ([calendarsInteractive]) may call at the same time. Each
 *   request exchange (session read, request, the automatic login and retry) holds one lock, so two
 *   callers never log in at once and the second one uses the session the first one stored. The
 *   [pacer] spaces requests of both. A run has one login budget ([beginRun]); every interactive call
 *   has its own, so neither spends nor depends on the other's.
 */
class OkHttpTimeTreeClient(
  private val http: OkHttpClient,
  private val sessions: TtSessionStore,
  private val pacer: RequestPacer,
  private val rawSink: RawResponseSink,
  private val baseUrl: HttpUrl = TimeTreeApi.BASE_URL.toHttpUrl(),
  private val log: TtLog = TtLog.None,
  private val nowMillis: () -> Long = System::currentTimeMillis,
  private val maxPages: Int = TimeTreeApi.MAX_PAGES,
) : TimeTreeClient {

  /** One automatic login (and the one retry after it), spent at most once. */
  private class LoginBudget {
    @Volatile var available = true
  }

  /** The current run's budget; [beginRun] starts a fresh one. */
  @Volatile private var runBudget = LoginBudget()

  /** Serializes request exchanges of a run and of screens (see the class comment). */
  private val exchange = Mutex()

  override suspend fun signIn(email: String, password: String): String {
    val sessionId = exchange.withLock { login(TtCredentials(email, password)) }
    log.i("timetree: interactive sign-in succeeded")
    return sessionId
  }

  override fun beginRun(runId: String) {
    runBudget = LoginBudget()
    try {
      rawSink.beginRun(runId)
    } catch (e: Exception) {
      log.w("timetree: raw dump unavailable (${e::class.simpleName})")
    }
  }

  override suspend fun calendars(): List<TtCalendar> =
    dataCall(Endpoint.Calendars, runBudget, dump = true, decode = ::decodeCalendars)

  override suspend fun calendarsInteractive(): List<TtCalendar> =
    dataCall(Endpoint.Calendars, LoginBudget(), dump = false, decode = ::decodeCalendars)

  private fun decodeCalendars(body: String): List<TtCalendar> =
    decodeTt("calendars") { TtJson.decodeFromString(TtCalendarsResponse.serializer(), body) }
      .calendars

  override suspend fun labels(calendarId: Long): List<TtLabel> =
    dataCall(Endpoint.Labels(calendarId), runBudget, dump = true) { body ->
      decodeTt("labels") { TtJson.decodeFromString(TtLabelsResponse.serializer(), body) }.labels
    }

  override suspend fun events(calendarId: Long, since: Long?): EventsResult {
    val byUuid = LinkedHashMap<String, FetchedEvent>()
    val skippedUuids = LinkedHashSet<String>()
    var skippedWithoutUuid = 0
    val requested = HashSet<Long>()
    var cursor = since
    var pages = 0
    val budget = runBudget
    while (true) {
      if (cursor != null) requested += cursor
      val page =
        dataCall(Endpoint.Events(calendarId, cursor), budget, dump = true) { body ->
          decodeTt("events page") { TtJson.decodeFromString(TtEventsPage.serializer(), body) }
        }
      pages++
      for (json in page.events) {
        // The last occurrence of a uuid wins, at its own position, even if it is malformed.
        val event =
          try {
            TtEvent.fromJson(json)
          } catch (e: TtError.ApiChanged) {
            val uuid = (json["uuid"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
            if (uuid == null) skippedWithoutUuid++
            else {
              byUuid.remove(uuid)
              skippedUuids += uuid
            }
            log.w(
              "timetree: calendar $calendarId: skipped malformed event " +
                (uuid?.let(TtLog::short) ?: "without uuid")
            )
            continue
          }
        skippedUuids -= event.uuid
        byUuid.remove(event.uuid)
        byUuid[event.uuid] = FetchedEvent(event, json)
      }
      if (!page.chunk) {
        val skipped = skippedUuids.size + skippedWithoutUuid
        log.d("timetree: calendar $calendarId: ${byUuid.size} events in $pages pages, $skipped skipped")
        return EventsResult(byUuid.values.toList(), page.since, pages, skippedUuids, skipped)
      }
      if (page.since in requested) {
        throw TtError.ApiChanged("events cursor repeated after $pages pages")
      }
      if (pages >= maxPages) throw TtError.ApiChanged("more than $maxPages event pages")
      cursor = page.since // may be lower than the previous one
    }
  }

  /**
   * One GET with the session, the one re-login of [budget], error mapping and (if [dump]) the raw
   * dump. The whole exchange holds [exchange].
   */
  private suspend fun <T> dataCall(
    endpoint: Endpoint,
    budget: LoginBudget,
    dump: Boolean,
    decode: (String) -> T,
  ): T = exchange.withLock { dataExchange(endpoint, budget, dump, decode) }

  private suspend fun <T> dataExchange(
    endpoint: Endpoint,
    budget: LoginBudget,
    dump: Boolean,
    decode: (String) -> T,
  ): T {
    var justLoggedIn = false
    // An empty value is no session: sending `_session_id=` could only earn a 401.
    var sessionId =
      sessions.sessionId()?.takeIf { it.isNotEmpty() }
        ?: autoLogin(endpoint, budget, reason = "no session").also { justLoggedIn = true }
    val url = endpoint.url(baseUrl)
    while (true) {
      val response = send(dataRequest(url, sessionId))
      val status = response.code
      val authFailure = status == 401 || (status == 403 && endpoint is Endpoint.Calendars)
      // Only a successful response's cookie is a session; an error page's cookie is worthless.
      if (response.isSuccessful) saveRotatedSession(url, response.headers, sessionId)
      if (dump) record(endpoint, response)
      if (response.isSuccessful) {
        log.d("timetree: ${endpoint.logName} -> $status")
        return decode(response.body)
      }
      val apiCode = apiErrorCode(response.body)
      log.w("timetree: ${endpoint.logName} -> $status (code=$apiCode)")
      when {
        apiCode == TimeTreeApi.CODE_RATE_LIMITED || status == 429 -> throw rateLimited(response)
        authFailure && justLoggedIn -> {
          sessions.onAutoLoginRejected()
          throw TtError.AuthRejected(apiCode)
        }
        authFailure && !budget.available -> throw TtError.Transient(status)
        authFailure -> {
          sessionId = autoLogin(endpoint, budget, reason = "HTTP $status")
          justLoggedIn = true
        }
        (status == 403 || status == 404) && endpoint.calendarId != null ->
          throw TtError.AccessLost(endpoint.calendarId, status)
        status >= 500 || status == 408 -> throw TtError.Transient(status)
        else -> throw TtError.ApiChanged("HTTP $status on ${endpoint.kind} (code=$apiCode)")
      }
    }
  }

  /** The [budget]'s one automatic login, with [TtSessionStore]'s credentials and block bookkeeping. */
  private suspend fun autoLogin(endpoint: Endpoint, budget: LoginBudget, reason: String): String {
    if (!budget.available) throw TtError.LoginRequired()
    budget.available = false
    val credentials = sessions.autoLoginCredentials()
    if (credentials == null) {
      log.w("timetree: ${endpoint.logName}: $reason, automatic sign-in not allowed")
      throw TtError.LoginRequired()
    }
    log.i("timetree: ${endpoint.logName}: $reason, signing in again")
    val sessionId =
      try {
        login(credentials)
      } catch (e: TtError.AuthRejected) {
        sessions.onAutoLoginRejected()
        throw e
      }
    sessions.onAutoLoginSucceeded(sessionId)
    return sessionId
  }

  /** `PUT /auth/email/signin`. Returns the new `_session_id`. Never dumped. */
  private suspend fun login(credentials: TtCredentials): String {
    val payload =
      TtJson.encodeToString(
        TtSignInRequest.serializer(),
        TtSignInRequest(credentials.email, credentials.password, sessions.installUuid()),
      )
    val url = baseUrl.newBuilder().addPathSegments("auth/email/signin").build()
    val request =
      Request.Builder()
        .url(url)
        // ByteArray body: keeps the Content-Type exactly "application/json" (no charset suffix).
        .put(payload.encodeToByteArray().toRequestBody(JSON))
        .header(TimeTreeApi.HEADER_CLIENT, TimeTreeApi.CLIENT_VALUE)
        .header(HEADER_CONTENT_TYPE, TimeTreeApi.CONTENT_TYPE_JSON)
        .build()
    val response = send(request)
    val status = response.code
    if (response.isSuccessful) {
      return sessionCookie(url, response.headers)
        ?: throw TtError.ApiChanged("sign-in answered HTTP $status without a session cookie")
    }
    val apiCode = apiErrorCode(response.body)
    log.w("timetree: sign-in -> $status (code=$apiCode)")
    throw when {
      apiCode == TimeTreeApi.CODE_AUTH_REJECTED -> TtError.AuthRejected(apiCode)
      apiCode == TimeTreeApi.CODE_RATE_LIMITED || status == 429 -> rateLimited(response)
      status == 401 -> TtError.AuthRejected(apiCode)
      status >= 500 || status == 408 -> TtError.Transient(status)
      // For example 400 with -401, which one report tied to a missing CSRF token (not in v1).
      else -> TtError.ApiChanged("sign-in answered HTTP $status (code=$apiCode)")
    }
  }

  private fun dataRequest(url: HttpUrl, sessionId: String): Request =
    Request.Builder()
      .url(url)
      .get()
      .header(TimeTreeApi.HEADER_CLIENT, TimeTreeApi.CLIENT_VALUE)
      .header(HEADER_CONTENT_TYPE, TimeTreeApi.CONTENT_TYPE_JSON)
      .header(HEADER_COOKIE, "${TimeTreeApi.SESSION_COOKIE}=$sessionId")
      .build()

  /** Waits for the pacer, then runs [request]. IO errors and timeouts become [TtError.Transient]. */
  private suspend fun send(request: Request): HttpResult {
    pacer.awaitSlot()
    return http.newCall(request).await()
  }

  private suspend fun saveRotatedSession(url: HttpUrl, headers: Headers, current: String) {
    val fresh = sessionCookie(url, headers) ?: return
    if (fresh != current) {
      sessions.onSessionRotated(fresh)
      log.d("timetree: session cookie rotated")
    }
  }

  /** The last `_session_id` that [headers] set, unless it is empty or already expired. */
  private fun sessionCookie(url: HttpUrl, headers: Headers): String? {
    var value: String? = null
    for (header in headers.values(HEADER_SET_COOKIE)) {
      val cookie = Cookie.parse(url, header) ?: continue
      if (cookie.name != TimeTreeApi.SESSION_COOKIE) continue
      value = cookie.value.takeIf { it.isNotEmpty() && cookie.expiresAt > nowMillis() }
    }
    return value
  }

  private fun rateLimited(response: HttpResult): TtError.RateLimited {
    val error = TtError.RateLimited(retryAfter(response.headers))
    pacer.backOff(error.backoff)
    return error
  }

  /** `Retry-After` as delta-seconds or an HTTP date; null if absent or unreadable. */
  private fun retryAfter(headers: Headers): Duration? {
    val value = headers[HEADER_RETRY_AFTER]?.trim() ?: return null
    value.toLongOrNull()?.let { return if (it >= 0) it.seconds else null }
    val date = headers.getDate(HEADER_RETRY_AFTER) ?: return null
    return (date.time - nowMillis()).coerceAtLeast(0).milliseconds
  }

  private fun record(endpoint: Endpoint, response: HttpResult) {
    val name =
      if (response.isSuccessful) endpoint.dumpName else "${endpoint.dumpName}_http${response.code}"
    try {
      rawSink.record(name, response.body)
    } catch (e: Exception) {
      log.w("timetree: raw dump failed (${e::class.simpleName})")
    }
  }

  /** The data endpoints. Names carry only ids and cursors, never content. */
  private sealed class Endpoint(val kind: String, val calendarId: Long?) {
    abstract fun url(base: HttpUrl): HttpUrl

    abstract val dumpName: String

    val logName: String
      get() = if (calendarId == null) kind else "$kind($calendarId)"

    /** `GET /calendars?since=0` */
    data object Calendars : Endpoint("calendars", null) {
      override val dumpName = "calendars"

      override fun url(base: HttpUrl): HttpUrl =
        base.newBuilder().addPathSegment("calendars").addQueryParameter("since", "0").build()
    }

    /** `GET /calendar/{id}/labels` */
    class Labels(id: Long) : Endpoint("labels", id) {
      override val dumpName = "calendar_${id}_labels"

      override fun url(base: HttpUrl): HttpUrl =
        base.newBuilder().addPathSegments("calendar/$calendarId/labels").build()
    }

    /** `GET /calendar/{id}/events/sync[?since=N]` */
    class Events(id: Long, private val since: Long?) : Endpoint("events", id) {
      override val dumpName = "calendar_${id}_events_" + (since?.let { "since_$it" } ?: "full")

      override fun url(base: HttpUrl): HttpUrl =
        base
          .newBuilder()
          .addPathSegments("calendar/$calendarId/events/sync")
          .apply { if (since != null) addQueryParameter("since", since.toString()) }
          .build()
    }
  }

  /** A fully read response; the body is read on OkHttp's thread, never on the caller's. */
  private class HttpResult(val code: Int, val headers: Headers, val body: String) {
    val isSuccessful: Boolean
      get() = code in 200..299
  }

  private suspend fun Call.await(): HttpResult = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(
      object : Callback {
        override fun onFailure(call: Call, e: IOException) {
          cont.resumeWithException(TtError.Transient(null, e))
        }

        override fun onResponse(call: Call, response: Response) {
          val result =
            try {
              response.use { HttpResult(it.code, it.headers, it.body.string()) }
            } catch (e: IOException) {
              cont.resumeWithException(TtError.Transient(null, e))
              return
            }
          cont.resume(result)
        }
      }
    )
  }

  private companion object {
    val JSON = TimeTreeApi.CONTENT_TYPE_JSON.toMediaType()
    const val HEADER_CONTENT_TYPE = "Content-Type"
    const val HEADER_COOKIE = "Cookie"
    const val HEADER_SET_COOKIE = "Set-Cookie"
    const val HEADER_RETRY_AFTER = "Retry-After"

    /** The `code` of an error body `{"error":{"code":N}}` (or a top-level `code`), else null. */
    fun apiErrorCode(body: String): Int? {
      val root =
        try {
          TtJson.parseToJsonElement(body) as? JsonObject
        } catch (e: SerializationException) {
          null
        } catch (e: IllegalArgumentException) {
          null
        } ?: return null
      val code = (root["error"] as? JsonObject)?.get("code") ?: root["code"]
      val primitive = code as? JsonPrimitive ?: return null
      return primitive.intOrNull ?: primitive.content.toIntOrNull()
    }
  }
}
