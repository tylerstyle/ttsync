package de.tergelius.ttsync.ui

import android.app.Notification
import de.tergelius.ttsync.calendar.BatchResult
import de.tergelius.ttsync.calendar.CalendarGateway
import de.tergelius.ttsync.calendar.CalendarInfo
import de.tergelius.ttsync.calendar.CalendarOp
import de.tergelius.ttsync.calendar.GoogleSyncState
import de.tergelius.ttsync.calendar.MarkedRow
import de.tergelius.ttsync.calendar.RowCheck
import de.tergelius.ttsync.calendar.TargetCalendar
import de.tergelius.ttsync.settings.CredentialStore
import de.tergelius.ttsync.settings.Credentials
import de.tergelius.ttsync.settings.Settings
import de.tergelius.ttsync.settings.SettingsRepository
import de.tergelius.ttsync.settings.SourceCalendar
import de.tergelius.ttsync.settings.StatusRepository
import de.tergelius.ttsync.settings.SyncInterval
import de.tergelius.ttsync.settings.SyncStatus
import de.tergelius.ttsync.sync.RemovalReport
import de.tergelius.ttsync.sync.ScheduleState
import de.tergelius.ttsync.sync.SyncEngine
import de.tergelius.ttsync.sync.SyncFailure
import de.tergelius.ttsync.sync.SyncNotifier
import de.tergelius.ttsync.sync.SyncReport
import de.tergelius.ttsync.sync.SyncRequest
import de.tergelius.ttsync.sync.SyncScheduler
import de.tergelius.ttsync.sync.SyncTrigger
import de.tergelius.ttsync.timetree.EventsResult
import de.tergelius.ttsync.timetree.TimeTreeClient
import de.tergelius.ttsync.timetree.TtCalendar
import de.tergelius.ttsync.timetree.TtLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/*
 * Fakes for the UI tests. Every fake appends to the shared [Calls] log, so tests can check the
 * order of steps across components (e.g. "cancel work, then delete copies").
 */

class Calls {
  val log = mutableListOf<String>()

  fun add(call: String) {
    log += call
  }

  override fun toString(): String = log.toString()
}

class FakeCredentials(private val calls: Calls, var stored: Credentials? = null) : CredentialStore {
  override suspend fun load(): Credentials? = stored

  override suspend fun save(credentials: Credentials) {
    calls.add("credentials.save")
    stored = credentials
  }

  override suspend fun updateSessionId(sessionId: String) {
    stored = stored?.copy(sessionId = sessionId)
  }

  override suspend fun clear() {
    calls.add("credentials.clear")
    stored = null
  }
}

class FakeSettings(private val calls: Calls, initial: Settings = Settings()) : SettingsRepository {
  val flow = MutableStateFlow(initial)
  override val settings: Flow<Settings> = flow

  override suspend fun current(): Settings = flow.value

  override suspend fun setSources(sources: List<SourceCalendar>) {
    calls.add("settings.setSources(${sources.map { it.id }})")
    flow.update { it.copy(sources = sources) }
  }

  override suspend fun setTarget(target: TargetCalendar, displayName: String) {
    calls.add("settings.setTarget(${target.calendarId})")
    flow.update { it.copy(target = target, targetDisplayName = displayName) }
  }

  override suspend fun setInterval(interval: SyncInterval) {
    calls.add("settings.setInterval($interval)")
    flow.update { it.copy(interval = interval) }
  }

  override suspend fun setKeepRunningDone(done: Boolean) {
    calls.add("settings.setKeepRunningDone($done)")
    flow.update { it.copy(keepRunningDone = done) }
  }

  override suspend fun installUuid(): String = "0".repeat(32)

  override suspend fun clear() {
    calls.add("settings.clear")
    flow.value = Settings()
  }
}

class FakeStatus(private val calls: Calls, initial: SyncStatus = SyncStatus()) : StatusRepository {
  val flow = MutableStateFlow(initial)
  override val status: Flow<SyncStatus> = flow

  override suspend fun current(): SyncStatus = flow.value

  override suspend fun recordRun(report: SyncReport) {
    flow.update { it.copy(lastReport = report) }
  }

  override suspend fun recordAuthRejected(nowMillis: Long) {
    flow.update { it.copy(authRejections = it.authRejections + 1) }
  }

  override suspend fun recordLoginSucceeded() {
    calls.add("status.recordLoginSucceeded")
    flow.update { it.copy(authRejections = 0, autoLoginBlockedUntil = null) }
  }

  override suspend fun setRateLimitedUntil(untilMillis: Long?) {
    calls.add("status.setRateLimitedUntil($untilMillis)")
    flow.update { it.copy(rateLimitedUntil = untilMillis) }
  }

  override suspend fun setFailureNotified(notified: Boolean) {
    flow.update { it.copy(failureNotified = notified) }
  }

  override suspend fun clear() {
    calls.add("status.clear")
    flow.value = SyncStatus()
  }
}

class FakeScheduler(private val calls: Calls) : SyncScheduler {
  val flow = MutableStateFlow(ScheduleState())
  override val state: Flow<ScheduleState> = flow

  override fun ensurePeriodic() {
    calls.add("scheduler.ensurePeriodic")
  }

  override fun updatePeriodic(interval: SyncInterval) {
    calls.add("scheduler.updatePeriodic($interval)")
  }

  override fun cancelPeriodic() {
    calls.add("scheduler.cancelPeriodic")
  }

  override fun runNow(full: Boolean, trigger: SyncTrigger) {
    calls.add("scheduler.runNow(full=$full, $trigger)")
  }

  override fun cancelAll() {
    calls.add("scheduler.cancelAll")
  }
}

class FakeEngine(private val calls: Calls) : SyncEngine {
  var removal = RemovalReport(deleted = 0, rejected = 0)
  var removeAllError: Exception? = null

  override suspend fun run(request: SyncRequest): SyncReport = error("not used by the UI")

  override suspend fun removeAllCopies(target: TargetCalendar, stopSyncing: Boolean): RemovalReport {
    calls.add("engine.removeAllCopies(${target.calendarId})")
    removeAllError?.let { throw it }
    return removal
  }

  override suspend fun deselect(ttCalIds: Set<Long>): RemovalReport {
    calls.add("engine.deselect(${ttCalIds.sorted()})")
    return removal
  }

  override suspend fun dropMappings() {
    calls.add("engine.dropMappings")
  }
}

class FakeGateway(
  var calendars: List<CalendarInfo> = emptyList(),
  var foreign: Map<Long, Int> = emptyMap(),
  var googleSync: GoogleSyncState = GoogleSyncState(masterSync = true, calendarSync = true),
) : CalendarGateway {
  override fun hasPermission(): Boolean = true

  override suspend fun calendars(): List<CalendarInfo> = calendars

  override suspend fun calendar(calendarId: Long): CalendarInfo? = calendars.firstOrNull {
    it.id == calendarId
  }

  override fun googleSyncState(target: TargetCalendar): GoogleSyncState = googleSync

  override suspend fun countForeignEvents(calendarId: Long): Int = foreign[calendarId] ?: 0

  override suspend fun findMarkedRows(target: TargetCalendar): List<MarkedRow> = error("not used")

  override suspend fun checkRows(
    target: TargetCalendar,
    expected: Map<Long, String>,
  ): Map<Long, RowCheck> = error("not used")

  override suspend fun apply(target: TargetCalendar, ops: List<CalendarOp>): BatchResult =
    error("the UI never writes events")

  override fun requestUpload(target: TargetCalendar) = Unit
}

class FakeClient(private val calls: Calls) : TimeTreeClient {
  var signInResult: () -> String = { "session" }
  var calendarsResult: () -> List<TtCalendar> = { emptyList() }

  override suspend fun signIn(email: String, password: String): String {
    calls.add("client.signIn($email)")
    return signInResult()
  }

  override fun beginRun(runId: String) = Unit

  override suspend fun calendars(): List<TtCalendar> = calendarsResult()

  override suspend fun labels(calendarId: Long): List<TtLabel> = error("not used")

  override suspend fun events(calendarId: Long, since: Long?): EventsResult = error("not used")
}

class FakeNotifier(private val calls: Calls) : SyncNotifier {
  override fun showFailure(failure: SyncFailure) = Unit

  override fun clearFailure() {
    calls.add("notifier.clearFailure")
  }

  override fun showAccessLost(ttCalId: Long, calendarName: String?) = Unit

  override fun clearAccessLost(ttCalId: Long) {
    calls.add("notifier.clearAccessLost($ttCalId)")
  }

  override fun runningNotification(): Notification = error("not used")
}

/** Everything a ViewModel test needs, wired like the app's factory. */
class UiTestGraph(
  settings: Settings = Settings(),
  status: SyncStatus = SyncStatus(),
  credentials: Credentials? = null,
  var device: DeviceState =
    DeviceState(calendarPermission = true, batteryUnrestricted = true, notificationsEnabled = true),
) {
  val calls = Calls()
  val credentials = FakeCredentials(calls, credentials)
  val settings = FakeSettings(calls, settings)
  val status = FakeStatus(calls, status)
  val scheduler = FakeScheduler(calls)
  val engine = FakeEngine(calls)
  val gateway = FakeGateway()
  val client = FakeClient(calls)
  val notifier = FakeNotifier(calls)
  val deviceChecks = DeviceChecks { device }

  val actions =
    UserActions(
      client = client,
      credentials = this.credentials,
      settings = this.settings,
      status = this.status,
      engine = engine,
      scheduler = scheduler,
      gateway = gateway,
      notifier = notifier,
      wipeLocalFiles = { calls.add("wipeLocalFiles") },
      io = Dispatchers.Unconfined,
      nowMillis = { NOW },
    )

  companion object {
    const val NOW = 1_794_481_200_000L
    const val ACCOUNT = "me@example.com"

    fun calendar(
      id: Long,
      name: String = "Cal $id",
      access: Int = 700,
      type: String = "com.google",
    ) =
      CalendarInfo(
        id = id,
        displayName = name,
        accountName = ACCOUNT,
        accountType = type,
        accessLevel = access,
        syncEvents = true,
        visible = true,
        color = null,
      )

    fun target(id: Long) = TargetCalendar(id, ACCOUNT, "com.google")

    val SIGNED_IN = Credentials("me@example.org", "secret", "session")
  }
}
