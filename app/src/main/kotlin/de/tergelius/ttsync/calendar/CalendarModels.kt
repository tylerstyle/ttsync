package de.tergelius.ttsync.calendar

import kotlinx.serialization.Serializable

/** One row of `CalendarContract.Calendars`, as far as the app cares. */
data class CalendarInfo(
  val id: Long,
  val displayName: String,
  val accountName: String,
  val accountType: String,
  /** `CALENDAR_ACCESS_LEVEL`. */
  val accessLevel: Int,
  /** `SYNC_EVENTS == 1`. */
  val syncEvents: Boolean,
  val visible: Boolean,
  val color: Int?,
) {
  /** Listed on the Target screen and required on every run: Google, writable, synced. */
  val isEligibleTarget: Boolean
    get() = accountType == ACCOUNT_TYPE_GOOGLE && accessLevel >= MIN_ACCESS_LEVEL && syncEvents

  /** Redacted: the account name is an email address. */
  override fun toString(): String =
    "CalendarInfo(id=$id, accountType=$accountType, access=$accessLevel, syncEvents=$syncEvents)"

  companion object {
    const val ACCOUNT_TYPE_GOOGLE = "com.google"

    /** `Calendars.CAL_ACCESS_CONTRIBUTOR`. */
    const val MIN_ACCESS_LEVEL = 500
  }
}

/**
 * The calendar chosen on the Target screen, identified by `_ID` **and** account. It is stored in
 * settings and re-checked against the provider before every run and every write batch (DESIGN.md
 * section 5, step 1 and R3): if the `_ID` now belongs to another account, nothing is written.
 */
@Serializable
data class TargetCalendar(
  val calendarId: Long,
  val accountName: String,
  val accountType: String,
) {
  /** Compares the stored identity with the provider's current row (null if the row is gone). */
  fun check(current: CalendarInfo?): TargetCheck =
    when {
      current == null || current.id != calendarId -> TargetCheck.MISSING
      current.accountName != accountName || current.accountType != accountType ->
        TargetCheck.ACCOUNT_CHANGED
      current.accountType != CalendarInfo.ACCOUNT_TYPE_GOOGLE -> TargetCheck.NOT_GOOGLE
      current.accessLevel < CalendarInfo.MIN_ACCESS_LEVEL -> TargetCheck.READ_ONLY
      !current.syncEvents -> TargetCheck.SYNC_EVENTS_OFF
      else -> TargetCheck.OK
    }

  override fun toString(): String = "TargetCalendar(calendarId=$calendarId, accountType=$accountType)"

  companion object {
    fun of(info: CalendarInfo): TargetCalendar =
      TargetCalendar(info.id, info.accountName, info.accountType)
  }
}

/** Result of [TargetCalendar.check]. Anything but [OK] stops the run before any write. */
enum class TargetCheck {
  OK,

  /** The row is gone, e.g. the calendar was deleted on calendar.google.com. Own Status state. */
  MISSING,

  /** The `_ID` now belongs to another account (reused after a storage reset). */
  ACCOUNT_CHANGED,
  NOT_GOOGLE,
  READ_ONLY,
  SYNC_EVENTS_OFF,
}

/**
 * Android's sync switches for the target account. If either is off the run still writes, but the
 * copies stay on the phone until sync is back on (Status warning, checklist item).
 */
data class GoogleSyncState(
  /** `ContentResolver.getMasterSyncAutomatically()`. */
  val masterSync: Boolean,
  /** `ContentResolver.getSyncAutomatically(account, "com.android.calendar")`. */
  val calendarSync: Boolean,
) {
  val uploads: Boolean
    get() = masterSync && calendarSync
}

/** Thrown by [CalendarGateway.apply] when the target no longer passes [TargetCalendar.check] (R3). */
class TargetUnavailableException(val check: TargetCheck) :
  IllegalStateException("target calendar unavailable: $check")
