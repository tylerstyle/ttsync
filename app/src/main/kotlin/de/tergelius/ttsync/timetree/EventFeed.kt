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
 * - A feed of several pages that was not in order gets no second pass (its stop rule needs the
 *   order), and the read is then not `complete`: the engine deletes nothing for missing events.
 *
 * Equal change times (review 2026-11, F7). The server's cursor, oldest − 1, would skip events that
 * share the page's oldest `updated_at` but did not fit on the page. So while the server's cursor
 * follows that rule, the next page is asked from `since = oldest` instead: it starts with the
 * page's last event(s) again and brings every other event of that millisecond. If a whole page of
 * several events has one `updated_at`, the overlap cannot move on; the read then follows the
 * server's cursor and is not `complete`, since more events of that millisecond may be unread.
 * (Not checkable against the server so far: no test calendar had two events with one `updated_at`.)
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
    var tiesUnread = first.tiesUnread
    if (pages > 1 && first.ordered && first.newest != null) {
      tiesUnread = pass(first.newest - TimeTreeApi.FEED_OVERLAP.inWholeMilliseconds).tiesUnread || tiesUnread
    }
    // Several pages out of order got no second pass, so an event edited while they were read may
    // be missing: such a read proves no absence (review 2026-11, F9). The same holds if events
    // with one change time may have been cut off at a page boundary (F7).
    val complete = first.complete && (feedPages == 1 || first.ordered) && !tiesUnread
    log.d(
      "timetree: calendar $calendarId: ${entries.size} events in $pages pages, " +
        "${entries.skipped} skipped, complete=$complete"
    )
    return entries.result(complete = complete, pages = pages, feedPages = feedPages)
  }

  private class Pass(
    /** The last page (`chunk == false`) was read. */
    val complete: Boolean,
    /** Every readable `updated_at` was ≤ the one before it. */
    val ordered: Boolean,
    /** The largest readable `updated_at` of this pass. */
    val newest: Long?,
    /** A page of several events had one `updated_at` and more pages followed (F7). */
    val tiesUnread: Boolean,
  )

  /** Reads from the top: to the end ([stopAt] null), or until the first page reaching [stopAt]. */
  private suspend fun pass(stopAt: Long?): Pass {
    val requested = HashSet<Long>()
    var cursor: Long? = null
    var ordered = true
    var previous: Long? = null
    var newest: Long? = null
    var tiesUnread = false
    while (true) {
      if (pages >= maxPages) throw TtError.ApiChanged("more than $maxPages event pages")
      if (cursor != null) requested += cursor
      val page = fetchPage(cursor)
      pages++
      var reachedKnown = false
      var pageOldest: Long? = null
      var pageNewest: Long? = null
      for (json in page.events) {
        val updatedAt = entries.add(json) ?: continue
        pageOldest = minOf(pageOldest ?: updatedAt, updatedAt)
        pageNewest = maxOf(pageNewest ?: updatedAt, updatedAt)
        if (previous != null && updatedAt > previous && ordered) {
          ordered = false
          log.w("timetree: calendar $calendarId: feed not ordered newest first; no second pass")
        }
        previous = updatedAt
        newest = maxOf(newest ?: updatedAt, updatedAt)
        if (stopAt != null && updatedAt <= stopAt) reachedKnown = true
      }
      if (!page.chunk) return Pass(complete = true, ordered, newest, tiesUnread)
      if (reachedKnown && ordered) return Pass(complete = false, ordered = true, newest, tiesUnread)
      var next = page.since
      // F7: only while the server's cursor is "oldest − 1", the one rule the overlap is known for.
      if (pageOldest != null && page.since == pageOldest - 1) {
        if (pageOldest != pageNewest || cursor == null || pageOldest < cursor) {
          // Ask from the oldest change time itself, unless that is the request just made.
          if (pageOldest !in requested) next = pageOldest
        } else if (page.events.size > 1) {
          // Asked from this very millisecond and got a page full of it: there may be more.
          tiesUnread = true
          log.w("timetree: calendar $calendarId: a whole page has one change time; read not complete")
        }
      }
      if (next in requested) {
        throw TtError.ApiChanged("events cursor repeated after $pages pages")
      }
      cursor = next
    }
  }
}
