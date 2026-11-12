package de.tergelius.ttsync.sync

import android.app.Notification
import android.content.Context

/**
 * Notifications (DESIGN.md sections 8 and 9). Phase 5 implements it as [AndroidSyncNotifier]. The
 * channels themselves are created by `App` at startup.
 *
 * Contract:
 * - There is only ever one failure notification ([ID_FAILURE] on [CHANNEL_PROBLEMS]). It appears
 *   after 3 failed runs in a row, when the last success is over 24 h old, or at once for a problem
 *   the user must fix; the next successful run clears it.
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
  }
}

/** Phase 5 implements this. */
class AndroidSyncNotifier(context: Context) : SyncNotifier {
  private val appContext = context.applicationContext

  override fun showFailure(failure: SyncFailure): Unit = notYet()

  override fun clearFailure(): Unit = notYet()

  override fun showAccessLost(ttCalId: Long, calendarName: String?): Unit = notYet()

  override fun clearAccessLost(ttCalId: Long): Unit = notYet()

  override fun runningNotification(): Notification = notYet()

  private fun notYet(): Nothing = TODO("phase 5: SyncNotifier (${appContext.packageName})")
}
