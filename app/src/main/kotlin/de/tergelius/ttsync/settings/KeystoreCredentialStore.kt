package de.tergelius.ttsync.settings

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.core.util.AtomicFile
import de.tergelius.ttsync.TtLog
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * [CredentialStore] with an AndroidKeyStore AES-256-GCM key (DESIGN.md section 6).
 *
 * - The key ([KEY_ALIAS]) is non-exportable and needs no user authentication, because background
 *   runs must decrypt without the user.
 * - `noBackupFilesDir/`[FILE_NAME] holds `[12-byte IV][ciphertext + 16-byte GCM tag]` of the JSON
 *   `{email, password, sessionId}`, written with [AtomicFile], so a crash never leaves half a file.
 * - If the file is lost for good (no key, a key that no longer fits, a corrupt file), the file and
 *   the key are wiped and [load] returns null: the app asks for sign-in again.
 * - Any other Keystore failure may pass (the Keystore service can be busy or restarting): [load]
 *   returns null for this call only, keeps the file and tries again at the next call (review
 *   2026-11, F4).
 * - [clear] (sign-out) deletes the file and the key alias.
 *
 * The decrypted value is cached in memory after the first [load], so the client's per-request
 * session lookups do not hit the Keystore. All file and Keystore work runs on `Dispatchers.IO`;
 * a mutex keeps read-modify-write ([updateSessionId]) atomic. Nothing here is ever logged except
 * exception class names.
 */
class KeystoreCredentialStore internal constructor(
  /** The folder of [FILE_NAME]; read lazily, on `Dispatchers.IO` (phase 5 review, NIT-2). */
  private val directory: () -> File,
  private val keys: CredentialKeys,
  private val log: TtLog,
) : CredentialStore {
  constructor(
    context: Context,
    log: TtLog = TtLog.None,
  ) : this({ context.applicationContext.noBackupFilesDir }, AndroidKeystoreKeys(log), log)

  /** Created on first use, inside an IO block: `noBackupFilesDir` may create the folder. */
  private val file by lazy { AtomicFile(File(directory(), FILE_NAME)) }
  private val mutex = Mutex()

  /** What the file holds, once read; null until then. */
  @Volatile private var cache: Loaded? = null

  private class Loaded(val credentials: Credentials?)

  override suspend fun load(): Credentials? = mutex.withLock { loadLocked() }

  override suspend fun save(credentials: Credentials) {
    mutex.withLock {
      io { write(credentials) }
      cache = Loaded(credentials)
    }
  }

  override suspend fun updateSessionId(sessionId: String) {
    mutex.withLock {
      val current = loadLocked() ?: return
      if (current.sessionId == sessionId) return
      val updated = current.copy(sessionId = sessionId)
      io { write(updated) }
      cache = Loaded(updated)
    }
  }

  override suspend fun clear() {
    mutex.withLock {
      io { wipe() }
      cache = Loaded(null)
    }
  }

  private suspend fun loadLocked(): Credentials? {
    cache?.let {
      return it.credentials
    }
    // Null: unreadable right now. Nothing is cached, so the next call reads again.
    val loaded = io { read() } ?: return null
    cache = loaded
    return loaded.credentials
  }

  private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

  // ---------------------------------------------------------------------------------------------
  // Blocking helpers (called on Dispatchers.IO with the mutex held)

  /** What the file holds, or null if the Keystore cannot tell right now (nothing is wiped then). */
  private fun read(): Loaded? {
    val blob =
      try {
        file.readFully()
      } catch (_: FileNotFoundException) {
        return Loaded(null)
      }
    val plain =
      try {
        decrypt(blob)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        if (!isLostForGood(e)) {
          log.w("credentials not readable now (${e.javaClass.simpleName}); kept, trying again later")
          return null
        }
        return lost(e)
      }
    return try {
      Loaded(decode(plain))
    } catch (e: IllegalArgumentException) {
      // Decrypted but not our JSON (SerializationException is one of these): a corrupt file.
      lost(e)
    } finally {
      plain.fill(0)
    }
  }

  private fun lost(e: Exception): Loaded {
    log.w("credentials unreadable (${e.javaClass.simpleName}); wiped, sign-in needed")
    wipe()
    return Loaded(null)
  }

  /**
   * True if no later attempt can decrypt the file: it is too short or the key is gone ([Lost]), the
   * key does not fit or the file is damaged (the GCM tag fails), or Android invalidated the key.
   * Everything else, such as a `ProviderException` or `KeyStoreException` of a busy Keystore, may
   * pass.
   *
   * `UnrecoverableKeyException` alone says nothing: since Android 12 it wraps every Keystore error
   * of `getKey`, a locked or busy Keystore included. It counts only if its cause says that the
   * failure is not transient (`android.security.KeyStoreException.isTransientFailure`, Android 13).
   * A file that is kept although its key is gone costs nothing: the next sign-in replaces both.
   */
  private fun isLostForGood(e: Exception): Boolean =
    e is Lost ||
      e is AEADBadTagException ||
      (e is UnrecoverableKeyException && causeIsPermanent(e)) ||
      // By name: android.* classes are not on the classpath of the JVM tests.
      e.javaClass.name == "android.security.keystore.KeyPermanentlyInvalidatedException"

  /** By reflection: the method is on an android.* class and exists from API 33 on. */
  private fun causeIsPermanent(e: Exception): Boolean {
    val cause = e.cause ?: return false
    return try {
      cause.javaClass.getMethod("isTransientFailure").invoke(cause) == false
    } catch (_: ReflectiveOperationException) {
      false
    }
  }

  /** The file can never be decrypted. */
  private class Lost(message: String) : GeneralSecurityException(message)

  private fun write(credentials: Credentials) {
    val plain = encode(credentials)
    val blob =
      try {
        try {
          encrypt(plain, keys.existing() ?: keys.create())
        } catch (e: GeneralSecurityException) {
          retryWithNewKey(plain, e)
        } catch (e: ProviderException) {
          retryWithNewKey(plain, e)
        }
      } finally {
        plain.fill(0)
      }
    val out = file.startWrite()
    try {
      out.write(blob)
      file.finishWrite(out)
    } catch (e: IOException) {
      file.failWrite(out)
      throw e
    }
  }

  /**
   * A broken key: replace it once. The old file is unreadable with the new key, which is fine: it
   * is about to be overwritten. The old key is not deleted first; [CredentialKeys.create] replaces
   * it, so if no new key can be made the old one, and with it the old file, stays usable.
   */
  private fun retryWithNewKey(plain: ByteArray, cause: Exception): ByteArray {
    log.w("credential key unusable (${cause.javaClass.simpleName}); creating a new one")
    return encrypt(plain, keys.create())
  }

  /** The file and the key alias (sign-out, or an unreadable file). */
  private fun wipe() {
    file.delete()
    keys.delete()
  }

  private fun encrypt(plain: ByteArray, key: SecretKey): ByteArray {
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.ENCRYPT_MODE, key)
    val iv = cipher.iv
    if (iv == null || iv.size != IV_BYTES) throw GeneralSecurityException("unexpected IV size")
    return iv + cipher.doFinal(plain)
  }

  private fun decrypt(blob: ByteArray): ByteArray {
    if (blob.size <= IV_BYTES + TAG_BITS / 8) throw Lost("file too short")
    val key = keys.existing() ?: throw Lost("no key")
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, blob, 0, IV_BYTES))
    return cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES)
  }

  /** The encrypted payload. Never logged. */
  @Serializable
  private class Payload(val email: String, val password: String, val sessionId: String? = null) {
    override fun toString(): String = "Payload(<redacted>)"
  }

  private fun encode(credentials: Credentials): ByteArray =
    JSON.encodeToString(
        Payload.serializer(),
        Payload(credentials.email, credentials.password, credentials.sessionId),
      )
      .encodeToByteArray()

  private fun decode(plain: ByteArray): Credentials {
    val payload = JSON.decodeFromString(Payload.serializer(), plain.decodeToString())
    return Credentials(payload.email, payload.password, payload.sessionId?.takeIf { it.isNotEmpty() })
  }

  companion object {
    const val FILE_NAME = "credentials.enc"
    const val KEY_ALIAS = "ttsync_credentials"

    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private val JSON = Json { ignoreUnknownKeys = true }
  }
}

/** The credential key: [AndroidKeystoreKeys] on the phone, a software key in JVM tests. */
internal interface CredentialKeys {
  /** The key, or null if there is none. */
  fun existing(): SecretKey?

  /** Generates a new key under the alias, replacing any old one. */
  fun create(): SecretKey

  /** Deletes the alias; never throws. */
  fun delete()
}

/**
 * The non-exportable AndroidKeyStore AES-256-GCM key [KeystoreCredentialStore.KEY_ALIAS], without
 * user authentication (background runs decrypt while the phone is locked).
 */
internal class AndroidKeystoreKeys(private val log: TtLog) : CredentialKeys {
  private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

  override fun existing(): SecretKey? =
    keyStore().getKey(KeystoreCredentialStore.KEY_ALIAS, null) as? SecretKey

  override fun create(): SecretKey {
    val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
    generator.init(
      KeyGenParameterSpec.Builder(
          KeystoreCredentialStore.KEY_ALIAS,
          KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setKeySize(256)
        // Background runs decrypt without the user, so no user authentication.
        .setUserAuthenticationRequired(false)
        .build()
    )
    return generator.generateKey()
  }

  override fun delete() {
    try {
      keyStore().deleteEntry(KeystoreCredentialStore.KEY_ALIAS)
    } catch (e: Exception) {
      log.w("credential key not deleted (${e.javaClass.simpleName})")
    }
  }

  private companion object {
    const val ANDROID_KEYSTORE = "AndroidKeyStore"
  }
}
