package de.tergelius.ttsync.timetree

import de.tergelius.ttsync.TtLog
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * The entries of one read of an event list ([EventFeed], [ChangeFeed]), page by page:
 * - A uuid that occurs more than once keeps its newest occurrence (by [TtEvent.changedAt]; on a tie
 *   the later one).
 * - An event that does not decode is left out and reported. One without a readable `updated_at`
 *   counts as newer than any decoded occurrence, so its uuid stays skipped (state unknown) rather
 *   than falling back to an older version.
 * - A malformed entry seen twice (a page read again) is logged and, without a uuid, counted once
 *   (review fix1, NIT-1).
 */
internal class FeedEntries(private val calendarId: Long, private val log: TtLog) {
  private val byUuid = LinkedHashMap<String, FetchedEvent>()

  /** The [TtEvent.changedAt] of the occurrence each uuid keeps, good or malformed. */
  private val versions = HashMap<String, Long>()
  private val skippedUuids = LinkedHashSet<String>()
  private var skippedWithoutUuid = 0
  private val malformedSeen = HashSet<JsonObject>()

  /** Decoded events kept so far. */
  val size: Int
    get() = byUuid.size

  /** Entries left out so far because they did not decode. */
  val skipped: Int
    get() = skippedUuids.size + skippedWithoutUuid

  /** Adds one page entry; returns its `updated_at` if readable (for ordering and stopping). */
  fun add(json: JsonObject): Long? {
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
    val firstSeen = malformedSeen.add(json)
    if (firstSeen) {
      log.w(
        "timetree: calendar $calendarId: skipped malformed event " +
          (uuid?.let(TtLog::short) ?: "without uuid")
      )
    }
    if (uuid == null) {
      if (firstSeen) skippedWithoutUuid++
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

  fun result(complete: Boolean, pages: Int, feedPages: Int): EventsResult =
    EventsResult(
      events = byUuid.values.toList(),
      complete = complete,
      pages = pages,
      feedPages = feedPages,
      skippedUuids = skippedUuids.toSet(),
      skippedMalformed = skipped,
    )

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
