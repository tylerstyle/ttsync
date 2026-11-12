package de.tergelius.ttsync.mapping

/**
 * The marker that identifies the app's own rows in the target calendar: the last line of
 * `DESCRIPTION` is exactly `[ttsync:<uuid>]` (DESIGN.md sections 4 and 5).
 *
 * **Safety rule.** Only rows in the target calendar whose description ends with the marker of the
 * expected uuid may ever be updated or deleted (R1). Everything else in the calendar belongs to
 * the user and is never touched. This object is the single definition of "carries our marker":
 * `EventMapper` writes it with [append], `CalendarGateway` checks it with [parse] and with the
 * [likeEndsWith] precondition, and the adoption scan finds candidates with [LIKE_ANY].
 *
 * [parse] is deliberately strict and agrees with the SQL precondition: anything [parse] accepts
 * for a uuid also matches `description LIKE '%[ttsync:<uuid>]'`. Trailing whitespace or text after
 * the marker means the row is no longer recognized as ours.
 */
object Marker {
  const val PREFIX = "[ttsync:"
  const val SUFFIX = "]"

  /** `LIKE` pattern of the adoption scan: every row that may carry a marker. Parse each hit. */
  const val LIKE_ANY = "%[ttsync:%"

  /** Characters allowed in a uuid inside a marker. */
  private val UUID = Regex("[A-Za-z0-9_-]{1,64}")
  private val MARKER_LINE = Regex("""\[ttsync:([A-Za-z0-9_-]{1,64})]""")

  fun isValidUuid(uuid: String): Boolean = UUID.matches(uuid)

  /** The marker line for [uuid]. */
  fun line(uuid: String): String {
    require(isValidUuid(uuid)) { "uuid not usable in a marker (length=${uuid.length})" }
    return PREFIX + uuid + SUFFIX
  }

  /** `LIKE` precondition for one row (R2): the description ends with the marker of [uuid]. */
  fun likeEndsWith(uuid: String): String = "%" + line(uuid)

  /**
   * Builds a description: [body] without trailing whitespace, a newline, then the marker line. A
   * null or blank body gives just the marker line. A fake marker inside the body is harmless,
   * because only the last line counts.
   */
  fun append(body: String?, uuid: String): String {
    val marker = line(uuid)
    val trimmed = body?.trimEnd()
    return if (trimmed.isNullOrEmpty()) marker else trimmed + "\n" + marker
  }

  /**
   * The uuid of the marker on the last line of [description], or null if the last line is not
   * exactly a marker. Lines may end with `\n` or `\r\n`; nothing may follow the marker.
   */
  fun parse(description: String?): String? {
    if (description == null || !description.endsWith(SUFFIX)) return null
    val lastLine = description.substring(description.lastIndexOf('\n') + 1)
    return MARKER_LINE.matchEntire(lastLine)?.groupValues?.get(1)
  }
}
