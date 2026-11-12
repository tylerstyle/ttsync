package de.tergelius.ttsync.ui.reliability

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.tergelius.ttsync.calendar.CalendarGateway
import de.tergelius.ttsync.calendar.GoogleSyncState
import de.tergelius.ttsync.settings.SettingsRepository
import de.tergelius.ttsync.settings.StatusRepository
import de.tergelius.ttsync.ui.DeviceChecks
import de.tergelius.ttsync.ui.DeviceState
import de.tergelius.ttsync.ui.UnusedAppRestrictions
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The rows of the reliability checklist (DESIGN.md section 7.6), in display order. */
enum class CheckId {
  CALENDAR_PERMISSION,
  NOTIFICATIONS,
  GOOGLE_SYNC,
  BATTERY,
  AUTOSTART,
  BACKGROUND_RESTRICTION,
  UNUSED_APP_RESTRICTIONS,
  DONT_KILL_MY_APP,
  STANDBY_BUCKET,
}

enum class CheckState {
  OK,
  PROBLEM,

  /** Cannot be read (Autostart), or not yet (Google sync before a target is chosen). */
  UNKNOWN,

  /** A link or a diagnosis, not a pass/fail check. */
  INFO,
}

data class CheckItem(val id: CheckId, val state: CheckState)

object Checklist {
  /**
   * Builds the checklist. [googleSync] is null when it cannot be read (no target or no calendar
   * permission). Google sync is a problem when a switch is known to be off, or when the last run
   * found [notUploaded] copies still waiting for upload; an account switch Android hides from the
   * app does not count (device test B2). Autostart appears only on the Xiaomi family, "Pause app
   * activity if unused" only where the feature exists, the standby bucket only on API 28+.
   */
  fun build(
    device: DeviceState,
    googleSync: GoogleSyncState?,
    notUploaded: Int = 0,
  ): List<CheckItem> = buildList {
    add(CheckItem(CheckId.CALENDAR_PERMISSION, ok(device.calendarPermission)))
    add(CheckItem(CheckId.NOTIFICATIONS, ok(device.notificationsEnabled)))
    add(
      CheckItem(
        CheckId.GOOGLE_SYNC,
        if (googleSync == null) CheckState.UNKNOWN else ok(googleSync.uploads && notUploaded == 0),
      )
    )
    add(CheckItem(CheckId.BATTERY, ok(device.batteryUnrestricted)))
    if (device.xiaomiFamily) add(CheckItem(CheckId.AUTOSTART, CheckState.UNKNOWN))
    add(CheckItem(CheckId.BACKGROUND_RESTRICTION, ok(!device.backgroundRestricted)))
    when (device.unusedAppRestrictions) {
      UnusedAppRestrictions.ENABLED ->
        add(CheckItem(CheckId.UNUSED_APP_RESTRICTIONS, CheckState.PROBLEM))
      UnusedAppRestrictions.DISABLED ->
        add(CheckItem(CheckId.UNUSED_APP_RESTRICTIONS, CheckState.OK))
      UnusedAppRestrictions.NOT_AVAILABLE -> Unit
    }
    add(CheckItem(CheckId.DONT_KILL_MY_APP, CheckState.INFO))
    if (device.standbyBucket != null) add(CheckItem(CheckId.STANDBY_BUCKET, CheckState.INFO))
  }

  private fun ok(value: Boolean) = if (value) CheckState.OK else CheckState.PROBLEM
}

data class ReliabilityUiState(
  val device: DeviceState? = null,
  val googleSync: GoogleSyncState? = null,
  val items: List<CheckItem> = emptyList(),
  /** Copies the last run found still waiting for Google's sync (`SyncReport.notUploaded`). */
  val notUploaded: Int = 0,
) {
  val problems: Int
    get() = items.count { it.state == CheckState.PROBLEM }
}

/** Reliability checklist. Re-read on every resume, since every fix happens in system settings. */
class ReliabilityViewModel(
  private val deviceChecks: DeviceChecks,
  private val settings: SettingsRepository,
  private val gateway: CalendarGateway,
  private val status: StatusRepository,
) : ViewModel() {
  private val _state = MutableStateFlow(ReliabilityUiState())
  val state: StateFlow<ReliabilityUiState> = _state.asStateFlow()

  fun refresh() {
    viewModelScope.launch {
      val device =
        try {
          deviceChecks.read()
        } catch (e: CancellationException) {
          throw e
        } catch (_: Exception) {
          DeviceState()
        }
      val googleSync = googleSync(device)
      val notUploaded =
        try {
          status.current().lastReport?.notUploaded ?: 0
        } catch (e: CancellationException) {
          throw e
        } catch (_: Exception) {
          0
        }
      _state.update {
        it.copy(
          device = device,
          googleSync = googleSync,
          items = Checklist.build(device, googleSync, notUploaded),
          notUploaded = notUploaded,
        )
      }
    }
  }

  private suspend fun googleSync(device: DeviceState): GoogleSyncState? {
    if (!device.calendarPermission) return null
    return try {
      settings.current().target?.let(gateway::googleSyncState)
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      null
    }
  }
}
