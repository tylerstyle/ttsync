package de.tergelius.ttsync.mapping

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EventSpecTest {
  private val day = EventSpec.DAY_MS

  private fun single(uuid: String = "u1", location: String? = null) =
    EventSpec(
      uuid = uuid,
      title = "Title",
      location = location,
      description = Marker.append("note", uuid),
      dtStart = 1_794_481_200_000,
      dtEnd = 1_794_484_800_000,
      duration = null,
      eventTimezone = "Europe/Berlin",
      eventEndTimezone = null,
      allDay = false,
      rrule = null,
      rdate = null,
      exdate = null,
    )

  private fun allDayWeekly() =
    EventSpec(
      uuid = "u2",
      title = "(TimeTree)",
      location = null,
      description = Marker.append(null, "u2"),
      dtStart = 20_000 * day,
      dtEnd = null,
      duration = "P1D",
      eventTimezone = "UTC",
      eventEndTimezone = null,
      allDay = true,
      rrule = "FREQ=WEEKLY;UNTIL=20261231",
      rdate = null,
      exdate = "20261118,20261125",
    )

  @Test
  fun validSpecsConstruct() {
    assertFalse(single().isRecurring)
    assertTrue(allDayWeekly().isRecurring)
    assertEquals(EventSpec.MANAGED_COLUMNS.sorted(), single().columns().keys.toList())
    assertEquals(null, single().columns()[EventSpec.COL_DURATION]) // explicit clearing
    assertEquals("1", allDayWeekly().columns()[EventSpec.COL_ALL_DAY])
  }

  @Test(expected = IllegalArgumentException::class)
  fun markerOfAnotherUuidIsRejected() {
    single().copy(description = Marker.append("note", "other"))
  }

  @Test(expected = IllegalArgumentException::class)
  fun singleWithDurationIsRejected() {
    single().copy(duration = "P3600S")
  }

  @Test(expected = IllegalArgumentException::class)
  fun recurringWithDtEndIsRejected() {
    allDayWeekly().copy(dtEnd = 20_001 * day)
  }

  @Test(expected = IllegalArgumentException::class)
  fun allDayOutsideUtcIsRejected() {
    allDayWeekly().copy(eventTimezone = "Europe/Berlin")
  }

  @Test(expected = IllegalArgumentException::class)
  fun allDayNotAtMidnightIsRejected() {
    allDayWeekly().copy(dtStart = 20_000 * day + 1)
  }

  @Test(expected = IllegalArgumentException::class)
  fun prefixedExdateIsRejected() {
    allDayWeekly().copy(exdate = "EXDATE:20261118")
  }

  @Test
  fun toStringIsRedacted() {
    assertFalse(single(location = "Secret place").toString().contains("Secret"))
  }

  @Test
  fun hashIsStableAndSensitive() {
    val hash = SpecHash.of(single())
    assertEquals(64, hash.length)
    assertTrue(hash.all { it in "0123456789abcdef" })
    assertEquals(hash, SpecHash.of(single()))
    assertNotEquals(hash, SpecHash.of(single().copy(title = "Other")))
    assertNotEquals(SpecHash.of(single(location = null)), SpecHash.of(single(location = "")))
    assertNotEquals(hash, SpecHash.of(single(), EventMapper.VERSION + 1))
  }

  @Test
  fun windowStartIsThirtyDaysBackAtLocalMidnight() {
    val start = EventMapper.windowStartMillis(LocalDate.of(2026, 11, 12), ZoneId.of("Europe/Berlin"))
    // 2026-10-13T00:00+02:00 (summer time until 2026-10-25)
    assertEquals(1_791_842_400_000, start)
  }
}
