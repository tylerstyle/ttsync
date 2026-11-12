package de.tergelius.ttsync.ui.status

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.tergelius.ttsync.R
import de.tergelius.ttsync.settings.SetupStep
import de.tergelius.ttsync.settings.SourceCalendar
import de.tergelius.ttsync.settings.SyncHealth
import de.tergelius.ttsync.sync.SyncCounts
import de.tergelius.ttsync.sync.SyncProblem
import de.tergelius.ttsync.sync.SyncReport
import de.tergelius.ttsync.sync.SyncTrigger
import de.tergelius.ttsync.ui.LocalViewModelFactory
import de.tergelius.ttsync.ui.common.CheckRow
import de.tergelius.ttsync.ui.common.ConfirmDialog
import de.tergelius.ttsync.ui.common.ErrorBox
import de.tergelius.ttsync.ui.common.Hint
import de.tergelius.ttsync.ui.common.LoadingBox
import de.tergelius.ttsync.ui.common.RowDivider
import de.tergelius.ttsync.ui.common.SectionCard
import de.tergelius.ttsync.ui.common.SystemIntents
import de.tergelius.ttsync.ui.common.Tone
import de.tergelius.ttsync.ui.common.ToneIcon
import de.tergelius.ttsync.ui.common.TtScaffold
import de.tergelius.ttsync.ui.common.formatDateTime
import de.tergelius.ttsync.ui.common.formatRelative
import de.tergelius.ttsync.ui.common.formatTime
import de.tergelius.ttsync.ui.common.rememberCalendarPermissionRequest
import de.tergelius.ttsync.ui.common.rememberNotificationRequest
import de.tergelius.ttsync.ui.common.rememberSettingsLauncher
import de.tergelius.ttsync.ui.theme.TTCalendarSyncTheme

/** Where the Status fix buttons lead. */
class StatusNavigation(
  val openSettings: () -> Unit,
  val openReliability: () -> Unit,
  val chooseTarget: () -> Unit,
  val signInAgain: () -> Unit,
  val continueSetup: (SetupStep) -> Unit,
)

/** What the Status fix buttons do in place. */
class StatusFixes(
  val syncNow: () -> Unit,
  val requestCalendar: () -> Unit,
  val requestNotifications: () -> Unit,
  val openSyncSettings: () -> Unit,
  val allowBattery: () -> Unit,
  val openAppDetails: () -> Unit,
  val removePaused: () -> Unit,
)

/** Status, the home screen (DESIGN.md section 7.5). */
@Composable
fun StatusScreen(
  navigation: StatusNavigation,
  viewModel: StatusViewModel = viewModel(factory = LocalViewModelFactory.current),
) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  val context = LocalContext.current
  LifecycleResumeEffect(viewModel) {
    viewModel.refresh()
    onPauseOrDispose {}
  }
  val snackbar = remember { SnackbarHostState() }
  val resources = LocalResources.current
  LaunchedEffect(state.syncedSecondsAgo) {
    state.syncedSecondsAgo?.let { seconds ->
      viewModel.onSyncedShown()
      snackbar.showSnackbar(resources.getString(R.string.status_synced_recently, seconds))
    }
  }

  // POST_NOTIFICATIONS is asked here (DESIGN.md section 8), once per app start until granted;
  // after two denials Android stops showing the dialog and the warning's button opens settings.
  var askedNotifications by rememberSaveable { mutableStateOf(false) }
  val askNotifications =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
      viewModel.refresh()
    }
  LaunchedEffect(state.notificationPermissionMissing, state.setupStep) {
    if (
      state.notificationPermissionMissing &&
        state.setupStep == SetupStep.DONE &&
        !askedNotifications &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    ) {
      askedNotifications = true
      askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
  }

  val launchSettings = rememberSettingsLauncher(onReturn = viewModel::refresh)
  val fixes =
    StatusFixes(
      syncNow = viewModel::syncNow,
      requestCalendar = rememberCalendarPermissionRequest(viewModel::onPermissionResult),
      requestNotifications =
        rememberNotificationRequest(state.notificationPermissionMissing, viewModel::refresh),
      openSyncSettings = { launchSettings(listOf(SystemIntents.syncSettings())) },
      allowBattery = {
        launchSettings(
          listOf(
            SystemIntents.requestBatteryUnrestricted(context),
            SystemIntents.batteryOptimizationList(),
          )
        )
      },
      openAppDetails = { launchSettings(emptyList()) },
      removePaused = viewModel::askRemovePaused,
    )
  StatusContent(state = state, navigation = navigation, fixes = fixes, snackbar = snackbar)

  if (state.confirmRemovePaused) {
    val resources = LocalResources.current
    ConfirmDialog(
      title = stringResource(R.string.status_remove_paused_title),
      text =
        stringResource(
          R.string.status_remove_paused_text,
          calendarNames(state.pausedCalendars) {
            resources.getString(R.string.sources_unnamed, it)
          },
          state.targetName ?: stringResource(R.string.status_target_unknown),
        ),
      confirmLabel = stringResource(R.string.action_remove_copies),
      onConfirm = viewModel::confirmRemovePaused,
      onDismiss = viewModel::dismissRemovePaused,
      busy = state.removingPaused,
    )
  }
}

private fun calendarNames(calendars: List<SourceCalendar>, unnamed: (Long) -> String): String =
  calendars.joinToString(", ") { it.name.ifBlank { unnamed(it.id) } }

@Composable
fun StatusContent(
  state: StatusUiState,
  navigation: StatusNavigation,
  fixes: StatusFixes,
  snackbar: SnackbarHostState? = null,
) {
  TtScaffold(
    title = stringResource(R.string.app_name),
    onBack = null,
    snackbarHostState = snackbar,
    actions = {
      IconButton(onClick = navigation.openSettings) {
        Icon(
          painterResource(R.drawable.ic_settings),
          contentDescription = stringResource(R.string.settings_title),
        )
      }
    },
  ) {
    if (state.loading) {
      LoadingBox()
      return@TtScaffold
    }
    if (state.setupStep != SetupStep.DONE) {
      SectionCard(title = stringResource(R.string.status_setup_title)) {
        Text(stringResource(R.string.status_setup_text))
        Button(onClick = { navigation.continueSetup(state.setupStep) }) {
          Text(stringResource(R.string.status_setup_button))
        }
      }
    }
    HealthCard(state, navigation, fixes)
    SyncCard(state, navigation, fixes)
    WarningsCard(state, fixes)
    if (state.removalFailed) {
      ErrorBox(stringResource(R.string.error_removal_failed), onRetry = null)
    } else if (state.actionFailed) {
      ErrorBox(stringResource(R.string.error_unknown), onRetry = null)
    }
  }
}

@Composable
private fun HealthCard(state: StatusUiState, navigation: StatusNavigation, fixes: StatusFixes) {
  val problemColor = MaterialTheme.colorScheme.errorContainer
  val (tone, container) =
    when (state.health) {
      SyncHealth.OK -> Tone.OK to MaterialTheme.colorScheme.primaryContainer
      SyncHealth.RUNNING -> Tone.INFO to MaterialTheme.colorScheme.secondaryContainer
      SyncHealth.PAUSED -> Tone.UNKNOWN to MaterialTheme.colorScheme.surfaceContainerHigh
      SyncHealth.NEEDS_ATTENTION,
      SyncHealth.TARGET_DELETED -> Tone.PROBLEM to problemColor
    }
  SectionCard(containerColor = container) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
      modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    ) {
      ToneIcon(tone, modifier = Modifier.size(32.dp))
      Text(healthHeadline(state), style = MaterialTheme.typography.titleLarge)
    }
    if (state.health == SyncHealth.RUNNING) {
      LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
    val problem = state.problem
    if (problem != null && state.health != SyncHealth.RUNNING) {
      Text(problemText(problem, state))
      ProblemFix(problem, navigation, fixes)
    } else if (state.health == SyncHealth.NEEDS_ATTENTION) {
      // No single cause: repeated failures or no success for over 24 h.
      Text(
        if (state.stale) stringResource(R.string.status_stale_text)
        else
          pluralStringResource(
            R.plurals.status_failures_text,
            state.consecutiveFailures,
            state.consecutiveFailures,
          )
      )
      OutlinedButton(onClick = navigation.openReliability) {
        Text(stringResource(R.string.status_open_checklist))
      }
    } else if (state.health == SyncHealth.PAUSED) {
      Text(stringResource(R.string.status_paused_text))
    }
  }
}

@Composable
private fun healthHeadline(state: StatusUiState): String =
  stringResource(
    when (state.health) {
      SyncHealth.OK ->
        if (state.lastReport == null) R.string.status_health_waiting else R.string.status_health_ok
      SyncHealth.RUNNING -> R.string.status_health_running
      SyncHealth.NEEDS_ATTENTION -> R.string.status_health_attention
      SyncHealth.PAUSED -> R.string.status_health_paused
      SyncHealth.TARGET_DELETED -> R.string.status_health_target_deleted
    }
  )

@Composable
private fun problemText(problem: SyncProblem, state: StatusUiState): String =
  when (problem) {
    SyncProblem.NOT_CONFIGURED -> stringResource(R.string.problem_not_configured)
    SyncProblem.NO_CALENDAR_PERMISSION -> stringResource(R.string.problem_no_permission)
    SyncProblem.TARGET_DELETED -> stringResource(R.string.problem_target_deleted)
    SyncProblem.TARGET_UNAVAILABLE -> stringResource(R.string.problem_target_unavailable)
    SyncProblem.NOT_SIGNED_IN -> stringResource(R.string.problem_not_signed_in)
    SyncProblem.AUTH_REJECTED -> stringResource(R.string.problem_auth_rejected)
    SyncProblem.RATE_LIMITED ->
      state.rateLimitedUntil?.let {
        stringResource(R.string.problem_rate_limited_until, formatTime(it))
      } ?: stringResource(R.string.problem_rate_limited)
    SyncProblem.NETWORK -> stringResource(R.string.problem_network)
    SyncProblem.API_CHANGED -> stringResource(R.string.error_api_changed)
    SyncProblem.CALENDAR_WRITE_FAILED -> stringResource(R.string.problem_write_failed)
    SyncProblem.INTERNAL ->
      state.failureCode?.let { stringResource(R.string.problem_internal_code, it) }
        ?: stringResource(R.string.problem_internal)
  }

@Composable
private fun ProblemFix(problem: SyncProblem, navigation: StatusNavigation, fixes: StatusFixes) {
  when (problem) {
    SyncProblem.NOT_CONFIGURED -> Unit // the setup card above has the button
    SyncProblem.NO_CALENDAR_PERMISSION ->
      Button(onClick = fixes.requestCalendar) { Text(stringResource(R.string.action_allow)) }
    SyncProblem.TARGET_DELETED,
    SyncProblem.TARGET_UNAVAILABLE ->
      Button(onClick = navigation.chooseTarget) {
        Text(stringResource(R.string.status_choose_target))
      }
    SyncProblem.NOT_SIGNED_IN,
    SyncProblem.AUTH_REJECTED ->
      Button(onClick = navigation.signInAgain) {
        Text(stringResource(R.string.action_sign_in_again))
      }
    SyncProblem.CALENDAR_WRITE_FAILED,
    SyncProblem.INTERNAL ->
      OutlinedButton(onClick = fixes.syncNow) { Text(stringResource(R.string.status_sync_now)) }
    SyncProblem.RATE_LIMITED,
    SyncProblem.NETWORK,
    SyncProblem.API_CHANGED -> Unit // nothing the user can do here; the app retries
  }
}

@Composable
private fun SyncCard(state: StatusUiState, navigation: StatusNavigation, fixes: StatusFixes) {
  SectionCard(title = stringResource(R.string.status_sync_title)) {
    LabeledValue(
      label = stringResource(R.string.status_last_success),
      value =
        state.lastSuccessAt?.let {
          stringResource(
            R.string.time_with_relative,
            formatDateTime(it),
            formatRelative(it, state.nowMillis),
          )
        } ?: stringResource(R.string.status_never),
      valueColor = if (state.stale) MaterialTheme.colorScheme.error else Color.Unspecified,
    )
    LabeledValue(
      label = stringResource(R.string.status_next_run),
      value =
        when {
          state.running -> stringResource(R.string.status_next_running)
          state.runNowPending -> stringResource(R.string.status_next_soon)
          state.nextRunAt != null ->
            stringResource(
              R.string.time_with_relative,
              formatDateTime(state.nextRunAt),
              formatRelative(state.nextRunAt, state.nowMillis),
            )
          else -> stringResource(R.string.status_next_not_scheduled)
        },
    )
    if (state.sources.isNotEmpty() && state.targetName != null) {
      val resources = LocalResources.current
      Hint(
        stringResource(
          R.string.status_copying,
          calendarNames(state.sources) { resources.getString(R.string.sources_unnamed, it) },
          state.targetName,
        )
      )
    }
    state.lastReport?.let { LastRun(it) }
    FlowRow(
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Button(onClick = fixes.syncNow, enabled = state.canSyncNow) {
        Text(stringResource(R.string.status_sync_now))
      }
      OutlinedButton(onClick = navigation.openReliability) {
        Text(stringResource(R.string.status_open_checklist))
      }
    }
  }
}

@Composable
private fun LastRun(report: SyncReport) {
  RowDivider()
  Text(
    stringResource(
      if (report.succeeded) R.string.status_last_run else R.string.status_last_run_failed,
      formatDateTime(report.finishedAt),
    ),
    style = MaterialTheme.typography.titleSmall,
  )
  val c: SyncCounts = report.counts
  Text(
    stringResource(R.string.status_counts, c.added, c.updated, c.deleted, c.skipped),
    style = MaterialTheme.typography.bodyMedium,
  )
  if (c.unmatchedOverrides > 0) {
    Hint(
      pluralStringResource(R.plurals.status_unmatched, c.unmatchedOverrides, c.unmatchedOverrides)
    )
  }
}

@Composable
private fun LabeledValue(label: String, value: String, valueColor: Color = Color.Unspecified) {
  Column {
    Text(
      label,
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(value, style = MaterialTheme.typography.bodyLarge, color = valueColor)
  }
}

@Composable
private fun WarningsCard(state: StatusUiState, fixes: StatusFixes) {
  if (state.warnings.isEmpty() && state.pausedCalendars.isEmpty()) return
  SectionCard(title = stringResource(R.string.status_warnings_title)) {
    if (state.pausedCalendars.isNotEmpty()) {
      val resources = LocalResources.current
      CheckRow(
        tone = Tone.PROBLEM,
        title = stringResource(R.string.status_warning_paused_title),
        detail =
          stringResource(
            R.string.status_warning_paused_text,
            calendarNames(state.pausedCalendars) {
              resources.getString(R.string.sources_unnamed, it)
            },
          ),
        action = {
          OutlinedButton(onClick = fixes.removePaused) {
            Text(stringResource(R.string.action_remove_copies))
          }
        },
      )
    }
    state.warnings.forEachIndexed { index, warning ->
      if (index > 0 || state.pausedCalendars.isNotEmpty()) RowDivider()
      WarningRow(warning, state, fixes)
    }
  }
}

@Composable
private fun WarningRow(warning: StatusWarning, state: StatusUiState, fixes: StatusFixes) {
  when (warning) {
    StatusWarning.NOT_UPLOADED ->
      CheckRow(
        tone = Tone.PROBLEM,
        title =
          pluralStringResource(R.plurals.status_warning_not_uploaded, state.notUploaded, state.notUploaded),
        detail = stringResource(R.string.status_warning_not_uploaded_text),
        action = {
          Button(onClick = fixes.openSyncSettings) {
            Text(stringResource(R.string.action_open_sync_settings))
          }
        },
      )
    StatusWarning.GOOGLE_SYNC_OFF ->
      CheckRow(
        tone = Tone.PROBLEM,
        title = stringResource(R.string.status_warning_google_sync),
        detail = stringResource(R.string.status_warning_google_sync_text),
        action = {
          Button(onClick = fixes.openSyncSettings) {
            Text(stringResource(R.string.action_open_sync_settings))
          }
        },
      )
    StatusWarning.BATTERY_RESTRICTED ->
      CheckRow(
        tone = Tone.PROBLEM,
        title = stringResource(R.string.status_warning_battery),
        detail = stringResource(R.string.check_battery_problem),
        action = {
          Button(onClick = fixes.allowBattery) { Text(stringResource(R.string.action_allow)) }
        },
      )
    StatusWarning.BACKGROUND_RESTRICTED ->
      CheckRow(
        tone = Tone.PROBLEM,
        title = stringResource(R.string.check_background_title),
        detail = stringResource(R.string.check_background_problem),
        action = {
          Button(onClick = fixes.openAppDetails) {
            Text(stringResource(R.string.action_open_app_settings))
          }
        },
      )
    StatusWarning.NOTIFICATIONS_OFF ->
      CheckRow(
        tone = Tone.PROBLEM,
        title = stringResource(R.string.status_warning_notifications),
        detail = stringResource(R.string.check_notifications_problem),
        action = {
          Button(onClick = fixes.requestNotifications) {
            Text(stringResource(R.string.action_turn_on))
          }
        },
      )
  }
}

@Preview(showBackground = true)
@Composable
private fun StatusPreview() {
  val now = 1_794_481_200_000L
  TTCalendarSyncTheme {
    StatusContent(
      state =
        StatusUiState(
          loading = false,
          health = SyncHealth.TARGET_DELETED,
          problem = SyncProblem.TARGET_DELETED,
          lastSuccessAt = now - 26 * 3_600_000L,
          stale = true,
          nextRunAt = now + 1_800_000L,
          periodicScheduled = true,
          lastReport =
            SyncReport(
              runId = "r",
              trigger = SyncTrigger.PERIODIC,
              startedAt = now - 60_000,
              finishedAt = now - 50_000,
              counts = SyncCounts(added = 3, updated = 1, skipped = 2, unmatchedOverrides = 1),
            ),
          warnings = listOf(StatusWarning.GOOGLE_SYNC_OFF, StatusWarning.BATTERY_RESTRICTED),
          sources = listOf(SourceCalendar(1, "Family")),
          targetName = "TimeTree",
          nowMillis = now,
        ),
      navigation = StatusNavigation({}, {}, {}, {}, {}),
      fixes = StatusFixes({}, {}, {}, {}, {}, {}, {}),
    )
  }
}
