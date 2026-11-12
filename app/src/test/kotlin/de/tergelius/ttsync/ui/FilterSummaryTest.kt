package de.tergelius.ttsync.ui

import de.tergelius.ttsync.mapping.EventFilter
import de.tergelius.ttsync.settings.SourceCalendar
import de.tergelius.ttsync.ui.sources.FilterSummary
import de.tergelius.ttsync.ui.sources.FilterSummary.Part
import de.tergelius.ttsync.ui.sources.sourceNames
import org.junit.Assert.assertEquals
import org.junit.Test

/** The summary line of a filter ("Events: …" on Sources) and the filtered marks in lists. */
class FilterSummaryTest {
  /** Like the English plurals in `values/strings.xml`. */
  private fun english(part: Part, n: Int): String =
    when (part) {
      Part.LABELS -> if (n == 1) "$n label" else "$n labels"
      Part.PEOPLE -> if (n == 1) "$n person" else "$n people"
      Part.WORDS -> if (n == 1) "$n word" else "$n words"
      Part.SKIP_WORDS -> if (n == 1) "skips $n word" else "skips $n words"
    }

  private fun summary(filter: EventFilter?) = FilterSummary.text(filter, "All", ::english)

  @Test
  fun noFilterOrAnEmptyOneIsAll() {
    assertEquals("All", summary(null))
    assertEquals("All", summary(EventFilter()))
    assertEquals("All", summary(EventFilter(includeTerms = listOf(" ", ""))))
    assertEquals(emptyList<Pair<Part, Int>>(), FilterSummary.parts(EventFilter()))
  }

  @Test
  fun everyCriterionInOrder() {
    val filter =
      EventFilter(
        labelIds = setOf(1, 2),
        attendeeIds = setOf(7),
        includeTerms = listOf("match", "training", "camp"),
        excludeTerms = listOf("cancelled"),
      )
    assertEquals("2 labels · 1 person · 3 words · skips 1 word", summary(filter))
  }

  @Test
  fun onlyTheCriteriaThatAreSet() {
    assertEquals("1 label", summary(EventFilter(labelIds = setOf(3))))
    assertEquals("2 people", summary(EventFilter(attendeeIds = setOf(1, 2))))
    assertEquals("skips 2 words", summary(EventFilter(excludeTerms = listOf("a", "b"))))
    assertEquals(
      listOf(Part.LABELS to 1, Part.WORDS to 1),
      FilterSummary.parts(EventFilter(labelIds = setOf(3), includeTerms = listOf("x"))),
    )
  }

  @Test
  fun blankAndRepeatedTermsDontCount() {
    assertEquals("2 words", summary(EventFilter(includeTerms = listOf("a", " a ", "", "b"))))
  }

  @Test
  fun listsMarkFilteredCalendars() {
    val names =
      sourceNames(
        listOf(
          SourceCalendar(1, "Family", EventFilter(labelIds = setOf(3))),
          SourceCalendar(2, "", EventFilter()),
          SourceCalendar(3, "Football"),
        ),
        unnamed = { "Calendar $it" },
        filtered = { "$it (filtered)" },
      )
    // An empty filter copies everything, so it isn't marked.
    assertEquals("Family (filtered), Calendar 2, Football", names)
  }
}
