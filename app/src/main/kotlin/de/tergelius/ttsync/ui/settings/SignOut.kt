package de.tergelius.ttsync.ui.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import de.tergelius.ttsync.R
import de.tergelius.ttsync.settings.SettingsRepository
import de.tergelius.ttsync.ui.LocalViewModelFactory
import de.tergelius.ttsync.ui.UserActions
import de.tergelius.ttsync.ui.common.ConfirmDialog
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SignOutUiState(
  val dialogOpen: Boolean = false,
  /** A target calendar is set, so there may be copies to remove. */
  val hasTarget: Boolean = false,
  val targetName: String? = null,
  val removeCopies: Boolean = true,
  val busy: Boolean = false,
  /** The removal or the wipe failed; the dialog stays open. */
  val failed: Boolean = false,
  val done: Boolean = false,
)

/**
 * Sign-out (DESIGN.md section 6): asks whether to remove the copies, then wipes everything. Used by
 * Settings and, during setup, by the "Sign out" action of the setup screens.
 */
class SignOutViewModel(
  private val settings: SettingsRepository,
  private val actions: UserActions,
) : ViewModel() {
  private val _state = MutableStateFlow(SignOutUiState())
  val state: StateFlow<SignOutUiState> = _state.asStateFlow()

  fun open() {
    viewModelScope.launch {
      val current =
        try {
          settings.current()
        } catch (e: CancellationException) {
          throw e
        } catch (_: Exception) {
          null
        }
      _state.update {
        it.copy(
          dialogOpen = true,
          hasTarget = current?.target != null,
          targetName = current?.targetDisplayName,
          removeCopies = true,
          failed = false,
        )
      }
    }
  }

  fun setRemoveCopies(remove: Boolean) = _state.update { it.copy(removeCopies = remove) }

  fun dismiss() = _state.update { if (it.busy) it else it.copy(dialogOpen = false, failed = false) }

  fun confirm() {
    val current = _state.value
    if (current.busy) return
    _state.update { it.copy(busy = true, failed = false) }
    viewModelScope.launch {
      try {
        val removal = actions.signOut(removeCopies = current.hasTarget && current.removeCopies)
        _state.update {
          // A failed removal keeps everything: still signed in, the dialog stays open.
          if (removal?.failure != null) it.copy(busy = false, failed = true)
          else it.copy(busy = false, dialogOpen = false, done = true)
        }
      } catch (e: CancellationException) {
        throw e
      } catch (_: Exception) {
        _state.update { it.copy(busy = false, failed = true) }
      }
    }
  }

  fun onNavigated() = _state.update { it.copy(done = false) }
}

/** A "Sign out" text button for a top app bar, with its dialog. */
@Composable
fun SignOutAction(
  onSignedOut: () -> Unit,
  viewModel: SignOutViewModel = viewModel(factory = LocalViewModelFactory.current),
) {
  TextButton(onClick = viewModel::open) { Text(stringResource(R.string.action_sign_out)) }
  SignOutDialogHost(viewModel = viewModel, onSignedOut = onSignedOut)
}

/** Shows the sign-out dialog while it is open and reports a finished sign-out. */
@Composable
fun SignOutDialogHost(viewModel: SignOutViewModel, onSignedOut: () -> Unit) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  LaunchedEffect(state.done) {
    if (state.done) {
      viewModel.onNavigated()
      onSignedOut()
    }
  }
  if (!state.dialogOpen) return
  ConfirmDialog(
    title = stringResource(R.string.sign_out_title),
    text =
      if (state.failed) {
        stringResource(
          if (state.hasTarget && state.removeCopies) R.string.sign_out_failed_removal
          else R.string.sign_out_failed
        )
      } else {
        stringResource(R.string.sign_out_text)
      },
    confirmLabel = stringResource(R.string.action_sign_out),
    onConfirm = viewModel::confirm,
    onDismiss = viewModel::dismiss,
    busy = state.busy,
    extra =
      if (state.hasTarget) {
        {
          Row(
            modifier =
              Modifier.fillMaxWidth()
                .toggleable(
                  value = state.removeCopies,
                  enabled = !state.busy,
                  role = Role.Checkbox,
                  onValueChange = viewModel::setRemoveCopies,
                )
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Checkbox(checked = state.removeCopies, onCheckedChange = null, enabled = !state.busy)
            Text(
              state.targetName?.let { stringResource(R.string.sign_out_remove_copies_from, it) }
                ?: stringResource(R.string.sign_out_remove_copies),
              style = MaterialTheme.typography.bodyMedium,
              modifier = Modifier.padding(start = 12.dp),
            )
          }
        }
      } else {
        null
      },
  )
}
