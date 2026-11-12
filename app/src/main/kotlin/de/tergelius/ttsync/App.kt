package de.tergelius.ttsync

import android.app.Application
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import de.tergelius.ttsync.sync.SyncNotifier

/**
 * Creates the [AppGraph] and the notification channels (DESIGN.md section 8).
 *
 * Phase 5 adds `graph.syncScheduler.ensurePeriodic()` here (periodic work with `KEEP` at app start).
 */
class App : Application() {
  lateinit var graph: AppGraph
    private set

  override fun onCreate() {
    super.onCreate()
    graph = AppGraph(this)
    createNotificationChannels()
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
