package de.tergelius.ttsync.timetree

import java.net.ProtocolException
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

/**
 * Constants of TimeTree's unofficial web API (DESIGN.md section 3). The app only ever reads it.
 *
 * Pure JVM: nothing in `timetree` may import `android.*`.
 */
object TimeTreeApi {
  /** Base URL for every request, with a trailing slash so relative paths resolve below it. */
  const val BASE_URL = "https://timetreeapp.com/api/v1/"

  const val HEADER_CLIENT = "X-Timetreea"
  const val CLIENT_VALUE = "web/2.1.0/en"
  const val CONTENT_TYPE_JSON = "application/json"

  /** The session cookie. The client sends it as a manual `Cookie` header, never through a CookieJar. */
  const val SESSION_COOKIE = "_session_id"

  /** Error code in a login response: wrong password or a temporary block. Maps to [TtError.AuthRejected]. */
  const val CODE_AUTH_REJECTED = -702

  /** Error code for rate limiting. Maps to [TtError.RateLimited], like HTTP 429. */
  const val CODE_RATE_LIMITED = -495

  /** Paging stops with [TtError.ApiChanged] after this many pages of one events fetch. */
  const val MAX_PAGES = 500

  /**
   * How far below a known `updated_at` a read starts again: the second pass of a FULL read
   * ([EventFeed]) and the `since` of an INCREMENTAL one ([ChangeFeed]). Changes that commit with a
   * slightly older timestamp, or share one, are read again rather than missed.
   * Re-reading a few known events is harmless; the engine's hash diff writes nothing for them.
   */
  val FEED_OVERLAP = 2.minutes

  val CONNECT_TIMEOUT = 15.seconds
  val READ_TIMEOUT = 30.seconds
  val CALL_TIMEOUT = 60.seconds

  /**
   * The shared OkHttp client with the design's timeouts. No cookie jar, no cache, default User-Agent.
   * Requests go only to the host of [baseUrl]: the session cookie is a manual header and the
   * sign-in body holds the password, and OkHttp would send both along with a redirect to another
   * host. Such a redirect fails as an IO error instead ([TtError.Transient]).
   */
  fun newOkHttpClient(baseUrl: HttpUrl = BASE_URL.toHttpUrl()): OkHttpClient =
    OkHttpClient.Builder()
      .addNetworkInterceptor { chain ->
        val url = chain.request().url
        if (url.host != baseUrl.host || url.port != baseUrl.port || url.scheme != baseUrl.scheme) {
          // ProtocolException: OkHttp does not try the request again on another route.
          throw ProtocolException("request to another host refused")
        }
        chain.proceed(chain.request())
      }
      .connectTimeout(CONNECT_TIMEOUT.inWholeMilliseconds, TimeUnit.MILLISECONDS)
      .readTimeout(READ_TIMEOUT.inWholeMilliseconds, TimeUnit.MILLISECONDS)
      .callTimeout(CALL_TIMEOUT.inWholeMilliseconds, TimeUnit.MILLISECONDS)
      .build()
}
