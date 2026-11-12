package de.tergelius.ttsync.store

/*
 * Rows of the four SyncDb tables (DESIGN.md section 2). All times are UTC epoch milliseconds.
 */

/**
 * `snapshot(uuid PK, tt_cal_id, updated_at, deactivated, json)`: the raw JSON of each live event,
 * plus tombstones of deactivated occurrences that have a `recurring_uuid` (needed by the override
 * rules). Other deleted or skipped events are not stored.
 */
data class Snapshot(
  val uuid: String,
  val ttCalId: Long,
  val updatedAt: Long,
  val deactivated: Boolean,
  /** The event exactly as TimeTree sent it; decode with `TtEvent.fromJson`. */
  val json: String,
) {
  /** Redacted: the JSON holds event content. */
  override fun toString(): String =
    "Snapshot(uuid=${uuid.take(8)}, ttCalId=$ttCalId, updatedAt=$updatedAt, deactivated=$deactivated)"
}

/**
 * `mapping(uuid PK, tt_cal_id, target_cal_id, event_id, spec_hash, end_at, written_at)`: one copy
 * the app manages. [eventId] is the provider's `Events._ID` in the target calendar [targetCalId].
 *
 * [endAt] is the last written end of the copy (for a series the end of its last instance, or
 * `Long.MAX_VALUE` if open-ended or unknown). Every window check uses it: a mapping with
 * `endAt < windowStart` is never updated or deleted.
 */
data class Mapping(
  val uuid: String,
  val ttCalId: Long,
  val targetCalId: Long,
  val eventId: Long,
  /** `SpecHash` of the last written spec, or [ADOPTED_HASH] for an adopted row. */
  val specHash: String,
  val endAt: Long,
  /**
   * When the app last wrote the row (insert, update, adoption), device clock; null if unknown
   * (mappings from schema 1). A row still `dirty` long after it is the upload signal of
   * `SyncReport.notUploaded`.
   */
  val writtenAt: Long? = null,
) {
  override fun toString(): String =
    "Mapping(uuid=${uuid.take(8)}, ttCalId=$ttCalId, targetCalId=$targetCalId, eventId=$eventId, " +
      "endAt=$endAt, writtenAt=$writtenAt)"

  companion object {
    /** Hash of an adopted row: never equal to a real hash, so the next diff updates the row once. */
    const val ADOPTED_HASH = ""
  }
}

/**
 * `cursor(tt_cal_id PK, high_water, last_full_at, state, labels_json, last_full_pages)`: per
 * TimeTree calendar.
 */
data class SyncCursor(
  val ttCalId: Long,
  /**
   * The high-water mark: the newest change (`TtEvent.changedAt`, the later of `updated_at` and
   * `deactivated_at`, TimeTree's clock) among the events of every complete fetch so far, or 0 if
   * the last FULL found none. An INCREMENTAL run reads the feed from the top until it reaches
   * events older than this minus `TimeTreeApi.FEED_OVERLAP`. Null before the first FULL, or after
   * the upgrade from schema 1, whose `since` was the server's backwards paging cursor: forces FULL.
   * The server's `since` is never stored.
   */
  val highWater: Long?,
  /** When the last FULL finished, or null if never (forces FULL). */
  val lastFullAt: Long?,
  val state: CalendarState,
  /** Cached `List<TtLabel>` as JSON (`TtJson`), or null if not fetched yet. */
  val labelsJson: String?,
  /**
   * Pages of the whole feed at the last FULL (`EventsResult.feedPages`), or null if unknown. A
   * small feed gets a FULL on every run, a larger one INCREMENTAL runs between FULLs (DESIGN.md
   * section 5).
   */
  val lastFullPages: Int? = null,
)

/** Stored by [name]; never rename the constants. */
enum class CalendarState {
  /** Synced normally. */
  ACTIVE,

  /** Access was lost: no requests, no writes, copies kept, until the user deselects it. */
  PAUSED,
}

/** Keys of the `meta(key PK, value)` table. */
object MetaKeys {
  /** Set at the start of a run and cleared at its end; still set means the last run crashed. */
  const val RUN_IN_PROGRESS = "run_in_progress"

  /** `EventMapper.VERSION` of the last complete run; a different value forces FULL. */
  const val MAPPER_VERSION = "mapper_version"
}
