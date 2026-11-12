package de.tergelius.ttsync.sync

import de.tergelius.ttsync.store.CalendarState
import kotlinx.serialization.Serializable

/**
 * The outcome of one [SyncEngine.run], saved in `StatusRepository` (as JSON) and shown on Status.
 * Holds only counts, ids and codes, never event content or credentials.
 */
@Serializable
data class SyncReport(
  val runId: String,
  val trigger: SyncTrigger,
  val startedAt: Long,
  val finishedAt: Long,
  /** Null if the run succeeded. A paused calendar alone does not make a run fail. */
  val failure: SyncFailure? = null,
  /** Totals over all calendars. */
  val counts: SyncCounts = SyncCounts(),
  val calendars: List<CalendarReport> = emptyList(),
  val warnings: Set<SyncWarning> = emptySet(),
  /**
   * Copies written more than 30 min before this run that Google's sync still has not uploaded
   * (`dirty = 1`); 0 if none or unknown (device test B2).
   */
  val notUploaded: Int = 0,
) {
  val succeeded: Boolean
    get() = failure == null
}

/** Counters of one run or one calendar. */
@Serializable
data class SyncCounts(
  val added: Int = 0,
  val updated: Int = 0,
  val deleted: Int = 0,
  /** Marked rows adopted into the mapping table (step 6). */
  val adopted: Int = 0,
  /** Birthdays and memos. */
  val skipped: Int = 0,
  /** Operations refused by the R1 checks and sent back through adopt-or-insert. */
  val rejected: Int = 0,
  /** Overrides whose original instance could not be matched exactly (section 4, rule 3). */
  val unmatchedOverrides: Int = 0,
  /**
   * Marked rows left alone (step 6): unknown uuids, unmapped rows of dead uuids, rows of paused
   * calendars and rows out of the window. Not the mapped rows the diff deletes.
   */
  val unmanagedMarked: Int = 0,
  /** Recurrence lines other than RRULE, EXDATE and RDATE. */
  val droppedRecurrenceLines: Int = 0,
  /**
   * Live, in-window events a calendar's filter excludes (0.2.0). Reports of 0.1.x have no such
   * field and read as 0.
   */
  val filtered: Int = 0,
) {
  /** Rows written to the calendar. Zero on a no-op run. */
  val writes: Int
    get() = added + updated + deleted

  operator fun plus(other: SyncCounts): SyncCounts =
    SyncCounts(
      added = added + other.added,
      updated = updated + other.updated,
      deleted = deleted + other.deleted,
      adopted = adopted + other.adopted,
      skipped = skipped + other.skipped,
      rejected = rejected + other.rejected,
      unmatchedOverrides = unmatchedOverrides + other.unmatchedOverrides,
      unmanagedMarked = unmanagedMarked + other.unmanagedMarked,
      droppedRecurrenceLines = droppedRecurrenceLines + other.droppedRecurrenceLines,
      filtered = filtered + other.filtered,
    )
}

/** Per TimeTree calendar. [mode] is null if the calendar was not fetched (paused or failed early). */
@Serializable
data class CalendarReport(
  val ttCalId: Long,
  val mode: SyncMode?,
  val state: CalendarState,
  val counts: SyncCounts = SyncCounts(),
)

@Serializable
enum class SyncMode {
  FULL,
  INCREMENTAL,
}

/** Why a run failed, with only codes and numbers attached. */
@Serializable
data class SyncFailure(
  val problem: SyncProblem,
  /** TimeTree error code or HTTP status, if any. */
  val code: Int? = null,
  /** For [SyncProblem.RATE_LIMITED]: wait at least this long. */
  val retryAfterMillis: Long? = null,
)

/**
 * Failure classes. [retryable] problems are retried by WorkManager while `runAttemptCount < 3`;
 * [userMustAct] problems fail at once, notify, and show a fix button on Status.
 */
@Serializable
enum class SyncProblem(val userMustAct: Boolean, val retryable: Boolean) {
  /** Sources or target not chosen yet. */
  NOT_CONFIGURED(userMustAct = true, retryable = false),
  NO_CALENDAR_PERMISSION(userMustAct = true, retryable = false),

  /** The target calendar row is gone (deleted on calendar.google.com). Own Status state. */
  TARGET_DELETED(userMustAct = true, retryable = false),

  /** The target changed account, became read-only, or stopped syncing events. */
  TARGET_UNAVAILABLE(userMustAct = true, retryable = false),

  /** No stored credentials, or `TtError.LoginRequired`. */
  NOT_SIGNED_IN(userMustAct = true, retryable = false),

  /** `TtError.AuthRejected`. */
  AUTH_REJECTED(userMustAct = true, retryable = false),

  /** `TtError.RateLimited`. */
  RATE_LIMITED(userMustAct = false, retryable = true),

  /** `TtError.Transient`: offline, timeout, 5xx. */
  NETWORK(userMustAct = false, retryable = true),

  /** `TtError.ApiChanged`: "update the app". */
  API_CHANGED(userMustAct = true, retryable = false),

  /** The calendar provider threw while writing. */
  CALENDAR_WRITE_FAILED(userMustAct = false, retryable = true),

  /** A bug. */
  INTERNAL(userMustAct = false, retryable = false),
}

/** Problems that do not stop the run. */
@Serializable
enum class SyncWarning {
  /** Master sync or the visible account's calendar sync is off: copies stay on this phone. */
  GOOGLE_SYNC_OFF,

  /** At least one TimeTree calendar is paused after losing access. */
  CALENDAR_PAUSED,

  /**
   * Google sync is not known to be off, but [SyncReport.notUploaded] copies are still waiting for
   * upload long after they were written: calendar sync may be off for the account (which Android
   * 13+ does not let the app see).
   */
  NOT_UPLOADED,
}

/** What started a run. */
@Serializable
enum class SyncTrigger {
  PERIODIC,

  /** "Sync now" or "Full resync". */
  MANUAL,

  /** First sync after setup, or after the sources or target changed. */
  SETUP,

  /** `debug.DebugReceiver`. */
  DEBUG,
}
