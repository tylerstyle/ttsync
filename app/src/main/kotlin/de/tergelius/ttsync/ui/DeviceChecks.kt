package de.tergelius.ttsync.ui

import android.Manifest
import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.PackageManagerCompat
import androidx.core.content.UnusedAppRestrictionsConstants
import de.tergelius.ttsync.sync.SyncNotifier
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * What the device allows the app to do in the background (DESIGN.md section 7, "Keep running" and
 * "Reliability"). Read by [DeviceChecks]; plain data, so ViewModels and their tests stay free of
 * `android.*`.
 */
data class DeviceState(
  /** `READ_CALENDAR` and `WRITE_CALENDAR`. */
  val calendarPermission: Boolean = false,
  /** Notifications are allowed and the `sync_problems` channel is not switched off. */
  val notificationsEnabled: Boolean = false,
  /** API 33+: `POST_NOTIFICATIONS` is not granted, so it can be requested at runtime. */
  val notificationPermissionMissing: Boolean = false,
  /** `PowerManager.isIgnoringBatteryOptimizations`: "battery unrestricted". */
  val batteryUnrestricted: Boolean = false,
  /** `ActivityManager.isBackgroundRestricted` (API 28+, false below). */
  val backgroundRestricted: Boolean = false,
  val unusedAppRestrictions: UnusedAppRestrictions = UnusedAppRestrictions.NOT_AVAILABLE,
  /** `UsageStatsManager.getAppStandbyBucket` (API 28+), for diagnosis only. */
  val standbyBucket: StandbyBucket? = null,
  /** `Build.MANUFACTURER`. */
  val manufacturer: String = "",
) {
  /** Xiaomi, Redmi and POCO need Autostart, or periodic work never fires. */
  val xiaomiFamily: Boolean
    get() = isXiaomiFamily(manufacturer)

  companion object {
    private val XIAOMI_FAMILY = setOf("xiaomi", "redmi", "poco")

    fun isXiaomiFamily(manufacturer: String): Boolean =
      manufacturer.trim().lowercase() in XIAOMI_FAMILY

    /** `https://dontkillmyapp.com/<slug>`, e.g. `oneplus` for "OnePlus". */
    fun dontKillMyAppSlug(manufacturer: String): String =
      manufacturer.lowercase().filter { it in 'a'..'z' || it in '0'..'9' }
  }
}

/** "Pause app activity if unused" (Android 11+, backported to 6–10 by Google Play). */
enum class UnusedAppRestrictions {
  /** The app would be hibernated or lose its permissions when unused. */
  ENABLED,
  DISABLED,

  /** The feature does not exist on this device, or its state cannot be read. */
  NOT_AVAILABLE,
}

/** App standby buckets (shown on the checklist for diagnosis only). */
enum class StandbyBucket {
  EXEMPTED,
  ACTIVE,
  WORKING_SET,
  FREQUENT,
  RARE,
  RESTRICTED,
  OTHER;

  companion object {
    /** Maps `UsageStatsManager.STANDBY_BUCKET_*` (the values are stable public constants). */
    fun of(bucket: Int): StandbyBucket =
      when (bucket) {
        5 -> EXEMPTED
        10 -> ACTIVE
        20 -> WORKING_SET
        30 -> FREQUENT
        40 -> RARE
        45 -> RESTRICTED
        else -> OTHER
      }
  }
}

/** Reads [DeviceState]. ViewModels re-read it whenever their screen resumes. */
fun interface DeviceChecks {
  suspend fun read(): DeviceState
}

/** [DeviceChecks] on the real platform services. */
class AndroidDeviceChecks(context: Context) : DeviceChecks {
  private val context = context.applicationContext

  override suspend fun read(): DeviceState =
    DeviceState(
      calendarPermission =
        granted(Manifest.permission.READ_CALENDAR) && granted(Manifest.permission.WRITE_CALENDAR),
      notificationsEnabled = notificationsEnabled(),
      notificationPermissionMissing =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
          !granted(Manifest.permission.POST_NOTIFICATIONS),
      batteryUnrestricted =
        context
          .getSystemService(PowerManager::class.java)
          ?.isIgnoringBatteryOptimizations(context.packageName) == true,
      backgroundRestricted =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
          context.getSystemService(ActivityManager::class.java)?.isBackgroundRestricted == true,
      unusedAppRestrictions = unusedAppRestrictions(),
      standbyBucket =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
          context
            .getSystemService(UsageStatsManager::class.java)
            ?.appStandbyBucket
            ?.let(StandbyBucket::of)
        } else {
          null
        },
      manufacturer = Build.MANUFACTURER.orEmpty(),
    )

  private fun granted(permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

  private fun notificationsEnabled(): Boolean {
    val manager = NotificationManagerCompat.from(context)
    if (!manager.areNotificationsEnabled()) return false
    val channel = manager.getNotificationChannelCompat(SyncNotifier.CHANNEL_PROBLEMS)
    return channel == null || channel.importance != NotificationManagerCompat.IMPORTANCE_NONE
  }

  private suspend fun unusedAppRestrictions(): UnusedAppRestrictions {
    val status =
      runCatching {
          val future = PackageManagerCompat.getUnusedAppRestrictionsStatus(context)
          suspendCancellableCoroutine { cont ->
            future.addListener(
              { cont.resume(runCatching { future.get() }.getOrNull()) },
              DirectExecutor,
            )
            cont.invokeOnCancellation { future.cancel(false) }
          }
        }
        .getOrNull()
    return when (status) {
      UnusedAppRestrictionsConstants.API_30_BACKPORT,
      UnusedAppRestrictionsConstants.API_30,
      UnusedAppRestrictionsConstants.API_31 -> UnusedAppRestrictions.ENABLED
      UnusedAppRestrictionsConstants.DISABLED -> UnusedAppRestrictions.DISABLED
      else -> UnusedAppRestrictions.NOT_AVAILABLE
    }
  }

  private object DirectExecutor : Executor {
    override fun execute(command: Runnable) = command.run()
  }
}
