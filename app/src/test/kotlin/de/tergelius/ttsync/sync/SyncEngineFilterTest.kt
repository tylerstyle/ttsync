package de.tergelius.ttsync.sync

import de.tergelius.ttsync.calendar.TargetCalendar
import de.tergelius.ttsync.mapping.EventFilter
import de.tergelius.ttsync.store.CalendarState
import de.tergelius.ttsync.sync.EngineHarness.Companion.A1
import de.tergelius.ttsync.sync.EngineHarness.Companion.A2
import de.tergelius.ttsync.sync.EngineHarness.Companion.A3
import de.tergelius.ttsync.sync.EngineHarness.Companion.B1
import de.tergelius.ttsync.sync.EngineHarness.Companion.CAL_A
import de.tergelius.ttsync.sync.EngineHarness.Companion.CAL_B
import de.tergelius.ttsync.sync.EngineHarness.Companion.OLD
import de.tergelius.ttsync.sync.EngineHarness.Companion.SERIES
import de.tergelius.ttsync.sync.EngineHarness.Companion.TARGET
import de.tergelius.ttsync.timetree.TtLabel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Filters in the engine (0.2.0): a changed filter forces one FULL run, its fingerprint is stored
 * only after the calendar's writes succeeded, leftovers of excluded events are cleaned up, and
 * nothing else is ever touched.
 */
class SyncEngineFilterTest {
  private val h = EngineHarness()

  /** Calendar A's titles: A1 "Breakfast", A2 "Lunch", A3 "Dinner", SERIES "Weekly". */
  private val noMeals = EventFilter(excludeTerms = listOf("lunch", "DINNER"))

  /** No event has label 999: excludes every event. */
  private val nothing = EventFilter(labelIds = setOf(999))

  private fun SyncReport.assertOk(): SyncReport {
    assertNull("failure: $failure", failure)
    return this
  }

  private fun SyncReport.mode(): SyncMode? = calendars.single { it.ttCalId == CAL_A }.mode

  private fun fullReasons(): List<String> =
    h.log.lines.filter { it.startsWith("I calendar 1: FULL (") }.map { it.substringAfter("FULL (").removeSuffix(")") }

  @Test
  fun narrowingRunsFullOnceAndDeletesExactlyTheExcludedCopies() = runTest {
    h.client.edit(CAL_A, A1) { it.copy(labelId = 1) }
    h.client.edit(CAL_A, SERIES) { it.copy(labelId = 1) }
    h.sync().assertOk()
    assertEquals(SyncMode.INCREMENTAL, h.sync().assertOk().mode())
    val kept = h.copies().filterKeys { it in setOf(A1, SERIES) }

    val filter = EventFilter(labelIds = setOf(1))
    val impact = h.engine.filterImpact(CAL_A, filter)
    assertEquals(2, impact)
    h.setFilter(CAL_A, filter)
    val report = h.sync().assertOk()

    assertEquals(SyncMode.FULL, report.mode())
    assertEquals("filter changed", fullReasons().last())
    assertEquals(impact, report.counts.deleted)
    assertEquals(0, report.counts.added + report.counts.updated)
    assertEquals(2, report.counts.filtered)
    // Only the unknown and the out-of-window row are left alone; the deleted copies were the diff's.
    assertEquals(2, report.counts.unmanagedMarked)
    assertEquals(kept, h.copies()) // the kept copies are untouched rows
    assertEquals(setOf(A1, SERIES), h.store.mappings.all().map { it.uuid }.toSet())
    assertEquals(filter.fingerprint(), h.store.cursors.get(CAL_A)!!.filterHash)
    h.assertForeignUntouched()

    // Committed: the next run is INCREMENTAL again and writes nothing.
    val next = h.sync().assertOk()
    assertEquals(SyncMode.INCREMENTAL, next.mode())
    assertEquals(0, next.counts.writes)
    assertEquals(2, next.counts.filtered)
  }

  @Test
  fun incrementalEditsAcrossAnUnchangedFilterDeleteAndInsertCopies() = runTest {
    h.client.edit(CAL_A, A1) { it.copy(labelId = 1) }
    h.client.edit(CAL_A, SERIES) { it.copy(labelId = 1) }
    val filter = EventFilter(labelIds = setOf(1))
    h.setFilter(CAL_A, filter)
    h.sync().assertOk()
    assertEquals(setOf(A1, SERIES), h.copies().keys)
    val series = h.copies().getValue(SERIES)

    // In TimeTree, A1 loses the label and A2 gets it; the filter stays as it is.
    h.client.edit(CAL_A, A1) { it.copy(labelId = 2) }
    h.client.edit(CAL_A, A2) { it.copy(labelId = 1) }
    val report = h.sync().assertOk()

    assertEquals(SyncMode.INCREMENTAL, report.mode())
    assertEquals(1, report.counts.deleted)
    assertEquals(1, report.counts.added)
    assertEquals(0, report.counts.updated)
    assertEquals(2, report.counts.filtered) // A1 and A3
    assertEquals(setOf(A2, SERIES), h.copies().keys)
    assertEquals(series, h.copies().getValue(SERIES))
    assertNull(h.store.mappings.get(A1))
    assertEquals(h.copies().getValue(A2).id, h.store.mappings.get(A2)?.eventId)
    assertEquals(filter.fingerprint(), h.store.cursors.get(CAL_A)!!.filterHash)
    h.assertForeignUntouched()
  }

  @Test
  fun wideningCopiesTheEventsAgain() = runTest {
    h.setFilter(CAL_A, noMeals)
    h.sync().assertOk()
    assertEquals(setOf(A1, SERIES), h.copies().keys)
    assertEquals(SyncMode.INCREMENTAL, h.sync().assertOk().mode())

    h.setFilter(CAL_A, null)
    val report = h.sync().assertOk()
    assertEquals(SyncMode.FULL, report.mode())
    assertEquals("filter changed", fullReasons().last())
    assertEquals(2, report.counts.added)
    assertEquals(0, report.counts.deleted)
    assertEquals(h.liveA, h.copies().keys)
    assertNull(h.store.cursors.get(CAL_A)!!.filterHash)
    h.assertForeignUntouched()
  }

  @Test
  fun anEquivalentFilterForcesNoFullRun() = runTest {
    h.setFilter(CAL_A, noMeals)
    h.sync().assertOk()
    h.setFilter(CAL_A, EventFilter(excludeTerms = listOf(" DINNER", "lunch", "")))
    assertEquals(SyncMode.INCREMENTAL, h.sync().assertOk().mode())
    // An empty filter is no filter: the same as never having set one.
    h.setFilter(CAL_A, null)
    h.sync().assertOk()
    h.setFilter(CAL_A, EventFilter(includeTerms = listOf(" ")))
    assertEquals(SyncMode.INCREMENTAL, h.sync().assertOk().mode())
  }

  @Test
  fun aFailedRunKeepsTheOldHashAndTheNextRunIsFullAgain() = runTest {
    h.sync().assertOk()
    h.setFilter(CAL_A, noMeals)
    h.gateway.onApply = { throw RuntimeException("provider failure") }
    val failed = h.sync()
    assertNotNull(failed.failure)
    assertNull(h.store.cursors.get(CAL_A)!!.filterHash)
    assertEquals(h.liveA, h.copies().keys)

    h.gateway.onApply = null
    val retry = h.sync().assertOk()
    assertEquals(SyncMode.FULL, retry.mode())
    assertEquals(listOf("filter changed", "filter changed"), fullReasons().takeLast(2))
    assertEquals(2, retry.counts.deleted)
    assertEquals(setOf(A1, SERIES), h.copies().keys)
    assertEquals(noMeals.fingerprint(), h.store.cursors.get(CAL_A)!!.filterHash)
    h.assertForeignUntouched()
  }

  @Test
  fun eachCalendarCommitsItsHashOnlyAfterItsOwnWrites() = runTest {
    h.selectB()
    h.sync().assertOk()
    h.setFilter(CAL_A, noMeals)
    h.setFilter(CAL_B, nothing)
    // Calendar A is applied first and succeeds; B's deletes fail.
    h.gateway.onApply = { ops -> if (ops.any { it.uuid.startsWith("bbbb") }) throw RuntimeException("provider failure") }
    assertNotNull(h.sync().failure)
    assertEquals(noMeals.fingerprint(), h.store.cursors.get(CAL_A)!!.filterHash)
    assertNull(h.store.cursors.get(CAL_B)!!.filterHash)
    assertEquals(h.liveB, h.copies().keys.filter { it.startsWith("bbbb") }.toSet())

    h.gateway.onApply = null
    val retry = h.sync().assertOk()
    assertEquals(SyncMode.FULL, retry.calendars.single { it.ttCalId == CAL_B }.mode)
    assertEquals(nothing.fingerprint(), h.store.cursors.get(CAL_B)!!.filterHash)
    assertEquals(setOf(A1, SERIES), h.copies().keys)
    h.assertForeignUntouched()
  }

  // Leftover cleanup (adoption scan) -------------------------------------------------------------

  @Test
  fun leftoversOfExcludedEventsAreDeletedWhenTheMappingsAreLost() = runTest {
    h.sync().assertOk()
    val before = h.copies()
    h.engine.dropMappings()
    h.setFilter(CAL_A, noMeals)
    val report = h.sync().assertOk()

    assertEquals(SyncMode.FULL, report.mode())
    assertEquals(2, report.counts.deleted) // A2 and A3, unmapped: only the scan can see them
    assertEquals(2, report.counts.adopted) // A1 and SERIES, the same rows as before
    assertEquals(before.filterKeys { it in setOf(A1, SERIES) }.mapValues { it.value.id }, h.copies().mapValues { it.value.id })
    assertEquals(2, report.counts.unmanagedMarked) // the unknown and the out-of-window row stay
    h.assertForeignUntouched()
  }

  @Test
  fun aFreshInstallWithAFilterRemovesTheOldCopiesOfExcludedEvents() = runTest {
    h.sync().assertOk()
    // Reinstall: the app's data is gone, the copies are still in the calendar.
    h.db.wipe()
    h.setFilter(CAL_A, noMeals)
    val report = h.sync().assertOk()
    assertEquals(SyncMode.FULL, report.mode())
    assertEquals(2, report.counts.deleted)
    assertEquals(0, report.counts.added)
    assertEquals(setOf(A1, SERIES), h.copies().keys)
    h.assertForeignUntouched()
  }

  @Test
  fun anExtraRowOfAMappedExcludedEventGoesWithIt() = runTest {
    h.sync().assertOk()
    val a2 = h.copies().getValue(A2)
    val extra = h.gateway.addRow(TARGET, a2.title, a2.description) // e.g. restored by the user
    h.setFilter(CAL_A, EventFilter(excludeTerms = listOf("lunch")))
    val report = h.sync().assertOk()
    assertEquals(2, report.counts.deleted) // the mapped copy (diff) and the extra row (scan)
    assertTrue(h.gateway.rows[extra]?.deleted != false)
    assertEquals(setOf(A1, A3, SERIES), h.copies().keys)
    h.assertForeignUntouched()
  }

  @Test
  fun unknownAndOutOfWindowRowsStayEvenIfTheFilterExcludesEverything() = runTest {
    h.sync().assertOk()
    h.engine.dropMappings()
    h.setFilter(CAL_A, nothing)
    val report = h.sync().assertOk()
    assertEquals(4, report.counts.deleted)
    assertTrue(h.copies().isEmpty())
    // "By hand" (unknown uuid) and "Old copy" (event OLD, 60 days ago) are untouched.
    assertEquals(2, report.counts.unmanagedMarked)
    assertTrue(h.gateway.markedRows(TARGET).any { it.markerUuid == OLD })
    h.assertForeignUntouched()
  }

  @Test
  fun aPausedCalendarsCopiesAreNeverTouchedByItsFilter() = runTest {
    h.selectB()
    h.sync().assertOk()
    val copiesOfB = h.copies().filterKeys { it in h.liveB }
    assertEquals(h.liveB, copiesOfB.keys)
    h.client.calendars.removeAll { it.id == CAL_B } // access lost: B is paused
    h.sync().assertOk()
    assertEquals(CalendarState.PAUSED, h.store.cursors.get(CAL_B)!!.state)

    h.setFilter(CAL_B, nothing)
    assertEquals(0, h.engine.filterImpact(CAL_B, nothing))
    h.engine.dropMappings() // and the scan cannot match B's rows to mappings either
    val report = h.sync(full = true).assertOk()
    assertEquals(copiesOfB, h.copies().filterKeys { it in h.liveB })
    assertEquals(0, report.counts.deleted)
    assertNull(h.store.cursors.get(CAL_B)!!.filterHash)
    h.assertForeignUntouched()
  }

  // filterImpact ------------------------------------------------------------------------------------

  @Test
  fun filterImpactIsReadOnly() = runTest {
    h.sync().assertOk()
    val rows = h.gateway.rows.toMap()
    val batches = h.gateway.batchSizes.toList()
    val mappings = h.store.mappings.all()
    val cursors = h.store.cursors.all()
    val requests = h.client.requests.toList()
    val reports = h.status.reports.size

    assertEquals(2, h.engine.filterImpact(CAL_A, noMeals))
    assertEquals(4, h.engine.filterImpact(CAL_A, nothing))

    assertEquals(rows, h.gateway.rows.toMap())
    assertEquals(batches, h.gateway.batchSizes)
    assertEquals(mappings, h.store.mappings.all())
    assertEquals(cursors, h.store.cursors.all())
    assertEquals(requests, h.client.requests)
    assertEquals(reports, h.status.reports.size)
  }

  @Test
  fun filterImpactCountsOnlyInWindowCopiesInTheCurrentTarget() = runTest {
    h.sync().assertOk()
    assertEquals(0, h.engine.filterImpact(CAL_A, null))
    assertEquals(0, h.engine.filterImpact(CAL_A, EventFilter(includeTerms = listOf(" "))))
    assertEquals(0, h.engine.filterImpact(CAL_B, nothing)) // not synced: nothing stored
    assertEquals(0, h.engine.filterImpact(CAL_A, EventFilter(excludeTerms = listOf("nowhere"))))

    // A mapping whose last written end is before the window is never deleted, so not counted.
    val a2 = h.store.mappings.get(A2)!!
    h.store.mappings.upsert(listOf(a2.copy(endAt = 0)))
    assertEquals(1, h.engine.filterImpact(CAL_A, noMeals))
    // Nor one for another calendar than the current target.
    val a3 = h.store.mappings.get(A3)!!
    h.store.mappings.upsert(listOf(a3.copy(targetCalId = EngineHarness.OTHER)))
    assertEquals(0, h.engine.filterImpact(CAL_A, noMeals))
  }

  @Test
  fun filterImpactFromAnActiveFilterToAStricterOneAndBack() = runTest {
    h.setFilter(CAL_A, noMeals)
    h.sync().assertOk()
    // A2 and A3 are stored as snapshots but have no copies: they never count.
    assertEquals(setOf(A1, SERIES), h.copies().keys)
    assertEquals(0, h.engine.filterImpact(CAL_A, noMeals))
    val stricter = EventFilter(excludeTerms = listOf("lunch", "dinner", "weekly"))
    assertEquals(1, h.engine.filterImpact(CAL_A, stricter)) // SERIES
    assertEquals(2, h.engine.filterImpact(CAL_A, nothing)) // A1 and SERIES, not 4

    h.setFilter(CAL_A, stricter)
    assertEquals(1, h.sync().assertOk().counts.deleted)
    assertEquals(setOf(A1), h.copies().keys)

    // Back to the wider filter: nothing to delete, and the run copies SERIES again.
    assertEquals(0, h.engine.filterImpact(CAL_A, noMeals))
    h.setFilter(CAL_A, noMeals)
    val back = h.sync().assertOk()
    assertEquals(0, back.counts.deleted)
    assertEquals(1, back.counts.added)
    assertEquals(setOf(A1, SERIES), h.copies().keys)
    h.assertForeignUntouched()
  }

  @Test
  fun filterImpactWithoutATargetIsZero() = runTest {
    h.sync().assertOk()
    h.settings.value = h.settings.value.copy(target = null)
    assertEquals(0, h.engine.filterImpact(CAL_A, nothing))
    h.settings.value = h.settings.value.copy(target = TargetCalendar(EngineHarness.NEW_TARGET, EngineHarness.ACCOUNT, "com.google"))
    assertEquals(0, h.engine.filterImpact(CAL_A, nothing))
  }

  // Label rename ------------------------------------------------------------------------------------

  @Test
  fun aRenamedLabelKeepsTheFilterAndReachesTheCopiesWithTheNextFullRun() = runTest {
    // As on 2026-11-12: TimeTree keeps ids and colours, sets the names, leaves the events alone.
    h.client.labels[CAL_A] = listOf(TtLabel(3, "", color = 0x47B2F7, order = 3), TtLabel(9, "", color = 0xFDC02D, order = 9))
    h.client.edit(CAL_A, A1) { it.copy(labelId = 3) }
    h.client.edit(CAL_A, A2) { it.copy(labelId = 9) }
    h.setFilter(CAL_A, EventFilter(labelIds = setOf(3, 9)))
    h.sync().assertOk()
    assertEquals(setOf(A1, A2), h.copies().keys)
    assertTrue(h.copies().getValue(A1).description!!.contains("Label: Deep sky blue"))
    assertTrue(h.copies().getValue(A2).description!!.contains("Label: Bright orange"))
    val ids = h.copies().mapValues { it.value.id }

    h.client.labels[CAL_A] = listOf(TtLabel(3, "Work", color = 0x47B2F7, order = 1), TtLabel(9, "Sports", color = 0xFDC02D, order = 2))
    val incremental = h.sync().assertOk()
    assertEquals(SyncMode.INCREMENTAL, incremental.mode())
    assertEquals(0, incremental.counts.writes) // labels are cached until the next FULL

    h.now += DefaultSyncEngine.FULL_INTERVAL_MILLIS
    val full = h.sync().assertOk()
    assertEquals(SyncMode.FULL, full.mode())
    assertEquals(2, full.counts.updated)
    assertEquals(0, full.counts.added + full.counts.deleted)
    assertEquals(ids, h.copies().mapValues { it.value.id })
    assertTrue(h.copies().getValue(A1).description!!.contains("Label: Work"))
    assertTrue(h.copies().getValue(A2).description!!.contains("Label: Sports"))
    h.assertForeignUntouched()
  }

  @Test
  fun reportAndLogCountFilteredEventsButNoContent() = runTest {
    h.setFilter(CAL_A, EventFilter(includeTerms = listOf("Breakfast"), excludeTerms = listOf("Secret term")))
    val report = h.sync().assertOk()
    assertEquals(3, report.counts.filtered)
    assertEquals(3, report.calendars.single().counts.filtered)
    assertTrue(h.log.lines.any { it.startsWith("I run finished") && "filtered=3" in it })
    for (line in h.log.lines) assertTrue(line, "Secret" !in line && "Breakfast" !in line)
    h.selectB() // without a filter
    val both = h.sync().assertOk()
    assertEquals(3, both.counts.filtered)
    assertEquals(0, both.calendars.single { it.ttCalId == CAL_B }.counts.filtered)
    assertTrue(B1 in h.copies())
  }
}
