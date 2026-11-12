package de.tergelius.ttsync.timetree

import kotlin.time.Duration.Companion.minutes
import kotlinx.serialization.builtins.ListSerializer
import kotlin.time.Duration.Companion.hours
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TtDtosTest {
  private val page =
    """
    {"chunk": true, "since": 1794481200123, "unknown_top": 1,
     "events": [
       {"uuid": "abcDEF123_-", "calendar_id": 7, "title": "Secret title", "all_day": false,
        "start_at": 1794481200000, "end_at": 1794484800000, "start_timezone": "Europe/Berlin",
        "label_id": 3, "recurrences": null, "updated_at": 1794400000000, "extra": {"x": [1, 2]}},
       {"uuid": "tomb1", "all_day": true, "start_at": 0, "end_at": 0, "type": 0, "category": 1,
        "recurring_uuid": "abcDEF123_-", "deactivated_at": 1794400000001, "updated_at": 1794400000001}
     ]}
    """
      .trimIndent()

  @Test
  fun decodesPageAndEventsLeniently() {
    val decoded = TtJson.decodeFromString(TtEventsPage.serializer(), page)
    assertTrue(decoded.chunk)
    assertEquals(1794481200123, decoded.since)

    val first = TtEvent.fromJson(decoded.events[0])
    assertEquals("abcDEF123_-", first.uuid)
    assertEquals(3L, first.labelId)
    assertEquals(emptyList<String>(), first.recurrences) // null coerced to the default
    assertFalse(first.isOverride)

    val tombstone = TtEvent.fromJson(decoded.events[1].toString())
    assertTrue(tombstone.isOverride)
    assertTrue(tombstone.isDeactivated)
    assertNull(tombstone.title)
  }

  @Test(expected = TtError.ApiChanged::class)
  fun missingRequiredFieldIsApiChanged() {
    TtEvent.fromJson("""{"uuid": "x", "all_day": false}""")
  }

  @Test
  fun toStringNeverShowsContent() {
    val event = TtEvent.fromJson(TtJson.decodeFromString(TtEventsPage.serializer(), page).events[0])
    assertFalse(event.toString().contains("Secret"))
    assertFalse(TtSignInRequest("me@example.org", "pw", "0".repeat(32)).toString().contains("pw"))
    assertFalse(TtCredentials("me@example.org", "hunter2").toString().contains("hunter2"))
  }

  private fun event(extra: String): TtEvent =
    TtEvent.fromJson(
      """{"uuid": "e1", "all_day": false, "start_at": 1, "end_at": 2, "updated_at": 3 $extra}"""
    )

  @Test
  fun attendeesAndAuthorDecode() {
    val event = event(""", "attendees": [11, 12], "author_id": 11""")
    assertEquals(listOf(11L, 12L), event.attendees)
    assertEquals(11L, event.authorId)
    // Missing or null: none, and the event still decodes.
    assertEquals(emptyList<Long>(), event("").attendees)
    assertNull(event("").authorId)
    assertEquals(emptyList<Long>(), event(""", "attendees": null, "author_id": null""").attendees)
  }

  @Test
  fun unexpectedAttendeesOrAuthorNeverCostTheEvent() {
    // A strict field would make the event "malformed", and a skipped event is never copied.
    val odd = event(""", "attendees": [11, "x", {"id": 3}, null, [1], 12], "author_id": "someone"""")
    assertEquals(listOf(11L, 12L), odd.attendees)
    assertNull(odd.authorId)
    assertEquals(emptyList<Long>(), event(""", "attendees": {"user_id": 11}""").attendees)
    assertEquals(emptyList<Long>(), event(""", "attendees": "11"""").attendees)
  }

  @Test
  fun eventsWithAttendeesRoundTrip() {
    val event = event(""", "attendees": [11, 12], "author_id": 11""")
    val json = TtJson.encodeToString(TtEvent.serializer(), event)
    assertEquals(event, TtEvent.fromJson(json))
    assertFalse(event.toString().contains("11"))
  }

  @Test
  fun labelsCachedBy01xStillDecode() {
    // cursor.labels_json as 0.1.x wrote it: id and name only.
    val cached =
      TtJson.decodeFromString(ListSerializer(TtLabel.serializer()), """[{"id":1,"name":""},{"id":2,"name":"Work"}]""")
    assertEquals(listOf(TtLabel(1, ""), TtLabel(2, "Work")), cached)
    assertNull(cached[0].color)

    // And what 0.2.0 caches decodes to the same labels.
    val label = TtLabel(3, "", color = 0x47B2F7, defaultColor = 0x2ECC87, order = 1)
    val json = TtJson.encodeToString(ListSerializer(TtLabel.serializer()), listOf(label))
    assertEquals(listOf(label), TtJson.decodeFromString(ListSerializer(TtLabel.serializer()), json))
  }

  @Test
  fun unexpectedLabelColourOrOrderKeepsTheLabel() {
    val labels =
      TtJson.decodeFromString(
          TtLabelsResponse.serializer(),
          """{"calendar_labels": [{"id": 1, "name": "Work", "color": "#2ecc87", "order": "first"}]}""",
        )
        .labels
    assertEquals(listOf(TtLabel(1, "Work")), labels)
  }

  @Test
  fun calendarLabelsAndMembersNeverFailTheCalendarList() {
    // GET /calendars runs first in every sync: a surprise in these lists must not stop syncing.
    val response =
      """
      {"calendars": [
        {"id": 1, "name": "Family",
         "calendar_labels": [{"id": 1, "name": "", "color": 3067015}, {"name": "no id"}, 7],
         "calendar_users": [
           {"user_id": 11, "name": "Alex", "role": 1, "deactivated_at": null},
           {"user_id": 12, "name": "Sam", "deactivated_at": 1794400000000},
           {"name": "no user id"},
           {"user_id": "x"}
         ]},
        {"id": 2, "calendar_labels": null, "calendar_users": {"user_id": 11}},
        {"id": 3}
      ]}
      """
        .trimIndent()
    val calendars = TtJson.decodeFromString(TtCalendarsResponse.serializer(), response).calendars
    val family = calendars[0]
    assertEquals(listOf(TtLabel(1, "", color = 3067015)), family.labels)
    assertEquals(listOf(11L, 12L), family.users.map { it.userId })
    assertEquals(listOf(true, false), family.users.map { it.isActive })
    assertFalse(family.users.toString().contains("Alex"))
    for (other in calendars.drop(1)) {
      assertTrue(other.labels.isEmpty())
      assertTrue(other.users.isEmpty())
    }
  }

  @Test
  fun rateLimitBackoffIsAtLeastThirtyMinutes() {
    assertEquals(30.minutes, TtError.RateLimited(null).backoff)
    assertEquals(30.minutes, TtError.RateLimited(5.minutes).backoff)
    assertEquals(2.hours, TtError.RateLimited(2.hours).backoff)
    assertTrue(TtError.RateLimited(null).retryable)
    assertTrue(TtError.Transient(503).retryable)
    assertFalse(TtError.AuthRejected(TimeTreeApi.CODE_AUTH_REJECTED).retryable)
    assertFalse(TtError.ApiChanged("x").retryable)
  }

  @Test
  fun errorBodyDecodes() {
    val body = TtJson.decodeFromString(TtErrorResponse.serializer(), """{"error":{"code":-702}}""")
    assertEquals(-702, body.error?.code)
  }
}
