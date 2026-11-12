package de.tergelius.ttsync.sync

import de.tergelius.ttsync.calendar.CalendarOp
import de.tergelius.ttsync.calendar.GoogleSyncState
import de.tergelius.ttsync.calendar.TargetCalendar
import de.tergelius.ttsync.calendar.TargetCheck
import de.tergelius.ttsync.calendar.TargetUnavailableException
import de.tergelius.ttsync.mapping.EventSpec
import de.tergelius.ttsync.mapping.Marker
import de.tergelius.ttsync.settings.SourceCalendar
import de.tergelius.ttsync.store.CalendarState
import de.tergelius.ttsync.store.MetaKeys
import de.tergelius.ttsync.sync.EngineHarness.Companion.A1
import de.tergelius.ttsync.sync.EngineHarness.Companion.A2
import de.tergelius.ttsync.sync.EngineHarness.Companion.A3
import de.tergelius.ttsync.sync.EngineHarness.Companion.B1
import de.tergelius.ttsync.sync.EngineHarness.Companion.CAL_A
import de.tergelius.ttsync.sync.EngineHarness.Companion.CAL_B
import de.tergelius.ttsync.sync.EngineHarness.Companion.DAY
import de.tergelius.ttsync.sync.EngineHarness.Companion.NEW_TARGET
import de.tergelius.ttsync.sync.EngineHarness.Companion.OLD
import de.tergelius.ttsync.sync.EngineHarness.Companion.SERIES
import de.tergelius.ttsync.sync.EngineHarness.Companion.TARGET
import de.tergelius.ttsync.timetree.TimeTreeApi
import de.tergelius.ttsync.timetree.TtError
import de.tergelius.ttsync.timetree.TtLabel
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** DESIGN.md section 10, engine tests E1–E15, plus the other paths that write or delete. */
class SyncEngineTest {
  private val h = EngineHarness()

  private fun SyncReport.assertOk(): SyncReport {
    assertNull("failure: $failure", failure)
    return this
  }

  private fun idOf(uuid: String): Long = h.copies().getValue(uuid).id

  // E1 ------------------------------------------------------------------------------------------

  @Test
  fun e1FirstSyncCopiesEventsAndLeavesForeignRowsByteIdentical() = runTest {
    val report = h.sync().assertOk()

    assertEquals(SyncCounts(added = 4, skipped = 2, unmanagedMarked = 2), report.counts)
    assertEquals(listOf(CalendarReport(CAL_A, SyncMode.FULL, CalendarState.ACTIVE, report.counts.copy(unmanagedMarked = 0))), report.calendars)
    val copies = h.copies()
    assertEquals(h.liveA, copies.keys)
    for ((uuid, row) in copies) {
      assertEquals(TARGET, row.calendarId)
      assertEquals(EventSpec.MANAGED_COLUMNS.toSet(), row.values.keys)
      assertEquals(uuid, Marker.parse(row.description))
    }
    h.assertForeignUntouched()

    assertEquals(copies.mapValues { it.value.id }, h.store.mappings.all().associate { it.uuid to it.eventId })
    val cursor = h.store.cursors.get(CAL_A)!!
    assertEquals(h.client.newestChange(CAL_A), cursor.highWater)
    assertEquals(h.now, cursor.lastFullAt)
    assertNull(h.store.meta.get(MetaKeys.RUN_IN_PROGRESS))
    assertEquals(1, h.gateway.uploadRequests)
    assertEquals(listOf("begin", "calendars", "labels:1", "events:1:null"), h.client.requests)
    assertEquals(report, h.status.value.lastReport)
    assertTrue(h.log.lines.any { it.startsWith("I run finished") })
  }

  @Test
  fun logsCarryNoContent() = runTest {
    h.sync().assertOk()
    h.client.edit(CAL_A, A1) { it.copy(title = "Secret title", note = "Secret note") }
    h.sync().assertOk()
    for (line in h.log.lines) {
      assertFalse(line, "Secret" in line || "Breakfast" in line || A1 in line || EngineHarness.ACCOUNT in line)
    }
  }

  // E2 ------------------------------------------------------------------------------------------

  @Test
  fun e2SecondRunWritesNothingAndSoDoesAForcedFullRun() = runTest {
    h.sync().assertOk()
    val rows = h.gateway.rows.toMap()
    val batches = h.gateway.batchSizes.size

    val incremental = h.sync().assertOk()
    assertEquals(SyncMode.INCREMENTAL, incremental.calendars.single().mode)
    assertEquals(0, incremental.counts.writes)

    val full = h.sync(full = true).assertOk()
    assertEquals(SyncMode.FULL, full.calendars.single().mode)
    assertEquals(0, full.counts.writes)
    assertEquals(0, full.counts.adopted)

    assertEquals(rows, h.gateway.rows.toMap())
    assertEquals(batches, h.gateway.batchSizes.size)
    assertEquals(1, h.gateway.uploadRequests)
  }

  // Incremental reads of the real feed (newest change first, paged backwards) -------------------

  private val overlap = TimeTreeApi.FEED_OVERLAP.inWholeMilliseconds

  @Test
  fun incrementalAfterAFullRunPicksUpAnEditANewEventAndATombstone() = runTest {
    // The bug from the device test: the app stored the server's last `since` (a cursor for OLDER
    // events) and the next INCREMENTAL run asked for `?since=<that>`, which returned nothing.
    h.sync().assertOk()
    val known = h.store.cursors.get(CAL_A)!!.highWater!!
    val a1 = idOf(A1)
    val a2 = idOf(A2)
    val a4 = "aaaa0004aaaa0004aaaa0004aaaa0004"
    h.client.edit(CAL_A, A1) { it.copy(title = "Brunch") }
    h.client.put(CAL_A, h.timed(a4, day = 3, title = "New"))
    h.client.deactivate(CAL_A, A2)
    h.client.pageRequests.clear()

    val report = h.sync().assertOk()
    assertEquals(SyncMode.INCREMENTAL, report.calendars.single().mode)
    assertEquals(
      SyncCounts(added = 1, updated = 1, deleted = 1),
      report.counts.copy(skipped = 0, unmanagedMarked = 0),
    )
    assertEquals("Brunch", h.gateway.rows.getValue(a1).title)
    assertTrue(h.gateway.rows.getValue(a2).deleted)
    assertEquals(setOf(A1, A3, SERIES, a4), h.copies().keys)
    // From the top, never with a stored `since`: the three changes fill page 1 and half of page 2,
    // page 3 starts with known events.
    assertEquals("events:1:${known - overlap}", h.client.requests.last())
    assertEquals("1:top", h.client.pageRequests.first())
    assertEquals(3, h.client.pageRequests.size)
    // The new high-water mark is the newest change (the tombstone's), not a server cursor.
    assertEquals(h.client.newestChange(CAL_A), h.store.cursors.get(CAL_A)!!.highWater)
    assertEquals(h.client.event(CAL_A, A2).deactivatedAt, h.store.cursors.get(CAL_A)!!.highWater)
    h.assertForeignUntouched()

    assertEquals(0, h.sync().assertOk().counts.writes)
  }

  @Test
  fun incrementalStopsAfterTheFirstPageWhenTheTopOfTheFeedIsKnown() = runTest {
    h.sync().assertOk()
    h.client.pageRequests.clear()
    val report = h.sync().assertOk()
    assertEquals(SyncMode.INCREMENTAL, report.calendars.single().mode)
    assertEquals(0, report.counts.writes)
    assertEquals(listOf("1:top"), h.client.pageRequests)
  }

  @Test
  fun fullRunReadsEveryBackwardPageAndRereadsTheTop() = runTest {
    // 7 events, 2 a page: 4 pages backwards, then the top once more (EventFeed).
    val report = h.sync().assertOk()
    assertEquals(SyncMode.FULL, report.calendars.single().mode)
    assertEquals(h.liveA, h.copies().keys)
    val feed = h.client.all(CAL_A).sortedByDescending { it.updatedAt }
    assertEquals(
      listOf(
        "1:top",
        "1:since=${feed[1].updatedAt - 1}",
        "1:since=${feed[3].updatedAt - 1}",
        "1:since=${feed[5].updatedAt - 1}",
        "1:top",
      ),
      h.client.pageRequests,
    )
    val cursor = h.store.cursors.get(CAL_A)!!
    assertEquals(h.client.newestChange(CAL_A), cursor.highWater)
    assertEquals(4, cursor.lastFullPages) // the feed, without the second pass
  }

  @Test
  fun anEventEditedWhileAFullRunReadsThePagesIsNotTakenForDeleted() = runTest {
    h.sync().assertOk()
    val a1 = idOf(A1)
    // A1 is the oldest change, on the last page. Edited before that page is read, it moves to the
    // top, which the first pass has already read: only the second pass from the top sees it.
    h.client.beforePage = { page -> if (page == 2) h.client.edit(CAL_A, A1) { it.copy(title = "Moved") } }
    val report = h.sync(full = true).assertOk()
    h.client.beforePage = null
    assertEquals(0, report.counts.deleted)
    assertEquals(1, report.counts.updated)
    assertEquals("Moved", h.gateway.rows.getValue(a1).title)
    assertFalse(h.gateway.rows.getValue(a1).deleted)
  }

  @Test
  fun eventsInsideTheOverlapAreReadAgainButWriteNothing() = runTest {
    h.client.step = 1_000 // every change within FEED_OVERLAP of the newest one
    h.client.all(CAL_A).forEach { h.client.put(CAL_A, it) }
    h.sync().assertOk()
    h.client.pageRequests.clear()
    val again = h.sync().assertOk()
    assertEquals(SyncMode.INCREMENTAL, again.calendars.single().mode)
    assertEquals(4, h.client.pageRequests.size) // the whole feed: none is old enough to stop at
    assertEquals(0, again.counts.writes)
  }

  @Test
  fun anEmptyCalendarStoresHighWaterZero() = runTest {
    h.client.all(CAL_A).forEach { h.client.purge(CAL_A, it.uuid) }
    h.sync().assertOk()
    assertEquals(0L, h.store.cursors.get(CAL_A)!!.highWater)
    assertEquals(1, h.store.cursors.get(CAL_A)!!.lastFullPages)
    h.client.put(CAL_A, h.timed(A1, day = 0, title = "Breakfast"))
    assertEquals(1, h.sync().assertOk().counts.added)
  }

  // When a run is FULL (deleted events vanish from the feed, so only a FULL sees them) ------------

  @Test
  fun aSmallFeedIsReadCompletelyOnEveryRunSoDeletionsArriveWithinTheInterval() = runTest {
    h.client.pageSize = 10 // calendar A fits on one page
    h.sync().assertOk()
    assertEquals(1, h.store.cursors.get(CAL_A)!!.lastFullPages)
    h.client.purge(CAL_A, A3) // deleted in TimeTree: gone from the feed, no tombstone
    h.now += 60 * 60_000L

    val report = h.sync().assertOk()
    assertEquals(SyncMode.FULL, report.calendars.single().mode)
    assertEquals(1, report.counts.deleted)
    assertFalse(A3 in h.copies())
    assertTrue(h.log.lines.any { it == "I calendar 1: FULL (small feed, pages=1)" })
    h.assertForeignUntouched()
  }

  @Test
  fun aLargerFeedRunsIncrementalWithAFullAtLeastEverySixHours() = runTest {
    h.sync().assertOk() // 4 pages
    h.now += 6 * 60 * 60_000L - 60_000L
    assertEquals(SyncMode.INCREMENTAL, h.sync().assertOk().calendars.single().mode)
    assertTrue(h.log.lines.any { it == "I calendar 1: INCREMENTAL (last FULL 359 min ago, pages=4)" })

    h.client.purge(CAL_A, A3)
    h.now += 60_000L
    val full = h.sync().assertOk()
    assertEquals(SyncMode.FULL, full.calendars.single().mode)
    assertEquals(1, full.counts.deleted)
    assertTrue(h.log.lines.any { it == "I calendar 1: FULL (last FULL 360 min ago, pages=4)" })
    assertEquals(6 * 60 * 60_000L, DefaultSyncEngine.FULL_INTERVAL_MILLIS)
  }

  @Test
  fun theFeedSizeOfTheLastFullDecidesAndIsRecordedAgain() = runTest {
    h.client.pageSize = 3 // 7 events: 3 pages, still small
    h.sync().assertOk()
    assertEquals(3, h.store.cursors.get(CAL_A)!!.lastFullPages)
    assertEquals(3, DefaultSyncEngine.SMALL_FEED_PAGES)
    repeat(3) { i -> h.client.put(CAL_A, h.timed("c%031d".format(i), day = 1, title = "E$i")) }
    assertEquals(SyncMode.FULL, h.sync().assertOk().calendars.single().mode) // records 4 pages
    assertEquals(4, h.store.cursors.get(CAL_A)!!.lastFullPages)
    assertEquals(SyncMode.INCREMENTAL, h.sync().assertOk().calendars.single().mode)
    assertEquals(4, h.store.cursors.get(CAL_A)!!.lastFullPages) // kept by INCREMENTAL runs
  }

  @Test
  fun aMissingHighWaterMarkForcesAFullRun() = runTest {
    h.sync().assertOk()
    val cursor = h.store.cursors.get(CAL_A)!!
    h.store.cursors.put(cursor.copy(highWater = null)) // as after the upgrade from schema 1
    assertEquals(SyncMode.FULL, h.sync().assertOk().calendars.single().mode)
    assertEquals(h.client.newestChange(CAL_A), h.store.cursors.get(CAL_A)!!.highWater)
  }

  @Test
  fun anIncrementalRunNeverDeletesByAbsence() = runTest {
    h.sync().assertOk()
    h.client.pageSize = 100 // the incremental read happens to see the whole feed
    h.client.purge(CAL_A, A3)
    h.client.edit(CAL_A, A1) { it.copy(title = "Changed") }
    val report = h.sync().assertOk()
    assertEquals(SyncMode.INCREMENTAL, report.calendars.single().mode)
    assertEquals(0, report.counts.deleted)
    assertTrue(A3 in h.copies())
  }

  // E3, E4 --------------------------------------------------------------------------------------

  @Test
  fun e3EditUpdatesTheSameRow() = runTest {
    h.sync().assertOk()
    val id = idOf(A1)
    h.client.edit(CAL_A, A1) { it.copy(title = "Brunch", startAt = it.startAt + 3_600_000, endAt = it.endAt + 3_600_000) }

    val report = h.sync().assertOk()
    assertEquals(SyncCounts(updated = 1), report.counts.copy(skipped = 0))
    val row = h.copies().getValue(A1)
    assertEquals(id, row.id)
    assertEquals("Brunch", row.title)
    h.assertForeignUntouched()
  }

  @Test
  fun e4DeleteRemovesTheCopyAndAFullRunCatchesSilentDeletes() = runTest {
    h.sync().assertOk()
    val a2 = idOf(A2)
    h.client.deactivate(CAL_A, A2)
    assertEquals(1, h.sync().assertOk().counts.deleted)
    assertTrue(h.gateway.rows.getValue(a2).deleted)
    assertNull(h.store.mappings.get(A2))

    h.client.purge(CAL_A, A3) // gone without a tombstone: only a complete fetch notices
    assertEquals(0, h.sync().assertOk().counts.deleted)
    assertEquals(1, h.sync(full = true).assertOk().counts.deleted)
    assertEquals(setOf(A1, SERIES), h.copies().keys)
    h.assertForeignUntouched()
  }

  @Test
  fun tombstoneWithoutTimesDeletesByTheMappingsEndAt() = runTest {
    h.sync().assertOk()
    h.client.deactivate(CAL_A, A1, unknownTimes = true) // start/end 0: the mapper leaves it out
    val report = h.sync().assertOk()
    assertEquals(1, report.counts.deleted)
    assertFalse(A1 in h.copies())
    h.assertForeignUntouched()
  }

  @Test
  fun becomingAMemoDeletesTheCopy() = runTest {
    h.sync().assertOk()
    h.client.edit(CAL_A, A1) { it.copy(category = 2) }
    assertEquals(1, h.sync().assertOk().counts.deleted)
    assertFalse(A1 in h.copies())
  }

  // E5 ------------------------------------------------------------------------------------------

  @Test
  fun e5ReusedIdOnAForeignRowStaysUntouched() = runTest {
    h.sync().assertOk()
    val reused = idOf(A1)
    // Storage reset: our row is gone and the user's new event got the same _ID.
    h.gateway.rows.remove(reused)
    h.addForeign(TARGET, "My new event", "Personal", id = reused)
    h.client.edit(CAL_A, A1) { it.copy(title = "Changed") }

    val report = h.sync().assertOk()
    assertEquals(1, report.counts.rejected)
    assertEquals(1, report.counts.added)
    assertNotEquals(reused, idOf(A1))
    assertEquals(idOf(A1), h.store.mappings.get(A1)!!.eventId)
    h.assertForeignUntouched()
  }

  @Test
  fun e5bRowsRedownloadedWithNewIdsAreAdoptedNotDuplicated() = runTest {
    h.sync().assertOk()
    val old = h.copies().getValue(A2)
    h.gateway.rows.remove(old.id) // re-downloaded by Google's sync under a new _ID
    val fresh = h.gateway.addRow(TARGET, old.title, old.description)
    h.addForeign(TARGET, "Reuses the old id", "mine", id = old.id)

    val report = h.sync(full = true).assertOk()
    assertEquals(0, report.counts.added)
    assertEquals(1, report.counts.adopted)
    assertEquals(fresh, idOf(A2))
    h.assertForeignUntouched()
  }

  // E6, E7 --------------------------------------------------------------------------------------

  @Test
  fun e6WipedMappingsGetAdopted() = runTest {
    h.sync().assertOk()
    val ids = h.copies().mapValues { it.value.id }
    h.engine.dropMappings()

    val report = h.sync().assertOk() // INCREMENTAL: the empty table triggers the adoption scan
    assertEquals(SyncMode.INCREMENTAL, report.calendars.single().mode)
    assertEquals(0, report.counts.added)
    assertEquals(4, report.counts.adopted)
    assertEquals(4, report.counts.updated) // one verified update each, as designed
    assertEquals(ids, h.copies().mapValues { it.value.id })
    assertEquals(0, h.sync().assertOk().counts.writes)
    h.assertForeignUntouched()
  }

  @Test
  fun e7CrashBetweenInsertAndMappingIsAdoptedNextRun() = runTest {
    var crash = true
    h.gateway.afterApply = { if (crash) throw RuntimeException("process died") }
    val failed = h.sync()
    assertEquals(SyncProblem.CALENDAR_WRITE_FAILED, failed.failure?.problem)
    assertEquals(4, h.copies().size) // written, but not recorded
    assertEquals(0, h.store.mappings.count())
    assertNotNull(h.store.meta.get(MetaKeys.RUN_IN_PROGRESS))
    assertNull(h.store.cursors.get(CAL_A))

    crash = false
    val report = h.sync().assertOk()
    assertEquals(0, report.counts.added)
    assertEquals(4, report.counts.adopted)
    assertEquals(h.liveA, h.copies().keys) // copies() fails on duplicates
    assertNull(h.store.meta.get(MetaKeys.RUN_IN_PROGRESS))
    h.assertForeignUntouched()
  }

  @Test
  fun e7bMappingWriteFailureInAnIncrementalRunIsAdoptedNextRun() = runTest {
    h.sync().assertOk()
    h.client.put(CAL_A, h.timed("aaaa0004aaaa0004aaaa0004aaaa0004", day = 3, title = "New"))
    h.db.failMappingWrites = true
    assertEquals(SyncProblem.INTERNAL, h.sync().failure?.problem)
    h.db.failMappingWrites = false

    val report = h.sync().assertOk()
    assertEquals(0, report.counts.added)
    assertEquals(1, report.counts.adopted)
    assertEquals(5, h.copies().size)
  }

  // E8 ------------------------------------------------------------------------------------------

  @Test
  fun e8DuplicatesAreDeletedOnlyForLiveActiveUuids() = runTest {
    h.sync().assertOk()
    h.client.deactivate(CAL_A, A3)
    h.sync().assertOk()
    val a1 = h.copies().getValue(A1)
    val a2 = h.copies().getValue(A2)
    val extraA1 = listOf(h.gateway.addRow(TARGET, a1.title, a1.description), h.gateway.addRow(TARGET, a1.title, a1.description))
    val extraA2 = h.gateway.addRow(TARGET, a2.title, a2.description, id = 5) // lower _ID than the mapped row
    // Marked rows of a dead uuid, an out-of-window uuid and an unknown one: never touched.
    for (uuid in listOf(A3, A3, OLD, "unknown-uuid-9")) h.addForeignMarked(uuid)

    val report = h.sync(full = true).assertOk()
    assertEquals(3, report.counts.deleted)
    for (id in extraA1 + extraA2) assertTrue(h.gateway.rows.getValue(id).deleted)
    assertEquals(a1.id, idOf(A1)) // the mapped row is the one kept
    assertEquals(a2.id, idOf(A2))
    h.assertForeignUntouched()
  }

  @Test
  fun e8bUnmappedDuplicatesKeepTheLowestId() = runTest {
    h.sync().assertOk()
    val a1 = h.copies().getValue(A1)
    val extra = h.gateway.addRow(TARGET, a1.title, a1.description)
    h.engine.dropMappings()

    val report = h.sync().assertOk()
    assertEquals(1, report.counts.deleted)
    assertEquals(0, report.counts.added)
    assertEquals(a1.id, idOf(A1))
    assertTrue(h.gateway.rows.getValue(extra).deleted)
  }

  private fun EngineHarness.addForeignMarked(uuid: String): Long {
    val id = gateway.addRow(TARGET, "marked", "Copied\n" + Marker.line(uuid))
    unmanagedMarked += id
    trackUserRow(id)
    return id
  }

  // E9 ------------------------------------------------------------------------------------------

  @Test
  fun e9TargetChangeRemovesOurRowsOnlyAndFillsTheNewTarget() = runTest {
    h.sync().assertOk()

    val removal = h.engine.removeAllCopies(h.target, stopSyncing = false)
    assertTrue(removal.succeeded)
    assertEquals(6, removal.deleted) // 4 copies and 2 unmapped marked rows: explicit removal takes all marked rows
    assertEquals(0, removal.rejected)
    assertTrue(h.gateway.markedRows(TARGET).isEmpty())
    assertEquals(0, h.store.mappings.count())
    assertEquals(listOf(SourceCalendar(CAL_A, "Family")), h.settings.value.sources) // syncing continues
    h.assertForeignUntouched(includeUnmanagedMarked = false)

    val newTarget = TargetCalendar.of(EngineHarness.google(NEW_TARGET, "ttsync"))
    h.settings.setTarget(newTarget, "ttsync")
    val report = h.sync(full = true).assertOk()
    assertEquals(4, report.counts.added)
    assertEquals(h.liveA, h.copies(NEW_TARGET).keys)
    assertTrue(h.copies(TARGET).isEmpty())
    assertTrue(h.store.mappings.all().all { it.targetCalId == NEW_TARGET })
    h.assertForeignUntouched(includeUnmanagedMarked = false)
  }

  @Test
  fun staleMappingsOfAnOldTargetAreNeverUsed() = runTest {
    h.sync().assertOk()
    // The target changes without the removal step (e.g. it failed): old mappings must not steer writes.
    h.settings.setTarget(TargetCalendar.of(EngineHarness.google(NEW_TARGET, "ttsync")), "ttsync")
    h.client.deactivate(CAL_A, A2)
    val report = h.sync().assertOk()
    assertEquals(0, report.counts.deleted) // A2's old copy lives in another calendar
    assertEquals(setOf(A1, A3, SERIES), h.copies(NEW_TARGET).keys)
    assertEquals(h.liveA, h.copies(TARGET).keys) // the old target is not touched at all
    h.assertForeignUntouched()
  }

  // E10 -----------------------------------------------------------------------------------------

  @Test
  fun e10DeselectDeletesOnlyThatCalendarsCopies() = runTest {
    h.selectB()
    h.sync().assertOk()
    assertEquals(h.liveA + h.liveB, h.copies().keys)

    val removal = h.engine.deselect(setOf(CAL_B))
    assertTrue(removal.succeeded)
    assertEquals(2, removal.deleted)
    assertEquals(h.liveA, h.copies().keys)
    assertEquals(listOf(CAL_A), h.settings.value.sources.map { it.id })
    assertTrue(h.store.snapshots.forCalendar(CAL_B).isEmpty())
    assertTrue(h.store.mappings.forCalendar(CAL_B).isEmpty())
    assertNull(h.store.cursors.get(CAL_B))
    h.assertForeignUntouched() // unmapped marked rows too: deselect deletes mapped copies only

    h.client.requests.clear()
    assertEquals(0, h.sync().assertOk().counts.writes)
    assertFalse(h.client.requests.any { it.startsWith("events:2") })
  }

  @Test
  fun deselectKeepsItsDataWhenTheDeletesFail() = runTest {
    h.selectB()
    h.sync().assertOk()
    h.gateway.onApply = { throw RuntimeException("provider died") }
    val removal = h.engine.deselect(setOf(CAL_B))
    assertEquals(SyncProblem.CALENDAR_WRITE_FAILED, removal.failure?.problem)
    assertEquals(2, h.store.mappings.forCalendar(CAL_B).size)
    h.gateway.onApply = null
    assertEquals(2, h.engine.deselect(setOf(CAL_B)).deleted)
  }

  @Test
  fun aFailedDeselectKeepsTheCalendarSelectedSoItCanBeRetried() = runTest {
    // Phase 4 review, SHOULD-FIX-1: the selection changes only after the copies are gone.
    h.selectB()
    h.sync().assertOk()
    val copiesBefore = h.copies().keys
    h.gateway.onApply = { throw RuntimeException("provider died") }

    val removal = h.engine.deselect(setOf(CAL_B))

    assertEquals(SyncProblem.CALENDAR_WRITE_FAILED, removal.failure?.problem)
    assertEquals(listOf(CAL_A, CAL_B), h.settings.value.sources.map { it.id })
    assertEquals(2, h.store.mappings.forCalendar(CAL_B).size)
    assertTrue(h.store.snapshots.forCalendar(CAL_B).isNotEmpty())
    assertNotNull(h.store.cursors.get(CAL_B))
    assertEquals(copiesBefore, h.copies().keys)

    h.gateway.onApply = null
    val retry = h.engine.deselect(setOf(CAL_B))
    assertTrue(retry.succeeded)
    assertEquals(2, retry.deleted)
    assertEquals(listOf(CAL_A), h.settings.value.sources.map { it.id })
    assertEquals(h.liveA, h.copies().keys)
  }

  // E11 -----------------------------------------------------------------------------------------

  @Test
  fun e11LostAccessPausesAndAFullRunWithAdoptionDeletesNothingOfIt() = runTest {
    h.selectB()
    h.sync().assertOk()
    val bRows = h.copies().filterKeys { it in h.liveB }.mapValues { it.value }
    h.client.calendars.removeAll { it.id == CAL_B }
    h.client.requests.clear()

    val report = h.sync().assertOk()
    val b = report.calendars.single { it.ttCalId == CAL_B }
    assertEquals(CalendarState.PAUSED, b.state)
    assertNull(b.mode)
    assertTrue(SyncWarning.CALENDAR_PAUSED in report.warnings)
    assertFalse(h.client.requests.any { it.startsWith("events:2") || it.startsWith("labels:2") })
    assertEquals(CalendarState.PAUSED, h.store.cursors.get(CAL_B)!!.state)

    // Lost mappings, a duplicate of a paused copy, a forced FULL: still nothing of B is touched.
    val dup = h.gateway.addRow(TARGET, bRows.getValue(B1).title, bRows.getValue(B1).description)
    h.engine.dropMappings()
    h.client.calendars += de.tergelius.ttsync.timetree.TtCalendar(CAL_B, "Club") // back: stays paused
    h.client.requests.clear()
    val full = h.sync(full = true).assertOk()
    assertEquals(0, full.counts.deleted)
    assertEquals(4, full.counts.adopted)
    assertEquals(5, full.counts.unmanagedMarked) // B1 twice, B2, and the two unmanaged rows
    for ((_, row) in bRows) assertEquals(row, h.gateway.rows[row.id])
    assertFalse(h.gateway.rows.getValue(dup).deleted)
    assertFalse(h.client.requests.any { it.startsWith("events:2") || it.startsWith("labels:2") })
    h.assertForeignUntouched()
  }

  @Test
  fun accessLostOnEventsPausesOnlyThatCalendar() = runTest {
    h.selectB()
    h.client.fail = { if (it.startsWith("events:2")) TtError.AccessLost(CAL_B, 404) else null }
    val report = h.sync().assertOk()
    assertEquals(CalendarState.PAUSED, report.calendars.single { it.ttCalId == CAL_B }.state)
    assertEquals(h.liveA, h.copies().keys)
  }

  // E12 -----------------------------------------------------------------------------------------

  @Test
  fun e12AFailedRunKeepsTheCursor() = runTest {
    h.sync().assertOk()
    val cursor = h.store.cursors.get(CAL_A)!!
    val snapshot = h.store.snapshots.get(A1)
    h.client.edit(CAL_A, A1) { it.copy(title = "Changed") }
    h.client.put(CAL_A, h.timed("aaaa0004aaaa0004aaaa0004aaaa0004", day = 3, title = "New"))

    h.gateway.onApply = { throw RuntimeException("provider died") }
    assertEquals(SyncProblem.CALENDAR_WRITE_FAILED, h.sync().failure?.problem)
    assertEquals(cursor, h.store.cursors.get(CAL_A))
    assertEquals(snapshot, h.store.snapshots.get(A1))

    h.gateway.onApply = null
    val report = h.sync().assertOk()
    assertEquals(1, report.counts.updated)
    assertEquals(1, report.counts.added)
    assertNotEquals(cursor.highWater, h.store.cursors.get(CAL_A)!!.highWater)
  }

  @Test
  fun aNetworkFailureWritesNothingAtAll() = runTest {
    h.selectB()
    h.client.fail = { if (it.startsWith("events:2")) TtError.Transient(503) else null }
    val report = h.sync()
    assertEquals(SyncFailure(SyncProblem.NETWORK, code = 503), report.failure)
    assertTrue(h.gateway.batchSizes.isEmpty()) // calendar A was fetched, but nothing is written before all are
    assertNull(h.store.cursors.get(CAL_A))
  }

  // E13 -----------------------------------------------------------------------------------------

  @Test
  fun e13MappingWithAnOldEndAtStaysUntouchedEvenWhenMissingOrDeleted() = runTest {
    val past = "aaaa0d0daaaa0d0daaaa0d0daaaa0d0d"
    val ghost = "aaaa0d0eaaaa0d0eaaaa0d0eaaaa0d0e"
    val future = "aaaa0f0faaaa0f0faaaa0f0faaaa0f0f"
    h.client.put(CAL_A, h.timed(past, day = -10, title = "Recently"))
    h.client.put(CAL_A, h.timed(ghost, day = -9, title = "Recently too"))
    h.client.put(CAL_A, h.timed(future, day = 20, title = "Later"))
    h.sync().assertOk()
    val pastRow = h.copies().getValue(past)
    val ghostRow = h.copies().getValue(ghost)

    h.now += 40 * DAY // both copies have ended more than 30 days ago now
    h.client.purge(CAL_A, past)
    h.client.deactivate(CAL_A, ghost, unknownTimes = true)
    h.client.purge(CAL_A, future) // control: in the window, so it is deleted
    val report = h.sync(full = true).assertOk()

    assertEquals(1, report.counts.deleted)
    assertEquals(pastRow, h.gateway.rows[pastRow.id])
    assertEquals(ghostRow, h.gateway.rows[ghostRow.id])
    assertNotNull(h.store.mappings.get(past))
    assertFalse(future in h.copies())
    h.assertForeignUntouched()
  }

  // E14 -----------------------------------------------------------------------------------------

  @Test
  fun e14UnknownAndOutOfWindowMarkedRowsStayUntouchedInAFullRunOnAnEmptyTable() = runTest {
    h.sync().assertOk()
    h.engine.dropMappings()
    val report = h.sync(full = true).assertOk()
    assertEquals(2, report.counts.unmanagedMarked)
    assertEquals(0, report.counts.deleted)
    assertEquals(0, report.counts.added)
    h.assertForeignUntouched()
  }

  // E15 -----------------------------------------------------------------------------------------

  @Test
  fun e15AFailedPreconditionAbortsTheChunkWhichIsReplayedOneByOne() = runTest {
    h.sync().assertOk()
    val a2 = idOf(A2)
    for (uuid in listOf(A1, A2, A3)) h.client.edit(CAL_A, uuid) { it.copy(title = "Edited") }
    val before = h.gateway.batchSizes.size
    var once = true
    // Between the pre-read and the batch the user removes the marker from the copy of A2.
    h.gateway.betweenReadAndApply = {
      if (once) {
        once = false
        h.userTakesOver(a2, "Mine now")
      }
    }

    val report = h.sync().assertOk()
    assertEquals(listOf(3, 1, 1, 1, 1), h.gateway.batchSizes.drop(before))
    assertEquals(2, report.counts.updated)
    assertEquals(1, report.counts.added) // A2 went back through adopt-or-insert
    assertEquals(1, report.counts.rejected)
    assertNotEquals(a2, idOf(A2))
    h.assertForeignUntouched()
  }

  @Test
  fun aPreconditionThatFailsOnOurOwnRowsStopsInsteadOfDuplicating() = runTest {
    h.sync().assertOk()
    h.client.edit(CAL_A, A1) { it.copy(title = "Edited") }
    h.gateway.brokenPrecondition = true
    assertEquals(SyncProblem.CALENDAR_WRITE_FAILED, h.sync().failure?.problem)
    assertEquals(h.liveA, h.copies().keys) // nothing inserted
    h.gateway.brokenPrecondition = false
    assertEquals(1, h.sync().assertOk().counts.updated)
  }

  // Preconditions -------------------------------------------------------------------------------

  @Test
  fun preconditionFailuresStopBeforeAnyRequestOrWrite() = runTest {
    suspend fun expect(problem: SyncProblem, setup: () -> Unit, undo: () -> Unit) {
      setup()
      assertEquals(problem, h.sync().failure?.problem)
      undo()
    }
    val info = h.gateway.calendars.getValue(TARGET)
    val settings = h.settings.value
    expect(SyncProblem.NOT_CONFIGURED, { h.settings.value = settings.copy(sources = emptyList()) }) { h.settings.value = settings }
    expect(SyncProblem.NOT_CONFIGURED, { h.settings.value = settings.copy(target = null) }) { h.settings.value = settings }
    expect(SyncProblem.NO_CALENDAR_PERMISSION, { h.gateway.permission = false }) { h.gateway.permission = true }
    expect(SyncProblem.TARGET_DELETED, { h.gateway.calendars.remove(TARGET) }) { h.gateway.calendars[TARGET] = info }
    for (changed in
      listOf(info.copy(accountName = "other@example.org"), info.copy(accessLevel = 200), info.copy(syncEvents = false))) {
      expect(SyncProblem.TARGET_UNAVAILABLE, { h.gateway.calendars[TARGET] = changed }) { h.gateway.calendars[TARGET] = info }
    }
    val credentials = h.credentials.stored
    expect(SyncProblem.NOT_SIGNED_IN, { h.credentials.stored = null }) { h.credentials.stored = credentials }

    assertTrue(h.client.requests.isEmpty())
    assertTrue(h.gateway.batchSizes.isEmpty())
    assertNull(h.store.meta.get(MetaKeys.RUN_IN_PROGRESS))
    h.sync().assertOk()
  }

  @Test
  fun permissionOrTargetLostDuringTheRunStopsIt() = runTest {
    h.gateway.onApply = { throw SecurityException("revoked") }
    assertEquals(SyncProblem.NO_CALENDAR_PERMISSION, h.sync().failure?.problem)
    h.gateway.onApply = { h.gateway.calendars.remove(TARGET) }
    assertEquals(SyncProblem.TARGET_DELETED, h.sync().failure?.problem)
    assertTrue(h.copies().isEmpty())
    h.assertForeignUntouched()
  }

  @Test
  fun googleSyncOffStillWritesButDoesNotRequestASync() = runTest {
    h.gateway.syncState = h.gateway.syncState.copy(calendarSync = false)
    val report = h.sync().assertOk()
    assertTrue(SyncWarning.GOOGLE_SYNC_OFF in report.warnings)
    assertEquals(4, report.counts.added)
    assertEquals(0, h.gateway.uploadRequests)
  }

  // Google sync and uploads (device test B2) -----------------------------------------------------

  @Test
  fun anAccountSwitchAndroidHidesIsNoWarningAndTheSyncRequestIsBestEffort() = runTest {
    // Android 13+ answers "off" for an account the app cannot see: the gateway reports unknown.
    h.gateway.syncState = GoogleSyncState(masterSync = true, calendarSync = null)
    val report = h.sync().assertOk()
    assertTrue(report.warnings.isEmpty())
    assertEquals(1, h.gateway.uploadRequests)
  }

  @Test
  fun copiesGoogleHasNotTakenHalfAnHourLaterAreReported() = runTest {
    h.sync().assertOk()
    assertTrue(h.store.mappings.all().all { it.writtenAt == h.now })
    h.now += 29 * 60_000L
    assertEquals(0, h.sync().assertOk().notUploaded) // Google's sync may still be on its way

    h.now += 2 * 60_000L
    val late = h.sync().assertOk()
    assertEquals(4, late.notUploaded)
    assertEquals(setOf(SyncWarning.NOT_UPLOADED), late.warnings)

    h.gateway.uploadAll()
    h.now += 60_000L
    val uploaded = h.sync().assertOk()
    assertEquals(0, uploaded.notUploaded)
    assertTrue(uploaded.warnings.isEmpty())
  }

  @Test
  fun anUnreadableDirtyColumnMeansNoSignalNotAFailure() = runTest {
    h.sync().assertOk()
    h.gateway.dirtyReadable = false
    h.now += 60 * 60_000L
    val report = h.sync().assertOk()
    assertEquals(0, report.notUploaded)
    assertTrue(report.warnings.isEmpty())
  }

  @Test
  fun aKnownOffSwitchIsTheOnlyWarningForPendingUploads() = runTest {
    h.sync().assertOk()
    h.gateway.syncState = GoogleSyncState(masterSync = false, calendarSync = null)
    h.now += 60 * 60_000L
    val report = h.sync().assertOk()
    assertEquals(4, report.notUploaded)
    assertEquals(setOf(SyncWarning.GOOGLE_SYNC_OFF), report.warnings)
  }

  @Test
  fun copiesTheRunDeletesOrRewritesAreNotCountedAsStale() = runTest {
    // Review fix1, SHOULD-FIX-1: counted after the writes, not before them.
    h.sync().assertOk()
    h.now += 60 * 60_000L // all 4 copies are still dirty an hour later
    h.client.deactivate(CAL_A, A1)
    h.client.edit(CAL_A, A2) { it.copy(title = "Renamed") }
    val report = h.sync().assertOk()
    assertEquals(1, report.counts.deleted)
    assertEquals(1, report.counts.updated)
    assertEquals(2, report.notUploaded) // A3 and SERIES; A2's copy starts a new grace period
    assertEquals(setOf(SyncWarning.NOT_UPLOADED), report.warnings)
  }

  @Test
  fun aRunThatFailsAfterThePreconditionsStillReportsPendingUploads() = runTest {
    h.sync().assertOk()
    h.now += 60 * 60_000L
    h.client.fail = { if (it.startsWith("events")) TtError.Transient(503) else null }
    val report = h.sync()
    assertEquals(SyncProblem.NETWORK, report.failure?.problem)
    assertEquals(4, report.notUploaded)
    assertTrue(SyncWarning.NOT_UPLOADED in report.warnings)
  }

  // TimeTree errors -----------------------------------------------------------------------------

  @Test
  fun rateLimitBlocksRequestsUntilItExpires() = runTest {
    h.client.fail = { if (it.startsWith("events")) TtError.RateLimited(45.minutes) else null }
    val limited = h.sync()
    assertEquals(SyncFailure(SyncProblem.RATE_LIMITED, retryAfterMillis = 45 * 60_000L), limited.failure)
    assertEquals(h.now + 45 * 60_000L, h.status.value.rateLimitedUntil)

    h.client.fail = null
    h.now += 10 * 60_000L
    val requests = h.client.requests.size
    assertEquals(SyncProblem.RATE_LIMITED, h.sync().failure?.problem)
    assertEquals(requests, h.client.requests.size)

    h.now += 36 * 60_000L
    h.sync().assertOk()
    assertNull(h.status.value.rateLimitedUntil)
  }

  @Test
  fun timeTreeErrorsMapToProblems() = runTest {
    val cases =
      listOf(
        TtError.AuthRejected(-702) to SyncFailure(SyncProblem.AUTH_REJECTED, code = -702),
        TtError.LoginRequired() to SyncFailure(SyncProblem.NOT_SIGNED_IN),
        TtError.ApiChanged("x") to SyncFailure(SyncProblem.API_CHANGED),
        TtError.Transient(null) to SyncFailure(SyncProblem.NETWORK),
        TtError.RateLimited(null) to SyncFailure(SyncProblem.RATE_LIMITED, retryAfterMillis = 30 * 60_000L),
      )
    for ((error, failure) in cases) {
      h.status.value = h.status.value.copy(rateLimitedUntil = null)
      h.client.fail = { if (it == "calendars") error else null }
      assertEquals(failure, h.sync().failure)
    }
    assertTrue(h.gateway.batchSizes.isEmpty())
  }

  // Modes, chunks, labels, recurrences --------------------------------------------------------------

  @Test
  fun fullRunsDailyAndAfterAMapperVersionChange() = runTest {
    h.sync().assertOk()
    assertEquals(SyncMode.INCREMENTAL, h.sync().calendars.single().mode)
    h.now += 25 * 3_600_000L
    assertEquals(SyncMode.FULL, h.sync().calendars.single().mode)
    h.store.meta.put(MetaKeys.MAPPER_VERSION, "0")
    assertEquals(SyncMode.FULL, h.sync().calendars.single().mode)
    assertEquals(SyncMode.INCREMENTAL, h.sync().calendars.single().mode)
  }

  @Test
  fun writesGoInChunksOfAtMostFifty() = runTest {
    repeat(120) { i -> h.client.put(CAL_A, h.timed("c%031d".format(i), day = 1 + i % 20, title = "E$i")) }
    val report = h.sync().assertOk()
    assertEquals(124, report.counts.added)
    assertEquals(listOf(50, 50, 24), h.gateway.batchSizes)
  }

  @Test
  fun aChangedOverrideAlsoUpdatesItsParent() = runTest {
    h.sync().assertOk()
    val series = h.copies().getValue(SERIES)
    val moved = "aaaa5e5faaaa5e5faaaa5e5faaaa5e5f"
    h.client.put(CAL_A, h.timed(moved, day = 6, title = "Weekly, moved").copy(recurringUuid = SERIES))
    val report = h.sync().assertOk()
    assertEquals(1, report.counts.added)
    assertEquals(1, report.counts.updated)
    val updated = h.copies().getValue(SERIES)
    assertEquals(series.id, updated.id)
    assertNotNull(updated.values[EventSpec.COL_EXDATE])
  }

  @Test
  fun anUnknownLabelIsFetchedOnce() = runTest {
    h.sync().assertOk()
    h.client.labels[CAL_A] = listOf(TtLabel(5, "Sports"))
    h.client.edit(CAL_A, A1) { it.copy(labelId = 5) }
    h.sync().assertOk()
    assertEquals(2, h.client.requests.count { it == "labels:1" })
    assertTrue(h.copies().getValue(A1).description!!.contains("Label: Sports"))
    h.sync().assertOk()
    assertEquals(2, h.client.requests.count { it == "labels:1" })
  }

  @Test
  fun unreadableSnapshotsForceAFullRun() = runTest {
    h.sync().assertOk()
    h.store.snapshots.upsert(listOf(h.store.snapshots.get(A1)!!.copy(json = "{broken")))
    val report = h.sync().assertOk()
    assertEquals(SyncMode.FULL, report.calendars.single().mode)
    assertEquals(0, report.counts.writes)
  }

  // Unreadable events (EventsResult.skippedUuids / skippedMalformed) --------------------------------

  @Test
  fun aSkippedUuidIsNeverTreatedAsDeleted() = runTest {
    h.sync().assertOk()
    val a2 = h.copies().getValue(A2)
    h.client.edit(CAL_A, A2) { it.copy(title = "now unreadable") }
    h.client.skipped = setOf(A2)

    val incremental = h.sync().assertOk()
    assertEquals(0, incremental.counts.writes)
    val full = h.sync(full = true).assertOk()
    assertEquals(0, full.counts.writes)
    assertEquals(a2, h.gateway.rows[a2.id])
    assertNotNull(h.store.mappings.get(A2))
    assertNotNull(h.store.snapshots.get(A2)) // the last good state is kept

    h.client.skipped = emptySet()
    h.client.edit(CAL_A, A2) { it.copy(title = "readable again") }
    assertEquals(1, h.sync().assertOk().counts.updated)
  }

  @Test
  fun aFullFetchThatSkippedEventsWithoutUuidDeletesNoMissingUuids() = runTest {
    h.sync().assertOk()
    h.client.purge(CAL_A, A3)
    h.client.skippedWithoutUuid = 1
    val report = h.sync(full = true).assertOk()
    assertEquals(0, report.counts.deleted)
    assertTrue(A3 in h.copies())
    assertNotNull(h.store.snapshots.get(A3)) // stored state is kept, not replaced

    h.client.skippedWithoutUuid = 0
    assertEquals(1, h.sync(full = true).assertOk().counts.deleted)
    assertFalse(A3 in h.copies())
  }

  @Test
  fun aSkippedUuidWithoutUuidlessSkipsStillAllowsOtherDeletes() = runTest {
    h.sync().assertOk()
    h.client.skipped = setOf(A2)
    h.client.purge(CAL_A, A3)
    val report = h.sync(full = true).assertOk()
    assertEquals(1, report.counts.deleted)
    assertEquals(setOf(A1, A2, SERIES), h.copies().keys)
  }

  // Copies the user changed ------------------------------------------------------------------------

  @Test
  fun aCopyDeletedInGoogleComesBackOnTheNextFullRunOnly() = runTest {
    h.sync().assertOk()
    val a1 = idOf(A1)
    h.gateway.userDelete(a1)
    val deleted = h.gateway.rows.getValue(a1)
    assertEquals(0, h.sync().assertOk().counts.writes)
    val report = h.sync(full = true).assertOk()
    assertEquals(1, report.counts.added)
    assertEquals(deleted, h.gateway.rows[a1])
  }

  @Test
  fun aCopyWhoseMarkerTheUserRemovedBecomesTheirs() = runTest {
    h.sync().assertOk()
    val a1 = idOf(A1)
    h.userTakesOver(a1, "My own notes now")
    h.client.edit(CAL_A, A1) { it.copy(title = "Edited") }
    val report = h.sync().assertOk()
    assertEquals(1, report.counts.rejected)
    assertEquals(1, report.counts.added)
    h.client.deactivate(CAL_A, A1)
    assertEquals(1, h.sync().assertOk().counts.deleted) // the new copy, not the user's row
    h.assertForeignUntouched()
  }

  // Remove all copies -----------------------------------------------------------------------------

  @Test
  fun removeAllCopiesAlsoStopsSyncingSoNothingComesBack() = runTest {
    h.selectB()
    h.sync().assertOk()

    val removal = h.engine.removeAllCopies(h.target)
    assertTrue(removal.succeeded)
    assertEquals(8, removal.deleted)
    assertTrue(h.gateway.markedRows(TARGET).isEmpty())
    assertTrue(h.settings.value.sources.isEmpty())
    assertEquals(0, h.store.mappings.count())
    assertTrue(h.store.cursors.all().isEmpty())
    assertTrue(h.store.snapshots.forCalendar(CAL_A).isEmpty())
    h.assertForeignUntouched(includeUnmanagedMarked = false)

    h.client.requests.clear()
    assertEquals(SyncProblem.NOT_CONFIGURED, h.sync().failure?.problem)
    assertTrue(h.gateway.markedRows(TARGET).isEmpty())
    assertTrue(h.client.requests.isEmpty())
  }

  @Test
  fun removeAllCopiesOnAnUnwritableTargetDeletesNothingAndChangesNothing() = runTest {
    h.sync().assertOk()
    val sources = h.settings.value.sources
    val cursors = h.store.cursors.all()
    val mappings = h.store.mappings.all()
    val snapshots = h.store.snapshots.forCalendar(CAL_A)
    h.gateway.calendars[TARGET] = h.gateway.calendars.getValue(TARGET).copy(accessLevel = 200)
    val removal = h.engine.removeAllCopies(h.target)
    assertEquals(SyncProblem.TARGET_UNAVAILABLE, removal.failure?.problem)
    assertEquals(0, removal.deleted)
    assertEquals(4, h.copies().size)
    // Phase 5 review, SHOULD-FIX-1: syncing stops only after the deletes went through.
    assertEquals(sources, h.settings.value.sources)
    assertEquals(cursors, h.store.cursors.all())
    assertEquals(mappings, h.store.mappings.all())
    assertEquals(snapshots, h.store.snapshots.forCalendar(CAL_A))

    h.gateway.calendars.remove(TARGET)
    assertTrue(h.engine.removeAllCopies(h.target).succeeded) // gone with its rows
    assertTrue(h.settings.value.sources.isEmpty())
    assertTrue(h.store.cursors.all().isEmpty())
  }

  @Test
  fun removeAllCopiesWithoutCalendarPermissionIsAFailureThatKeepsEverything() = runTest {
    h.sync().assertOk()
    val cursors = h.store.cursors.all()
    h.gateway.permission = false
    val removal = h.engine.removeAllCopies(h.target)
    assertEquals(SyncProblem.NO_CALENDAR_PERMISSION, removal.failure?.problem)
    assertEquals(listOf(SourceCalendar(CAL_A, "Family")), h.settings.value.sources)
    assertEquals(cursors, h.store.cursors.all())
    assertEquals(4, h.store.mappings.count())

    h.gateway.permission = true
    assertEquals(6, h.engine.removeAllCopies(h.target).deleted) // 4 copies + 2 unmanaged marked rows
  }

  @Test
  fun removalsVerifyEveryRowAgainstItsMarker() = runTest {
    h.sync().assertOk()
    val a1 = idOf(A1)
    // Between the scan and the batch the user takes over one copy: it must survive the removal.
    var once = true
    h.gateway.betweenReadAndApply = {
      if (once) {
        once = false
        h.userTakesOver(a1, "Mine")
      }
    }
    val removal = h.engine.removeAllCopies(h.target)
    assertEquals(5, removal.deleted)
    assertEquals(1, removal.rejected)
    h.assertForeignUntouched(includeUnmanagedMarked = false)
  }

  @Test
  fun operationsOnlyEverTargetSingleRowsInTheTarget() = runTest {
    h.selectB()
    h.sync().assertOk()
    h.engine.dropMappings()
    h.sync(full = true).assertOk()
    h.engine.removeAllCopies(h.target, stopSyncing = false)
    val foreignIds = h.foreign + h.elsewhere
    for (op in h.gateway.written) {
      when (op) {
        is CalendarOp.Insert -> Unit
        is CalendarOp.Update -> assertFalse(op.eventId in foreignIds)
        is CalendarOp.Delete -> assertFalse(op.eventId in foreignIds)
      }
    }
    h.assertForeignUntouched(includeUnmanagedMarked = false)
  }

  @Test
  fun targetUnavailableExceptionMapsToItsProblem() = runTest {
    h.gateway.onApply = { throw TargetUnavailableException(TargetCheck.READ_ONLY) }
    assertEquals(SyncProblem.TARGET_UNAVAILABLE, h.sync().failure?.problem)
  }
}
