package de.tergelius.ttsync.ui.common

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.content.IntentCompat
import androidx.core.net.toUri
import de.tergelius.ttsync.ui.DeviceState

/**
 * Intents into system settings for the "Keep running" step, the reliability checklist and the
 * Status fix buttons. Every launcher falls back to the app's details page when an OEM build lacks
 * the screen, so a button never does nothing.
 */
object SystemIntents {
  fun appDetails(context: Context): Intent =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())

  fun notificationSettings(context: Context): Intent =
    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
      .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

  /** Master sync and the account's calendar sync (DESIGN.md section 7, Reliability). */
  fun syncSettings(): Intent = Intent(Settings.ACTION_SYNC_SETTINGS)

  /**
   * The system dialog that exempts this app from battery optimizations. Periodic sync is the app's
   * core function and an exemption is the only way to keep it running on aggressive OEM builds
   * (DESIGN.md section 7, "Keep running"), which is an accepted use case.
   */
  @SuppressLint("BatteryLife")
  fun requestBatteryUnrestricted(context: Context): Intent =
    Intent(
      Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
      "package:${context.packageName}".toUri(),
    )

  fun batteryOptimizationList(): Intent =
    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

  /** "Pause app activity if unused" (or the permission auto-reset switch on older versions). */
  fun unusedAppRestrictions(context: Context): Intent =
    IntentCompat.createManageUnusedAppRestrictionsIntent(context, context.packageName)

  /** MIUI/HyperOS Autostart list (Xiaomi, Redmi, POCO). */
  fun xiaomiAutostart(): Intent =
    Intent()
      .setComponent(
        ComponentName(
          "com.miui.securitycenter",
          "com.miui.permcenter.autostart.AutoStartManagementActivity",
        )
      )

  fun dontKillMyApp(manufacturer: String): Intent =
    Intent(
      Intent.ACTION_VIEW,
      "https://dontkillmyapp.com/${DeviceState.dontKillMyAppSlug(manufacturer)}".toUri(),
    )

  fun browse(url: String): Intent = Intent(Intent.ACTION_VIEW, url.toUri())

  /**
   * Starts the first intent that resolves, ending with the app's details page. Returns false only
   * if not even that exists.
   */
  fun start(context: Context, vararg intents: Intent): Boolean {
    for (intent in intents.toList() + appDetails(context)) {
      try {
        context.startActivity(intent)
        return true
      } catch (_: ActivityNotFoundException) {
        // try the next one
      } catch (_: SecurityException) {
        // an OEM screen that is not exported; try the next one
      }
    }
    return false
  }
}
