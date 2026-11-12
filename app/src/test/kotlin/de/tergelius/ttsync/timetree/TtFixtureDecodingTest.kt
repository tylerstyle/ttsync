package de.tergelius.ttsync.timetree

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The DTOs against sanitized copies of real responses (2026-11-12, `fixtures/timetree/`): same
 * shapes and timestamps, fake ids and text.
 */
class TtFixtureDecodingTest {
  @Test
  fun calendars() {
    val calendars =
      TtJson.decodeFromString(TtCalendarsResponse.serializer(), fixture("calendars.json")).calendars
    assertEquals(1, calendars.size)
    assertEquals(7000001L, calendars[0].id)
    assertTrue(calendars[0].isActive)
  }

  @Test
  fun labelsComeFromCalendarLabels() {
    val labels = TtJson.decodeFromString(TtLabelsResponse.serializer(), fixture("labels.json")).labels
    assertEquals(10, labels.size)
    assertEquals("", labels[0].name) // unnamed labels are empty strings
  }

  @Test
  fun labelsAcceptThePlainKeyToo() {
    val labels =
      TtJson.decodeFromString(TtLabelsResponse.serializer(), """{"labels":[{"id":3,"name":"x"}]}""")
        .labels
    assertEquals(listOf(3L), labels.map { it.id })
  }

  @Test
  fun eventsPage() {
    val page = TtJson.decodeFromString(TtEventsPage.serializer(), fixture("events-page.json"))
    assertEquals(false, page.chunk)
    assertEquals(1794465807011L, page.since)
    val events = page.events.map { TtEvent.fromJson(it) }
    assertEquals(8, events.size)
    assertTrue(events.all { it.calendarId == 7000001L && it.uuid.length == 32 })

    val series = events.single { it.recurrences.isNotEmpty() }
    assertEquals(
      listOf(
        "RRULE:FREQ=WEEKLY;COUNT=4;BYDAY=MO",
        "EXDATE:20261123T170000Z",
        "EXDATE:20261130T170000Z",
      ),
      series.recurrences,
    )
    val override = events.single { it.isOverride }
    assertEquals(series.uuid, override.recurringUuid)
    assertEquals(series.uuid, override.parentId)

    val allDay = events.single { it.allDay }
    assertEquals("UTC", allDay.startTimezone)
    assertNull(allDay.note)
  }

  @Test
  fun tombstoneSnapshotWithoutTimesDecodes() {
    val event =
      TtEvent.fromJson("""{"uuid":"t1","recurring_uuid":"p1","deactivated_at":1794470000000}""")
    assertTrue(event.isDeactivated)
    assertEquals("p1", event.recurringUuid)
    assertEquals(false, event.allDay)
    assertEquals(0L, event.startAt)
    assertEquals(0L, event.endAt)
    assertEquals(1794470000000L, event.updatedAt)
  }

  @Test
  fun liveEventWithoutTimesIsApiChangedWithoutLeakingContent() {
    try {
      TtEvent.fromJson("""{"uuid":"x","title":"Secret dentist","all_day":false}""")
      fail("expected ApiChanged")
    } catch (e: TtError.ApiChanged) {
      assertNull(e.cause) // decoder messages can quote the JSON input
      assertFalse(e.message!!.contains("Secret"))
    }
  }
}
