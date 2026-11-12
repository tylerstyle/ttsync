package de.tergelius.ttsync.sync

import de.tergelius.ttsync.TtLog
import de.tergelius.ttsync.calendar.CalendarGateway
import de.tergelius.ttsync.calendar.TargetCalendar
import de.tergelius.ttsync.mapping.EventMapper
import de.tergelius.ttsync.settings.CredentialStore
import de.tergelius.ttsync.settings.SettingsRepository
import de.tergelius.ttsync.settings.StatusRepository
import de.tergelius.ttsync.store.SyncDatabase
import de.tergelius.ttsync.timetree.TimeTreeClient
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.random.Random

/**
 * The sync algorithm (DESIGN.md section 5) and the user actions that delete copies. Phase 4
 * implements it as [DefaultSyncEngine]. Pure Kotlin: no `android.*` imports, so JVM tests cover it
 * with fakes of the client, the gateway and the stores.
 *
 * Contract for every implementation:
 * - **One at a time.** All methods take the same process-wide mutex, so a periodic run, "Sync now"
 *   and a user action never overlap.
 * - **Safety.** Writes go only through [CalendarGateway], whose R1–R5 rules hold; only rows in the
 *   target calendar that carry the marker of the expected uuid are ever updated or deleted. During
 *   a sync, only the diff (step 5) deletes, and only in-window mappings of active calendars. The
 *   adoption scan only adopts (and removes duplicates of live, active uuids).
 * - **Idempotent.** A run after an unchanged TimeTree writes nothing.
 * - **Cursor.** A calendar's cursor advances only after all its writes succeeded and their mappings
 *   are committed.
 * - **Privacy.** Logs carry only counts, codes and 8-character uuid prefixes.
 */
interface SyncEngine {
  /**
   * Runs one sync: preconditions, crash check, fetch, map, diff, apply, finish. Never throws for
   * expected failures; they come back as [SyncReport.failure]. Saves the report to
   * `StatusRepository` and logs `run finished` (tag `TtSync`).
   */
  suspend fun run(request: SyncRequest): SyncReport

  /**
   * "Remove all copies", removal at sign-out, and the first half of a target change: deletes every
   * row in [target] that carries the app's marker, mapped or not, each with an R1-verified delete
   * against the uuid read from its marker; then clears the mapping table. Rows that fail R1 are left
   * alone and counted. Does not change settings, snapshots, cursors or the schedule; the caller
   * does that (for a target change: save the new target, then [SyncScheduler.runNow] with FULL).
   * Call only after the user confirmed.
   */
  suspend fun removeAllCopies(target: TargetCalendar): RemovalReport

  /**
   * Deselecting TimeTree calendars, or "Remove copies" for a paused one: R1-verified deletes of
   * their copies in the current target, then drops their snapshots, mappings and cursors. Call only
   * after the user confirmed, and remove them from the selection.
   */
  suspend fun deselect(ttCalIds: Set<Long>): RemovalReport

  /** Debug `DROP_MAPPINGS`: clears the mapping table only, so the next run must adopt. */
  suspend fun dropMappings()
}

/** One run's parameters. */
data class SyncRequest(
  val trigger: SyncTrigger,
  /** Forces FULL for every calendar ("Full resync", setup, source or target change). */
  val forceFull: Boolean = false,
  /** Names the raw dump folder and the report; see [newRunId]. */
  val runId: String,
) {
  companion object {
    private val RUN_ID_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)

    /** A sortable, file-name-safe id such as `20261112-080000-3fa2`. */
    fun newRunId(nowMillis: Long, random: Random = Random.Default): String =
      RUN_ID_TIME.format(Instant.ofEpochMilli(nowMillis)) +
        "-" +
        random.nextInt(0x10000).toString(16).padStart(4, '0')
  }
}

/** Outcome of [SyncEngine.removeAllCopies] and [SyncEngine.deselect]. */
data class RemovalReport(
  val deleted: Int,
  /** Rows that failed the R1 checks and were left alone. */
  val rejected: Int,
)

/**
 * Phase 4 implements this. Every dependency is an interface without `android.*`, so JVM tests can
 * pass fakes; [nowMillis] and [zone] make time controllable.
 */
class DefaultSyncEngine(
  private val client: TimeTreeClient,
  private val mapper: EventMapper,
  private val db: SyncDatabase,
  private val gateway: CalendarGateway,
  private val settings: SettingsRepository,
  private val status: StatusRepository,
  private val credentials: CredentialStore,
  private val log: TtLog,
  private val nowMillis: () -> Long = System::currentTimeMillis,
  private val zone: () -> ZoneId = ZoneId::systemDefault,
) : SyncEngine {
  override suspend fun run(request: SyncRequest): SyncReport = notYet()

  override suspend fun removeAllCopies(target: TargetCalendar): RemovalReport = notYet()

  override suspend fun deselect(ttCalIds: Set<Long>): RemovalReport = notYet()

  override suspend fun dropMappings(): Unit = notYet()

  private fun notYet(): Nothing =
    TODO(
      "phase 4: SyncEngine ($client, $mapper, $db, $gateway, $settings, $status, $credentials, " +
        "$log, ${nowMillis()}, ${zone()})"
    )
}
