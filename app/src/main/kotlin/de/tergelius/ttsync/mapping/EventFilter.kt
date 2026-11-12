package de.tergelius.ttsync.mapping

import de.tergelius.ttsync.timetree.TtEvent
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import kotlinx.serialization.Serializable

/**
 * Which events of one TimeTree calendar are copied (0.2.0, user decisions of 2026-11-12). Stored
 * per selected calendar in the settings (`SourceCalendar.filter`); the mapper applies it.
 *
 * Rules:
 * - Three criteria: labels, attendees, text. An empty criterion restricts nothing, and a filter
 *   whose criteria are all empty ([isEmpty]) copies every event, exactly like no filter.
 * - Criteria combine with AND, the values within one criterion with OR, and a skip term always
 *   wins.
 * - **Labels by id**, never by name: users rename labels, and a rename only changes the name, so a
 *   filter survives it. An event without a label never matches a label filter.
 * - **Attendees** by user id ([TtEvent.attendees]): any one of [attendeeIds] is enough.
 * - **Text**: [includeTerms] ("only if it contains …") and [excludeTerms] ("skip if it contains
 *   …") are searched in title, note and location, case-insensitive, after Unicode NFC
 *   normalization of both sides. Terms are trimmed; blank ones are ignored.
 *
 * Every event is judged on its own fields; an edited occurrence (override) has its own label,
 * attendees and title, see [DefaultEventMapper] for the consequences.
 *
 * Search terms are personal data: [toString] leaves them out, and the sync state stores only the
 * [fingerprint].
 */
@Serializable
data class EventFilter(
  val labelIds: Set<Long> = emptySet(),
  val attendeeIds: Set<Long> = emptySet(),
  val includeTerms: List<String> = emptyList(),
  val excludeTerms: List<String> = emptyList(),
) {
  // Delegated properties are not serialized; computed once per instance.
  private val include: List<String> by lazy { terms(includeTerms) }
  private val exclude: List<String> by lazy { terms(excludeTerms) }

  /** True if the filter restricts nothing (every event matches). */
  val isEmpty: Boolean
    get() = labelIds.isEmpty() && attendeeIds.isEmpty() && include.isEmpty() && exclude.isEmpty()

  /** Whether [event] is copied. Only its own fields count (see the class rules). */
  fun matches(event: TtEvent): Boolean {
    val texts =
      if (include.isEmpty() && exclude.isEmpty()) emptyList()
      else listOfNotNull(event.title, event.note, event.location).map(::nfc)
    fun containsAny(terms: List<String>) =
      terms.any { term -> texts.any { it.contains(term, ignoreCase = true) } }

    if (containsAny(exclude)) return false
    if (labelIds.isNotEmpty() && event.labelId.let { it == null || it !in labelIds }) return false
    if (attendeeIds.isNotEmpty() && event.attendees.none { it in attendeeIds }) return false
    if (include.isNotEmpty() && !containsAny(include)) return false
    return true
  }

  /** The same filter with trimmed, NFC-normalized terms, without blank or repeated ones. */
  fun normalized(): EventFilter = copy(includeTerms = include, excludeTerms = exclude)

  /**
   * SHA-256 (64 hex characters) of a canonical form: ids sorted, terms normalized and sorted. Two
   * filters with the same fingerprint copy the same events, so the sync state stores it
   * (`cursor.filter_hash`) to notice a changed filter without keeping the terms in clear text.
   */
  fun fingerprint(): String {
    val canonical = buildString {
      append("ttsync-filter\n")
      append("version=").append(VERSION).append('\n')
      append("labels=").append(labelIds.sorted().joinToString(",")).append('\n')
      append("attendees=").append(attendeeIds.sorted().joinToString(",")).append('\n')
      for ((name, terms) in listOf("include" to include, "exclude" to exclude)) {
        // Length-prefixed, so no two different term lists give the same text.
        for (term in terms.sorted()) append(name).append('=').append(term.length).append(':').append(term).append('\n')
      }
    }
    val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(Locale.ROOT, it) }
  }

  /** Redacted: no search terms. */
  override fun toString(): String =
    "EventFilter(labels=${labelIds.size}, attendees=${attendeeIds.size}, " +
      "include=${include.size}, exclude=${exclude.size})"

  companion object {
    /**
     * Part of every [fingerprint]. Bump it when the meaning of a stored filter changes, so that
     * every filtered calendar gets one FULL run.
     */
    const val VERSION = 1

    private fun nfc(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFC)

    private fun terms(raw: List<String>): List<String> =
      raw.map { nfc(it).trim() }.filter { it.isNotEmpty() }.distinct()
  }
}
