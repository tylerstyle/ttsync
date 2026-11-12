package de.tergelius.ttsync.settings

import de.tergelius.ttsync.timetree.TtCredentials
import de.tergelius.ttsync.ui.Calls
import de.tergelius.ttsync.ui.FakeCredentials
import de.tergelius.ttsync.ui.FakeSettings
import de.tergelius.ttsync.ui.FakeStatus
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StoredSessionStoreTest {
  private val now = 1_794_481_200_000L
  private val calls = Calls()
  private val credentials = FakeCredentials(calls, Credentials("me@example.org", "secret", "sess-1"))
  private val status = FakeStatus(calls)
  private val store = StoredSessionStore(credentials, FakeSettings(calls), status) { now }

  @Test
  fun sessionComesFromTheCredentialStoreAndAnEmptyOneIsNone() = runTest {
    assertEquals("sess-1", store.sessionId())
    store.onSessionRotated("sess-2")
    assertEquals("sess-2", credentials.stored?.sessionId)
    credentials.stored = credentials.stored?.copy(sessionId = "")
    assertNull(store.sessionId())
    credentials.stored = null
    assertNull(store.sessionId())
  }

  @Test
  fun automaticLoginsFollowTheAuthBlock() = runTest {
    assertEquals(TtCredentials("me@example.org", "secret"), store.autoLoginCredentials())

    status.flow.value = SyncStatus(authRejections = 1, autoLoginBlockedUntil = now + 1)
    assertNull(store.autoLoginCredentials())

    status.flow.value = SyncStatus(authRejections = 1, autoLoginBlockedUntil = now)
    assertEquals(TtCredentials("me@example.org", "secret"), store.autoLoginCredentials())

    status.flow.value = SyncStatus(authRejections = 2) // stopped until the user signs in
    assertNull(store.autoLoginCredentials())

    store.onAutoLoginSucceeded("sess-3")
    assertEquals("sess-3", credentials.stored?.sessionId)
    assertEquals(0, status.flow.value.authRejections)
  }

  @Test
  fun aRejectedAutomaticLoginIsRecorded() = runTest {
    store.onAutoLoginRejected()
    assertEquals(1, status.flow.value.authRejections)
  }

  @Test
  fun installUuidComesFromSettings() = runTest {
    assertEquals("0".repeat(32), store.installUuid())
  }
}
