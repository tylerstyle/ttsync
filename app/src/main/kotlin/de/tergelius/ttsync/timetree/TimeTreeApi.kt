package de.tergelius.ttsync.timetree

import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds
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

  val CONNECT_TIMEOUT = 15.seconds
  val READ_TIMEOUT = 30.seconds
  val CALL_TIMEOUT = 60.seconds

  /** The shared OkHttp client with the design's timeouts. No cookie jar, no cache, default User-Agent. */
  fun newOkHttpClient(): OkHttpClient =
    OkHttpClient.Builder()
      .connectTimeout(CONNECT_TIMEOUT.inWholeMilliseconds, TimeUnit.MILLISECONDS)
      .readTimeout(READ_TIMEOUT.inWholeMilliseconds, TimeUnit.MILLISECONDS)
      .callTimeout(CALL_TIMEOUT.inWholeMilliseconds, TimeUnit.MILLISECONDS)
      .build()
}
