package de.tergelius.ttsync.sync

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import de.tergelius.ttsync.MainActivity
import de.tergelius.ttsync.R

/**
 * Notifications (DESIGN.md sections 8 and 9), implemented by [AndroidSyncNotifier]. The channels
 * themselves are created by `App` at startup.
 *
 * Contract:
 * - There is only ever one failure notification ([ID_FAILURE] on [CHANNEL_PROBLEMS]). It appears
 *   after 3 failed runs in a row, when the last success is over 24 h old, or at once for a problem
 *   the user must fix; the next successful run clears it. (`SyncWorker` decides when.)
 * - Lost access gets one notification per TimeTree calendar with a "Remove copies" action.
 * - Texts come from string resources (en/de) and carry no event content.
 * - Without the notification permission, calls do nothing (no crash).
 */
interface SyncNotifier {
  fun showFailure(failure: SyncFailure)

  fun clearFailure()

  /** [calendarName] is the user's own selection label, shown only in the notification. */
  fun showAccessLost(ttCalId: Long, calendarName: String?)

  fun clearAccessLost(ttCalId: Long)

  /** The ongoing notification for `SyncWorker.getForegroundInfo()` on [CHANNEL_RUNNING]. */
  fun runningNotification(): Notification

  companion object {
    const val CHANNEL_PROBLEMS = "sync_problems"
    const val CHANNEL_RUNNING = "sync_running"
    const val ID_FAILURE = 1
    const val ID_RUNNING = 2

    /** Access-lost notifications use `ID_ACCESS_LOST_BASE + (ttCalId % 100_000)`. */
    const val ID_ACCESS_LOST_BASE = 1_000

    fun accessLostId(ttCalId: Long): Int = ID_ACCESS_LOST_BASE + Math.floorMod(ttCalId, 100_000L).toInt()
  }
}

/**
 * [SyncNotifier] on `NotificationManagerCompat`. Every notification opens the app, which starts at
 * Status once setup is done; "Remove copies" also just opens Status, where the removal is
 * confirmed (copies are never deleted from a notification).
 */
class AndroidSyncNotifier(context: Context) : SyncNotifier {
  private val appContext = context.applicationContext
  private val manager = NotificationManagerCompat.from(appContext)

  override fun showFailure(failure: SyncFailure) {
    val text = problemText(failure)
    val notification =
      NotificationCompat.Builder(appContext, SyncNotifier.CHANNEL_PROBLEMS)
        .setSmallIcon(R.drawable.ic_error)
        .setContentTitle(appContext.getString(R.string.notification_failure_title))
        .setContentText(text)
        .setStyle(NotificationCompat.BigTextStyle().bigText(text))
        .setContentIntent(openApp(REQUEST_FAILURE))
        .setAutoCancel(true)
        .setOnlyAlertOnce(true)
        .setCategory(NotificationCompat.CATEGORY_ERROR)
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .build()
    post(SyncNotifier.ID_FAILURE, notification)
  }

  override fun clearFailure() {
    manager.cancel(SyncNotifier.ID_FAILURE)
  }

  override fun showAccessLost(ttCalId: Long, calendarName: String?) {
    val text =
      calendarName?.takeIf { it.isNotBlank() }
        ?.let { appContext.getString(R.string.notification_access_lost_text, it) }
        ?: appContext.getString(R.string.notification_access_lost_text_unnamed)
    val id = SyncNotifier.accessLostId(ttCalId)
    val open = openApp(REQUEST_ACCESS_LOST_BASE + id)
    val notification =
      NotificationCompat.Builder(appContext, SyncNotifier.CHANNEL_PROBLEMS)
        .setSmallIcon(R.drawable.ic_error)
        .setContentTitle(appContext.getString(R.string.notification_access_lost_title))
        .setContentText(text)
        .setStyle(NotificationCompat.BigTextStyle().bigText(text))
        .setContentIntent(open)
        .addAction(0, appContext.getString(R.string.action_remove_copies), open)
        .setAutoCancel(true)
        .setOnlyAlertOnce(true)
        .setCategory(NotificationCompat.CATEGORY_STATUS)
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .build()
    post(id, notification)
  }

  override fun clearAccessLost(ttCalId: Long) {
    manager.cancel(SyncNotifier.accessLostId(ttCalId))
  }

  override fun runningNotification(): Notification =
    NotificationCompat.Builder(appContext, SyncNotifier.CHANNEL_RUNNING)
      .setSmallIcon(R.drawable.ic_refresh)
      .setContentTitle(appContext.getString(R.string.notification_running_title))
      .setContentIntent(openApp(REQUEST_RUNNING))
      .setOngoing(true)
      .setSilent(true)
      .setCategory(NotificationCompat.CATEGORY_PROGRESS)
      .setPriority(NotificationCompat.PRIORITY_LOW)
      .build()

  private fun problemText(failure: SyncFailure): String =
    when (failure.problem) {
      SyncProblem.NOT_CONFIGURED -> appContext.getString(R.string.problem_not_configured)
      SyncProblem.NO_CALENDAR_PERMISSION -> appContext.getString(R.string.problem_no_permission)
      SyncProblem.TARGET_DELETED -> appContext.getString(R.string.problem_target_deleted)
      SyncProblem.TARGET_UNAVAILABLE -> appContext.getString(R.string.problem_target_unavailable)
      SyncProblem.NOT_SIGNED_IN -> appContext.getString(R.string.problem_not_signed_in)
      SyncProblem.AUTH_REJECTED -> appContext.getString(R.string.problem_auth_rejected)
      SyncProblem.RATE_LIMITED -> appContext.getString(R.string.problem_rate_limited)
      SyncProblem.NETWORK -> appContext.getString(R.string.problem_network)
      SyncProblem.API_CHANGED -> appContext.getString(R.string.error_api_changed)
      SyncProblem.CALENDAR_WRITE_FAILED -> appContext.getString(R.string.problem_write_failed)
      SyncProblem.INTERNAL ->
        failure.code?.let { appContext.getString(R.string.problem_internal_code, it) }
          ?: appContext.getString(R.string.problem_internal)
    }

  private fun openApp(requestCode: Int): PendingIntent =
    PendingIntent.getActivity(
      appContext,
      requestCode,
      Intent(appContext, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

  /** Posts only with the notification permission (API 33+); otherwise silently does nothing. */
  @SuppressLint("MissingPermission")
  private fun post(id: Int, notification: Notification) {
    if (
      Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) !=
          PackageManager.PERMISSION_GRANTED
    ) {
      return
    }
    try {
      manager.notify(id, notification)
    } catch (_: SecurityException) {
      // Permission revoked in between: nothing to show.
    }
  }

  private companion object {
    const val REQUEST_FAILURE = 1
    const val REQUEST_RUNNING = 2
    const val REQUEST_ACCESS_LOST_BASE = 10_000
  }
}
