package de.tergelius.ttsync.ui.sources

import de.tergelius.ttsync.mapping.EventFilter
import de.tergelius.ttsync.timetree.TtCalendar
import de.tergelius.ttsync.timetree.TtLabel

/** One label in the filter editor. [label] gives the id, the current name and the colour. */
data class LabelChoice(val label: TtLabel, val selected: Boolean) {
  val id: Long
    get() = label.id
}

/**
 * One calendar member in the filter editor. [left] marks a former member (or one TimeTree no longer
 * lists), shown only because the filter selects them.
 */
data class MemberChoice(
  val userId: Long,
  val name: String?,
  val left: Boolean,
  val selected: Boolean,
) {
  /** Redacted: names and user ids are personal data. */
  override fun toString(): String = "MemberChoice(<redacted>, left=$left, selected=$selected)"
}

/**
 * The filter editor of one TimeTree calendar (DESIGN.md sections 4a and 7.2): a draft that the
 * Sources screen keeps until the user saves there. Pure, so the rules are unit-tested:
 * - Labels in TimeTree's display [TtLabel.order]; members in TimeTree's order, former members
 *   only if the filter selects them (so they can be unticked), marked [MemberChoice.left].
 * - Ids the filter selects that the calendar no longer lists stay visible too, so nothing hidden is
 *   left in a filter.
 * - The two text fields hold comma-separated terms ([termsOf]).
 * - [filter] is what gets stored: normalized, and null when it restricts nothing.
 */
data class FilterEditorState(
  val calendarId: Long,
  val calendarName: String?,
  val labels: List<LabelChoice>,
  val members: List<MemberChoice>,
  val includeText: String,
  val excludeText: String,
) {
  /** The filter to store: normalized, or null if it restricts nothing (all events). */
  val filter: EventFilter?
    get() =
      EventFilter(
          labelIds = labels.filter { it.selected }.map { it.id }.toSet(),
          attendeeIds = members.filter { it.selected }.map { it.userId }.toSet(),
          includeTerms = termsOf(includeText),
          excludeTerms = termsOf(excludeText),
        )
        .normalized()
        .takeUnless { it.isEmpty }

  /** Nothing ticked and no terms: all events are copied. */
  val isEmpty: Boolean
    get() = filter == null

  fun toggleLabel(id: Long): FilterEditorState =
    copy(labels = labels.map { if (it.id == id) it.copy(selected = !it.selected) else it })

  fun toggleMember(userId: Long): FilterEditorState =
    copy(
      members = members.map { if (it.userId == userId) it.copy(selected = !it.selected) else it }
    )

  fun withIncludeText(text: String): FilterEditorState = copy(includeText = text)

  fun withExcludeText(text: String): FilterEditorState = copy(excludeText = text)

  /**
   * Back to all events. Rows stay where they are (a former member stays visible, unticked, until
   * the editor is opened again), so a mistaken tap can be undone.
   */
  fun cleared(): FilterEditorState =
    copy(
      labels = labels.map { it.copy(selected = false) },
      members = members.map { it.copy(selected = false) },
      includeText = "",
      excludeText = "",
    )

  /** Redacted: no names or search terms. */
  override fun toString(): String =
    "FilterEditorState(calendar=$calendarId, labels=${labels.count { it.selected }}, " +
      "members=${members.count { it.selected }}, filtered=${!isEmpty})"

  companion object {
    /** The separator of several terms in one text field. */
    const val TERM_SEPARATOR = ','

    /** Opens the editor for [calendar] with its current [filter] (null: all events). */
    fun of(calendar: TtCalendar, filter: EventFilter?): FilterEditorState {
      val f = filter?.normalized()
      val labelIds = f?.labelIds.orEmpty()
      val attendeeIds = f?.attendeeIds.orEmpty()
      val listed =
        calendar.labels
          .distinctBy { it.id }
          .sortedWith(compareBy<TtLabel, Int?>(nullsLast()) { it.order }.thenBy { it.id })
      val unlistedLabels =
        (labelIds - listed.map { it.id }.toSet()).sorted().map { TtLabel(id = it) }
      val users = calendar.users.distinctBy { it.userId }
      val current = users.filter { it.isActive }
      val former = users.filter { !it.isActive && it.userId in attendeeIds }
      val unknown = (attendeeIds - users.map { it.userId }.toSet()).sorted()
      return FilterEditorState(
        calendarId = calendar.id,
        calendarName = calendar.name?.takeIf(String::isNotBlank),
        labels = (listed + unlistedLabels).map { LabelChoice(it, selected = it.id in labelIds) },
        members =
          current.map { MemberChoice(it.userId, it.name, left = false, it.userId in attendeeIds) } +
            former.map { MemberChoice(it.userId, it.name, left = true, selected = true) } +
            unknown.map { MemberChoice(it, name = null, left = true, selected = true) },
        includeText = textOf(f?.includeTerms.orEmpty()),
        excludeText = textOf(f?.excludeTerms.orEmpty()),
      )
    }

    /** "work, team meeting ," → `["work", "team meeting"]`: split at commas, trimmed, no blanks. */
    fun termsOf(text: String): List<String> =
      text.split(TERM_SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }

    private fun textOf(terms: List<String>): String = terms.joinToString("$TERM_SEPARATOR ")
  }
}
