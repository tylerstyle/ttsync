package de.tergelius.ttsync.mapping

import de.tergelius.ttsync.mapping.MappingFixtures.DAY
import de.tergelius.ttsync.mapping.MappingFixtures.HOUR
import de.tergelius.ttsync.mapping.MappingFixtures.events
import de.tergelius.ttsync.mapping.MappingFixtures.labels
import de.tergelius.ttsync.mapping.MappingFixtures.map
import de.tergelius.ttsync.mapping.MappingFixtures.utcDay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** DESIGN.md section 10: M1–M5, M10, M15–M17. Recurrences and the window: [RecurrenceTest]. */
class EventMapperTest {
  private val timed = map(events("synthetic/timed.json"))
  private val allDay = map(events("synthetic/allday.json"))
  private val content =
    map(events("synthetic/content.json"), labels("synthetic/labels.json"))

  private fun assertSingle(spec: EventSpec) {
    assertNull(spec.duration)
    assertNull(spec.rrule)
    assertNull(spec.rdate)
    assertNull(spec.exdate)
    assertFalse(spec.isRecurring)
  }

  @Test
  fun m01TimedEventInBerlin() {
    val write = timed.write("m01-berlin")
    val spec = write.spec
    assertEquals("Dentist", spec.title)
    assertEquals("Main St 1", spec.location)
    assertEquals(1_794_481_200_000, spec.dtStart) // 2026-11-12 12:00 Europe/Berlin
    assertEquals(1_794_484_800_000, spec.dtEnd)
    assertEquals("Europe/Berlin", spec.eventTimezone)
    assertNull(spec.eventEndTimezone) // same zone as the start
    assertFalse(spec.allDay)
    assertSingle(spec)
    assertEquals(spec.dtEnd, write.endAt)
  }

  @Test
  fun m02DstChangeAndOtherEndZone() {
    // 01:30 CEST → 03:30 CET on the night summer time ends: three real hours.
    val night = timed.spec("m02-dst-night")
    assertEquals(3 * HOUR, night.dtEnd!! - night.dtStart)
    assertEquals("Europe/Berlin", night.eventTimezone)

    val flight = timed.spec("m02-flight")
    assertEquals("Europe/Berlin", flight.eventTimezone)
    assertEquals("America/New_York", flight.eventEndTimezone)
    assertEquals(1_795_197_600_000, flight.dtEnd) // 13:00 in New York

    // A weekly series across the DST change keeps its wall clock; DURATION is plain seconds.
    val weekly = timed.spec("m02-weekly-dst")
    assertEquals("P3600S", weekly.duration)
    assertNull(weekly.dtEnd)
    assertEquals("FREQ=WEEKLY;BYDAY=TH", weekly.rrule)
    assertEquals("Europe/Berlin", weekly.eventTimezone)

    val reversed = timed.spec("m02-end-before-start")
    assertEquals(reversed.dtStart, reversed.dtEnd)
  }

  @Test
  fun m03AllDayOneDay() {
    val write = allDay.write("m03-one-day")
    val spec = write.spec
    assertTrue(spec.allDay)
    assertEquals(utcDay(2026, 11, 20), spec.dtStart)
    assertEquals(utcDay(2026, 11, 21), spec.dtEnd) // inclusive end + 1 day
    assertEquals("UTC", spec.eventTimezone)
    assertNull(spec.eventEndTimezone)
    assertSingle(spec)
    assertEquals(spec.dtEnd, write.endAt)
  }

  @Test
  fun m04AllDayThreeDays() {
    val spec = allDay.spec("m04-three-day")
    assertEquals(utcDay(2026, 11, 20), spec.dtStart)
    assertEquals(utcDay(2026, 11, 23), spec.dtEnd)
    assertNull(spec.eventEndTimezone)
  }

  @Test
  fun m05AllDayNotAtUtcMidnightUsesStartZoneDate() {
    // start_at/end_at at Tokyo midnights (15:00Z the day before).
    val tokyo = allDay.spec("m05-tokyo-midnight")
    assertEquals(utcDay(2026, 11, 20), tokyo.dtStart)
    assertEquals(utcDay(2026, 11, 22), tokyo.dtEnd)
    assertEquals("UTC", tokyo.eventTimezone)
    assertNull(tokyo.eventEndTimezone)

    // The end date is never before the start date.
    val reversed = allDay.spec("m05-end-before-start")
    assertEquals(utcDay(2026, 11, 20), reversed.dtStart)
    assertEquals(utcDay(2026, 11, 21), reversed.dtEnd)
  }

  @Test
  fun m10AllDayDurationCountsInclusiveDays() {
    val yearly = allDay.write("m10-yearly")
    assertEquals("P1D", yearly.spec.duration) // start_at == end_at
    assertEquals("FREQ=YEARLY", yearly.spec.rrule)
    assertNull(yearly.spec.dtEnd)
    assertEquals(utcDay(2026, 12, 24), yearly.spec.dtStart)
    assertEquals(Long.MAX_VALUE, yearly.endAt)

    val weekend = allDay.write("m10-weekend-weekly")
    assertEquals("P2D", weekend.spec.duration)
    assertEquals("FREQ=WEEKLY;UNTIL=20261231", weekend.spec.rrule)
    assertEquals(utcDay(2026, 12, 31) + 2 * DAY, weekend.endAt)
  }

  @Test
  fun m15BirthdaysMemosAndDeletedEventsAreAbsent() {
    assertEquals(Desired.Absent, content.desired["m15-birthday"])
    assertEquals(Desired.Absent, content.desired["m15-memo"])
    assertEquals(Desired.Absent, content.desired["m15-deleted"])
    assertEquals(Desired.Absent, content.desired["m15-deleted-series"]) // open series: in window
    assertEquals(2, content.stats.skipped) // birthday and memo; deleted events are not "skipped"
  }

  @Test
  fun m16DescriptionLabelUrlAndMarker() {
    val full = content.spec("m16-full")
    assertEquals("Team lunch", full.title)
    assertEquals("Cafe Central", full.location)
    assertEquals(
      "Bring the slides\r\nand snacks\n\nLabel: Work\nURL: https://example.org/lunch\n[ttsync:m16-full]",
      full.description,
    )

    // Label name squeezed onto one line.
    assertEquals(
      "Label: Work stuff\n[ttsync:m16-multiline-label]",
      content.spec("m16-multiline-label").description,
    )
  }

  @Test
  fun m16BlankFields() {
    val blank = content.spec("m16-blank")
    assertEquals("(TimeTree)", blank.title)
    assertNull(blank.location)
    // Blank note and url, unknown label id: only the marker is left.
    assertEquals("[ttsync:m16-blank]", blank.description)
  }

  @Test
  fun m16FakeMarkerInNoteIsHarmless() {
    val spec = content.spec("m16-fake-marker")
    // Label 3 has an empty name (like TimeTree's default labels): no label line.
    assertEquals("[ttsync:someone-else]\n[ttsync:m16-fake-marker]", spec.description)
    assertEquals("m16-fake-marker", Marker.parse(spec.description))
  }

  @Test
  fun m17InvalidTimeZonesFallBackToUtc() {
    val invalid = timed.spec("m17-invalid-zone")
    assertEquals("UTC", invalid.eventTimezone)
    assertNull(invalid.eventEndTimezone) // "+01:00" is not a tz database id
    assertEquals(1_794_571_200_000, invalid.dtStart) // the instant is kept

    val missing = timed.spec("m17-null-zone")
    assertEquals("UTC", missing.eventTimezone)
    assertEquals("Europe/Berlin", missing.eventEndTimezone) // valid and differs from UTC

    // All-day, not at a UTC midnight, invalid zone: the UTC date.
    val allDayBad = allDay.spec("m17-allday-bad-zone")
    assertEquals(utcDay(2026, 11, 19), allDayBad.dtStart)
    assertEquals("UTC", allDayBad.eventTimezone)
  }

  @Test
  fun everyWriteCarriesItsOwnMarker() {
    for (result in listOf(timed, allDay, content)) {
      for ((uuid, desired) in result.desired) {
        if (desired is Desired.Write) assertEquals(uuid, Marker.parse(desired.spec.description))
      }
    }
  }

  @Test
  fun uuidThatCannotBeMarkedIsLeftAlone() {
    val event = events("synthetic/timed.json").first().copy(uuid = "not a uuid!")
    val result = map(listOf(event))
    assertTrue(result.desired.isEmpty())
  }

  @Test
  fun newestCopyOfADuplicateUuidWins() {
    val event = events("synthetic/timed.json").first()
    val newer = event.copy(title = "Newer", updatedAt = event.updatedAt + 1)
    assertEquals("Newer", map(listOf(newer, event)).spec(event.uuid).title)
    assertEquals("Newer", map(listOf(event, newer)).spec(event.uuid).title)
    val deleted = event.copy(deactivatedAt = 1L) // same updated_at: deleted wins
    assertEquals(Desired.Absent, map(listOf(event, deleted)).desired[event.uuid])
  }
}
