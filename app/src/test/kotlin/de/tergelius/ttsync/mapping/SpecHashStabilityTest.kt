package de.tergelius.ttsync.mapping

import de.tergelius.ttsync.mapping.MappingFixtures.DETAILS
import de.tergelius.ttsync.mapping.MappingFixtures.REAL
import de.tergelius.ttsync.mapping.MappingFixtures.WEEKLY
import de.tergelius.ttsync.mapping.MappingFixtures.events
import de.tergelius.ttsync.mapping.MappingFixtures.labels
import de.tergelius.ttsync.mapping.MappingFixtures.map
import de.tergelius.ttsync.mapping.MappingFixtures.realEvents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** DESIGN.md section 10: M19 (hash stability, version bump) and M20 (explicit nulls). */
class SpecHashStabilityTest {
  private val labels = labels("$REAL/labels.json")
  private val allEvents =
    realEvents() +
      listOf("timed", "allday", "recurring", "content").flatMap { events("synthetic/$it.json") }

  private fun hashes(result: MappingResult): Map<String, String> =
    result.desired.mapNotNull { (uuid, d) -> (d as? Desired.Write)?.let { uuid to SpecHash.of(it.spec) } }.toMap()

  @Test
  fun m19SameInputGivesSameHashesInAnyOrder() {
    val first = map(allEvents, labels)
    assertEquals(first, map(allEvents, labels))
    assertEquals(first, map(allEvents.reversed(), labels))
    assertEquals(first, map(allEvents.shuffled(kotlin.random.Random(42)), labels))
  }

  @Test
  fun m19RecurrenceLineOrderDoesNotMatter() {
    val events = realEvents()
    val parent = events.single { it.uuid == WEEKLY }
    val reordered = parent.copy(recurrences = parent.recurrences.reversed())
    val before = SpecHash.of(map(events).spec(WEEKLY))
    val after = SpecHash.of(map(events.map { if (it.uuid == WEEKLY) reordered else it }).spec(WEEKLY))
    assertEquals(before, after)
  }

  @Test
  fun m19HashIsPinned() {
    // Pinned so that any change of the mapper output or the hash format is noticed: if this fails
    // on purpose, bump EventMapper.VERSION (every row is rewritten once) and update the values.
    val result = map(realEvents(), labels)
    assertEquals(1, EventMapper.VERSION)
    // Cross-checked with an independent Python implementation of the canonical form.
    assertEquals(
      "a470ad8227095880c7d6dd7177bdabe023c820ca7072504d31d9a137eed75e61",
      SpecHash.of(result.spec(DETAILS)),
    )
    assertEquals(
      "a76e986913dbd8a85a29dc929a8a6609186ec162e58d0a2f585f34e1860a15a1",
      SpecHash.of(result.spec(WEEKLY)),
    )
  }

  @Test
  fun m19VersionBumpChangesEveryHash() {
    val result = map(allEvents, labels)
    for (spec in result.desired.values.filterIsInstance<Desired.Write>().map { it.spec }) {
      assertNotEquals(SpecHash.of(spec), SpecHash.of(spec, EventMapper.VERSION + 1))
    }
    assertEquals(hashes(result).size, hashes(result).values.toSet().size) // no two specs collide
  }

  @Test
  fun m20SwitchBetweenSingleAndRecurringWritesExplicitNulls() {
    val single = realEvents().single { it.uuid == DETAILS }
    val series =
      single.copy(recurrences = listOf("RRULE:FREQ=DAILY;COUNT=3"), updatedAt = single.updatedAt + 1)
    val singleAgain = single.copy(updatedAt = single.updatedAt + 2)

    val v1 = map(listOf(single), labels).spec(DETAILS).columns()
    val v2 = map(listOf(series), labels).spec(DETAILS).columns()
    val v3 = map(listOf(singleAgain), labels).spec(DETAILS)

    for (columns in listOf(v1, v2)) assertEquals(EventSpec.MANAGED_COLUMNS.toSet(), columns.keys)
    // Single: DTEND set; DURATION, RRULE, RDATE, EXDATE present and null.
    assertEquals((single.endAt).toString(), v1[EventSpec.COL_DTEND])
    for (column in listOf(EventSpec.COL_DURATION, EventSpec.COL_RRULE, EventSpec.COL_RDATE, EventSpec.COL_EXDATE)) {
      assertNull(column, v1[column])
    }
    // Recurring: DTEND present and null.
    assertNull(v2[EventSpec.COL_DTEND])
    assertEquals("P3600S", v2[EventSpec.COL_DURATION])
    assertEquals("FREQ=DAILY;COUNT=3", v2[EventSpec.COL_RRULE])
    // Back to single: the same content and hash as before the switch.
    assertEquals(v1, v3.columns())
    assertEquals(SpecHash.of(map(listOf(single), labels).spec(DETAILS)), SpecHash.of(v3))
  }

  @Test
  fun m20AllDayNeverHasAnEndTimeZone() {
    val allDay = events("synthetic/allday.json")
    for (event in allDay + allDay.map { it.copy(recurrences = listOf("RRULE:FREQ=WEEKLY")) }) {
      val spec = map(listOf(event)).spec(event.uuid)
      assertNull(event.uuid, spec.columns()[EventSpec.COL_EVENT_END_TIMEZONE])
      assertEquals(event.uuid, "UTC", spec.eventTimezone)
    }
  }
}
