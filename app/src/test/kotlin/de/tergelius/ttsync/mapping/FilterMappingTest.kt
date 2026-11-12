package de.tergelius.ttsync.mapping

import de.tergelius.ttsync.mapping.MappingFixtures.MOVED
import de.tergelius.ttsync.mapping.MappingFixtures.WEEKLY
import de.tergelius.ttsync.mapping.MappingFixtures.events
import de.tergelius.ttsync.mapping.MappingFixtures.labels
import de.tergelius.ttsync.mapping.MappingFixtures.map
import de.tergelius.ttsync.mapping.MappingFixtures.realEvents
import de.tergelius.ttsync.timetree.TtEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The filter in [DefaultEventMapper]: Absent, counted, and checked after everything else. */
class FilterMappingTest {
  private val synthetic =
    listOf("timed", "allday", "recurring", "content").flatMap { events("synthetic/$it.json") }
  private val syntheticLabels = labels("synthetic/labels.json")

  /** Excludes every event: no event has label 999. */
  private val nothing = EventFilter(labelIds = setOf(999))

  @Test
  fun excludedEventsAreAbsentCountedAndListed() {
    val events = realEvents()
    val all = map(events)
    val onlyLabel6 = map(events, filter = EventFilter(labelIds = setOf(6)))
    val kept = all.desired.keys.filter { events.byUuid(it).labelId == 6L }.toSet()
    assertEquals(setOf(MappingFixtures.DETAILS), kept)
    for ((uuid, desired) in onlyLabel6.desired) {
      if (uuid in kept) assertEquals(all.desired[uuid], desired) // the copy is the same
      else assertEquals(Desired.Absent, desired)
    }
    assertEquals(all.desired.keys - kept, onlyLabel6.filtered)
    assertEquals(onlyLabel6.filtered.size, onlyLabel6.stats.filtered)
    assertEquals(all.parentOf, onlyLabel6.parentOf)
  }

  @Test
  fun emptyOrBlankFilterChangesNothing() {
    val plain = map(synthetic, syntheticLabels)
    assertEquals(plain, map(synthetic, syntheticLabels, filter = EventFilter()))
    assertEquals(plain, map(synthetic, syntheticLabels, filter = EventFilter(includeTerms = listOf(" "))))
    assertTrue(plain.filtered.isEmpty())
    assertEquals(0, plain.stats.filtered)
  }

  @Test
  fun tombstonesWindowBirthdaysAndMemosBehaveAsWithoutAFilter() {
    val plain = map(synthetic, syntheticLabels)
    val filtered = map(synthetic, syntheticLabels, filter = nothing)
    // The same uuids in the map (out of window stays out), and the same counters.
    assertEquals(plain.desired.keys, filtered.desired.keys)
    assertEquals(plain.stats.outOfWindow, filtered.stats.outOfWindow)
    assertEquals(plain.stats.skipped, filtered.stats.skipped)
    assertEquals(2, filtered.stats.skipped)
    for (uuid in listOf("m15-birthday", "m15-memo", "m15-deleted", "m15-deleted-series")) {
      assertEquals(Desired.Absent, filtered.desired[uuid])
      assertTrue(uuid, uuid !in filtered.filtered)
    }
    // Everything that was written is now filtered, and nothing else.
    val written = plain.desired.filterValues { it is Desired.Write }.keys
    assertEquals(written, filtered.filtered)
    assertTrue(filtered.desired.values.all { it == Desired.Absent })
    // Filtered series add no dropped lines or unmatched overrides: there is no copy.
    assertEquals(0, filtered.stats.droppedRecurrenceLines)
    assertEquals(0, filtered.stats.unmatchedOverrides)
  }

  @Test
  fun tombstoneWithUnknownTimesStaysAbsentWithAFilter() {
    val single = realEvents().first { it.recurringUuid == null && it.recurrences.isEmpty() && !it.allDay }
    val tombstone = single.copy(startAt = 0, endAt = 0, deactivatedAt = single.updatedAt + 1)
    val result = map(listOf(tombstone), filter = nothing)
    assertEquals(Desired.Absent, result.desired[single.uuid])
    assertTrue(result.filtered.isEmpty())
  }

  // Overrides: every event is judged on its own fields ---------------------------------------------

  private val weekly = realEvents().byUuid(WEEKLY) // label 1
  private val moved = realEvents().byUuid(MOVED) // label 1, an edited occurrence of WEEKLY

  @Test
  fun seriesMatchesButOccurrenceDoesNot() {
    // The occurrence is simply missing: not copied, and the series keeps its EXDATE for it.
    val relabelled = moved.copy(labelId = 2)
    val plain = map(listOf(weekly, relabelled))
    val result = map(listOf(weekly, relabelled), filter = EventFilter(labelIds = setOf(1)))
    assertEquals(Desired.Absent, result.desired[MOVED])
    assertEquals(setOf(MOVED), result.filtered)
    assertEquals(plain.desired[WEEKLY], result.desired[WEEKLY])
    assertEquals("20261123T170000Z,20261130T170000Z", result.spec(WEEKLY).exdate)
    assertEquals(mapOf(MOVED to WEEKLY), result.parentOf) // the engine still re-checks the parent
  }

  @Test
  fun occurrenceMatchesButSeriesDoesNot() {
    // Only that occurrence is copied, standalone, like an override whose series is missing.
    val relabelled = moved.copy(labelId = 2)
    val result = map(listOf(weekly, relabelled), filter = EventFilter(labelIds = setOf(2)))
    assertEquals(Desired.Absent, result.desired[WEEKLY])
    assertEquals(setOf(WEEKLY), result.filtered)
    val spec = result.spec(MOVED)
    assertNull(spec.rrule)
    assertNull(spec.exdate)
    assertEquals(map(listOf(relabelled)).spec(MOVED), spec)
  }

  @Test
  fun aFilteredOccurrenceStillCountsWhenItsSeriesLacksTheExdate() {
    // Without the EXDATE the series' copy would show the occurrence at its original time.
    val bare = weekly.copy(recurrences = listOf(weekly.recurrences.first()))
    val relabelled = moved.copy(labelId = 2)
    val result = map(listOf(bare, relabelled), filter = EventFilter(labelIds = setOf(1)))
    assertEquals(Desired.Absent, result.desired[MOVED])
    assertTrue(result.desired[WEEKLY] is Desired.Write)
    assertEquals(1, result.stats.unmatchedOverrides)
  }

  @Test
  fun textAndAttendeesComeFromTheEventItself() {
    val a = moved.copy(title = "Match", attendees = listOf(11))
    val b = weekly.copy(title = "Training", attendees = listOf(12))
    val byAttendee = map(listOf(a, b), filter = EventFilter(attendeeIds = setOf(11)))
    assertEquals(setOf(WEEKLY), byAttendee.filtered)
    val byText = map(listOf(a, b), filter = EventFilter(excludeTerms = listOf("match")))
    assertEquals(setOf(MOVED), byText.filtered)
  }

  private fun List<TtEvent>.byUuid(uuid: String): TtEvent = single { it.uuid == uuid }
}
