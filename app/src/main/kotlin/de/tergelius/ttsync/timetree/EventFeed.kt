package de.tergelius.ttsync.timetree

import de.tergelius.ttsync.TtLog
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Reads one calendar's change feed, `GET /calendar/{id}/events/sync`, page by page: the paging
 * rules of [TimeTreeClient.events], without HTTP. [OkHttpTimeTreeClient] passes the request as
 * [fetchPage]; engine tests pass an in-memory server. `fetchPage(null)` asks for the top of the
 * feed, `fetchPage(since)` for the page after a returned `since`.
 *
 * How the API pages (on-device test, 2026-11-12):
 * - The feed is ordered by `updated_at`, newest change first. A request without `since` returns
 *   the newest page. Its `since` is (the page's oldest `updated_at`) − 1 and asks for the next
 *   *older* page; `chunk == false` marks the oldest one. So `since` is a cursor that pages
 *   backwards, never "changed after": `?since=<last since>` returns nothing new.
 * - Deleted events stay in the feed as tombstones (`deactivated_at` set).
 *
 * A read runs one or two passes from the top of the feed:
 * - **Full** (`stopAt == null`): every page until `chunk == false`. If that took more than one page
 *   and the feed was in order, a second pass re-reads the top down to the first pass's newest
 *   change − [TimeTreeApi.FEED_OVERLAP]: an event edited while the pages were read moves to the
 *   top, possibly out of a page not read yet, and must not look deleted.
 * - **Incremental** (`stopAt != null`): stops after the first page that holds an event with
 *   `updated_at <= stopAt`; everything newer is on that page or before it. If the feed turns out not
 *   to be ordered newest first, the early stop is not trusted and the read goes on to the end.
 *
 * Defences: more than [maxPages] requests, or a cursor that repeats within a pass, is
 * [TtError.ApiChanged]. A uuid that occurs more than once keeps its newest occurrence (by
 * [TtEvent.changedAt]; on a tie the later one). An event that does not decode is left out and
 * reported; one without a readable `updated_at` counts as newer than any decoded occurrence, so its
 * uuid stays skipped (state unknown) rather than falling back to an older version.
 */
internal class EventFeed(
  private val calendarId: Long,
  private val maxPages: Int,
  private val log: TtLog,
  private val fetchPage: suspend (since: Long?) -> TtEventsPage,
) {
  private val byUuid = LinkedHashMap<String, FetchedEvent>()

  /** The [TtEvent.changedAt] of the occurrence each uuid keeps, good or malformed. */
  private val versions = HashMap<String, Long>()
  private val skippedUuids = LinkedHashSet<String>()
  private var skippedWithoutUuid = 0
  private var pages = 0
  private var used = false

  /** One read; an instance reads once. */
  suspend fun read(stopAt: Long?): EventsResult {
    check(!used) { "an EventFeed reads once" }
    used = true
    val first = pass(stopAt)
    val feedPages = pages
    if (stopAt == null && pages > 1 && first.ordered && first.newest != null) {
      pass(first.newest - TimeTreeApi.FEED_OVERLAP.inWholeMilliseconds)
    }
    val skipped = skippedUuids.size + skippedWithoutUuid
    log.d(
      "timetree: calendar $calendarId: ${byUuid.size} events in $pages pages, $skipped skipped, " +
        "complete=${first.complete}"
    )
    return EventsResult(
      events = byUuid.values.toList(),
      complete = first.complete,
      pages = pages,
      feedPages = feedPages,
      skippedUuids = skippedUuids.toSet(),
      skippedMalformed = skipped,
    )
  }

  private class Pass(
    /** The last page (`chunk == false`) was read. */
    val complete: Boolean,
    /** Every readable `updated_at` was ≤ the one before it. */
    val ordered: Boolean,
    /** The largest readable `updated_at` of this pass. */
    val newest: Long?,
  )

  private suspend fun pass(stopAt: Long?): Pass {
    val requested = HashSet<Long>()
    var cursor: Long? = null
    var ordered = true
    var previous: Long? = null
    var newest: Long? = null
    while (true) {
      if (pages >= maxPages) throw TtError.ApiChanged("more than $maxPages event pages")
      if (cursor != null) requested += cursor
      val page = fetchPage(cursor)
      pages++
      var reachedKnown = false
      for (json in page.events) {
        val updatedAt = add(json) ?: continue
        if (previous != null && updatedAt > previous && ordered) {
          ordered = false
          log.w("timetree: calendar $calendarId: feed not ordered newest first; reading every page")
        }
        previous = updatedAt
        newest = maxOf(newest ?: updatedAt, updatedAt)
        if (stopAt != null && updatedAt <= stopAt) reachedKnown = true
      }
      if (!page.chunk) return Pass(complete = true, ordered, newest)
      if (reachedKnown && ordered) return Pass(complete = false, ordered = true, newest)
      if (page.since in requested) {
        throw TtError.ApiChanged("events cursor repeated after $pages pages")
      }
      cursor = page.since
    }
  }

  /** Adds one page entry; returns its `updated_at` if readable (for ordering and stopping). */
  private fun add(json: JsonObject): Long? {
    val event =
      try {
        TtEvent.fromJson(json)
      } catch (e: TtError.ApiChanged) {
        null
      }
    if (event != null) {
      if (keeps(event.uuid, event.changedAt)) {
        skippedUuids -= event.uuid
        byUuid.remove(event.uuid)
        byUuid[event.uuid] = FetchedEvent(event, json)
      }
      return event.updatedAt
    }
    val uuid = (json["uuid"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
    val updatedAt = json.long("updated_at")
    log.w(
      "timetree: calendar $calendarId: skipped malformed event " +
        (uuid?.let(TtLog::short) ?: "without uuid")
    )
    if (uuid == null) {
      skippedWithoutUuid++
    } else {
      val deactivatedAt = json.long("deactivated_at")
      val version =
        if (updatedAt == null) Long.MAX_VALUE else maxOf(updatedAt, deactivatedAt ?: updatedAt)
      if (keeps(uuid, version)) {
        byUuid.remove(uuid)
        skippedUuids += uuid
      }
    }
    return updatedAt
  }

  /** Records [version] for [uuid] if it is at least as new as the kept occurrence. */
  private fun keeps(uuid: String, version: Long): Boolean {
    val kept = versions[uuid]
    if (kept != null && version < kept) return false
    versions[uuid] = version
    return true
  }

  private companion object {
    fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
  }
}
