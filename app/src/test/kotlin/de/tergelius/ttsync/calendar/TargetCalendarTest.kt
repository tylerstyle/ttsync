package de.tergelius.ttsync.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetCalendarTest {
  private val info =
    CalendarInfo(
      id = 15,
      displayName = "personal",
      accountName = "user@example.org",
      accountType = CalendarInfo.ACCOUNT_TYPE_GOOGLE,
      accessLevel = 700,
      syncEvents = true,
      visible = true,
      color = null,
      serverId = "personal@group.example",
    )
  private val target = TargetCalendar.of(info)

  @Test
  fun anIdThatHoldsAnotherCalendarNowIsMissing() {
    // F5: after a reset of the calendar storage `_ID` 15 is another calendar of the same account.
    val other = info.copy(displayName = "other", serverId = "other@group.example")
    assertEquals(TargetCheck.MISSING, target.check(other))
    assertFalse(target.isSameCalendar(TargetCalendar.of(other)))
    // Renamed in Google: still the same calendar.
    assertEquals(TargetCheck.OK, target.check(info.copy(displayName = "renamed")))
  }

  @Test
  fun aTargetStoredWithoutAServerIdIsCheckedByIdAndAccountAndLearnsIt() {
    val old = TargetCalendar(15, "user@example.org", CalendarInfo.ACCOUNT_TYPE_GOOGLE)
    assertEquals(TargetCheck.OK, old.check(info))
    assertTrue(old.isSameCalendar(target))
    assertEquals(target, old.withServerIdOf(info))
    // Nothing to learn from a row without one, and a known id is never replaced.
    assertEquals(old, old.withServerIdOf(info.copy(serverId = null)))
    assertEquals(target, target.withServerIdOf(info.copy(serverId = "other@group.example")))
    // Once the id is known, a row without one is not the target (fails closed).
    assertEquals(TargetCheck.MISSING, target.check(info.copy(serverId = null)))
    assertFalse(target.isSameCalendar(old))
  }

  @Test
  fun aRenumberedCalendarIsFoundByItsServerId() {
    val other = info.copy(id = 15, displayName = "other", serverId = "other@group.example")
    val moved = info.copy(id = 22)
    assertEquals(moved, target.movedTo(listOf(other, moved)))
    // Where it was, gone, another account, no server id stored, or not unique: no answer.
    assertEquals(null, target.movedTo(listOf(info)))
    assertEquals(null, target.movedTo(listOf(other)))
    assertEquals(null, target.movedTo(listOf(other, moved.copy(accountName = "else@example.org"))))
    assertEquals(null, target.copy(serverId = null).movedTo(listOf(other, moved)))
    assertEquals(null, target.movedTo(listOf(other, moved, moved.copy(id = 23))))
    assertEquals(2, target.sameCalendars(listOf(other, moved, moved.copy(id = 23))).size)
    assertEquals(listOf(info), target.sameCalendars(listOf(info, other)))
  }

  @Test
  fun sameCalendarPasses() {
    assertTrue(info.isEligibleTarget)
    assertEquals(TargetCheck.OK, target.check(info))
  }

  @Test
  fun everyChangeIsCaught() {
    assertEquals(TargetCheck.MISSING, target.check(null))
    assertEquals(TargetCheck.MISSING, target.check(info.copy(id = 16)))
    assertEquals(TargetCheck.ACCOUNT_CHANGED, target.check(info.copy(accountName = "other@example.org")))
    assertEquals(TargetCheck.READ_ONLY, target.check(info.copy(accessLevel = 200)))
    assertEquals(TargetCheck.SYNC_EVENTS_OFF, target.check(info.copy(syncEvents = false)))
    val local = info.copy(accountType = "LOCAL")
    assertEquals(TargetCheck.NOT_GOOGLE, TargetCalendar.of(local).check(local))
    assertFalse(local.isEligibleTarget)
  }

  @Test
  fun toStringHidesTheAccount() {
    assertFalse(target.toString().contains("example"))
    assertFalse(info.toString().contains("example.org"))
  }

  @Test
  fun uploadsNeedBothSwitches() {
    assertTrue(GoogleSyncState(masterSync = true, calendarSync = true).uploads)
    assertFalse(GoogleSyncState(masterSync = false, calendarSync = true).uploads)
    assertFalse(GoogleSyncState(masterSync = true, calendarSync = false).uploads)
    // Android 13+ hides the account's switch from the app: unknown is not "off" (device test B2).
    assertTrue(GoogleSyncState(masterSync = true, calendarSync = null).uploads)
    assertFalse(GoogleSyncState(masterSync = false, calendarSync = null).uploads)
  }
}
