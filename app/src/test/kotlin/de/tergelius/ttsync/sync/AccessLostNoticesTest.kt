package de.tergelius.ttsync.sync

import de.tergelius.ttsync.store.CalendarState
import org.junit.Assert.assertEquals
import org.junit.Test

/** Issue #1: the "No access" notification comes once per loss of access. */
class AccessLostNoticesTest {
  private val a = 1L
  private val b = 2L
  private val selected = setOf(a, b)

  private fun report(vararg calendars: Pair<Long, CalendarState>, failed: Boolean = false) =
    SyncReport(
      runId = "r",
      trigger = SyncTrigger.PERIODIC,
      startedAt = 0,
      finishedAt = 1,
      failure = if (failed) SyncFailure(SyncProblem.NETWORK) else null,
      calendars = calendars.map { (id, state) -> CalendarReport(id, mode = null, state = state) },
    )

  private val paused = CalendarState.PAUSED
  private val active = CalendarState.ACTIVE

  @Test
  fun aRunThatFailsEarlyInBetweenDoesNotNotifyAgain() {
    // The issue's steps: A loses access; the next run fails before it lists any calendar; the run
    // after it finds A still paused.
    val first = AccessLostNotices.decide(report(a to paused, b to active), emptySet(), null, selected)
    assertEquals(listOf(a), first.notify)
    val failed = report(failed = true)
    val second = AccessLostNotices.decide(failed, first.notified, null, selected)
    assertEquals(emptyList<Long>(), second.notify)
    assertEquals(setOf(a), second.notified)
    val third = AccessLostNotices.decide(report(a to paused, b to active), second.notified, failed, selected)
    assertEquals(emptyList<Long>(), third.notify) // the old rule notified A again here
    assertEquals(setOf(a), third.notified)
  }

  @Test
  fun aRunThatListsOnlySomeCalendarsForgetsNoneOfTheOthers() {
    // Failed halfway: B was listed, A not. A stays notified.
    val partial = AccessLostNotices.decide(report(b to active, failed = true), setOf(a), null, selected)
    assertEquals(setOf(a), partial.notified)
    assertEquals(emptyList<Long>(), AccessLostNotices.decide(report(a to paused), partial.notified, null, selected).notify)
  }

  @Test
  fun aCalendarActiveAgainIsNotifiedAgainWhenItLosesAccessOnceMore() {
    val back = AccessLostNotices.decide(report(a to active, b to active), setOf(a), null, selected)
    assertEquals(emptySet<Long>(), back.notified)
    assertEquals(listOf(a), AccessLostNotices.decide(report(a to paused), back.notified, null, selected).notify)
  }

  @Test
  fun severalAtOnceAndADeselectedCalendarIsForgotten() {
    val both = AccessLostNotices.decide(report(a to paused, b to paused), emptySet(), null, selected)
    assertEquals(listOf(a, b), both.notify)
    assertEquals(setOf(a, b), both.notified)
    // B deselected: it leaves the set; selected again and paused later, it is notified again.
    assertEquals(setOf(a), AccessLostNotices.decide(report(a to paused), both.notified, null, setOf(a)).notified)
  }

  @Test
  fun anEmptySelectionForgetsNothing() {
    // Settings that could not be read come back empty; a run always has a calendar (review
    // 2026-11, B3).
    val unread = AccessLostNotices.decide(report(failed = true), setOf(a), null, emptySet())
    assertEquals(emptyList<Long>(), unread.notify)
    assertEquals(setOf(a), unread.notified)
  }

  @Test
  fun withoutAStoredSetThePreviousReportCountsAsBefore() {
    // Updating from 0.2.1: the previous report's paused calendars were notified under the old rule.
    val previous = report(a to paused, b to active)
    val first = AccessLostNotices.decide(report(a to paused, b to paused), null, previous, selected)
    assertEquals(listOf(b), first.notify)
    assertEquals(setOf(a, b), first.notified)
  }
}
