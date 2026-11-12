package de.tergelius.ttsync.ui.target

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
import de.tergelius.ttsync.calendar.CalendarInfo
import de.tergelius.ttsync.settings.SetupStep
import de.tergelius.ttsync.ui.LocalViewModelFactory
import de.tergelius.ttsync.ui.common.BottomActionBar
import de.tergelius.ttsync.ui.common.ConfirmDialog
import de.tergelius.ttsync.ui.common.ErrorBox
import de.tergelius.ttsync.ui.common.Hint
import de.tergelius.ttsync.ui.common.InfoDialog
import de.tergelius.ttsync.ui.common.LoadingBox
import de.tergelius.ttsync.ui.common.RowDivider
import de.tergelius.ttsync.ui.common.SectionCard
import de.tergelius.ttsync.ui.common.SystemIntents
import de.tergelius.ttsync.ui.common.TtScaffold
import de.tergelius.ttsync.ui.common.rememberCalendarPermissionRequest
import de.tergelius.ttsync.ui.settings.SignOutAction
import de.tergelius.ttsync.ui.theme.TTCalendarSyncTheme

/** Target (DESIGN.md section 7.3). Asks for the calendar permission first. */
@Composable
fun TargetScreen(
  setup: Boolean,
  onDone: (SetupStep) -> Unit,
  onBack: (() -> Unit)?,
  onSignedOut: () -> Unit,
  viewModel: TargetViewModel = viewModel(factory = LocalViewModelFactory.current),
) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  val context = LocalContext.current
  LifecycleResumeEffect(viewModel) {
    viewModel.refresh()
    onPauseOrDispose {}
  }
  LaunchedEffect(state.next) {
    state.next?.let {
      viewModel.onNavigated()
      onDone(it)
    }
  }
  val requestPermission = rememberCalendarPermissionRequest { viewModel.refresh() }
  TargetContent(
    state = state,
    setup = setup,
    onBack = onBack,
    onRequestPermission = requestPermission,
    onRefresh = viewModel::refresh,
    onSelect = viewModel::select,
    onConfirm = viewModel::confirm,
    onOpenSyncSettings = { SystemIntents.start(context, SystemIntents.syncSettings()) },
    signOutAction = { if (setup) SignOutAction(onSignedOut = onSignedOut) },
  )
  state.confirmSwitch?.let { switch ->
    ConfirmDialog(
      title = stringResource(R.string.target_switch_title),
      text =
        stringResource(
          R.string.target_switch_text,
          switch.oldName ?: stringResource(R.string.target_old_calendar),
          switch.newName,
        ),
      confirmLabel = stringResource(R.string.target_switch_confirm),
      onConfirm = viewModel::confirmSwitch,
      onDismiss = viewModel::dismissSwitch,
    )
  }
  state.switchResult?.let { result ->
    val newName = state.selected?.info?.displayName.orEmpty()
    InfoDialog(
      title = stringResource(R.string.target_switched_title),
      text =
        when {
          result.oldCopiesLeft -> stringResource(R.string.target_switched_left, newName)
          result.removal != null ->
            pluralStringResource(
              R.plurals.target_switched_removed,
              result.removal.deleted,
              result.removal.deleted,
              newName,
            )
          else -> stringResource(R.string.target_switched_plain, newName)
        },
      onDismiss = viewModel::dismissResult,
    )
  }
}

@Composable
fun TargetContent(
  state: TargetUiState,
  setup: Boolean,
  onBack: (() -> Unit)?,
  onRequestPermission: () -> Unit,
  onRefresh: () -> Unit,
  onSelect: (Long) -> Unit,
  onConfirm: () -> Unit,
  onOpenSyncSettings: () -> Unit,
  signOutAction: @Composable () -> Unit = {},
) {
  TtScaffold(
    title = stringResource(R.string.target_title),
    onBack = onBack,
    actions = {
      if (state.permission == true) {
        IconButton(onClick = onRefresh, enabled = !state.loading) {
          Icon(
            painterResource(R.drawable.ic_refresh),
            contentDescription = stringResource(R.string.action_refresh),
          )
        }
      }
      signOutAction()
    },
    bottomBar = {
      BottomActionBar {
        Button(onClick = onConfirm, enabled = state.canConfirm) {
          Text(stringResource(if (setup) R.string.target_use else R.string.action_save))
        }
      }
    },
  ) {
    Text(stringResource(R.string.target_intro), style = MaterialTheme.typography.bodyLarge)
    when (state.permission) {
      null -> LoadingBox()
      false ->
        SectionCard(title = stringResource(R.string.target_permission_title)) {
          Text(stringResource(R.string.target_permission_text))
          Button(onClick = onRequestPermission) {
            Text(stringResource(R.string.target_permission_button))
          }
        }
      true -> {
        SectionCard(containerColor = MaterialTheme.colorScheme.secondaryContainer) {
          Text(
            stringResource(R.string.target_recommend),
            color = MaterialTheme.colorScheme.onSecondaryContainer,
          )
        }
        when {
          state.loading && state.options.isEmpty() -> LoadingBox()
          state.loadFailed ->
            ErrorBox(stringResource(R.string.target_load_failed), onRetry = onRefresh)
          state.options.isEmpty() ->
            SectionCard(title = stringResource(R.string.target_empty_title)) {
              Text(stringResource(R.string.target_empty_text))
              OutlinedButton(onClick = onOpenSyncSettings) {
                Text(stringResource(R.string.action_open_sync_settings))
              }
            }
          else ->
            SectionCard {
              state.options.forEachIndexed { index, option ->
                if (index > 0) RowDivider()
                TargetRow(
                  option = option,
                  selected = option.info.id == state.selectedId,
                  enabled = !state.saving,
                  onSelect = { onSelect(option.info.id) },
                )
              }
            }
        }
        Hint(stringResource(R.string.target_untouched_note))
      }
    }
    if (state.saveFailed) ErrorBox(stringResource(R.string.target_save_failed), onRetry = null)
    if (state.saving) LoadingBox()
  }
}

@Composable
private fun TargetRow(
  option: TargetOption,
  selected: Boolean,
  enabled: Boolean,
  onSelect: () -> Unit,
) {
  Row(
    modifier =
      Modifier.fillMaxWidth()
        .selectable(
          selected = selected,
          enabled = enabled,
          role = Role.RadioButton,
          onClick = onSelect,
        )
        .padding(vertical = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    RadioButton(selected = selected, onClick = null, enabled = enabled)
    Box(
      modifier =
        Modifier.padding(start = 12.dp)
          .size(12.dp)
          .background(
            option.info.color?.let { Color(it) } ?: MaterialTheme.colorScheme.outline,
            CircleShape,
          )
    )
    Column(modifier = Modifier.padding(start = 12.dp)) {
      Text(option.info.displayName, style = MaterialTheme.typography.bodyLarge)
      Text(
        option.info.accountName,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      option.otherEvents?.let { count ->
        Text(
          pluralStringResource(R.plurals.target_other_events, count, count),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

@Preview(showBackground = true)
@Composable
private fun TargetPreview() {
  val info =
    CalendarInfo(
      id = 15,
      displayName = "TimeTree",
      accountName = "me@example.com",
      accountType = CalendarInfo.ACCOUNT_TYPE_GOOGLE,
      accessLevel = 700,
      syncEvents = true,
      visible = true,
      color = 0xFF33B679.toInt(),
    )
  TTCalendarSyncTheme {
    TargetContent(
      state =
        TargetUiState(
          permission = true,
          options =
            listOf(
              TargetOption(info, otherEvents = 0),
              TargetOption(info.copy(id = 16, displayName = "Personal"), 7),
            ),
          selectedId = 15,
        ),
      setup = true,
      onBack = null,
      onRequestPermission = {},
      onRefresh = {},
      onSelect = {},
      onConfirm = {},
      onOpenSyncSettings = {},
    )
  }
}
