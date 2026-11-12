package de.tergelius.ttsync.settings

import de.tergelius.ttsync.calendar.TargetCalendar
import de.tergelius.ttsync.store.CalendarState
import de.tergelius.ttsync.sync.CalendarReport
import de.tergelius.ttsync.sync.SyncFailure
import de.tergelius.ttsync.sync.SyncProblem
import de.tergelius.ttsync.sync.SyncReport
import de.tergelius.ttsync.sync.SyncTrigger
import de.tergelius.ttsync.sync.SyncWarning
import kotlin.time.Duration.Companion.hours
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsModelsTest {
  private val hour = 1.hours.inWholeMilliseconds

  @Test
  fun setupStepFollowsProgress() {
    val target = TargetCalendar(15, "user@example.org", "com.google")
    val sources = listOf(SourceCalendar(7, "Family"))
    assertEquals(SetupStep.LOGIN, SetupStep.of(false, Settings()))
    assertEquals(SetupStep.SOURCES, SetupStep.of(true, Settings()))
    assertEquals(SetupStep.TARGET, SetupStep.of(true, Settings(sources = sources)))
    assertEquals(
      SetupStep.KEEP_RUNNING,
      SetupStep.of(true, Settings(sources = sources, target = target)),
    )
    assertEquals(
      SetupStep.DONE,
      SetupStep.of(true, Settings(sources = sources, target = target, keepRunningDone = true)),
    )
  }

  @Test
  fun intervalsAreNeverShorterThanAnHour() {
    assertTrue(SyncInterval.entries.all { it.duration >= 1.hours })
    assertEquals(SyncInterval.HOURS_1, SyncInterval.DEFAULT)
  }

  @Test
  fun autoLoginBlockAndStop() {
    val now = 100 * hour
    assertTrue(SyncStatus().mayAutoLogin(now))
    val blocked = SyncStatus(authRejections = 1, autoLoginBlockedUntil = now + 6 * hour)
    assertFalse(blocked.mayAutoLogin(now))
    assertTrue(blocked.mayAutoLogin(now + 6 * hour))
    val stopped = SyncStatus(authRejections = 2, autoLoginBlockedUntil = now - hour)
    assertTrue(stopped.autoLoginStopped)
    assertFalse(stopped.mayAutoLogin(now))
  }

  @Test
  fun healthStates() {
    val now = 100 * hour
    fun report(failure: SyncFailure? = null, paused: Boolean = false, warn: Boolean = false) =
      SyncReport(
        runId = "r",
        trigger = SyncTrigger.PERIODIC,
        startedAt = now - 1,
        finishedAt = now,
        failure = failure,
        calendars = listOf(CalendarReport(7, null, if (paused) CalendarState.PAUSED else CalendarState.ACTIVE)),
        warnings = if (warn) setOf(SyncWarning.GOOGLE_SYNC_OFF) else emptySet(),
      )

    val ok = SyncStatus(lastReport = report(warn = true), lastSuccessAt = now)
    assertEquals(SyncHealth.OK, ok.health(running = false, nowMillis = now))
    assertTrue(ok.googleSyncOff)
    assertEquals(SyncHealth.RUNNING, ok.health(running = true, nowMillis = now))
    assertEquals(SyncHealth.NEEDS_ATTENTION, ok.health(running = false, nowMillis = now + 25 * hour))

    val deleted = SyncStatus(lastReport = report(SyncFailure(SyncProblem.TARGET_DELETED)))
    assertEquals(SyncHealth.TARGET_DELETED, deleted.health(running = false, nowMillis = now))

    val paused = SyncStatus(lastReport = report(paused = true), lastSuccessAt = now)
    assertEquals(SyncHealth.PAUSED, paused.health(running = false, nowMillis = now))

    val flaky = SyncStatus(lastReport = report(SyncFailure(SyncProblem.NETWORK)), consecutiveFailures = 2)
    assertEquals(SyncHealth.OK, flaky.health(running = false, nowMillis = now))
    assertTrue(flaky.copy(consecutiveFailures = 3).shouldNotifyFailure(now))
  }

  @Test
  fun credentialsAreRedacted() {
    assertFalse(Credentials("me@example.org", "hunter2", "cookie").toString().contains("hunter2"))
  }
}
