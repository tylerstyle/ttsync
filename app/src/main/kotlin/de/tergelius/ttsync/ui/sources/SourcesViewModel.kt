package de.tergelius.ttsync.ui.sources

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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

/** One checkbox. [available] is false for a selected calendar that TimeTree no longer shows. */
data class SourceOption(
  val id: Long,
  val name: String?,
  val selected: Boolean,
  val available: Boolean = true,
) {
  override fun toString(): String = "SourceOption(id=$id, selected=$selected, available=$available)"
}

data class SourcesUiState(
  val loading: Boolean = true,
  val options: List<SourceOption> = emptyList(),
  val loadError: SourcesError? = null,
  val saving: Boolean = false,
  val saveError: SourcesError? = null,
  /** Asks before the copies of these deselected calendars are deleted. */
  val confirmRemoval: List<SourceCalendar>? = null,
  val next: SetupStep? = null,
) {
  val canSave: Boolean
    get() = !loading && !saving && options.any { it.selected }
}

/**
 * Sources (DESIGN.md section 7.2): checkboxes from `GET /calendars`, read through
 * [UserActions.timeTreeCalendars] (safe while a sync runs).
 */
class SourcesViewModel(
  private val settings: SettingsRepository,
  private val actions: UserActions,
) : ViewModel() {
  private val _state = MutableStateFlow(SourcesUiState())
  val state: StateFlow<SourcesUiState> = _state.asStateFlow()

  init {
    load()
  }

  fun load() {
    _state.update { it.copy(loading = true, loadError = null) }
    viewModelScope.launch {
      try {
        val selected = settings.current().sources
        val calendars = actions.timeTreeCalendars()
        _state.update { it.copy(loading = false, options = optionsOf(calendars, selected)) }
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

  /** Saves, after asking if copies would be deleted. */
  fun save() {
    if (!_state.value.canSave) return
    val options = _state.value.options
    viewModelScope.launch {
      try {
        val selected = selection(options)
        val change = actions.sourceChange(selected)
        if (change.removesCopies) {
          _state.update { it.copy(confirmRemoval = change.removed) }
        } else {
          apply(selected)
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        _state.update { it.copy(saveError = errorOf(e)) }
      }
    }
  }

  fun confirmRemoval() {
    _state.update { it.copy(confirmRemoval = null) }
    val options = _state.value.options
    viewModelScope.launch { apply(selection(options)) }
  }

  fun dismissRemoval() = _state.update { it.copy(confirmRemoval = null) }

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
     * everything stored with it (its filter), with the current name; a new one starts unfiltered.
     */
    fun selectionOf(options: List<SourceOption>, stored: List<SourceCalendar>): List<SourceCalendar> {
      val byId = stored.associateBy { it.id }
      return options
        .filter { it.selected }
        .map { option ->
          val name = option.name.orEmpty()
          byId[option.id]?.copy(name = name) ?: SourceCalendar(option.id, name)
        }
    }

    /**
     * Active TimeTree calendars, pre-selected from the stored selection, plus stored ones that
     * TimeTree no longer lists (lost access), so they can be deselected.
     */
    fun optionsOf(calendars: List<TtCalendar>, selected: List<SourceCalendar>): List<SourceOption> {
      val selectedIds = selected.map { it.id }.toSet()
      val active =
        calendars
          .filter { it.isActive }
          .map { SourceOption(it.id, it.name?.takeIf(String::isNotBlank), it.id in selectedIds) }
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
