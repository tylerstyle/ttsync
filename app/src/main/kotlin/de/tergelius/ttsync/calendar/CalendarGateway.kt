package de.tergelius.ttsync.calendar

import de.tergelius.ttsync.mapping.EventSpec
import de.tergelius.ttsync.mapping.Marker

/**
 * The only way the app touches `CalendarContract` (DESIGN.md section 5). [ProviderCalendarGateway]
 * implements it on the real provider; engine tests use `FakeCalendarGateway`, which must enforce
 * the same rules.
 *
 * **The safety rule.** The target calendar usually also holds the user's own events. Only rows in
 * the target calendar that carry the app's marker for the expected uuid (see [Marker]) may ever be
 * updated or deleted. Every implementation must guarantee:
 * - **R1.** Update or delete only by `_ID`, and only after reading that row and checking
 *   `calendar_id == target`, `deleted == 0` and that [Marker.parse] of its description equals the
 *   expected uuid. If a check fails, write nothing for that operation and return
 *   [OpResult.Rejected]; the engine sends the uuid back through adopt-or-insert.
 * - **R2.** Never update or delete with a selection over the table. Every write targets
 *   `ContentUris.withAppendedId(Events.CONTENT_URI, id)` and is preceded in the same batch by the
 *   precondition `_id=? AND calendar_id=? AND deleted=0 AND description LIKE ?`
 *   ([GatewayRules.PRECONDITION], an assert query with `withExpectedCount(1)`; CalendarProvider2
 *   ignores a selection on an `_ID` URI, so it cannot sit on the write itself). If a precondition
 *   fails while the batch applies, the whole batch rolls back and [apply] returns
 *   [BatchResult.Aborted].
 * - **R3.** Insert only into the target calendar, and only after [TargetCalendar.check] is OK; [apply]
 *   re-checks it per batch and throws [TargetUnavailableException] otherwise.
 * - **R4.** Never use `CALLER_IS_SYNCADAPTER`. Never write Attendees, Reminders,
 *   ExtendedProperties, Colors or Calendars. Write exactly [EventSpec.MANAGED_COLUMNS] (nulls
 *   included, "explicit clearing") plus `calendar_id` on insert, nothing else.
 * - **R5.** Every query for the app's rows is limited to `calendar_id = target`.
 *
 * Without calendar permission every method except [hasPermission] throws `SecurityException`.
 * Suspending methods do their IO on `Dispatchers.IO`.
 */
interface CalendarGateway {
  /** True if both `READ_CALENDAR` and `WRITE_CALENDAR` are granted. */
  fun hasPermission(): Boolean

  /** Every calendar on the device. The Target screen shows those with [CalendarInfo.isEligibleTarget]. */
  suspend fun calendars(): List<CalendarInfo>

  /** The calendar with this `_ID`, or null if there is none. */
  suspend fun calendar(calendarId: Long): CalendarInfo?

  /**
   * Android's master and account calendar sync switches for the target's account. The account
   * switch is null (unknown) when the account is not visible to the app (see [GoogleSyncState]).
   */
  fun googleSyncState(target: TargetCalendar): GoogleSyncState

  /**
   * Events in [calendarId] (`deleted = 0`) that do not carry the app's marker: the "N other events
   * stay untouched" on the Target screen. Read-only.
   */
  suspend fun countForeignEvents(calendarId: Long): Int

  /**
   * Adoption scan (step 6, R5): rows with `calendar_id = target AND deleted = 0 AND description
   * LIKE '%[ttsync:%'` whose last line parses with [Marker.parse], ordered by `_ID`. Read-only.
   */
  suspend fun findMarkedRows(target: TargetCalendar): List<MarkedRow>

  /**
   * FULL check of mapped rows: for each `_ID` → expected uuid, whether the row is still ours
   * (the R1 checks). Read-only.
   */
  suspend fun checkRows(target: TargetCalendar, expected: Map<Long, String>): Map<Long, RowCheck>

  /**
   * Of the rows [eventIds] in [target] (R5, `deleted = 0`), those that Google's sync adapter has not
   * uploaded yet (`dirty = 1`). Read-only. Null if the provider does not let the app read `dirty`;
   * the caller then has no upload signal (device test B2).
   */
  suspend fun pendingUploads(target: TargetCalendar, eventIds: Collection<Long>): Set<Long>?

  /**
   * Applies at most [MAX_BATCH] operations with one `applyBatch`.
   *
   * Operations that fail the R1 pre-read are not sent and get [OpResult.Rejected]; the others are
   * applied atomically. Returns [BatchResult.Applied] with one result per operation, in order, or
   * [BatchResult.Aborted] if an R2 precondition failed during the batch (nothing was written; the
   * engine then replays the operations one at a time). Any other provider failure throws, and
   * nothing of the batch was written.
   *
   * @throws TargetUnavailableException if the target no longer passes [TargetCalendar.check]
   */
  suspend fun apply(target: TargetCalendar, ops: List<CalendarOp>): BatchResult

  /**
   * Asks Android to upload the changes now: `ContentResolver.requestSync(account,
   * "com.android.calendar", {SYNC_EXTRAS_EXPEDITED, SYNC_EXTRAS_MANUAL})`. Never changes sync
   * settings. Best effort only: it does nothing for an account the app cannot see (Android 13+),
   * and the provider starts Google's sync for changed rows on its own anyway.
   */
  fun requestUpload(target: TargetCalendar)

  companion object {
    /** Chunk size for [apply] (DESIGN.md section 5, step 7). */
    const val MAX_BATCH = 50
  }
}

/** A row in the target calendar whose description ends with a valid marker. */
data class MarkedRow(val eventId: Long, val uuid: String) {
  override fun toString(): String = "MarkedRow(eventId=$eventId, uuid=${uuid.take(8)})"
}

/** One write. Updates and deletes name the uuid whose marker the row must carry (R1). */
sealed interface CalendarOp {
  val uuid: String

  /** Insert a new row into the target calendar (R3). */
  data class Insert(val spec: EventSpec) : CalendarOp {
    override val uuid: String
      get() = spec.uuid
  }

  /** Overwrite every managed column of row [eventId], which must carry the marker of `spec.uuid`. */
  data class Update(val eventId: Long, val spec: EventSpec) : CalendarOp {
    override val uuid: String
      get() = spec.uuid
  }

  /** Delete row [eventId] (provider sets `deleted=1`), which must carry the marker of [uuid]. */
  data class Delete(val eventId: Long, override val uuid: String) : CalendarOp {
    override fun toString(): String = "Delete(eventId=$eventId, uuid=${uuid.take(8)})"
  }
}

sealed interface OpResult {
  data class Inserted(val eventId: Long) : OpResult

  data object Updated : OpResult

  data object Deleted : OpResult

  /** The R1 check failed; nothing was written for this operation. */
  data class Rejected(val check: RowCheck) : OpResult
}

sealed interface BatchResult {
  /** One result per operation, in the order given. */
  data class Applied(val results: List<OpResult>) : BatchResult

  /** An R2 precondition failed during `applyBatch`; nothing was written. Replay one at a time. */
  data object Aborted : BatchResult
}

/** Outcome of the R1 checks for one `_ID`. Only [OURS] permits an update or delete. */
enum class RowCheck {
  OURS,

  /** No row with this `_ID`. */
  MISSING,

  /** The row is in another calendar (e.g. an `_ID` reused after a storage reset). */
  OTHER_CALENDAR,

  /** The row has `deleted = 1` (deleted by the user, waiting for the sync adapter). */
  DELETED,

  /** The last description line is not the marker of the expected uuid. */
  MARKER_MISMATCH,
}
