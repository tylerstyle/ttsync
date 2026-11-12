package de.tergelius.ttsync

import org.junit.Assert.assertEquals
import org.junit.Test

class TtLogTest {
  @Test
  fun uuidsAreShortenedToEightCharacters() {
    assertEquals("12345678", TtLog.short("1234567890abcdef"))
    assertEquals("abc", TtLog.short("abc"))
  }
}
