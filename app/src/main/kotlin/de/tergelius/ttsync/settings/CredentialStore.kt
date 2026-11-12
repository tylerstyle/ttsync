package de.tergelius.ttsync.settings

/**
 * Encrypted TimeTree credentials (DESIGN.md section 6). Phase 5 implements it as
 * `KeystoreCredentialStore`: a non-exportable AES-256-GCM AndroidKeyStore key without user
 * authentication encrypts [Credentials] into `noBackupFilesDir/credentials.enc`, laid out as
 * `[12-byte IV][ciphertext + 16-byte GCM tag]` and written with `AtomicFile`.
 *
 * Contract: [load] returns null if nothing is stored; if decryption fails it wipes the file and the
 * key and returns null (the app then asks for sign-in). Nothing here is ever logged. No
 * `android.*` in this interface, so the sync engine can depend on it.
 */
interface CredentialStore {
  suspend fun load(): Credentials?

  suspend fun save(credentials: Credentials)

  /** Replaces only the session id (cookie rotation, automatic re-login). No-op if nothing is stored. */
  suspend fun updateSessionId(sessionId: String)

  /** Sign-out: deletes the file and the Keystore key. */
  suspend fun clear()
}

/** Stored so the app can sign in again when the ~14-day session ends. Never logged. */
data class Credentials(val email: String, val password: String, val sessionId: String?) {
  override fun toString(): String = "Credentials(<redacted>)"
}
