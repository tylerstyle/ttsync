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
  /**
   * `Calendars.OWNER_ACCOUNT`: for a Google calendar its id on Google's server (the account's
   * address for the main calendar, `…@group.calendar.google.com` and the like for the others),
   * one per calendar of an account. Unlike `_ID` it survives a reset of the phone's calendar
   * storage, and renaming the calendar does not change it. Null if the provider has none.
   * (`Calendars._SYNC_ID` is empty for Google calendars: device check 2026-11-26.)
   */
  val serverId: String? = null,
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
 * The calendar chosen on the Target screen, identified by `_ID`, account **and** Google's server
 * id. It is stored in settings and re-checked against the provider before every run and every
 * write batch (DESIGN.md section 5, step 1 and R3): if the `_ID` now belongs to another account or
 * to another calendar, nothing is written.
 *
 * The phone numbers its calendars anew when its calendar storage is reset, so an `_ID` can come to
 * mean another calendar of the same account (device test 2026-11-26, F5). [serverId] catches that.
 * It is null in settings written before 0.2.2; the check then compares `_ID` and account only, and
 * the engine stores the id at its next run ([withServerIdOf]). Once it is stored, a row without a
 * server id or with another one is not the target.
 */
@Serializable
data class TargetCalendar(
  val calendarId: Long,
  val accountName: String,
  val accountType: String,
  val serverId: String? = null,
) {
  /** Compares the stored identity with the provider's current row (null if the row is gone). */
  fun check(current: CalendarInfo?): TargetCheck =
    when {
      current == null || current.id != calendarId -> TargetCheck.MISSING
      current.accountName != accountName || current.accountType != accountType ->
        TargetCheck.ACCOUNT_CHANGED
      // The chosen calendar is not at this `_ID` any more: for the app it is gone.
      serverId != null && current.serverId != serverId -> TargetCheck.MISSING
      current.accountType != CalendarInfo.ACCOUNT_TYPE_GOOGLE -> TargetCheck.NOT_GOOGLE
      current.accessLevel < CalendarInfo.MIN_ACCESS_LEVEL -> TargetCheck.READ_ONLY
      !current.syncEvents -> TargetCheck.SYNC_EVENTS_OFF
      else -> TargetCheck.OK
    }

  /**
   * True if [other], a row read now, is this stored calendar: `_ID` and account are equal, and so
   * is the server id if this target has one. A target stored without a server id (before 0.2.2)
   * equals the same row read now.
   */
  fun isSameCalendar(other: TargetCalendar): Boolean =
    calendarId == other.calendarId &&
      accountName == other.accountName &&
      accountType == other.accountType &&
      (serverId == null || serverId == other.serverId)

  /** This target with the server id of [current], if it has none yet and [current] has one. */
  fun withServerIdOf(current: CalendarInfo): TargetCalendar =
    if (serverId == null && current.serverId != null) copy(serverId = current.serverId) else this

  /** Redacted: the account name is an email address, and so is the server id of a main calendar. */
  override fun toString(): String = "TargetCalendar(calendarId=$calendarId, accountType=$accountType)"

  companion object {
    fun of(info: CalendarInfo): TargetCalendar =
      TargetCalendar(info.id, info.accountName, info.accountType, info.serverId)
  }
}

/** Result of [TargetCalendar.check]. Anything but [OK] stops the run before any write. */
enum class TargetCheck {
  OK,

  /**
   * The row is gone, e.g. the calendar was deleted on calendar.google.com, or its `_ID` now holds
   * another calendar (the phone's calendar storage was reset). Own Status state.
   */
  MISSING,

  /** The `_ID` now belongs to another account (reused after a storage reset). */
  ACCOUNT_CHANGED,
  NOT_GOOGLE,
  READ_ONLY,
  SYNC_EVENTS_OFF,
}

/**
 * Android's sync switches for the target account. If one is known to be off the run still writes,
 * but the copies stay on the phone until sync is back on (Status warning, checklist item).
 */
data class GoogleSyncState(
  /** `ContentResolver.getMasterSyncAutomatically()`; needs no account access, so always known. */
  val masterSync: Boolean,
  /**
   * `ContentResolver.getSyncAutomatically(account, "com.android.calendar")`, or null if unknown.
   * Since Android 13 (targetSdk > 32) that call answers false, and `requestSync` does nothing, for
   * an account the app cannot see, and a Google account usually is not visible to the app (device
   * test B2). The app then judges uploads from its own rows instead (`SyncReport.notUploaded`).
   */
  val calendarSync: Boolean?,
) {
  /** Not known to be off: true unless master sync or the (visible) account's calendar sync is off. */
  val uploads: Boolean
    get() = masterSync && calendarSync != false
}

/** Thrown by [CalendarGateway.apply] when the target no longer passes [TargetCalendar.check] (R3). */
class TargetUnavailableException(val check: TargetCheck) :
  IllegalStateException("target calendar unavailable: $check")
