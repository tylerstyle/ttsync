package de.tergelius.ttsync.mapping

import de.tergelius.ttsync.timetree.TtEvent
import de.tergelius.ttsync.timetree.TtLabel
import java.time.LocalDate
import java.time.ZoneId

/**
 * Turns the TimeTree events of one calendar into the desired state of their copies (DESIGN.md
 * section 4). Phase 3 implements it as [DefaultEventMapper].
 *
 * Contract:
 * - **Pure.** No IO, no clock, no `android.*`; the same input always gives the same output.
 * - **Every input uuid** ends up in exactly one place: [MappingResult.desired] as [Desired.Write]
 *   or [Desired.Absent], or not in the map at all when it is outside the window.
 * - **Absent** for birthdays (`type 1`), memos (`category 2`) and deactivated events (including
 *   override tombstones) that are inside the window.
 * - **Window.** An event whose `endAt` is before [windowStartMillis] is left out of the map, so its
 *   copy is never touched. Open-ended series and `COUNT` series (end unknown) have
 *   `endAt = Long.MAX_VALUE` and are always in the window.
 * - **Overrides.** The parent's spec gets the EXDATEs that [OverrideResolver] derives from its
 *   overrides and tombstones; [MappingResult.parentOf] tells the engine which parent a changed
 *   override affects.
 * - Every [Desired.Write.spec] satisfies the [EventSpec] invariants, so it carries the marker of
 *   its own uuid.
 */
interface EventMapper {
  fun map(input: MappingInput): MappingResult

  companion object {
    /** Part of every [SpecHash]. Bump it whenever the output for the same input changes. */
    const val VERSION: Int = 1

    /** Events that end before today minus this many days are never written or touched. */
    const val WINDOW_DAYS: Long = 30

    /**
     * Start of the window in UTC ms: the start of (today − [WINDOW_DAYS]) in [zone]. An event or a
     * mapping is in the window if its `endAt` is at or after this instant. The mapper and the
     * engine both use this function, so they agree on the boundary.
     */
    fun windowStartMillis(today: LocalDate, zone: ZoneId): Long =
      today.minusDays(WINDOW_DAYS).atStartOfDay(zone).toInstant().toEpochMilli()
  }
}

/** Input for one TimeTree calendar. */
data class MappingInput(
  /**
   * Every known event of the calendar: all stored snapshots (live events and override tombstones)
   * merged with this run's fetched events, deleted and skipped ones included. Overrides need their
   * parent and the parent needs all its overrides, so pass the whole calendar, not only the
   * changed events; the engine then diffs only the affected uuids.
   */
  val events: Collection<TtEvent>,
  /** The calendar's labels by id. A `label_id` missing here gets no label line. */
  val labels: Map<Long, TtLabel>,
  /** The device's current date. */
  val today: LocalDate,
  /** The device's time zone, used for [EventMapper.windowStartMillis]. */
  val zone: ZoneId,
)

/** What a copy should look like. */
sealed interface Desired {
  /**
   * The row must exist with exactly [spec]. [endAt] is the end of the copy in UTC ms (for a
   * series, the end of its last instance, or `Long.MAX_VALUE` if open-ended or unknown); it is
   * stored as `mapping.end_at` for later window checks.
   */
  data class Write(val spec: EventSpec, val endAt: Long) : Desired

  /** The row must not exist. */
  data object Absent : Desired
}

data class MappingResult(
  /** uuid → desired state. Uuids outside the window are missing. */
  val desired: Map<String, Desired>,
  /** Override or tombstone uuid → parent uuid, for overrides whose parent is in the input. */
  val parentOf: Map<String, String>,
  val stats: MappingStats = MappingStats(),
)

/** Counters that go into `SyncReport`. */
data class MappingStats(
  /** Birthdays and memos. */
  val skipped: Int = 0,
  /** Events left out because they ended before the window. */
  val outOfWindow: Int = 0,
  /** Recurrence lines other than RRULE, EXDATE and RDATE, which are dropped. */
  val droppedRecurrenceLines: Int = 0,
  /** Overrides and tombstones whose original instance could not be matched exactly (4, rule 3). */
  val unmatchedOverrides: Int = 0,
)

/** Phase 3 implements this. */
class DefaultEventMapper : EventMapper {
  override fun map(input: MappingInput): MappingResult = TODO("phase 3: EventMapper")
}
