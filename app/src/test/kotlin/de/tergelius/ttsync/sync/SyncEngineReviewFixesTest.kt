package de.tergelius.ttsync.sync

import de.tergelius.ttsync.mapping.EventFilter
import de.tergelius.ttsync.mapping.EventMapper
import de.tergelius.ttsync.mapping.EventSpec
import de.tergelius.ttsync.store.MetaKeys
import de.tergelius.ttsync.sync.EngineHarness.Companion.A1
import de.tergelius.ttsync.sync.EngineHarness.Companion.A2
import de.tergelius.ttsync.sync.EngineHarness.Companion.A3
import de.tergelius.ttsync.sync.EngineHarness.Companion.CAL_A
import de.tergelius.ttsync.sync.EngineHarness.Companion.DAY
import de.tergelius.ttsync.sync.EngineHarness.Companion.HOUR
import de.tergelius.ttsync.timetree.TtError
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The engine fixes of the code review of 2026-11-25 (F2, F8, F10, F14, F15). */
class SyncEngineReviewFixesTest {
  private val h = EngineHarness()

  private fun windowStart(): Long =
    EventMapper.windowStartMillis(Instant.ofEpochMilli(h.now).atZone(h.zone).toLocalDate(), h.zone)

  // F2: an event edited to end before the window -------------------------------------------------

  @Test
  fun f2_anEventMovedBeforeTheWindowTakesItsCopyAlong() = runTest {
    h.sync()
    h.client.edit(CAL_A, A2) { it.copy(startAt = it.startAt - 60 * DAY, endAt = it.endAt - 60 * DAY) }
    val moved = h.client.event(CAL_A, A2)

    val report = h.sync() // INCREMENTAL
    assertEquals(1, report.counts.updated)
    assertEquals(0, report.counts.deleted)
    assertEquals(moved.startAt.toString(), h.copies().getValue(A2).values[EventSpec.COL_DTSTART].toString())
    assertTrue(h.db.mappings.all().single { it.uuid == A2 }.endAt < windowStart())

    // From now on both the event and its copy are outside the window: never touched again.
    assertEquals(0, h.sync(full = true).counts.writes)
    assertEquals(h.liveA, h.copies().keys)
    h.assertForeignUntouched()
  }

  @Test
  fun f2_aSeriesEndedInThePastStopsRecurringInTheCopy() = runTest {
    // The harness mapper takes any recurrence for open-ended, so the ended series is a single event.
    h.sync()
    val series = EngineHarness.SERIES
    assertNotNull(h.copies().getValue(series).values[EventSpec.COL_RRULE])
    h.client.edit(CAL_A, series) {
      it.copy(recurrences = emptyList(), startAt = it.startAt - 90 * DAY, endAt = it.endAt - 90 * DAY)
    }

    h.now += 7 * HOUR
    val report = h.sync() // FULL by age
    assertEquals(SyncMode.FULL, report.calendars.single().mode)
    assertEquals(1, report.counts.updated)
    assertNull(h.copies().getValue(series).values[EventSpec.COL_RRULE])
    assertEquals(0, h.sync(full = true).counts.writes)
  }

  // F8: the guard against a mass delete ----------------------------------------------------------

  private val many = (0 until 12).map { "cccc%028d".format(it) }

  private suspend fun syncedWithManyEvents() {
    many.forEachIndexed { i, uuid -> h.client.put(CAL_A, h.timed(uuid, day = 1, title = "Extra $i")) }
    h.sync()
    assertEquals(h.liveA + many, h.copies().keys)
  }

  @Test
  fun f8_aSuddenMassDeleteWaitsForASecondFull() = runTest {
    syncedWithManyEvents()
    val mark = h.db.cursors.get(CAL_A)!!.highWater
    val snapshots = h.db.snapshots.forCalendar(CAL_A).map { it.uuid }.toSet()
    many.forEach { h.client.purge(CAL_A, it) }
    h.client.edit(CAL_A, A1) { it.copy(title = "Breakfast, early") }

    val held = h.sync(full = true)
    assertTrue(held.succeeded)
    assertEquals(0, held.counts.deleted)
    assertEquals("writes are not held", 1, held.counts.updated)
    assertEquals(h.liveA + many, h.copies().keys)
    assertTrue(h.log.lines.any { it.contains("12 of 16 copies to delete; held") })
    // The distrusted read replaces neither the snapshots nor the high-water mark.
    assertTrue(h.db.snapshots.forCalendar(CAL_A).map { it.uuid }.containsAll(snapshots))
    assertEquals(mark, h.db.cursors.get(CAL_A)!!.highWater)

    // A run right behind it is FULL again but too soon to confirm anything.
    val tooSoon = h.sync()
    assertEquals(SyncMode.FULL, tooSoon.calendars.single().mode)
    assertEquals(0, tooSoon.counts.deleted)

    // A later run is FULL again, whatever its request, and its agreeing result deletes.
    h.now += HOUR
    val second = h.sync()
    assertEquals(SyncMode.FULL, second.calendars.single().mode)
    assertEquals(12, second.counts.deleted)
    assertEquals(h.liveA, h.copies().keys)
    assertNull(h.db.meta.get(MetaKeys.massDeleteSeen(CAL_A)))
    assertEquals(h.liveA, h.db.snapshots.forCalendar(CAL_A).map { it.uuid }.toSet() intersect (h.liveA + many))
    assertEquals(SyncMode.INCREMENTAL, h.sync().calendars.single().mode)
    h.assertForeignUntouched()
  }

  @Test
  fun f8_aWrongAnswerThatIsGoneAtTheNextRunDeletesNothing() = runTest {
    syncedWithManyEvents()
    val events = many.map { h.client.event(CAL_A, it) }
    many.forEach { h.client.purge(CAL_A, it) } // TimeTree answers with a cut-off list once
    assertEquals(0, h.sync(full = true).counts.deleted)
    events.forEach { h.client.put(CAL_A, it) }

    h.now += HOUR
    val second = h.sync()
    assertEquals(SyncMode.FULL, second.calendars.single().mode)
    assertEquals(0, second.counts.deleted)
    assertEquals(h.liveA + many, h.copies().keys)
    assertNull(h.db.meta.get(MetaKeys.massDeleteSeen(CAL_A)))
  }

  @Test
  fun f8_anOldHoldConfirmsNothing() = runTest {
    syncedWithManyEvents()
    many.forEach { h.client.purge(CAL_A, it) }
    assertEquals(0, h.sync(full = true).counts.deleted)

    h.now += 2 * DAY // longer than MASS_DELETE_CONFIRM_MILLIS
    assertEquals(0, h.sync().counts.deleted) // held anew
    h.now += HOUR
    assertEquals(12, h.sync().counts.deleted)
  }

  @Test
  fun f8_aFewDeletesAreNotHeld() = runTest {
    syncedWithManyEvents()
    many.take(9).forEach { h.client.purge(CAL_A, it) } // fewer than MASS_DELETE_MIN
    assertEquals(9, h.sync(full = true).counts.deleted)
  }

  @Test
  fun f8_halfOfTheCopiesIsNotHeldOneMoreIs() = runTest {
    val extra = (0 until 16).map { "dddd%028d".format(it) } // 4 + 16 = 20 copies
    extra.forEachIndexed { i, uuid -> h.client.put(CAL_A, h.timed(uuid, day = 1, title = "Extra $i")) }
    h.sync()
    val events = extra.take(11).map { h.client.event(CAL_A, it) }
    extra.take(11).forEach { h.client.purge(CAL_A, it) }
    assertEquals(0, h.sync(full = true).counts.deleted) // 11 of 20: held

    h.client.put(CAL_A, events.first()) // back: 10 of 20 are missing now
    h.now += 2 * DAY // no confirmation left from the hold
    assertEquals(10, h.sync().counts.deleted)
  }

  @Test
  fun f8_theUsersOwnFilterChangeIsNotHeld() = runTest {
    syncedWithManyEvents()
    h.setFilter(CAL_A, EventFilter(labelIds = setOf(999))) // excludes all 16
    val filtered = h.sync()
    assertEquals(16, filtered.counts.deleted)
    assertTrue(h.copies().isEmpty())
    h.assertForeignUntouched()
  }

  @Test
  fun f8_unknownAttendeesKeepTheirCopiesThroughAFull() = runTest {
    // As if TimeTree changed the shape of "attendees": every event decodes with null.
    syncedWithManyEvents()
    h.client.all(CAL_A).forEach { e -> h.client.edit(CAL_A, e.uuid) { it.copy(attendees = null) } }
    h.setFilter(CAL_A, EventFilter(attendeeIds = setOf(11)))

    val report = h.sync(full = true)
    assertTrue(report.succeeded)
    assertEquals(0, report.counts.deleted)
    assertEquals(h.liveA + many, h.copies().keys)
    h.assertForeignUntouched()
  }

  // F10: a stored rate limit ---------------------------------------------------------------------

  @Test
  fun f10_aStoredRateLimitFarAheadIsIgnored() = runTest {
    h.status.value = h.status.value.copy(rateLimitedUntil = h.now + HOUR)
    assertEquals(SyncProblem.RATE_LIMITED, h.sync().failure?.problem)

    // More than the longest back-off: the clock was ahead when it was stored, or the value is broken.
    h.status.value =
      h.status.value.copy(rateLimitedUntil = h.now + TtError.RateLimited.MAX_BACKOFF.inWholeMilliseconds + 1)
    assertTrue(h.sync().succeeded)
    assertNull(h.status.value.rateLimitedUntil)

    h.status.value = h.status.value.copy(rateLimitedUntil = Long.MIN_VALUE + 5) // an overflowed sum
    assertTrue(h.sync().succeeded)
    // A negative value whose difference to now wraps around to "one minute left".
    h.status.value = h.status.value.copy(rateLimitedUntil = Long.MIN_VALUE + h.now + 60_000)
    assertTrue(h.sync().succeeded)
  }

  // F14: a FULL that proves no absence -----------------------------------------------------------

  @Test
  fun f14_aFullThatCannotProveAbsenceLeavesTheMarkForTheChangeList() = runTest {
    h.sync()
    val mark = h.db.cursors.get(CAL_A)!!.highWater
    h.client.skippedWithoutUuid = 1 // every FULL from now on skips an entry it cannot name
    h.client.deactivate(CAL_A, A2)
    h.client.edit(CAL_A, A3) { it.copy(title = "Dinner, later") } // a newer change than the tombstone

    h.now += 7 * HOUR
    val full = h.sync()
    assertEquals(SyncMode.FULL, full.calendars.single().mode)
    assertTrue("the FULL cannot delete by absence", A2 in h.copies())
    assertEquals(mark, h.db.cursors.get(CAL_A)!!.highWater)

    // The change list still holds the tombstone, and the mark did not skip it.
    val incremental = h.sync()
    assertEquals(SyncMode.INCREMENTAL, incremental.calendars.single().mode)
    assertEquals(1, incremental.counts.deleted)
    assertTrue(A2 !in h.copies())
  }

  // F15: two mappings that name the same row -----------------------------------------------------

  @Test
  fun f15_aStaleMappingOnAnotherCopysRowIsRepaired() = runTest {
    h.sync()
    val mappings = h.db.mappings.all().associateBy { it.uuid }
    val rowA1 = mappings.getValue(A1).eventId
    val rowA2 = mappings.getValue(A2).eventId
    // As after a reset of the calendar storage: A1's mapping names the row that is A2's copy.
    h.db.mappings.upsert(listOf(mappings.getValue(A1).copy(eventId = rowA2)))

    val report = h.sync(full = true)
    assertTrue(report.succeeded)
    val after = h.db.mappings.all().associateBy { it.uuid }
    assertEquals(rowA1, after.getValue(A1).eventId)
    assertEquals(rowA2, after.getValue(A2).eventId)
    assertEquals(h.liveA, h.copies().keys)
    h.assertForeignUntouched()
  }

  // F1: an edited date of a copied series (device test 2026-11-26) -------------------------------

  /** Google's row for one edited date of the copy of [uuid]: a new row with the description copied. */
  private fun userEditsOneDate(uuid: String): Long {
    val series = h.copies().getValue(uuid)
    val id = h.gateway.addRow(EngineHarness.TARGET, "Weekly, moved", series.description)
    h.gateway.rows[id] = h.gateway.rows.getValue(id).copy(originalId = series.id)
    return id
  }

  @Test
  fun f1_anEditedDateOfACopiedSeriesIsNotADuplicate() = runTest {
    h.sync()
    val series = h.copies().getValue(EngineHarness.SERIES).id
    val edited = userEditsOneDate(EngineHarness.SERIES)
    val before = h.gateway.rows.getValue(edited)

    val report = h.sync(full = true)
    assertEquals(0, report.counts.writes)
    assertEquals(before, h.gateway.rows.getValue(edited))
    assertEquals(series, h.db.mappings.all().single { it.uuid == EngineHarness.SERIES }.eventId)

    // Without mappings the series row is adopted, never the edited date, whatever their order.
    h.engine.dropMappings()
    h.sync(full = true)
    assertEquals(before, h.gateway.rows.getValue(edited))
    assertEquals(series, h.db.mappings.all().single { it.uuid == EngineHarness.SERIES }.eventId)
  }

  @Test
  fun f1_aMappingThatNamesAnEditedDateIsReplacedNotWrittenThrough() = runTest {
    h.sync()
    // As an install before this fix could have left it: the edited date was adopted, the series row deleted.
    val old = h.copies().getValue(EngineHarness.SERIES)
    h.gateway.rows[old.id] = old.copy(originalId = 1L)
    h.client.edit(CAL_A, EngineHarness.SERIES) { it.copy(title = "Renamed") }

    h.sync() // INCREMENTAL: the update is refused, the series gets a row of its own
    assertEquals(old.copy(originalId = 1L), h.gateway.rows.getValue(old.id))
    val mapped = h.db.mappings.all().single { it.uuid == EngineHarness.SERIES }.eventId
    assertTrue(mapped != old.id)
    assertEquals("Renamed", h.gateway.rows.getValue(mapped).title)
    assertNull(h.gateway.rows.getValue(mapped).originalId)
  }

  // F5: the target's `_ID` holds another calendar (device test 2026-11-26) ------------------------

  @Test
  fun f5_nothingIsWrittenWhenTheTargetIdHoldsAnotherCalendarNow() = runTest {
    h.sync()
    val rows = h.gateway.rows.toMap()
    // The phone's calendar storage was reset: `_ID` TARGET is another calendar of the same account.
    h.gateway.calendars[EngineHarness.TARGET] = EngineHarness.google(EngineHarness.TARGET, "another")
    h.client.edit(CAL_A, A1) { it.copy(title = "Changed") }

    val report = h.sync(full = true)
    assertEquals(SyncProblem.TARGET_DELETED, report.failure?.problem)
    assertEquals(rows, h.gateway.rows.toMap())
    assertEquals(h.target, h.settings.value.target)
  }

  @Test
  fun f5_aTargetStoredByAnOlderVersionGetsItsServerId() = runTest {
    val old = h.target.copy(serverId = null)
    h.settings.value = h.settings.value.copy(target = old)

    h.sync()
    assertEquals(h.target, h.settings.value.target)
    assertEquals("personal", h.settings.value.targetDisplayName)
    assertNotNull(h.target.serverId)
    assertEquals(h.liveA, h.copies().keys)
  }
}
