package de.tergelius.ttsync.mapping

import de.tergelius.ttsync.mapping.MappingFixtures.events
import de.tergelius.ttsync.mapping.MappingFixtures.labels
import de.tergelius.ttsync.mapping.MappingFixtures.map
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The label rename of 2026-11-12, on the synthetic pair in `fixtures/mapping/rename/`: TimeTree
 * kept the ids and colours, set the names (and moved the two labels to the top) and left the
 * events alone. A filter by id must copy the same events afterwards, and the `Label:` line must
 * follow the new name.
 */
class RenameFixtureTest {
  private val before = labels("rename/labels-before.json")
  private val after = labels("rename/labels-after.json")
  private val events = events("rename/events.json")
  private val filter = EventFilter(labelIds = setOf(3, 9))

  private val testA = "%032x".format(0xe1)
  private val testB = "%032x".format(0xe2)
  private val testC = "%032x".format(0xe3)
  private val testD = "%032x".format(0xe4)

  @Test
  fun fixturePairHasTheShapeOfTheRealRename() {
    assertEquals(before.keys, after.keys)
    assertEquals(before.mapValues { it.value.color }, after.mapValues { it.value.color })
    assertEquals(before.mapValues { it.value.defaultColor }, after.mapValues { it.value.defaultColor })
    assertEquals(mapOf(3L to "Work", 9L to "Sports"), after.filterValues { it.name != "" }.mapValues { it.value.name })
    assertEquals(setOf(""), before.values.map { it.name }.toSet())
    assertNotEquals(before.mapValues { it.value.order }, after.mapValues { it.value.order })
  }

  @Test
  fun filterByIdCopiesTheSameEventsAfterTheRename() {
    val was = map(events, before, filter = filter)
    val now = map(events, after, filter = filter)
    assertEquals(setOf(testA, testB), was.desired.filterValues { it is Desired.Write }.keys)
    assertEquals(setOf(testC, testD), was.filtered)
    assertEquals(was.desired.keys, now.desired.keys)
    assertEquals(was.filtered, now.filtered)
    assertEquals(
      was.desired.filterValues { it is Desired.Write }.keys,
      now.desired.filterValues { it is Desired.Write }.keys,
    )
  }

  @Test
  fun labelLineFollowsTheNewName() {
    val was = map(events, before, filter = filter)
    val now = map(events, after, filter = filter)
    assertEquals("Label: Deep sky blue\n[ttsync:$testA]", was.spec(testA).description)
    assertEquals("Label: Work\n[ttsync:$testA]", now.spec(testA).description)
    assertEquals("Label: Bright orange\n[ttsync:$testB]", was.spec(testB).description)
    assertEquals("Label: Sports\n[ttsync:$testB]", now.spec(testB).description)
    // So the renamed copies get rewritten (at the next FULL run), and only they.
    assertNotEquals(SpecHash.of(was.spec(testA)), SpecHash.of(now.spec(testA)))
    val unfilteredBefore = map(events, before)
    val unfilteredAfter = map(events, after)
    assertEquals(SpecHash.of(unfilteredBefore.spec(testC)), SpecHash.of(unfilteredAfter.spec(testC)))
    assertEquals("Label: Emerald green\n[ttsync:$testC]", unfilteredAfter.spec(testC).description)
  }

  @Test
  fun uiListsLabelsInTheirNewOrderWithTheirCurrentNames() {
    fun names(labels: Map<Long, de.tergelius.ttsync.timetree.TtLabel>) =
      labels.values.sortedBy { it.order }.map { LabelNames.display(it) { c -> c.english } }
    assertEquals("Emerald green", names(before).first())
    assertEquals(listOf("Work", "Sports", "Emerald green", "Modern cyan"), names(after).take(4))
  }
}
