package de.tergelius.ttsync.timetree

import de.tergelius.ttsync.TtLog

/**
 * Reads one calendar's changes since a point in time from `GET /calendar/{id}/events?since=N`,
 * page by page: the rules of [TimeTreeClient.changes], without HTTP. [OkHttpTimeTreeClient] passes
 * the request as [fetchPage]; engine tests pass an in-memory server.
 *
 * How the list behaves (on-device probe, 2026-11-17):
 * - It holds the calendar's live events, as the same JSON as `/events/sync`, and its deleted ones
 *   as tombstones: `deactivated_at` set, every other field kept. Tombstones were still there after
 *   5 days; how long they stay beyond that is unknown.
 * - It is ordered by `updated_at`, **oldest first**. A deletion sets `updated_at` (about 30 ms after
 *   `deactivated_at`), so a tombstone moves to the newest end like any edit. Deleting one date of a
 *   series adds an EXDATE to the series, which is an edit of the series.
 * - `since=N` returns the entries with `updated_at >= N` (inclusive). The response's `since` is the
 *   newest returned `updated_at` + 1, or N itself when nothing is newer: a forward cursor.
 * - `chunk == true` should mean that more entries follow, asked for with the returned `since`. No
 *   second page has been seen yet (the test calendar was too small), so the read checks that.
 *
 * The read is trusted only while the list behaves like that. Otherwise it logs why and returns
 * null, and the engine reads the calendar completely instead ([EventFeed]):
 * - an entry older than the `since` its page was asked for, or out of order (oldest first);
 * - `chunk == true` with a `since` that does not move forward;
 * - more than [maxPages] pages.
 *
 * Equal change times (review 2026-11, F7): the server's cursor, newest + 1, would skip entries that
 * share the page's newest `updated_at` but did not fit on the page. While the cursor follows that
 * rule, the next page is asked from `since = newest` instead, so it starts with the last entry
 * again and brings the others of that millisecond. A whole page of one `updated_at` cannot be
 * passed that way; the read then follows the server's cursor, as it did before, and logs it. (Not
 * "unusable": the FULL read cannot prove absence in that state either, and with both stuck no
 * deletion would ever arrive again; review of the fix, S2.)
 *
 * Duplicate and undecodable entries: see [FeedEntries].
 */
internal class ChangeFeed(
  private val calendarId: Long,
  private val maxPages: Int,
  private val log: TtLog,
  private val fetchPage: suspend (since: Long) -> TtEventsPage,
) {
  private val entries = FeedEntries(calendarId, log)
  private var used = false

  /** One read from [since] on; null if the list can't be trusted. An instance reads once. */
  suspend fun read(since: Long): EventsResult? {
    check(!used) { "a ChangeFeed reads once" }
    used = true
    var cursor = since
    var previous: Long? = null
    var pages = 0
    while (true) {
      if (pages >= maxPages) return unusable("more than $maxPages pages")
      val page = fetchPage(cursor)
      pages++
      var pageOldest: Long? = null
      for (json in page.events) {
        val updatedAt = entries.add(json) ?: continue
        if (updatedAt < cursor) return unusable("an entry older than since")
        if (previous != null && updatedAt < previous) return unusable("not ordered oldest first")
        if (pageOldest == null) pageOldest = updatedAt
        previous = updatedAt
      }
      if (!page.chunk) break
      if (page.since <= cursor) return unusable("since did not move forward")
      // The page is ordered, so `previous` is its newest change time.
      val pageNewest = previous.takeIf { pageOldest != null }
      cursor =
        when {
          pageNewest == null || page.since != pageNewest + 1 -> page.since
          pageNewest > cursor -> pageNewest
          // Asked from this very millisecond and got nothing newer: the overlap cannot move on.
          else -> {
            if (page.events.size > 1) {
              log.w("timetree: calendar $calendarId: a whole page of changes has one change time")
            }
            page.since
          }
        }
      // The first entry of each page repeats the one before; that is in order.
    }
    log.d(
      "timetree: calendar $calendarId: ${entries.size} changes in $pages pages, " +
        "${entries.skipped} skipped"
    )
    // Never complete: absence from a list of changes means nothing.
    return entries.result(complete = false, pages = pages, feedPages = pages)
  }

  private fun unusable(why: String): EventsResult? {
    log.w("timetree: calendar $calendarId: change list unusable ($why)")
    return null
  }
}
