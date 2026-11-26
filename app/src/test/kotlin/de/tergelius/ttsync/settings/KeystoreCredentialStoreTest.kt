package de.tergelius.ttsync.settings

import de.tergelius.ttsync.TtLog
import java.io.File
import java.nio.file.Files
import java.security.ProviderException
import java.security.UnrecoverableKeyException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [KeystoreCredentialStore]'s file handling with a software AES key in place of AndroidKeyStore,
 * which only exists on a phone. Device test step 6: sign-out must delete the key alias as well.
 */
class KeystoreCredentialStoreTest {
  private val dir: File = Files.createTempDirectory("credentials").toFile()
  private val keys = SoftwareKeys()
  private var directoryReads = 0
  private val credentials = Credentials("me@example.org", "hunter2-secret", "session-1")

  private fun store() =
    KeystoreCredentialStore({ directoryReads++; dir }, keys, TtLog.None)

  private val file: File
    get() = File(dir, KeystoreCredentialStore.FILE_NAME)

  @After fun cleanUp() = dir.deleteRecursively().let {}

  @Test
  fun savedCredentialsAreEncryptedAndReadBack() = runTest {
    store().save(credentials)
    assertTrue(file.exists())
    assertFalse("the file is encrypted", file.readText(Charsets.ISO_8859_1).contains("hunter2"))
    assertEquals(credentials, store().load()) // a new instance reads the file
  }

  @Test
  fun signOutDeletesTheFileAndTheKeyAlias() = runTest {
    val store = store()
    store.save(credentials)
    assertTrue(keys.key != null)
    store.clear()
    assertFalse(file.exists())
    assertNull("the key alias is deleted", keys.key)
    assertEquals(1, keys.deletes)
    assertNull(store.load())
    assertNull(store().load())
  }

  @Test
  fun anUnreadableFileIsWipedWithItsKey() = runTest {
    store().save(credentials)
    file.writeBytes(ByteArray(64) { 7 })
    assertNull(store().load())
    assertFalse(file.exists())
    assertNull(keys.key)
  }

  @Test
  fun aLostKeyMeansSignInAgain() = runTest {
    store().save(credentials)
    keys.key = null // e.g. the Keystore was reset
    assertNull(store().load())
    assertFalse(file.exists())
  }

  @Test
  fun aPassingKeystoreErrorKeepsTheFileAndIsNotRemembered() = runTest {
    // Review 2026-11, F4: a busy Keystore used to wipe the sign-in for good.
    store().save(credentials)
    val store = store()
    keys.failExisting = ProviderException("keystore busy")
    assertNull(store.load())
    assertTrue(file.exists())
    assertTrue(keys.key != null)
    assertEquals(0, keys.deletes)
    keys.failExisting = null
    assertEquals(credentials, store.load()) // the same instance reads again
  }

  /** Stands in for android.security.KeyStoreException (API 33), which the store asks by reflection. */
  class KeystoreFailure(private val transient: Boolean) : Exception("keystore") {
    @Suppress("unused") fun isTransientFailure(): Boolean = transient
  }

  @Test
  fun anUnrecoverableKeyOnlyCountsAsLostWhenItsCauseIsPermanent() = runTest {
    // Android 12+ wraps a locked or busy Keystore in UnrecoverableKeyException too.
    store().save(credentials)
    for (cause in listOf(null, KeystoreFailure(transient = true), IllegalStateException("old Android"))) {
      keys.failExisting = UnrecoverableKeyException("failed to obtain key").apply { cause?.let(::initCause) }
      assertNull(store().load())
      assertTrue("kept with cause $cause", file.exists())
    }
    keys.failExisting = UnrecoverableKeyException("failed").apply { initCause(KeystoreFailure(transient = false)) }
    assertNull(store().load())
    assertFalse(file.exists())
  }

  @Test
  fun aFailedKeyReplacementKeepsTheOldKeyAndFile() = runTest {
    store().save(credentials)
    val store = store()
    assertEquals(credentials, store.load())
    keys.failExisting = ProviderException("keystore busy")
    keys.failCreate = ProviderException("still busy")
    try {
      store.updateSessionId("session-2")
      fail("expected the Keystore error")
    } catch (_: ProviderException) {}
    assertEquals(0, keys.deletes)
    keys.failExisting = null
    keys.failCreate = null
    assertEquals(credentials, store().load()) // the old file still decrypts
  }

  @Test
  fun theFolderIsOnlyLookedUpWhenNeeded() = runTest {
    val store = store()
    assertEquals(0, directoryReads) // not in the constructor (phase 5 review, NIT-2)
    store.load()
    assertEquals(1, directoryReads)
    store.save(credentials)
    assertEquals(1, directoryReads)
  }

  private class SoftwareKeys : CredentialKeys {
    var key: SecretKey? = null
    var deletes = 0
    var failExisting: Exception? = null
    var failCreate: Exception? = null

    override fun existing(): SecretKey? {
      failExisting?.let { throw it }
      return key
    }

    override fun create(): SecretKey {
      failCreate?.let { throw it }
      return KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().also { key = it }
    }

    override fun delete() {
      deletes++
      key = null
    }
  }
}
