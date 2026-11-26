package de.tergelius.ttsync.mapping

import de.tergelius.ttsync.mapping.MappingFixtures.BERLIN
import de.tergelius.ttsync.mapping.MappingFixtures.DAY
import de.tergelius.ttsync.mapping.MappingFixtures.HOUR
import de.tergelius.ttsync.mapping.MappingFixtures.TODAY
import de.tergelius.ttsync.mapping.MappingFixtures.berlin
import de.tergelius.ttsync.timetree.TtEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The mapper fixes of the code review of 2026-11-25 (F2, F8, F13). */
class ReviewFixesMappingTest {
  private val uuid = "0123456789abcdef0123456789abcdef"
  private val start = berlin(2026, 11, 20, 18)

  private fun event(
    startAt: Long = start,
    recurrences: List<String> = emptyList(),
    attendees: List<Long>? = listOf(11),
    parent: String? = null,
  ) =
    TtEvent(
      uuid = uuid,
      title = "Training",
      allDay = false,
      startAt = startAt,
      endAt = startAt + HOUR,
      startTimezone = "Europe/Berlin",
      updatedAt = 5,
      recurrences = recurrences,
      attendees = attendees,
      recurringUuid = parent,
    )

  private fun map(event: TtEvent, filter: EventFilter? = null, copiesInWindow: Set<String> = emptySet()) =
    DefaultEventMapper().map(MappingInput(listOf(event), emptyMap(), TODAY, BERLIN, filter, copiesInWindow))

  @Test
  fun f2_anEventBeforeTheWindowIsMappedWhileItsCopyIsStillInTheWindow() {
    val past = event(startAt = start - 90 * DAY)
    val plain = map(past)
    assertNull(plain.desired[uuid])
    assertEquals(1, plain.stats.outOfWindow)

    val withCopy = map(past, copiesInWindow = setOf(uuid))
    assertEquals(0, withCopy.stats.outOfWindow)
    assertEquals(past.startAt, withCopy.spec(uuid).dtStart)
    assertEquals(past.endAt, withCopy.write(uuid).endAt)
  }

  @Test
  fun f2_aSeriesThatNowEndsBeforeTheWindowIsWrittenWithItsEnd() {
    val ended = event(startAt = start - 200 * DAY, recurrences = listOf("RRULE:FREQ=WEEKLY;UNTIL=20260801T000000Z"))
    assertNull(map(ended).desired[uuid])

    val write = map(ended, copiesInWindow = setOf(uuid)).write(uuid)
    assertTrue(write.spec.rrule!!.contains("UNTIL=20260801"))
    assertTrue(write.endAt < EventMapper.windowStartMillis(TODAY, BERLIN))
  }

  @Test
  fun f2_otherUuidsInTheSetChangeNothing() {
    val past = event(startAt = start - 90 * DAY)
    assertEquals(map(past), map(past, copiesInWindow = setOf("ffffffffffffffffffffffffffffffff")))
    val current = event()
    assertEquals(map(current), map(current, copiesInWindow = setOf(uuid)))
  }

  @Test
  fun f8_anEventWithUnknownAttendeesIsLeftAloneByAnAttendeeFilter() {
    val filter = EventFilter(attendeeIds = setOf(11))
    val unknown = map(event(attendees = null), filter)
    assertNull("neither written nor absent", unknown.desired[uuid])
    assertEquals(1, unknown.stats.unjudged)
    assertEquals(0, unknown.stats.filtered)
    assertTrue(unknown.filtered.isEmpty())

    // Known attendees decide as before.
    assertTrue(map(event(attendees = listOf(11)), filter).desired[uuid] is Desired.Write)
    assertEquals(Desired.Absent, map(event(attendees = emptyList()), filter).desired[uuid])
    // A filter that does not ask for attendees does not care.
    val labelsOnly = map(event(attendees = null), EventFilter(excludeTerms = listOf("match")))
    assertTrue(labelsOnly.desired[uuid] is Desired.Write)
    assertEquals(0, labelsOnly.stats.unjudged)
  }

  @Test
  fun f8_canJudge() {
    assertFalse(EventFilter(attendeeIds = setOf(11)).canJudge(event(attendees = null)))
    assertTrue(EventFilter(attendeeIds = setOf(11)).canJudge(event(attendees = emptyList())))
    assertTrue(EventFilter(labelIds = setOf(1)).canJudge(event(attendees = null)))
    assertFalse(EventFilter(attendeeIds = setOf(11)).matches(event(attendees = null)))
  }

  @Test
  fun f13_recurrenceLinesOfAnEditedOccurrenceAreCounted() {
    val child = event(recurrences = listOf("RRULE:FREQ=WEEKLY", "EXDATE:20261127T170000Z"), parent = "a".repeat(32))
    val result = map(child)
    assertFalse(result.spec(uuid).isRecurring)
    assertEquals(2, result.stats.droppedRecurrenceLines)
    assertEquals(0, map(event(parent = "a".repeat(32))).stats.droppedRecurrenceLines)
  }
}
