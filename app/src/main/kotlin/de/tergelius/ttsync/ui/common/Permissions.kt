package de.tergelius.ttsync.ui.common

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat

/** `READ_CALENDAR` and `WRITE_CALENDAR`, requested together on the Target screen. */
val CalendarPermissions =
  arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)

/**
 * Returns a function that asks for the calendar permission. If the user denied it for good (no
 * rationale is shown any more), it opens the app's settings page instead. [onResult] gets whether
 * both permissions are now granted.
 */
@Composable
fun rememberCalendarPermissionRequest(onResult: (Boolean) -> Unit): () -> Unit {
  val activity = LocalActivity.current
  val context = LocalContext.current
  val currentOnResult by rememberUpdatedState(onResult)
  val launcher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result
      ->
      val granted = CalendarPermissions.all { result[it] == true }
      if (
        !granted &&
          activity != null &&
          !ActivityCompat.shouldShowRequestPermissionRationale(activity, CalendarPermissions[0])
      ) {
        SystemIntents.start(context, SystemIntents.appDetails(context))
      }
      currentOnResult(granted)
    }
  return { launcher.launch(CalendarPermissions) }
}

/**
 * Returns a function that turns notifications on: on API 33+ it asks for `POST_NOTIFICATIONS` if
 * [permissionMissing], otherwise (or after a final denial) it opens the app's notification
 * settings, where a switched-off app or channel can be turned back on.
 */
@Composable
fun rememberNotificationRequest(permissionMissing: Boolean, onResult: () -> Unit): () -> Unit {
  val activity = LocalActivity.current
  val context = LocalContext.current
  val currentOnResult by rememberUpdatedState(onResult)
  val launcher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
      if (
        !granted &&
          activity != null &&
          Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
          !ActivityCompat.shouldShowRequestPermissionRationale(
            activity,
            Manifest.permission.POST_NOTIFICATIONS,
          )
      ) {
        SystemIntents.start(context, SystemIntents.notificationSettings(context))
      }
      currentOnResult()
    }
  return {
    if (permissionMissing && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    } else {
      SystemIntents.start(context, SystemIntents.notificationSettings(context))
    }
  }
}

/**
 * Returns a function that starts settings screens "for result" (required for the unused-app
 * restrictions screen, harmless for the others), trying each intent in turn and ending with the
 * app's details page. [onReturn] runs when the user comes back.
 */
@Composable
fun rememberSettingsLauncher(onReturn: () -> Unit = {}): (List<Intent>) -> Unit {
  val context = LocalContext.current
  val currentOnReturn by rememberUpdatedState(onReturn)
  val launcher =
    rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
      currentOnReturn()
    }
  return { intents ->
    val candidates = intents + SystemIntents.appDetails(context)
    for (intent in candidates) {
      val started =
        try {
          launcher.launch(intent)
          true
        } catch (_: ActivityNotFoundException) {
          false
        } catch (_: SecurityException) {
          false
        }
      if (started) break
    }
  }
}
