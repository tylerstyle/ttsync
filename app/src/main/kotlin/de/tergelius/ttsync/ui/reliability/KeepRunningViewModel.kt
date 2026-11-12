package de.tergelius.ttsync.ui.reliability

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.tergelius.ttsync.settings.SetupStep
import de.tergelius.ttsync.ui.DeviceChecks
import de.tergelius.ttsync.ui.DeviceState
import de.tergelius.ttsync.ui.UserActions
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class KeepRunningUiState(
  /** Null until read. */
  val device: DeviceState? = null,
  val saving: Boolean = false,
  val failed: Boolean = false,
  val next: SetupStep? = null,
) {
  /** Everything the step can check is done, so the button says "Continue", not "Skip". */
  val complete: Boolean
    get() = device?.batteryUnrestricted == true
}

/**
 * "Keep running" setup step (DESIGN.md section 7.4): battery unrestricted, and Autostart on the
 * Xiaomi family. The user may skip; Status then warns.
 */
class KeepRunningViewModel(
  private val deviceChecks: DeviceChecks,
  private val actions: UserActions,
) : ViewModel() {
  private val _state = MutableStateFlow(KeepRunningUiState())
  val state: StateFlow<KeepRunningUiState> = _state.asStateFlow()

  fun refresh() {
    viewModelScope.launch {
      try {
        val device = deviceChecks.read()
        _state.update { it.copy(device = device) }
      } catch (e: CancellationException) {
        throw e
      } catch (_: Exception) {
        _state.update { it.copy(device = it.device ?: DeviceState()) }
      }
    }
  }

  /** "Continue" or "Skip": both finish the step. */
  fun finish() {
    if (_state.value.saving) return
    _state.update { it.copy(saving = true, failed = false) }
    viewModelScope.launch {
      try {
        val next = actions.finishKeepRunning()
        _state.update { it.copy(saving = false, next = next) }
      } catch (e: CancellationException) {
        throw e
      } catch (_: Exception) {
        _state.update { it.copy(saving = false, failed = true) }
      }
    }
  }

  fun onNavigated() = _state.update { it.copy(next = null) }
}
