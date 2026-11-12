package de.tergelius.ttsync.mapping

import de.tergelius.ttsync.mapping.MappingFixtures.HOUR
import de.tergelius.ttsync.mapping.MappingFixtures.MOVED
import de.tergelius.ttsync.mapping.MappingFixtures.WEEKLY
import de.tergelius.ttsync.mapping.MappingFixtures.berlin
import de.tergelius.ttsync.mapping.MappingFixtures.map
import de.tergelius.ttsync.mapping.MappingFixtures.realEvents
import de.tergelius.ttsync.timetree.TtEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DESIGN.md section 10, M11–M14, on the real dump of 2026-11-12: "TT test weekly" is a weekly
 * series (Mondays 18:00 Berlin, `COUNT=4`) whose 2nd occurrence was moved to 20:00 and whose 3rd
 * was deleted. TimeTree put an EXDATE for both on the parent; the moved occurrence is its own
 * event with `recurring_uuid`/`parent_id`; the deleted one left no event at all.
 */
class EditedOccurrencesTest {
  private val events = realEvents()
  private val parent = events.single { it.uuid == WEEKLY }
  private val moved = events.single { it.uuid == MOVED }

  private fun mapWith(vararg replaced: TtEvent): MappingResult {
    val byUuid = events.associateBy { it.uuid }.toMutableMap()
    for (event in replaced) byUuid[event.uuid] = event
    return map(byUuid.values)
  }

  @Test
  fun fixtureHasTheShapeTheRuleRelies() {
    assertEquals(
      listOf("RRULE:FREQ=WEEKLY;COUNT=4;BYDAY=MO", "EXDATE:20261123T170000Z", "EXDATE:20261130T170000Z"),
      parent.recurrences,
    )
    assertEquals(WEEKLY, moved.recurringUuid)
    assertEquals(WEEKLY, moved.parentId)
    assertTrue(moved.recurrences.isEmpty())
    assertEquals(events.size, events.map { it.uuid }.toSet().size)
    assertTrue(events.none { it.isDeactivated }) // the deleted occurrence has no tombstone
  }

  @Test
  fun m11ParentKeepsItsNativeExdatesForMovedAndDeletedOccurrences() {
    val result = map(events)
    val write = result.write(WEEKLY)
    val spec = write.spec
    assertEquals("FREQ=WEEKLY;COUNT=4;BYDAY=MO", spec.rrule)
    assertEquals("20261123T170000Z,20261130T170000Z", spec.exdate)
    assertNull(spec.rdate)
    assertEquals(berlin(2026, 11, 16, 18), spec.dtStart)
    assertNull(spec.dtEnd)
    assertEquals("P3600S", spec.duration)
    assertEquals("Europe/Berlin", spec.eventTimezone)
    assertNull(spec.eventEndTimezone)
    assertEquals(Long.MAX_VALUE, write.endAt) // COUNT: end unknown without RRULE expansion
    assertEquals(0, result.stats.unmatchedOverrides)
    assertEquals(0, result.stats.droppedRecurrenceLines)
  }

  @Test
  fun m12MovedOccurrenceIsAStandaloneEventAndNoExdateIsSynthesized() {
    // No field holds the original instance start, so nothing is added to the parent: its EXDATE
    // is exactly its own (the moved occurrence's new start, 19:00Z, is not excluded).
    val result = map(events)
    val spec = result.spec(MOVED)
    assertEquals("TT test weekly", spec.title)
    assertEquals(berlin(2026, 11, 23, 20), spec.dtStart)
    assertEquals(berlin(2026, 11, 23, 20) + HOUR, spec.dtEnd)
    assertFalse(spec.isRecurring)
    assertNull(spec.exdate)
    assertNull(spec.duration)
    assertEquals(mapOf(MOVED to WEEKLY), result.parentOf)
    assertFalse(result.spec(WEEKLY).exdate!!.contains("20261123T190000Z"))
  }

  @Test
  fun m13ParentWithoutExdateCountsTheOverrideAsUnmatched() {
    // The other hypothesis (yehzu): the parent lacks the EXDATE. No guessing: nothing is added,
    // the override is still written, and the case is counted.
    val bare = parent.copy(recurrences = listOf(parent.recurrences.first()))
    val result = mapWith(bare)
    assertNull(result.spec(WEEKLY).exdate)
    assertTrue(result.desired[MOVED] is Desired.Write)
    assertEquals(1, result.stats.unmatchedOverrides)
  }

  @Test
  fun m13FewerExdatesThanOverridesCountsTheDifference() {
    // One EXDATE left, but a live override and a tombstone each need one.
    val oneExdate = parent.copy(recurrences = parent.recurrences.take(2))
    val tombstone = moved.copy(uuid = "tombstone-1", deactivatedAt = moved.updatedAt + 1)
    val result = mapWith(oneExdate, tombstone)
    assertEquals("20261123T170000Z", result.spec(WEEKLY).exdate)
    assertEquals(1, result.stats.unmatchedOverrides)
  }

  @Test
  fun m14DeletedMovedOccurrenceBecomesAbsent() {
    // Deleting the moved occurrence deactivates it; the parent's EXDATE still hides the original.
    val tombstone = moved.copy(deactivatedAt = moved.updatedAt + 1, updatedAt = moved.updatedAt + 1)
    val result = mapWith(tombstone)
    assertEquals(Desired.Absent, result.desired[MOVED])
    assertEquals(WEEKLY, result.parentOf[MOVED]) // the engine re-checks the parent
    assertEquals("20261123T170000Z,20261130T170000Z", result.spec(WEEKLY).exdate)
    assertEquals(0, result.stats.unmatchedOverrides)
  }

  @Test
  fun tombstoneWithUnknownTimesIsAbsentNotOutOfWindow() {
    // Lenient decoding gives tombstones without times start/end 0 ("unknown").
    // They must still reach the engine as Absent (review phase3-agy BLOCKER-1).
    val single = events.first { it.recurringUuid == null && it.recurrences.isEmpty() && !it.allDay }
    val tombstone = single.copy(startAt = 0, endAt = 0, deactivatedAt = single.updatedAt + 1)
    val result = mapWith(tombstone)
    assertEquals(Desired.Absent, result.desired[single.uuid])
    assertEquals(0, result.stats.outOfWindow)
  }

  @Test
  fun m14MissingParentLeavesTheOverrideStandalone() {
    val result = map(events.filterNot { it.uuid == WEEKLY })
    assertTrue(result.desired[MOVED] is Desired.Write)
    assertFalse(result.spec(MOVED).isRecurring)
    assertTrue(result.parentOf.isEmpty())
    assertEquals(0, result.stats.unmatchedOverrides)
  }

  @Test
  fun overrideIgnoresItsOwnRecurrences() {
    val withRule = moved.copy(recurrences = parent.recurrences)
    val spec = mapWith(withRule).spec(MOVED)
    assertFalse(spec.isRecurring)
    assertNull(spec.exdate)
  }

  @Test
  fun selfReferenceDoesNotTurnTheParentIntoAnOverride() {
    val selfRef = parent.copy(recurringUuid = WEEKLY, parentId = WEEKLY)
    val result = mapWith(selfRef)
    assertEquals("FREQ=WEEKLY;COUNT=4;BYDAY=MO", result.spec(WEEKLY).rrule)
    assertFalse(WEEKLY in result.parentOf)
  }

  @Test
  fun deletedSeriesIsAbsentAndItsLiveOverrideIsJudgedOnItsOwn() {
    val deleted = parent.copy(deactivatedAt = parent.updatedAt + 1)
    val result = mapWith(deleted)
    assertEquals(Desired.Absent, result.desired[WEEKLY])
    assertTrue(result.desired[MOVED] is Desired.Write)
    assertEquals(WEEKLY, result.parentOf[MOVED])
    assertEquals(0, result.stats.unmatchedOverrides)
  }
}
