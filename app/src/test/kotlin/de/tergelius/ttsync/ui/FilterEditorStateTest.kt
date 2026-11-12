package de.tergelius.ttsync.ui

import de.tergelius.ttsync.mapping.EventFilter
import de.tergelius.ttsync.timetree.TtCalendar
import de.tergelius.ttsync.timetree.TtCalendarUser
import de.tergelius.ttsync.timetree.TtLabel
import de.tergelius.ttsync.ui.sources.FilterEditorState
import de.tergelius.ttsync.ui.sources.MemberChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The filter editor's rules (DESIGN.md sections 4a and 7.2), without a screen. */
class FilterEditorStateTest {
  private val calendar =
    TtCalendar(
      id = 1,
      name = "Family",
      labels =
        listOf(
          TtLabel(id = 1, name = "", color = 0x2ECC87, order = 3),
          TtLabel(id = 3, name = "Work", color = 0x47B2F7, order = 1),
          TtLabel(id = 9, name = "Sports", color = 0xE73B3B, order = 2),
          TtLabel(id = 2, name = "", color = 0x3DC2C8, order = null),
        ),
      users =
        listOf(
          TtCalendarUser(userId = 10, name = "Alex"),
          TtCalendarUser(userId = 11, name = "Sam", deactivatedAt = 5),
          TtCalendarUser(userId = 12, name = "Kim"),
          TtCalendarUser(userId = 13, name = "Robin", deactivatedAt = 7),
        ),
    )

  @Test
  fun labelsFollowTimeTreesOrder() {
    val editor = FilterEditorState.of(calendar, filter = null)
    // order 1, 2, 3, then the label without an order
    assertEquals(listOf(3L, 9L, 1L, 2L), editor.labels.map { it.id })
    assertTrue(editor.labels.none { it.selected })
    assertTrue(editor.isEmpty)
    assertNull(editor.filter)
    assertEquals("", editor.includeText)
  }

  @Test
  fun formerMembersAreListedOnlyIfSelectedAndMarkedAsLeft() {
    val none = FilterEditorState.of(calendar, filter = null)
    assertEquals(listOf(10L, 12L), none.members.map { it.userId })
    assertTrue(none.members.none { it.left })

    val editor = FilterEditorState.of(calendar, EventFilter(attendeeIds = setOf(12, 13)))
    assertEquals(
      listOf(
        MemberChoice(10, "Alex", left = false, selected = false),
        MemberChoice(12, "Kim", left = false, selected = true),
        MemberChoice(13, "Robin", left = true, selected = true),
      ),
      editor.members,
    )
  }

  @Test
  fun selectedIdsTheCalendarNoLongerListsStayVisible() {
    // Nothing in a filter may be hidden from the user: an unknown label or member can be unticked.
    val editor =
      FilterEditorState.of(calendar, EventFilter(labelIds = setOf(3, 42), attendeeIds = setOf(99)))
    assertEquals(42L, editor.labels.last().id)
    assertTrue(editor.labels.last().selected)
    assertNull(editor.labels.last().label.name)
    assertEquals(MemberChoice(99, name = null, left = true, selected = true), editor.members.last())

    val cleaned = editor.toggleLabel(42).toggleMember(99)
    assertEquals(EventFilter(labelIds = setOf(3)), cleaned.filter)
  }

  @Test
  fun toggledChoicesAndTermsMakeTheFilter() {
    val editor =
      FilterEditorState.of(calendar, filter = null)
        .toggleLabel(3)
        .toggleLabel(9)
        .toggleLabel(9)
        .toggleMember(10)
        .withIncludeText(" training,team meeting , ,training")
        .withExcludeText("cancelled")
    assertFalse(editor.isEmpty)
    assertEquals(
      EventFilter(
        labelIds = setOf(3),
        attendeeIds = setOf(10),
        includeTerms = listOf("training", "team meeting"),
        excludeTerms = listOf("cancelled"),
      ),
      editor.filter,
    )
  }

  @Test
  fun theStoredFilterIsShownAndRoundTrips() {
    val stored =
      EventFilter(
        labelIds = setOf(9),
        attendeeIds = setOf(11),
        includeTerms = listOf("match", "training"),
        excludeTerms = listOf("away"),
      )
    val editor = FilterEditorState.of(calendar, stored)
    assertEquals(listOf(9L), editor.labels.filter { it.selected }.map { it.id })
    assertEquals("match, training", editor.includeText)
    assertEquals("away", editor.excludeText)
    assertEquals(stored, editor.filter)
  }

  @Test
  fun onlyBlankTermsMeanAllEvents() {
    val editor = FilterEditorState.of(calendar, null).withIncludeText(" , ").withExcludeText("  ")
    assertTrue(editor.isEmpty)
    assertNull(editor.filter)
  }

  @Test
  fun clearGoesBackToAllEventsButKeepsTheRows() {
    val editor =
      FilterEditorState.of(
          calendar,
          EventFilter(labelIds = setOf(3), attendeeIds = setOf(13), includeTerms = listOf("x")),
        )
        .cleared()
    assertNull(editor.filter)
    assertTrue(editor.isEmpty)
    assertEquals("", editor.includeText)
    // The former member stays visible (unticked) until the editor is opened again.
    assertEquals(MemberChoice(13, "Robin", left = true, selected = false), editor.members.last())
    val reopened = FilterEditorState.of(calendar, editor.filter)
    assertEquals(listOf(10L, 12L), reopened.members.map { it.userId })
  }

  @Test
  fun termsAreSplitAtCommas() {
    assertEquals(listOf("a b", "c"), FilterEditorState.termsOf(" a b ,c,, "))
    assertEquals(emptyList<String>(), FilterEditorState.termsOf(""))
  }

  @Test
  fun toStringKeepsNamesAndTermsOut() {
    val editor =
      FilterEditorState.of(calendar, EventFilter(attendeeIds = setOf(10)))
        .withIncludeText("secret plan")
    val text = editor.toString() + editor.members.toString()
    assertFalse(text, text.contains("Alex"))
    assertFalse(text, text.contains("secret"))
    assertFalse(text, text.contains("Family"))
  }
}
