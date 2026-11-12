package de.tergelius.ttsync.ui

import de.tergelius.ttsync.calendar.GoogleSyncState
import de.tergelius.ttsync.ui.about.Fingerprint
import de.tergelius.ttsync.ui.reliability.CheckId
import de.tergelius.ttsync.ui.reliability.CheckItem
import de.tergelius.ttsync.ui.reliability.CheckState
import de.tergelius.ttsync.ui.reliability.Checklist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChecklistTest {
  private val ok =
    DeviceState(
      calendarPermission = true,
      notificationsEnabled = true,
      batteryUnrestricted = true,
      unusedAppRestrictions = UnusedAppRestrictions.DISABLED,
      standbyBucket = StandbyBucket.ACTIVE,
      manufacturer = "OnePlus",
    )

  @Test
  fun allRowsInOrder() {
    val items = Checklist.build(ok, GoogleSyncState(masterSync = true, calendarSync = true))
    assertEquals(
      listOf(
        CheckId.CALENDAR_PERMISSION,
        CheckId.NOTIFICATIONS,
        CheckId.GOOGLE_SYNC,
        CheckId.BATTERY,
        CheckId.BACKGROUND_RESTRICTION,
        CheckId.UNUSED_APP_RESTRICTIONS,
        CheckId.DONT_KILL_MY_APP,
        CheckId.STANDBY_BUCKET,
      ),
      items.map { it.id },
    )
    assertTrue(items.none { it.state == CheckState.PROBLEM })
  }

  @Test
  fun problemsAndUnknowns() {
    val device =
      ok.copy(
        notificationsEnabled = false,
        batteryUnrestricted = false,
        backgroundRestricted = true,
        unusedAppRestrictions = UnusedAppRestrictions.ENABLED,
      )
    val items = Checklist.build(device, googleSync = null).associate { it.id to it.state }
    assertEquals(CheckState.PROBLEM, items[CheckId.NOTIFICATIONS])
    assertEquals(CheckState.PROBLEM, items[CheckId.BATTERY])
    assertEquals(CheckState.PROBLEM, items[CheckId.BACKGROUND_RESTRICTION])
    assertEquals(CheckState.PROBLEM, items[CheckId.UNUSED_APP_RESTRICTIONS])
    assertEquals(CheckState.UNKNOWN, items[CheckId.GOOGLE_SYNC])
  }

  @Test
  fun googleSyncNeedsBothSwitches() {
    val master = Checklist.build(ok, GoogleSyncState(masterSync = false, calendarSync = true))
    assertTrue(CheckItem(CheckId.GOOGLE_SYNC, CheckState.PROBLEM) in master)
  }

  @Test
  fun autostartOnlyOnTheXiaomiFamily() {
    for (maker in listOf("Xiaomi", "Redmi", "POCO", "xiaomi ")) {
      val items = Checklist.build(ok.copy(manufacturer = maker), null)
      assertTrue(maker, CheckItem(CheckId.AUTOSTART, CheckState.UNKNOWN) in items)
    }
    assertFalse(Checklist.build(ok, null).any { it.id == CheckId.AUTOSTART })
  }

  @Test
  fun rowsHiddenWhereTheFeatureIsMissing() {
    val old =
      ok.copy(unusedAppRestrictions = UnusedAppRestrictions.NOT_AVAILABLE, standbyBucket = null)
    val ids = Checklist.build(old, null).map { it.id }
    assertFalse(CheckId.UNUSED_APP_RESTRICTIONS in ids)
    assertFalse(CheckId.STANDBY_BUCKET in ids)
  }

  @Test
  fun deviceHelpers() {
    assertEquals("oneplus", DeviceState.dontKillMyAppSlug("OnePlus"))
    assertEquals("hmdglobal", DeviceState.dontKillMyAppSlug("HMD Global"))
    assertEquals(StandbyBucket.RARE, StandbyBucket.of(40))
    assertEquals(StandbyBucket.RESTRICTED, StandbyBucket.of(45))
    assertEquals(StandbyBucket.OTHER, StandbyBucket.of(99))
  }

  @Test
  fun fingerprintFormat() {
    assertEquals(
      "00:0F:A0:FF",
      Fingerprint.format(byteArrayOf(0x00, 0x0F, 0xA0.toByte(), 0xFF.toByte())),
    )
  }
}
