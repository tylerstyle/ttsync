package de.tergelius.ttsync.ui

import de.tergelius.ttsync.calendar.CalendarGateway
import de.tergelius.ttsync.calendar.CalendarInfo
import de.tergelius.ttsync.calendar.TargetCalendar
import de.tergelius.ttsync.calendar.TargetCheck
import de.tergelius.ttsync.settings.CredentialStore
import de.tergelius.ttsync.settings.Credentials
import de.tergelius.ttsync.settings.SettingsRepository
import de.tergelius.ttsync.settings.SetupStep
import de.tergelius.ttsync.settings.SourceCalendar
import de.tergelius.ttsync.settings.StatusRepository
import de.tergelius.ttsync.settings.SyncInterval
import de.tergelius.ttsync.sync.RemovalReport
import de.tergelius.ttsync.sync.SyncEngine
import de.tergelius.ttsync.sync.SyncNotifier
import de.tergelius.ttsync.sync.SyncScheduler
import de.tergelius.ttsync.sync.SyncTrigger
import de.tergelius.ttsync.timetree.TimeTreeClient
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
 * - Before copies are deleted, scheduled work is cancelled or the selection is changed first, so a
 *   periodic run cannot re-create them in between (the engine's mutex serializes the rest).
 * - Every change that needs new copies ends with [SyncScheduler.runNow] `(full = true, SETUP)`.
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
        status.setRateLimitedUntil(nowMillis() + e.backoff.inWholeMilliseconds)
        throw e
      }
    credentials.save(Credentials(email = email, password = password, sessionId = sessionId))
    status.recordLoginSucceeded()
    val next = currentStep()
    if (next == SetupStep.DONE) scheduler.runNow(full = false, trigger = SyncTrigger.MANUAL)
    return next
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
   * (after the caller's confirm), newly selected ones start a FULL sync. The selection is saved
   * first, so a run that starts meanwhile no longer fetches the removed calendars.
   */
  suspend fun saveSources(selected: List<SourceCalendar>): SetupStep {
    require(selected.isNotEmpty()) { "select at least one calendar" }
    val change = sourceChange(selected)
    settings.setSources(selected)
    if (change.hasTarget) {
      if (change.removed.isNotEmpty()) {
        engine.deselect(change.removed.map { it.id }.toSet())
        change.removed.forEach { notifier.clearAccessLost(it.id) }
      }
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
   * mappings cleared, and only then is the new target saved.
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
      when (old.check(gateway.calendar(old.calendarId))) {
        TargetCheck.OK -> removal = engine.removeAllCopies(old)
        // The calendar and its copies are gone: only the mappings are left to clear.
        TargetCheck.MISSING -> engine.dropMappings()
        // The old calendar can no longer be written safely: leave its rows alone.
        else -> {
          engine.dropMappings()
          oldCopiesLeft = true
        }
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
   * "Remove copies" for TimeTree calendars that lost access (Status): drops them from the
   * selection, then deletes their copies. With no calendar left the periodic work is cancelled.
   */
  suspend fun removePaused(ids: Set<Long>): RemovalReport {
    val current = settings.current()
    val remaining = current.sources.filter { it.id !in ids }
    settings.setSources(remaining)
    if (remaining.isEmpty()) scheduler.cancelAll()
    val report = engine.deselect(ids)
    ids.forEach(notifier::clearAccessLost)
    return report
  }

  /**
   * "Remove all copies" (Settings): stops syncing, deletes every marked row in the target and
   * clears the selection, so setup continues at Sources. The target and sign-in are kept.
   */
  suspend fun removeAllCopies(): RemovalReport {
    val current = settings.current()
    scheduler.cancelAll()
    val target = current.target
    val report =
      if (
        target != null && target.check(gateway.calendar(target.calendarId)) != TargetCheck.MISSING
      ) {
        engine.removeAllCopies(target)
      } else {
        engine.dropMappings()
        RemovalReport(deleted = 0, rejected = 0)
      }
    val ids = current.sources.map { it.id }.toSet()
    if (ids.isNotEmpty()) engine.deselect(ids)
    settings.setSources(emptyList())
    notifier.clearFailure()
    ids.forEach(notifier::clearAccessLost)
    return report
  }

  /**
   * Sign-out (DESIGN.md section 6). Optionally removes the copies first; if that fails, nothing
   * else happens and the error is thrown (the user can retry or keep the copies). Then work is
   * cancelled and everything is wiped: credentials and key, settings, status, `SyncDb` and the
   * debug dumps. The wipe is best effort: every step runs, and the first failure is thrown at the
   * end.
   */
  suspend fun signOut(removeCopies: Boolean): RemovalReport? {
    val current = settings.current()
    scheduler.cancelAll()
    val removal =
      if (removeCopies && current.target != null) {
        val target = current.target
        if (target.check(gateway.calendar(target.calendarId)) == TargetCheck.MISSING) null
        else engine.removeAllCopies(target)
      } else {
        null
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
}

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
)
