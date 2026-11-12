package de.tergelius.ttsync.ui.reliability

import androidx.annotation.StringRes
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.tergelius.ttsync.R
import de.tergelius.ttsync.calendar.GoogleSyncState
import de.tergelius.ttsync.ui.DeviceState
import de.tergelius.ttsync.ui.LocalViewModelFactory
import de.tergelius.ttsync.ui.StandbyBucket
import de.tergelius.ttsync.ui.UnusedAppRestrictions
import de.tergelius.ttsync.ui.common.CheckRow
import de.tergelius.ttsync.ui.common.Hint
import de.tergelius.ttsync.ui.common.LoadingBox
import de.tergelius.ttsync.ui.common.RowDivider
import de.tergelius.ttsync.ui.common.SectionCard
import de.tergelius.ttsync.ui.common.SystemIntents
import de.tergelius.ttsync.ui.common.Tone
import de.tergelius.ttsync.ui.common.TtScaffold
import de.tergelius.ttsync.ui.common.rememberCalendarPermissionRequest
import de.tergelius.ttsync.ui.common.rememberNotificationRequest
import de.tergelius.ttsync.ui.common.rememberSettingsLauncher
import de.tergelius.ttsync.ui.theme.TTCalendarSyncTheme

/** What each row's fix button does; the screen wires them to intents and permission requests. */
class ChecklistActions(
  val requestCalendar: () -> Unit,
  val requestNotifications: () -> Unit,
  val openSyncSettings: () -> Unit,
  val allowBattery: () -> Unit,
  val openAutostart: () -> Unit,
  val openAppDetails: () -> Unit,
  val openUnusedAppSettings: () -> Unit,
  val openDontKillMyApp: () -> Unit,
)

/** Reliability checklist (DESIGN.md section 7.6). */
@Composable
fun ReliabilityScreen(
  onBack: () -> Unit,
  viewModel: ReliabilityViewModel = viewModel(factory = LocalViewModelFactory.current),
) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  LifecycleResumeEffect(viewModel) {
    viewModel.refresh()
    onPauseOrDispose {}
  }
  val actions = rememberChecklistActions(state.device, onChanged = viewModel::refresh)
  ReliabilityContent(state = state, onBack = onBack, actions = actions)
}

/** Wires every checklist fix to its system screen or permission request. */
@Composable
fun rememberChecklistActions(device: DeviceState?, onChanged: () -> Unit): ChecklistActions {
  val context = LocalContext.current
  val launchSettings = rememberSettingsLauncher(onReturn = onChanged)
  val requestCalendar = rememberCalendarPermissionRequest { onChanged() }
  val requestNotifications =
    rememberNotificationRequest(
      permissionMissing = device?.notificationPermissionMissing == true,
      onResult = onChanged,
    )
  return ChecklistActions(
    requestCalendar = requestCalendar,
    requestNotifications = requestNotifications,
    openSyncSettings = { launchSettings(listOf(SystemIntents.syncSettings())) },
    allowBattery = {
      launchSettings(
        listOf(
          SystemIntents.requestBatteryUnrestricted(context),
          SystemIntents.batteryOptimizationList(),
        )
      )
    },
    openAutostart = { launchSettings(listOf(SystemIntents.xiaomiAutostart())) },
    openAppDetails = { launchSettings(emptyList()) },
    openUnusedAppSettings = {
      // Throws if the feature does not exist; the row is hidden then, but be safe.
      val intent = runCatching { SystemIntents.unusedAppRestrictions(context) }.getOrNull()
      launchSettings(listOfNotNull(intent))
    },
    openDontKillMyApp = {
      SystemIntents.start(context, SystemIntents.dontKillMyApp(device?.manufacturer.orEmpty()))
    },
  )
}

@Composable
fun ReliabilityContent(state: ReliabilityUiState, onBack: () -> Unit, actions: ChecklistActions) {
  TtScaffold(title = stringResource(R.string.reliability_title), onBack = onBack) {
    Text(stringResource(R.string.reliability_intro), style = MaterialTheme.typography.bodyLarge)
    val device = state.device
    if (device == null) {
      LoadingBox()
      return@TtScaffold
    }
    Text(
      if (state.problems == 0) stringResource(R.string.reliability_all_ok)
      else pluralStringResource(R.plurals.reliability_problems, state.problems, state.problems),
      style = MaterialTheme.typography.titleMedium,
      color =
        if (state.problems == 0) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.error,
    )
    SectionCard {
      state.items.forEachIndexed { index, item ->
        if (index > 0) RowDivider()
        ChecklistRow(item, device, state.googleSync, actions)
      }
    }
    Hint(stringResource(R.string.reliability_footer))
  }
}

@Composable
private fun ChecklistRow(
  item: CheckItem,
  device: DeviceState,
  googleSync: GoogleSyncState?,
  actions: ChecklistActions,
) {
  val tone =
    when (item.state) {
      CheckState.OK -> Tone.OK
      CheckState.PROBLEM -> Tone.PROBLEM
      CheckState.UNKNOWN -> Tone.UNKNOWN
      CheckState.INFO -> Tone.INFO
    }
  val problem = item.state == CheckState.PROBLEM
  when (item.id) {
    CheckId.CALENDAR_PERMISSION ->
      CheckRow(
        tone = tone,
        title = stringResource(R.string.check_calendar_title),
        detail =
          stringResource(
            if (problem) R.string.check_calendar_problem else R.string.check_calendar_ok
          ),
        action = fixButton(problem, R.string.action_allow, actions.requestCalendar),
      )
    CheckId.NOTIFICATIONS ->
      CheckRow(
        tone = tone,
        title = stringResource(R.string.check_notifications_title),
        detail =
          stringResource(
            if (problem) R.string.check_notifications_problem else R.string.check_notifications_ok
          ),
        action = fixButton(problem, R.string.action_turn_on, actions.requestNotifications),
      )
    CheckId.GOOGLE_SYNC ->
      CheckRow(
        tone = tone,
        title = stringResource(R.string.check_google_sync_title),
        detail =
          stringResource(
            when {
              googleSync == null -> R.string.check_google_sync_unknown
              !googleSync.masterSync -> R.string.check_google_sync_master_off
              !googleSync.calendarSync -> R.string.check_google_sync_account_off
              else -> R.string.check_google_sync_ok
            }
          ),
        action =
          fixButton(
            item.state != CheckState.OK,
            R.string.action_open_sync_settings,
            actions.openSyncSettings,
          ),
      )
    CheckId.BATTERY -> BatteryRow(device, actions.allowBattery)
    CheckId.AUTOSTART -> AutostartRow(actions.openAutostart)
    CheckId.BACKGROUND_RESTRICTION ->
      CheckRow(
        tone = tone,
        title = stringResource(R.string.check_background_title),
        detail =
          stringResource(
            if (problem) R.string.check_background_problem else R.string.check_background_ok
          ),
        action = fixButton(problem, R.string.action_open_app_settings, actions.openAppDetails),
      )
    CheckId.UNUSED_APP_RESTRICTIONS ->
      CheckRow(
        tone = tone,
        title = stringResource(R.string.check_unused_title),
        detail =
          stringResource(if (problem) R.string.check_unused_problem else R.string.check_unused_ok),
        action = fixButton(problem, R.string.action_turn_off, actions.openUnusedAppSettings),
      )
    CheckId.DONT_KILL_MY_APP ->
      CheckRow(
        tone = tone,
        title = stringResource(R.string.check_dontkill_title),
        detail =
          if (device.manufacturer.isBlank()) stringResource(R.string.check_dontkill_detail_generic)
          else stringResource(R.string.check_dontkill_detail, device.manufacturer),
        action = {
          OutlinedButton(onClick = actions.openDontKillMyApp) {
            Text(stringResource(R.string.action_open_dontkillmyapp))
          }
        },
      )
    CheckId.STANDBY_BUCKET ->
      CheckRow(
        tone = tone,
        title = stringResource(R.string.check_bucket_title),
        detail = stringResource(R.string.check_bucket_detail, bucketName(device.standbyBucket)),
      )
  }
}

private fun fixButton(
  show: Boolean,
  @StringRes label: Int,
  onClick: () -> Unit,
): (@Composable () -> Unit)? =
  if (!show) null
  else {
    { Button(onClick = onClick) { Text(stringResource(label)) } }
  }

@Composable
private fun bucketName(bucket: StandbyBucket?): String =
  stringResource(
    when (bucket) {
      StandbyBucket.EXEMPTED -> R.string.bucket_exempted
      StandbyBucket.ACTIVE -> R.string.bucket_active
      StandbyBucket.WORKING_SET -> R.string.bucket_working_set
      StandbyBucket.FREQUENT -> R.string.bucket_frequent
      StandbyBucket.RARE -> R.string.bucket_rare
      StandbyBucket.RESTRICTED -> R.string.bucket_restricted
      StandbyBucket.OTHER,
      null -> R.string.bucket_other
    }
  )

@Preview(showBackground = true)
@Composable
private fun ReliabilityPreview() {
  val device =
    DeviceState(
      calendarPermission = true,
      notificationsEnabled = false,
      batteryUnrestricted = true,
      unusedAppRestrictions = UnusedAppRestrictions.ENABLED,
      standbyBucket = StandbyBucket.ACTIVE,
      manufacturer = "Xiaomi",
    )
  val googleSync = GoogleSyncState(masterSync = true, calendarSync = false)
  TTCalendarSyncTheme {
    ReliabilityContent(
      state = ReliabilityUiState(device, googleSync, Checklist.build(device, googleSync)),
      onBack = {},
      actions = ChecklistActions({}, {}, {}, {}, {}, {}, {}, {}),
    )
  }
}
