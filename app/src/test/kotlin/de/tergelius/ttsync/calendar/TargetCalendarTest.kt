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
    )
  private val target = TargetCalendar.of(info)

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
    assertFalse(target.toString().contains("example.org"))
    assertFalse(info.toString().contains("example.org"))
  }

  @Test
  fun uploadsNeedBothSwitches() {
    assertTrue(GoogleSyncState(masterSync = true, calendarSync = true).uploads)
    assertFalse(GoogleSyncState(masterSync = false, calendarSync = true).uploads)
    assertFalse(GoogleSyncState(masterSync = true, calendarSync = false).uploads)
  }
}
