package de.tergelius.ttsync.settings

import de.tergelius.ttsync.TtLog
import java.io.File
import java.nio.file.Files
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    override fun existing(): SecretKey? = key

    override fun create(): SecretKey =
      KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().also { key = it }

    override fun delete() {
      deletes++
      key = null
    }
  }
}
