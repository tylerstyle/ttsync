package de.tergelius.ttsync.settings

import android.content.Context
import de.tergelius.ttsync.calendar.TargetCalendar
import de.tergelius.ttsync.sync.SyncReport
import kotlinx.coroutines.flow.Flow

/*
 * Android implementations of the settings interfaces. Phase 5 implements them; until then every
 * method throws. Constructing them is cheap and safe (AppGraph creates them lazily).
 */

/** [SettingsRepository] on Preferences DataStore. */
class DataStoreSettingsRepository(context: Context) : SettingsRepository {
  private val appContext = context.applicationContext

  override val settings: Flow<Settings>
    get() = notYet()

  override suspend fun current(): Settings = notYet()

  override suspend fun setSources(sources: List<SourceCalendar>): Unit = notYet()

  override suspend fun setTarget(target: TargetCalendar, displayName: String): Unit = notYet()

  override suspend fun setInterval(interval: SyncInterval): Unit = notYet()

  override suspend fun setKeepRunningDone(done: Boolean): Unit = notYet()

  override suspend fun installUuid(): String = notYet()

  override suspend fun clear(): Unit = notYet()

  private fun notYet(): Nothing = TODO("phase 5: SettingsRepository (${appContext.packageName})")
}

/** [StatusRepository] on Preferences DataStore (the report stored as JSON). */
class DataStoreStatusRepository(context: Context) : StatusRepository {
  private val appContext = context.applicationContext

  override val status: Flow<SyncStatus>
    get() = notYet()

  override suspend fun current(): SyncStatus = notYet()

  override suspend fun recordRun(report: SyncReport): Unit = notYet()

  override suspend fun recordAuthRejected(nowMillis: Long): Unit = notYet()

  override suspend fun recordLoginSucceeded(): Unit = notYet()

  override suspend fun setRateLimitedUntil(untilMillis: Long?): Unit = notYet()

  override suspend fun setFailureNotified(notified: Boolean): Unit = notYet()

  override suspend fun clear(): Unit = notYet()

  private fun notYet(): Nothing = TODO("phase 5: StatusRepository (${appContext.packageName})")
}

/** [CredentialStore] with an AndroidKeyStore AES-256-GCM key. */
class KeystoreCredentialStore(context: Context) : CredentialStore {
  private val appContext = context.applicationContext

  override suspend fun load(): Credentials? = notYet()

  override suspend fun save(credentials: Credentials): Unit = notYet()

  override suspend fun updateSessionId(sessionId: String): Unit = notYet()

  override suspend fun clear(): Unit = notYet()

  private fun notYet(): Nothing = TODO("phase 5: CredentialStore (${appContext.packageName})")

  companion object {
    const val FILE_NAME = "credentials.enc"
    const val KEY_ALIAS = "ttsync_credentials"
  }
}
