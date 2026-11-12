package de.tergelius.ttsync

import android.app.Application
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import de.tergelius.ttsync.sync.SyncNotifier
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.launch

/**
 * Creates the [AppGraph] and the notification channels (DESIGN.md section 8), restores a rate
 * limit that is still running, and makes sure the periodic work exists (`KEEP`, only if setup is
 * far enough; see [de.tergelius.ttsync.sync.WorkManagerSyncScheduler]).
 */
class App : Application() {
  lateinit var graph: AppGraph
    private set

  override fun onCreate() {
    super.onCreate()
    graph = AppGraph(this)
    createNotificationChannels()
    graph.appScope.launch {
      try {
        graph.restoreRateLimit()
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        graph.log.w("rate limit not restored (${e.javaClass.simpleName})")
      }
    }
    graph.syncScheduler.ensurePeriodic()
  }

  private fun createNotificationChannels() {
    val problems =
      NotificationChannelCompat.Builder(
          SyncNotifier.CHANNEL_PROBLEMS,
          NotificationManagerCompat.IMPORTANCE_DEFAULT,
        )
        .setName(getString(R.string.channel_sync_problems_name))
        .setDescription(getString(R.string.channel_sync_problems_description))
        .build()
    val running =
      NotificationChannelCompat.Builder(
          SyncNotifier.CHANNEL_RUNNING,
          NotificationManagerCompat.IMPORTANCE_LOW,
        )
        .setName(getString(R.string.channel_sync_running_name))
        .setDescription(getString(R.string.channel_sync_running_description))
        .setShowBadge(false)
        .build()
    NotificationManagerCompat.from(this).createNotificationChannelsCompat(listOf(problems, running))
  }
}
