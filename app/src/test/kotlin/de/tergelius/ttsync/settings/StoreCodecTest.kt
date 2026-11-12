package de.tergelius.ttsync.settings

import de.tergelius.ttsync.calendar.TargetCalendar
import de.tergelius.ttsync.mapping.EventFilter
import de.tergelius.ttsync.store.CalendarState
import de.tergelius.ttsync.sync.CalendarReport
import de.tergelius.ttsync.sync.SyncCounts
import de.tergelius.ttsync.sync.SyncMode
import de.tergelius.ttsync.sync.SyncReport
import de.tergelius.ttsync.sync.SyncTrigger
import de.tergelius.ttsync.sync.SyncWarning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What DataStore holds as JSON text, across versions (0.1.x ↔ 0.2.0). */
class StoreCodecTest {
  /** The selection exactly as 0.1.x stored it. */
  private val sources01 = """[{"id":7,"name":"Family"},{"id":8,"name":"Football"}]"""

  @Test
  fun sourcesOf01xDecodeWithoutFilterAndStayByteIdentical() {
    val sources = StoreCodec.decodeSources(sources01)
    assertEquals(listOf(SourceCalendar(7, "Family"), SourceCalendar(8, "Football")), sources)
    assertTrue(sources.all { it.filter == null && it.activeFilter == null })
    assertEquals(sources01, StoreCodec.encodeSources(sources))
  }

  @Test
  fun filterRoundTripsAndOnlyItsSetCriteriaAreWritten() {
    val filter = EventFilter(labelIds = setOf(3, 9), excludeTerms = listOf("cancelled"))
    val sources = listOf(SourceCalendar(7, "Family", filter), SourceCalendar(8, "Football"))
    val json = StoreCodec.encodeSources(sources)
    assertEquals(
      """[{"id":7,"name":"Family","filter":{"labelIds":[3,9],"excludeTerms":["cancelled"]}},""" +
        """{"id":8,"name":"Football"}]""",
      json,
    )
    assertEquals(sources, StoreCodec.decodeSources(json))
  }

  @Test
  fun anEmptyFilterIsNoFilter() {
    val source = SourceCalendar(7, "Family", EventFilter(includeTerms = listOf("  ")))
    assertNull(source.activeFilter)
    assertEquals(source, StoreCodec.decodeSources(StoreCodec.encodeSources(listOf(source))).single())
    assertFalse(source.toString().contains("Family"))
  }

  @Test
  fun unknownKeysOfANewerVersionAreIgnored() {
    val newer = """[{"id":7,"name":"Family","colour":"red","filter":{"labelIds":[3],"members":[1]}}]"""
    assertEquals(
      listOf(SourceCalendar(7, "Family", EventFilter(labelIds = setOf(3)))),
      StoreCodec.decodeSources(newer),
    )
  }

  @Test
  fun unreadableValuesReadAsMissing() {
    assertEquals(emptyList<SourceCalendar>(), StoreCodec.decodeSources(null))
    assertEquals(emptyList<SourceCalendar>(), StoreCodec.decodeSources("{broken"))
    assertEquals(emptyList<SourceCalendar>(), StoreCodec.decodeSources("""[{"name":"no id"}]"""))
    assertNull(StoreCodec.decodeTarget("[]"))
    assertNull(StoreCodec.decodeReport("""{"runId":"r"}"""))
  }

  @Test
  fun targetRoundTrips() {
    val target = TargetCalendar(15, "user@example.org", "com.google")
    val json = StoreCodec.encodeTarget(target)
    assertEquals("""{"calendarId":15,"accountName":"user@example.org","accountType":"com.google"}""", json)
    assertEquals(target, StoreCodec.decodeTarget(json))
  }

  @Test
  fun reportOf01xDecodesWithNothingFiltered() {
    // A report as 0.1.x stored it: SyncCounts has no "filtered".
    val report01 =
      """{"runId":"20261112-080000-00ff","trigger":"PERIODIC","startedAt":1,"finishedAt":2,""" +
        """"counts":{"added":3,"skipped":2,"unmanagedMarked":1},""" +
        """"calendars":[{"ttCalId":7,"mode":"FULL","state":"ACTIVE","counts":{"added":3,"skipped":2}}],""" +
        """"warnings":["GOOGLE_SYNC_OFF"],"notUploaded":4}"""
    val report = StoreCodec.decodeReport(report01)!!
    assertEquals(
      SyncReport(
        runId = "20261112-080000-00ff",
        trigger = SyncTrigger.PERIODIC,
        startedAt = 1,
        finishedAt = 2,
        counts = SyncCounts(added = 3, skipped = 2, unmanagedMarked = 1),
        calendars = listOf(CalendarReport(7, SyncMode.FULL, CalendarState.ACTIVE, SyncCounts(added = 3, skipped = 2))),
        warnings = setOf(SyncWarning.GOOGLE_SYNC_OFF),
        notUploaded = 4,
      ),
      report,
    )
    assertEquals(0, report.counts.filtered)
    // Nothing filtered: written exactly as 0.1.x wrote it.
    assertEquals(report01, StoreCodec.encodeReport(report))
    // Something filtered: the count survives the round trip.
    val filtered = report.copy(counts = report.counts.copy(filtered = 5))
    assertEquals(filtered, StoreCodec.decodeReport(StoreCodec.encodeReport(filtered)))
  }
}
