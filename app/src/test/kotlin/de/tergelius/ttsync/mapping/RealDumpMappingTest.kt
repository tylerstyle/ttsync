package de.tergelius.ttsync.mapping

import de.tergelius.ttsync.mapping.MappingFixtures.ALL_DAY
import de.tergelius.ttsync.mapping.MappingFixtures.DETAILS
import de.tergelius.ttsync.mapping.MappingFixtures.HOUR
import de.tergelius.ttsync.mapping.MappingFixtures.REAL
import de.tergelius.ttsync.mapping.MappingFixtures.berlin
import de.tergelius.ttsync.mapping.MappingFixtures.labels
import de.tergelius.ttsync.mapping.MappingFixtures.map
import de.tergelius.ttsync.mapping.MappingFixtures.realEvents
import de.tergelius.ttsync.mapping.MappingFixtures.utcDay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The whole sanitized page of 2026-11-12, mapped with its real labels. */
class RealDumpMappingTest {
  private val labels = labels("$REAL/labels.json")
  private val result = map(realEvents(), labels)

  @Test
  fun everyEventIsWrittenAndNothingIsDroppedOrCounted() {
    assertEquals(8, result.desired.size)
    assertTrue(result.desired.values.all { it is Desired.Write })
    assertEquals(MappingStats(), result.stats)
  }

  @Test
  fun detailsEventWithLocationNoteUrlAndUnnamedLabel() {
    val spec = result.spec(DETAILS)
    assertEquals("TT test details", spec.title)
    assertEquals("Berlin Hauptbahnhof", spec.location)
    // Label 6 has an empty name (TimeTree's default labels are colours only), so the copy names
    // its colour, as TimeTree does (B3): 0xE73B3B is "Apple red".
    assertEquals("", labels.getValue(6).name)
    assertEquals(
      "Test note\n\nLabel: Apple red\nURL: https://example.org\n[ttsync:$DETAILS]",
      spec.description,
    )
    assertEquals(berlin(2026, 11, 17, 15), spec.dtStart)
    assertEquals(berlin(2026, 11, 17, 15) + HOUR, spec.dtEnd)
    assertEquals("Europe/Berlin", spec.eventTimezone)
    assertNull(spec.eventEndTimezone)
  }

  @Test
  fun twoDayAllDayEventCoversExactlyTwoDays() {
    // start_at 2026-11-18T00:00Z, end_at 2026-11-19T00:00Z (inclusive), zones "UTC".
    val write = result.write(ALL_DAY)
    val spec = write.spec
    assertTrue(spec.allDay)
    assertEquals(utcDay(2026, 11, 18), spec.dtStart)
    assertEquals(utcDay(2026, 11, 20), spec.dtEnd)
    assertEquals("UTC", spec.eventTimezone)
    assertNull(spec.eventEndTimezone)
    assertNull(spec.location) // "" in the dump
    // Note and url are null; label 1 is unnamed, colour 0x2ECC87.
    assertEquals("Label: Emerald green\n[ttsync:$ALL_DAY]", spec.description)
    assertEquals(utcDay(2026, 11, 20), write.endAt)
  }
}
