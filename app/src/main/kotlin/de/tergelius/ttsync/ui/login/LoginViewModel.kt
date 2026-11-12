package de.tergelius.ttsync.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.tergelius.ttsync.settings.SetupStep
import de.tergelius.ttsync.timetree.TtError
import de.tergelius.ttsync.ui.UserActions
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Login errors shown on the screen (DESIGN.md section 7.1). */
enum class LoginError {
  /** `-702`: wrong email or password, or a temporary block. */
  REJECTED,
  RATE_LIMITED,
  OFFLINE,

  /** The answer did not look like the API this app knows: update the app. */
  API_CHANGED,
  UNKNOWN,
}

data class LoginUiState(
  val email: String = "",
  val password: String = "",
  val passwordVisible: Boolean = false,
  val busy: Boolean = false,
  val error: LoginError? = null,
  /** For [LoginError.RATE_LIMITED]: wait at least this many minutes. */
  val retryAfterMinutes: Long? = null,
  /** Set once signed in: the setup step to show next. */
  val next: SetupStep? = null,
) {
  val canSubmit: Boolean
    get() = email.isNotBlank() && password.isNotEmpty() && !busy

  /** Never let the password reach logs through a state dump. */
  override fun toString(): String =
    "LoginUiState(busy=$busy, error=$error, next=$next, visible=$passwordVisible)"
}

/**
 * Login screen. The password lives only in this ViewModel's memory (no SavedStateHandle) until it
 * goes to the encrypted credential store.
 */
class LoginViewModel(private val actions: UserActions) : ViewModel() {
  private val _state = MutableStateFlow(LoginUiState())
  val state: StateFlow<LoginUiState> = _state.asStateFlow()

  fun onEmailChange(email: String) = _state.update { it.copy(email = email, error = null) }

  fun onPasswordChange(password: String) = _state.update {
    it.copy(password = password, error = null)
  }

  fun togglePasswordVisible() = _state.update { it.copy(passwordVisible = !it.passwordVisible) }

  fun submit() {
    val current = _state.value
    if (!current.canSubmit) return
    _state.update { it.copy(busy = true, error = null, retryAfterMinutes = null) }
    viewModelScope.launch {
      try {
        val next = actions.signIn(current.email.trim(), current.password)
        _state.update { it.copy(busy = false, password = "", next = next) }
      } catch (e: CancellationException) {
        throw e
      } catch (e: TtError.RateLimited) {
        _state.update {
          it.copy(
            busy = false,
            error = LoginError.RATE_LIMITED,
            retryAfterMinutes = e.backoff.inWholeMinutes,
          )
        }
      } catch (e: Exception) {
        _state.update { it.copy(busy = false, error = errorOf(e)) }
      }
    }
  }

  fun onNavigated() = _state.update { it.copy(next = null) }

  companion object {
    fun errorOf(e: Exception): LoginError =
      when (e) {
        is TtError.AuthRejected,
        is TtError.LoginRequired -> LoginError.REJECTED
        is TtError.RateLimited -> LoginError.RATE_LIMITED
        is TtError.Transient -> LoginError.OFFLINE
        is TtError.ApiChanged -> LoginError.API_CHANGED
        else -> LoginError.UNKNOWN
      }
  }
}
