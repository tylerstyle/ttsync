package de.tergelius.ttsync.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.await
import androidx.work.workDataOf
import de.tergelius.ttsync.TtLog
import de.tergelius.ttsync.settings.CredentialStore
import de.tergelius.ttsync.settings.SettingsRepository
import de.tergelius.ttsync.settings.StatusRepository
import de.tergelius.ttsync.settings.SyncInterval
import de.tergelius.ttsync.store.CalendarState
import de.tergelius.ttsync.timetree.RequestPacer
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * [SyncScheduler] on WorkManager (DESIGN.md section 9).
 *
 * - **Order.** The interface methods return at once; the work happens on [scope]. Every operation
 *   queues on one fair mutex in call order (started undispatched), so "cancel all, then schedule"
 *   from the UI is applied in exactly that order even though some steps read settings first.
 * - **Periodic** ([SyncScheduler.PERIODIC_WORK]): the chosen interval with [SyncScheduler.FLEX]
 *   flex, `NetworkType.CONNECTED`, exponential back-off from [SyncScheduler.BACKOFF]. It is only
 *   enqueued while syncing is wanted (signed in, sources and a target chosen); "Keep running" does
 *   not have to be done.
 * - **Now** ([SyncScheduler.NOW_WORK]): expedited one-time work, falling back to a normal request
 *   when out of quota. A pending request is replaced, and a pending FULL stays FULL. If the previous
 *   run finished less than [RequestPacer.MANUAL_RUN_GAP] ago, the request is a normal one delayed
 *   by the rest of that gap (expedited work cannot be delayed). While a "now" run is going on, the
 *   new request is appended after it, so the running sync is never cancelled.
 */
class WorkManagerSyncScheduler(
  context: Context,
  private val settings: SettingsRepository,
  private val credentials: CredentialStore,
  private val status: StatusRepository,
  private val scope: CoroutineScope,
  private val log: TtLog,
  private val nowMillis: () -> Long = System::currentTimeMillis,
) : SyncScheduler {
  private val appContext = context.applicationContext
  private val workManager: WorkManager by lazy { WorkManager.getInstance(appContext) }
  private val queue = Mutex()

  override fun ensurePeriodic() {
    ordered("ensurePeriodic") { enqueuePeriodic(ExistingPeriodicWorkPolicy.KEEP, interval = null) }
  }

  override fun updatePeriodic(interval: SyncInterval) {
    ordered("updatePeriodic") { enqueuePeriodic(ExistingPeriodicWorkPolicy.UPDATE, interval) }
  }

  override fun cancelPeriodic() {
    ordered("cancelPeriodic") {
      workManager.cancelUniqueWork(SyncScheduler.PERIODIC_WORK).await()
      log.i("scheduler: periodic work cancelled")
    }
  }

  override fun runNow(full: Boolean, trigger: SyncTrigger) {
    ordered("runNow") { enqueueNow(full, trigger) }
  }

  /** [runNow], but waits until the request is enqueued and says how (debug receiver). */
  suspend fun runNowAndWait(full: Boolean, trigger: SyncTrigger): NowRequest =
    orderedAsync { enqueueNow(full, trigger) }.await()

  override fun cancelAll() {
    ordered("cancelAll") {
      workManager.cancelUniqueWork(SyncScheduler.PERIODIC_WORK).await()
      workManager.cancelUniqueWork(SyncScheduler.NOW_WORK).await()
      log.i("scheduler: all work cancelled")
    }
  }

  override val state: Flow<ScheduleState> by lazy {
    combine(
        workManager.getWorkInfosForUniqueWorkFlow(SyncScheduler.PERIODIC_WORK),
        workManager.getWorkInfosForUniqueWorkFlow(SyncScheduler.NOW_WORK),
      ) { periodic, now ->
        val scheduled = periodic.firstOrNull { !it.state.isFinished }
        ScheduleState(
          periodicScheduled = scheduled != null,
          nextRunAtMillis =
            scheduled
              ?.takeIf { it.state == WorkInfo.State.ENQUEUED }
              ?.nextScheduleTimeMillis
              ?.takeIf { it in 1 until Long.MAX_VALUE },
          running = (periodic + now).any { it.state == WorkInfo.State.RUNNING },
          runNowPending =
            now.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED },
        )
      }
      .distinctUntilChanged()
  }

  /** How [runNowAndWait] enqueued the request. */
  data class NowRequest(
    val full: Boolean,
    val expedited: Boolean,
    val delayMillis: Long,
    /** Appended after a "now" run that was going on. */
    val afterRunning: Boolean,
  )

  // ---------------------------------------------------------------------------------------------

  private suspend fun enqueuePeriodic(policy: ExistingPeriodicWorkPolicy, interval: SyncInterval?) {
    val current = settings.current()
    if (current.sources.isEmpty() || current.target == null || credentials.load() == null) {
      log.i("scheduler: periodic work not scheduled, setup incomplete")
      return
    }
    if (policy == ExistingPeriodicWorkPolicy.KEEP) {
      // App start: not while the last run found every selected calendar paused (no calendar left).
      val paused =
        status.current().lastReport?.calendars.orEmpty()
          .filter { it.state == CalendarState.PAUSED }
          .map { it.ttCalId }
          .toSet()
      if (current.sources.all { it.id in paused }) {
        log.i("scheduler: periodic work not scheduled, every calendar is paused")
        return
      }
    }
    val every = interval ?: current.interval
    val request =
      PeriodicWorkRequest.Builder(
          SyncWorker::class.java,
          every.duration.inWholeMinutes,
          TimeUnit.MINUTES,
          SyncScheduler.FLEX.inWholeMinutes,
          TimeUnit.MINUTES,
        )
        .setConstraints(
          Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        )
        .setBackoffCriteria(
          BackoffPolicy.EXPONENTIAL,
          SyncScheduler.BACKOFF.inWholeMinutes,
          TimeUnit.MINUTES,
        )
        .setInputData(
          workDataOf(
            SyncWorker.KEY_TRIGGER to SyncTrigger.PERIODIC.name,
            SyncWorker.KEY_FULL to false,
          )
        )
        .addTag(TAG_SYNC)
        .build()
    workManager.enqueueUniquePeriodicWork(SyncScheduler.PERIODIC_WORK, policy, request).await()
    log.i("scheduler: periodic work every ${every.duration.inWholeHours} h ($policy)")
  }

  private suspend fun enqueueNow(full: Boolean, trigger: SyncTrigger): NowRequest {
    val existing = workManager.getWorkInfosForUniqueWorkFlow(SyncScheduler.NOW_WORK).first()
    val running = existing.any { it.state == WorkInfo.State.RUNNING }
    val pendingFull =
      existing.any {
        (it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED) &&
          TAG_FULL in it.tags
      }
    val merged = full || pendingFull
    val gap = RequestPacer.MANUAL_RUN_GAP.inWholeMilliseconds
    val delay =
      if (running) {
        gap
      } else {
        val lastFinished = status.current().lastReport?.finishedAt
        if (lastFinished == null) 0L else (lastFinished + gap - nowMillis()).coerceIn(0L, gap)
      }
    val builder =
      OneTimeWorkRequest.Builder(SyncWorker::class.java)
        .setInputData(
          workDataOf(SyncWorker.KEY_TRIGGER to trigger.name, SyncWorker.KEY_FULL to merged)
        )
        .setBackoffCriteria(
          BackoffPolicy.EXPONENTIAL,
          SyncScheduler.BACKOFF.inWholeMinutes,
          TimeUnit.MINUTES,
        )
        .addTag(TAG_SYNC)
    if (merged) builder.addTag(TAG_FULL)
    val expedited = delay == 0L
    if (expedited) {
      builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
    } else {
      builder.setInitialDelay(delay, TimeUnit.MILLISECONDS)
    }
    val policy = if (running) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.REPLACE
    workManager.enqueueUniqueWork(SyncScheduler.NOW_WORK, policy, builder.build()).await()
    log.i(
      "scheduler: ${SyncScheduler.NOW_WORK} enqueued trigger=$trigger full=$merged " +
        "expedited=$expedited delay=${delay / 1000}s afterRunning=$running"
    )
    return NowRequest(full = merged, expedited = expedited, delayMillis = delay, afterRunning = running)
  }

  private fun ordered(what: String, block: suspend () -> Unit) {
    scope.launch(start = CoroutineStart.UNDISPATCHED) {
      try {
        queue.withLock { block() }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        log.e("scheduler: $what failed: ${e.javaClass.simpleName}")
      }
    }
  }

  private fun <T> orderedAsync(block: suspend () -> T): Deferred<T> =
    scope.async(start = CoroutineStart.UNDISPATCHED) { queue.withLock { block() } }

  companion object {
    /** On every sync request, for `adb shell dumpsys jobscheduler` and WorkManager queries. */
    const val TAG_SYNC = "ttsync"

    /** On a pending "now" request that must run FULL; a replacement keeps it FULL. */
    const val TAG_FULL = "ttsync-full"
  }
}
