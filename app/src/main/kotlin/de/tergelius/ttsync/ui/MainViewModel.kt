package de.tergelius.ttsync.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.tergelius.ttsync.settings.SetupStep
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Activity-scoped: decides the start screen, which follows setup progress (DESIGN.md section 7):
 * Login → Sources → Target → Keep running → Status.
 */
class MainViewModel(private val actions: UserActions) : ViewModel() {
  private val _startStep = MutableStateFlow<SetupStep?>(null)

  /** Null while loading. */
  val startStep: StateFlow<SetupStep?> = _startStep.asStateFlow()

  init {
    viewModelScope.launch {
      _startStep.value =
        try {
          actions.currentStep()
        } catch (e: CancellationException) {
          throw e
        } catch (_: Exception) {
          // Unreadable settings: start over at Login, which never deletes anything.
          SetupStep.LOGIN
        }
    }
  }
}
