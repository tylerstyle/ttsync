package de.tergelius.ttsync.ui

import de.tergelius.ttsync.calendar.GoogleSyncState
import de.tergelius.ttsync.calendar.TargetCheck
import de.tergelius.ttsync.settings.Settings
import de.tergelius.ttsync.settings.SetupStep
import de.tergelius.ttsync.settings.SourceCalendar
import de.tergelius.ttsync.settings.SyncHealth
import de.tergelius.ttsync.settings.SyncStatus
import de.tergelius.ttsync.store.CalendarState
import de.tergelius.ttsync.sync.CalendarReport
import de.tergelius.ttsync.sync.ScheduleState
import de.tergelius.ttsync.sync.SyncFailure
import de.tergelius.ttsync.sync.SyncProblem
import de.tergelius.ttsync.sync.SyncReport
import de.tergelius.ttsync.sync.SyncTrigger
import de.tergelius.ttsync.sync.SyncWarning
import de.tergelius.ttsync.ui.UiTestGraph.Companion.NOW
import de.tergelius.ttsync.ui.UiTestGraph.Companion.SIGNED_IN
import de.tergelius.ttsync.ui.UiTestGraph.Companion.calendar
import de.tergelius.ttsync.ui.UiTestGraph.Companion.target
import de.tergelius.ttsync.ui.status.LiveChecks
import de.tergelius.ttsync.ui.status.StatusUiState
import de.tergelius.ttsync.ui.status.StatusViewModel
import de.tergelius.ttsync.ui.status.StatusWarning
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class StatusViewModelTest {
  @get:Rule val main = MainDispatcherRule()

  private val settings =
    Settings(
      sources = listOf(SourceCalendar(1, "Family"), SourceCalendar(2, "Football")),
      target = target(15),
      targetDisplayName = "TimeTree",
      keepRunningDone = true,
    )
  private val okDevice =
    DeviceState(calendarPermission = true, notificationsEnabled = true, batteryUnrestricted = true)

  private fun report(
    failure: SyncFailure? = null,
    warnings: Set<SyncWarning> = emptySet(),
    calendars: List<CalendarReport> = emptyList(),
  ) =
    SyncReport(
      runId = "r",
      trigger = SyncTrigger.PERIODIC,
      startedAt = NOW - 60_000,
      finishedAt = NOW - 50_000,
      failure = failure,
      warnings = warnings,
      calendars = calendars,
    )

  private fun derive(
    status: SyncStatus = SyncStatus(lastReport = report(), lastSuccessAt = NOW - 60_000),
    live: LiveChecks = LiveChecks(device = okDevice, targetCheck = TargetCheck.OK),
    schedule: ScheduleState =
      ScheduleState(periodicScheduled = true, nextRunAtMillis = NOW + 1.hours.inWholeMilliseconds),
    s: Settings = settings,
  ) = StatusUiState.derive(s, status, schedule, live, SetupStep.of(true, s), NOW)

  @Test
  fun healthyRun() {
    val state = derive()
    assertEquals(SyncHealth.OK, state.health)
    assertNull(state.problem)
    assertTrue(state.warnings.isEmpty())
    assertFalse(state.stale)
    assertTrue(state.canSyncNow)
  }

  @Test
  fun aDeletedTargetShowsAtOnceWithoutWaitingForARun() {
    val state = derive(live = LiveChecks(device = okDevice, targetCheck = TargetCheck.MISSING))
    assertEquals(SyncHealth.TARGET_DELETED, state.health)
    assertEquals(SyncProblem.TARGET_DELETED, state.problem)
  }

  @Test
  fun theEngineReportingADeletedTargetAlsoCounts() {
    val failed = SyncStatus(lastReport = report(SyncFailure(SyncProblem.TARGET_DELETED)))
    val state = derive(status = failed, live = LiveChecks(device = okDevice, targetCheck = null))
    assertEquals(SyncHealth.TARGET_DELETED, state.health)
  }

  @Test
  fun aNewTargetResolvesTheStoredTargetDeletedFailure() {
    val failed = SyncStatus(lastReport = report(SyncFailure(SyncProblem.TARGET_DELETED)))
    val state = derive(status = failed)
    assertEquals(SyncHealth.OK, state.health)
    assertNull(state.problem)
  }

  @Test
  fun missingCalendarPermissionNeedsAttention() {
    val state = derive(live = LiveChecks(device = okDevice.copy(calendarPermission = false)))
    assertEquals(SyncHealth.NEEDS_ATTENTION, state.health)
    assertEquals(SyncProblem.NO_CALENDAR_PERMISSION, state.problem)
  }

  @Test
  fun signInProblemsComeFromTheLastRun() {
    val failed =
      SyncStatus(lastReport = report(SyncFailure(SyncProblem.AUTH_REJECTED, code = -702)))
    val state = derive(status = failed)
    assertEquals(SyncHealth.NEEDS_ATTENTION, state.health)
    assertEquals(SyncProblem.AUTH_REJECTED, state.problem)
    assertEquals(-702, state.failureCode)
  }

  @Test
  fun staleSuccessIsRedAndNeedsAttention() {
    val old = SyncStatus(lastReport = report(), lastSuccessAt = NOW - 25.hours.inWholeMilliseconds)
    val state = derive(status = old)
    assertTrue(state.stale)
    assertEquals(SyncHealth.NEEDS_ATTENTION, state.health)
  }

  @Test
  fun googleSyncOffWarnsFromTheLiveCheckOrTheReport() {
    val live =
      LiveChecks(
        device = okDevice,
        targetCheck = TargetCheck.OK,
        googleSync = GoogleSyncState(true, false),
      )
    assertEquals(listOf(StatusWarning.GOOGLE_SYNC_OFF), derive(live = live).warnings)

    val fromReport =
      SyncStatus(
        lastReport = report(warnings = setOf(SyncWarning.GOOGLE_SYNC_OFF)),
        lastSuccessAt = NOW,
      )
    assertEquals(listOf(StatusWarning.GOOGLE_SYNC_OFF), derive(status = fromReport).warnings)

    // The live check wins once sync is back on.
    val back = live.copy(googleSync = GoogleSyncState(true, true))
    assertTrue(derive(status = fromReport, live = back).warnings.isEmpty())
  }

  @Test
  fun anAccountAndroidHidesFromTheAppIsNoGoogleSyncWarning() {
    // Device test B2: Android 13+ answers "off" for an account the app cannot see.
    val hidden =
      LiveChecks(
        device = okDevice,
        targetCheck = TargetCheck.OK,
        googleSync = GoogleSyncState(masterSync = true, calendarSync = null),
      )
    assertTrue(derive(live = hidden).warnings.isEmpty())
    val masterOff = hidden.copy(googleSync = GoogleSyncState(masterSync = false, calendarSync = null))
    assertEquals(listOf(StatusWarning.GOOGLE_SYNC_OFF), derive(live = masterOff).warnings)
  }

  @Test
  fun copiesThatGoogleDoesNotTakeWarnWithTheirCount() {
    val status =
      SyncStatus(lastReport = report().copy(notUploaded = 3), lastSuccessAt = NOW - 60_000)
    val state = derive(status = status)
    assertEquals(listOf(StatusWarning.NOT_UPLOADED), state.warnings)
    assertEquals(3, state.notUploaded)
    // Known to be off: that warning says it all.
    val off =
      LiveChecks(okDevice, TargetCheck.OK, GoogleSyncState(masterSync = false, calendarSync = null))
    assertEquals(listOf(StatusWarning.GOOGLE_SYNC_OFF), derive(status = status, live = off).warnings)
  }

  @Test
  fun syncNowRightAfterASuccessfulRunSaysSoInsteadOfQueueing() = runTest {
    // Device test F4: a delayed run can wait half an hour in the background, so none is queued.
    val recent = SyncStatus(lastReport = report(), lastSuccessAt = NOW - 50_000) // 50 s ago
    val g =
      UiTestGraph(settings = settings, status = recent, credentials = SIGNED_IN, device = okDevice)
    val vm =
      StatusViewModel(g.settings, g.status, g.scheduler, g.gateway, g.deviceChecks, g.actions, flowOf(NOW))
    backgroundScope.launch(main.dispatcher) { vm.state.collect {} }
    vm.syncNow()
    val note = vm.state.value.syncedNote!!
    assertEquals(50L, note.secondsAgo)
    assertTrue(g.calls.log.none { it.startsWith("scheduler.runNow") })
    vm.onSyncedShown(note.id)
    assertNull(vm.state.value.syncedNote)

    // After a failed run, "Sync now" retries at once.
    g.status.flow.value = recent.copy(lastReport = report(failure = SyncFailure(SyncProblem.NETWORK)))
    vm.syncNow()
    assertNull(vm.state.value.syncedNote)
    assertEquals("scheduler.runNow(full=false, MANUAL)", g.calls.log.last())
  }

  @Test
  fun theSyncedNoteStaysUntilItsSnackbarWasShown() = runTest {
    // Device retest R1: the screen consumes the note after showSnackbar returns. Each tap is a new
    // note, even with the same text, and consuming an older one leaves the newer one alone.
    val recent = SyncStatus(lastReport = report(), lastSuccessAt = NOW - 50_000)
    val g =
      UiTestGraph(settings = settings, status = recent, credentials = SIGNED_IN, device = okDevice)
    val vm =
      StatusViewModel(g.settings, g.status, g.scheduler, g.gateway, g.deviceChecks, g.actions, flowOf(NOW))
    backgroundScope.launch(main.dispatcher) { vm.state.collect {} }
    vm.syncNow()
    val first = vm.state.value.syncedNote!!
    vm.refresh() // other state changes keep the note
    assertEquals(first, vm.state.value.syncedNote)

    vm.syncNow() // a second tap while the first snackbar shows
    val second = vm.state.value.syncedNote!!
    assertEquals(first.secondsAgo, second.secondsAgo)
    assertNotEquals(first.id, second.id)
    vm.onSyncedShown(first.id)
    assertEquals(second, vm.state.value.syncedNote)
    vm.onSyncedShown(second.id)
    assertNull(vm.state.value.syncedNote)
  }

  @Test
  fun skippedKeepRunningAndOffNotificationsWarn() {
    val device = okDevice.copy(batteryUnrestricted = false, notificationsEnabled = false)
    val state = derive(live = LiveChecks(device = device, targetCheck = TargetCheck.OK))
    assertEquals(
      listOf(StatusWarning.BATTERY_RESTRICTED, StatusWarning.NOTIFICATIONS_OFF),
      state.warnings,
    )
  }

  @Test
  fun pausedCalendarsAreNamed() {
    val paused =
      SyncStatus(
        lastReport =
          report(
            calendars =
              listOf(
                CalendarReport(1, mode = null, state = CalendarState.PAUSED),
                CalendarReport(2, mode = null, state = CalendarState.ACTIVE),
              )
          ),
        lastSuccessAt = NOW,
      )
    val state = derive(status = paused)
    assertEquals(listOf(SourceCalendar(1, "Family")), state.pausedCalendars)
    assertEquals(SyncHealth.OK, state.health)
  }

  @Test
  fun runningBlocksSyncNow() {
    val state = derive(schedule = ScheduleState(running = true))
    assertEquals(SyncHealth.RUNNING, state.health)
    assertFalse(state.canSyncNow)
  }

  @Test
  fun aRunAfterTheLastClockTickIsNotInTheFuture() {
    // Device test O1: the minute clock lagged behind the run that just finished, so "Last
    // successful sync" read "In 0 minutes".
    val justNow = NOW + 20_000
    val state = derive(status = SyncStatus(lastReport = report(), lastSuccessAt = justNow))
    assertEquals(justNow, state.nowMillis)
    assertFalse(state.stale)
    assertEquals(SyncHealth.OK, state.health)
    // An older success leaves the clock as it is.
    assertEquals(NOW, derive().nowMillis)
  }

  @Test
  fun viewModelCombinesTheStoresAndSyncsNow() = runTest {
    val g = UiTestGraph(settings = settings, credentials = SIGNED_IN, device = okDevice)
    g.gateway.calendars = listOf(calendar(15, "TimeTree"))
    val vm =
      StatusViewModel(
        settings = g.settings,
        status = g.status,
        scheduler = g.scheduler,
        gateway = g.gateway,
        deviceChecks = g.deviceChecks,
        actions = g.actions,
        clock = flowOf(NOW),
      )
    backgroundScope.launch(main.dispatcher) { vm.state.collect {} }
    vm.refresh()
    assertFalse(vm.state.value.loading)
    assertEquals("TimeTree", vm.state.value.targetName)
    assertEquals(SyncHealth.OK, vm.state.value.health)

    g.gateway.calendars = emptyList() // deleted on calendar.google.com
    vm.refresh()
    assertEquals(SyncHealth.TARGET_DELETED, vm.state.value.health)

    vm.syncNow()
    assertEquals("scheduler.runNow(full=false, MANUAL)", g.calls.log.last())
  }

  @Test
  fun removingPausedCalendarsAsksFirst() = runTest {
    val paused =
      SyncStatus(
        lastReport =
          report(calendars = listOf(CalendarReport(2, mode = null, state = CalendarState.PAUSED))),
        lastSuccessAt = NOW,
      )
    val g =
      UiTestGraph(settings = settings, status = paused, credentials = SIGNED_IN, device = okDevice)
    val vm =
      StatusViewModel(
        g.settings,
        g.status,
        g.scheduler,
        g.gateway,
        g.deviceChecks,
        g.actions,
        flowOf(NOW),
      )
    backgroundScope.launch(main.dispatcher) { vm.state.collect {} }
    vm.askRemovePaused()
    assertTrue(vm.state.value.confirmRemovePaused)
    vm.confirmRemovePaused()
    assertFalse(vm.state.value.confirmRemovePaused)
    assertEquals(listOf(SourceCalendar(1, "Family")), g.settings.flow.value.sources)
    assertTrue("engine.deselect([2])" in g.calls.log)
  }
}
