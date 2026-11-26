package de.tergelius.ttsync.timetree

import de.tergelius.ttsync.TtLog
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Events that share one `updated_at` at a page boundary (review 2026-11, F7). The servers here page
 * as the real lists do, with the server's own cursor one step past the page.
 */
class FeedTiesTest {
  private val times = LinkedHashMap<String, Long>()
  private val requests = mutableListOf<Long?>()

  private fun json(uuid: String): JsonObject =
    TtJson.parseToJsonElement(eventJson(uuid, times.getValue(uuid))).jsonObject

  /** `/events/sync`: `updated_at <= since`, newest first; `since` = the page's oldest − 1. */
  private fun syncPage(since: Long?, size: Int): TtEventsPage {
    requests += since
    val rest = times.entries.sortedByDescending { it.value }.filter { since == null || it.value <= since }
    val page = rest.take(size)
    return TtEventsPage(page.map { json(it.key) }, rest.size > size, (page.lastOrNull()?.value ?: 1) - 1)
  }

  /** `/events?since=`: `updated_at >= since`, oldest first; `since` = the page's newest + 1. */
  private fun changesPage(since: Long, size: Int): TtEventsPage {
    requests += since
    val rest = times.entries.sortedBy { it.value }.filter { it.value >= since }
    val page = rest.take(size)
    return TtEventsPage(page.map { json(it.key) }, rest.size > size, page.lastOrNull()?.value?.plus(1) ?: since)
  }

  private suspend fun full(size: Int) = EventFeed(7, 50, TtLog.None) { syncPage(it, size) }.read()

  private suspend fun changes(size: Int) = ChangeFeed(7, 50, TtLog.None) { changesPage(it, size) }.read(0)

  @Test
  fun aFullReadKeepsEventsThatShareAChangeTimeAcrossAPageBoundary() = runTest {
    // "c" and "d" changed in the same millisecond, and the first page of three ends between them.
    times += listOf("a" to 400L, "b" to 350L, "c" to 300L, "d" to 300L, "e" to 200L, "f" to 100L)

    val result = full(size = 3)

    assertEquals(setOf("a", "b", "c", "d", "e", "f"), result.events.map { it.event.uuid }.toSet())
    assertTrue(result.complete)
    // The second page is asked from 300 itself, not from 299 as the server's cursor says.
    assertEquals(listOf(null, 300L), requests.take(2))
  }

  @Test
  fun aFullReadIsNotCompleteWhenAWholePageHasOneChangeTime() = runTest {
    // Four events of one millisecond and pages of three: the fourth cannot be reached.
    times += listOf("a" to 400L, "b" to 300L, "c" to 300L, "d" to 300L, "e" to 300L, "f" to 100L)

    val result = full(size = 3)

    assertFalse(result.complete) // so the engine deletes nothing for the event that is missing
    assertTrue(result.events.size < 6)
  }

  @Test
  fun aFullReadOfDistinctChangeTimesIsCompleteAndRepeatsOneEventAPage() = runTest {
    times += listOf("a" to 500L, "b" to 400L, "c" to 300L, "d" to 200L, "e" to 100L)

    val result = full(size = 2)

    assertTrue(result.complete)
    assertEquals(5, result.events.size)
    // First pass, then from the top once more.
    assertEquals(listOf(null, 400L, 300L, 200L, null), requests.take(5))
  }

  @Test
  fun pagesOfOneEventStillMoveOn() = runTest {
    times += listOf("a" to 300L, "b" to 200L, "c" to 100L)

    val result = full(size = 1)

    assertTrue(result.complete)
    assertEquals(3, result.events.size)
  }

  @Test
  fun aChangeReadKeepsEntriesThatShareAChangeTimeAcrossAPageBoundary() = runTest {
    times += listOf("a" to 100L, "b" to 150L, "c" to 200L, "d" to 200L, "e" to 300L)

    val result = changes(size = 3)!!

    assertEquals(listOf("a", "b", "c", "d", "e"), result.events.map { it.event.uuid }.sorted())
    assertEquals(listOf<Long?>(0L, 200L), requests.take(2))
  }

  @Test
  fun aChangeListWithAWholePageOfOneChangeTimeIsStillReadToItsEnd() = runTest {
    // Four entries of one millisecond and pages of three: the fourth cannot be reached, but the
    // read goes on to the newer changes instead of blocking every later run.
    times += listOf("a" to 100L, "b" to 200L, "c" to 200L, "d" to 200L, "e" to 200L, "f" to 300L)

    val result = changes(size = 3)!!

    assertTrue("f" in result.events.map { it.event.uuid })
    assertEquals(300L, result.highWater)
  }
}
