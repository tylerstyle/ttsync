package de.tergelius.ttsync.sync

import android.content.Context
import de.tergelius.ttsync.settings.SyncInterval
import kotlin.time.Duration
import de.tergelius.ttsync.timetree.RequestPacer
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.flow.Flow

/**
 * WorkManager scheduling (DESIGN.md section 9), implemented by [WorkManagerSyncScheduler].
 *
 * Contract:
 * - Periodic work [PERIODIC_WORK] runs [SyncWorker] at the chosen [SyncInterval] (never shorter
 *   than 1 h) with [FLEX] flex, `NetworkType.CONNECTED`, and exponential backoff from [BACKOFF].
 * - [runNow] enqueues the one-time [NOW_WORK] as expedited work (falling back to a normal request
 *   when out of quota), replacing a pending one, or as the one follow-up of a running one.
 * - Periodic work is only (re-)enqueued while syncing is wanted: signed in, at least one TimeTree
 *   calendar selected and a target set. The "Keep running" step does not have to be done, because
 *   the UI schedules as soon as the target is confirmed. App start also skips it when the last run
 *   found every selected calendar paused.
 */
interface SyncScheduler {
  /** App start: enqueue the periodic work with `KEEP`, if setup is complete. */
  fun ensurePeriodic()

  /** Settings changed or setup finished: enqueue the periodic work with `UPDATE`. */
  fun updatePeriodic(interval: SyncInterval)

  /** No calendar left (all deselected or paused): cancel the periodic work. */
  fun cancelPeriodic()

  /**
   * Run a sync now (expedited, never delayed). Setup completion and source or target changes call
   * this with `full = true, trigger = SETUP`, so the first copies appear at once and not after an
   * hour. "Sync now" uses `MANUAL`: within `RequestPacer.MANUAL_RUN_GAP` of a successful run it does
   * nothing ([recentSuccessAgo]; the UI says "Synced N s ago"), and while a run is going on it
   * queues at most one follow-up run.
   */
  fun runNow(full: Boolean, trigger: SyncTrigger)

  /** Sign-out: cancel every piece of work. */
  fun cancelAll()

  /** For Status: whether a run is going on, and when the next periodic run is due. */
  val state: Flow<ScheduleState>

  companion object {
    const val PERIODIC_WORK = "ttsync-periodic"
    const val NOW_WORK = "ttsync-now"
    val FLEX: Duration = 15.minutes
    val BACKOFF: Duration = 15.minutes

    /** Retryable failures are retried while `runAttemptCount <` this. */
    const val MAX_ATTEMPTS = 3

    /**
     * How long ago [last] finished, if it succeeded less than `RequestPacer.MANUAL_RUN_GAP` ago: a
     * manual non-FULL request then needs no run (device test F4). Null otherwise, also after a
     * failed run, which the user may want to retry at once.
     */
    fun recentSuccessAgo(last: SyncReport?, nowMillis: Long): Long? {
      if (last == null || !last.succeeded) return null
      val ago = nowMillis - last.finishedAt
      return ago.takeIf { it in 0 until RequestPacer.MANUAL_RUN_GAP.inWholeMilliseconds }
    }
  }
}

data class ScheduleState(
  val periodicScheduled: Boolean = false,
  /** `WorkInfo.nextScheduleTimeMillis` of the periodic work, or null. */
  val nextRunAtMillis: Long? = null,
  val running: Boolean = false,
  /** A [SyncScheduler.runNow] request is waiting to start. */
  val runNowPending: Boolean = false,
)

