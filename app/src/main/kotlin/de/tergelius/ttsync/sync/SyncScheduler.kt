package de.tergelius.ttsync.sync

import android.content.Context
import de.tergelius.ttsync.settings.SyncInterval
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.flow.Flow

/**
 * WorkManager scheduling (DESIGN.md section 9). Phase 5 implements it as [WorkManagerSyncScheduler].
 *
 * Contract:
 * - Periodic work [PERIODIC_WORK] runs [SyncWorker] at the chosen [SyncInterval] (never shorter
 *   than 1 h) with [FLEX] flex, `NetworkType.CONNECTED`, and exponential backoff from [BACKOFF].
 * - [runNow] enqueues the one-time [NOW_WORK] as expedited work (falling back to a normal request
 *   when out of quota), replacing a pending one.
 * - Nothing is scheduled while setup is incomplete or no TimeTree calendar is active.
 */
interface SyncScheduler {
  /** App start: enqueue the periodic work with `KEEP`, if setup is complete. */
  fun ensurePeriodic()

  /** Settings changed or setup finished: enqueue the periodic work with `UPDATE`. */
  fun updatePeriodic(interval: SyncInterval)

  /** No calendar left (all deselected or paused): cancel the periodic work. */
  fun cancelPeriodic()

  /**
   * Run a sync now (expedited). Setup completion and source or target changes call this with
   * `full = true, trigger = SETUP`, so the first copies appear at once and not after an hour.
   * "Sync now" uses `MANUAL`; the run itself waits until `RequestPacer.MANUAL_RUN_GAP` after the
   * previous run.
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

/** Phase 5 implements this. */
class WorkManagerSyncScheduler(context: Context) : SyncScheduler {
  private val appContext = context.applicationContext

  override fun ensurePeriodic(): Unit = notYet()

  override fun updatePeriodic(interval: SyncInterval): Unit = notYet()

  override fun cancelPeriodic(): Unit = notYet()

  override fun runNow(full: Boolean, trigger: SyncTrigger): Unit = notYet()

  override fun cancelAll(): Unit = notYet()

  override val state: Flow<ScheduleState>
    get() = notYet()

  private fun notYet(): Nothing = TODO("phase 5: SyncScheduler (${appContext.packageName})")
}
