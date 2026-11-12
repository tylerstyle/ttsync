package de.tergelius.ttsync.calendar

import android.Manifest
import android.accounts.Account
import android.accounts.AccountManager
import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.OperationApplicationException
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.sqlite.SQLiteException
import android.os.Bundle
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import androidx.core.content.ContextCompat
import de.tergelius.ttsync.mapping.Marker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [CalendarGateway] on the real `CalendarContract` provider. Kept thin: the decisions live in
 * [GatewayRules] (JVM-tested); this class only reads rows and builds the `applyBatch`. It is
 * verified on the device (DESIGN.md section 10).
 *
 * How the hard rules are implemented here:
 * - **R1.** [apply] reads every row it is about to update or delete (by `_ID`, all in one query)
 *   and runs [GatewayRules.check]. Operations that fail are not sent and come back as
 *   [OpResult.Rejected].
 * - **R2.** Each update or delete is two operations: an assert query on `Events.CONTENT_URI` with
 *   the selection [GatewayRules.PRECONDITION] and `withExpectedCount(1)`, then the write on
 *   `ContentUris.withAppendedId(Events.CONTENT_URI, id)` without any selection. The selection is
 *   only on the assert: CalendarProvider2 ignores a selection on an `_ID` URI, so putting it on the
 *   write would not protect anything. If an assert fails, `applyBatch` throws
 *   [OperationApplicationException], CalendarProvider2 rolls back the whole batch, and [apply]
 *   returns [BatchResult.Aborted].
 * - **R3.** Inserts set `calendar_id` from the target, and every [apply] re-checks the target first.
 * - **R4.** Plain `Events.CONTENT_URI`, never `CALLER_IS_SYNCADAPTER`. Only Events rows are written,
 *   with exactly the [GatewayRules.updateValues] columns (plus `calendar_id` on insert).
 * - **R5.** [findMarkedRows] and [countForeignEvents] select `calendar_id = target`.
 */
class ProviderCalendarGateway(context: Context) : CalendarGateway {
  private val appContext = context.applicationContext
  private val resolver: ContentResolver
    get() = appContext.contentResolver

  override fun hasPermission(): Boolean =
    PERMISSIONS.all {
      ContextCompat.checkSelfPermission(appContext, it) == PackageManager.PERMISSION_GRANTED
    }

  override suspend fun calendars(): List<CalendarInfo> = io { queryCalendars(null, null) }

  override suspend fun calendar(calendarId: Long): CalendarInfo? = io { queryCalendar(calendarId) }

  override fun googleSyncState(target: TargetCalendar): GoogleSyncState {
    val account = target.account()
    // Android 13+ answers false for an account the app cannot see; that means "unknown" here.
    val visible =
      try {
        AccountManager.get(appContext).getAccountsByType(account.type).any { it == account }
      } catch (e: RuntimeException) {
        false
      }
    return GoogleSyncState(
      masterSync = ContentResolver.getMasterSyncAutomatically(),
      calendarSync =
        if (visible) ContentResolver.getSyncAutomatically(account, CalendarContract.AUTHORITY)
        else null,
    )
  }

  override suspend fun countForeignEvents(calendarId: Long): Int = io {
    query(
      Events.CONTENT_URI,
      arrayOf(Events._ID, Events.DESCRIPTION),
      "${Events.CALENDAR_ID} = ? AND ${Events.DELETED} = 0",
      arrayOf(calendarId.toString()),
    ) { cursor ->
      Marker.parse(cursor.getStringOrNull(1))
    }
      .count { it == null }
  }

  override suspend fun findMarkedRows(target: TargetCalendar): List<MarkedRow> = io {
    query(
      Events.CONTENT_URI,
      arrayOf(Events._ID, Events.DESCRIPTION),
      "${Events.CALENDAR_ID} = ? AND ${Events.DELETED} = 0 AND ${Events.DESCRIPTION} LIKE ?",
      arrayOf(target.calendarId.toString(), Marker.LIKE_ANY),
      "${Events._ID} ASC",
    ) { cursor ->
      Marker.parse(cursor.getStringOrNull(1))?.let { MarkedRow(cursor.getLong(0), it) }
    }
      .filterNotNull()
  }

  override suspend fun checkRows(target: TargetCalendar, expected: Map<Long, String>): Map<Long, RowCheck> =
    io {
      val rows = readRows(expected.keys)
      expected.mapValues { (id, uuid) -> GatewayRules.check(rows[id], target.calendarId, uuid) }
    }

  override suspend fun apply(target: TargetCalendar, ops: List<CalendarOp>): BatchResult = io {
    require(ops.size <= CalendarGateway.MAX_BATCH) { "batch too large: ${ops.size}" }
    if (ops.isEmpty()) return@io BatchResult.Applied(emptyList())

    // R3: the target must still be the calendar the user chose.
    val targetCheck = target.check(queryCalendar(target.calendarId))
    if (targetCheck != TargetCheck.OK) throw TargetUnavailableException(targetCheck)

    // R1: read every row we would touch, and refuse each one that is not ours.
    val rows = readRows(ops.mapNotNull { it.eventIdOrNull() })
    val checks: List<RowCheck?> = ops.map { op ->
      op.eventIdOrNull()?.let { GatewayRules.check(rows[it], target.calendarId, op.uuid) }
    }

    // R2: one assert per update/delete, then the write on the single _ID URI.
    val batch = ArrayList<ContentProviderOperation>()
    val writeIndex = IntArray(ops.size) { -1 }
    ops.forEachIndexed { i, op ->
      if (checks[i] != null && checks[i] != RowCheck.OURS) return@forEachIndexed
      when (op) {
        is CalendarOp.Insert -> {
          writeIndex[i] = batch.size
          batch +=
            ContentProviderOperation.newInsert(Events.CONTENT_URI)
              .withValues(GatewayRules.insertValues(op.spec, target).toContentValues())
              .build()
        }
        is CalendarOp.Update -> {
          batch += precondition(op.eventId, target, op.uuid)
          writeIndex[i] = batch.size
          batch +=
            ContentProviderOperation.newUpdate(eventUri(op.eventId))
              .withValues(GatewayRules.updateValues(op.spec).toContentValues())
              .build()
        }
        is CalendarOp.Delete -> {
          batch += precondition(op.eventId, target, op.uuid)
          writeIndex[i] = batch.size
          batch += ContentProviderOperation.newDelete(eventUri(op.eventId)).build()
        }
      }
    }

    val results =
      if (batch.isEmpty()) {
        emptyArray()
      } else {
        try {
          resolver.applyBatch(CalendarContract.AUTHORITY, batch)
        } catch (e: OperationApplicationException) {
          // An R2 precondition failed (or an insert returned no row); nothing was written.
          return@io BatchResult.Aborted
        }
      }

    BatchResult.Applied(
      ops.mapIndexed { i, op ->
        val check = checks[i]
        if (check != null && check != RowCheck.OURS) return@mapIndexed OpResult.Rejected(check)
        val result = results[writeIndex[i]]
        when (op) {
          is CalendarOp.Insert ->
            OpResult.Inserted(
              ContentUris.parseId(checkNotNull(result.uri) { "insert returned no uri" })
            )
          is CalendarOp.Update -> OpResult.Updated
          is CalendarOp.Delete -> OpResult.Deleted
        }
      }
    )
  }

  /**
   * Reads `dirty` of our rows, chunked by `_ID` and limited to the target (R5). A provider that
   * refuses the column (an unknown column is an `IllegalArgumentException`, or a `SQLiteException`)
   * gives null: no signal rather than a failed run.
   */
  override suspend fun pendingUploads(target: TargetCalendar, eventIds: Collection<Long>): Set<Long>? =
    io {
      try {
        val dirty = HashSet<Long>()
        for (chunk in eventIds.distinct().chunked(READ_CHUNK)) {
          query(
            Events.CONTENT_URI,
            arrayOf(Events._ID),
            "${Events._ID} IN (${chunk.joinToString(",") { "?" }}) AND ${Events.CALENDAR_ID} = ? " +
              "AND ${Events.DELETED} = 0 AND ${Events.DIRTY} = 1",
            (chunk.map { it.toString() } + target.calendarId.toString()).toTypedArray(),
          ) { cursor ->
            cursor.getLong(0)
          }
            .forEach { dirty += it }
        }
        dirty
      } catch (e: IllegalArgumentException) {
        null
      } catch (e: SQLiteException) {
        null
      }
    }

  /**
   * `requestSync(account, "com.android.calendar", {EXPEDITED, MANUAL})`, best effort. The engine
   * calls it only while Google sync is not known to be off, because a manual request would override
   * the user's sync switches. For an account the app cannot see it does nothing (Android 13+).
   */
  override fun requestUpload(target: TargetCalendar) {
    val extras =
      Bundle().apply {
        putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
        putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
      }
    ContentResolver.requestSync(target.account(), CalendarContract.AUTHORITY, extras)
  }

  private fun precondition(eventId: Long, target: TargetCalendar, uuid: String): ContentProviderOperation =
    ContentProviderOperation.newAssertQuery(Events.CONTENT_URI)
      .withSelection(
        GatewayRules.PRECONDITION,
        GatewayRules.preconditionArgs(eventId, target.calendarId, uuid).toTypedArray(),
      )
      .withExpectedCount(1)
      .build()

  /** The R1 columns of each existing row among [ids], read by `_ID` in chunks. */
  private fun readRows(ids: Collection<Long>): Map<Long, ObservedRow> {
    val rows = HashMap<Long, ObservedRow>()
    for (chunk in ids.distinct().chunked(READ_CHUNK)) {
      query(
        Events.CONTENT_URI,
        arrayOf(Events._ID, Events.CALENDAR_ID, Events.DELETED, Events.DESCRIPTION),
        "${Events._ID} IN (${chunk.joinToString(",") { "?" }})",
        chunk.map { it.toString() }.toTypedArray(),
      ) { cursor ->
        ObservedRow(
          eventId = cursor.getLong(0),
          calendarId = cursor.getLong(1),
          deleted = cursor.getInt(2) != 0,
          description = cursor.getStringOrNull(3),
        )
      }
        .forEach { rows[it.eventId] = it }
    }
    return rows
  }

  private fun queryCalendar(calendarId: Long): CalendarInfo? =
    queryCalendars("${Calendars._ID} = ?", arrayOf(calendarId.toString())).firstOrNull()

  private fun queryCalendars(selection: String?, args: Array<String>?): List<CalendarInfo> =
    query(Calendars.CONTENT_URI, CALENDAR_PROJECTION, selection, args, "${Calendars._ID} ASC") { c ->
      CalendarInfo(
        id = c.getLong(0),
        displayName = c.getStringOrNull(1).orEmpty(),
        accountName = c.getStringOrNull(2).orEmpty(),
        accountType = c.getStringOrNull(3).orEmpty(),
        accessLevel = c.getInt(4),
        syncEvents = c.getInt(5) == 1,
        visible = c.getInt(6) == 1,
        color = if (c.isNull(7)) null else c.getInt(7),
      )
    }

  private fun <T> query(
    uri: android.net.Uri,
    projection: Array<String>,
    selection: String?,
    args: Array<String>?,
    sortOrder: String? = null,
    read: (Cursor) -> T,
  ): List<T> {
    val cursor =
      resolver.query(uri, projection, selection, args, sortOrder)
        ?: throw IllegalStateException("calendar provider unavailable")
    return cursor.use { buildList { while (it.moveToNext()) add(read(it)) } }
  }

  private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

  private companion object {
    val PERMISSIONS = listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)

    /** Rows per `_id IN (…)` query; well below SQLite's limit of bound arguments. */
    const val READ_CHUNK = 200

    val CALENDAR_PROJECTION =
      arrayOf(
        Calendars._ID,
        Calendars.CALENDAR_DISPLAY_NAME,
        Calendars.ACCOUNT_NAME,
        Calendars.ACCOUNT_TYPE,
        Calendars.CALENDAR_ACCESS_LEVEL,
        Calendars.SYNC_EVENTS,
        Calendars.VISIBLE,
        Calendars.CALENDAR_COLOR,
      )

    fun eventUri(eventId: Long) = ContentUris.withAppendedId(Events.CONTENT_URI, eventId)

    fun TargetCalendar.account() = Account(accountName, accountType)

    fun CalendarOp.eventIdOrNull(): Long? =
      when (this) {
        is CalendarOp.Insert -> null
        is CalendarOp.Update -> eventId
        is CalendarOp.Delete -> eventId
      }

    fun Cursor.getStringOrNull(index: Int): String? = if (isNull(index)) null else getString(index)

    fun Map<String, Any?>.toContentValues(): ContentValues {
      val values = ContentValues(size)
      for ((key, value) in this) {
        require(key !in GatewayRules.FORBIDDEN_COLUMNS) { "column $key must never be written" }
        when (value) {
          null -> values.putNull(key)
          is Long -> values.put(key, value)
          is Int -> values.put(key, value)
          is String -> values.put(key, value)
          else -> throw IllegalArgumentException("unsupported value for $key")
        }
      }
      return values
    }
  }
}
