package de.tergelius.ttsync.calendar

import de.tergelius.ttsync.mapping.EventSpec
import de.tergelius.ttsync.mapping.Marker

/**
 * The pure part of the [CalendarGateway] safety rules, shared by [ProviderCalendarGateway] and the
 * tests' `FakeCalendarGateway` so both decide "is this row ours" in exactly the same way. No
 * `android.*` here, so JVM tests cover it.
 */
object GatewayRules {
  // Equal to the CalendarContract.Events constants (this file must not import android.*).
  const val COL_ID = "_id"
  const val COL_CALENDAR_ID = "calendar_id"
  const val COL_DELETED = "deleted"

  /**
   * The R2 precondition, run as a `newAssertQuery(Events.CONTENT_URI)` with `withExpectedCount(1)`
   * right before each update or delete in the same `applyBatch`. Arguments: [preconditionArgs].
   */
  const val PRECONDITION: String =
    "$COL_ID = ? AND $COL_CALENDAR_ID = ? AND $COL_DELETED = 0 AND " +
      "${EventSpec.COL_DESCRIPTION} LIKE ? ESCAPE '\\'"

  /** Columns no app write may ever contain: sync-adapter-only, or not ours to set (R4). */
  val FORBIDDEN_COLUMNS: Set<String> =
    setOf(
      "dirty",
      "mutators",
      "_sync_id",
      "sync_data1",
      "sync_data2",
      "sync_data3",
      "sync_data4",
      "sync_data5",
      "sync_data6",
      "sync_data7",
      "sync_data8",
      "sync_data9",
      "sync_data10",
      COL_DELETED,
      "original_id",
      "original_sync_id",
      "customAppPackage",
      "customAppUri",
      "uid2445",
      "hasAlarm",
      "hasAttendeeData",
      "eventColor",
      "eventColor_index",
    )

  /**
   * R1: whether row [row] (null if there is no row with that `_ID`) may be updated or deleted as
   * the copy of [expectedUuid] in the target calendar [targetCalendarId].
   */
  fun check(row: ObservedRow?, targetCalendarId: Long, expectedUuid: String): RowCheck =
    when {
      row == null -> RowCheck.MISSING
      row.calendarId != targetCalendarId -> RowCheck.OTHER_CALENDAR
      row.deleted -> RowCheck.DELETED
      Marker.parse(row.description) != expectedUuid -> RowCheck.MARKER_MISMATCH
      else -> RowCheck.OURS
    }

  /** Arguments of [PRECONDITION] for one row. */
  fun preconditionArgs(eventId: Long, targetCalendarId: Long, uuid: String): List<String> =
    listOf(eventId.toString(), targetCalendarId.toString(), likeEndsWith(uuid))

  /**
   * `LIKE … ESCAPE '\'` pattern for "the description ends with the marker of [uuid]": the same as
   * [Marker.likeEndsWith], with `\`, `%` and `_` escaped so a `_` in the uuid matches only itself.
   */
  fun likeEndsWith(uuid: String): String = "%" + escapeLike(Marker.line(uuid))

  fun escapeLike(text: String): String =
    buildString {
      for (c in text) {
        if (c == '\\' || c == '%' || c == '_') append('\\')
        append(c)
      }
    }

  /**
   * Values of an update: exactly [EventSpec.MANAGED_COLUMNS], nulls included ("explicit
   * clearing"), typed as the provider stores them (`Long` times, `Int` all-day flag, `String`).
   */
  fun updateValues(spec: EventSpec): Map<String, Any?> {
    val values = linkedMapOf<String, Any?>()
    for ((column, value) in spec.columns()) {
      values[column] =
        when (column) {
          EventSpec.COL_DTSTART,
          EventSpec.COL_DTEND -> value?.toLong()
          EventSpec.COL_ALL_DAY -> value?.toInt()
          else -> value
        }
    }
    check(values.keys == EventSpec.MANAGED_COLUMNS.toSet()) { "spec columns differ from MANAGED_COLUMNS" }
    return values
  }

  /** Values of an insert: [updateValues] plus `calendar_id` of the verified target (R3). */
  fun insertValues(spec: EventSpec, target: TargetCalendar): Map<String, Any?> =
    updateValues(spec) + (COL_CALENDAR_ID to target.calendarId)
}

/** The columns of an `Events` row that the R1 checks read. */
data class ObservedRow(
  val eventId: Long,
  val calendarId: Long,
  val deleted: Boolean,
  val description: String?,
) {
  /** Redacted: the description is event content. */
  override fun toString(): String = "ObservedRow(eventId=$eventId, calendarId=$calendarId, deleted=$deleted)"
}
