package de.tergelius.ttsync.ui.sources

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.tergelius.ttsync.R
import de.tergelius.ttsync.mapping.EventFilter
import de.tergelius.ttsync.settings.SetupStep
import de.tergelius.ttsync.settings.SourceCalendar
import de.tergelius.ttsync.timetree.TtCalendar
import de.tergelius.ttsync.ui.LocalViewModelFactory
import de.tergelius.ttsync.ui.common.BottomActionBar
import de.tergelius.ttsync.ui.common.ConfirmDialog
import de.tergelius.ttsync.ui.common.ErrorBox
import de.tergelius.ttsync.ui.common.Hint
import de.tergelius.ttsync.ui.common.LoadingBox
import de.tergelius.ttsync.ui.common.RowDivider
import de.tergelius.ttsync.ui.common.SectionCard
import de.tergelius.ttsync.ui.common.TtScaffold
import de.tergelius.ttsync.ui.common.wrapBetweenItems
import de.tergelius.ttsync.ui.settings.SignOutAction
import de.tergelius.ttsync.ui.theme.TTCalendarSyncTheme

/**
 * Sources (DESIGN.md section 7.2). In [setup] mode "Next" continues setup; otherwise "Save" goes
 * back. [onSignInAgain] is offered when the stored sign-in no longer works. Each selected calendar
 * has an "Events: …" line that opens its filter editor in place of the list (back returns).
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
  LifecycleResumeEffect(viewModel) {
    viewModel.onResume()
    onPauseOrDispose {}
  }
  LaunchedEffect(state.next) {
    state.next?.let {
      viewModel.onNavigated()
      onDone(it)
    }
  }
  val editor = state.editor
  if (editor != null) {
    BackHandler(onBack = viewModel::closeFilter)
    FilterEditorContent(
      state = editor,
      actions =
        FilterEditorActions(
          toggleLabel = viewModel::toggleLabel,
          toggleMember = viewModel::toggleMember,
          includeText = viewModel::setIncludeText,
          excludeText = viewModel::setExcludeText,
          clear = viewModel::clearFilter,
          done = viewModel::closeFilter,
        ),
    )
  } else {
    SourcesContent(
      state = state,
      setup = setup,
      onBack = onBack,
      onToggle = viewModel::toggle,
      onEditFilter = viewModel::openFilter,
      onSave = viewModel::save,
      onRetry = viewModel::load,
      onSignInAgain = onSignInAgain,
      signOutAction = { if (setup) SignOutAction(onSignedOut = onSignedOut) },
    )
  }
  state.confirm?.let { confirm ->
    ConfirmDialog(
      title = stringResource(R.string.sources_remove_title),
      text = confirmText(confirm),
      confirmLabel = stringResource(R.string.action_remove_copies),
      onConfirm = viewModel::confirmRemoval,
      onDismiss = viewModel::dismissRemoval,
    )
  }
}

/** The removal question: deselected calendars, then copies a narrower filter no longer matches. */
@Composable
private fun confirmText(confirm: SourcesConfirm): String {
  val resources = LocalResources.current
  val target = confirm.targetName ?: stringResource(R.string.status_target_unknown)
  val parts = buildList {
    if (confirm.removed.isNotEmpty()) {
      add(
        stringResource(
          R.string.sources_remove_calendars,
          confirm.removed.joinToString(", ") {
            it.name.ifBlank { resources.getString(R.string.sources_unnamed, it.id) }
          },
          target,
        )
      )
    }
    if (confirm.filteredCopies > 0) {
      add(
        pluralStringResource(
          R.plurals.sources_remove_filtered,
          confirm.filteredCopies,
          confirm.filteredCopies,
          target,
        )
      )
    }
  }
  return parts.joinToString("\n\n") + " " + stringResource(R.string.sources_remove_untouched)
}

@Composable
fun SourcesContent(
  state: SourcesUiState,
  setup: Boolean,
  onBack: (() -> Unit)?,
  onToggle: (Long) -> Unit,
  onEditFilter: (Long) -> Unit,
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
            SourceRow(
              option,
              onToggle = { onToggle(option.id) },
              onEditFilter = { onEditFilter(option.id) },
              enabled = !state.saving,
            )
          }
        }
    }
    state.saveError?.let { SourcesErrorBox(it, onRetry = null, onSignInAgain = onSignInAgain) }
    if (state.saving) LoadingBox()
  }
}

@Composable
private fun SourceRow(
  option: SourceOption,
  onToggle: () -> Unit,
  onEditFilter: () -> Unit,
  enabled: Boolean,
) {
  Column {
    SourceCheckRow(option, onToggle, enabled)
    if (option.canFilter) FilterLine(option.filter, onEditFilter, enabled)
  }
}

/**
 * "Events: All ›", right under the calendar's name like a subtitle (device run 5, L3): it opens the
 * calendar's filter editor. The whole width is the touch target, and Compose routes touches close
 * to a target this small to it.
 */
@Composable
private fun FilterLine(filter: EventFilter?, onClick: () -> Unit, enabled: Boolean) {
  val resources = LocalResources.current
  Row(
    modifier =
      Modifier.fillMaxWidth()
        .heightIn(min = 32.dp)
        .clickable(
          enabled = enabled,
          onClickLabel = stringResource(R.string.sources_filter_open),
          role = Role.Button,
          onClick = onClick,
        )
        // Lines up with the calendar name: checkbox (24 dp) plus the name's padding (12 dp).
        .padding(start = 36.dp, bottom = 8.dp),
    verticalAlignment = Alignment.Top,
  ) {
    Text(
      wrapBetweenItems(
        stringResource(R.string.sources_filter_line, filterSummaryText(filter, resources))
      ),
      modifier = Modifier.weight(1f),
      style = MaterialTheme.typography.bodyMedium,
      color =
        if (enabled) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Icon(
      painterResource(R.drawable.ic_chevron_right),
      contentDescription = null,
      tint = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.size(20.dp),
    )
  }
}

@Composable
private fun SourceCheckRow(option: SourceOption, onToggle: () -> Unit, enabled: Boolean) {
  Row(
    modifier =
      Modifier.fillMaxWidth()
        .heightIn(min = 48.dp)
        .toggleable(
          value = option.selected,
          enabled = enabled,
          role = Role.Checkbox,
          onValueChange = { onToggle() },
        )
        // With an "Events: …" line below, the name moves down towards it (L3).
        .then(
          if (option.canFilter) Modifier.padding(top = 8.dp)
          else Modifier.padding(vertical = 4.dp)
        ),
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
      SourcesError.REMOVAL_FAILED -> stringResource(R.string.error_removal_failed)
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
              calendars = listOf(TtCalendar(1, "Family"), TtCalendar(2, "Football")),
              selected =
                listOf(
                  SourceCalendar(1, "Family", EventFilter(labelIds = setOf(1, 2))),
                  SourceCalendar(2, "Football"),
                ),
            ),
        ),
      setup = true,
      onBack = null,
      onToggle = {},
      onEditFilter = {},
      onSave = {},
      onRetry = {},
      onSignInAgain = {},
    )
  }
}
