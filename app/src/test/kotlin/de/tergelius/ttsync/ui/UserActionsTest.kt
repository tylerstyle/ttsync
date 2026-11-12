package de.tergelius.ttsync.ui

import de.tergelius.ttsync.mapping.EventFilter
import de.tergelius.ttsync.settings.Settings
import de.tergelius.ttsync.settings.SetupStep
import de.tergelius.ttsync.settings.SourceCalendar
import de.tergelius.ttsync.settings.SyncInterval
import de.tergelius.ttsync.settings.SyncStatus
import de.tergelius.ttsync.sync.RemovalReport
import de.tergelius.ttsync.sync.SyncFailure
import de.tergelius.ttsync.sync.SyncProblem
import de.tergelius.ttsync.sync.SyncReport
import de.tergelius.ttsync.sync.SyncTrigger
import de.tergelius.ttsync.timetree.TtError
import de.tergelius.ttsync.ui.UiTestGraph.Companion.NOW
import de.tergelius.ttsync.ui.UiTestGraph.Companion.SIGNED_IN
import de.tergelius.ttsync.ui.UiTestGraph.Companion.calendar
import de.tergelius.ttsync.ui.UiTestGraph.Companion.target
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class UserActionsTest {
  private val family = SourceCalendar(1, "Family")
  private val football = SourceCalendar(2, "Football")
  private val configured =
    Settings(
      sources = listOf(family, football),
      target = target(15),
      targetDisplayName = "TimeTree",
      interval = SyncInterval.HOURS_3,
      keepRunningDone = true,
    )

  @Test
  fun signInStoresCredentialsAndContinuesWithSources() = runTest {
    val g = UiTestGraph()
    val next = g.actions.signIn("me@example.org", "secret")
    assertEquals(SetupStep.SOURCES, next)
    assertEquals("session", g.credentials.stored?.sessionId)
    assertEquals(
      listOf("client.signIn(me@example.org)", "credentials.save", "status.recordLoginSucceeded"),
      g.calls.log,
    )
  }

  @Test
  fun signingInAgainAfterSetupStartsASync() = runTest {
    val g = UiTestGraph(settings = configured)
    assertEquals(SetupStep.DONE, g.actions.signIn("me@example.org", "secret"))
    assertEquals("scheduler.runNow(full=false, MANUAL)", g.calls.log.last())
  }

  @Test
  fun syncNowRightAfterASuccessfulRunQueuesNothing() = runTest {
    // Device test F4: a delayed run can wait half an hour while the app is in the background.
    val last = SyncReport("r", SyncTrigger.MANUAL, startedAt = NOW - 20_000, finishedAt = NOW - 10_000)
    val g = UiTestGraph(settings = configured, status = SyncStatus(lastReport = last), credentials = SIGNED_IN)
    assertEquals(SyncNowResult.RecentlySynced(10_000), g.actions.syncNow())
    assertTrue(g.calls.log.none { it.startsWith("scheduler.runNow") })

    g.actions.fullResync() // FULL always runs
    assertEquals("scheduler.runNow(full=true, MANUAL)", g.calls.log.last())

    g.status.flow.value = SyncStatus(lastReport = last.copy(finishedAt = NOW - 61_000))
    assertEquals(SyncNowResult.Started, g.actions.syncNow())
    assertEquals("scheduler.runNow(full=false, MANUAL)", g.calls.log.last())
  }

  @Test
  fun rateLimitedSignInIsRecordedForTheWorker() = runTest {
    val g = UiTestGraph()
    g.client.signInResult = { throw TtError.RateLimited(retryAfter = null) }
    try {
      g.actions.signIn("me@example.org", "secret")
      fail("expected RateLimited")
    } catch (_: TtError.RateLimited) {}
    assertEquals(NOW + 30.minutes.inWholeMilliseconds, g.status.flow.value.rateLimitedUntil)
    assertNull(g.credentials.stored)
  }

  @Test
  fun sourcesWithoutTargetOnlySaveTheSelection() = runTest {
    val g = UiTestGraph(credentials = SIGNED_IN)
    val next = g.actions.saveSources(listOf(family))
    assertEquals(SetupStep.TARGET, next)
    assertEquals(listOf("settings.setSources([1])"), g.calls.log)
  }

  @Test
  fun deselectingDeletesCopiesFirstThenSavesAndAddingStartsAFullSync() = runTest {
    val g = UiTestGraph(settings = configured, credentials = SIGNED_IN)
    val newCal = SourceCalendar(3, "School")
    val change = g.actions.sourceChange(listOf(family, newCal))
    assertTrue(change.removesCopies)
    assertEquals(listOf(football), change.removed)

    g.actions.saveSources(listOf(family, newCal))
    assertEquals(
      listOf(
        "engine.deselect([2])",
        "notifier.clearAccessLost(2)",
        "settings.setSources([1, 3])",
        "scheduler.updatePeriodic(HOURS_3)",
        "scheduler.runNow(full=true, SETUP)",
      ),
      g.calls.log,
    )
  }

  @Test
  fun aChangedFilterStartsAFullSyncAndKeepsEveryCalendar() = runTest {
    val g = UiTestGraph(settings = configured, credentials = SIGNED_IN)
    val filtered = family.copy(filter = EventFilter(labelIds = setOf(3)))
    val change = g.actions.sourceChange(listOf(filtered, football))
    assertEquals(listOf(filtered), change.filterChanged)
    assertFalse(change.removesCopies)

    g.actions.saveSources(listOf(filtered, football))
    assertEquals(listOf("settings.setSources([1, 2])", "scheduler.runNow(full=true, SETUP)"), g.calls.log)
    assertEquals(listOf(filtered, football), g.settings.flow.value.sources)
  }

  @Test
  fun anEquivalentFilterStartsNoSync() = runTest {
    val stored = family.copy(filter = EventFilter(includeTerms = listOf("Training", "gym")))
    val g = UiTestGraph(settings = configured.copy(sources = listOf(stored, football)), credentials = SIGNED_IN)
    val same = family.copy(filter = EventFilter(includeTerms = listOf(" gym", "Training")))
    // An empty filter is no filter.
    val none = football.copy(filter = EventFilter(excludeTerms = listOf(" ")))
    assertTrue(g.actions.sourceChange(listOf(same, none)).filterChanged.isEmpty())
    g.actions.saveSources(listOf(same, none))
    assertEquals(listOf("settings.setSources([1, 2])"), g.calls.log)
  }

  @Test
  fun aFilterWithoutTargetOnlySavesTheSelection() = runTest {
    val g = UiTestGraph(settings = Settings(sources = listOf(family)), credentials = SIGNED_IN)
    g.actions.saveSources(listOf(family.copy(filter = EventFilter(labelIds = setOf(3)))))
    assertEquals(listOf("settings.setSources([1])"), g.calls.log)
  }

  @Test
  fun filterImpactAsksTheEngine() = runTest {
    val g = UiTestGraph(settings = configured, credentials = SIGNED_IN)
    g.engine.impact = 7
    assertEquals(7, g.actions.filterImpact(1, EventFilter(labelIds = setOf(3))))
    assertEquals(listOf("engine.filterImpact(1)"), g.calls.log)
  }

  @Test
  fun confirmingTheFirstTargetStartsAnImmediateFullSyncAndThePeriodicWork() = runTest {
    val g = UiTestGraph(settings = Settings(sources = listOf(family)), credentials = SIGNED_IN)
    g.gateway.calendars = listOf(calendar(15, "TimeTree"))
    val change = g.actions.setTarget(calendar(15, "TimeTree"))
    assertEquals(SetupStep.KEEP_RUNNING, change.next)
    assertNull(change.removal)
    assertEquals(
      listOf(
        "settings.setTarget(15)",
        "scheduler.updatePeriodic(HOURS_1)",
        "scheduler.runNow(full=true, SETUP)",
      ),
      g.calls.log,
    )
    assertEquals("TimeTree", g.settings.flow.value.targetDisplayName)
  }

  @Test
  fun switchingTargetsStopsWorkAndRemovesOldCopiesBeforeSaving() = runTest {
    val g = UiTestGraph(settings = configured, credentials = SIGNED_IN)
    g.gateway.calendars = listOf(calendar(15, "TimeTree"), calendar(16, "Other"))
    g.engine.removal = RemovalReport(deleted = 4, rejected = 0)
    val change = g.actions.setTarget(calendar(16, "Other"))
    assertEquals(4, change.removal?.deleted)
    assertEquals(
      listOf(
        "scheduler.cancelAll",
        "engine.removeAllCopies(15)",
        "settings.setTarget(16)",
        "scheduler.updatePeriodic(HOURS_3)",
        "scheduler.runNow(full=true, SETUP)",
      ),
      g.calls.log,
    )
  }

  @Test
  fun switchingAwayFromADeletedTargetOnlyClearsTheMappings() = runTest {
    val g = UiTestGraph(settings = configured, credentials = SIGNED_IN)
    g.gateway.calendars = listOf(calendar(16, "Other")) // 15 was deleted on the web
    val change = g.actions.setTarget(calendar(16, "Other"))
    assertNull(change.removal)
    assertTrue("engine.dropMappings" in g.calls.log)
    assertTrue(g.calls.log.none { it.startsWith("engine.removeAllCopies") })
  }

  @Test
  fun removeAllCopiesStopsSyncingAndReturnsToSources() = runTest {
    val g = UiTestGraph(settings = configured, credentials = SIGNED_IN)
    g.gateway.calendars = listOf(calendar(15, "TimeTree"))
    g.engine.removal = RemovalReport(deleted = 12, rejected = 1)
    val report = g.actions.removeAllCopies()
    assertEquals(12, report.deleted)
    // The engine clears the selection itself; nothing deselects a second time (review fix1, NIT-3).
    assertEquals(
      listOf(
        "scheduler.cancelAll",
        "engine.removeAllCopies(15)",
        "notifier.clearFailure",
        "notifier.clearAccessLost(1)",
        "notifier.clearAccessLost(2)",
      ),
      g.calls.log,
    )
    assertEquals(SetupStep.SOURCES, g.actions.currentStep())
    assertEquals(target(15), g.settings.flow.value.target)
  }

  @Test
  fun removeAllCopiesWithoutATargetOnlyClearsTheSelection() = runTest {
    val g = UiTestGraph(settings = configured.copy(target = null), credentials = SIGNED_IN)
    val report = g.actions.removeAllCopies()
    assertTrue(report.succeeded)
    assertEquals(
      listOf(
        "scheduler.cancelAll",
        "engine.dropMappings",
        "engine.deselect([1, 2])",
        "settings.setSources([])",
        "notifier.clearFailure",
      ),
      g.calls.log.take(5),
    )
    assertTrue(g.settings.flow.value.sources.isEmpty())
  }

  @Test
  fun signOutRemovesCopiesThenWipesEverything() = runTest {
    val g = UiTestGraph(settings = configured, credentials = SIGNED_IN)
    g.gateway.calendars = listOf(calendar(15, "TimeTree"))
    assertNotNull(g.actions.signOut(removeCopies = true))
    assertEquals(
      listOf(
        "scheduler.cancelAll",
        "engine.removeAllCopies(15)",
        "credentials.clear",
        "settings.clear",
        "status.clear",
        "wipeLocalFiles",
        "notifier.clearFailure",
      ),
      g.calls.log.take(7),
    )
    assertEquals(SetupStep.LOGIN, g.actions.currentStep())
  }

  @Test
  fun signOutKeepsEverythingWhenTheRemovalFails() = runTest {
    val g = UiTestGraph(settings = configured, credentials = SIGNED_IN)
    g.gateway.calendars = listOf(calendar(15, "TimeTree"))
    g.engine.removeAllError = SecurityException("no permission")
    try {
      g.actions.signOut(removeCopies = true)
      fail("expected the removal error")
    } catch (_: SecurityException) {}
    assertNotNull(g.credentials.stored)
    assertTrue(g.calls.log.none { it.endsWith(".clear") })
  }

  @Test
  fun signOutWithoutRemovalLeavesTheCopies() = runTest {
    val g = UiTestGraph(settings = configured, credentials = SIGNED_IN)
    assertNull(g.actions.signOut(removeCopies = false))
    assertTrue(g.calls.log.none { it.startsWith("engine.") })
    assertNull(g.credentials.stored)
  }

  @Test
  fun removingTheLastPausedCalendarCancelsTheWork() = runTest {
    val g =
      UiTestGraph(settings = configured.copy(sources = listOf(family)), credentials = SIGNED_IN)
    g.actions.removePaused(setOf(1))
    assertEquals(
      listOf("engine.deselect([1])", "notifier.clearAccessLost(1)", "scheduler.cancelAll"),
      g.calls.log,
    )
    assertTrue(g.settings.flow.value.sources.isEmpty())
  }

  // A failed removal (RemovalReport.failure) changes nothing else ---------------------------------

  private val noPermission = SyncFailure(SyncProblem.NO_CALENDAR_PERMISSION)

  @Test
  fun aFailedDeselectKeepsTheSelectionAndStartsNoSync() = runTest {
    val g = UiTestGraph(settings = configured, credentials = SIGNED_IN)
    g.engine.removalFailure = noPermission
    try {
      g.actions.saveSources(listOf(family, SourceCalendar(3, "School")))
      fail("expected RemovalFailedException")
    } catch (e: RemovalFailedException) {
      assertEquals(SyncProblem.NO_CALENDAR_PERMISSION, e.failure.problem)
    }
    assertEquals(configured.sources, g.settings.flow.value.sources)
    assertEquals(listOf("engine.deselect([2])"), g.calls.log)
  }

  @Test
  fun aFailedTargetSwitchKeepsTheOldTargetAndItsSync() = runTest {
    val g = UiTestGraph(settings = configured, credentials = SIGNED_IN)
    g.gateway.calendars = listOf(calendar(15, "TimeTree"), calendar(16, "Other"))
    g.engine.removalFailure = noPermission
    val change = g.actions.setTarget(calendar(16, "Other"))
    assertTrue(change.failed)
    assertEquals(target(15), g.settings.flow.value.target)
    assertEquals(configured.sources, g.settings.flow.value.sources)
    assertEquals(
      listOf(
        "scheduler.cancelAll",
        "engine.removeAllCopies(15)",
        "scheduler.updatePeriodic(HOURS_3)",
      ),
      g.calls.log,
    )
  }

  @Test
  fun aFailedRemoveAllCopiesKeepsTheSelectionAndRestoresTheSync() = runTest {
    val g = UiTestGraph(settings = configured, credentials = SIGNED_IN)
    g.gateway.calendars = listOf(calendar(15, "TimeTree"))
    g.engine.removalFailure = noPermission
    val report = g.actions.removeAllCopies()
    assertEquals(SyncProblem.NO_CALENDAR_PERMISSION, report.failure?.problem)
    assertEquals(configured.sources, g.settings.flow.value.sources)
    assertEquals(SetupStep.DONE, g.actions.currentStep())
    assertEquals(
      listOf(
        "scheduler.cancelAll",
        "engine.removeAllCopies(15)",
        "scheduler.updatePeriodic(HOURS_3)",
      ),
      g.calls.log,
    )
  }

  @Test
  fun aRevokedPermissionComesBackAsARemovalFailureNotAnException() = runTest {
    // Phase 5 review, SHOULD-FIX-1: the target check is the engine's, so a SecurityException from
    // the provider cannot escape past it.
    val g = UiTestGraph(settings = configured, credentials = SIGNED_IN)
    g.gateway.calendarError = SecurityException("READ_CALENDAR revoked")
    g.engine.removalFailure = noPermission
    assertEquals(SyncProblem.NO_CALENDAR_PERMISSION, g.actions.removeAllCopies().failure?.problem)
    val signOut = g.actions.signOut(removeCopies = true)
    assertEquals(SyncProblem.NO_CALENDAR_PERMISSION, signOut?.failure?.problem)
    assertEquals(SIGNED_IN, g.credentials.stored)
    assertEquals(configured, g.settings.flow.value)
  }

  @Test
  fun removingAllCopiesFromADeletedTargetLetsTheEngineDecide() = runTest {
    val g = UiTestGraph(settings = configured, credentials = SIGNED_IN)
    g.gateway.calendars = emptyList() // 15 was deleted on the web, together with its rows
    val report = g.actions.removeAllCopies()
    assertTrue(report.succeeded)
    assertEquals(
      listOf("scheduler.cancelAll", "engine.removeAllCopies(15)", "notifier.clearFailure"),
      g.calls.log.take(3),
    )
    assertTrue(g.settings.flow.value.sources.isEmpty())
  }

  @Test
  fun aFailedRemovalAtSignOutKeepsTheSignInAndEverythingElse() = runTest {
    val g = UiTestGraph(settings = configured, credentials = SIGNED_IN)
    g.gateway.calendars = listOf(calendar(15, "TimeTree"))
    g.engine.removalFailure = noPermission
    val report = g.actions.signOut(removeCopies = true)
    assertEquals(SyncProblem.NO_CALENDAR_PERMISSION, report?.failure?.problem)
    assertEquals(SIGNED_IN, g.credentials.stored)
    assertEquals(configured, g.settings.flow.value)
    assertTrue(g.calls.log.none { it.endsWith(".clear") || it == "wipeLocalFiles" })
    assertEquals("scheduler.updatePeriodic(HOURS_3)", g.calls.log.last())
  }

  @Test
  fun aFailedRemovalOfPausedCalendarsKeepsThemSelected() = runTest {
    val g = UiTestGraph(settings = configured, credentials = SIGNED_IN)
    g.engine.removalFailure = noPermission
    val report = g.actions.removePaused(setOf(2))
    assertEquals(SyncProblem.NO_CALENDAR_PERMISSION, report.failure?.problem)
    assertEquals(configured.sources, g.settings.flow.value.sources)
    assertEquals(listOf("engine.deselect([2])"), g.calls.log)
  }

  @Test
  fun aRateLimitWhileListingCalendarsIsRecordedForTheWorker() = runTest {
    val g = UiTestGraph(credentials = SIGNED_IN)
    g.client.calendarsResult = { throw TtError.RateLimited(retryAfter = null) }
    try {
      g.actions.timeTreeCalendars()
      fail("expected RateLimited")
    } catch (_: TtError.RateLimited) {}
    assertEquals(NOW + 30.minutes.inWholeMilliseconds, g.status.flow.value.rateLimitedUntil)
  }
}
