package de.tergelius.ttsync.sync

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import de.tergelius.ttsync.AppGraph
import de.tergelius.ttsync.appGraph
import de.tergelius.ttsync.settings.SyncStatus
import de.tergelius.ttsync.store.CalendarState
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Runs [SyncEngine.run] for the periodic and the "now" work (DESIGN.md section 9). Dependencies
 * come from `context.appGraph` (no WorkerFactory needed).
 *
 * Contract:
 * - Reads [KEY_TRIGGER] and [KEY_FULL] and runs the engine on `Dispatchers.IO` (its stores block).
 * - A retryable failure gives `Result.retry()` while `runAttemptCount <`
 *   [SyncScheduler.MAX_ATTEMPTS]. Any other finished run gives `Result.success()` with the problem
 *   in the output data: the outcome is recorded in `StatusRepository` and shown from there, and a
 *   failed result would also fail a "now" request appended behind this one.
 * - Notifications: the next success clears the failure notification; a failure shows it when
 *   [SyncStatus.shouldNotifyFailure] says so, once per problem. A calendar that became paused gets
 *   its access-lost notification once. When every selected calendar is paused, the periodic work
 *   is cancelled ("no calendar left").
 * - `getForegroundInfo()` (expedited work on API 26–30) uses [SyncNotifier.runningNotification]
 *   with the `dataSync` foreground service type.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
  override suspend fun doWork(): Result {
    val graph = applicationContext.appGraph
    val log = graph.log
    val triggerName = inputData.getString(KEY_TRIGGER)
    val trigger = SyncTrigger.entries.firstOrNull { it.name == triggerName } ?: SyncTrigger.PERIODIC
    val full = inputData.getBoolean(KEY_FULL, false)
    return withContext(Dispatchers.IO) {
      val before =
        try {
          graph.statusRepository.current()
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          log.w("worker: status unreadable (${e.javaClass.simpleName})")
          SyncStatus()
        }
      val request =
        SyncRequest(
          trigger = trigger,
          forceFull = full,
          runId = SyncRequest.newRunId(System.currentTimeMillis()),
        )
      log.i(
        "worker: start id=${request.runId} trigger=$trigger full=$full attempt=$runAttemptCount " +
          "work=${id.toString().take(8)}"
      )
      val report = graph.syncEngine.run(request)
      afterRun(graph, before, report)
      val failure = report.failure
      val retry =
        failure != null && failure.problem.retryable && runAttemptCount < SyncScheduler.MAX_ATTEMPTS
      log.i(
        "worker: done id=${request.runId} ok=${report.succeeded} problem=${failure?.problem} " +
          "result=${if (retry) "retry" else "done"}"
      )
      when {
        failure == null -> Result.success()
        retry -> Result.retry()
        else -> Result.success(workDataOf(OUT_PROBLEM to failure.problem.name))
      }
    }
  }

  override suspend fun getForegroundInfo(): ForegroundInfo {
    val notification = applicationContext.appGraph.syncNotifier.runningNotification()
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      ForegroundInfo(
        SyncNotifier.ID_RUNNING,
        notification,
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
      )
    } else {
      ForegroundInfo(SyncNotifier.ID_RUNNING, notification)
    }
  }

  /** Notifications and "no calendar left". Never fails the work. */
  private suspend fun afterRun(graph: AppGraph, before: SyncStatus, report: SyncReport) {
    val log = graph.log
    val notifier = graph.syncNotifier
    val status = graph.statusRepository
    try {
      val failure = report.failure
      if (failure == null) {
        notifier.clearFailure()
        if (before.failureNotified) status.setFailureNotified(false)
      } else {
        val after = status.current()
        val previous = before.lastReport?.failure?.problem
        if (
          after.shouldNotifyFailure(System.currentTimeMillis()) &&
            (!after.failureNotified || previous != failure.problem)
        ) {
          notifier.showFailure(failure)
          status.setFailureNotified(true)
          log.i("worker: failure notification shown (${failure.problem})")
        }
      }

      val pausedNow = report.calendars.filter { it.state == CalendarState.PAUSED }.map { it.ttCalId }
      if (pausedNow.isNotEmpty()) {
        val pausedBefore =
          before.lastReport?.calendars.orEmpty()
            .filter { it.state == CalendarState.PAUSED }
            .map { it.ttCalId }
            .toSet()
        val newlyPaused = pausedNow.filter { it !in pausedBefore }
        if (newlyPaused.isNotEmpty()) {
          val names = graph.settingsRepository.current().sources.associate { it.id to it.name }
          newlyPaused.forEach { notifier.showAccessLost(it, names[it]) }
          log.i("worker: access-lost notification for ${newlyPaused.size} calendar(s)")
        }
        if (report.calendars.all { it.state == CalendarState.PAUSED }) {
          graph.syncScheduler.cancelPeriodic()
          log.i("worker: every calendar is paused; periodic work cancelled")
        }
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      log.w("worker: notification bookkeeping failed (${e.javaClass.simpleName})")
    }
  }

  companion object {
    /** [SyncTrigger] name. */
    const val KEY_TRIGGER = "trigger"

    /** Boolean: force FULL. */
    const val KEY_FULL = "full"

    /** Output: the [SyncProblem] name of a run that finished with a failure. */
    const val OUT_PROBLEM = "problem"
  }
}
