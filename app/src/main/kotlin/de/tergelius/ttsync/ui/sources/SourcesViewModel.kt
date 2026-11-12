package de.tergelius.ttsync.ui.sources

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.tergelius.ttsync.mapping.EventFilter
import de.tergelius.ttsync.settings.SettingsRepository
import de.tergelius.ttsync.settings.SetupStep
import de.tergelius.ttsync.settings.SourceCalendar
import de.tergelius.ttsync.timetree.TtCalendar
import de.tergelius.ttsync.timetree.TtError
import de.tergelius.ttsync.ui.RemovalFailedException
import de.tergelius.ttsync.ui.UserActions
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Why the calendar list could not be loaded or saved. */
enum class SourcesError {
  /** The stored sign-in no longer works: sign in again. */
  SIGN_IN,
  RATE_LIMITED,
  OFFLINE,
  API_CHANGED,

  /** The copies of the deselected calendars could not be removed; nothing was changed. */
  REMOVAL_FAILED,
  UNKNOWN,
}

/**
 * One checkbox. [available] is false for a selected calendar that TimeTree no longer shows.
 * [filter] is what the row shows (null: all events): the stored filter, or the user's edit if
 * [filterEdited]. Only an edited filter replaces the stored one on save
 * ([SourcesViewModel.selectionOf]).
 */
data class SourceOption(
  val id: Long,
  val name: String?,
  val selected: Boolean,
  val available: Boolean = true,
  val filter: EventFilter? = null,
  val filterEdited: Boolean = false,
) {
  /** Only a selected calendar TimeTree still shows has a filter line (and labels and members). */
  val canFilter: Boolean
    get() = selected && available

  override fun toString(): String =
    "SourceOption(id=$id, selected=$selected, available=$available, filtered=${filter != null})"
}

/**
 * The question before a save deletes copies: those of the deselected calendars ([removed]), and
 * [filteredCopies] copies that a narrower filter no longer matches ([UserActions.filterImpact]).
 * [targetName] is the target calendar's name, null if unknown.
 */
data class SourcesConfirm(
  val removed: List<SourceCalendar>,
  val filteredCopies: Int,
  val targetName: String?,
)

data class SourcesUiState(
  val loading: Boolean = true,
  val options: List<SourceOption> = emptyList(),
  val loadError: SourcesError? = null,
  val saving: Boolean = false,
  val saveError: SourcesError? = null,
  /** Asks before copies are deleted (deselected calendars, narrower filters). */
  val confirm: SourcesConfirm? = null,
  /** The full-screen filter editor of one calendar, or null for the list. */
  val editor: FilterEditorState? = null,
  val next: SetupStep? = null,
) {
  val canSave: Boolean
    get() = !loading && !saving && options.any { it.selected }
}

/**
 * Sources (DESIGN.md section 7.2): checkboxes from `GET /calendars`, read through
 * [UserActions.timeTreeCalendars] (safe while a sync runs), and a filter per selected calendar
 * (0.2.0), edited in [FilterEditorState] and saved with the selection.
 */
class SourcesViewModel(
  private val settings: SettingsRepository,
  private val actions: UserActions,
) : ViewModel() {
  private val _state = MutableStateFlow(SourcesUiState())
  val state: StateFlow<SourcesUiState> = _state.asStateFlow()

  /** The calendars of the last load, by id: their labels and members feed the filter editor. */
  private var calendars: Map<Long, TtCalendar> = emptyMap()

  init {
    load()
  }

  fun load() {
    _state.update { it.copy(loading = true, loadError = null) }
    viewModelScope.launch {
      try {
        val selected = settings.current().sources
        val calendars = actions.timeTreeCalendars()
        this@SourcesViewModel.calendars = calendars.associateBy { it.id }
        _state.update {
          it.copy(loading = false, options = optionsOf(calendars, selected), editor = null)
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        _state.update { it.copy(loading = false, loadError = errorOf(e)) }
      }
    }
  }

  /**
   * The screen is shown again, e.g. back from "Sign in again": a list that failed for want of a
   * sign-in is loaded again (phase 5 review, SHOULD-FIX-3).
   */
  fun onResume() {
    val current = _state.value
    if (current.saveError == SourcesError.SIGN_IN) _state.update { it.copy(saveError = null) }
    if (current.loadError == SourcesError.SIGN_IN && !current.loading) load()
  }

  fun toggle(id: Long) = _state.update { state ->
    state.copy(
      options = state.options.map { if (it.id == id) it.copy(selected = !it.selected) else it },
      saveError = null,
    )
  }

  /**
   * Saves, after asking if copies would be deleted: those of deselected calendars, and those a
   * changed filter no longer matches (one question for both; none if no copy would go).
   */
  fun save() {
    if (!_state.value.canSave) return
    val options = _state.value.options
    _state.update { it.copy(saving = true, saveError = null) }
    viewModelScope.launch {
      try {
        val selected = selection(options)
        val change = actions.sourceChange(selected)
        val filteredCopies =
          if (change.hasTarget) {
            change.filterChanged.sumOf { actions.filterImpact(it.id, it.activeFilter) }
          } else {
            0
          }
        if (change.removesCopies || filteredCopies > 0) {
          val confirm =
            SourcesConfirm(
              removed = if (change.removesCopies) change.removed else emptyList(),
              filteredCopies = filteredCopies,
              targetName = settings.current().targetDisplayName,
            )
          _state.update { it.copy(saving = false, confirm = confirm) }
        } else {
          apply(selected)
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        _state.update { it.copy(saving = false, saveError = errorOf(e)) }
      }
    }
  }

  fun confirmRemoval() {
    _state.update { it.copy(confirm = null) }
    val options = _state.value.options
    viewModelScope.launch { apply(selection(options)) }
  }

  fun dismissRemoval() = _state.update { it.copy(confirm = null) }

  // Filter editor. Its changes stay a draft in [SourceOption.filter] until [save].

  /** Opens the filter editor of a selected, available calendar. */
  fun openFilter(id: Long) {
    val option = _state.value.options.firstOrNull { it.id == id && it.canFilter } ?: return
    val calendar = calendars[id] ?: return
    _state.update {
      it.copy(editor = FilterEditorState.of(calendar, option.filter), saveError = null)
    }
  }

  fun toggleLabel(id: Long) = editFilter { it.toggleLabel(id) }

  fun toggleMember(userId: Long) = editFilter { it.toggleMember(userId) }

  fun setIncludeText(text: String) = editFilter { it.withIncludeText(text) }

  fun setExcludeText(text: String) = editFilter { it.withExcludeText(text) }

  /** "Clear": back to all events (applied with "Done"). */
  fun clearFilter() = editFilter { it.cleared() }

  /** "Done", or back: the editor's filter becomes the calendar's draft, to be saved with [save]. */
  fun closeFilter() = _state.update { state ->
    val editor = state.editor ?: return@update state
    val filter = editor.filter
    state.copy(
      editor = null,
      options =
        state.options.map {
          if (it.id == editor.calendarId && filter != it.filter) {
            it.copy(filter = filter, filterEdited = true)
          } else {
            it
          }
        },
    )
  }

  private fun editFilter(change: (FilterEditorState) -> FilterEditorState) = _state.update {
    it.copy(editor = it.editor?.let(change))
  }

  fun onNavigated() = _state.update { it.copy(next = null) }

  /** The checked calendars, read against the stored selection so that filters are kept. */
  private suspend fun selection(options: List<SourceOption>): List<SourceCalendar> =
    selectionOf(options, settings.current().sources)

  private suspend fun apply(selected: List<SourceCalendar>) {
    _state.update { it.copy(saving = true, saveError = null) }
    try {
      val next = actions.saveSources(selected)
      _state.update { it.copy(saving = false, next = next) }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      _state.update { it.copy(saving = false, saveError = errorOf(e)) }
    }
  }

  companion object {
    /**
     * The checked [options] as the selection to save. A calendar that is already [stored] keeps
     * everything stored with it (`copy`, its filter included), with the current name; a new one
     * starts unfiltered. A filter the user edited ([SourceOption.filterEdited]) replaces that,
     * normalized, and an empty one as null (all events, stored exactly like 0.1.x).
     */
    fun selectionOf(options: List<SourceOption>, stored: List<SourceCalendar>): List<SourceCalendar> {
      val byId = stored.associateBy { it.id }
      return options
        .filter { it.selected }
        .map { option ->
          val name = option.name.orEmpty()
          val kept = byId[option.id]?.copy(name = name) ?: SourceCalendar(option.id, name)
          if (option.filterEdited) {
            kept.copy(filter = option.filter?.normalized()?.takeUnless { it.isEmpty })
          } else {
            kept
          }
        }
    }

    /**
     * Active TimeTree calendars, pre-selected from the stored selection with their filters, plus
     * stored ones that TimeTree no longer lists (lost access), so they can be deselected.
     */
    fun optionsOf(calendars: List<TtCalendar>, selected: List<SourceCalendar>): List<SourceOption> {
      val selectedIds = selected.map { it.id }.toSet()
      val filters = selected.associate { it.id to it.activeFilter }
      val active =
        calendars
          .filter { it.isActive }
          .map {
            SourceOption(
              it.id,
              it.name?.takeIf(String::isNotBlank),
              selected = it.id in selectedIds,
              filter = filters[it.id],
            )
          }
      val activeIds = active.map { it.id }.toSet()
      val gone =
        selected
          .filter { it.id !in activeIds }
          .map {
            SourceOption(
              it.id,
              it.name.takeIf(String::isNotBlank),
              selected = true,
              available = false,
              filter = it.activeFilter,
            )
          }
      return active + gone
    }

    fun errorOf(e: Exception): SourcesError =
      when (e) {
        is TtError.LoginRequired,
        is TtError.AuthRejected -> SourcesError.SIGN_IN
        is TtError.RateLimited -> SourcesError.RATE_LIMITED
        is TtError.Transient -> SourcesError.OFFLINE
        is TtError.ApiChanged -> SourcesError.API_CHANGED
        is RemovalFailedException -> SourcesError.REMOVAL_FAILED
        else -> SourcesError.UNKNOWN
      }
  }
}
