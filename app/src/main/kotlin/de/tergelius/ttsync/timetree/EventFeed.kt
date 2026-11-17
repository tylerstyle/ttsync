package de.tergelius.ttsync.timetree

import de.tergelius.ttsync.TtLog

/**
 * Reads one calendar's live events completely from `GET /calendar/{id}/events/sync`, page by page:
 * the paging rules of [TimeTreeClient.events], without HTTP. [OkHttpTimeTreeClient] passes the
 * request as [fetchPage]; engine tests pass an in-memory server. `fetchPage(null)` asks for the top
 * of the feed, `fetchPage(since)` for the page after a returned `since`.
 *
 * How the API pages (on-device tests, 2026-11-12 and 2026-11-17):
 * - The feed is ordered by `updated_at`, newest change first. A request without `since` returns
 *   the newest page. Its `since` is (the page's oldest `updated_at`) − 1 and asks for the next
 *   *older* page; `chunk == false` marks the oldest one. So `since` is a cursor that pages
 *   backwards (an exclusive upper bound), never "changed after".
 * - Deleted events are not in this feed: they vanish without a trace. Only a complete read shows a
 *   deletion, as an absence. [ChangeFeed] reads the list that keeps them.
 *
 * A read runs one or two passes from the top of the feed:
 * - The first pass reads every page until `chunk == false`.
 * - If that took more than one page and the feed was in order, a second pass re-reads the top down
 *   to the first pass's newest change − [TimeTreeApi.FEED_OVERLAP]: an event edited while the pages
 *   were read moves to the top, possibly out of a page not read yet, and must not look deleted.
 *   The second pass stops after the first page that holds an event with `updated_at` at or below
 *   that mark.
 *
 * Defences: more than [maxPages] requests, or a cursor that repeats within a pass, is
 * [TtError.ApiChanged]. Duplicate and undecodable entries: see [FeedEntries].
 */
internal class EventFeed(
  private val calendarId: Long,
  private val maxPages: Int,
  private val log: TtLog,
  private val fetchPage: suspend (since: Long?) -> TtEventsPage,
) {
  private val entries = FeedEntries(calendarId, log)
  private var pages = 0
  private var used = false

  /** One complete read; an instance reads once. */
  suspend fun read(): EventsResult {
    check(!used) { "an EventFeed reads once" }
    used = true
    val first = pass(stopAt = null)
    val feedPages = pages
    if (pages > 1 && first.ordered && first.newest != null) {
      pass(first.newest - TimeTreeApi.FEED_OVERLAP.inWholeMilliseconds)
    }
    log.d(
      "timetree: calendar $calendarId: ${entries.size} events in $pages pages, " +
        "${entries.skipped} skipped, complete=${first.complete}"
    )
    return entries.result(complete = first.complete, pages = pages, feedPages = feedPages)
  }

  private class Pass(
    /** The last page (`chunk == false`) was read. */
    val complete: Boolean,
    /** Every readable `updated_at` was ≤ the one before it. */
    val ordered: Boolean,
    /** The largest readable `updated_at` of this pass. */
    val newest: Long?,
  )

  /** Reads from the top: to the end ([stopAt] null), or until the first page reaching [stopAt]. */
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
        val updatedAt = entries.add(json) ?: continue
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
}
