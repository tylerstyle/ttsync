package de.tergelius.ttsync.sync

import de.tergelius.ttsync.TtLog
import de.tergelius.ttsync.calendar.BatchResult
import de.tergelius.ttsync.calendar.CalendarGateway
import de.tergelius.ttsync.calendar.CalendarOp
import de.tergelius.ttsync.calendar.MarkedRow
import de.tergelius.ttsync.calendar.OpResult
import de.tergelius.ttsync.calendar.RowCheck
import de.tergelius.ttsync.calendar.TargetCalendar
import de.tergelius.ttsync.calendar.TargetCheck
import de.tergelius.ttsync.calendar.TargetUnavailableException
import de.tergelius.ttsync.mapping.Desired
import de.tergelius.ttsync.mapping.EventFilter
import de.tergelius.ttsync.mapping.EventMapper
import de.tergelius.ttsync.mapping.MappingInput
import de.tergelius.ttsync.mapping.MappingResult
import de.tergelius.ttsync.mapping.Marker
import de.tergelius.ttsync.mapping.SpecHash
import de.tergelius.ttsync.settings.CredentialStore
import de.tergelius.ttsync.settings.SettingsRepository
import de.tergelius.ttsync.settings.StatusRepository
import de.tergelius.ttsync.store.CalendarState
import de.tergelius.ttsync.store.Mapping
import de.tergelius.ttsync.store.MetaKeys
import de.tergelius.ttsync.store.PendingDelete
import de.tergelius.ttsync.store.Snapshot
import de.tergelius.ttsync.store.SyncCursor
import de.tergelius.ttsync.store.SyncDatabase
import de.tergelius.ttsync.timetree.FetchedEvent
import de.tergelius.ttsync.timetree.TimeTreeApi
import de.tergelius.ttsync.timetree.TimeTreeClient
import de.tergelius.ttsync.timetree.TtError
import de.tergelius.ttsync.timetree.TtEvent
import de.tergelius.ttsync.timetree.TtJson
import de.tergelius.ttsync.timetree.TtLabel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer

/**
 * The sync algorithm (DESIGN.md section 5) and the user actions that delete copies. Pure Kotlin: no
 * `android.*` imports, so JVM tests cover it with fakes of the client, the gateway and the stores.
 *
 * Contract for every implementation:
 * - **One at a time.** All methods take the same process-wide mutex, so a periodic run, "Sync now"
 *   and a user action never overlap.
 * - **Safety.** Writes go only through [CalendarGateway], whose R1–R6 rules hold; only rows in the
 *   target calendar that carry the marker of the expected uuid are ever updated or deleted. During
 *   a sync, the diff (step 5) deletes only in-window mappings of active calendars. The adoption
 *   scan only adopts, removes duplicates of live, active uuids, and removes unmapped leftovers of
 *   live, in-window events that their active calendar's filter excludes. Step 6a deletes again only
 *   rows that an earlier sync deleted and Google's sync put back, still with their marker in the
 *   target, for active calendars and uuids no calendar wants.
 * - **Idempotent.** A run after an unchanged TimeTree writes nothing.
 * - **Cursor.** A calendar's cursor (its high-water mark, see [SyncCursor.highWater]) advances only
 *   after all its writes succeeded and their mappings are committed. It is computed from the
 *   fetched events, never taken from the server's `since` (a backwards paging cursor).
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
   * Deletes every row in [target] that carries the app's marker, mapped or not, each with an
   * R1-verified delete against the uuid read from its marker; then clears the mapping table. Rows
   * that fail R1 are left alone and counted. A [target] that no longer exists has nothing to
   * delete. Call only after the user confirmed.
   *
   * [stopSyncing] (the default) is for "Remove all copies" and removal at sign-out: once the deletes
   * went through it also stops syncing, so the next run cannot recreate the copies. It empties the
   * selected sources in `SettingsRepository` and drops all snapshots, cursors and mappings; the
   * caller cancels the periodic work first (no run can start in between: this holds the engine's
   * mutex). A target change passes `false`: only the copies and the mappings go, and the caller
   * then saves the new target and runs FULL ([SyncScheduler.runNow] with `full = true`).
   *
   * Every failure, a missing calendar permission included, comes back as [RemovalReport.failure],
   * and then nothing else was changed: the selection, snapshots, cursors and mappings stay as they
   * were (phase 5 review, SHOULD-FIX-1).
   */
  suspend fun removeAllCopies(target: TargetCalendar, stopSyncing: Boolean = true): RemovalReport

  /**
   * Deselecting TimeTree calendars, or "Remove copies" for a paused one: R1-verified deletes of
   * their mapped copies in the current target, then removes them from the selected sources and drops
   * their snapshots, mappings and cursors. If the deletes fail, the selection and the data are kept
   * as they were, so the calendar still shows as selected and the call can be repeated. Call only
   * after the user confirmed. (No run can start in between: this holds the engine's mutex.)
   */
  suspend fun deselect(ttCalIds: Set<Long>): RemovalReport

  /** Debug `DROP_MAPPINGS`: clears the mapping table only, so the next run must adopt. */
  suspend fun dropMappings()

  /**
   * Runs [block] while no run and no removal is going on: it waits for a running one to end and
   * keeps the next one out. For the wipe at sign-out, which must not happen under a run that is
   * still writing. [block] must not call the engine (the mutex is not reentrant).
   */
  suspend fun <T> exclusive(block: suspend () -> T): T

  /**
   * How many copies of TimeTree calendar [ttCalId] the next run would delete if its filter became
   * [filter], for the confirmation before saving a filter. Read-only and without the run lock (a
   * sync may be going on; the number is an estimate as of the last run): maps the stored snapshots
   * with [filter] and counts the in-window mappings in the current target whose events it would
   * exclude. 0 for no filter, no target, or a paused calendar (its copies are never touched).
   * Unmapped leftovers are not counted. Blocking: call off the main thread.
   */
  suspend fun filterImpact(ttCalId: Long, filter: EventFilter?): Int
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
  /** Null if every delete went through; otherwise why it stopped (the rest is left as it was). */
  val failure: SyncFailure? = null,
) {
  val succeeded: Boolean
    get() = failure == null
}

/**
 * The engine. Every dependency is an interface without `android.*`, so JVM tests can pass fakes;
 * [nowMillis] and [zone] make time controllable.
 *
 * Per run: preconditions → crash flag → `GET /calendars` → fetch every active calendar (FULL or
 * INCREMENTAL) → map → adoption scan if needed → per calendar: plan, apply in verified chunks,
 * commit mappings per chunk, then snapshots and cursor → clear the flag, report, `requestSync`.
 * Nothing is written to the calendar before every calendar has been fetched and mapped.
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

  override suspend fun run(request: SyncRequest): SyncReport = LOCK.withLock { Run(request).execute() }

  override suspend fun removeAllCopies(target: TargetCalendar, stopSyncing: Boolean): RemovalReport =
    LOCK.withLock {
      removal("remove all copies") { counter ->
        if (targetWritable(target)) {
          val rows = calendarCall { gateway.findMarkedRows(target) }
          deleteVerified(target, rows.map { CalendarOp.Delete(it.eventId, it.uuid) }, counter)
        }
        // Only after the deletes went through: a failed removal keeps everything, so syncing goes
        // on and the user can retry (phase 5 review, SHOULD-FIX-1).
        if (stopSyncing) stopSyncing() else db.transaction { db.mappings.clear(); db.pendingDeletes.clear() }
      }
    }

  override suspend fun deselect(ttCalIds: Set<Long>): RemovalReport =
    LOCK.withLock {
      removal("deselect ${ttCalIds.size}") { counter ->
        val current = settings.current()
        val target = current.target
        if (target != null) {
          val copies =
            db.mappings.all().filter { it.ttCalId in ttCalIds && it.targetCalId == target.calendarId }
          if (copies.isNotEmpty() && targetWritable(target)) {
            deleteVerified(target, copies.map { CalendarOp.Delete(it.eventId, it.uuid) }, counter)
          }
        }
        // Only after the deletes went through: a failed removal leaves the calendar selected, so
        // the user can retry (phase 4 review, SHOULD-FIX-1).
        val sources = settings.current().sources
        val remaining = sources.filterNot { it.id in ttCalIds }
        if (remaining.size != sources.size) settings.setSources(remaining)
        db.transaction {
          for (id in ttCalIds) {
            db.snapshots.deleteCalendar(id)
            db.mappings.deleteCalendar(id)
            db.pendingDeletes.deleteCalendar(id)
            db.cursors.delete(id)
          }
        }
      }
    }

  override suspend fun dropMappings() {
    LOCK.withLock {
      db.mappings.clear()
      log.i("mappings dropped")
    }
  }

  override suspend fun <T> exclusive(block: suspend () -> T): T = LOCK.withLock { block() }

  override suspend fun filterImpact(ttCalId: Long, filter: EventFilter?): Int {
    val active = filter?.takeUnless { it.isEmpty } ?: return 0
    val target = settings.current().target ?: return 0
    val cursor = db.cursors.get(ttCalId)
    if (cursor?.state == CalendarState.PAUSED) return 0
    val events =
      db.snapshots.forCalendar(ttCalId).mapNotNull {
        try {
          TtEvent.fromJson(it.json)
        } catch (e: TtError.ApiChanged) {
          null
        }
      }
    val zoneId = zone()
    val today = Instant.ofEpochMilli(nowMillis()).atZone(zoneId).toLocalDate()
    val windowStart = EventMapper.windowStartMillis(today, zoneId)
    val labels = decodeLabels(cursor?.labelsJson).orEmpty().associateBy { it.id }
    val excluded = mapper.map(MappingInput(events, labels, today, zoneId, active)).filtered
    val impact =
      db.mappings.forCalendar(ttCalId).count {
        it.targetCalId == target.calendarId && it.uuid in excluded && it.endAt >= windowStart
      }
    log.i("filter impact for calendar $ttCalId: $impact copies")
    return impact
  }

  // ---------------------------------------------------------------------------------------------
  // Removals

  private class RemovalCounter {
    var deleted = 0
    var rejected = 0
  }

  private suspend fun removal(what: String, block: suspend (RemovalCounter) -> Unit): RemovalReport {
    val counter = RemovalCounter()
    val failure =
      try {
        block(counter)
        null
      } catch (e: CancellationException) {
        throw e
      } catch (e: RunFailed) {
        e.failure
      } catch (e: Exception) {
        log.e("$what failed: ${e.javaClass.simpleName}")
        SyncFailure(SyncProblem.INTERNAL)
      }
    val report = RemovalReport(counter.deleted, counter.rejected, failure)
    log.i("$what: deleted=${report.deleted} rejected=${report.rejected} failure=${failure?.problem}")
    return report
  }

  /** "Remove all copies" succeeded: nothing may be recreated, so the sources and their state go. */
  private suspend fun stopSyncing() {
    val current = settings.current()
    settings.setSources(emptyList())
    db.transaction {
      val ids = db.cursors.all().map { it.ttCalId } + current.sources.map { it.id }
      for (id in ids.toSet()) {
        db.snapshots.deleteCalendar(id)
        db.cursors.delete(id)
      }
      db.mappings.clear()
      db.pendingDeletes.clear()
    }
    log.i("syncing stopped: ${current.sources.size} sources cleared")
  }

  /** True if [target] can take deletes, false if it is gone (its rows went with it). */
  private suspend fun targetWritable(target: TargetCalendar): Boolean {
    if (!gateway.hasPermission()) fail(SyncProblem.NO_CALENDAR_PERMISSION)
    return when (val check = target.check(calendarCall { gateway.calendar(target.calendarId) })) {
      TargetCheck.OK -> true
      TargetCheck.MISSING -> false
      else -> {
        log.w("target not writable: $check")
        fail(SyncProblem.TARGET_UNAVAILABLE)
      }
    }
  }

  private suspend fun deleteVerified(
    target: TargetCalendar,
    deletes: List<CalendarOp.Delete>,
    counter: RemovalCounter,
  ) {
    for (chunk in deletes.chunked(CalendarGateway.MAX_BATCH)) {
      for (result in applyOps(target, chunk)) {
        when (result) {
          OpResult.Deleted -> counter.deleted++
          is OpResult.Rejected -> if (!result.check.isGone()) counter.rejected++
          else -> error("unexpected result of a delete: $result")
        }
      }
    }
    if (counter.deleted > 0) requestUpload(target, uploads = null)
  }

  // ---------------------------------------------------------------------------------------------
  // Shared write path

  /**
   * Applies one chunk (≤ [CalendarGateway.MAX_BATCH]) and returns one result per operation. If an
   * R2 precondition aborts the chunk, the operations are replayed one at a time (DESIGN.md R2, E15);
   * a single operation that aborts again is re-checked: if the row is no longer ours it counts as
   * [OpResult.Rejected], but if it still is, the precondition itself is broken on this device and the
   * run stops instead of inserting duplicates.
   */
  private suspend fun applyOps(target: TargetCalendar, ops: List<CalendarOp>): List<OpResult> {
    if (ops.isEmpty()) return emptyList()
    when (val batch = calendarCall { gateway.apply(target, ops) }) {
      is BatchResult.Applied -> {
        check(batch.results.size == ops.size) { "gateway returned ${batch.results.size} of ${ops.size}" }
        return batch.results
      }
      BatchResult.Aborted -> log.w("batch of ${ops.size} aborted by a precondition, replaying one at a time")
    }
    return ops.map { op ->
      when (val single = calendarCall { gateway.apply(target, listOf(op)) }) {
        is BatchResult.Applied -> single.results.single()
        BatchResult.Aborted -> {
          val eventId =
            when (op) {
              is CalendarOp.Insert -> {
                log.e("insert aborted on its own")
                fail(SyncProblem.CALENDAR_WRITE_FAILED)
              }
              is CalendarOp.Update -> op.eventId
              is CalendarOp.Delete -> op.eventId
            }
          val check =
            calendarCall { gateway.checkRows(target, mapOf(eventId to op.uuid)) }[eventId]
              ?: RowCheck.MISSING
          if (check == RowCheck.OURS) {
            log.e(
              "precondition fails on an own row: ${op.javaClass.simpleName} eventId=$eventId " +
                "uuid=${TtLog.short(op.uuid)} check=$check, replayed alone from a batch of " +
                "${ops.size}; stopping"
            )
            fail(SyncProblem.CALENDAR_WRITE_FAILED)
          }
          OpResult.Rejected(check)
        }
      }
    }
  }

  /**
   * Best effort: `requestSync` does nothing for an account the app cannot see (Android 13+), and
   * the provider starts Google's sync for changed rows on its own (device test B2). Nothing relies
   * on it; [SyncReport.notUploaded] watches whether the copies really get uploaded.
   */
  private fun requestUpload(target: TargetCalendar, uploads: Boolean?) {
    try {
      // Pointless while sync is known to be off. (The request is not a manual one, so it obeys
      // the switches also where the app cannot read them: review 2026-11, F22.)
      if (uploads ?: gateway.googleSyncState(target).uploads) {
        gateway.requestUpload(target)
        log.d("requestSync asked (best effort)")
      }
    } catch (e: Exception) {
      log.d("requestSync failed: ${e.javaClass.simpleName}")
    }
  }

  private suspend fun <T> calendarCall(block: suspend () -> T): T =
    try {
      block()
    } catch (e: CancellationException) {
      throw e
    } catch (e: RunFailed) {
      throw e
    } catch (e: SecurityException) {
      log.w("calendar permission missing")
      fail(SyncProblem.NO_CALENDAR_PERMISSION)
    } catch (e: TargetUnavailableException) {
      log.w("target unavailable: ${e.check}")
      fail(if (e.check == TargetCheck.MISSING) SyncProblem.TARGET_DELETED else SyncProblem.TARGET_UNAVAILABLE)
    } catch (e: Exception) {
      log.e("calendar provider failed: ${e.javaClass.simpleName}")
      fail(SyncProblem.CALENDAR_WRITE_FAILED)
    }

  // ---------------------------------------------------------------------------------------------
  // One run

  private enum class Kind {
    /** Absent, or missing from a complete fetch: verified delete of the mapped row. */
    DELETE,

    /** An extra row of a live, active uuid found by the adoption scan. */
    DUPLICATE,

    /**
     * An unmapped marked row of a live, in-window event that its active calendar's filter
     * excludes, found by the adoption scan (e.g. after a reinstall or lost mappings).
     */
    LEFTOVER,

    /**
     * A row an earlier run deleted that is back (`deleted = 0`, still ours): Google's sync adapter
     * restored it before uploading the delete (device test 0.2.1). Verified delete again.
     */
    REDELETE,

    /** Mapped row with a different hash. */
    UPDATE,

    /** Unmapped uuid, marked row found by the adoption scan: verified update of that row. */
    ADOPT,
    INSERT,
  }

  /** One planned calendar operation with what the engine must record when it succeeds. */
  private class Planned(
    val op: CalendarOp,
    val kind: Kind,
    val write: Desired.Write? = null,
    val hash: String? = null,
    /** Rows already tried for this uuid; adoption never picks them again. */
    val excluded: Set<Long> = emptySet(),
  ) {
    val uuid: String
      get() = op.uuid

    val eventId: Long?
      get() =
        when (op) {
          is CalendarOp.Insert -> null
          is CalendarOp.Update -> op.eventId
          is CalendarOp.Delete -> op.eventId
        }
  }

  private class Counter {
    var added = 0
    var updated = 0
    var deleted = 0
    var adopted = 0
    var rejected = 0
  }

  /**
   * One selected TimeTree calendar during a run. [filter] is its active filter (null: every event);
   * [filterHash] is stored in its cursor once its writes succeeded.
   */
  private class CalendarWork(val id: Long, val cursor: SyncCursor, val filter: EventFilter?) {
    val filterHash: String? = filter?.fingerprint()
    var paused = cursor.state == CalendarState.PAUSED
    var mode: SyncMode? = null
    var stored: List<Snapshot> = emptyList()
    var storedEvents: Map<String, TtEvent> = emptyMap()
    var labels: List<TtLabel> = emptyList()
    var fetched: List<FetchedEvent> = emptyList()
    var fetchedUuids: Set<String> = emptySet()

    /** The high-water mark to store once this calendar's writes succeeded. */
    var highWater: Long? = null

    /** The size of the feed, if this run read all of it (FULL). */
    var fullPages: Int? = null

    /** Events of this fetch that did not decode: their state is unknown, so they are left alone. */
    var skippedUuids: Set<String> = emptySet()

    /**
     * A FULL fetch that may delete copies of missing uuids: the whole feed was read, and every event
     * it skipped had a readable uuid (DESIGN.md step 5, "complete fetch"). An INCREMENTAL fetch never
     * proves absence, even if it happened to read the whole feed.
     */
    var provesAbsence = false

    /** This run held back a mass delete ([Run.holdMassDelete]). */
    var deletesHeld = false

    /**
     * Stored snapshots the complete fetch does not replace: skipped uuids, and tombstones of
     * occurrences whose series is still there (live, or unreadable). A tombstone whose series is
     * gone serves no override rule any more, so it is dropped (review 0.2.1, NIT-2).
     */
    fun storedToKeep(): List<Snapshot> =
      stored.filter {
        val event = storedEvents[it.uuid]
        it.uuid !in fetchedUuids &&
          event != null &&
          (it.uuid in skippedUuids ||
            it.deactivated && event.parentRefs.any { parent -> parent in fetchedUuids || parent in skippedUuids })
      }
    var result: MappingResult = MappingResult(emptyMap(), emptyMap())
    var affected: Set<String> = emptySet()
    val duplicates = mutableListOf<Planned>()
    val leftovers = mutableListOf<Planned>()
    val returned = mutableListOf<Planned>()
    val counter = Counter()

    val desired: Map<String, Desired>
      get() = result.desired

    fun counts(): SyncCounts =
      SyncCounts(
        added = counter.added,
        updated = counter.updated,
        deleted = counter.deleted,
        adopted = counter.adopted,
        skipped = result.stats.skipped,
        rejected = counter.rejected,
        unmatchedOverrides = result.stats.unmatchedOverrides,
        droppedRecurrenceLines = result.stats.droppedRecurrenceLines,
        filtered = result.stats.filtered,
      )
  }

  private inner class Run(private val request: SyncRequest) {
    private val startedAt = nowMillis()
    private val works = mutableListOf<CalendarWork>()
    private val warnings = mutableSetOf<SyncWarning>()
    private var uploads = true
    private var unmanagedMarked = 0
    private var notUploaded = 0

    /** The preconditions passed, so the run ends with the upload check ([checkUploads]). */
    private var uploadCheckDue = false
    private lateinit var target: TargetCalendar
    private lateinit var zoneId: ZoneId
    private lateinit var today: LocalDate
    private var windowStart = 0L

    /** Every mapping by uuid, kept equal to the table as chunks commit. */
    private val mappings = HashMap<String, Mapping>()

    /** uuid → `_ID`s of the marked rows in the target (ascending), once the scan ran. */
    private var index: Map<String, List<Long>>? = null

    /** `_ID`s already adopted or being deleted as duplicates in this run. */
    private val claimed = HashSet<Long>()

    /** Step 6a: the first `deleted_at` of each row deleted again, so the watch doesn't renew. */
    private val redeleteSince = HashMap<Long, Long>()

    /** Uuids that are [Desired.Write] in an active calendar: the only ones the scan may adopt. */
    private val activeWrites = HashMap<String, CalendarWork>()

    /**
     * Live, in-window uuids that the filter of their active calendar excludes: the only ones whose
     * unmapped rows the scan may delete as leftovers.
     */
    private val activeFiltered = HashMap<String, CalendarWork>()

    suspend fun execute(): SyncReport {
      log.i("run started id=${request.runId} trigger=${request.trigger} forceFull=${request.forceFull}")
      val failure =
        try {
          body()
          null
        } catch (e: CancellationException) {
          throw e
        } catch (e: RunFailed) {
          e.failure
        } catch (e: TtError) {
          log.e("run failed: unexpected ${e.javaClass.simpleName}", e)
          SyncFailure(SyncProblem.INTERNAL)
        } catch (e: Exception) {
          log.e("run failed: ${e.javaClass.simpleName}")
          SyncFailure(SyncProblem.INTERNAL)
        }
      if (uploadCheckDue) checkUploads()
      val report = report(failure)
      try {
        status.recordRun(report)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        log.w("could not save the report: ${e.javaClass.simpleName}")
      }
      val c = report.counts
      log.i(
        "run finished id=${request.runId} ok=${report.succeeded} problem=${failure?.problem} " +
          "written=${c.writes} added=${c.added} updated=${c.updated} deleted=${c.deleted} " +
          "skipped=${c.skipped} filtered=${c.filtered} adopted=${c.adopted} rejected=${c.rejected} " +
          "unmanaged=${c.unmanagedMarked} unmatched=${c.unmatchedOverrides} " +
          "paused=${report.calendars.count { it.state == CalendarState.PAUSED }} " +
          "warnings=${report.warnings.sorted().joinToString(",").ifEmpty { "none" }}"
      )
      return report
    }

    private suspend fun body() {
      // 1. Preconditions. Nothing is requested or written before they pass.
      val current = settings.current()
      val stored = current.target ?: fail(SyncProblem.NOT_CONFIGURED)
      val sources = current.sources.distinctBy { it.id }
      if (sources.isEmpty()) fail(SyncProblem.NOT_CONFIGURED)
      this.target = stored
      if (!gateway.hasPermission()) fail(SyncProblem.NO_CALENDAR_PERMISSION)
      val targetRow = calendarCall { gateway.calendar(stored.calendarId) }
      when (val check = stored.check(targetRow)) {
        TargetCheck.OK -> Unit
        TargetCheck.MISSING -> {
          if (targetRow != null) log.w("the target's _id holds another calendar now")
          fail(SyncProblem.TARGET_DELETED)
        }
        else -> {
          log.w("target check failed: $check")
          fail(SyncProblem.TARGET_UNAVAILABLE)
        }
      }
      // A target stored before 0.2.2 has no server id yet: keep it from now on (F5).
      val target = targetRow?.let(stored::withServerIdOf) ?: stored
      if (target != stored) {
        settings.setTarget(target, current.targetDisplayName ?: targetRow?.displayName.orEmpty())
        this.target = target
        log.i("target: server id stored")
      }
      if (target.serverId == null) log.w("target: the calendar has no server id; checked by _id and account only")
      if (credentials.load() == null) fail(SyncProblem.NOT_SIGNED_IN)
      status.current().rateLimitedUntil?.let { until ->
        // Compared before subtracting: a broken, very negative value would wrap around.
        val wait = if (until <= startedAt) 0 else until - startedAt
        when {
          wait <= 0 -> Unit
          // No back-off is that long (TtError.RateLimited.MAX_BACKOFF): the clock was ahead when
          // it was stored, or the value is broken. Waiting for it could stop syncing for good.
          wait > TtError.RateLimited.MAX_BACKOFF.inWholeMilliseconds -> {
            log.w("stored rate limit is too far ahead; ignored")
            status.setRateLimitedUntil(null)
          }
          else -> fail(SyncFailure(SyncProblem.RATE_LIMITED, retryAfterMillis = wait))
        }
      }
      uploads =
        try {
          gateway.googleSyncState(target).uploads
        } catch (e: Exception) {
          log.w("sync state unknown: ${e.javaClass.simpleName}")
          true
        }
      if (!uploads) warnings += SyncWarning.GOOGLE_SYNC_OFF
      uploadCheckDue = true
      zoneId = zone()
      today = Instant.ofEpochMilli(startedAt).atZone(zoneId).toLocalDate()
      windowStart = EventMapper.windowStartMillis(today, zoneId)

      // 2. Crash check: a flag left over means the last run may have written without recording.
      val crashed = db.meta.get(MetaKeys.RUN_IN_PROGRESS) != null
      db.meta.put(MetaKeys.RUN_IN_PROGRESS, request.runId)
      val mapperChanged = db.meta.get(MetaKeys.MAPPER_VERSION) != EventMapper.VERSION.toString()
      if (crashed) log.w("previous run did not finish; adoption scan included")

      // 3. Fetch every calendar before writing anything.
      client.beginRun(request.runId)
      val ttCalendars = timeTree { client.calendars() }.associateBy { it.id }
      for (source in sources) {
        val work =
          CalendarWork(
            source.id,
            db.cursors.get(source.id) ?: SyncCursor(source.id, null, null, CalendarState.ACTIVE, null),
            source.activeFilter,
          )
        works += work
        when {
          work.paused -> log.i("calendar ${work.id} paused; skipped")
          ttCalendars[work.id]?.isActive != true -> pause(work, "missing or deactivated")
          else -> fetch(work, mapperChanged)
        }
      }
      if (works.any { it.paused }) warnings += SyncWarning.CALENDAR_PAUSED
      val active = works.filterNot { it.paused }

      // 4. Map, and work out the affected uuids. The mappings come first: the mapper needs to know
      // which copies reach into the window.
      db.mappings.all().associateByTo(mappings) { it.uuid }
      active.forEach { mapEvents(it) }
      for (work in active) {
        for ((uuid, desired) in work.desired) {
          if (desired is Desired.Write && uuid !in work.skippedUuids) activeWrites[uuid] = work
        }
      }
      for (work in active) {
        for (uuid in work.result.filtered) {
          if (uuid !in work.skippedUuids && uuid !in activeWrites) activeFiltered[uuid] = work
        }
      }
      active.forEach { it.affected = affected(it) }

      // 6a. Copies an earlier run deleted that are back (Google's sync adapter restored them).
      recheckDeleted(active)

      // 6. Adoption scan, before any write (step 6 builds the index the diff uses).
      val needsAdoption = active.any { w -> w.affected.any { w.desired[it] is Desired.Write && mappingFor(it) == null } }
      if (crashed || mappings.isEmpty() || needsAdoption || active.any { it.mode == SyncMode.FULL }) {
        scan(withDuplicates = true)
      }

      // 5 + 7. Diff and apply, one calendar after the other; 8. its cursor only after its writes.
      for (work in active) {
        val ops = holdMassDelete(work, plan(work))
        applyPlan(work, ops)
        commit(work)
      }

      // 8. Finish.
      db.transaction {
        db.meta.put(MetaKeys.MAPPER_VERSION, EventMapper.VERSION.toString())
        db.meta.put(MetaKeys.RUN_IN_PROGRESS, null)
      }
      if (works.sumOf { it.counter.added + it.counter.updated + it.counter.deleted } > 0) {
        requestUpload(target, uploads)
      }
      if (status.current().rateLimitedUntil != null) status.setRateLimitedUntil(null)
    }

    /**
     * Sets [notUploaded] and the [SyncWarning.NOT_UPLOADED] warning at the end of the run, also of
     * one that failed after the preconditions. At the end, copies this run deleted are gone and
     * copies it rewrote start a new grace period, so neither counts as stale (review fix1,
     * SHOULD-FIX-1).
     */
    private suspend fun checkUploads() {
      notUploaded = notUploaded(target) ?: 0
      if (notUploaded > 0 && uploads) warnings += SyncWarning.NOT_UPLOADED
    }

    /**
     * The upload signal (device test B2): copies the app wrote more than [UPLOAD_GRACE_MILLIS] ago
     * that are still `dirty`, i.e. Google's sync has not taken them. Null if unknown; it never fails
     * the run.
     */
    private suspend fun notUploaded(target: TargetCalendar): Int? {
      val cutoff = startedAt - UPLOAD_GRACE_MILLIS
      return try {
        val written =
          db.mappings.all().filter { m ->
            m.targetCalId == target.calendarId && m.writtenAt.let { it != null && it < cutoff }
          }
        if (written.isEmpty()) return 0
        val pending = gateway.pendingUploads(target, written.map { it.eventId })
        if (pending == null) log.d("upload state unknown: dirty not readable")
        else if (pending.isNotEmpty()) log.w("${pending.size} copies not uploaded after 30 min")
        pending?.size
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        log.w("upload state unknown: ${e.javaClass.simpleName}")
        null
      }
    }

    private fun pause(work: CalendarWork, reason: String) {
      work.paused = true
      work.mode = null
      db.cursors.put(work.cursor.copy(state = CalendarState.PAUSED))
      log.w("calendar ${work.id} paused: $reason; its copies are kept")
    }

    private suspend fun fetch(work: CalendarWork, mapperChanged: Boolean) {
      val stored = db.snapshots.forCalendar(work.id)
      val events = HashMap<String, TtEvent>()
      var undecodable = 0
      for (snapshot in stored) {
        try {
          events[snapshot.uuid] = TtEvent.fromJson(snapshot.json)
        } catch (e: TtError.ApiChanged) {
          undecodable++
        }
      }
      work.stored = stored
      work.storedEvents = events
      if (undecodable > 0) log.w("calendar ${work.id}: $undecodable snapshots unreadable; FULL")

      val cursor = work.cursor
      try {
        var fullReason = fullReason(cursor, mapperChanged, undecodable, work.filterHash)
        // INCREMENTAL reads the changes since the known high-water mark, minus an overlap for
        // changes committed with a slightly older timestamp. Deletions come as tombstones.
        val changes =
          if (fullReason != null) null
          else {
            // Not below 0: an empty calendar's mark is 0, and a negative since was never probed.
            val since =
              (checkNotNull(cursor.highWater) - TimeTreeApi.FEED_OVERLAP.inWholeMilliseconds).coerceAtLeast(0)
            timeTree { client.changes(work.id, since) }.also { if (it == null) fullReason = "change list unusable" }
          }
        val full = fullReason != null
        work.mode = if (full) SyncMode.FULL else SyncMode.INCREMENTAL
        val why =
          fullReason
            ?: "last FULL ${minutesSince(cursor.lastFullAt)} min ago, pages=${cursor.lastFullPages}"
        log.i("calendar ${work.id}: ${work.mode} ($why)")

        var labels = if (full) null else decodeLabels(cursor.labelsJson)
        val labelsFresh = labels == null
        if (labels == null) labels = timeTree { client.labels(work.id) }
        val result = changes ?: timeTree { client.events(work.id) }
        if (!labelsFresh) {
          val known = labels.mapTo(HashSet()) { it.id }
          val unknownLabel =
            result.events.any { val id = it.event.labelId; id != null && id !in known && !it.event.isDeactivated }
          if (unknownLabel) labels = timeTree { client.labels(work.id) }
        }
        work.labels = labels
        if (full) work.fullPages = result.feedPages
        work.fetched = result.events
        work.fetchedUuids = result.events.mapTo(HashSet()) { it.event.uuid }
        work.skippedUuids = result.skippedUuids
        work.provesAbsence =
          full && result.complete && result.skippedMalformed <= result.skippedUuids.size
        val newest = result.highWater
        work.highWater =
          when {
            // INCREMENTAL: never lower than before, since it reads only the changes from the last
            // mark on.
            !full -> maxOf(cursor.highWater ?: 0L, newest ?: Long.MIN_VALUE)
            // FULL: the newest change of the whole feed (0 for an empty one).
            work.provesAbsence -> newest ?: 0L
            // A FULL that cannot delete by absence must not move the mark forward either: the
            // deletions since the old mark are then known only to the change list, which the next
            // INCREMENTAL reads from the mark on (review 2026-11, F14).
            else -> minOf(cursor.highWater ?: Long.MAX_VALUE, newest ?: 0L)
          }
        log.i(
          "calendar ${work.id}: ${work.mode} fetched=${result.events.size} pages=${result.pages} " +
            "complete=${result.complete}"
        )
        if (result.skippedMalformed > 0) {
          log.w(
            "calendar ${work.id}: ${result.skippedMalformed} unreadable events left alone " +
              "(${result.skippedUuids.size} with uuid); deletes of missing uuids: ${work.provesAbsence}"
          )
        }
      } catch (e: TtError.AccessLost) {
        pause(work, "access lost (http=${e.httpStatus})")
      }
    }

    /**
     * Why this calendar's run must be FULL, or null for INCREMENTAL (DESIGN.md section 5).
     * INCREMENTAL reads the list of changes, which also holds deleted events. A FULL at least every
     * [FULL_INTERVAL_MILLIS] is the safety net: it notices whatever that list no longer shows (a
     * tombstone TimeTree dropped while the phone was off, say) and brings back copies that were
     * deleted in Google (it checks that each copy still exists and is ours).
     * A changed filter needs a FULL too: copies of events it now excludes must go, and events it now
     * includes must be copied, whether they changed or not.
     */
    private fun fullReason(
      cursor: SyncCursor,
      mapperChanged: Boolean,
      undecodable: Int,
      filterHash: String?,
    ): String? {
      val lastFull = cursor.lastFullAt
      val pages = cursor.lastFullPages
      return when {
        request.forceFull -> "forced"
        mapperChanged -> "mapper version changed"
        undecodable > 0 -> "unreadable snapshots"
        cursor.highWater == null -> "no high-water mark"
        lastFull == null || pages == null -> "no FULL recorded"
        cursor.filterHash != filterHash -> "filter changed"
        startedAt < lastFull -> "clock went back"
        startedAt - lastFull >= FULL_INTERVAL_MILLIS ->
          "last FULL ${minutesSince(lastFull)} min ago, pages=$pages"
        else -> null
      }
    }

    private fun minutesSince(at: Long?): Long? = at?.let { (startedAt - it) / 60_000 }

    private fun mapEvents(work: CalendarWork) {
      val fetchedEvents = work.fetched.map { it.event }
      val events: Collection<TtEvent> =
        if (work.provesAbsence) {
          // The complete fetch replaces the stored events, except stored tombstones (in case the
          // fetch leaves deleted occurrences out) and the last good state of unreadable events.
          fetchedEvents + work.storedToKeep().mapNotNull { work.storedEvents[it.uuid] }
        } else {
          // INCREMENTAL, or a FULL that skipped events without a uuid: what is not fetched stays as stored.
          (work.storedEvents + fetchedEvents.associateBy { it.uuid }).values
        }
      // An event that was moved or shortened to end before the window still has its old copy in
      // the window. It is mapped once more, so that the copy follows it there (review 2026-11, F2).
      val copiesInWindow =
        mappings.values
          .filter { it.ttCalId == work.id && it.targetCalId == target.calendarId && it.endAt >= windowStart }
          .mapTo(HashSet()) { it.uuid }
      val result =
        try {
          mapper.map(
            MappingInput(events, work.labels.associateBy { it.id }, today, zoneId, work.filter, copiesInWindow)
          )
        } catch (e: Exception) {
          log.e("calendar ${work.id}: mapper failed: ${e.javaClass.simpleName}")
          fail(SyncProblem.INTERNAL)
        }
      if (result.stats.unjudged > 0) {
        log.w("calendar ${work.id}: ${result.stats.unjudged} events without readable attendees left alone")
      }
      // A tombstone means "deleted" whatever its times say (they may be 0, i.e. unknown). The diff
      // deletes its copy only if the mapping's own end_at is in the window.
      val tombstones =
        events.filter { it.isDeactivated && it.uuid !in result.desired && Marker.isValidUuid(it.uuid) }
      work.result =
        if (tombstones.isEmpty()) result
        else result.copy(desired = result.desired + tombstones.associate { it.uuid to Desired.Absent })
    }

    /** The uuids the diff looks at. Unreadable events (skipped by the client) are never among them. */
    private fun affected(work: CalendarWork): Set<String> {
      if (work.mode == SyncMode.FULL) return work.desired.keys - work.skippedUuids
      val changed = work.fetchedUuids
      val parents = changed.mapNotNull { work.result.parentOf[it] }
      // Normally empty; after lost mappings it lets the run adopt instead of waiting for a FULL.
      val unmapped = work.desired.filter { (uuid, d) -> d is Desired.Write && mappingFor(uuid) == null }.keys
      return changed + parents + unmapped - work.skippedUuids
    }

    /** A mapping counts only for the current target; one for an old target is never used. */
    private fun mappingFor(uuid: String): Mapping? =
      mappings[uuid]?.takeIf { it.targetCalId == target.calendarId }

    /**
     * Step 6a. Rows that earlier runs deleted ([PendingDelete]) are checked by `_ID`:
     * - gone (Google purged it), in another calendar, or no longer ours: forgotten, never touched;
     * - still `deleted = 1`: kept, Google hasn't uploaded the delete yet;
     * - back (`deleted = 0` and ours): Google's sync adapter restored it before uploading the
     *   delete (device test 0.2.1). Deleted again ([Kind.REDELETE]), unless its uuid is wanted now
     *   ([activeWrites]) or a mapping points at the row: then it is forgotten and the diff or the
     *   adoption scan decides.
     *
     * Only entries of the current target and of active calendars are checked (a paused calendar's
     * rows are never touched); older than [PENDING_DELETE_MAX_AGE_MILLIS], or for another target,
     * they are forgotten.
     */
    private suspend fun recheckDeleted(active: List<CalendarWork>) {
      val entries = db.pendingDeletes.all()
      if (entries.isEmpty()) return
      val byId = active.associateBy { it.id }
      val (current, dropped) =
        entries.partition { it.targetCalId == target.calendarId && startedAt - it.deletedAt < PENDING_DELETE_MAX_AGE_MILLIS }
      val due = current.filter { it.ttCalId in byId }
      val forget = dropped.mapTo(mutableListOf()) { it.eventId }
      if (due.isNotEmpty()) {
        val checks = calendarCall { gateway.checkRows(target, due.associate { it.eventId to it.uuid }) }
        val mapped = mappings.values.mapTo(HashSet()) { it.eventId }
        for (entry in due) {
          val work = byId.getValue(entry.ttCalId)
          when (checks[entry.eventId] ?: RowCheck.MISSING) {
            RowCheck.DELETED -> Unit
            RowCheck.OURS ->
              when {
                entry.uuid in activeWrites || entry.eventId in mapped -> forget += entry.eventId
                // Unreadable this run: its state is unknown, so wait (review 0.2.1-2, NIT-3).
                entry.uuid in work.skippedUuids -> Unit
                else -> {
                  log.w("copy of ${TtLog.short(entry.uuid)} is back after its delete; deleting it again")
                  work.returned += Planned(CalendarOp.Delete(entry.eventId, entry.uuid), Kind.REDELETE)
                  claimed += entry.eventId
                  redeleteSince[entry.eventId] = entry.deletedAt
                }
              }
            else -> forget += entry.eventId
          }
        }
      }
      db.pendingDeletes.delete(forget)
      log.d("deleted copies: watched=${entries.size - forget.size} forgotten=${forget.size}")
    }

    /**
     * Step 6. Builds the marker index. Only adopts (through the diff) and, with [withDuplicates],
     * plans verified deletes of extra rows of uuids that are live and active, and of unmapped
     * leftovers: rows of live, in-window events that their active calendar's filter excludes
     * (approved for 0.2.0; with a mapping, the diff deletes the copy instead). Every other marked
     * row (unknown, dead, paused or out of window) is only counted.
     */
    private suspend fun scan(withDuplicates: Boolean) {
      val rows = calendarCall { gateway.findMarkedRows(target) }
      val byUuid = rows.groupBy({ it.uuid }, { it.eventId }).mapValues { (_, ids) -> ids.sorted() }
      index = byUuid
      val leftoverIds = HashSet<Long>()
      var duplicates = 0
      if (withDuplicates) {
        for ((uuid, ids) in byUuid) {
          if (ids.size < 2) continue
          val work = activeWrites[uuid] ?: continue
          val mapped = mappingFor(uuid)?.eventId
          val keeper = if (mapped != null && mapped in ids) mapped else ids.first()
          for (extra in ids) {
            if (extra == keeper) continue
            work.duplicates += Planned(CalendarOp.Delete(extra, uuid), Kind.DUPLICATE)
            claimed += extra
            duplicates++
          }
        }
        for ((uuid, ids) in byUuid) {
          val work = activeFiltered[uuid] ?: continue
          // A mapped copy is the diff's (Absent, in window); only the other rows are leftovers.
          val mapped = mappingFor(uuid)?.eventId
          for (id in ids) {
            if (id == mapped || id in claimed) continue
            work.leftovers += Planned(CalendarOp.Delete(id, uuid), Kind.LEFTOVER)
            claimed += id
            leftoverIds += id
          }
        }
      }
      // A mapped in-window row of an active calendar is the diff's even if its uuid is not written:
      // the diff deletes it (Absent, or missing from a complete fetch), so it is not left alone.
      val active = works.filterNot { it.paused }.mapTo(HashSet()) { it.id }
      fun managedByDiff(row: MarkedRow): Boolean =
        mappingFor(row.uuid)?.let { it.eventId == row.eventId && it.endAt >= windowStart && it.ttCalId in active } == true
      unmanagedMarked =
        rows.count {
          it.uuid !in activeWrites && it.eventId !in leftoverIds && it.eventId !in claimed && !managedByDiff(it)
        }
      log.i(
        "adoption scan: marked=${rows.size} unmanaged=$unmanagedMarked duplicates=$duplicates " +
          "leftovers=${leftoverIds.size}"
      )
    }

    private suspend fun adoptOrInsert(write: Desired.Write, hash: String, excluded: Set<Long>): Planned {
      if (index == null) scan(withDuplicates = false)
      val candidate = index?.get(write.spec.uuid)?.firstOrNull { it !in excluded && it !in claimed }
      return if (candidate != null) {
        claimed += candidate
        Planned(CalendarOp.Update(candidate, write.spec), Kind.ADOPT, write, hash, excluded)
      } else {
        Planned(CalendarOp.Insert(write.spec), Kind.INSERT, write, hash, excluded)
      }
    }

    /** Step 5, the only place a sync deletes. */
    private suspend fun plan(work: CalendarWork): List<Planned> {
      val ops = ArrayList<Planned>(work.duplicates + work.leftovers + work.returned)
      val planned = HashSet<String>()
      /** uuid → its mapped row, for the FULL check below. */
      val verify = LinkedHashMap<String, Long>()
      val endAtFixes = mutableListOf<Mapping>()
      for (uuid in work.affected.sorted()) {
        when (val desired = work.desired[uuid]) {
          null -> Unit // outside the window, and so is its copy if it has one: never touched
          is Desired.Write -> {
            val hash = SpecHash.of(desired.spec)
            val mapping = mappingFor(uuid)
            when {
              mapping == null -> ops += adoptOrInsert(desired, hash, emptySet())
              mapping.specHash != hash ->
                ops += Planned(CalendarOp.Update(mapping.eventId, desired.spec), Kind.UPDATE, desired, hash)
              else -> {
                if (work.mode == SyncMode.FULL) verify[uuid] = mapping.eventId
                if (mapping.endAt != desired.endAt) endAtFixes += mapping.copy(ttCalId = work.id, endAt = desired.endAt)
              }
            }
            planned += uuid
          }
          Desired.Absent -> {
            val mapping = mappingFor(uuid)
            if (mapping != null && mapping.endAt >= windowStart) {
              ops += Planned(CalendarOp.Delete(mapping.eventId, uuid), Kind.DELETE)
            }
            planned += uuid
          }
        }
      }
      if (work.provesAbsence) {
        // A mapped uuid that the complete fetch no longer contains: deleted in TimeTree. Not for an
        // unreadable event (skipped by the client): its state is unknown.
        for (mapping in mappings.values.sortedBy { it.uuid }) {
          if (
            mapping.ttCalId == work.id &&
              mapping.targetCalId == target.calendarId &&
              mapping.uuid !in work.fetchedUuids &&
              mapping.uuid !in work.skippedUuids &&
              mapping.uuid !in planned &&
              mapping.uuid !in activeWrites &&
              mapping.endAt >= windowStart
          ) {
            ops += Planned(CalendarOp.Delete(mapping.eventId, mapping.uuid), Kind.DELETE)
          }
        }
      }
      if (work.mode == SyncMode.FULL) {
        // Each in-window copy must still exist and be ours; otherwise re-adopt or re-insert it.
        val checks = checkMapped(verify)
        for ((uuid, eventId) in verify) {
          val check = checks[uuid] ?: RowCheck.MISSING
          if (check == RowCheck.OURS) continue
          log.i("copy of ${TtLog.short(uuid)} is $check; re-adopting or re-inserting")
          val write = work.desired[uuid] as Desired.Write
          ops += adoptOrInsert(write, SpecHash.of(write.spec), setOf(eventId))
        }
      }
      if (endAtFixes.isNotEmpty()) record(endAtFixes, emptyList())
      return ops.sortedBy { it.kind.ordinal }
    }

    /**
     * R1 for mapped rows, by uuid. Two mappings can name the same `_ID` (one of them stale, after a
     * reset of the calendar storage), and the gateway takes one expected uuid per row, so such
     * rows are checked in separate calls (review 2026-11, F15).
     */
    private suspend fun checkMapped(rows: Map<String, Long>): Map<String, RowCheck> {
      val result = HashMap<String, RowCheck>()
      var rest = rows.toList()
      while (rest.isNotEmpty()) {
        val batch = LinkedHashMap<Long, String>()
        val later = mutableListOf<Pair<String, Long>>()
        for ((uuid, eventId) in rest) {
          if (eventId in batch) later += uuid to eventId else batch[eventId] = uuid
        }
        val checks = calendarCall { gateway.checkRows(target, batch) }
        for ((eventId, uuid) in batch) result[uuid] = checks[eventId] ?: RowCheck.MISSING
        rest = later
      }
      return result
    }

    /**
     * The guard against a mass delete (review 2026-11, F8). A FULL run that would delete at least
     * [MASS_DELETE_MIN] copies of a calendar and more than half of those it has in the window is
     * more likely a wrong answer from TimeTree (an empty or cut-off list, a changed field) than
     * what the user did. The first such run deletes nothing for that calendar and asks for another
     * FULL; only if that one, within [MASS_DELETE_CONFIRM_MILLIS], comes to the same result are the
     * copies deleted. A changed filter is the user's own doing and is not held back, and neither
     * are INCREMENTAL runs, which delete only what the change list names.
     *
     * @return the operations to apply now
     */
    private fun holdMassDelete(work: CalendarWork, ops: List<Planned>): List<Planned> {
      val key = MetaKeys.massDeleteSeen(work.id)
      val seenAt = db.meta.get(key)?.toLongOrNull()
      val deletes = ops.count { it.kind == Kind.DELETE }
      val copies =
        mappings.values.count {
          it.ttCalId == work.id && it.targetCalId == target.calendarId && it.endAt >= windowStart
        }
      val suspicious =
        work.mode == SyncMode.FULL &&
          work.cursor.filterHash == work.filterHash &&
          deletes >= MASS_DELETE_MIN &&
          deletes * 2L > copies
      if (!suspicious) {
        if (seenAt != null) db.meta.put(key, null)
        return ops
      }
      val age = seenAt?.let { startedAt - it }
      if (age != null && age in 0 until MASS_DELETE_MIN_GAP_MILLIS) {
        // Too soon to count as a second opinion (a run queued right behind the first one would
        // see the same glitch): still held, and the first time stays.
        log.w("calendar ${work.id}: $deletes of $copies copies to delete; still held")
        return hold(work, ops)
      }
      if (age != null && age in MASS_DELETE_MIN_GAP_MILLIS until MASS_DELETE_CONFIRM_MILLIS) {
        log.w("calendar ${work.id}: $deletes of $copies copies to delete, seen again; deleting them")
        db.meta.put(key, null)
        return ops
      }
      log.w("calendar ${work.id}: $deletes of $copies copies to delete; held until the next FULL agrees")
      db.meta.put(key, startedAt.toString())
      return hold(work, ops)
    }

    /** [ops] without the deletes. The high-water mark stays, as after a FULL that proves no absence. */
    private fun hold(work: CalendarWork, ops: List<Planned>): List<Planned> {
      work.deletesHeld = true
      work.highWater = work.cursor.highWater ?: work.highWater
      return ops.filter { it.kind != Kind.DELETE }
    }

    /** Step 7: chunks of [CalendarGateway.MAX_BATCH], each chunk's mappings in one transaction. */
    private suspend fun applyPlan(work: CalendarWork, ops: List<Planned>) {
      var queue = ops
      var round = 0
      while (queue.isNotEmpty()) {
        if (++round > MAX_ROUNDS) {
          log.e("calendar ${work.id}: operations still rejected after $MAX_ROUNDS rounds")
          fail(SyncProblem.CALENDAR_WRITE_FAILED)
        }
        val retry = mutableListOf<Planned>()
        for (chunk in queue.chunked(CalendarGateway.MAX_BATCH)) {
          val results = applyOps(target, chunk.map { it.op })
          recordResults(work, chunk, results, retry)
        }
        // R1 refusals go back through adopt-or-insert, never to the refused row again.
        queue = retry.map { adoptOrInsert(it.write!!, it.hash!!, it.excluded + it.eventId!!) }
      }
    }

    private fun recordResults(
      work: CalendarWork,
      chunk: List<Planned>,
      results: List<OpResult>,
      retry: MutableList<Planned>,
    ) {
      val upserts = mutableListOf<Mapping>()
      val removed = mutableListOf<String>()
      val deleted = mutableListOf<PendingDelete>()
      fun forget(p: Planned) {
        if (mappings[p.uuid]?.eventId == p.eventId) removed += p.uuid
      }
      fun watch(p: Planned) {
        val eventId = p.eventId!!
        deleted += PendingDelete(eventId, p.uuid, work.id, target.calendarId, redeleteSince[eventId] ?: nowMillis())
      }
      fun remember(p: Planned, eventId: Long) {
        upserts +=
          Mapping(p.uuid, work.id, target.calendarId, eventId, p.hash!!, p.write!!.endAt, nowMillis())
      }
      for ((p, result) in chunk.zip(results)) {
        when (result) {
          is OpResult.Inserted -> {
            remember(p, result.eventId)
            work.counter.added++
          }
          OpResult.Updated -> {
            remember(p, p.eventId!!)
            work.counter.updated++
            if (p.kind == Kind.ADOPT) work.counter.adopted++
          }
          OpResult.Deleted -> {
            if (p.kind == Kind.DELETE) forget(p)
            // Watched until the row is gone (step 6a); a duplicate needs no watch, since the scan
            // finds an extra row of a live uuid again.
            if (p.kind != Kind.DUPLICATE) watch(p)
            work.counter.deleted++
          }
          is OpResult.Rejected -> {
            log.w("${p.kind} of ${TtLog.short(p.uuid)} refused: ${result.check}")
            when (p.kind) {
              Kind.UPDATE,
              Kind.ADOPT -> {
                work.counter.rejected++
                forget(p)
                retry += p
              }
              Kind.DELETE -> {
                if (!result.check.isGone()) work.counter.rejected++
                // Already deleted (by the user in Google, or before a crash): Google's sync may
                // still put it back, so it is watched too (review 0.2.1-2, NIT-2).
                if (result.check == RowCheck.DELETED) watch(p)
                forget(p)
              }
              Kind.LEFTOVER,
              Kind.REDELETE -> {
                if (!result.check.isGone()) work.counter.rejected++
                if (result.check == RowCheck.DELETED) watch(p)
              }
              Kind.DUPLICATE -> if (!result.check.isGone()) work.counter.rejected++
              Kind.INSERT -> error("an insert cannot be refused")
            }
          }
        }
      }
      record(upserts, removed, deleted)
    }

    private fun record(upserts: List<Mapping>, removed: List<String>, deleted: List<PendingDelete> = emptyList()) {
      if (upserts.isEmpty() && removed.isEmpty() && deleted.isEmpty()) return
      db.transaction {
        db.mappings.delete(removed)
        db.mappings.upsert(upserts)
        db.pendingDeletes.upsert(deleted)
      }
      removed.forEach { mappings.remove(it) }
      upserts.forEach { mappings[it.uuid] = it }
    }

    /** Step 8 for one calendar: its writes all succeeded, so snapshots and cursor may move. */
    private fun commit(work: CalendarWork) {
      val keep = work.fetched.filter { it.event.isStorable() }.map { it.toSnapshot(work.id) }
      val drop = work.fetched.filterNot { it.event.isStorable() }.map { it.event.uuid }
      db.transaction {
        // Not with held deletes: the read that would delete them is not trusted yet, so it must
        // not replace the snapshots either.
        if (work.provesAbsence && !work.deletesHeld) {
          db.snapshots.deleteCalendar(work.id)
          db.snapshots.upsert(work.storedToKeep() + keep)
        } else {
          db.snapshots.delete(drop)
          db.snapshots.upsert(keep)
        }
        db.cursors.put(
          SyncCursor(
            ttCalId = work.id,
            highWater = work.highWater,
            lastFullAt =
              when {
                // Held deletes need a second FULL to confirm them: the next run is one.
                work.deletesHeld -> null
                work.mode == SyncMode.FULL -> startedAt
                else -> work.cursor.lastFullAt
              },
            state = CalendarState.ACTIVE,
            labelsJson = TtJson.encodeToString(LABELS, work.labels),
            lastFullPages = work.fullPages ?: work.cursor.lastFullPages,
            // Only now: a run that failed before this point keeps the old hash and runs FULL again.
            filterHash = work.filterHash,
          )
        )
      }
      val c = work.counter
      log.i(
        "calendar ${work.id}: done added=${c.added} updated=${c.updated} deleted=${c.deleted} " +
          "adopted=${c.adopted} rejected=${c.rejected}"
      )
    }

    private fun report(failure: SyncFailure?): SyncReport {
      val calendars =
        works.map {
          CalendarReport(
            ttCalId = it.id,
            mode = if (it.paused) null else it.mode,
            state = if (it.paused) CalendarState.PAUSED else CalendarState.ACTIVE,
            counts = it.counts(),
          )
        }
      val totals = calendars.fold(SyncCounts(unmanagedMarked = unmanagedMarked)) { sum, c -> sum + c.counts }
      return SyncReport(
        runId = request.runId,
        trigger = request.trigger,
        startedAt = startedAt,
        finishedAt = nowMillis(),
        failure = failure,
        counts = totals,
        calendars = calendars,
        warnings = warnings.toSet(),
        notUploaded = notUploaded,
      )
    }

    private suspend fun <T> timeTree(block: suspend () -> T): T =
      try {
        block()
      } catch (e: TtError.AccessLost) {
        throw e
      } catch (e: TtError.RateLimited) {
        val backoff = e.backoff.inWholeMilliseconds
        status.setRateLimitedUntil(nowMillis() + backoff)
        log.w("rate limited; next try in ${backoff / 60_000} min")
        fail(SyncFailure(SyncProblem.RATE_LIMITED, retryAfterMillis = backoff))
      } catch (e: TtError) {
        log.w("TimeTree request failed", e)
        fail(failureOf(e))
      }
  }

  private class RunFailed(val failure: SyncFailure) : Exception(failure.problem.name)

  companion object {
    /** The process-wide lock: runs and user actions never overlap. */
    private val LOCK = Mutex()

    /** Runs between FULLs are INCREMENTAL; a FULL comes at least this often. */
    val FULL_INTERVAL_MILLIS: Long = 6.hours.inWholeMilliseconds

    /** How long a deleted copy is watched for a restore by Google's sync (step 6a). */
    val PENDING_DELETE_MAX_AGE_MILLIS: Long = 30.days.inWholeMilliseconds

    /** A copy still `dirty` this long after the app wrote it counts as not uploaded. */
    val UPLOAD_GRACE_MILLIS: Long = 30.minutes.inWholeMilliseconds

    /** [Run.holdMassDelete]: fewer deletes than this are never held back. */
    const val MASS_DELETE_MIN = 10

    /** [Run.holdMassDelete]: how long a held mass delete waits for the FULL that confirms it. */
    val MASS_DELETE_CONFIRM_MILLIS: Long = 1.days.inWholeMilliseconds

    /** A FULL sooner than this after the hold confirms nothing. */
    const val MASS_DELETE_MIN_GAP_MILLIS: Long = 10 * 60_000L

    /**
     * Rounds of adopt-or-insert after R1 refusals. Each refusal excludes that row, and an insert is
     * never refused, so two rounds normally suffice; more means something is badly wrong.
     */
    const val MAX_ROUNDS = 4

    private val LABELS = ListSerializer(TtLabel.serializer())

    private fun fail(problem: SyncProblem): Nothing = throw RunFailed(SyncFailure(problem))

    private fun fail(failure: SyncFailure): Nothing = throw RunFailed(failure)

    private fun RowCheck.isGone(): Boolean = this == RowCheck.MISSING || this == RowCheck.DELETED

    fun failureOf(error: TtError): SyncFailure =
      when (error) {
        is TtError.AuthRejected -> SyncFailure(SyncProblem.AUTH_REJECTED, code = error.code)
        is TtError.LoginRequired -> SyncFailure(SyncProblem.NOT_SIGNED_IN)
        is TtError.RateLimited ->
          SyncFailure(SyncProblem.RATE_LIMITED, retryAfterMillis = error.backoff.inWholeMilliseconds)
        is TtError.Transient -> SyncFailure(SyncProblem.NETWORK, code = error.httpStatus)
        is TtError.ApiChanged -> SyncFailure(SyncProblem.API_CHANGED)
        is TtError.AccessLost -> SyncFailure(SyncProblem.INTERNAL, code = error.httpStatus)
      }

    /** Cached labels, or null if there are none or they no longer decode (then they are refetched). */
    private fun decodeLabels(json: String?): List<TtLabel>? =
      json?.let {
        try {
          TtJson.decodeFromString(LABELS, it)
        } catch (e: SerializationException) {
          null
        } catch (e: IllegalArgumentException) {
          null
        }
      }

    /**
     * Step 3: live events are stored, and so are tombstones of occurrences (the override rules need
     * them); every other deleted event, birthday and memo is dropped.
     */
    private fun TtEvent.isStorable(): Boolean =
      if (isDeactivated) isOverride else !isBirthday && !isMemo

    private fun FetchedEvent.toSnapshot(ttCalId: Long) =
      Snapshot(
        uuid = event.uuid,
        ttCalId = ttCalId,
        updatedAt = event.updatedAt,
        deactivated = event.isDeactivated,
        json = json.toString(),
      )
  }
}
