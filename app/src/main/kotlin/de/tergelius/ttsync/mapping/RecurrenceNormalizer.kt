package de.tergelius.ttsync.mapping

import java.time.ZoneId

/**
 * Turns TimeTree's iCal recurrence lines into the provider's `RRULE`, `RDATE` and `EXDATE` values
 * (DESIGN.md section 4, "Recurrences"): strip prefixes and parameters, normalize `UNTIL`, drop
 * `COUNT` next to `UNTIL`, convert EXDATE/RDATE values to UTC `…Z` or `yyyyMMdd`, merge, sort and
 * deduplicate, count every other line as dropped.
 *
 * Internal helper of [DefaultEventMapper]; phase 3 owns it and may change its signature.
 */
internal class RecurrenceNormalizer {
  fun normalize(lines: List<String>, allDay: Boolean, startZone: ZoneId): NormalizedRecurrence =
    TODO("phase 3: RecurrenceNormalizer (${lines.size} lines, allDay=$allDay, $startZone)")
}

internal data class NormalizedRecurrence(
  val rrule: String?,
  val rdate: String?,
  val exdate: String?,
  val droppedLines: Int,
)
