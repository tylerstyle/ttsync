package de.tergelius.ttsync.sync

import de.tergelius.ttsync.TtLog
import de.tergelius.ttsync.calendar.BatchResult
import de.tergelius.ttsync.calendar.CalendarGateway
import de.tergelius.ttsync.calendar.CalendarOp
import de.tergelius.ttsync.calendar.OpResult
import de.tergelius.ttsync.calendar.RowCheck
import de.tergelius.ttsync.calendar.TargetCalendar
import de.tergelius.ttsync.calendar.TargetCheck
import de.tergelius.ttsync.calendar.TargetUnavailableException
import de.tergelius.ttsync.mapping.Desired
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
 * - **Safety.** Writes go only through [CalendarGateway], whose R1–R5 rules hold; only rows in the
 *   target calendar that carry the marker of the expected uuid are ever updated or deleted. During
 *   a sync, only the diff (step 5) deletes, and only in-window mappings of active calendars. The
 *   adoption scan only adopts (and removes duplicates of live, active uuids).
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
        if (stopSyncing) stopSyncing() else db.mappings.clear()
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
      // A manual sync request would override the user's sync switches, so only ask while they are
      // not known to be off.
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

  /** One selected TimeTree calendar during a run. */
  private class CalendarWork(val id: Long, val cursor: SyncCursor) {
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

    /** Stored snapshots the complete fetch does not replace: tombstones, and skipped uuids. */
    fun storedToKeep(): List<Snapshot> =
      stored.filter {
        it.uuid !in fetchedUuids && it.uuid in storedEvents && (it.deactivated || it.uuid in skippedUuids)
      }
    var result: MappingResult = MappingResult(emptyMap(), emptyMap())
    var affected: Set<String> = emptySet()
    val duplicates = mutableListOf<Planned>()
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

    /** Uuids that are [Desired.Write] in an active calendar: the only ones the scan may act on. */
    private val activeWrites = HashMap<String, CalendarWork>()

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
          "skipped=${c.skipped} adopted=${c.adopted} rejected=${c.rejected} " +
          "unmanaged=${c.unmanagedMarked} unmatched=${c.unmatchedOverrides} " +
          "paused=${report.calendars.count { it.state == CalendarState.PAUSED }} " +
          "warnings=${report.warnings.sorted().joinToString(",").ifEmpty { "none" }}"
      )
      return report
    }

    private suspend fun body() {
      // 1. Preconditions. Nothing is requested or written before they pass.
      val current = settings.current()
      val target = current.target ?: fail(SyncProblem.NOT_CONFIGURED)
      val sources = current.sources.distinctBy { it.id }
      if (sources.isEmpty()) fail(SyncProblem.NOT_CONFIGURED)
      this.target = target
      if (!gateway.hasPermission()) fail(SyncProblem.NO_CALENDAR_PERMISSION)
      when (val check = target.check(calendarCall { gateway.calendar(target.calendarId) })) {
        TargetCheck.OK -> Unit
        TargetCheck.MISSING -> fail(SyncProblem.TARGET_DELETED)
        else -> {
          log.w("target check failed: $check")
          fail(SyncProblem.TARGET_UNAVAILABLE)
        }
      }
      if (credentials.load() == null) fail(SyncProblem.NOT_SIGNED_IN)
      status.current().rateLimitedUntil?.let { until ->
        if (startedAt < until) {
          fail(SyncFailure(SyncProblem.RATE_LIMITED, retryAfterMillis = until - startedAt))
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

      // 4. Map, and work out the affected uuids.
      active.forEach { mapEvents(it) }
      db.mappings.all().associateByTo(mappings) { it.uuid }
      for (work in active) {
        for ((uuid, desired) in work.desired) {
          if (desired is Desired.Write && uuid !in work.skippedUuids) activeWrites[uuid] = work
        }
      }
      active.forEach { it.affected = affected(it) }

      // 6. Adoption scan, before any write (step 6 builds the index the diff uses).
      val needsAdoption = active.any { w -> w.affected.any { w.desired[it] is Desired.Write && mappingFor(it) == null } }
      if (crashed || mappings.isEmpty() || needsAdoption || active.any { it.mode == SyncMode.FULL }) {
        scan(withDuplicates = true)
      }

      // 5 + 7. Diff and apply, one calendar after the other; 8. its cursor only after its writes.
      for (work in active) {
        val ops = plan(work)
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
      val fullReason = fullReason(cursor, mapperChanged, undecodable)
      val full = fullReason != null
      work.mode = if (full) SyncMode.FULL else SyncMode.INCREMENTAL
      val why =
        fullReason
          ?: "last FULL ${minutesSince(cursor.lastFullAt)} min ago, pages=${cursor.lastFullPages}"
      log.i("calendar ${work.id}: ${work.mode} ($why)")

      try {
        var labels = if (full) null else decodeLabels(cursor.labelsJson)
        val labelsFresh = labels == null
        if (labels == null) labels = timeTree { client.labels(work.id) }
        // INCREMENTAL reads from the top of the feed (newest change first) down to the known
        // high-water mark, minus an overlap for changes committed with a slightly older timestamp.
        val stopAt =
          if (full) null else cursor.highWater?.minus(TimeTreeApi.FEED_OVERLAP.inWholeMilliseconds)
        val result = timeTree { client.events(work.id, stopAt) }
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
        // FULL: the newest change of the whole feed (0 for an empty one). INCREMENTAL: never lower
        // than before, since it re-reads only the top of the feed.
        work.highWater =
          if (full) result.highWater ?: 0L
          else maxOf(cursor.highWater ?: 0L, result.highWater ?: Long.MIN_VALUE)
        work.skippedUuids = result.skippedUuids
        work.provesAbsence =
          full && result.complete && result.skippedMalformed <= result.skippedUuids.size
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
     * Why this calendar's run must be FULL, or null for INCREMENTAL (DESIGN.md section 5). Deleted
     * events seem to vanish from the feed rather than come back as tombstones, so only a FULL sees
     * them: a small feed ([SMALL_FEED_PAGES] pages or fewer at its last FULL) is read completely on
     * every run, a larger one at least every [FULL_INTERVAL_MILLIS].
     */
    private fun fullReason(cursor: SyncCursor, mapperChanged: Boolean, undecodable: Int): String? {
      val lastFull = cursor.lastFullAt
      val pages = cursor.lastFullPages
      return when {
        request.forceFull -> "forced"
        mapperChanged -> "mapper version changed"
        undecodable > 0 -> "unreadable snapshots"
        cursor.highWater == null -> "no high-water mark"
        lastFull == null || pages == null -> "no FULL recorded"
        pages <= SMALL_FEED_PAGES -> "small feed, pages=$pages"
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
      val result =
        try {
          mapper.map(MappingInput(events, work.labels.associateBy { it.id }, today, zoneId))
        } catch (e: Exception) {
          log.e("calendar ${work.id}: mapper failed: ${e.javaClass.simpleName}")
          fail(SyncProblem.INTERNAL)
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
     * Step 6. Builds the marker index. Only adopts (through the diff) and, with [withDuplicates],
     * plans verified deletes of extra rows of uuids that are live and active. Every other marked row
     * (unknown, dead, paused or out of window) is only counted.
     */
    private suspend fun scan(withDuplicates: Boolean) {
      val rows = calendarCall { gateway.findMarkedRows(target) }
      val byUuid = rows.groupBy({ it.uuid }, { it.eventId }).mapValues { (_, ids) -> ids.sorted() }
      index = byUuid
      unmanagedMarked = rows.count { it.uuid !in activeWrites }
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
      }
      log.i("adoption scan: marked=${rows.size} unmanaged=$unmanagedMarked duplicates=$duplicates")
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
      val ops = ArrayList<Planned>(work.duplicates)
      val planned = HashSet<String>()
      val verify = LinkedHashMap<Long, String>()
      val endAtFixes = mutableListOf<Mapping>()
      for (uuid in work.affected.sorted()) {
        when (val desired = work.desired[uuid]) {
          null -> Unit // outside the window: never touched
          is Desired.Write -> {
            val hash = SpecHash.of(desired.spec)
            val mapping = mappingFor(uuid)
            when {
              mapping == null -> ops += adoptOrInsert(desired, hash, emptySet())
              mapping.specHash != hash ->
                ops += Planned(CalendarOp.Update(mapping.eventId, desired.spec), Kind.UPDATE, desired, hash)
              else -> {
                if (work.mode == SyncMode.FULL) verify[mapping.eventId] = uuid
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
        val checks = if (verify.isEmpty()) emptyMap() else calendarCall { gateway.checkRows(target, verify) }
        for ((eventId, uuid) in verify) {
          val check = checks[eventId] ?: RowCheck.MISSING
          if (check == RowCheck.OURS) continue
          log.i("copy of ${TtLog.short(uuid)} is $check; re-adopting or re-inserting")
          val write = work.desired[uuid] as Desired.Write
          ops += adoptOrInsert(write, SpecHash.of(write.spec), setOf(eventId))
        }
      }
      if (endAtFixes.isNotEmpty()) record(endAtFixes, emptyList())
      return ops.sortedBy { it.kind.ordinal }
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
      fun forget(p: Planned) {
        if (mappings[p.uuid]?.eventId == p.eventId) removed += p.uuid
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
                forget(p)
              }
              Kind.DUPLICATE -> if (!result.check.isGone()) work.counter.rejected++
              Kind.INSERT -> error("an insert cannot be refused")
            }
          }
        }
      }
      record(upserts, removed)
    }

    private fun record(upserts: List<Mapping>, removed: List<String>) {
      if (upserts.isEmpty() && removed.isEmpty()) return
      db.transaction {
        db.mappings.delete(removed)
        db.mappings.upsert(upserts)
      }
      removed.forEach { mappings.remove(it) }
      upserts.forEach { mappings[it.uuid] = it }
    }

    /** Step 8 for one calendar: its writes all succeeded, so snapshots and cursor may move. */
    private fun commit(work: CalendarWork) {
      val keep = work.fetched.filter { it.event.isStorable() }.map { it.toSnapshot(work.id) }
      val drop = work.fetched.filterNot { it.event.isStorable() }.map { it.event.uuid }
      db.transaction {
        if (work.provesAbsence) {
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
            lastFullAt = if (work.mode == SyncMode.FULL) startedAt else work.cursor.lastFullAt,
            state = CalendarState.ACTIVE,
            labelsJson = TtJson.encodeToString(LABELS, work.labels),
            lastFullPages = work.fullPages ?: work.cursor.lastFullPages,
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

    /**
     * A feed of at most this many pages at its last FULL gets a FULL on every run: a few requests,
     * and deletions (which do not show up in the feed) reach Google within the sync interval.
     */
    const val SMALL_FEED_PAGES = 3

    /** A larger feed gets INCREMENTAL runs, with a FULL at least this often. */
    val FULL_INTERVAL_MILLIS: Long = 6.hours.inWholeMilliseconds

    /** A copy still `dirty` this long after the app wrote it counts as not uploaded. */
    val UPLOAD_GRACE_MILLIS: Long = 30.minutes.inWholeMilliseconds

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
