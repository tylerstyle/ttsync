package de.tergelius.ttsync.ui.sources

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.tergelius.ttsync.R
import de.tergelius.ttsync.settings.SetupStep
import de.tergelius.ttsync.settings.SourceCalendar
import de.tergelius.ttsync.ui.LocalViewModelFactory
import de.tergelius.ttsync.ui.common.BottomActionBar
import de.tergelius.ttsync.ui.common.ConfirmDialog
import de.tergelius.ttsync.ui.common.ErrorBox
import de.tergelius.ttsync.ui.common.Hint
import de.tergelius.ttsync.ui.common.LoadingBox
import de.tergelius.ttsync.ui.common.RowDivider
import de.tergelius.ttsync.ui.common.SectionCard
import de.tergelius.ttsync.ui.common.TtScaffold
import de.tergelius.ttsync.ui.settings.SignOutAction
import de.tergelius.ttsync.ui.theme.TTCalendarSyncTheme

/**
 * Sources (DESIGN.md section 7.2). In [setup] mode "Next" continues setup; otherwise "Save" goes
 * back. [onSignInAgain] is offered when the stored sign-in no longer works.
 */
@Composable
fun SourcesScreen(
  setup: Boolean,
  onDone: (SetupStep) -> Unit,
  onBack: (() -> Unit)?,
  onSignInAgain: () -> Unit,
  onSignedOut: () -> Unit,
  viewModel: SourcesViewModel = viewModel(factory = LocalViewModelFactory.current),
) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  LaunchedEffect(state.next) {
    state.next?.let {
      viewModel.onNavigated()
      onDone(it)
    }
  }
  SourcesContent(
    state = state,
    setup = setup,
    onBack = onBack,
    onToggle = viewModel::toggle,
    onSave = viewModel::save,
    onRetry = viewModel::load,
    onSignInAgain = onSignInAgain,
    signOutAction = { if (setup) SignOutAction(onSignedOut = onSignedOut) },
  )
  state.confirmRemoval?.let { removed ->
    val resources = LocalResources.current
    ConfirmDialog(
      title = stringResource(R.string.sources_remove_title),
      text =
        stringResource(
          R.string.sources_remove_text,
          removed.joinToString(", ") {
            it.name.ifBlank { resources.getString(R.string.sources_unnamed, it.id) }
          },
        ),
      confirmLabel = stringResource(R.string.action_remove_copies),
      onConfirm = viewModel::confirmRemoval,
      onDismiss = viewModel::dismissRemoval,
    )
  }
}

@Composable
fun SourcesContent(
  state: SourcesUiState,
  setup: Boolean,
  onBack: (() -> Unit)?,
  onToggle: (Long) -> Unit,
  onSave: () -> Unit,
  onRetry: () -> Unit,
  onSignInAgain: () -> Unit,
  signOutAction: @Composable () -> Unit = {},
) {
  TtScaffold(
    title = stringResource(R.string.sources_title),
    onBack = onBack,
    actions = { signOutAction() },
    bottomBar = {
      BottomActionBar {
        Button(onClick = onSave, enabled = state.canSave) {
          Text(stringResource(if (setup) R.string.action_next else R.string.action_save))
        }
      }
    },
  ) {
    Text(stringResource(R.string.sources_intro), style = MaterialTheme.typography.bodyLarge)
    SectionCard(containerColor = MaterialTheme.colorScheme.secondaryContainer) {
      Text(
        stringResource(R.string.sources_owner_note),
        color = MaterialTheme.colorScheme.onSecondaryContainer,
      )
    }
    when {
      state.loading -> LoadingBox()
      state.loadError != null -> SourcesErrorBox(state.loadError, onRetry, onSignInAgain)
      state.options.isEmpty() -> Hint(stringResource(R.string.sources_empty))
      else ->
        SectionCard {
          state.options.forEachIndexed { index, option ->
            if (index > 0) RowDivider()
            SourceRow(option, onToggle = { onToggle(option.id) }, enabled = !state.saving)
          }
        }
    }
    state.saveError?.let { SourcesErrorBox(it, onRetry = null, onSignInAgain = onSignInAgain) }
    if (state.saving) LoadingBox()
  }
}

@Composable
private fun SourceRow(option: SourceOption, onToggle: () -> Unit, enabled: Boolean) {
  Row(
    modifier =
      Modifier.fillMaxWidth()
        .toggleable(
          value = option.selected,
          enabled = enabled,
          role = Role.Checkbox,
          onValueChange = { onToggle() },
        )
        .padding(vertical = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Checkbox(checked = option.selected, onCheckedChange = null, enabled = enabled)
    Column(modifier = Modifier.padding(start = 12.dp)) {
      Text(
        option.name ?: stringResource(R.string.sources_unnamed, option.id),
        style = MaterialTheme.typography.bodyLarge,
      )
      if (!option.available) {
        Text(
          stringResource(R.string.sources_unavailable),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.error,
        )
      }
    }
  }
}

@Composable
private fun SourcesErrorBox(
  error: SourcesError,
  onRetry: (() -> Unit)?,
  onSignInAgain: () -> Unit,
) {
  val message =
    when (error) {
      SourcesError.SIGN_IN -> stringResource(R.string.sources_error_sign_in)
      SourcesError.RATE_LIMITED -> stringResource(R.string.error_rate_limited)
      SourcesError.OFFLINE -> stringResource(R.string.error_offline)
      SourcesError.API_CHANGED -> stringResource(R.string.error_api_changed)
      SourcesError.UNKNOWN -> stringResource(R.string.error_unknown)
    }
  if (error == SourcesError.SIGN_IN) {
    ErrorBox(message = message, onRetry = null)
    Button(onClick = onSignInAgain) { Text(stringResource(R.string.action_sign_in_again)) }
  } else {
    ErrorBox(message = message, onRetry = onRetry)
  }
}

@Preview(showBackground = true)
@Composable
private fun SourcesPreview() {
  TTCalendarSyncTheme {
    SourcesContent(
      state =
        SourcesUiState(
          loading = false,
          options =
            SourcesViewModel.optionsOf(
              calendars = emptyList(),
              selected = listOf(SourceCalendar(1, "Family"), SourceCalendar(2, "Football")),
            ),
        ),
      setup = true,
      onBack = null,
      onToggle = {},
      onSave = {},
      onRetry = {},
      onSignInAgain = {},
    )
  }
}
