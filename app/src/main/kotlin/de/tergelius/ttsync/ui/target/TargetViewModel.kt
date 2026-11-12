package de.tergelius.ttsync.ui.target

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.tergelius.ttsync.calendar.CalendarGateway
import de.tergelius.ttsync.calendar.CalendarInfo
import de.tergelius.ttsync.calendar.TargetCalendar
import de.tergelius.ttsync.settings.SettingsRepository
import de.tergelius.ttsync.settings.SetupStep
import de.tergelius.ttsync.ui.DeviceChecks
import de.tergelius.ttsync.ui.TargetChange
import de.tergelius.ttsync.ui.UserActions
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One eligible calendar; [otherEvents] is null while unknown. */
data class TargetOption(val info: CalendarInfo, val otherEvents: Int?) {
  override fun toString(): String = "TargetOption(id=${info.id}, otherEvents=$otherEvents)"
}

/** Asks before switching: the copies in [oldName] are deleted. */
data class TargetSwitch(val oldName: String?, val newName: String)

data class TargetUiState(
  /** Null until the first permission check. */
  val permission: Boolean? = null,
  val loading: Boolean = false,
  val options: List<TargetOption> = emptyList(),
  val selectedId: Long? = null,
  /** The stored target, if any. */
  val current: TargetCalendar? = null,
  val currentName: String? = null,
  val loadFailed: Boolean = false,
  val saving: Boolean = false,
  val saveFailed: Boolean = false,
  val confirmSwitch: TargetSwitch? = null,
  /** Shown after a switch, before navigating on. */
  val switchResult: TargetChange? = null,
  val next: SetupStep? = null,
) {
  val selected: TargetOption?
    get() = options.firstOrNull { it.info.id == selectedId }

  val canConfirm: Boolean
    get() = permission == true && !loading && !saving && selected != null
}

/**
 * Target (DESIGN.md section 7.3): writable, synced Google calendars. Confirming starts the first
 * FULL sync and the periodic work ([UserActions.setTarget]).
 */
class TargetViewModel(
  private val gateway: CalendarGateway,
  private val settings: SettingsRepository,
  private val deviceChecks: DeviceChecks,
  private val actions: UserActions,
) : ViewModel() {
  private val _state = MutableStateFlow(TargetUiState())
  val state: StateFlow<TargetUiState> = _state.asStateFlow()

  private var refreshJob: Job? = null

  /** Re-checks the permission and reloads the list (screen resume, Refresh, after the request). */
  fun refresh() {
    refreshJob?.cancel()
    refreshJob = viewModelScope.launch {
      val permission =
        try {
          deviceChecks.read().calendarPermission
        } catch (e: CancellationException) {
          throw e
        } catch (_: Exception) {
          false
        }
      _state.update { it.copy(permission = permission) }
      if (permission) load()
    }
  }

  private suspend fun load() {
    _state.update { it.copy(loading = true, loadFailed = false) }
    try {
      val stored = settings.current()
      val eligible = eligible(gateway.calendars())
      _state.update { state ->
        val keep = state.selectedId?.takeIf { id -> eligible.any { it.id == id } }
        state.copy(
          loading = false,
          options = eligible.map { info -> TargetOption(info, otherEvents = null) },
          current = stored.target,
          currentName = stored.targetDisplayName,
          selectedId =
            keep ?: stored.target?.calendarId?.takeIf { id -> eligible.any { it.id == id } },
        )
      }
      for (info in eligible) {
        val count =
          try {
            gateway.countForeignEvents(info.id)
          } catch (e: CancellationException) {
            throw e
          } catch (_: Exception) {
            null
          }
        _state.update { state ->
          state.copy(
            options =
              state.options.map { if (it.info.id == info.id) it.copy(otherEvents = count) else it }
          )
        }
      }
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      _state.update { it.copy(loading = false, loadFailed = true) }
    }
  }

  fun select(id: Long) = _state.update { it.copy(selectedId = id, saveFailed = false) }

  /** Confirms the selection, after asking if an existing target would be replaced. */
  fun confirm() {
    val state = _state.value
    val option = state.selected ?: return
    if (!state.canConfirm) return
    val current = state.current
    if (current != null && current != TargetCalendar.of(option.info)) {
      _state.update {
        it.copy(
          confirmSwitch =
            TargetSwitch(oldName = state.currentName, newName = option.info.displayName)
        )
      }
    } else {
      apply(option.info, showResult = false)
    }
  }

  fun confirmSwitch() {
    val option = _state.value.selected ?: return
    _state.update { it.copy(confirmSwitch = null) }
    apply(option.info, showResult = true)
  }

  fun dismissSwitch() = _state.update { it.copy(confirmSwitch = null) }

  /** The user read the switch result: move on. */
  fun dismissResult() = _state.update { it.copy(switchResult = null, next = it.switchResult?.next) }

  fun onNavigated() = _state.update { it.copy(next = null) }

  private fun apply(info: CalendarInfo, showResult: Boolean) {
    _state.update { it.copy(saving = true, saveFailed = false) }
    viewModelScope.launch {
      try {
        val change = actions.setTarget(info)
        _state.update {
          if (showResult) it.copy(saving = false, switchResult = change)
          else it.copy(saving = false, next = change.next)
        }
      } catch (e: CancellationException) {
        throw e
      } catch (_: Exception) {
        _state.update { it.copy(saving = false, saveFailed = true) }
      }
    }
  }

  companion object {
    /** `com.google` calendars with access ≥ 500 and `SYNC_EVENTS=1`, by account, then name. */
    fun eligible(calendars: List<CalendarInfo>): List<CalendarInfo> =
      calendars
        .filter { it.isEligibleTarget }
        .sortedWith(compareBy({ it.accountName.lowercase() }, { it.displayName.lowercase() }))
  }
}
