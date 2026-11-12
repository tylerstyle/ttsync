package de.tergelius.ttsync.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Runs [SyncEngine.run] for the periodic and the "now" work (DESIGN.md section 9). Phase 5
 * implements it; dependencies come from `context.appGraph` (no WorkerFactory needed).
 *
 * Contract: reads [KEY_TRIGGER] and [KEY_FULL]; maps a retryable failure to `Result.retry()` while
 * `runAttemptCount < SyncScheduler.MAX_ATTEMPTS`, anything else to success or failure; updates the
 * failure notification through [SyncNotifier]; `getForegroundInfo()` uses
 * [SyncNotifier.runningNotification] with the `dataSync` foreground service type.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
  override suspend fun doWork(): Result = TODO("phase 5: SyncWorker")

  companion object {
    /** [SyncTrigger] name. */
    const val KEY_TRIGGER = "trigger"

    /** Boolean: force FULL. */
    const val KEY_FULL = "full"
  }
}
