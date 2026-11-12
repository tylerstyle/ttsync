package de.tergelius.ttsync.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.tergelius.ttsync.R
import de.tergelius.ttsync.calendar.TargetCalendar
import de.tergelius.ttsync.settings.Settings
import de.tergelius.ttsync.settings.SetupStep
import de.tergelius.ttsync.settings.SourceCalendar
import de.tergelius.ttsync.settings.SyncInterval
import de.tergelius.ttsync.ui.LocalViewModelFactory
import de.tergelius.ttsync.ui.common.ConfirmDialog
import de.tergelius.ttsync.ui.common.ErrorBox
import de.tergelius.ttsync.ui.common.Hint
import de.tergelius.ttsync.ui.common.InfoDialog
import de.tergelius.ttsync.ui.common.LoadingBox
import de.tergelius.ttsync.ui.common.RowDivider
import de.tergelius.ttsync.ui.common.SectionCard
import de.tergelius.ttsync.ui.common.TtScaffold
import de.tergelius.ttsync.ui.theme.TTCalendarSyncTheme

/** Where the Settings rows lead. */
class SettingsNavigation(
  val back: () -> Unit,
  val sources: () -> Unit,
  val target: () -> Unit,
  val reliability: () -> Unit,
  val about: () -> Unit,
  /** After "Remove all copies": setup continues at this step. */
  val continueSetup: (SetupStep) -> Unit,
  val signedOut: () -> Unit,
)

/** Settings (DESIGN.md section 7.7). */
@Composable
fun SettingsScreen(
  navigation: SettingsNavigation,
  viewModel: SettingsViewModel = viewModel(factory = LocalViewModelFactory.current),
  signOutViewModel: SignOutViewModel = viewModel(factory = LocalViewModelFactory.current),
) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  val snackbar = remember { SnackbarHostState() }
  val resyncMessage = stringResource(R.string.settings_resync_started)
  LaunchedEffect(state.resyncStarted) {
    if (state.resyncStarted) {
      viewModel.onResyncShown()
      snackbar.showSnackbar(resyncMessage)
    }
  }
  LaunchedEffect(state.next) {
    state.next?.let {
      viewModel.onNavigated()
      navigation.continueSetup(it)
    }
  }
  SettingsContent(
    state = state,
    navigation = navigation,
    snackbar = snackbar,
    onInterval = viewModel::setInterval,
    onFullResync = viewModel::fullResync,
    onRemoveAll = viewModel::askRemoveAll,
    onSignOut = signOutViewModel::open,
  )
  SignOutDialogHost(viewModel = signOutViewModel, onSignedOut = navigation.signedOut)
  if (state.confirmRemoveAll) {
    ConfirmDialog(
      title = stringResource(R.string.settings_remove_all_title),
      text =
        stringResource(
          R.string.settings_remove_all_text,
          state.settings?.targetDisplayName ?: stringResource(R.string.status_target_unknown),
        ),
      confirmLabel = stringResource(R.string.action_remove_copies),
      onConfirm = viewModel::confirmRemoveAll,
      onDismiss = viewModel::dismissRemoveAll,
      busy = state.removing,
    )
  }
  state.removal?.let { removal ->
    InfoDialog(
      title = stringResource(R.string.settings_removed_title),
      text =
        pluralStringResource(R.plurals.settings_removed_text, removal.deleted, removal.deleted) +
          if (removal.rejected > 0) {
            "\n\n" +
              pluralStringResource(
                R.plurals.settings_removed_rejected,
                removal.rejected,
                removal.rejected,
              )
          } else {
            ""
          },
      onDismiss = viewModel::dismissRemoval,
    )
  }
}

@Composable
fun SettingsContent(
  state: SettingsUiState,
  navigation: SettingsNavigation,
  snackbar: SnackbarHostState?,
  onInterval: (SyncInterval) -> Unit,
  onFullResync: () -> Unit,
  onRemoveAll: () -> Unit,
  onSignOut: () -> Unit,
) {
  TtScaffold(
    title = stringResource(R.string.settings_title),
    onBack = navigation.back,
    snackbarHostState = snackbar,
  ) {
    val settings = state.settings
    if (settings == null) {
      LoadingBox()
      return@TtScaffold
    }
    SectionCard(title = stringResource(R.string.settings_interval_title)) {
      Column(modifier = Modifier.selectableGroup()) {
        SyncInterval.entries.forEach { interval ->
          IntervalRow(
            interval,
            selected = interval == settings.interval,
            onSelect = { onInterval(interval) },
          )
        }
      }
      Hint(stringResource(R.string.settings_interval_note))
    }
    SectionCard(title = stringResource(R.string.settings_copying_title)) {
      val resources = LocalResources.current
      ActionRow(
        title = stringResource(R.string.settings_sources),
        subtitle =
          settings.sources
            .joinToString(", ") {
              it.name.ifBlank { resources.getString(R.string.sources_unnamed, it.id) }
            }
            .ifBlank { stringResource(R.string.settings_none) },
        actionLabel = stringResource(R.string.action_change),
        onAction = navigation.sources,
      )
      RowDivider()
      ActionRow(
        title = stringResource(R.string.settings_target),
        subtitle =
          settings.targetDisplayName?.let { name ->
            settings.target?.let {
              stringResource(R.string.settings_target_value, name, it.accountName)
            } ?: name
          } ?: stringResource(R.string.settings_none),
        actionLabel = stringResource(R.string.action_change),
        onAction = navigation.target,
      )
    }
    SectionCard(title = stringResource(R.string.settings_maintenance_title)) {
      ActionRow(
        title = stringResource(R.string.reliability_title),
        subtitle = stringResource(R.string.settings_reliability_text),
        actionLabel = stringResource(R.string.action_open),
        onAction = navigation.reliability,
      )
      RowDivider()
      ActionRow(
        title = stringResource(R.string.settings_resync),
        subtitle = stringResource(R.string.settings_resync_text),
        actionLabel = stringResource(R.string.action_start),
        onAction = onFullResync,
        enabled = settings.target != null && settings.sources.isNotEmpty(),
      )
      RowDivider()
      ActionRow(
        title = stringResource(R.string.settings_remove_all),
        subtitle = stringResource(R.string.settings_remove_all_text_short),
        actionLabel = stringResource(R.string.action_remove),
        onAction = onRemoveAll,
        destructive = true,
        enabled = settings.target != null,
      )
    }
    SectionCard(title = stringResource(R.string.settings_account_title)) {
      ActionRow(
        title = stringResource(R.string.action_sign_out),
        subtitle = stringResource(R.string.settings_sign_out_text),
        actionLabel = stringResource(R.string.action_sign_out),
        onAction = onSignOut,
        destructive = true,
      )
      RowDivider()
      ActionRow(
        title = stringResource(R.string.about_title),
        subtitle = stringResource(R.string.settings_about_text),
        actionLabel = stringResource(R.string.action_open),
        onAction = navigation.about,
      )
    }
    if (state.failed) ErrorBox(stringResource(R.string.error_unknown), onRetry = null)
  }
}

@Composable
private fun IntervalRow(interval: SyncInterval, selected: Boolean, onSelect: () -> Unit) {
  val hours = interval.duration.inWholeHours.toInt()
  Row(
    modifier =
      Modifier.fillMaxWidth()
        .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
        .padding(vertical = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    RadioButton(selected = selected, onClick = null)
    Text(
      pluralStringResource(R.plurals.settings_interval_hours, hours, hours),
      modifier = Modifier.padding(start = 12.dp),
      style = MaterialTheme.typography.bodyLarge,
    )
  }
}

@Composable
private fun ActionRow(
  title: String,
  subtitle: String,
  actionLabel: String,
  onAction: () -> Unit,
  destructive: Boolean = false,
  enabled: Boolean = true,
) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
      Text(title, style = MaterialTheme.typography.titleSmall)
      Text(
        subtitle,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    OutlinedButton(
      onClick = onAction,
      enabled = enabled,
      colors =
        if (destructive)
          ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
        else ButtonDefaults.outlinedButtonColors(),
    ) {
      Text(actionLabel)
    }
  }
}

@Preview(showBackground = true)
@Composable
private fun SettingsPreview() {
  TTCalendarSyncTheme {
    SettingsContent(
      state =
        SettingsUiState(
          settings =
            Settings(
              sources = listOf(SourceCalendar(1, "Family")),
              target = TargetCalendar(15, "me@example.com", "com.google"),
              targetDisplayName = "TimeTree",
            )
        ),
      navigation = SettingsNavigation({}, {}, {}, {}, {}, {}, {}),
      snackbar = null,
      onInterval = {},
      onFullResync = {},
      onRemoveAll = {},
      onSignOut = {},
    )
  }
}
