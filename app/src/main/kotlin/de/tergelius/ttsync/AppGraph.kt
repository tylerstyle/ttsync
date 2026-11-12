package de.tergelius.ttsync

import android.content.Context
import androidx.lifecycle.ViewModelProvider
import de.tergelius.ttsync.calendar.CalendarGateway
import de.tergelius.ttsync.calendar.ProviderCalendarGateway
import de.tergelius.ttsync.mapping.DefaultEventMapper
import de.tergelius.ttsync.mapping.EventMapper
import de.tergelius.ttsync.settings.CredentialStore
import de.tergelius.ttsync.settings.DataStoreSettingsRepository
import de.tergelius.ttsync.settings.DataStoreStatusRepository
import de.tergelius.ttsync.settings.KeystoreCredentialStore
import de.tergelius.ttsync.settings.SettingsRepository
import de.tergelius.ttsync.settings.StatusRepository
import de.tergelius.ttsync.settings.StoredSessionStore
import de.tergelius.ttsync.store.SyncDatabase
import de.tergelius.ttsync.store.SyncDb
import de.tergelius.ttsync.sync.AndroidSyncNotifier
import de.tergelius.ttsync.sync.DefaultSyncEngine
import de.tergelius.ttsync.sync.SyncEngine
import de.tergelius.ttsync.sync.SyncNotifier
import de.tergelius.ttsync.sync.SyncScheduler
import de.tergelius.ttsync.sync.WorkManagerSyncScheduler
import de.tergelius.ttsync.timetree.DefaultRequestPacer
import de.tergelius.ttsync.timetree.OkHttpTimeTreeClient
import de.tergelius.ttsync.timetree.RawResponseSink
import de.tergelius.ttsync.timetree.RequestPacer
import de.tergelius.ttsync.timetree.TimeTreeApi
import de.tergelius.ttsync.timetree.TimeTreeClient
import de.tergelius.ttsync.timetree.TtSessionStore
import de.tergelius.ttsync.ui.appViewModelFactory
import okhttp3.OkHttpClient

/**
 * Manual dependency injection (no Hilt). One instance per process, owned by [App]; reach it with
 * [appGraph]. Everything is created lazily on first use, so building the graph is cheap and a
 * component is only constructed when something needs it. Every property is a process-wide
 * singleton; in particular [syncEngine] holds the one mutex that serializes all runs.
 *
 * ViewModels and the worker take what they need from here; tests construct the classes directly
 * with fakes instead of using the graph.
 */
class AppGraph(context: Context) {
  private val appContext: Context = context.applicationContext

  val log: TtLog = AndroidTtLog

  // timetree
  val okHttpClient: OkHttpClient by lazy { TimeTreeApi.newOkHttpClient() }
  val rawResponseSink: RawResponseSink by lazy { BuildVariant.rawResponseSink(appContext) }
  val requestPacer: RequestPacer by lazy { DefaultRequestPacer() }
  val sessionStore: TtSessionStore by lazy {
    StoredSessionStore(credentialStore, settingsRepository, statusRepository)
  }
  val timeTreeClient: TimeTreeClient by lazy {
    OkHttpTimeTreeClient(okHttpClient, sessionStore, requestPacer, rawResponseSink)
  }

  // settings
  val settingsRepository: SettingsRepository by lazy { DataStoreSettingsRepository(appContext) }
  val statusRepository: StatusRepository by lazy { DataStoreStatusRepository(appContext) }
  val credentialStore: CredentialStore by lazy { KeystoreCredentialStore(appContext) }

  // mapping, store, calendar
  val eventMapper: EventMapper by lazy { DefaultEventMapper() }
  val syncDatabase: SyncDatabase by lazy { SyncDb(appContext) }
  val calendarGateway: CalendarGateway by lazy { ProviderCalendarGateway(appContext) }

  // sync
  val syncEngine: SyncEngine by lazy {
    DefaultSyncEngine(
      client = timeTreeClient,
      mapper = eventMapper,
      db = syncDatabase,
      gateway = calendarGateway,
      settings = settingsRepository,
      status = statusRepository,
      credentials = credentialStore,
      log = log,
    )
  }
  val syncScheduler: SyncScheduler by lazy { WorkManagerSyncScheduler(appContext) }
  val syncNotifier: SyncNotifier by lazy { AndroidSyncNotifier(appContext) }

  // ui
  val viewModelFactory: ViewModelProvider.Factory by lazy { appViewModelFactory(this, appContext) }
}

/** The process-wide [AppGraph]. */
val Context.appGraph: AppGraph
  get() = (applicationContext as App).graph
