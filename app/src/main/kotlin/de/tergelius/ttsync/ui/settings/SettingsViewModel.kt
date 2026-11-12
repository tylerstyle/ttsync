package de.tergelius.ttsync.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.tergelius.ttsync.settings.Settings
import de.tergelius.ttsync.settings.SettingsRepository
import de.tergelius.ttsync.settings.SetupStep
import de.tergelius.ttsync.settings.SyncInterval
import de.tergelius.ttsync.sync.RemovalReport
import de.tergelius.ttsync.ui.UserActions
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
  /** Null while loading. */
  val settings: Settings? = null,
  val confirmRemoveAll: Boolean = false,
  val removing: Boolean = false,
  /** Shown after "Remove all copies"; dismissing it continues at [next]. */
  val removal: RemovalReport? = null,
  val failed: Boolean = false,
  /** "Remove all copies" failed; nothing was changed. Shown instead of the generic error. */
  val removalFailed: Boolean = false,
  /** One-off: show "Full resync started". */
  val resyncStarted: Boolean = false,
  val next: SetupStep? = null,
)

/** Settings (DESIGN.md section 7.7). Sign-out lives in [SignOutViewModel]. */
class SettingsViewModel(
  settings: SettingsRepository,
  private val actions: UserActions,
) : ViewModel() {
  private val ui = MutableStateFlow(SettingsUiState())

  val state: StateFlow<SettingsUiState> =
    combine(settings.settings, ui) { s, u -> u.copy(settings = s) }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

  fun setInterval(interval: SyncInterval) {
    viewModelScope.launch {
      try {
        actions.setInterval(interval)
      } catch (e: CancellationException) {
        throw e
      } catch (_: Exception) {
        ui.update { it.copy(failed = true) }
      }
    }
  }

  fun fullResync() {
    val ok = runCatching { actions.fullResync() }.isSuccess
    ui.update { it.copy(resyncStarted = ok, failed = !ok) }
  }

  fun onResyncShown() = ui.update { it.copy(resyncStarted = false) }

  fun askRemoveAll() = ui.update {
    it.copy(confirmRemoveAll = true, failed = false, removalFailed = false)
  }

  fun dismissRemoveAll() = ui.update { if (it.removing) it else it.copy(confirmRemoveAll = false) }

  /** "Remove all copies": stops syncing and deletes every copy ([UserActions.removeAllCopies]). */
  fun confirmRemoveAll() {
    if (ui.value.removing) return
    ui.update { it.copy(removing = true, failed = false, removalFailed = false) }
    viewModelScope.launch {
      try {
        val report = actions.removeAllCopies()
        ui.update {
          if (report.failure != null) {
            it.copy(removing = false, confirmRemoveAll = false, failed = true, removalFailed = true)
          } else {
            it.copy(removing = false, confirmRemoveAll = false, removal = report)
          }
        }
      } catch (e: CancellationException) {
        throw e
      } catch (_: Exception) {
        ui.update { it.copy(removing = false, confirmRemoveAll = false, failed = true) }
      }
    }
  }

  /** The removal result was read: continue setup (the selection is now empty). */
  fun dismissRemoval() {
    viewModelScope.launch {
      val next =
        try {
          actions.currentStep()
        } catch (e: CancellationException) {
          throw e
        } catch (_: Exception) {
          SetupStep.SOURCES
        }
      ui.update { it.copy(removal = null, next = next) }
    }
  }

  fun dismissFailed() = ui.update { it.copy(failed = false, removalFailed = false) }

  fun onNavigated() = ui.update { it.copy(next = null) }
}
