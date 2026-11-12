package de.tergelius.ttsync.mapping

import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Turns TimeTree's iCal recurrence lines into the provider's `RRULE`, `RDATE` and `EXDATE` values
 * (DESIGN.md section 4, "Recurrences"):
 * - `RRULE`: the `RRULE:` prefix is removed and the rule is upper-cased. Only the first RRULE line
 *   is kept. `COUNT` next to `UNTIL` is dropped. A timed event's date-only `UNTIL` becomes 23:59:59
 *   in the start zone and a local `UNTIL` is read in the start zone, both written as UTC `…Z`; an
 *   all-day event's `UNTIL` becomes a date.
 * - `EXDATE`/`RDATE`: the property name and its parameters are stripped up to the first `:`. Each
 *   value is read in its `TZID` zone (the start zone without one; `Z` means UTC) and becomes UTC
 *   `yyyyMMdd'T'HHmmss'Z'` (timed) or `yyyyMMdd` (all-day). A date-only value on a timed event means
 *   that day's instance, so it gets the local start time of the series. A `PERIOD` value keeps its
 *   start. All lines of one property are merged, sorted and deduplicated.
 * - Every other line, every RRULE line after the first and every line that cannot be parsed is
 *   dropped and counted in [NormalizedRecurrence.droppedLines].
 *
 * Pure; [startZone] must already be the resolved zone ([TimeRules.zoneOrUtc]).
 */
internal class RecurrenceNormalizer {
  fun normalize(
    lines: List<String>,
    allDay: Boolean,
    startZone: ZoneId,
    dtStart: Long,
  ): NormalizedRecurrence {
    val frame = Frame(allDay, startZone, dtStart)
    var rule: Rule? = null
    val rdates = sortedMapOf<String, Long>()
    val exdates = sortedSetOf<String>()
    var dropped = 0

    for (raw in lines) {
      if (raw.isBlank()) continue
      val line = ContentLine.parse(raw)
      val ok =
        line != null &&
          when (line.name) {
            "RRULE" -> rule == null && frame.rule(line.value)?.also { rule = it } != null
            "EXDATE" -> frame.dates(line).also { exdates.addAll(it.values.keys) }.complete
            "RDATE" -> frame.dates(line).also { rdates.putAll(it.values) }.complete
            else -> false
          }
      if (!ok) dropped++
    }

    return NormalizedRecurrence(
      rrule = rule?.text,
      rdates = rdates.keys.toList(),
      exdates = exdates.toList(),
      droppedLines = dropped,
      untilMillis = rule?.untilMillis,
      lastRdateMillis = rdates.values.maxOrNull(),
    )
  }

  private class Rule(val text: String, val untilMillis: Long?)

  /** Parsed values of one EXDATE/RDATE line: value → instant (UTC ms); [complete] if none failed. */
  private class Dates(val values: Map<String, Long>, val complete: Boolean)

  private class Frame(val allDay: Boolean, val zone: ZoneId, val dtStart: Long) {
    /** Local start time of the series, for date-only values on a timed event. */
    private val localStartTime: LocalTime by lazy {
      Instant.ofEpochMilli(dtStart).atZone(zone).toLocalTime()
    }

    fun rule(value: String): Rule? {
      val parts = LinkedHashMap<String, String>()
      for (part in value.split(';')) {
        val trimmed = part.trim()
        if (trimmed.isEmpty()) continue
        val eq = trimmed.indexOf('=')
        if (eq <= 0) return null
        val key = trimmed.substring(0, eq).trim().uppercase()
        val v = trimmed.substring(eq + 1).trim().uppercase()
        if (v.isEmpty()) return null
        parts.putIfAbsent(key, v)
      }
      if ("FREQ" !in parts) return null
      var untilMillis: Long? = null
      val until = parts["UNTIL"]
      if (until != null) {
        parts.remove("COUNT")
        val stamp = Stamp.parse(until) ?: return null
        if (allDay) {
          val date = stamp.date(zone)
          parts["UNTIL"] = TimeRules.formatDate(date)
          untilMillis = TimeRules.utcMidnight(date)
        } else {
          untilMillis =
            when (stamp) {
              is Stamp.Date -> stamp.date.atTime(LAST_SECOND).atZone(zone).toInstant().toEpochMilli()
              is Stamp.Local -> stamp.dateTime.atZone(zone).toInstant().toEpochMilli()
              is Stamp.Utc -> stamp.epochMs
            }
          parts["UNTIL"] = TimeRules.formatUtc(untilMillis)
        }
      }
      val text = parts.entries.joinToString(";") { (k, v) -> "$k=$v" }
      return if (':' in text) null else Rule(text, untilMillis)
    }

    fun dates(line: ContentLine): Dates {
      val valueZone = TimeRules.zoneOrNull(line.params["TZID"]) ?: zone
      val values = LinkedHashMap<String, Long>()
      var complete = true
      for (raw in line.value.split(',')) {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) continue
        // A PERIOD value (`start/end` or `start/duration`) keeps its start.
        val stamp = Stamp.parse(trimmed.substringBefore('/'))
        if (stamp == null) {
          complete = false
          continue
        }
        if (allDay) {
          val date = stamp.date(zone)
          values[TimeRules.formatDate(date)] = TimeRules.utcMidnight(date)
        } else {
          val epochMs =
            when (stamp) {
              is Stamp.Date -> stamp.date.atTime(localStartTime).atZone(zone).toInstant().toEpochMilli()
              is Stamp.Local -> stamp.dateTime.atZone(valueZone).toInstant().toEpochMilli()
              is Stamp.Utc -> stamp.epochMs
            }
          values[TimeRules.formatUtc(epochMs)] = epochMs
        }
      }
      return Dates(values, complete && values.isNotEmpty())
    }
  }

  /** An iCal DATE or DATE-TIME value. */
  private sealed interface Stamp {
    data class Date(val date: LocalDate) : Stamp

    data class Local(val dateTime: LocalDateTime) : Stamp

    data class Utc(val epochMs: Long) : Stamp

    /**
     * The date for an all-day series: a date as is, a local time's date, and a UTC instant by the
     * all-day rule ([TimeRules.allDayDate]).
     */
    fun date(zone: ZoneId): LocalDate =
      when (this) {
        is Date -> date
        is Local -> dateTime.toLocalDate()
        is Utc -> TimeRules.allDayDate(epochMs, zone)
      }

    companion object {
      private val FORM = Regex("""(\d{4})(\d{2})(\d{2})(?:T(\d{2})(\d{2})(\d{2})(Z)?)?""")

      fun parse(text: String): Stamp? {
        val m = FORM.matchEntire(text.trim().uppercase()) ?: return null
        val g = m.groupValues
        return try {
          val date = LocalDate.of(g[1].toInt(), g[2].toInt(), g[3].toInt())
          when {
            g[4].isEmpty() -> Date(date)
            else -> {
              val local = date.atTime(g[4].toInt(), g[5].toInt(), g[6].toInt())
              if (g[7].isEmpty()) Local(local)
              else Utc(local.atZone(TimeRules.UTC).toInstant().toEpochMilli())
            }
          }
        } catch (e: DateTimeException) {
          null
        }
      }
    }
  }

  /** `NAME;PARAM=VALUE;…:VALUE`. A bare `FREQ=…` line is read as an RRULE. */
  internal class ContentLine(val name: String, val params: Map<String, String>, val value: String) {
    companion object {
      fun parse(raw: String): ContentLine? {
        val line = raw.trim()
        val colon = line.indexOf(':')
        if (colon < 0) {
          return if (line.uppercase().startsWith("FREQ=")) ContentLine("RRULE", emptyMap(), line)
          else null
        }
        val head = line.substring(0, colon).split(';')
        val params = HashMap<String, String>()
        for (param in head.drop(1)) {
          val eq = param.indexOf('=')
          if (eq <= 0) continue
          params[param.substring(0, eq).trim().uppercase()] =
            param.substring(eq + 1).trim().removeSurrounding("\"")
        }
        return ContentLine(head[0].trim().uppercase(), params, line.substring(colon + 1).trim())
      }
    }
  }

  private companion object {
    val LAST_SECOND: LocalTime = LocalTime.of(23, 59, 59)
  }
}

internal data class NormalizedRecurrence(
  /** RRULE without prefix, or null. */
  val rrule: String?,
  /** Sorted, distinct RDATE values. */
  val rdates: List<String>,
  /** Sorted, distinct EXDATE values: the parent's own, "native" ones. */
  val exdates: List<String>,
  val droppedLines: Int,
  /**
   * The RRULE's `UNTIL` in UTC ms, the latest start an instance may have (all-day: the UTC midnight
   * of the `UNTIL` date). Null without an RRULE or without `UNTIL` (open-ended or `COUNT`).
   */
  val untilMillis: Long?,
  /** The latest RDATE in UTC ms, or null. */
  val lastRdateMillis: Long?,
) {
  val rdate: String?
    get() = rdates.joinToString(",").ifEmpty { null }

  val exdate: String?
    get() = exdates.joinToString(",").ifEmpty { null }

  /** An RRULE or an RDATE makes a series; EXDATEs alone don't. */
  val isRecurring: Boolean
    get() = rrule != null || rdates.isNotEmpty()

  /**
   * The latest start any instance can have, or null if unbounded (an RRULE without `UNTIL`, i.e.
   * open-ended or `COUNT`; DESIGN.md section 4: such a series gets `endAt = Long.MAX_VALUE`).
   */
  fun lastStart(dtStart: Long): Long? {
    if (rrule != null && untilMillis == null) return null
    return maxOf(dtStart, untilMillis ?: dtStart, lastRdateMillis ?: dtStart)
  }
}
