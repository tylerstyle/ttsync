package de.tergelius.ttsync.ui.reliability

import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.tergelius.ttsync.R
import de.tergelius.ttsync.settings.SetupStep
import de.tergelius.ttsync.ui.DeviceState
import de.tergelius.ttsync.ui.LocalViewModelFactory
import de.tergelius.ttsync.ui.common.BottomActionBar
import de.tergelius.ttsync.ui.common.CheckRow
import de.tergelius.ttsync.ui.common.ErrorBox
import de.tergelius.ttsync.ui.common.Hint
import de.tergelius.ttsync.ui.common.LoadingBox
import de.tergelius.ttsync.ui.common.RowDivider
import de.tergelius.ttsync.ui.common.SectionCard
import de.tergelius.ttsync.ui.common.SystemIntents
import de.tergelius.ttsync.ui.common.Tone
import de.tergelius.ttsync.ui.common.TtScaffold
import de.tergelius.ttsync.ui.common.rememberSettingsLauncher
import de.tergelius.ttsync.ui.settings.SignOutAction
import de.tergelius.ttsync.ui.theme.TTCalendarSyncTheme

/** "Keep running" (DESIGN.md section 7.4), the last setup step. */
@Composable
fun KeepRunningScreen(
  onDone: (SetupStep) -> Unit,
  onBack: (() -> Unit)?,
  onSignedOut: () -> Unit,
  viewModel: KeepRunningViewModel = viewModel(factory = LocalViewModelFactory.current),
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
  val launchSettings = rememberSettingsLauncher(onReturn = viewModel::refresh)
  KeepRunningContent(
    state = state,
    onBack = onBack,
    onAllowBattery = {
      launchSettings(
        listOf(
          SystemIntents.requestBatteryUnrestricted(context),
          SystemIntents.batteryOptimizationList(),
        )
      )
    },
    onOpenAutostart = { launchSettings(listOf(SystemIntents.xiaomiAutostart())) },
    onFinish = viewModel::finish,
    signOutAction = { SignOutAction(onSignedOut = onSignedOut) },
  )
}

@Composable
fun KeepRunningContent(
  state: KeepRunningUiState,
  onBack: (() -> Unit)?,
  onAllowBattery: () -> Unit,
  onOpenAutostart: () -> Unit,
  onFinish: () -> Unit,
  signOutAction: @Composable () -> Unit = {},
) {
  TtScaffold(
    title = stringResource(R.string.keep_running_title),
    onBack = onBack,
    actions = { signOutAction() },
    bottomBar = {
      BottomActionBar {
        if (state.complete) {
          Button(onClick = onFinish, enabled = !state.saving) {
            Text(stringResource(R.string.action_continue))
          }
        } else {
          TextButton(onClick = onFinish, enabled = !state.saving && state.device != null) {
            Text(stringResource(R.string.keep_running_skip))
          }
        }
      }
    },
  ) {
    Text(stringResource(R.string.keep_running_intro), style = MaterialTheme.typography.bodyLarge)
    val device = state.device
    if (device == null) {
      LoadingBox()
    } else {
      SectionCard {
        BatteryRow(device, onAllowBattery)
        if (device.xiaomiFamily) {
          RowDivider()
          AutostartRow(onOpenAutostart)
        }
      }
      if (!state.complete) Hint(stringResource(R.string.keep_running_skip_note))
      Hint(stringResource(R.string.keep_running_later))
    }
    if (state.failed) ErrorBox(stringResource(R.string.error_unknown), onRetry = null)
  }
}

/** Shared with the reliability checklist. */
@Composable
fun BatteryRow(device: DeviceState, onAllow: () -> Unit) {
  CheckRow(
    tone = if (device.batteryUnrestricted) Tone.OK else Tone.PROBLEM,
    title = stringResource(R.string.check_battery_title),
    detail =
      stringResource(
        if (device.batteryUnrestricted) R.string.check_battery_ok
        else R.string.check_battery_problem
      ),
    action =
      if (device.batteryUnrestricted) null
      else {
        { Button(onClick = onAllow) { Text(stringResource(R.string.action_allow)) } }
      },
  )
}

/** Shared with the reliability checklist. Autostart cannot be read, so its state is unknown. */
@Composable
fun AutostartRow(onOpen: () -> Unit) {
  CheckRow(
    tone = Tone.UNKNOWN,
    title = stringResource(R.string.check_autostart_title),
    detail = stringResource(R.string.check_autostart_detail),
    action = {
      OutlinedButton(onClick = onOpen) { Text(stringResource(R.string.action_open_autostart)) }
    },
  )
}

@Preview(showBackground = true)
@Composable
private fun KeepRunningPreview() {
  TTCalendarSyncTheme {
    KeepRunningContent(
      state = KeepRunningUiState(device = DeviceState(manufacturer = "Xiaomi")),
      onBack = {},
      onAllowBattery = {},
      onOpenAutostart = {},
      onFinish = {},
    )
  }
}
