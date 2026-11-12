package de.tergelius.ttsync.mapping

import de.tergelius.ttsync.timetree.TtEvent
import de.tergelius.ttsync.timetree.TtJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The filter rules of 0.2.0 (user decisions of 2026-11-12). */
class EventFilterTest {
  private fun event(
    title: String? = "Training",
    note: String? = null,
    location: String? = null,
    label: Long? = null,
    attendees: List<Long> = emptyList(),
  ) =
    TtEvent(
      uuid = "f1",
      title = title,
      note = note,
      location = location,
      labelId = label,
      attendees = attendees,
      allDay = false,
      startAt = 0,
      endAt = 0,
      updatedAt = 0,
    )

  @Test
  fun emptyFilterMatchesEverything() {
    val empty = EventFilter()
    assertTrue(empty.isEmpty)
    assertTrue(empty.matches(event()))
    assertTrue(empty.matches(event(title = null)))
    // Blank terms restrict nothing.
    assertTrue(EventFilter(includeTerms = listOf(" ", ""), excludeTerms = listOf("\t")).isEmpty)
    assertFalse(EventFilter(labelIds = setOf(1)).isEmpty)
  }

  @Test
  fun labelsByIdAnyOfThem() {
    val filter = EventFilter(labelIds = setOf(3, 9))
    assertTrue(filter.matches(event(label = 3)))
    assertTrue(filter.matches(event(label = 9)))
    assertFalse(filter.matches(event(label = 1)))
    // An event without a label never matches a label filter.
    assertFalse(filter.matches(event(label = null)))
  }

  @Test
  fun attendeesAnyOfThem() {
    val filter = EventFilter(attendeeIds = setOf(11, 12))
    assertTrue(filter.matches(event(attendees = listOf(12, 13))))
    assertFalse(filter.matches(event(attendees = listOf(13))))
    assertFalse(filter.matches(event(attendees = emptyList())))
  }

  @Test
  fun textIsSearchedInTitleNoteAndLocationIgnoringCase() {
    val filter = EventFilter(includeTerms = listOf("school"))
    assertTrue(filter.matches(event(title = "School trip")))
    assertTrue(filter.matches(event(title = "Trip", note = "meet at the SCHOOL")))
    assertTrue(filter.matches(event(title = null, location = "Schoolyard")))
    assertFalse(filter.matches(event(title = "Trip", note = "meet at the gym")))
    assertFalse(filter.matches(event(title = null)))
    // Either term is enough; terms are trimmed.
    assertTrue(EventFilter(includeTerms = listOf("gym", " trip ")).matches(event(title = "Trip")))
  }

  @Test
  fun textIsComparedAfterUnicodeNormalization() {
    val composed = "Café" // é as one code point
    val decomposed = "Café" // e + combining acute accent
    assertTrue(EventFilter(includeTerms = listOf(composed)).matches(event(title = "$decomposed am Markt")))
    assertTrue(EventFilter(includeTerms = listOf(decomposed)).matches(event(title = composed)))
    // Case-insensitive beyond ASCII.
    assertTrue(EventFilter(includeTerms = listOf("übung")).matches(event(title = "Übung")))
    assertTrue(EventFilter(includeTerms = listOf("ÄRZTIN")).matches(event(title = "Ärztin")))
  }

  @Test
  fun skipAlwaysWins() {
    val filter =
      EventFilter(labelIds = setOf(3), includeTerms = listOf("training"), excludeTerms = listOf("cancelled"))
    assertTrue(filter.matches(event(title = "Training", label = 3)))
    assertFalse(filter.matches(event(title = "Training cancelled", label = 3)))
    assertFalse(filter.matches(event(title = "Training", note = "Cancelled!", label = 3)))
    assertFalse(EventFilter(excludeTerms = listOf("x")).matches(event(title = null, location = "X-Ray")))
    assertTrue(EventFilter(excludeTerms = listOf("x")).matches(event(title = "Gym")))
  }

  @Test
  fun criteriaCombineWithAnd() {
    val filter = EventFilter(labelIds = setOf(3), attendeeIds = setOf(11), includeTerms = listOf("training"))
    assertTrue(filter.matches(event(title = "Training", label = 3, attendees = listOf(11))))
    assertFalse(filter.matches(event(title = "Training", label = 4, attendees = listOf(11))))
    assertFalse(filter.matches(event(title = "Training", label = 3, attendees = listOf(12))))
    assertFalse(filter.matches(event(title = "Match", label = 3, attendees = listOf(11))))
  }

  @Test
  fun fingerprintIgnoresOrderAndNormalization() {
    val a = EventFilter(setOf(3, 9), setOf(12, 11), listOf("Training", "gym"), listOf("cancelled"))
    val b = EventFilter(setOf(9, 3), setOf(11, 12), listOf(" gym", "Training", "", "gym"), listOf("cancelled "))
    assertEquals(a.fingerprint(), b.fingerprint())
    assertTrue(Regex("[0-9a-f]{64}").matches(a.fingerprint()))
    assertEquals(EventFilter(includeTerms = listOf("Café")).fingerprint(), EventFilter(includeTerms = listOf("Café")).fingerprint())
    assertEquals(a.normalized().fingerprint(), a.fingerprint())
    assertEquals(listOf("gym", "Training"), b.normalized().includeTerms)
  }

  @Test
  fun fingerprintChangesWithEveryCriterion() {
    val base = EventFilter(setOf(3), setOf(11), listOf("a"), listOf("b"))
    val variants =
      listOf(
        base,
        base.copy(labelIds = setOf(4)),
        base.copy(attendeeIds = setOf(12)),
        base.copy(includeTerms = listOf("c")),
        base.copy(excludeTerms = listOf("c")),
        // The same terms on the other side, or split differently, are other filters.
        base.copy(includeTerms = listOf("b"), excludeTerms = listOf("a")),
        base.copy(includeTerms = listOf("a", "b"), excludeTerms = emptyList()),
        base.copy(labelIds = emptySet(), attendeeIds = setOf(3, 11)),
        EventFilter(),
      )
    assertEquals(variants.size, variants.map { it.fingerprint() }.toSet().size)
    // A case change is another fingerprint too (one extra FULL run, harmless).
    assertNotEquals(EventFilter(includeTerms = listOf("A")).fingerprint(), EventFilter(includeTerms = listOf("a")).fingerprint())
  }

  @Test
  fun searchTermsStayOutOfToStringAndFingerprint() {
    val filter = EventFilter(includeTerms = listOf("Secret plan"), excludeTerms = listOf("Hidden"))
    assertFalse(filter.toString().contains("Secret") || filter.toString().contains("Hidden"))
    assertFalse(filter.fingerprint().contains("Secret"))
  }

  @Test
  fun jsonLeavesOutEmptyCriteria() {
    val filter = EventFilter(labelIds = setOf(3))
    val json = TtJson.encodeToString(EventFilter.serializer(), filter)
    assertEquals("""{"labelIds":[3]}""", json)
    assertEquals(filter, TtJson.decodeFromString(EventFilter.serializer(), json))
  }
}
