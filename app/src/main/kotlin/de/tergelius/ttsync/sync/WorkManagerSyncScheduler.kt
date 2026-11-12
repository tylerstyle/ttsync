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
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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
 * - **Now** ([SyncScheduler.NOW_WORK]): always expedited one-time work, falling back to a normal
 *   request when out of quota; never delayed, because a delayed job of an app in the background
 *   can wait half an hour (device test F4). See [planNow] for what a request does:
 *   - a pending request is replaced, and a pending FULL stays FULL;
 *   - a non-FULL request within [RequestPacer.MANUAL_RUN_GAP] of a successful run is not enqueued
 *     at all ([NowRequest.recentlySyncedAgoMillis]; the UI says "Synced N s ago");
 *   - while a "now" run is going on, the first request is appended after it as its follow-up, so
 *     the running sync is never cancelled, and later ones coalesce into that follow-up (FULL wins:
 *     a follow-up that is not FULL is updated in place), so repeated taps never queue a chain of
 *     runs (phase 5 review, SHOULD-FIX-2).
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

  /** How [runNowAndWait] handled the request. */
  data class NowRequest(
    val full: Boolean,
    /** Appended after a "now" run that was going on. */
    val afterRunning: Boolean,
    /** Merged into the follow-up that already waited behind the running sync. */
    val coalesced: Boolean = false,
    /** Not enqueued: the last run succeeded this long ago (less than the manual run gap). */
    val recentlySyncedAgoMillis: Long? = null,
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
    val existing =
      workManager.getWorkInfosForUniqueWorkFlow(SyncScheduler.NOW_WORK).first().map {
        NowWork(it.id, it.state, full = TAG_FULL in it.tags)
      }
    val recentAgo = SyncScheduler.recentSuccessAgo(status.current().lastReport, nowMillis())
    var plan = planNow(existing, full, recentAgo)
    if (plan is NowPlan.UpgradeToFull) {
      if (upgradeToFull(plan.ids, trigger)) {
        log.i("scheduler: ${SyncScheduler.NOW_WORK} follow-up upgraded to FULL trigger=$trigger")
        return NowRequest(full = true, afterRunning = true, coalesced = true)
      }
      // The follow-up started or finished in the meantime: append a new one.
      plan = NowPlan.Enqueue(full = true, afterRunning = true)
    }
    when (plan) {
      is NowPlan.RecentlySynced -> {
        log.i("scheduler: ${SyncScheduler.NOW_WORK} not enqueued, synced ${plan.agoMillis / 1000} s ago")
        return NowRequest(full = false, afterRunning = false, recentlySyncedAgoMillis = plan.agoMillis)
      }
      is NowPlan.Coalesce -> {
        log.i("scheduler: ${SyncScheduler.NOW_WORK} already queued behind the running sync (full=${plan.full})")
        return NowRequest(plan.full, afterRunning = true, coalesced = true)
      }
      is NowPlan.Enqueue -> {
        val policy =
          if (plan.afterRunning) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.REPLACE
        workManager.enqueueUniqueWork(SyncScheduler.NOW_WORK, policy, nowWork(plan.full, trigger)).await()
        log.i(
          "scheduler: ${SyncScheduler.NOW_WORK} enqueued trigger=$trigger full=${plan.full} " +
            "afterRunning=${plan.afterRunning}"
        )
        return NowRequest(plan.full, afterRunning = plan.afterRunning)
      }
      is NowPlan.UpgradeToFull -> error("handled above")
    }
  }

  /**
   * Turns the follow-ups waiting behind a running sync into FULL runs in place, keeping their place
   * in the chain (`WorkManager.updateWork`). False if one could not be updated any more (it started
   * or finished meanwhile).
   */
  private suspend fun upgradeToFull(ids: List<UUID>, trigger: SyncTrigger): Boolean =
    ids.all { id ->
      val update = workManager.updateWork(nowWork(full = true, trigger, id = id))
      val result = withContext(Dispatchers.IO) { update.get() }
      result == WorkManager.UpdateResult.APPLIED_IMMEDIATELY
    }

  /** A [SyncScheduler.NOW_WORK] request: expedited, never delayed (see the class comment). */
  private fun nowWork(full: Boolean, trigger: SyncTrigger, id: UUID? = null): OneTimeWorkRequest {
    val builder =
      OneTimeWorkRequest.Builder(SyncWorker::class.java)
        .setInputData(workDataOf(SyncWorker.KEY_TRIGGER to trigger.name, SyncWorker.KEY_FULL to full))
        .setBackoffCriteria(
          BackoffPolicy.EXPONENTIAL,
          SyncScheduler.BACKOFF.inWholeMinutes,
          TimeUnit.MINUTES,
        )
        .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        .addTag(TAG_SYNC)
    if (id != null) builder.setId(id)
    if (full) builder.addTag(TAG_FULL)
    return builder.build()
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

  /** One [SyncScheduler.NOW_WORK] item as [planNow] sees it. */
  data class NowWork(val id: UUID, val state: WorkInfo.State, val full: Boolean)

  /** What a [runNow] request does with the unique work as it stands. */
  sealed interface NowPlan {
    /** Enqueue a request: replacing a pending one, or appended after a running one. */
    data class Enqueue(val full: Boolean, val afterRunning: Boolean) : NowPlan

    /** Nothing to do: the last run succeeded [agoMillis] ago, within the manual run gap. */
    data class RecentlySynced(val agoMillis: Long) : NowPlan

    /** A follow-up already waits behind the running sync and covers this request. */
    data class Coalesce(val full: Boolean) : NowPlan

    /** The follow-ups waiting behind the running sync become FULL, in place. */
    data class UpgradeToFull(val ids: List<UUID>) : NowPlan
  }

  companion object {
    /**
     * The scheduling decision of [runNow], pure for tests. [recentSuccessAgo] is
     * [SyncScheduler.recentSuccessAgo] of the last report.
     * - While a sync runs, the first request is appended as its follow-up and every later one
     *   coalesces into it; only a FULL request changes a follow-up that is not FULL yet.
     * - Otherwise a non-FULL request right after a successful run needs no run (unless a FULL is
     *   pending); anything else replaces a pending request, and a pending FULL stays FULL.
     */
    fun planNow(existing: List<NowWork>, full: Boolean, recentSuccessAgo: Long?): NowPlan {
      val running = existing.any { it.state == WorkInfo.State.RUNNING }
      val pending =
        existing.filter { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }
      val pendingFull = pending.any { it.full }
      return when {
        running && pending.isEmpty() -> NowPlan.Enqueue(full, afterRunning = true)
        running && full && !pendingFull -> NowPlan.UpgradeToFull(pending.map { it.id })
        running -> NowPlan.Coalesce(full || pendingFull)
        !full && !pendingFull && recentSuccessAgo != null -> NowPlan.RecentlySynced(recentSuccessAgo)
        else -> NowPlan.Enqueue(full || pendingFull, afterRunning = false)
      }
    }

    /** On every sync request, for `adb shell dumpsys jobscheduler` and WorkManager queries. */
    const val TAG_SYNC = "ttsync"

    /** On a pending "now" request that must run FULL; a replacement keeps it FULL. */
    const val TAG_FULL = "ttsync-full"
  }
}
