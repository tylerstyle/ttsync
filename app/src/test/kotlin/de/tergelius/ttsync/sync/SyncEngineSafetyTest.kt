package de.tergelius.ttsync.sync

import de.tergelius.ttsync.calendar.CalendarOp
import de.tergelius.ttsync.mapping.EventFilter
import de.tergelius.ttsync.mapping.EventMapper
import de.tergelius.ttsync.mapping.Marker
import de.tergelius.ttsync.store.CalendarState
import de.tergelius.ttsync.sync.EngineHarness.Companion.CAL_A
import de.tergelius.ttsync.sync.EngineHarness.Companion.CAL_B
import de.tergelius.ttsync.sync.EngineHarness.Companion.DAY
import de.tergelius.ttsync.sync.EngineHarness.Companion.TARGET
import java.time.Instant
import kotlin.random.Random
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Random mixes of TimeTree changes, user edits in the calendar, lost mappings, crashes, provider
 * failures, races between the pre-read and the batch, lost access, filter changes and passing
 * time. After every step the rows the app did not create must be exactly as they were; at the end
 * two clean FULL runs leave exactly one copy per live event that its calendar's filter includes and
 * none of the events it excludes, and the second run writes nothing.
 */
class SyncEngineSafetyTest {
  @Test
  fun foreignRowsSurviveEveryRandomSequence() = runTest {
    for (seed in 1..60) run(seed)
  }

  private suspend fun run(seed: Int) {
    val random = Random(seed)
    val h = EngineHarness()
    if (random.nextBoolean()) h.selectB()
    var created = 0
    fun live(cal: Long) = h.client.all(cal).filter { !it.isDeactivated }
    fun pickCopy(): Long? =
      h.gateway.markedRows(TARGET).filter { it.id !in h.unmanagedMarked }.randomOrNull(random)?.id

    h.sync()
    repeat(18) { step ->
      val cal = if (random.nextBoolean()) CAL_A else CAL_B
      val action = random.nextInt(17)
      when (action) {
        0 -> {
          val event = h.timed("r%03d%028d".format(seed, created++), day = random.nextInt(-45, 45), title = "N")
          h.client.put(cal, event.copy(labelId = listOf(null, 1L, 2L).random(random)))
        }
        1 -> live(cal).randomOrNull(random)?.let { e ->
          h.client.edit(cal, e.uuid) { it.copy(title = "E$step", startAt = it.startAt + DAY, endAt = it.endAt + DAY) }
        }
        2 -> live(cal).randomOrNull(random)?.let { h.client.deactivate(cal, it.uuid, unknownTimes = random.nextBoolean()) }
        3 -> h.client.all(cal).randomOrNull(random)?.let { h.client.purge(cal, it.uuid) }
        4 -> h.sync()
        5 -> h.sync(full = true)
        6 -> h.engine.dropMappings()
        7 -> pickCopy()?.let { h.gateway.userDelete(it) }
        8 -> pickCopy()?.let { h.userTakesOver(it, "Mine now") }
        9 -> pickCopy()?.let { id ->
          val row = h.gateway.rows.getValue(id)
          h.gateway.addRow(TARGET, row.title, row.description)
        }
        10 -> {
          var once = true
          h.gateway.afterApply = { if (once) { once = false; throw RuntimeException("process died") } }
          h.sync(full = random.nextBoolean())
          h.gateway.afterApply = null
        }
        11 -> h.now += random.nextLong(0, 10) * DAY
        12 -> h.client.calendars.removeAll { it.id == CAL_B }
        13 -> {
          var once = true
          h.gateway.betweenReadAndApply = { ops ->
            val victim = ops.firstNotNullOfOrNull { (it as? CalendarOp.Update)?.eventId ?: (it as? CalendarOp.Delete)?.eventId }
            if (once && victim != null && h.gateway.rows[victim]?.let { !it.deleted && it.markerUuid != null } == true) {
              once = false
              h.userTakesOver(victim, "Taken over during the run")
            }
          }
          h.sync(full = random.nextBoolean())
          h.gateway.betweenReadAndApply = null
        }
        14 -> h.addForeign(TARGET, "User $step", listOf(null, "Plain", "Mentions [ttsync:x] here", "[ttsync:abc] tail").random(random))
        15 -> {
          h.gateway.onApply = { throw RuntimeException("provider failure") }
          h.sync()
          h.gateway.onApply = null
        }
        16 -> {
          val selected = h.settings.value.sources.map { it.id }
          val filter =
            listOf(
                null,
                EventFilter(labelIds = setOf(1)),
                EventFilter(labelIds = setOf(2)),
                EventFilter(excludeTerms = listOf("e")), // edited titles are "E<step>"
                EventFilter(includeTerms = listOf("n")),
              )
              .random(random)
          h.setFilter(selected.random(random), filter)
        }
      }
      try {
        h.assertForeignUntouched()
      } catch (e: AssertionError) {
        throw AssertionError("seed $seed, step $step, action $action: ${e.message}", e)
      }
    }

    // Two clean FULL runs: one copy per live in-window event of every active calendar; then a no-op.
    val first = h.sync(full = true)
    assertNull("seed $seed: ${first.failure}", first.failure)
    val second = h.sync(full = true)
    assertEquals("seed $seed: second FULL wrote", 0, second.counts.writes)
    h.assertForeignUntouched()

    val today = Instant.ofEpochMilli(h.now).atZone(h.zone).toLocalDate()
    val windowStart = EventMapper.windowStartMillis(today, h.zone)
    val filters = h.settings.value.sources.associate { it.id to it.activeFilter }
    val active = filters.keys.filter { h.store.cursors.get(it)?.state == CalendarState.ACTIVE }
    val (expected, excluded) =
      active
        .flatMap { cal -> h.client.all(cal).map { cal to it } }
        .filter { (_, e) -> !e.isDeactivated && !e.isBirthday && !e.isMemo }
        .filter { (_, e) -> e.recurrences.isNotEmpty() || maxOf(e.endAt, e.startAt) >= windowStart }
        .partition { (cal, e) -> filters[cal]?.matches(e) != false }
        .let { (inc, exc) -> inc.map { it.second.uuid }.toSet() to exc.map { it.second.uuid }.toSet() }
    val marked = h.gateway.markedRows(TARGET).groupBy { Marker.parse(it.description)!! }
    for (uuid in expected) {
      assertEquals("seed $seed: copies of ${uuid.take(8)}", 1, marked[uuid]?.size ?: 0)
      assertEquals("seed $seed: mapping of ${uuid.take(8)}", marked.getValue(uuid).single().id, h.store.mappings.get(uuid)?.eventId)
    }
    for (uuid in excluded) {
      assertEquals("seed $seed: copies of excluded ${uuid.take(8)}", 0, marked[uuid]?.size ?: 0)
      assertNull("seed $seed: mapping of excluded ${uuid.take(8)}", h.store.mappings.get(uuid))
    }
  }
}
