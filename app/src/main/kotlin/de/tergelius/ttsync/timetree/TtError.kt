package de.tergelius.ttsync.timetree

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Every failure the TimeTree client reports (DESIGN.md section 3, error table). [TimeTreeClient]
 * methods throw only these (plus `CancellationException`); IO exceptions and timeouts are wrapped
 * into [Transient].
 *
 * Messages carry only HTTP status, error codes and counts, never credentials, cookies or event
 * content, because they end up in logs and in `SyncReport`.
 */
sealed class TtError(message: String, cause: Throwable? = null) : Exception(message, cause) {
  /** True if the same run may be retried later without the user doing anything. */
  abstract val retryable: Boolean

  /**
   * Login `-702` (wrong password or a temporary block), or HTTP 401 right after a login.
   *
   * Reaction: no automatic login for 6 h; a second one in a row stops automatic logins until the
   * user signs in; notify. The client reports it through [TtSessionStore.onAutoLoginRejected] before
   * throwing, so the block is recorded exactly once.
   */
  class AuthRejected(val code: Int?) : TtError("sign-in rejected (code=$code)") {
    override val retryable: Boolean
      get() = false
  }

  /**
   * The session is invalid and an automatic login is not allowed: no stored credentials, or
   * automatic logins are blocked after [AuthRejected]. The user must sign in again. Unlike
   * [AuthRejected], no login was attempted, so it does not count towards the block.
   */
  class LoginRequired : TtError("sign-in required") {
    override val retryable: Boolean
      get() = false
  }

  /**
   * `-495` or HTTP 429. Retry after [backoff]: the server's `Retry-After` if it sent one, but never
   * sooner than [MIN_BACKOFF].
   */
  class RateLimited(val retryAfter: Duration?) : TtError("rate limited (retryAfter=$retryAfter)") {
    override val retryable: Boolean
      get() = true

    val backoff: Duration
      get() = retryAfter?.coerceAtLeast(MIN_BACKOFF) ?: MIN_BACKOFF

    companion object {
      val MIN_BACKOFF: Duration = 30.minutes
    }
  }

  /**
   * The calendar is missing from `/calendars`, deactivated, or its events answer 403/404. The
   * engine pauses that calendar, stops all its requests and keeps its copies. Other calendars
   * continue.
   */
  class AccessLost(val calendarId: Long, val httpStatus: Int?) :
    TtError("access lost to calendar $calendarId (http=$httpStatus)") {
    override val retryable: Boolean
      get() = false
  }

  /** HTTP 5xx, an IO error or a timeout. Retried by WorkManager. */
  class Transient(val httpStatus: Int?, cause: Throwable? = null) :
    TtError("transient failure (http=$httpStatus)", cause) {
    override val retryable: Boolean
      get() = true
  }

  /**
   * The response did not look like the API this app was built against: unexpected JSON, an
   * unexpected status, a repeated `since` cursor, or more than [TimeTreeApi.MAX_PAGES] pages. The
   * run fails and the user is told to update the app.
   */
  class ApiChanged(detail: String, cause: Throwable? = null) : TtError("API changed: $detail", cause) {
    override val retryable: Boolean
      get() = false
  }
}
