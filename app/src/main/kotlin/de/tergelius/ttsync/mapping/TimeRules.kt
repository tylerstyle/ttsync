package de.tergelius.ttsync.mapping

import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Time zone and date rules shared by [DefaultEventMapper], [RecurrenceNormalizer] and
 * [OverrideResolver] (DESIGN.md section 4), so all three agree on every instant and date.
 */
internal object TimeRules {
  const val DAY_MS: Long = EventSpec.DAY_MS

  /** `UTC` as a region id, so that `UTC.id` is the string `UTC` (not `Z`). */
  val UTC: ZoneId = ZoneId.of(EventSpec.UTC)

  private val knownZoneIds: Set<String> by lazy { ZoneId.getAvailableZoneIds() }

  private val UTC_DATE_TIME: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'", Locale.ROOT).withZone(ZoneOffset.UTC)
  private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd", Locale.ROOT)

  /**
   * The zone named by [id] if it is a tz database id (e.g. `Europe/Berlin`, `UTC`), else null.
   * Offsets such as `+01:00` and unknown names count as invalid, because the calendar provider
   * resolves `EVENT_TIMEZONE` by tz database id.
   */
  fun zoneOrNull(id: String?): ZoneId? {
    val trimmed = id?.trim()
    if (trimmed.isNullOrEmpty() || trimmed !in knownZoneIds) return null
    return try {
      ZoneId.of(trimmed)
    } catch (e: DateTimeException) {
      null
    }
  }

  /** [zoneOrNull], falling back to UTC. */
  fun zoneOrUtc(id: String?): ZoneId = zoneOrNull(id) ?: UTC

  /**
   * The date of an all-day timestamp: its UTC date if it is at a UTC midnight, otherwise its date
   * in [zone] (DESIGN.md section 4, "All-day dates").
   */
  fun allDayDate(epochMs: Long, zone: ZoneId): LocalDate =
    if (Math.floorMod(epochMs, DAY_MS) == 0L) LocalDate.ofEpochDay(Math.floorDiv(epochMs, DAY_MS))
    else Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()

  /** UTC midnight of [date] in epoch ms. */
  fun utcMidnight(date: LocalDate): Long = date.toEpochDay() * DAY_MS

  /** `yyyyMMdd'T'HHmmss'Z'`; milliseconds are dropped. */
  fun formatUtc(epochMs: Long): String = UTC_DATE_TIME.format(Instant.ofEpochMilli(epochMs))

  /** `yyyyMMdd`. */
  fun formatDate(date: LocalDate): String = DATE.format(date)
}
