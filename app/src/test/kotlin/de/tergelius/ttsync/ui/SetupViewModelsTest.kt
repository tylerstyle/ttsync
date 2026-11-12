package de.tergelius.ttsync.ui

import de.tergelius.ttsync.settings.Settings
import de.tergelius.ttsync.settings.SetupStep
import de.tergelius.ttsync.settings.SourceCalendar
import de.tergelius.ttsync.timetree.TtCalendar
import de.tergelius.ttsync.timetree.TtError
import de.tergelius.ttsync.ui.UiTestGraph.Companion.SIGNED_IN
import de.tergelius.ttsync.ui.UiTestGraph.Companion.calendar
import de.tergelius.ttsync.ui.UiTestGraph.Companion.target
import de.tergelius.ttsync.ui.login.LoginError
import de.tergelius.ttsync.ui.login.LoginViewModel
import de.tergelius.ttsync.ui.reliability.KeepRunningViewModel
import de.tergelius.ttsync.ui.sources.SourceOption
import de.tergelius.ttsync.ui.sources.SourcesError
import de.tergelius.ttsync.ui.sources.SourcesViewModel
import de.tergelius.ttsync.ui.target.TargetSwitch
import de.tergelius.ttsync.ui.target.TargetViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SetupViewModelsTest {
  @get:Rule val main = MainDispatcherRule()

  // Login

  @Test
  fun loginNeedsEmailAndPassword() {
    val vm = LoginViewModel(UiTestGraph().actions)
    assertFalse(vm.state.value.canSubmit)
    vm.onEmailChange("me@example.org")
    assertFalse(vm.state.value.canSubmit)
    vm.onPasswordChange("secret")
    assertTrue(vm.state.value.canSubmit)
  }

  @Test
  fun loginSuccessMovesToSourcesAndForgetsThePassword() {
    val g = UiTestGraph()
    val vm = LoginViewModel(g.actions)
    vm.onEmailChange("  me@example.org ")
    vm.onPasswordChange("secret")
    vm.submit()
    assertEquals(SetupStep.SOURCES, vm.state.value.next)
    assertEquals("", vm.state.value.password)
    assertEquals("me@example.org", g.credentials.stored?.email)
    assertFalse(vm.state.value.toString().contains("secret"))
  }

  @Test
  fun loginErrorsAreMapped() {
    val g = UiTestGraph()
    val vm = LoginViewModel(g.actions)
    vm.onEmailChange("me@example.org")
    vm.onPasswordChange("wrong")

    g.client.signInResult = { throw TtError.AuthRejected(code = -702) }
    vm.submit()
    assertEquals(LoginError.REJECTED, vm.state.value.error)
    assertFalse(vm.state.value.busy)

    g.client.signInResult = { throw TtError.Transient(httpStatus = null) }
    vm.submit()
    assertEquals(LoginError.OFFLINE, vm.state.value.error)

    g.client.signInResult = { throw TtError.RateLimited(retryAfter = null) }
    vm.submit()
    assertEquals(LoginError.RATE_LIMITED, vm.state.value.error)
    assertEquals(30L, vm.state.value.retryAfterMinutes)

    g.client.signInResult = { throw TtError.ApiChanged("x") }
    vm.submit()
    assertEquals(LoginError.API_CHANGED, vm.state.value.error)
    assertNull(vm.state.value.next)
  }

  // Sources

  @Test
  fun sourcesListActiveCalendarsAndKeepLostOnesDeselectable() {
    val options =
      SourcesViewModel.optionsOf(
        calendars =
          listOf(
            TtCalendar(1, "Family"),
            TtCalendar(2, "Old", deactivatedAt = 5),
            TtCalendar(3, null),
          ),
        selected = listOf(SourceCalendar(1, "Family"), SourceCalendar(9, "Gone")),
      )
    assertEquals(
      listOf(
        SourceOption(1, "Family", selected = true),
        SourceOption(3, null, selected = false),
        SourceOption(9, "Gone", selected = true, available = false),
      ),
      options,
    )
  }

  @Test
  fun sourcesAskBeforeDeletingCopies() {
    val g =
      UiTestGraph(
        settings =
          Settings(
            sources = listOf(SourceCalendar(1, "Family"), SourceCalendar(2, "Football")),
            target = target(15),
            keepRunningDone = true,
          ),
        credentials = SIGNED_IN,
      )
    g.client.calendarsResult = { listOf(TtCalendar(1, "Family"), TtCalendar(2, "Football")) }
    val vm = SourcesViewModel(g.settings, g.actions)
    assertFalse(vm.state.value.loading)
    vm.toggle(2)
    vm.save()
    assertEquals(listOf(SourceCalendar(2, "Football")), vm.state.value.confirmRemoval)
    assertTrue(g.calls.log.isEmpty())

    vm.confirmRemoval()
    assertNull(vm.state.value.confirmRemoval)
    assertEquals(SetupStep.DONE, vm.state.value.next)
    assertEquals(
      listOf("engine.deselect([2])", "notifier.clearAccessLost(2)", "settings.setSources([1])"),
      g.calls.log.take(3),
    )
  }

  @Test
  fun sourcesNeedOneSelectionAndContinueToTarget() {
    val g = UiTestGraph(credentials = SIGNED_IN)
    g.client.calendarsResult = { listOf(TtCalendar(1, "Family")) }
    val vm = SourcesViewModel(g.settings, g.actions)
    assertFalse(vm.state.value.canSave)
    vm.toggle(1)
    vm.save()
    assertEquals(SetupStep.TARGET, vm.state.value.next)
    assertEquals(listOf(SourceCalendar(1, "Family")), g.settings.flow.value.sources)
  }

  @Test
  fun sourcesShowSignInAgainWhenTheSessionIsGone() {
    val g = UiTestGraph(credentials = SIGNED_IN)
    g.client.calendarsResult = { throw TtError.LoginRequired() }
    val vm = SourcesViewModel(g.settings, g.actions)
    assertEquals(SourcesError.SIGN_IN, vm.state.value.loadError)
  }

  // Target

  @Test
  fun targetWithoutPermissionLoadsNothing() {
    val g = UiTestGraph(device = DeviceState(calendarPermission = false))
    g.gateway.calendars = listOf(calendar(15))
    val vm = TargetViewModel(g.gateway, g.settings, g.deviceChecks, g.actions)
    vm.refresh()
    assertEquals(false, vm.state.value.permission)
    assertTrue(vm.state.value.options.isEmpty())
    assertFalse(vm.state.value.canConfirm)
  }

  @Test
  fun targetListsOnlyWritableSyncedGoogleCalendars() {
    val g = UiTestGraph()
    g.gateway.calendars =
      listOf(
        calendar(15, "TimeTree"),
        calendar(16, "Holidays", access = 200),
        calendar(17, "Exchange", type = "com.android.exchange"),
        calendar(18, "Not synced").copy(syncEvents = false),
        calendar(19, "Personal"),
      )
    g.gateway.foreign = mapOf(19L to 7)
    val vm = TargetViewModel(g.gateway, g.settings, g.deviceChecks, g.actions)
    vm.refresh()
    val options = vm.state.value.options
    assertEquals(listOf(19L, 15L), options.map { it.info.id }) // sorted by name
    assertEquals(7, options.first().otherEvents)
    assertEquals(0, options.last().otherEvents)
  }

  @Test
  fun confirmingTheTargetStartsTheFirstSync() {
    val g =
      UiTestGraph(
        settings = Settings(sources = listOf(SourceCalendar(1, "Family"))),
        credentials = SIGNED_IN,
      )
    g.gateway.calendars = listOf(calendar(15, "TimeTree"))
    val vm = TargetViewModel(g.gateway, g.settings, g.deviceChecks, g.actions)
    vm.refresh()
    vm.select(15)
    vm.confirm()
    assertEquals(SetupStep.KEEP_RUNNING, vm.state.value.next)
    assertTrue("scheduler.runNow(full=true, SETUP)" in g.calls.log)
  }

  @Test
  fun changingTheTargetAsksAndReportsTheRemoval() {
    val g =
      UiTestGraph(
        settings =
          Settings(
            sources = listOf(SourceCalendar(1, "Family")),
            target = target(15),
            targetDisplayName = "TimeTree",
            keepRunningDone = true,
          ),
        credentials = SIGNED_IN,
      )
    g.gateway.calendars = listOf(calendar(15, "TimeTree"), calendar(16, "Other"))
    val vm = TargetViewModel(g.gateway, g.settings, g.deviceChecks, g.actions)
    vm.refresh()
    assertEquals(15L, vm.state.value.selectedId)
    vm.select(16)
    vm.confirm()
    assertEquals(
      TargetSwitch(oldName = "TimeTree", newName = "Other"),
      vm.state.value.confirmSwitch,
    )
    assertTrue(g.calls.log.isEmpty())

    vm.confirmSwitch()
    assertNotNull(vm.state.value.switchResult)
    assertNull(vm.state.value.next)
    vm.dismissResult()
    assertEquals(SetupStep.DONE, vm.state.value.next)
    assertEquals("engine.removeAllCopies(15)", g.calls.log[1])
  }

  // Keep running

  @Test
  fun keepRunningCanBeSkipped() {
    val g = UiTestGraph(device = DeviceState(batteryUnrestricted = false, manufacturer = "Xiaomi"))
    val vm = KeepRunningViewModel(g.deviceChecks, g.actions)
    vm.refresh()
    assertFalse(vm.state.value.complete)
    assertTrue(vm.state.value.device!!.xiaomiFamily)
    vm.finish()
    assertTrue(g.settings.flow.value.keepRunningDone)
    assertEquals(SetupStep.LOGIN, vm.state.value.next) // not signed in in this graph
  }
}
