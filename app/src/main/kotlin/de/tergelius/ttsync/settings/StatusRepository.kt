package de.tergelius.ttsync.settings

import de.tergelius.ttsync.store.CalendarState
import de.tergelius.ttsync.sync.SyncProblem
import de.tergelius.ttsync.sync.SyncReport
import de.tergelius.ttsync.sync.SyncWarning
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.flow.Flow

/**
 * Sync status and the auth/rate-limit back-off state (DESIGN.md sections 3, 7 and 9), stored in
 * DataStore. Phase 5 implements it as `DataStoreStatusRepository`. No `android.*` here.
 */
interface StatusRepository {
  val status: Flow<SyncStatus>

  suspend fun current(): SyncStatus

  /**
   * Stores the report of a finished run. A success sets `lastSuccessAt` and resets
   * `consecutiveFailures`; a failure increments it.
   */
  suspend fun recordRun(report: SyncReport)

  /** A login was rejected: block automatic logins for [SyncStatus.AUTH_BLOCK]; two in a row stop them. */
  suspend fun recordAuthRejected(nowMillis: Long)

  /** A login succeeded (interactive or automatic): clear the auth block. */
  suspend fun recordLoginSucceeded()

  /** After `TtError.RateLimited`: no requests before [untilMillis] (null clears it). */
  suspend fun setRateLimitedUntil(untilMillis: Long?)

  /** Whether the single failure notification is currently shown. */
  suspend fun setFailureNotified(notified: Boolean)

  /** Sign-out. */
  suspend fun clear()
}

data class SyncStatus(
  val lastReport: SyncReport? = null,
  val lastSuccessAt: Long? = null,
  val consecutiveFailures: Int = 0,
  /** `AuthRejected` in a row without a successful login in between. */
  val authRejections: Int = 0,
  val autoLoginBlockedUntil: Long? = null,
  val rateLimitedUntil: Long? = null,
  val failureNotified: Boolean = false,
) {
  /** After the second rejection in a row, automatic logins stop until the user signs in. */
  val autoLoginStopped: Boolean
    get() = authRejections >= 2

  fun mayAutoLogin(nowMillis: Long): Boolean =
    !autoLoginStopped && (autoLoginBlockedUntil == null || nowMillis >= autoLoginBlockedUntil)

  /** The last success is more than [STALE_AFTER] ago (shown in red on Status). */
  fun isStale(nowMillis: Long): Boolean =
    lastSuccessAt != null && nowMillis - lastSuccessAt > STALE_AFTER.inWholeMilliseconds

  /** The failure notification is due (section 9). */
  fun shouldNotifyFailure(nowMillis: Long): Boolean =
    consecutiveFailures >= FAILURES_BEFORE_NOTIFY ||
      isStale(nowMillis) ||
      lastReport?.failure?.problem?.userMustAct == true

  val googleSyncOff: Boolean
    get() = lastReport?.warnings?.contains(SyncWarning.GOOGLE_SYNC_OFF) == true

  /** The state shown at the top of Status. */
  fun health(running: Boolean, nowMillis: Long): SyncHealth {
    val problem = lastReport?.failure?.problem
    val calendars = lastReport?.calendars.orEmpty()
    return when {
      running -> SyncHealth.RUNNING
      problem == SyncProblem.TARGET_DELETED -> SyncHealth.TARGET_DELETED
      problem?.userMustAct == true -> SyncHealth.NEEDS_ATTENTION
      consecutiveFailures >= FAILURES_BEFORE_NOTIFY || isStale(nowMillis) -> SyncHealth.NEEDS_ATTENTION
      calendars.isNotEmpty() && calendars.all { it.state == CalendarState.PAUSED } -> SyncHealth.PAUSED
      else -> SyncHealth.OK
    }
  }

  companion object {
    val AUTH_BLOCK: Duration = 6.hours
    val STALE_AFTER: Duration = 24.hours
    const val FAILURES_BEFORE_NOTIFY = 3
  }
}

/** Status headline (DESIGN.md section 7, Status). Warnings such as Google sync off come on top. */
enum class SyncHealth {
  OK,
  RUNNING,
  NEEDS_ATTENTION,

  /** Every selected TimeTree calendar lost access. */
  PAUSED,

  /** "Target calendar was deleted", with a button to the Target screen. */
  TARGET_DELETED,
}
