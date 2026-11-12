package de.tergelius.ttsync.settings

import de.tergelius.ttsync.timetree.TtCredentials
import de.tergelius.ttsync.timetree.TtSessionStore

/**
 * [TtSessionStore] on top of the settings layer: the session and credentials from
 * [CredentialStore], the install uuid from [SettingsRepository], and the auth block from
 * [StatusRepository] ([SyncStatus.mayAutoLogin]).
 */
class StoredSessionStore(
  private val credentials: CredentialStore,
  private val settings: SettingsRepository,
  private val status: StatusRepository,
  private val nowMillis: () -> Long = System::currentTimeMillis,
) : TtSessionStore {
  override suspend fun installUuid(): String = settings.installUuid()

  /** An empty stored value counts as no session (phase 2 review, NIT-1). */
  override suspend fun sessionId(): String? =
    credentials.load()?.sessionId?.takeIf { it.isNotEmpty() }

  override suspend fun onSessionRotated(sessionId: String) {
    credentials.updateSessionId(sessionId)
  }

  override suspend fun autoLoginCredentials(): TtCredentials? {
    if (!status.current().mayAutoLogin(nowMillis())) return null
    return credentials.load()?.let { TtCredentials(it.email, it.password) }
  }

  override suspend fun onAutoLoginSucceeded(sessionId: String) {
    credentials.updateSessionId(sessionId)
    status.recordLoginSucceeded()
  }

  override suspend fun onAutoLoginRejected() {
    status.recordAuthRejected(nowMillis())
  }
}
