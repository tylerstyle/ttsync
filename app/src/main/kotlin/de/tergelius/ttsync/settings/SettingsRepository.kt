package de.tergelius.ttsync.settings

import de.tergelius.ttsync.calendar.TargetCalendar
import de.tergelius.ttsync.mapping.EventFilter
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/**
 * The user's choices (DESIGN.md section 7), stored in DataStore and implemented by
 * `DataStoreSettingsRepository`; UI previews and tests use fakes. No `android.*` here, so the sync
 * engine can depend on it.
 */
interface SettingsRepository {
  val settings: Flow<Settings>

  /** The current value, read once. */
  suspend fun current(): Settings

  /** The selected TimeTree calendars (Sources screen). */
  suspend fun setSources(sources: List<SourceCalendar>)

  /** The target calendar (Target screen); [displayName] is for display only. */
  suspend fun setTarget(target: TargetCalendar, displayName: String)

  suspend fun setInterval(interval: SyncInterval)

  /** The "Keep running" setup step was completed or skipped. */
  suspend fun setKeepRunningDone(done: Boolean)

  /**
   * The install uuid sent with every login: 32 lowercase hex characters, created on first use and
   * kept until [clear] (sign-out renews it).
   */
  suspend fun installUuid(): String

  /** Sign-out: deletes everything, including the install uuid. */
  suspend fun clear()
}

data class Settings(
  val sources: List<SourceCalendar> = emptyList(),
  val target: TargetCalendar? = null,
  val targetDisplayName: String? = null,
  val interval: SyncInterval = SyncInterval.DEFAULT,
  val keepRunningDone: Boolean = false,
)

/**
 * A selected TimeTree calendar. [name] is TimeTree's name at selection time, for display only.
 * [filter] chooses which of its events are copied; null copies all of them (0.1.x behaviour).
 *
 * Stored as JSON ([StoreCodec]); a null filter is not written, so an unfiltered selection is stored
 * exactly as 0.1.x stored it, and 0.1.x values decode with `filter = null`. Anything that rebuilds
 * a selection must carry the filter over (`copy`, not a new instance).
 */
@Serializable
data class SourceCalendar(val id: Long, val name: String, val filter: EventFilter? = null) {
  /** [filter] if it restricts anything, else null: what the sync applies. */
  val activeFilter: EventFilter?
    get() = filter?.takeUnless { it.isEmpty }

  override fun toString(): String = "SourceCalendar(id=$id, filter=${activeFilter != null})"
}

/** Sync intervals the user may choose; never shorter than 1 h, as calendar owners were promised. */
enum class SyncInterval(val duration: Duration) {
  HOURS_1(1.hours),
  HOURS_3(3.hours),
  HOURS_6(6.hours);

  companion object {
    val DEFAULT = HOURS_1
  }
}

/** The start screen follows setup progress: Login → Sources → Target → Keep running → Status. */
enum class SetupStep {
  LOGIN,
  SOURCES,
  TARGET,
  KEEP_RUNNING,
  DONE;

  companion object {
    fun of(signedIn: Boolean, settings: Settings): SetupStep =
      when {
        !signedIn -> LOGIN
        settings.sources.isEmpty() -> SOURCES
        settings.target == null -> TARGET
        !settings.keepRunningDone -> KEEP_RUNNING
        else -> DONE
      }
  }
}
