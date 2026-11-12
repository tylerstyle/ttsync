package de.tergelius.ttsync.calendar

import de.tergelius.ttsync.mapping.EventSpec
import de.tergelius.ttsync.mapping.Marker
import java.util.TreeMap

/**
 * An in-memory calendar provider behind [CalendarGateway] for JVM tests. It enforces the same rules
 * as [ProviderCalendarGateway], with the same [GatewayRules]:
 * - R1 pre-read of every update/delete; refused operations are not applied ([OpResult.Rejected]);
 * - R2 precondition per update/delete, evaluated while the "batch" applies; one failure rolls the
 *   whole batch back and returns [BatchResult.Aborted];
 * - R3 target re-check on every [apply], inserts only into the target;
 * - R4 only the managed columns are ever written (anything else fails the test);
 * - deletes set `deleted = 1`, as an app delete on CalendarProvider2 does.
 *
 * Hooks simulate what happens on a real phone between the pre-read and the batch, provider failures,
 * and a process death right after `applyBatch`.
 */
class FakeCalendarGateway : CalendarGateway {
  /** One `Events` row; [values] holds the managed columns only. Immutable, so copies compare by value. */
  data class Row(
    val id: Long,
    val calendarId: Long,
    val deleted: Boolean,
    val values: Map<String, Any?>,
  ) {
    val description: String?
      get() = values[EventSpec.COL_DESCRIPTION] as String?

    val title: String?
      get() = values[EventSpec.COL_TITLE] as String?

    val markerUuid: String?
      get() = Marker.parse(description)
  }

  val calendars = LinkedHashMap<Long, CalendarInfo>()
  val rows = TreeMap<Long, Row>()
  private var nextId = 1000L

  var permission = true
  var syncState = GoogleSyncState(masterSync = true, calendarSync = true)
  var uploadRequests = 0
    private set

  /** Sizes of every [apply] call, in order. */
  val batchSizes = mutableListOf<Int>()

  /** Every operation that was actually written, in order. */
  val written = mutableListOf<CalendarOp>()

  /** Called with the operations of each [apply] before anything happens; may throw (provider failure). */
  var onApply: ((List<CalendarOp>) -> Unit)? = null

  /** Runs between the R1 pre-read and the batch: a concurrent edit by the user or Google's sync. */
  var betweenReadAndApply: ((List<CalendarOp>) -> Unit)? = null

  /** Runs after a batch was written; throwing here simulates a process death before the mappings. */
  var afterApply: ((List<CalendarOp>) -> Unit)? = null

  /** An OEM provider whose assert never passes: every update/delete aborts. */
  var brokenPrecondition = false

  fun addCalendar(info: CalendarInfo) {
    calendars[info.id] = info
  }

  /** Adds a row as the user or another app would; returns its `_ID`. */
  fun addRow(
    calendarId: Long,
    title: String?,
    description: String?,
    deleted: Boolean = false,
    id: Long = nextId++,
  ): Long {
    require(id !in rows) { "row $id exists" }
    nextId = maxOf(nextId, id + 1)
    rows[id] =
      Row(
        id,
        calendarId,
        deleted,
        mapOf(EventSpec.COL_TITLE to title, EventSpec.COL_DESCRIPTION to description),
      )
    return id
  }

  /** The user edits a row in their calendar app. */
  fun userEdit(id: Long, description: String?) {
    val row = rows.getValue(id)
    rows[id] = row.copy(values = row.values + (EventSpec.COL_DESCRIPTION to description))
  }

  /** The user deletes a row in their calendar app (`deleted = 1` until Google syncs). */
  fun userDelete(id: Long) {
    rows[id] = rows.getValue(id).copy(deleted = true)
  }

  /** Storage reset of the calendar provider: every row disappears. */
  fun wipeRows() = rows.clear()

  /** Continues `_ID`s after a wipe from [next] (an `_ID` may then be reused). */
  fun setNextId(next: Long) {
    nextId = next
  }

  /** Live rows of [calendarId] whose description ends with a valid marker, by marker uuid. */
  fun markedRows(calendarId: Long): List<Row> =
    rows.values.filter { it.calendarId == calendarId && !it.deleted && it.markerUuid != null }

  override fun hasPermission(): Boolean = permission

  private fun requirePermission() {
    if (!permission) throw SecurityException("calendar permission missing")
  }

  override suspend fun calendars(): List<CalendarInfo> {
    requirePermission()
    return calendars.values.toList()
  }

  override suspend fun calendar(calendarId: Long): CalendarInfo? {
    requirePermission()
    return calendars[calendarId]
  }

  override fun googleSyncState(target: TargetCalendar): GoogleSyncState = syncState

  override suspend fun countForeignEvents(calendarId: Long): Int {
    requirePermission()
    return rows.values.count { it.calendarId == calendarId && !it.deleted && it.markerUuid == null }
  }

  override suspend fun findMarkedRows(target: TargetCalendar): List<MarkedRow> {
    requirePermission()
    // R5: only the target calendar; the LIKE '%[ttsync:%' prefilter, then the strict parse.
    return rows.values
      .filter { it.calendarId == target.calendarId && !it.deleted }
      .filter { it.description?.contains(Marker.PREFIX) == true }
      .mapNotNull { row -> row.markerUuid?.let { MarkedRow(row.id, it) } }
  }

  override suspend fun checkRows(target: TargetCalendar, expected: Map<Long, String>): Map<Long, RowCheck> {
    requirePermission()
    return expected.mapValues { (id, uuid) -> GatewayRules.check(observe(id), target.calendarId, uuid) }
  }

  override suspend fun apply(target: TargetCalendar, ops: List<CalendarOp>): BatchResult {
    requirePermission()
    require(ops.size <= CalendarGateway.MAX_BATCH) { "batch of ${ops.size}" }
    batchSizes += ops.size
    onApply?.invoke(ops)
    val targetCheck = target.check(calendars[target.calendarId])
    if (targetCheck != TargetCheck.OK) throw TargetUnavailableException(targetCheck)

    // R1 pre-read.
    val checks =
      ops.map { op -> op.eventIdOrNull()?.let { GatewayRules.check(observe(it), target.calendarId, op.uuid) } }
    betweenReadAndApply?.invoke(ops)

    // applyBatch: in order, all or nothing.
    val before = TreeMap(rows)
    val beforeNext = nextId
    val results = mutableListOf<OpResult>()
    val applied = mutableListOf<CalendarOp>()
    for ((i, op) in ops.withIndex()) {
      val check = checks[i]
      if (check != null && check != RowCheck.OURS) {
        results += OpResult.Rejected(check)
        continue
      }
      when (op) {
        is CalendarOp.Insert -> {
          val values = GatewayRules.insertValues(op.spec, target)
          assertManaged(values.keys - GatewayRules.COL_CALENDAR_ID)
          val id = nextId++
          rows[id] = Row(id, values[GatewayRules.COL_CALENDAR_ID] as Long, false, values - GatewayRules.COL_CALENDAR_ID)
          results += OpResult.Inserted(id)
        }
        is CalendarOp.Update -> {
          if (!precondition(op.eventId, target, op.uuid)) return abort(before, beforeNext)
          val values = GatewayRules.updateValues(op.spec)
          assertManaged(values.keys)
          val row = rows.getValue(op.eventId)
          rows[op.eventId] = row.copy(values = row.values + values)
          results += OpResult.Updated
        }
        is CalendarOp.Delete -> {
          if (!precondition(op.eventId, target, op.uuid)) return abort(before, beforeNext)
          rows[op.eventId] = rows.getValue(op.eventId).copy(deleted = true)
          results += OpResult.Deleted
        }
      }
      applied += op
    }
    written += applied
    afterApply?.invoke(ops)
    return BatchResult.Applied(results)
  }

  override fun requestUpload(target: TargetCalendar) {
    uploadRequests++
  }

  private fun abort(before: TreeMap<Long, Row>, beforeNext: Long): BatchResult {
    rows.clear()
    rows.putAll(before)
    nextId = beforeNext
    return BatchResult.Aborted
  }

  /** The R2 assert: `_id=? AND calendar_id=? AND deleted=0 AND description LIKE '%[ttsync:uuid]'`. */
  private fun precondition(id: Long, target: TargetCalendar, uuid: String): Boolean {
    if (brokenPrecondition) return false
    val row = rows[id] ?: return false
    // SQLite's LIKE ignores ASCII case; emulate it.
    return row.calendarId == target.calendarId &&
      !row.deleted &&
      row.description?.endsWith(Marker.line(uuid), ignoreCase = true) == true
  }

  private fun observe(id: Long): ObservedRow? =
    rows[id]?.let { ObservedRow(it.id, it.calendarId, it.deleted, it.description) }

  private fun assertManaged(columns: Set<String>) {
    check(columns == EventSpec.MANAGED_COLUMNS.toSet()) { "R4: wrote columns $columns" }
    check(columns.none { it in GatewayRules.FORBIDDEN_COLUMNS }) { "R4: forbidden column" }
  }

  private fun CalendarOp.eventIdOrNull(): Long? =
    when (this) {
      is CalendarOp.Insert -> null
      is CalendarOp.Update -> eventId
      is CalendarOp.Delete -> eventId
    }
}
