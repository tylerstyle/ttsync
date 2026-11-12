package de.tergelius.ttsync.mapping

import de.tergelius.ttsync.mapping.MappingFixtures.BERLIN
import de.tergelius.ttsync.mapping.MappingFixtures.DAY
import de.tergelius.ttsync.mapping.MappingFixtures.HOUR
import de.tergelius.ttsync.mapping.MappingFixtures.TODAY
import de.tergelius.ttsync.mapping.MappingFixtures.berlin
import de.tergelius.ttsync.mapping.MappingFixtures.events
import de.tergelius.ttsync.mapping.MappingFixtures.map
import de.tergelius.ttsync.mapping.MappingFixtures.utcDay
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** DESIGN.md section 10: M6–M9 and M18, plus [RecurrenceNormalizer] edge cases. */
class RecurrenceTest {
  private val recurring = map(events("synthetic/recurring.json"))
  private val normalizer = RecurrenceNormalizer()

  private fun timed(vararg lines: String, dtStart: Long = berlin(2026, 11, 12, 12)) =
    normalizer.normalize(lines.toList(), allDay = false, startZone = BERLIN, dtStart = dtStart)

  private fun allDay(vararg lines: String, zone: ZoneId = BERLIN) =
    normalizer.normalize(lines.toList(), allDay = true, startZone = zone, dtStart = utcDay(2026, 11, 14))

  @Test
  fun m06DateOnlyUntilBecomesEndOfDayInStartZone() {
    assertEquals(
      "FREQ=WEEKLY;BYDAY=TH;UNTIL=20261231T225959Z", // CET, UTC+1
      recurring.spec("m06-until-winter").rrule,
    )
    assertEquals(
      "FREQ=WEEKLY;BYDAY=TH;UNTIL=20270701T215959Z", // CEST, UTC+2
      recurring.spec("m06-until-summer").rrule,
    )
    assertEquals(berlin(2026, 12, 31, 23, 59, 59) + HOUR, recurring.write("m06-until-winter").endAt)
  }

  @Test
  fun m07LocalUntilIsConvertedToUtc() {
    assertEquals("FREQ=WEEKLY;UNTIL=20261231T110000Z", recurring.spec("m07-until-local").rrule)
    // All-day: UNTIL becomes a date.
    assertEquals("FREQ=WEEKLY;UNTIL=20261226", recurring.spec("m07-allday-until-utc").rrule)
    assertEquals("FREQ=WEEKLY;UNTIL=20261226", allDay("RRULE:FREQ=WEEKLY;UNTIL=20261226T120000").rrule)
  }

  @Test
  fun m08CountNextToUntilIsDropped() {
    assertEquals("FREQ=DAILY;UNTIL=20261130T225959Z", recurring.spec("m08-count-until").rrule)
    val count = recurring.write("m08-count")
    assertEquals("FREQ=WEEKLY;COUNT=4;BYDAY=TH", count.spec.rrule)
    assertEquals(Long.MAX_VALUE, count.endAt)
  }

  @Test
  fun m09ExdateAndRdateFormsAreNormalized() {
    val spec = recurring.spec("m09-forms")
    // `EXDATE:…Z`, `EXDATE;TZID=…:a,b` (b duplicates the first line) and `VALUE=DATE` (that day's
    // instance at the series' local start time, 12:00 Berlin).
    assertEquals("20261119T110000Z,20261126T110000Z,20261203T110000Z", spec.exdate)
    assertEquals("20261216T110000Z,20261217T110000Z", spec.rdate)
    assertEquals("FREQ=WEEKLY", spec.rrule)
    for (column in listOf(spec.rrule, spec.rdate, spec.exdate)) {
      assertFalse(column!!.contains(':'))
      assertFalse(column.contains("TZID"))
    }
    // X-UNKNOWN and EXRULE are dropped and counted; nothing else in the file drops a line.
    assertEquals(2, recurring.stats.droppedRecurrenceLines)

    // All-day: every form becomes a date; a UTC instant that is not a UTC midnight uses the
    // start zone's date (2026-12-11T23:00Z is 2026-12-12 in Berlin).
    assertEquals("20261121,20261128,20261205,20261212", recurring.spec("m09-allday-forms").exdate)
  }

  @Test
  fun m09DateOnlyExdateKeepsLocalStartTimeAcrossDst() {
    // Series starts 2026-10-15 12:00 CEST (10:00Z); the 2026-11-05 instance is 12:00 CET (11:00Z).
    val r = timed("RRULE:FREQ=WEEKLY", "EXDATE;VALUE=DATE:20261105", dtStart = berlin(2026, 10, 15, 12))
    assertEquals("20261105T110000Z", r.exdate)
  }

  @Test
  fun m18WindowAndEndAt() {
    // Out of the window: not in the map at all, so their copies are never touched.
    for (uuid in listOf("m18-old-until", "m18-old-single", "m18-just-before", "m18-old-deleted")) {
      assertFalse(uuid, uuid in recurring.desired)
    }
    assertEquals(4, recurring.stats.outOfWindow)

    val windowStart = EventMapper.windowStartMillis(TODAY, BERLIN)
    assertEquals(windowStart, recurring.write("m18-boundary").endAt) // ends exactly at the start

    // UNTIL: the last possible start plus the duration.
    assertEquals(berlin(2026, 10, 13, 23, 59, 59) + HOUR, recurring.write("m18-until-in-window").endAt)
    assertEquals(utcDay(2026, 12, 26) + DAY, recurring.write("m07-allday-until-utc").endAt)
    // An RDATE after UNTIL extends the series.
    assertEquals(utcDay(2026, 12, 24) + 11 * HOUR + HOUR, recurring.write("m18-rdate-beyond").endAt)
    // Open-ended and COUNT series.
    assertEquals(Long.MAX_VALUE, recurring.write("m18-open").endAt)
    assertEquals(Long.MAX_VALUE, recurring.write("m08-count").endAt)
  }

  @Test
  fun rruleIsUpperCasedAndOnlyTheFirstIsKept() {
    val r = timed("rrule:freq=weekly;byday=mo;", "RRULE:FREQ=DAILY")
    assertEquals("FREQ=WEEKLY;BYDAY=MO", r.rrule)
    assertEquals(1, r.droppedLines)
    assertEquals("FREQ=DAILY", timed("FREQ=DAILY").rrule) // bare rule without the property name
  }

  @Test
  fun unusableRulesAreDroppedAndCounted() {
    val noFreq = timed("RRULE:BYDAY=MO")
    assertNull(noFreq.rrule)
    assertEquals(1, noFreq.droppedLines)
    assertFalse(noFreq.isRecurring)

    val badUntil = timed("RRULE:FREQ=WEEKLY;UNTIL=2026-12-31")
    assertNull(badUntil.rrule)
    assertEquals(1, badUntil.droppedLines)
  }

  @Test
  fun badDateValuesAreSkippedButGoodOnesKept() {
    val r = timed("RRULE:FREQ=WEEKLY", "EXDATE:20261119T110000Z,garbage", "EXDATE:", "", "  ")
    assertEquals("20261119T110000Z", r.exdate)
    assertEquals(2, r.droppedLines) // the line with garbage and the empty EXDATE; blank lines are ignored
  }

  @Test
  fun unknownTzidFallsBackToStartZone() {
    assertEquals("20261119T110000Z", timed("RRULE:FREQ=WEEKLY", "EXDATE;TZID=Nowhere/X:20261119T120000").exdate)
    assertEquals("20261119T110000Z", timed("RRULE:FREQ=WEEKLY", "EXDATE;TZID=\"Europe/Berlin\":20261119T120000").exdate)
  }

  @Test
  fun rdatePeriodKeepsItsStart() {
    val r = timed("RDATE;VALUE=PERIOD:20261216T110000Z/PT1H")
    assertEquals("20261216T110000Z", r.rdate)
    assertNull(r.rrule)
    assertEquals(true, r.isRecurring) // an RDATE alone makes a series
  }

  @Test
  fun exdateWithoutRuleIsNotASeries() {
    val event =
      events("synthetic/timed.json").first().copy(recurrences = listOf("EXDATE:20261119T110000Z"))
    val spec = map(listOf(event)).spec(event.uuid)
    assertNull(spec.exdate)
    assertFalse(spec.isRecurring)
  }

  @Test
  fun allDayUntilFromLocalOrUtcBecomesDate() {
    assertEquals("FREQ=DAILY;UNTIL=20261231", allDay("RRULE:FREQ=DAILY;UNTIL=20261231T225959Z").rrule)
    assertEquals("20261231", allDay("RDATE:20261231T000000Z").rdate)
    assertEquals(utcDay(2026, 12, 31), allDay("RRULE:FREQ=DAILY;UNTIL=20261231").untilMillis)
  }
}
