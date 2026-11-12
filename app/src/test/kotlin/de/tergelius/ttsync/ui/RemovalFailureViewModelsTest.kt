package de.tergelius.ttsync.ui

import de.tergelius.ttsync.settings.Settings
import de.tergelius.ttsync.settings.SourceCalendar
import de.tergelius.ttsync.settings.SyncInterval
import de.tergelius.ttsync.settings.SyncStatus
import de.tergelius.ttsync.store.CalendarState
import de.tergelius.ttsync.sync.CalendarReport
import de.tergelius.ttsync.sync.SyncFailure
import de.tergelius.ttsync.sync.SyncProblem
import de.tergelius.ttsync.sync.SyncReport
import de.tergelius.ttsync.sync.SyncTrigger
import de.tergelius.ttsync.timetree.TtCalendar
import de.tergelius.ttsync.ui.UiTestGraph.Companion.NOW
import de.tergelius.ttsync.ui.UiTestGraph.Companion.SIGNED_IN
import de.tergelius.ttsync.ui.UiTestGraph.Companion.calendar
import de.tergelius.ttsync.ui.UiTestGraph.Companion.target
import de.tergelius.ttsync.ui.settings.SettingsViewModel
import de.tergelius.ttsync.ui.settings.SignOutViewModel
import de.tergelius.ttsync.ui.sources.SourcesError
import de.tergelius.ttsync.ui.sources.SourcesViewModel
import de.tergelius.ttsync.ui.status.StatusViewModel
import de.tergelius.ttsync.ui.target.TargetViewModel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The engine reports a failed removal in `RemovalReport.failure` instead of throwing. Every screen
 * that removes copies must show the error and leave the sign-in, the selection and the target as
 * they were.
 */
class RemovalFailureViewModelsTest {
  @get:Rule val main = MainDispatcherRule()

  private val configured =
    Settings(
      sources = listOf(SourceCalendar(1, "Family"), SourceCalendar(2, "Football")),
      target = target(15),
      targetDisplayName = "TimeTree",
      interval = SyncInterval.HOURS_1,
      keepRunningDone = true,
    )

  private fun failingGraph(status: SyncStatus = SyncStatus()) =
    UiTestGraph(settings = configured, status = status, credentials = SIGNED_IN).apply {
      gateway.calendars = listOf(calendar(15, "TimeTree"), calendar(16, "Other"))
      engine.removalFailure = SyncFailure(SyncProblem.CALENDAR_WRITE_FAILED)
    }

  @Test
  fun sourcesShowTheRemovalErrorAndKeepTheSelection() {
    val g = failingGraph()
    g.client.calendarsResult = { listOf(TtCalendar(1, "Family"), TtCalendar(2, "Football")) }
    val vm = SourcesViewModel(g.settings, g.actions)
    vm.toggle(2)
    vm.save()
    vm.confirmRemoval()
    assertEquals(SourcesError.REMOVAL_FAILED, vm.state.value.saveError)
    assertNull(vm.state.value.next)
    assertEquals(configured.sources, g.settings.flow.value.sources)
  }

  @Test
  fun targetSwitchShowsTheErrorAndKeepsTheOldTarget() {
    val g = failingGraph()
    val vm = TargetViewModel(g.gateway, g.settings, g.deviceChecks, g.actions)
    vm.refresh()
    vm.select(16)
    vm.confirm()
    vm.confirmSwitch()
    assertTrue(vm.state.value.saveFailed)
    assertNull(vm.state.value.switchResult)
    assertNull(vm.state.value.next)
    assertEquals(target(15), g.settings.flow.value.target)
  }

  @Test
  fun removeAllCopiesShowsTheRemovalError() = runTest {
    val g = failingGraph()
    val vm = SettingsViewModel(g.settings, g.actions)
    backgroundScope.launch(main.dispatcher) { vm.state.collect {} }
    vm.askRemoveAll()
    vm.confirmRemoveAll()
    assertTrue(vm.state.value.removalFailed)
    assertNull(vm.state.value.removal)
    assertFalse(vm.state.value.confirmRemoveAll)
    assertEquals(configured.sources, g.settings.flow.value.sources)
  }

  @Test
  fun signOutStaysSignedInWhenTheRemovalFails() {
    val g = failingGraph()
    val vm = SignOutViewModel(g.settings, g.actions)
    vm.open()
    vm.confirm()
    assertTrue(vm.state.value.failed)
    assertTrue(vm.state.value.dialogOpen)
    assertFalse(vm.state.value.done)
    assertEquals(SIGNED_IN, g.credentials.stored)
    assertEquals(configured, g.settings.flow.value)
  }

  @Test
  fun removingPausedCalendarsShowsTheErrorAndKeepsThem() = runTest {
    val paused =
      SyncStatus(
        lastReport =
          SyncReport(
            runId = "r",
            trigger = SyncTrigger.PERIODIC,
            startedAt = NOW - 60_000,
            finishedAt = NOW - 50_000,
            calendars = listOf(CalendarReport(2, mode = null, state = CalendarState.PAUSED)),
          ),
        lastSuccessAt = NOW,
      )
    val g = failingGraph(paused)
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
    vm.confirmRemovePaused()
    assertTrue(vm.state.value.removalFailed)
    assertTrue(vm.state.value.actionFailed)
    assertEquals(configured.sources, g.settings.flow.value.sources)
    assertEquals(listOf(SourceCalendar(2, "Football")), vm.state.value.pausedCalendars)
  }
}
