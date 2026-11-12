package de.tergelius.ttsync.ui

import de.tergelius.ttsync.calendar.CalendarGateway
import de.tergelius.ttsync.calendar.CalendarInfo
import de.tergelius.ttsync.calendar.TargetCalendar
import de.tergelius.ttsync.calendar.TargetCheck
import de.tergelius.ttsync.settings.CredentialStore
import de.tergelius.ttsync.settings.Credentials
import de.tergelius.ttsync.settings.Settings
import de.tergelius.ttsync.settings.SettingsRepository
import de.tergelius.ttsync.settings.SetupStep
import de.tergelius.ttsync.settings.SourceCalendar
import de.tergelius.ttsync.settings.StatusRepository
import de.tergelius.ttsync.settings.SyncInterval
import de.tergelius.ttsync.sync.RemovalReport
import de.tergelius.ttsync.sync.SyncEngine
import de.tergelius.ttsync.sync.SyncFailure
import de.tergelius.ttsync.sync.SyncNotifier
import de.tergelius.ttsync.sync.SyncScheduler
import de.tergelius.ttsync.sync.SyncTrigger
import de.tergelius.ttsync.timetree.TimeTreeClient
import de.tergelius.ttsync.timetree.TtCalendar
import de.tergelius.ttsync.timetree.TtError
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The user actions behind the screens (DESIGN.md sections 5 "User actions", 6 and 7), in one place
 * so that every screen orders the steps the same way. The ViewModels call these after the user
 * confirmed; nothing here asks questions.
 *
 * Ordering rules:
 * - Before copies are deleted, scheduled work is cancelled, or the engine changes the selection
 *   under its mutex, so a periodic run cannot re-create them in between.
 * - A removal that fails ([RemovalReport.failure]) changes nothing else: the selection, the target,
 *   the settings and the sign-in stay as they were (restored where the engine had already cleared
 *   them), the periodic work is scheduled again, and the caller shows the error.
 * - Every change that needs new copies ends with [SyncScheduler.runNow] `(full = true, SETUP)`.
 * - Engine calls run on [io]: the engine's stores block.
 */
class UserActions(
  private val client: TimeTreeClient,
  private val credentials: CredentialStore,
  private val settings: SettingsRepository,
  private val status: StatusRepository,
  private val engine: SyncEngine,
  private val scheduler: SyncScheduler,
  private val gateway: CalendarGateway,
  private val notifier: SyncNotifier,
  /** Sign-out: wipes `SyncDb` and the debug dumps (blocking; runs on [io]). */
  private val wipeLocalFiles: () -> Unit,
  private val io: CoroutineDispatcher = Dispatchers.IO,
  private val nowMillis: () -> Long = System::currentTimeMillis,
) {
  /** The setup step that follows from what is stored now. */
  suspend fun currentStep(): SetupStep =
    SetupStep.of(signedIn = credentials.load() != null, settings = settings.current())

  /**
   * Interactive sign-in (Login screen). Stores the credentials and session, clears the auth block,
   * and, if setup was already complete (signing in again), starts a sync. Returns the next step.
   *
   * @throws TtError from [TimeTreeClient.signIn]; a rate limit is also recorded for the worker.
   */
  suspend fun signIn(email: String, password: String): SetupStep {
    val sessionId =
      try {
        client.signIn(email, password)
      } catch (e: TtError.RateLimited) {
        recordRateLimit(e)
        throw e
      }
    credentials.save(Credentials(email = email, password = password, sessionId = sessionId))
    status.recordLoginSucceeded()
    val next = currentStep()
    if (next == SetupStep.DONE) scheduler.runNow(full = false, trigger = SyncTrigger.MANUAL)
    return next
  }

  /**
   * The TimeTree calendars for the Sources screen, through [TimeTreeClient.calendarsInteractive], so
   * it is safe while a sync runs. A rate limit is recorded for the worker, as at sign-in.
   */
  suspend fun timeTreeCalendars(): List<TtCalendar> =
    try {
      client.calendarsInteractive()
    } catch (e: TtError.RateLimited) {
      recordRateLimit(e)
      throw e
    }

  private suspend fun recordRateLimit(e: TtError.RateLimited) {
    status.setRateLimitedUntil(nowMillis() + e.backoff.inWholeMilliseconds)
  }

  /** What [saveSources] would change; the caller confirms if [removesCopies]. */
  suspend fun sourceChange(selected: List<SourceCalendar>): SourceChange {
    val current = settings.current()
    val old = current.sources.map { it.id }.toSet()
    val new = selected.map { it.id }.toSet()
    return SourceChange(
      removed = current.sources.filter { it.id !in new },
      added = selected.filter { it.id !in old },
      hasTarget = current.target != null,
    )
  }

  /**
   * Saves the TimeTree calendar selection (Sources screen). Deselected calendars lose their copies
   * first (after the caller's confirm): the engine drops them from the selection under its mutex
   * once their copies are gone, so no run fetches them again. Then the new selection is saved, and
   * newly selected calendars start a FULL sync.
   *
   * @throws RemovalFailedException if the copies could not be removed; nothing was changed.
   */
  suspend fun saveSources(selected: List<SourceCalendar>): SetupStep {
    require(selected.isNotEmpty()) { "select at least one calendar" }
    val change = sourceChange(selected)
    if (change.hasTarget && change.removed.isNotEmpty()) {
      val report = withContext(io) { engine.deselect(change.removed.map { it.id }.toSet()) }
      report.failure?.let { throw RemovalFailedException(it) }
      change.removed.forEach { notifier.clearAccessLost(it.id) }
    }
    settings.setSources(selected)
    if (change.hasTarget) {
      if (change.added.isNotEmpty()) {
        // The periodic work may have been cancelled ("Remove all copies", last calendar removed).
        scheduler.updatePeriodic(settings.current().interval)
        scheduler.runNow(full = true, trigger = SyncTrigger.SETUP)
      }
    }
    return currentStep()
  }

  /**
   * Sets the target calendar (Target screen) and starts the first FULL sync plus the periodic work
   * (DESIGN.md sections 7.3 and 9). Changing an existing target first removes the copies from the
   * old calendar (after the caller's confirm): work is cancelled, the old copies deleted and the
   * mappings cleared, and only then is the new target saved. If that removal fails, the old target
   * stays and keeps syncing, and [TargetChange.failed] is set.
   */
  suspend fun setTarget(info: CalendarInfo): TargetChange {
    val current = settings.current()
    val newTarget = TargetCalendar.of(info)
    val old = current.target
    if (old == newTarget) {
      settings.setTarget(newTarget, info.displayName)
      return TargetChange(removal = null, oldCopiesLeft = false, next = currentStep())
    }
    var removal: RemovalReport? = null
    var oldCopiesLeft = false
    if (old != null) {
      scheduler.cancelAll()
      restoringOnFailure(current) {
        when (old.check(gateway.calendar(old.calendarId))) {
          TargetCheck.OK ->
            removal = withContext(io) { engine.removeAllCopies(old, stopSyncing = false) }
          // The calendar and its copies are gone: only the mappings are left to clear.
          TargetCheck.MISSING -> withContext(io) { engine.dropMappings() }
          // The old calendar can no longer be written safely: leave its rows alone.
          else -> {
            withContext(io) { engine.dropMappings() }
            oldCopiesLeft = true
          }
        }
      }
      if (removal?.failure != null) {
        restore(current)
        return TargetChange(removal = removal, oldCopiesLeft = false, next = currentStep())
      }
    }
    settings.setTarget(newTarget, info.displayName)
    scheduler.updatePeriodic(current.interval)
    scheduler.runNow(full = true, trigger = SyncTrigger.SETUP)
    return TargetChange(removal = removal, oldCopiesLeft = oldCopiesLeft, next = currentStep())
  }

  /** The "Keep running" step was completed or skipped. */
  suspend fun finishKeepRunning(): SetupStep {
    settings.setKeepRunningDone(true)
    return currentStep()
  }

  fun syncNow() = scheduler.runNow(full = false, trigger = SyncTrigger.MANUAL)

  fun fullResync() = scheduler.runNow(full = true, trigger = SyncTrigger.MANUAL)

  suspend fun setInterval(interval: SyncInterval) {
    settings.setInterval(interval)
    scheduler.updatePeriodic(interval)
  }

  /**
   * "Remove copies" for TimeTree calendars that lost access (Status): deletes their copies, and the
   * engine then drops them from the selection. With no calendar left the periodic work is
   * cancelled. If the removal fails ([RemovalReport.failure]), the calendars stay selected.
   */
  suspend fun removePaused(ids: Set<Long>): RemovalReport {
    val report = withContext(io) { engine.deselect(ids) }
    if (report.failure != null) return report
    ids.forEach(notifier::clearAccessLost)
    val sources = settings.current().sources
    val remaining = sources.filter { it.id !in ids }
    if (remaining.size != sources.size) settings.setSources(remaining)
    if (remaining.isEmpty()) scheduler.cancelAll()
    return report
  }

  /**
   * "Remove all copies" (Settings): stops syncing, deletes every marked row in the target and
   * clears the selection, so setup continues at Sources. The target and sign-in are kept. If the
   * removal fails ([RemovalReport.failure]), the selection is restored and syncing goes on.
   */
  suspend fun removeAllCopies(): RemovalReport {
    val current = settings.current()
    scheduler.cancelAll()
    val target = current.target
    val report =
      restoringOnFailure(current) {
        if (
          target != null && target.check(gateway.calendar(target.calendarId)) != TargetCheck.MISSING
        ) {
          withContext(io) { engine.removeAllCopies(target) }
        } else {
          withContext(io) { engine.dropMappings() }
          RemovalReport(deleted = 0, rejected = 0)
        }
      }
    if (report.failure != null) {
      restore(current)
      return report
    }
    val ids = current.sources.map { it.id }.toSet()
    if (ids.isNotEmpty()) withContext(io) { engine.deselect(ids) }
    settings.setSources(emptyList())
    notifier.clearFailure()
    ids.forEach(notifier::clearAccessLost)
    return report
  }

  /**
   * Sign-out (DESIGN.md section 6). Optionally removes the copies first; if that fails, nothing
   * else happens: the sign-in, settings and selection stay, syncing goes on, and the failed report
   * is returned (or the error thrown) so the user can retry or keep the copies. Then work is
   * cancelled and everything is wiped: credentials and key, settings, status, `SyncDb` and the
   * debug dumps. The wipe is best effort: every step runs, and the first failure is thrown at the
   * end.
   *
   * @return the removal report, or null if no copies were removed; check [RemovalReport.failure].
   */
  suspend fun signOut(removeCopies: Boolean): RemovalReport? {
    val current = settings.current()
    scheduler.cancelAll()
    val removal =
      restoringOnFailure(current) {
        if (removeCopies && current.target != null) {
          val target = current.target
          if (target.check(gateway.calendar(target.calendarId)) == TargetCheck.MISSING) null
          else withContext(io) { engine.removeAllCopies(target) }
        } else {
          null
        }
      }
    if (removal?.failure != null) {
      restore(current)
      return removal
    }
    var failure: Throwable? = null
    suspend fun step(block: suspend () -> Unit) {
      try {
        block()
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        if (failure == null) failure = e
      }
    }
    step { credentials.clear() }
    step { settings.clear() }
    step { status.clear() }
    step { withContext(io) { wipeLocalFiles() } }
    step { notifier.clearFailure() }
    current.sources.forEach { source -> step { notifier.clearAccessLost(source.id) } }
    failure?.let { throw it }
    return removal
  }

  /** Runs [block]; if it throws, [restore]s [before] and rethrows. */
  private suspend fun <T> restoringOnFailure(before: Settings, block: suspend () -> T): T =
    try {
      block()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      restore(before)
      throw e
    }

  /**
   * After a failed removal: the selection as it was (the engine may have cleared it before
   * deleting), and the periodic work that was cancelled for the removal.
   */
  private suspend fun restore(before: Settings) {
    try {
      if (settings.current().sources != before.sources) settings.setSources(before.sources)
    } finally {
      scheduler.updatePeriodic(before.interval)
    }
  }
}

/** A removal of copies failed, so nothing was changed; [failure] says why. */
class RemovalFailedException(val failure: SyncFailure) :
  Exception("removal of copies failed: ${failure.problem}")

/** Result of [UserActions.sourceChange]. */
data class SourceChange(
  val removed: List<SourceCalendar>,
  val added: List<SourceCalendar>,
  val hasTarget: Boolean,
) {
  /** Copies exist that the change would delete: ask first. */
  val removesCopies: Boolean
    get() = hasTarget && removed.isNotEmpty()
}

/** Result of [UserActions.setTarget]. */
data class TargetChange(
  /** Copies removed from the old target, or null if there was none to remove. */
  val removal: RemovalReport?,
  /** The old target can no longer be written safely, so its copies stay there. */
  val oldCopiesLeft: Boolean,
  val next: SetupStep,
) {
  /** Removing the old copies failed, so the target was not changed. */
  val failed: Boolean
    get() = removal?.failure != null
}
