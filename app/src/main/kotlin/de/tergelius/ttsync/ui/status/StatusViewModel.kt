package de.tergelius.ttsync.ui.status

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.tergelius.ttsync.calendar.CalendarGateway
import de.tergelius.ttsync.calendar.GoogleSyncState
import de.tergelius.ttsync.calendar.TargetCheck
import de.tergelius.ttsync.settings.Settings
import de.tergelius.ttsync.settings.SettingsRepository
import de.tergelius.ttsync.settings.SetupStep
import de.tergelius.ttsync.settings.SourceCalendar
import de.tergelius.ttsync.settings.StatusRepository
import de.tergelius.ttsync.settings.SyncHealth
import de.tergelius.ttsync.settings.SyncStatus
import de.tergelius.ttsync.store.CalendarState
import de.tergelius.ttsync.sync.ScheduleState
import de.tergelius.ttsync.sync.SyncProblem
import de.tergelius.ttsync.sync.SyncReport
import de.tergelius.ttsync.sync.SyncScheduler
import de.tergelius.ttsync.ui.DeviceChecks
import de.tergelius.ttsync.ui.DeviceState
import de.tergelius.ttsync.ui.SyncNowResult
import de.tergelius.ttsync.ui.UserActions
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Checks Status makes itself, re-read on every resume. */
data class LiveChecks(
  val device: DeviceState? = null,
  /** The stored target against the provider; null without a target or calendar permission. */
  val targetCheck: TargetCheck? = null,
  /** The target account's sync switches; null if unknown. */
  val googleSync: GoogleSyncState? = null,
)

/** Warnings that do not stop syncing but deserve a fix button (DESIGN.md section 7.5). */
enum class StatusWarning {
  /** "Google sync is off, copies stay on this phone." */
  GOOGLE_SYNC_OFF,

  /** "N changes not uploaded to Google yet" ([StatusUiState.notUploaded], device test B2). */
  NOT_UPLOADED,
  BATTERY_RESTRICTED,
  BACKGROUND_RESTRICTED,
  NOTIFICATIONS_OFF,
}

/**
 * One-off note: "Sync now" needed no run, the last one succeeded [secondsAgo] s ago. Each tap gets
 * a new [id], so two taps with the same text still show two snackbars, and clearing an old note
 * never clears a newer one.
 */
data class SyncedNote(val id: Long, val secondsAgo: Long)

data class StatusUiState(
  val loading: Boolean = true,
  val setupStep: SetupStep = SetupStep.DONE,
  val health: SyncHealth = SyncHealth.OK,
  /** What to fix: live checks first, then the last run's failure (if still relevant). */
  val problem: SyncProblem? = null,
  val failureCode: Int? = null,
  val lastSuccessAt: Long? = null,
  /** The last success is more than 24 h ago: shown in red. */
  val stale: Boolean = false,
  val lastReport: SyncReport? = null,
  val consecutiveFailures: Int = 0,
  val nextRunAt: Long? = null,
  val periodicScheduled: Boolean = false,
  val running: Boolean = false,
  val runNowPending: Boolean = false,
  val rateLimitedUntil: Long? = null,
  val warnings: List<StatusWarning> = emptyList(),
  /** Selected TimeTree calendars that lost access (their copies are kept). */
  val pausedCalendars: List<SourceCalendar> = emptyList(),
  val sources: List<SourceCalendar> = emptyList(),
  val targetName: String? = null,
  val notificationPermissionMissing: Boolean = false,
  val nowMillis: Long = 0,
  /** Copies still waiting for Google's sync 30 min after they were written (last report). */
  val notUploaded: Int = 0,
  // Dialogs and one-off actions
  val confirmRemovePaused: Boolean = false,
  val removingPaused: Boolean = false,
  val actionFailed: Boolean = false,
  /** "Remove copies" of paused calendars failed; they stay selected. */
  val removalFailed: Boolean = false,
  /** One-off "Synced N s ago" snackbar, until [StatusViewModel.onSyncedShown]. */
  val syncedNote: SyncedNote? = null,
) {
  /** "Sync now" is pointless while a run is going on or about to start. */
  val canSyncNow: Boolean
    get() = setupStep == SetupStep.DONE && !running && !runNowPending

  companion object {
    /**
     * Combines the stored status with the schedule and the live checks. Live checks win: a missing
     * calendar permission or a deleted target shows at once, without waiting for a run, and a
     * stored failure that a live check shows as fixed (permission granted again, a new target
     * chosen, setup finished) no longer counts.
     */
    fun derive(
      settings: Settings,
      status: SyncStatus,
      schedule: ScheduleState,
      live: LiveChecks,
      setupStep: SetupStep,
      nowMillis: Long,
    ): StatusUiState {
      val device = live.device
      val report = status.lastReport
      val reportProblem = report?.failure?.problem
      val resolved =
        when (reportProblem) {
          SyncProblem.NOT_CONFIGURED -> setupStep == SetupStep.DONE
          SyncProblem.NO_CALENDAR_PERMISSION -> device?.calendarPermission == true
          SyncProblem.TARGET_DELETED,
          SyncProblem.TARGET_UNAVAILABLE -> live.targetCheck == TargetCheck.OK
          else -> false
        }
      val effective =
        if (resolved && report != null) status.copy(lastReport = report.copy(failure = null))
        else status
      val liveProblem =
        when {
          settings.target == null -> null
          device != null && !device.calendarPermission -> SyncProblem.NO_CALENDAR_PERMISSION
          live.targetCheck == TargetCheck.MISSING -> SyncProblem.TARGET_DELETED
          live.targetCheck != null && live.targetCheck != TargetCheck.OK ->
            SyncProblem.TARGET_UNAVAILABLE
          else -> null
        }
      val problem = liveProblem ?: effective.lastReport?.failure?.problem
      val health =
        when {
          schedule.running -> SyncHealth.RUNNING
          liveProblem == SyncProblem.TARGET_DELETED -> SyncHealth.TARGET_DELETED
          liveProblem != null -> SyncHealth.NEEDS_ATTENTION
          else -> effective.health(running = false, nowMillis = nowMillis)
        }
      // Known to be off only: an account Android hides from the app counts as unknown (B2).
      val googleSyncOff =
        live.googleSync?.let { !it.uploads } ?: (report != null && status.googleSyncOff)
      val notUploaded = report?.notUploaded ?: 0
      val warnings = buildList {
        if (googleSyncOff) add(StatusWarning.GOOGLE_SYNC_OFF)
        else if (notUploaded > 0) add(StatusWarning.NOT_UPLOADED)
        if (device != null && !device.batteryUnrestricted) add(StatusWarning.BATTERY_RESTRICTED)
        if (device?.backgroundRestricted == true) add(StatusWarning.BACKGROUND_RESTRICTED)
        if (device != null && !device.notificationsEnabled) add(StatusWarning.NOTIFICATIONS_OFF)
      }
      val pausedIds =
        report?.calendars.orEmpty().filter { it.state == CalendarState.PAUSED }.map { it.ttCalId }
      return StatusUiState(
        loading = false,
        setupStep = setupStep,
        health = health,
        problem = problem,
        failureCode = if (liveProblem == null) effective.lastReport?.failure?.code else null,
        lastSuccessAt = status.lastSuccessAt,
        stale = status.isStale(nowMillis),
        lastReport = report,
        consecutiveFailures = status.consecutiveFailures,
        nextRunAt = schedule.nextRunAtMillis,
        periodicScheduled = schedule.periodicScheduled,
        running = schedule.running,
        runNowPending = schedule.runNowPending,
        rateLimitedUntil = status.rateLimitedUntil?.takeIf { it > nowMillis },
        warnings = warnings,
        pausedCalendars = settings.sources.filter { it.id in pausedIds },
        sources = settings.sources,
        targetName = settings.targetDisplayName,
        notificationPermissionMissing = device?.notificationPermissionMissing == true,
        nowMillis = nowMillis,
        notUploaded = notUploaded,
      )
    }

    /** Emits the time now and then every minute, so "5 minutes ago" stays current. */
    fun minuteTicker(nowMillis: () -> Long = System::currentTimeMillis): Flow<Long> = flow {
      while (true) {
        emit(nowMillis())
        delay(60_000)
      }
    }
  }
}

/** Status, the home screen (DESIGN.md section 7.5). */
class StatusViewModel(
  private val settings: SettingsRepository,
  status: StatusRepository,
  scheduler: SyncScheduler,
  private val gateway: CalendarGateway,
  private val deviceChecks: DeviceChecks,
  private val actions: UserActions,
  clock: Flow<Long> = StatusUiState.minuteTicker(),
) : ViewModel() {
  private val live = MutableStateFlow(LiveChecks())
  private val dialogs = MutableStateFlow(Dialogs())

  private data class Dialogs(
    val confirmRemovePaused: Boolean = false,
    val removingPaused: Boolean = false,
    val actionFailed: Boolean = false,
    val removalFailed: Boolean = false,
    val syncedNote: SyncedNote? = null,
  )

  private var lastNoteId = 0L

  private val stored =
    combine(settings.settings, status.status, scheduler.state) { s, st, sch -> Triple(s, st, sch) }

  val state: StateFlow<StatusUiState> =
    combine(stored, live, dialogs, clock) { (s, st, sch), liveChecks, d, now ->
        StatusUiState.derive(
            settings = s,
            status = st,
            schedule = sch,
            live = liveChecks,
            setupStep = SetupStep.of(signedIn = true, settings = s),
            nowMillis = now,
          )
          .copy(
            confirmRemovePaused = d.confirmRemovePaused,
            removingPaused = d.removingPaused,
            actionFailed = d.actionFailed,
            removalFailed = d.removalFailed,
            syncedNote = d.syncedNote,
          )
      }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatusUiState())

  /** Re-reads the live checks (screen resume, after a fix). */
  fun refresh() {
    viewModelScope.launch {
      val device =
        try {
          deviceChecks.read()
        } catch (e: CancellationException) {
          throw e
        } catch (_: Exception) {
          null
        }
      val target =
        try {
          settings.current().target
        } catch (e: CancellationException) {
          throw e
        } catch (_: Exception) {
          null
        }
      var targetCheck: TargetCheck? = null
      var googleSync: GoogleSyncState? = null
      if (target != null && device?.calendarPermission == true) {
        targetCheck = attempt { target.check(gateway.calendar(target.calendarId)) }
        if (targetCheck == TargetCheck.OK) googleSync = attempt { gateway.googleSyncState(target) }
      }
      live.value = LiveChecks(device = device, targetCheck = targetCheck, googleSync = googleSync)
    }
  }

  /**
   * "Sync now". Right after a successful run there is nothing to do: the screen says "Synced N s
   * ago" instead of queueing a delayed run (device test F4).
   */
  fun syncNow() {
    viewModelScope.launch {
      val result =
        try {
          actions.syncNow()
        } catch (e: CancellationException) {
          throw e
        } catch (_: Exception) {
          null
        }
      val note =
        (result as? SyncNowResult.RecentlySynced)?.let {
          SyncedNote(id = ++lastNoteId, secondsAgo = it.agoMillis / 1000)
        }
      dialogs.update {
        it.copy(actionFailed = result == null, removalFailed = false, syncedNote = note)
      }
    }
  }

  /**
   * The snackbar of note [id] is gone. The screen calls this only after `showSnackbar` returned:
   * clearing the note first changed the screen's effect key, and Compose cancelled the effect
   * before the snackbar showed (device retest R1). A newer note stays.
   */
  fun onSyncedShown(id: Long) = dialogs.update {
    if (it.syncedNote?.id == id) it.copy(syncedNote = null) else it
  }

  /** Calendar permission granted again from the Status fix button: sync right away. */
  fun onPermissionResult(granted: Boolean) {
    refresh()
    if (granted) syncNow()
  }

  fun askRemovePaused() = dialogs.update { it.copy(confirmRemovePaused = true) }

  fun dismissRemovePaused() = dialogs.update {
    if (it.removingPaused) it else it.copy(confirmRemovePaused = false)
  }

  fun confirmRemovePaused() {
    val ids = state.value.pausedCalendars.map { it.id }.toSet()
    if (ids.isEmpty()) {
      dialogs.update { it.copy(confirmRemovePaused = false) }
      return
    }
    dialogs.update { it.copy(removingPaused = true, actionFailed = false, removalFailed = false) }
    viewModelScope.launch {
      try {
        val report = actions.removePaused(ids)
        val failed = report.failure != null
        dialogs.update {
          it.copy(
            confirmRemovePaused = false,
            removingPaused = false,
            actionFailed = failed,
            removalFailed = failed,
          )
        }
      } catch (e: CancellationException) {
        throw e
      } catch (_: Exception) {
        dialogs.update {
          it.copy(confirmRemovePaused = false, removingPaused = false, actionFailed = true)
        }
      }
    }
  }

  fun dismissActionFailed() = dialogs.update { it.copy(actionFailed = false, removalFailed = false) }

  private inline fun <T> attempt(block: () -> T): T? =
    try {
      block()
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      null
    }
}
