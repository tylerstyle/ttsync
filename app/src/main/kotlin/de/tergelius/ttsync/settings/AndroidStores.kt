package de.tergelius.ttsync.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import de.tergelius.ttsync.calendar.TargetCalendar
import de.tergelius.ttsync.sync.SyncReport
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/*
 * Android implementations of the settings interfaces on Preferences DataStore. One DataStore per
 * file and process: the `preferencesDataStore` delegates below are the only instances, and AppGraph
 * creates each repository once. Both files live in `files/datastore/`, which backup excludes (the
 * app has backup off entirely). A corrupt file is replaced by empty preferences, which at worst
 * sends the user back through setup; it never deletes copies.
 */

private val Context.settingsStore: DataStore<Preferences> by
  preferencesDataStore(
    name = "settings",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
  )

private val Context.statusStore: DataStore<Preferences> by
  preferencesDataStore(
    name = "status",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
  )

/** Lenient JSON for the values stored as text: unknown keys from a newer version are ignored. */
private val StoreJson = Json { ignoreUnknownKeys = true }

/** Reads a JSON value, or null if it is missing or no longer decodes (then it is set up again). */
private fun <T> decodeOrNull(serializer: KSerializer<T>, text: String?): T? =
  text?.let {
    try {
      StoreJson.decodeFromString(serializer, it)
    } catch (_: SerializationException) {
      null
    } catch (_: IllegalArgumentException) {
      null
    }
  }

/** An unreadable file reads as empty instead of failing every collector. */
private fun DataStore<Preferences>.safeData(): Flow<Preferences> =
  data.catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }

/** [SettingsRepository] on Preferences DataStore. */
class DataStoreSettingsRepository(context: Context) : SettingsRepository {
  private val store = context.applicationContext.settingsStore

  override val settings: Flow<Settings> =
    store.safeData().map(::decode).distinctUntilChanged()

  override suspend fun current(): Settings = settings.first()

  override suspend fun setSources(sources: List<SourceCalendar>) {
    store.edit { it[SOURCES] = StoreJson.encodeToString(SOURCES_SERIALIZER, sources) }
  }

  override suspend fun setTarget(target: TargetCalendar, displayName: String) {
    store.edit {
      it[TARGET] = StoreJson.encodeToString(TargetCalendar.serializer(), target)
      it[TARGET_NAME] = displayName
    }
  }

  override suspend fun setInterval(interval: SyncInterval) {
    store.edit { it[INTERVAL] = interval.name }
  }

  override suspend fun setKeepRunningDone(done: Boolean) {
    store.edit { it[KEEP_RUNNING_DONE] = done }
  }

  override suspend fun installUuid(): String {
    store.safeData().first()[INSTALL_UUID]?.takeIf(::isInstallUuid)?.let {
      return it
    }
    var uuid = ""
    // Inside one edit, so two first callers cannot store two different values.
    store.edit { prefs ->
      uuid =
        prefs[INSTALL_UUID]?.takeIf(::isInstallUuid)
          ?: UUID.randomUUID().toString().replace("-", "").also { prefs[INSTALL_UUID] = it }
    }
    return uuid
  }

  override suspend fun clear() {
    store.edit { it.clear() }
  }

  private fun decode(prefs: Preferences): Settings =
    Settings(
      sources = decodeOrNull(SOURCES_SERIALIZER, prefs[SOURCES]).orEmpty(),
      target = decodeOrNull(TargetCalendar.serializer(), prefs[TARGET]),
      targetDisplayName = prefs[TARGET_NAME],
      interval =
        SyncInterval.entries.firstOrNull { it.name == prefs[INTERVAL] } ?: SyncInterval.DEFAULT,
      keepRunningDone = prefs[KEEP_RUNNING_DONE] ?: false,
    )

  private companion object {
    val SOURCES = stringPreferencesKey("sources")
    val TARGET = stringPreferencesKey("target")
    val TARGET_NAME = stringPreferencesKey("target_name")
    val INTERVAL = stringPreferencesKey("interval")
    val KEEP_RUNNING_DONE = booleanPreferencesKey("keep_running_done")
    val INSTALL_UUID = stringPreferencesKey("install_uuid")
    val SOURCES_SERIALIZER = ListSerializer(SourceCalendar.serializer())
    val HEX32 = Regex("[0-9a-f]{32}")

    fun isInstallUuid(value: String): Boolean = HEX32.matches(value)
  }
}

/** [StatusRepository] on Preferences DataStore (the report stored as JSON). */
class DataStoreStatusRepository(context: Context) : StatusRepository {
  private val store = context.applicationContext.statusStore

  override val status: Flow<SyncStatus> = store.safeData().map(::decode).distinctUntilChanged()

  override suspend fun current(): SyncStatus = status.first()

  override suspend fun recordRun(report: SyncReport) {
    store.edit { prefs ->
      prefs[LAST_REPORT] = StoreJson.encodeToString(SyncReport.serializer(), report)
      if (report.succeeded) {
        prefs[LAST_SUCCESS_AT] = report.finishedAt
        prefs.remove(CONSECUTIVE_FAILURES)
      } else {
        prefs[CONSECUTIVE_FAILURES] = (prefs[CONSECUTIVE_FAILURES] ?: 0) + 1
      }
    }
  }

  override suspend fun recordAuthRejected(nowMillis: Long) {
    store.edit { prefs ->
      prefs[AUTH_REJECTIONS] = (prefs[AUTH_REJECTIONS] ?: 0) + 1
      prefs[AUTO_LOGIN_BLOCKED_UNTIL] = nowMillis + SyncStatus.AUTH_BLOCK.inWholeMilliseconds
    }
  }

  override suspend fun recordLoginSucceeded() {
    store.edit { prefs ->
      prefs.remove(AUTH_REJECTIONS)
      prefs.remove(AUTO_LOGIN_BLOCKED_UNTIL)
    }
  }

  override suspend fun setRateLimitedUntil(untilMillis: Long?) {
    store.edit { prefs ->
      if (untilMillis == null) prefs.remove(RATE_LIMITED_UNTIL)
      else prefs[RATE_LIMITED_UNTIL] = untilMillis
    }
  }

  override suspend fun setFailureNotified(notified: Boolean) {
    store.edit { it[FAILURE_NOTIFIED] = notified }
  }

  override suspend fun clear() {
    store.edit { it.clear() }
  }

  private fun decode(prefs: Preferences): SyncStatus =
    SyncStatus(
      lastReport = decodeOrNull(SyncReport.serializer(), prefs[LAST_REPORT]),
      lastSuccessAt = prefs[LAST_SUCCESS_AT],
      consecutiveFailures = prefs[CONSECUTIVE_FAILURES] ?: 0,
      authRejections = prefs[AUTH_REJECTIONS] ?: 0,
      autoLoginBlockedUntil = prefs[AUTO_LOGIN_BLOCKED_UNTIL],
      rateLimitedUntil = prefs[RATE_LIMITED_UNTIL],
      failureNotified = prefs[FAILURE_NOTIFIED] ?: false,
    )

  private companion object {
    val LAST_REPORT = stringPreferencesKey("last_report")
    val LAST_SUCCESS_AT = longPreferencesKey("last_success_at")
    val CONSECUTIVE_FAILURES = intPreferencesKey("consecutive_failures")
    val AUTH_REJECTIONS = intPreferencesKey("auth_rejections")
    val AUTO_LOGIN_BLOCKED_UNTIL = longPreferencesKey("auto_login_blocked_until")
    val RATE_LIMITED_UNTIL = longPreferencesKey("rate_limited_until")
    val FAILURE_NOTIFIED = booleanPreferencesKey("failure_notified")
  }
}
