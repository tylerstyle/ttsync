package de.tergelius.ttsync.timetree

import kotlin.time.Duration.Companion.minutes
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
