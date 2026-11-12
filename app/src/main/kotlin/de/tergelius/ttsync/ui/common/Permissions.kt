package de.tergelius.ttsync.ui.common

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat

/** `READ_CALENDAR` and `WRITE_CALENDAR`, requested together on the Target screen. */
val CalendarPermissions =
  arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)

/**
 * Returns a function that asks for the calendar permission. If the user had denied it for good
 * before (Android showed no dialog), it opens the app's settings page instead. [onResult] gets
 * whether both permissions are now granted.
 */
@Composable
fun rememberCalendarPermissionRequest(onResult: (Boolean) -> Unit): () -> Unit {
  val activity = LocalActivity.current
  val context = LocalContext.current
  val currentOnResult by rememberUpdatedState(onResult)
  val hadRationale = rememberSaveable { mutableStateOf(false) }
  val launcher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result
      ->
      val granted = CalendarPermissions.all { result[it] == true }
      if (!granted && deniedForGood(activity, CalendarPermissions[0], hadRationale.value)) {
        SystemIntents.start(context, SystemIntents.appDetails(context))
      }
      currentOnResult(granted)
    }
  return {
    hadRationale.value = showsRationale(activity, CalendarPermissions[0])
    launcher.launch(CalendarPermissions)
  }
}

private fun showsRationale(activity: Activity?, permission: String): Boolean =
  activity != null && ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)

/**
 * Android showed no dialog because [permission] was already denied for good: no rationale before
 * the request ([hadRationale]) and none after. A denial in the dialog itself, even the second one
 * that makes it final, does not count (phase 5 review, SHOULD-FIX-4): the user just said no, so
 * sending them to Settings would be pushy.
 */
private fun deniedForGood(activity: Activity?, permission: String, hadRationale: Boolean): Boolean =
  activity != null && !hadRationale && !showsRationale(activity, permission)

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
  val hadRationale = rememberSaveable { mutableStateOf(false) }
  val launcher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
      if (
        !granted &&
          Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
          deniedForGood(activity, Manifest.permission.POST_NOTIFICATIONS, hadRationale.value)
      ) {
        SystemIntents.start(context, SystemIntents.notificationSettings(context))
      }
      currentOnResult()
    }
  return {
    if (permissionMissing && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      hadRationale.value = showsRationale(activity, Manifest.permission.POST_NOTIFICATIONS)
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
