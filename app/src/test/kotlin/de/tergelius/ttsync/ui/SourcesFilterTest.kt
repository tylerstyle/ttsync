package de.tergelius.ttsync.ui

import de.tergelius.ttsync.mapping.EventFilter
import de.tergelius.ttsync.settings.Settings
import de.tergelius.ttsync.settings.SetupStep
import de.tergelius.ttsync.settings.SourceCalendar
import de.tergelius.ttsync.timetree.TtCalendar
import de.tergelius.ttsync.timetree.TtCalendarUser
import de.tergelius.ttsync.timetree.TtLabel
import de.tergelius.ttsync.ui.UiTestGraph.Companion.SIGNED_IN
import de.tergelius.ttsync.ui.UiTestGraph.Companion.target
import de.tergelius.ttsync.ui.sources.SourcesConfirm
import de.tergelius.ttsync.ui.sources.SourcesViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Filters on the Sources screen (0.2.0): the editor's draft, saving it in setup and from Settings,
 * and the question before a narrower filter removes copies.
 */
class SourcesFilterTest {
  @get:Rule val main = MainDispatcherRule()

  private val family =
    TtCalendar(
      id = 1,
      name = "Family",
      labels =
        listOf(
          TtLabel(id = 3, name = "Work", color = 0x47B2F7, order = 1),
          TtLabel(id = 9, name = "", color = 0xE73B3B, order = 2),
        ),
      users = listOf(TtCalendarUser(userId = 10, name = "Alex")),
    )
  private val football = TtCalendar(id = 2, name = "Football")
  private val work = EventFilter(labelIds = setOf(3))

  /** Setup finished: a target and [sources] are stored. */
  private fun configured(vararg sources: SourceCalendar) =
    UiTestGraph(
        settings =
          Settings(
            sources = sources.toList(),
            target = target(15),
            targetDisplayName = "TimeTree",
            keepRunningDone = true,
          ),
        credentials = SIGNED_IN,
      )
      .also { it.client.calendarsResult = { listOf(family, football) } }

  private val fullRun = "scheduler.runNow(full=true, SETUP)"

  @Test
  fun aFilterSetDuringSetupIsSavedBeforeTheFirstSync() {
    val g = UiTestGraph(credentials = SIGNED_IN)
    g.client.calendarsResult = { listOf(family, football) }
    val vm = SourcesViewModel(g.settings, g.actions)
    vm.openFilter(1) // not selected yet: no filter line
    assertNull(vm.state.value.editor)

    vm.toggle(1)
    vm.openFilter(1)
    val editor = vm.state.value.editor
    assertNotNull(editor)
    assertEquals(listOf(3L, 9L), editor!!.labels.map { it.id })
    vm.toggleLabel(3)
    vm.toggleMember(10)
    vm.setIncludeText("training, ")
    vm.setExcludeText("cancelled")
    vm.closeFilter()
    assertNull(vm.state.value.editor)
    val filter =
      EventFilter(
        labelIds = setOf(3),
        attendeeIds = setOf(10),
        includeTerms = listOf("training"),
        excludeTerms = listOf("cancelled"),
      )
    assertEquals(filter, vm.state.value.options.first { it.id == 1L }.filter)

    vm.save()
    assertNull(vm.state.value.confirm)
    assertEquals(SetupStep.TARGET, vm.state.value.next)
    assertEquals(listOf(SourceCalendar(1, "Family", filter)), g.settings.flow.value.sources)
    // No target yet: nothing to remove, no sync to start.
    assertTrue(g.calls.log.none { it.startsWith("engine.") || it.startsWith("scheduler.") })
  }

  @Test
  fun theListShowsTheStoredFilterAndOnlyForSelectedAvailableCalendars() {
    val g = configured(SourceCalendar(1, "Family", work), SourceCalendar(7, "Gone", work))
    val vm = SourcesViewModel(g.settings, g.actions)
    val options = vm.state.value.options.associateBy { it.id }
    assertEquals(work, options.getValue(1).filter)
    assertTrue(options.getValue(1).canFilter)
    assertFalse(options.getValue(2).canFilter) // not selected
    assertFalse(options.getValue(7).canFilter) // no access any more
    vm.openFilter(7)
    assertNull(vm.state.value.editor)

    vm.openFilter(1)
    assertEquals(listOf(3L), vm.state.value.editor!!.labels.filter { it.selected }.map { it.id })
  }

  @Test
  fun aNarrowerFilterFromSettingsAsksHowManyCopiesGo() {
    val g = configured(SourceCalendar(1, "Family"))
    g.engine.impact = 3
    val vm = SourcesViewModel(g.settings, g.actions)
    vm.openFilter(1)
    vm.toggleLabel(3)
    vm.closeFilter()
    vm.save()
    assertEquals(
      SourcesConfirm(removed = emptyList(), filteredCopies = 3, targetName = "TimeTree"),
      vm.state.value.confirm,
    )
    assertFalse(vm.state.value.saving)
    assertEquals(listOf("engine.filterImpact(1)"), g.calls.log)
    assertEquals(listOf(SourceCalendar(1, "Family")), g.settings.flow.value.sources)

    vm.confirmRemoval()
    assertNull(vm.state.value.confirm)
    assertEquals(SetupStep.DONE, vm.state.value.next)
    assertEquals(listOf(SourceCalendar(1, "Family", work)), g.settings.flow.value.sources)
    assertEquals(1, g.calls.log.count { it == fullRun })
  }

  @Test
  fun aFilterChangeThatRemovesNoCopySavesWithoutAsking() {
    val g = configured(SourceCalendar(1, "Family"))
    g.engine.impact = 0
    val vm = SourcesViewModel(g.settings, g.actions)
    vm.openFilter(1)
    vm.setExcludeText("cancelled")
    vm.closeFilter()
    vm.save()
    assertNull(vm.state.value.confirm)
    assertEquals(SetupStep.DONE, vm.state.value.next)
    assertEquals(
      listOf(SourceCalendar(1, "Family", EventFilter(excludeTerms = listOf("cancelled")))),
      g.settings.flow.value.sources,
    )
    assertEquals(1, g.calls.log.count { it == fullRun })
  }

  @Test
  fun deselectingAndFilteringShareOneQuestion() {
    val g = configured(SourceCalendar(1, "Family"), SourceCalendar(2, "Football"))
    g.engine.impact = 2
    val vm = SourcesViewModel(g.settings, g.actions)
    vm.toggle(2)
    vm.openFilter(1)
    vm.toggleLabel(3)
    vm.closeFilter()
    vm.save()
    assertEquals(
      SourcesConfirm(
        removed = listOf(SourceCalendar(2, "Football")),
        filteredCopies = 2,
        targetName = "TimeTree",
      ),
      vm.state.value.confirm,
    )

    // Cancel: nothing is stored, nothing removed, no sync.
    vm.dismissRemoval()
    assertNull(vm.state.value.confirm)
    assertEquals(listOf("engine.filterImpact(1)"), g.calls.log)
    assertEquals(2, g.settings.flow.value.sources.size)
    // The draft is still there for another try.
    assertEquals(work, vm.state.value.options.first { it.id == 1L }.filter)
  }

  @Test
  fun aDeselectWithoutFilterChangeAsksOnlyAboutTheCalendar() {
    val g = configured(SourceCalendar(1, "Family", work), SourceCalendar(2, "Football"))
    g.engine.impact = 5 // must not be asked: no filter changed
    val vm = SourcesViewModel(g.settings, g.actions)
    vm.toggle(2)
    vm.save()
    assertEquals(
      SourcesConfirm(listOf(SourceCalendar(2, "Football")), 0, "TimeTree"),
      vm.state.value.confirm,
    )
    assertTrue(g.calls.log.isEmpty())
  }

  @Test
  fun clearingAFilterCopiesAllEventsAgain() {
    val g = configured(SourceCalendar(1, "Family", work))
    val vm = SourcesViewModel(g.settings, g.actions)
    vm.openFilter(1)
    vm.clearFilter()
    assertTrue(vm.state.value.editor!!.isEmpty)
    vm.closeFilter()
    assertNull(vm.state.value.options.first { it.id == 1L }.filter)
    vm.save()
    assertNull(vm.state.value.confirm)
    assertEquals(listOf(SourceCalendar(1, "Family")), g.settings.flow.value.sources)
    assertNull(g.settings.flow.value.sources.single().filter)
    assertEquals(1, g.calls.log.count { it == fullRun })
  }

  @Test
  fun lookingAtAFilterWithoutChangesKeepsItAndStartsNoSync() {
    val stored = EventFilter(labelIds = setOf(3), includeTerms = listOf("match"))
    val g = configured(SourceCalendar(1, "Family", stored))
    val vm = SourcesViewModel(g.settings, g.actions)
    vm.openFilter(1)
    vm.closeFilter()
    assertFalse(vm.state.value.options.first { it.id == 1L }.filterEdited)
    vm.save()
    assertEquals(listOf(SourceCalendar(1, "Family", stored)), g.settings.flow.value.sources)
    assertTrue(g.calls.log.none { it.startsWith("scheduler.runNow") })
  }

  @Test
  fun aDeselectedAndReselectedCalendarKeepsItsDraft() {
    val g = configured(SourceCalendar(1, "Family"))
    val vm = SourcesViewModel(g.settings, g.actions)
    vm.openFilter(1)
    vm.toggleLabel(3)
    vm.closeFilter()
    vm.toggle(1)
    vm.toggle(1)
    vm.save()
    assertEquals(listOf(SourceCalendar(1, "Family", work)), g.settings.flow.value.sources)
  }
}
