package de.tergelius.ttsync.settings

import de.tergelius.ttsync.timetree.TtCredentials
import de.tergelius.ttsync.timetree.TtSessionStore

/**
 * [TtSessionStore] on top of the settings layer (phase 5): the session and credentials from
 * [CredentialStore], the install uuid from [SettingsRepository], and the auth block from
 * [StatusRepository] ([SyncStatus.mayAutoLogin]).
 */
class StoredSessionStore(
  private val credentials: CredentialStore,
  private val settings: SettingsRepository,
  private val status: StatusRepository,
  private val nowMillis: () -> Long = System::currentTimeMillis,
) : TtSessionStore {
  override suspend fun installUuid(): String = notYet()

  override suspend fun sessionId(): String? = notYet()

  override suspend fun onSessionRotated(sessionId: String): Unit = notYet()

  override suspend fun autoLoginCredentials(): TtCredentials? = notYet()

  override suspend fun onAutoLoginSucceeded(sessionId: String): Unit = notYet()

  override suspend fun onAutoLoginRejected(): Unit = notYet()

  private fun notYet(): Nothing =
    TODO("phase 5: StoredSessionStore ($credentials, $settings, $status, ${nowMillis()})")
}
