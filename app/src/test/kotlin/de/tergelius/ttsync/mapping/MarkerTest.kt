package de.tergelius.ttsync.mapping

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkerTest {
  @Test
  fun appendThenParseRoundTrips() {
    val description = Marker.append("Note\nLabel: Work\n\n", "abc-123_X")
    assertEquals("Note\nLabel: Work\n[ttsync:abc-123_X]", description)
    assertEquals("abc-123_X", Marker.parse(description))
  }

  @Test
  fun blankBodyGivesOnlyTheMarker() {
    assertEquals("[ttsync:u1]", Marker.append(null, "u1"))
    assertEquals("[ttsync:u1]", Marker.append("  \n", "u1"))
    assertEquals("u1", Marker.parse("[ttsync:u1]"))
  }

  @Test
  fun onlyTheLastLineCounts() {
    // A fake marker in the user's note must not be taken for ours.
    val description = Marker.append("[ttsync:fake]", "real")
    assertEquals("real", Marker.parse(description))
    assertNull(Marker.parse("[ttsync:fake]\nsomething else"))
    assertNull(Marker.parse("text [ttsync:u1]"))
  }

  @Test
  fun crlfIsAccepted() {
    assertEquals("u1", Marker.parse("note\r\n[ttsync:u1]"))
  }

  @Test
  fun anythingAfterTheMarkerMeansNotOurs() {
    assertNull(Marker.parse("note\n[ttsync:u1]\n"))
    assertNull(Marker.parse("note\n[ttsync:u1] "))
    assertNull(Marker.parse(null))
    assertNull(Marker.parse(""))
    assertNull(Marker.parse("[ttsync:]"))
    assertNull(Marker.parse("[ttsync:a b]"))
  }

  @Test
  fun everythingParseAcceptsMatchesTheR2LikePrecondition() {
    val uuid = "u1"
    val description = Marker.append("body", uuid)
    val like = Marker.likeEndsWith(uuid)
    assertEquals("%[ttsync:u1]", like)
    assertTrue(description.endsWith(like.removePrefix("%")))
  }

  @Test(expected = IllegalArgumentException::class)
  fun invalidUuidIsRejected() {
    Marker.line("has]bracket")
  }
}
