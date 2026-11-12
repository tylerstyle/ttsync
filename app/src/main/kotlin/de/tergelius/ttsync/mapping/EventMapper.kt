package de.tergelius.ttsync.mapping

import de.tergelius.ttsync.timetree.TtEvent
import de.tergelius.ttsync.timetree.TtLabel
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.SortedMap
import java.util.TreeMap

/**
 * Turns the TimeTree events of one calendar into the desired state of their copies (DESIGN.md
 * section 4). Phase 3 implements it as [DefaultEventMapper].
 *
 * Contract:
 * - **Pure.** No IO, no clock, no `android.*`; the same input always gives the same output.
 * - **Every input uuid** ends up in exactly one place: [MappingResult.desired] as [Desired.Write]
 *   or [Desired.Absent], or not in the map at all when it is outside the window (or, defensively,
 *   when the uuid cannot be put in a [Marker]; real uuids are 32 hex characters).
 * - **Absent** for birthdays (`type 1`), memos (`category 2`) and deactivated events (including
 *   override tombstones) that are inside the window.
 * - **Window.** An event whose `endAt` is before [windowStartMillis] is left out of the map, so its
 *   copy is never touched. Open-ended series and `COUNT` series (end unknown) have
 *   `endAt = Long.MAX_VALUE` and are always in the window.
 * - **Overrides** (events with `recurring_uuid`/`parent_id`) are written as standalone single
 *   events, without RRULE. The parent keeps exactly its own EXDATEs, which TimeTree sets for every
 *   moved and deleted occurrence ([OverrideResolver] has the details and the unmatched count);
 *   [MappingResult.parentOf] tells the engine which parent a changed override affects.
 * - If the same uuid appears twice in the input, the copy with the newer `updated_at` wins.
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
  /**
   * Recurrence lines of written series that were dropped: lines other than RRULE, EXDATE and
   * RDATE, RRULE lines after the first, and lines that could not be parsed.
   */
  val droppedRecurrenceLines: Int = 0,
  /**
   * Overrides and tombstones whose original instance their parent does not exclude (section 4,
   * rule 3; see [OverrideResolver]).
   */
  val unmatchedOverrides: Int = 0,
)

/**
 * The mapping of DESIGN.md section 4. Column rules:
 * - `TITLE`: the title, or `(TimeTree)` if it is blank. `EVENT_LOCATION`: the location, or null if
 *   blank.
 * - `DESCRIPTION`: the note, a blank line, then `Label: <name>` and `URL: <url>`, each only if set;
 *   the marker is always the last line.
 * - Timed: `DTSTART = start_at`, `DTEND = max(end_at, start_at)`, `EVENT_TIMEZONE` the valid
 *   `start_timezone` or `UTC`, `EVENT_END_TIMEZONE` the valid `end_timezone` if it differs, else null.
 * - All-day: start and inclusive end date by [TimeRules.allDayDate], `DTSTART`/`DTEND` at UTC
 *   midnights with an exclusive end, `EVENT_TIMEZONE = UTC`, `EVENT_END_TIMEZONE` null.
 * - Series (an RRULE or RDATE that survives [RecurrenceNormalizer]): `DTEND` null and `DURATION`
 *   `P<seconds>S` (timed) or `P<days>D` (all-day, one-day events give `P1D`); single events have
 *   null `DURATION`, `RRULE`, `RDATE` and `EXDATE`.
 */
class DefaultEventMapper : EventMapper {
  private val normalizer = RecurrenceNormalizer()
  private val resolver = OverrideResolver()

  override fun map(input: MappingInput): MappingResult {
    val windowStart = EventMapper.windowStartMillis(input.today, input.zone)
    val events = newestByUuid(input.events)

    val parentOf = LinkedHashMap<String, String>()
    val children = HashMap<String, MutableList<TtEvent>>()
    for (event in events.values) {
      val parent = parentRefs(event).firstOrNull { it in events } ?: continue
      parentOf[event.uuid] = parent
      children.getOrPut(parent) { mutableListOf() } += event
    }

    val desired = LinkedHashMap<String, Desired>()
    var skipped = 0
    var outOfWindow = 0
    var dropped = 0
    var unmatched = 0
    for (event in events.values) {
      if (!Marker.isValidUuid(event.uuid)) continue
      val shape = shapeOf(event)
      when {
        // A tombstone may carry start/end 0 ("unknown"); the window check must not drop
        // it. Whether a copy gets deleted is decided by the engine from the mapping's own
        // end_at. Tombstones with known old times stay out of the window as before.
        event.isDeactivated && event.endAt == 0L -> desired[event.uuid] = Desired.Absent
        shape.endAt < windowStart -> outOfWindow++
        event.isDeactivated -> desired[event.uuid] = Desired.Absent
        event.isBirthday || event.isMemo -> {
          skipped++
          desired[event.uuid] = Desired.Absent
        }
        else -> {
          val recurrence = shape.recurrence
          if (recurrence != null) {
            dropped += recurrence.droppedLines
            if (shape.isSeries) {
              val (tombstones, overrides) = children[event.uuid].orEmpty().partition { it.isDeactivated }
              unmatched += resolver.resolve(recurrence.exdates, overrides, tombstones).unmatched
            }
          }
          desired[event.uuid] = Desired.Write(spec(event, shape, input.labels), shape.endAt)
        }
      }
    }

    return MappingResult(
      desired = desired,
      parentOf = parentOf,
      stats =
        MappingStats(
          skipped = skipped,
          outOfWindow = outOfWindow,
          droppedRecurrenceLines = dropped,
          unmatchedOverrides = unmatched,
        ),
    )
  }

  /** Times and recurrence of one event, before any content. */
  private class Shape(
    val allDay: Boolean,
    val dtStart: Long,
    /** Exclusive end of the first (or only) instance. */
    val dtEnd: Long,
    val duration: String,
    val timezone: String,
    val endTimezone: String?,
    /** Null for overrides, which are always single events. */
    val recurrence: NormalizedRecurrence?,
  ) {
    val isSeries: Boolean
      get() = recurrence?.isRecurring == true

    /** `Desired.Write.endAt`: the end of the last possible instance, see [NormalizedRecurrence.lastStart]. */
    val endAt: Long
      get() {
        if (recurrence == null || !recurrence.isRecurring) return dtEnd
        val lastStart = recurrence.lastStart(dtStart) ?: return Long.MAX_VALUE
        val length = dtEnd - dtStart
        return if (lastStart > Long.MAX_VALUE - length) Long.MAX_VALUE else lastStart + length
      }
  }

  private fun shapeOf(event: TtEvent): Shape {
    val zone = TimeRules.zoneOrUtc(event.startTimezone)
    val dtStart: Long
    val dtEnd: Long
    val duration: String
    if (event.allDay) {
      val startDate = TimeRules.allDayDate(event.startAt, zone)
      val endDate = maxOf(TimeRules.allDayDate(event.endAt, zone), startDate) // inclusive
      dtStart = TimeRules.utcMidnight(startDate)
      dtEnd = TimeRules.utcMidnight(endDate.plusDays(1))
      duration = "P${ChronoUnit.DAYS.between(startDate, endDate) + 1}D"
    } else {
      dtStart = event.startAt
      dtEnd = maxOf(event.endAt, event.startAt)
      duration = "P${(dtEnd - dtStart) / 1000}S"
    }
    val timezone = if (event.allDay) EventSpec.UTC else zone.id
    val endTimezone =
      if (event.allDay) null
      else TimeRules.zoneOrNull(event.endTimezone)?.id?.takeIf { it != timezone }
    val recurrence =
      if (parentRefs(event).isNotEmpty()) null
      else normalizer.normalize(event.recurrences, event.allDay, zone, dtStart)
    return Shape(event.allDay, dtStart, dtEnd, duration, timezone, endTimezone, recurrence)
  }

  private fun spec(event: TtEvent, shape: Shape, labels: Map<Long, TtLabel>): EventSpec {
    val series = if (shape.isSeries) shape.recurrence else null
    return EventSpec(
      uuid = event.uuid,
      title = event.title?.takeIf { it.isNotBlank() } ?: UNTITLED,
      location = event.location?.takeIf { it.isNotBlank() },
      description = description(event, labels),
      dtStart = shape.dtStart,
      dtEnd = if (series == null) shape.dtEnd else null,
      duration = if (series == null) null else shape.duration,
      eventTimezone = shape.timezone,
      eventEndTimezone = shape.endTimezone,
      allDay = shape.allDay,
      rrule = series?.rrule,
      rdate = series?.rdate,
      exdate = series?.exdate,
    )
  }

  private fun description(event: TtEvent, labels: Map<Long, TtLabel>): String {
    val note = event.note?.takeIf { it.isNotBlank() }?.trimEnd()
    val footer =
      listOfNotNull(
        event.labelId?.let { labels[it]?.name }?.let(::oneLine)?.let { "Label: $it" },
        event.url?.let(::oneLine)?.let { "URL: $it" },
      )
    val body = listOfNotNull(note, footer.joinToString("\n").ifEmpty { null }).joinToString("\n\n")
    return Marker.append(body, event.uuid)
  }

  private companion object {
    const val UNTITLED = "(TimeTree)"
    val LINE_BREAKS = Regex("""\s*[\r\n]+\s*""")

    fun oneLine(text: String): String? = text.trim().replace(LINE_BREAKS, " ").ifEmpty { null }

    /** The uuids an override points at (`recurring_uuid` first), never its own. */
    fun parentRefs(event: TtEvent): List<String> =
      listOfNotNull(event.recurringUuid, event.parentId)
        .map { it.trim() }
        .filter { it.isNotEmpty() && it != event.uuid }
        .distinct()

    /** One event per uuid, the newest by `updated_at` (deactivated wins a tie), sorted by uuid. */
    fun newestByUuid(events: Collection<TtEvent>): SortedMap<String, TtEvent> {
      val newest = TreeMap<String, TtEvent>()
      val order = compareBy<TtEvent>({ it.updatedAt }, { it.isDeactivated })
      for (event in events) {
        val current = newest[event.uuid]
        if (current == null || order.compare(event, current) > 0) newest[event.uuid] = event
      }
      return newest
    }
  }
}
