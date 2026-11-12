package de.tergelius.ttsync.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class StoreModelsTest {
  @Test
  fun storedEnumNamesNeverChange() {
    assertEquals(listOf("ACTIVE", "PAUSED"), CalendarState.entries.map { it.name })
  }

  @Test
  fun adoptedHashIsEmpty() {
    assertEquals("", Mapping.ADOPTED_HASH)
  }

  @Test
  fun snapshotToStringHidesJson() {
    val snapshot = Snapshot("uuid-12345678", 7, 1, false, """{"title":"Secret"}""")
    assertFalse(snapshot.toString().contains("Secret"))
  }
}
