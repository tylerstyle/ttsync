package de.tergelius.ttsync.mapping

/**
 * The desired content of one copy in the target calendar (DESIGN.md section 4, column table).
 *
 * It holds every column the app manages, and nothing else. The gateway writes all of them on
 * insert and update, writing null wherever a value is null ("explicit clearing"): a single event
 * clears `DURATION`, `RRULE`, `RDATE` and `EXDATE`; a recurring event clears `DTEND`; an all-day
 * event clears `EVENT_END_TIMEZONE`. So a change between single and recurring leaves no stale field.
 * `CALENDAR_ID` is not part of the spec: the gateway sets it from the verified target on insert and
 * never changes it.
 *
 * The constructor enforces the invariants below and throws [IllegalArgumentException] if one is
 * broken, so a mapper bug can never reach the calendar provider:
 * - [description]'s last line is the marker of [uuid] (see [Marker]); every row we write is marked.
 * - Recurring ([rrule] or [rdate] set): [dtEnd] null, [duration] set.
 * - Single: [dtEnd] set and not before [dtStart]; [duration], [exdate] null.
 * - All-day: [eventTimezone] is `UTC`, [eventEndTimezone] null, [dtStart] and [dtEnd] at UTC
 *   midnights, [dtEnd] after [dtStart].
 * - [rrule], [rdate], [exdate] are bare values: no `RRULE:`/`EXDATE;TZID=…:` prefix, no `:` at all.
 *
 * Times are UTC epoch milliseconds. Field names map 1:1 to `CalendarContract.Events` columns; see
 * [columns].
 */
data class EventSpec(
  val uuid: String,
  val title: String,
  val location: String?,
  val description: String,
  val dtStart: Long,
  val dtEnd: Long?,
  val duration: String?,
  val eventTimezone: String,
  val eventEndTimezone: String?,
  val allDay: Boolean,
  val rrule: String?,
  val rdate: String?,
  val exdate: String?,
) {
  init {
    require(Marker.parse(description) == uuid) { "description must end with the marker of $shortUuid" }
    if (isRecurring) {
      require(dtEnd == null) { "recurring $shortUuid must not have DTEND" }
      require(!duration.isNullOrBlank()) { "recurring $shortUuid needs DURATION" }
    } else {
      require(dtEnd != null && dtEnd >= dtStart) { "single $shortUuid needs DTEND >= DTSTART" }
      require(duration == null) { "single $shortUuid must not have DURATION" }
      require(exdate == null) { "single $shortUuid must not have EXDATE" }
    }
    if (allDay) {
      require(eventTimezone == UTC) { "all-day $shortUuid must use UTC" }
      require(eventEndTimezone == null) { "all-day $shortUuid must not have an end time zone" }
      require(dtStart % DAY_MS == 0L) { "all-day $shortUuid must start at a UTC midnight" }
      require(dtEnd == null || (dtEnd % DAY_MS == 0L && dtEnd > dtStart)) {
        "all-day $shortUuid must end at a later UTC midnight"
      }
    }
    require(listOfNotNull(rrule, rdate, exdate).none { ':' in it }) {
      "recurrence columns of $shortUuid must not contain a prefix"
    }
  }

  val isRecurring: Boolean
    get() = rrule != null || rdate != null

  private val shortUuid: String
    get() = uuid.take(8)

  /**
   * Every managed column, keyed by its `CalendarContract.Events` column name, as strings
   * (`allDay` is `0`/`1`). Null values are part of the map, because they must be written as null.
   * [SpecHash] hashes exactly this map plus the uuid.
   */
  fun columns(): Map<String, String?> =
    sortedMapOf(
      COL_TITLE to title,
      COL_EVENT_LOCATION to location,
      COL_DESCRIPTION to description,
      COL_DTSTART to dtStart.toString(),
      COL_DTEND to dtEnd?.toString(),
      COL_DURATION to duration,
      COL_EVENT_TIMEZONE to eventTimezone,
      COL_EVENT_END_TIMEZONE to eventEndTimezone,
      COL_ALL_DAY to if (allDay) "1" else "0",
      COL_RRULE to rrule,
      COL_RDATE to rdate,
      COL_EXDATE to exdate,
    )

  /** Redacted: titles, locations and notes must never reach logs. */
  override fun toString(): String =
    "EventSpec(uuid=$shortUuid, allDay=$allDay, recurring=$isRecurring, dtStart=$dtStart)"

  companion object {
    const val UTC = "UTC"
    const val DAY_MS = 86_400_000L

    // Equal to the CalendarContract.Events constants (this package must not import android.*).
    const val COL_TITLE = "title"
    const val COL_EVENT_LOCATION = "eventLocation"
    const val COL_DESCRIPTION = "description"
    const val COL_DTSTART = "dtstart"
    const val COL_DTEND = "dtend"
    const val COL_DURATION = "duration"
    const val COL_EVENT_TIMEZONE = "eventTimezone"
    const val COL_EVENT_END_TIMEZONE = "eventEndTimezone"
    const val COL_ALL_DAY = "allDay"
    const val COL_RRULE = "rrule"
    const val COL_RDATE = "rdate"
    const val COL_EXDATE = "exdate"

    /** The columns the gateway writes on every insert and update (plus `calendar_id` on insert). */
    val MANAGED_COLUMNS: List<String> =
      listOf(
        COL_TITLE,
        COL_EVENT_LOCATION,
        COL_DESCRIPTION,
        COL_DTSTART,
        COL_DTEND,
        COL_DURATION,
        COL_EVENT_TIMEZONE,
        COL_EVENT_END_TIMEZONE,
        COL_ALL_DAY,
        COL_RRULE,
        COL_RDATE,
        COL_EXDATE,
      )
  }
}
