package de.tergelius.ttsync.sync

import de.tergelius.ttsync.TtLog
import de.tergelius.ttsync.calendar.CalendarInfo
import de.tergelius.ttsync.calendar.FakeCalendarGateway
import de.tergelius.ttsync.calendar.TargetCalendar
import de.tergelius.ttsync.mapping.Desired
import de.tergelius.ttsync.mapping.EventFilter
import de.tergelius.ttsync.mapping.EventMapper
import de.tergelius.ttsync.mapping.EventSpec
import de.tergelius.ttsync.mapping.LabelNames
import de.tergelius.ttsync.mapping.MappingInput
import de.tergelius.ttsync.mapping.MappingResult
import de.tergelius.ttsync.mapping.MappingStats
import de.tergelius.ttsync.mapping.Marker
import de.tergelius.ttsync.settings.CredentialStore
import de.tergelius.ttsync.settings.Credentials
import de.tergelius.ttsync.settings.Settings
import de.tergelius.ttsync.settings.SettingsRepository
import de.tergelius.ttsync.settings.SourceCalendar
import de.tergelius.ttsync.settings.StatusRepository
import de.tergelius.ttsync.settings.SyncInterval
import de.tergelius.ttsync.settings.SyncStatus
import de.tergelius.ttsync.store.JdbcSqlConnection
import de.tergelius.ttsync.store.MappingStore
import de.tergelius.ttsync.store.SyncDatabase
import de.tergelius.ttsync.timetree.EventFeed
import de.tergelius.ttsync.timetree.EventsResult
import de.tergelius.ttsync.timetree.TimeTreeApi
import de.tergelius.ttsync.timetree.TimeTreeClient
import de.tergelius.ttsync.timetree.TtCalendar
import de.tergelius.ttsync.timetree.TtError
import de.tergelius.ttsync.timetree.TtEvent
import de.tergelius.ttsync.timetree.TtEventsPage
import de.tergelius.ttsync.timetree.TtJson
import de.tergelius.ttsync.timetree.TtLabel
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * Everything an engine test needs: the real store SQL on in-memory SQLite, a [FakeCalendarGateway]
 * with the user's own rows in the target calendar, and fakes of TimeTree, the mapper, settings,
 * status and credentials. Time is controlled by [now].
 */
class EngineHarness {
  var now: Long = Instant.parse("2026-11-12T08:00:00Z").toEpochMilli()
  val zone: ZoneId = ZoneId.of("Europe/Berlin")

  val gateway = FakeCalendarGateway()
  val store = JdbcSqlConnection.syncDatabase()
  val db = FlakyDatabase(store)
  val client = FakeClient()
  val mapper = FakeMapper()
  val settings = MemSettings()
  val status = MemStatus()
  val credentials = MemCredentials()
  val log = RecordingLog()
  private var runs = 0

  val engine =
    DefaultSyncEngine(client, mapper, db, gateway, settings, status, credentials, log, { now }, { zone })

  val target: TargetCalendar = TargetCalendar.of(google(TARGET, "personal"))

  /** Rows the app did not create and that carry no valid marker: every path must leave them as they are. */
  val foreign = mutableSetOf<Long>()

  /**
   * Rows in the target with a valid marker of a uuid the app does not manage (unknown, or out of
   * the window). Sync never touches them; only the explicit removals delete them, by design.
   */
  val unmanagedMarked = mutableSetOf<Long>()

  /** Rows in other calendars, including one with the exact marker of a live uuid. */
  val elsewhere = mutableSetOf<Long>()

  private var baseline: Map<Long, FakeCalendarGateway.Row> = emptyMap()

  init {
    gateway.addCalendar(google(TARGET, "personal"))
    gateway.addCalendar(google(OTHER, "work"))
    gateway.addCalendar(google(NEW_TARGET, "ttsync"))
    gateway.addCalendar(
      CalendarInfo(LOCAL, "phone", "phone", "LOCAL", 700, syncEvents = true, visible = true, color = null)
    )
    settings.value =
      Settings(sources = listOf(SourceCalendar(CAL_A, "Family")), target = target, targetDisplayName = "personal")
    client.calendars += TtCalendar(CAL_A, "Family")
    client.calendars += TtCalendar(CAL_B, "Club")

    // The user's own events in the target calendar (DESIGN.md E1: seven of them).
    foreign += gateway.addRow(TARGET, "Dentist", null)
    foreign += gateway.addRow(TARGET, "Papers", "Bring [ttsync:$A1] papers")
    foreign += gateway.addRow(TARGET, "Trailing", "Notes\n[ttsync:$A1] trailing")
    foreign += gateway.addRow(TARGET, "Upper", "[TTSYNC:$A2]")
    foreign += gateway.addRow(TARGET, "Deleted copy", "x\n[ttsync:$A3]", deleted = true)
    unmanagedMarked += gateway.addRow(TARGET, "By hand", "Copied by hand\n[ttsync:unknown-uuid-9]")
    unmanagedMarked += gateway.addRow(TARGET, "Old copy", "Old\n[ttsync:$OLD]")
    // Other calendars: never queried as ours, never written (R5).
    elsewhere += gateway.addRow(OTHER, "Same marker", "[ttsync:$A1]")
    elsewhere += gateway.addRow(LOCAL, "Phone only", null)
    elsewhere += gateway.addRow(NEW_TARGET, "In the new target", "Mine")
    baseline = gateway.rows.toMap()

    // TimeTree calendar A.
    client.put(CAL_A, timed(A1, day = 0, title = "Breakfast"))
    client.put(CAL_A, timed(A2, day = 1, title = "Lunch"))
    client.put(CAL_A, timed(A3, day = 2, title = "Dinner"))
    client.put(CAL_A, timed(OLD, day = -60, title = "Long ago"))
    client.put(CAL_A, timed(BIRTHDAY, day = 3, title = "Birthday").copy(type = TtEvent.TYPE_BIRTHDAY))
    client.put(CAL_A, timed(MEMO, day = 3, title = "Memo").copy(category = TtEvent.CATEGORY_MEMO))
    client.put(CAL_A, timed(SERIES, day = -1, title = "Weekly").copy(recurrences = listOf("RRULE:FREQ=WEEKLY")))
    // TimeTree calendar B.
    client.put(CAL_B, timed(B1, day = 4, title = "Training"))
    client.put(CAL_B, timed(B2, day = 5, title = "Match"))
  }

  /** Live, in-window, non-skipped uuids of calendar A at setup. */
  val liveA = setOf(A1, A2, A3, SERIES)
  val liveB = setOf(B1, B2)

  fun selectB() {
    settings.value = settings.value.copy(sources = settings.value.sources + SourceCalendar(CAL_B, "Club"))
  }

  /** Sets (or with null clears) the filter of the selected calendar [calendarId]. */
  fun setFilter(calendarId: Long, filter: EventFilter?) {
    val sources = settings.value.sources
    check(sources.any { it.id == calendarId }) { "calendar $calendarId not selected" }
    settings.value =
      settings.value.copy(sources = sources.map { if (it.id == calendarId) it.copy(filter = filter) else it })
  }

  suspend fun sync(full: Boolean = false, trigger: SyncTrigger = SyncTrigger.MANUAL): SyncReport =
    engine.run(SyncRequest(trigger, full, runId = "run-${++runs}"))

  /** Live rows in [calendarId] with a valid marker, by uuid; fails on duplicates. */
  fun copies(calendarId: Long = TARGET): Map<String, FakeCalendarGateway.Row> {
    val rows = gateway.markedRows(calendarId).filter { it.id !in unmanagedMarked }
    val byUuid = rows.groupBy { it.markerUuid!! }
    val duplicates = byUuid.filterValues { it.size > 1 }.keys
    assertTrue("duplicate copies of $duplicates", duplicates.isEmpty())
    return byUuid.mapValues { it.value.single() }
  }

  /** The core safety property: rows the app did not create are exactly as they were. */
  fun assertForeignUntouched(includeUnmanagedMarked: Boolean = true) {
    val ids = foreign + elsewhere + if (includeUnmanagedMarked) unmanagedMarked else emptySet()
    for (id in ids) {
      val expected = baseline[id] ?: userRows.getValue(id)
      assertEquals("row $id changed", expected, gateway.rows[id])
    }
  }

  /** Rows the user took over after the baseline (e.g. removed the marker from a copy). */
  private val userRows = mutableMapOf<Long, FakeCalendarGateway.Row>()

  /** Includes a row added after setup in the safety check, in its current state. */
  fun trackUserRow(id: Long) {
    userRows[id] = gateway.rows.getValue(id)
  }

  /** The user edits a copy and removes the marker: from now on the row is theirs. */
  fun userTakesOver(id: Long, description: String) {
    gateway.userEdit(id, description)
    foreign += id
    userRows[id] = gateway.rows.getValue(id)
  }

  /** Adds a foreign row after setup (it joins the safety check). */
  fun addForeign(calendarId: Long, title: String, description: String?, id: Long? = null): Long {
    val rowId =
      if (id == null) gateway.addRow(calendarId, title, description)
      else gateway.addRow(calendarId, title, description, id = id)
    foreign += rowId
    userRows[rowId] = gateway.rows.getValue(rowId)
    return rowId
  }

  fun timed(uuid: String, day: Int, title: String, hours: Int = 1): TtEvent {
    val start = DAY_START + day * DAY
    return TtEvent(
      uuid = uuid,
      title = title,
      allDay = false,
      startAt = start,
      endAt = start + hours * HOUR,
      startTimezone = "Europe/Berlin",
      updatedAt = 0,
    )
  }

  companion object {
    const val TARGET = 15L
    const val OTHER = 16L
    const val NEW_TARGET = 17L
    const val LOCAL = 99L
    const val CAL_A = 1L
    const val CAL_B = 2L
    const val ACCOUNT = "user@example.org"
    const val HOUR = 3_600_000L
    const val DAY = 86_400_000L
    /** 2026-11-12 12:00 Europe/Berlin. */
    const val DAY_START = 1_794_481_200_000L

    const val A1 = "aaaa0001aaaa0001aaaa0001aaaa0001"
    const val A2 = "aaaa0002aaaa0002aaaa0002aaaa0002"
    const val A3 = "aaaa0003aaaa0003aaaa0003aaaa0003"
    const val OLD = "aaaa00ffaaaa00ffaaaa00ffaaaa00ff"
    const val BIRTHDAY = "aaaa0b0daaaa0b0daaaa0b0daaaa0b0d"
    const val MEMO = "aaaa0e0eaaaa0e0eaaaa0e0eaaaa0e0e"
    const val SERIES = "aaaa5e5eaaaa5e5eaaaa5e5eaaaa5e5e"
    const val B1 = "bbbb0001bbbb0001bbbb0001bbbb0001"
    const val B2 = "bbbb0002bbbb0002bbbb0002bbbb0002"

    fun google(id: Long, name: String, account: String = ACCOUNT) =
      CalendarInfo(id, name, account, CalendarInfo.ACCOUNT_TYPE_GOOGLE, 700, syncEvents = true, visible = true, color = null)
  }

  // ---------------------------------------------------------------------------------------------

  /**
   * TimeTree's sync endpoint in memory, paging like the real API (on-device test, 2026-11-12): the
   * feed is ordered by `updated_at`, newest change first, [pageSize] events a page. A page's
   * `since` is its oldest `updated_at` − 1 and asks for the next *older* page; `chunk == false`
   * marks the oldest one. Deleted events stay in the feed as tombstones. The paging rules are the
   * real client's ([EventFeed]); only HTTP is replaced.
   *
   * Every change gets a new `updated_at` from TimeTree's own [clock], [step] apart.
   */
  class FakeClient : TimeTreeClient {
    val calendars = mutableListOf<TtCalendar>()
    val labels = mutableMapOf<Long, List<TtLabel>>()
    private val events = mutableMapOf<Long, LinkedHashMap<String, TtEvent>>()

    /** TimeTree's clock: the `updated_at` of the latest change. */
    var clock = 1_794_400_000_000L

    /** Between two changes; more than [TimeTreeApi.FEED_OVERLAP], so incremental reads stop early. */
    var step = 10 * 60_000L

    /**
     * 2: calendar A's 7 events make 4 pages, more than [DefaultSyncEngine.SMALL_FEED_PAGES], so
     * runs between FULLs are INCREMENTAL unless a test makes the feed small.
     */
    var pageSize = 2

    /** Every request, e.g. `calendars`, `labels:1`, `events:1:null` (one per read, not per page). */
    val requests = mutableListOf<String>()

    /** Every page request of the events reads, e.g. `1:top`, `1:since=1794400599999`. */
    val pageRequests = mutableListOf<String>()

    /** Return a TtError to make that request fail. */
    var fail: ((String) -> TtError?)? = null

    /** Called before each page of an events read is served, with its number (from 1). */
    var beforePage: ((Int) -> Unit)? = null

    /** Simulates events that do not decode (live events without `start_at`). */
    var skipped: Set<String> = emptySet()

    /** That many undecodable entries without a uuid at the top of the feed. */
    var skippedWithoutUuid = 0

    fun put(calendarId: Long, event: TtEvent) {
      clock += step
      events.getOrPut(calendarId) { LinkedHashMap() }[event.uuid] = event.copy(updatedAt = clock)
    }

    fun edit(calendarId: Long, uuid: String, change: (TtEvent) -> TtEvent) =
      put(calendarId, change(event(calendarId, uuid)))

    /** Deleted in TimeTree: a tombstone. [unknownTimes] sends start/end 0 like real tombstones may. */
    fun deactivate(calendarId: Long, uuid: String, unknownTimes: Boolean = false) =
      edit(calendarId, uuid) {
        val dead = it.copy(deactivatedAt = clock + step)
        if (unknownTimes) dead.copy(startAt = 0, endAt = 0) else dead
      }

    /** Gone without a trace: missing from every later fetch. */
    fun purge(calendarId: Long, uuid: String) {
      events.getValue(calendarId).remove(uuid)
    }

    fun event(calendarId: Long, uuid: String): TtEvent = events.getValue(calendarId).getValue(uuid)

    fun all(calendarId: Long): List<TtEvent> = events[calendarId].orEmpty().values.toList()

    /** The newest change in [calendarId]'s feed: what a complete fetch stores as high-water mark. */
    fun newestChange(calendarId: Long): Long = all(calendarId).maxOf { it.changedAt }

    private fun record(request: String) {
      requests += request
      fail?.invoke(request)?.let { throw it }
    }

    override suspend fun signIn(email: String, password: String): String = error("not in engine tests")

    override fun beginRun(runId: String) {
      requests += "begin"
    }

    override suspend fun calendars(): List<TtCalendar> {
      record("calendars")
      return calendars.toList()
    }

    override suspend fun labels(calendarId: Long): List<TtLabel> {
      record("labels:$calendarId")
      return labels[calendarId].orEmpty()
    }

    override suspend fun events(calendarId: Long, stopAt: Long?): EventsResult {
      record("events:$calendarId:$stopAt")
      var page = 0
      val feed =
        EventFeed(calendarId, TimeTreeApi.MAX_PAGES, TtLog.None) { since ->
          beforePage?.invoke(++page)
          pageRequests += "$calendarId:" + (since?.let { "since=$it" } ?: "top")
          serve(calendarId, since)
        }
      return feed.read(stopAt)
    }

    /** One page as the server sends it. */
    private fun serve(calendarId: Long, since: Long?): TtEventsPage {
      val feed = all(calendarId).sortedByDescending { it.updatedAt }
      val remaining = if (since == null) feed else feed.filter { it.updatedAt <= since }
      val page = remaining.take(pageSize)
      val json = page.map(::toJson).toMutableList()
      if (since == null) {
        repeat(skippedWithoutUuid) { json.add(0, JsonObject(mapOf("title" to JsonPrimitive("?")))) }
      }
      val oldest = page.lastOrNull()?.updatedAt ?: ((since ?: 0L) + 1)
      return TtEventsPage(json, chunk = remaining.size > pageSize, since = oldest - 1)
    }

    private fun toJson(event: TtEvent): JsonObject {
      val json = TtJson.encodeToJsonElement(TtEvent.serializer(), event).jsonObject
      return if (event.uuid in skipped) JsonObject(json - "start_at") else json
    }
  }

  /**
   * A small stand-in for phase 3's mapper with the same contract: single timed events, a series
   * from an RRULE (open-ended, `endAt = Long.MAX_VALUE`) whose EXDATE lists its overrides, Absent
   * for birthdays, memos and deactivated events in the window, nothing for events before the window,
   * and Absent (listed in `filtered`) for live in-window events the filter excludes. The filter and
   * the label line are the real ones ([EventFilter.matches], [LabelNames.forCopy]).
   */
  class FakeMapper : EventMapper {
    var calls = 0

    override fun map(input: MappingInput): MappingResult {
      calls++
      val windowStart = EventMapper.windowStartMillis(input.today, input.zone)
      val byUuid = input.events.associateBy { it.uuid }
      val desired = LinkedHashMap<String, Desired>()
      val parentOf = LinkedHashMap<String, String>()
      var skipped = 0
      var outOfWindow = 0
      val filter = input.filter?.takeUnless { it.isEmpty }
      val filtered = LinkedHashSet<String>()
      for (e in input.events.sortedBy { it.uuid }) {
        val parent = e.recurringUuid ?: e.parentId
        if (parent != null && parent in byUuid) parentOf[e.uuid] = parent
        val endAt = if (e.recurrences.isNotEmpty()) Long.MAX_VALUE else maxOf(e.endAt, e.startAt)
        if (endAt < windowStart) {
          outOfWindow++
          continue
        }
        when {
          e.isBirthday || e.isMemo -> {
            skipped++
            desired[e.uuid] = Desired.Absent
          }
          e.isDeactivated -> desired[e.uuid] = Desired.Absent
          filter != null && !filter.matches(e) -> {
            filtered += e.uuid
            desired[e.uuid] = Desired.Absent
          }
          else -> desired[e.uuid] = Desired.Write(spec(e, input, byUuid.values), endAt)
        }
      }
      return MappingResult(
        desired,
        parentOf,
        MappingStats(skipped = skipped, outOfWindow = outOfWindow, filtered = filtered.size),
        filtered,
      )
    }

    private fun spec(e: TtEvent, input: MappingInput, all: Collection<TtEvent>): EventSpec {
      val label = e.labelId?.let { input.labels[it] }?.let(LabelNames::forCopy)?.let { "Label: $it" }
      val description = Marker.append(listOfNotNull(e.note, label).joinToString("\n"), e.uuid)
      val rrule = e.recurrences.firstOrNull()?.removePrefix("RRULE:")
      val exdate =
        if (rrule == null) null
        else
          all
            .filter { it.recurringUuid == e.uuid }
            .map { UTC_STAMP.format(Instant.ofEpochMilli(it.startAt)) }
            .sorted()
            .joinToString(",")
            .ifEmpty { null }
      return EventSpec(
        uuid = e.uuid,
        title = e.title?.takeIf { it.isNotBlank() } ?: "(TimeTree)",
        location = e.location,
        description = description,
        dtStart = e.startAt,
        dtEnd = if (rrule == null) maxOf(e.endAt, e.startAt) else null,
        duration = if (rrule == null) null else "P${(e.endAt - e.startAt) / 1000}S",
        eventTimezone = e.startTimezone ?: "UTC",
        eventEndTimezone = null,
        allDay = false,
        rrule = rrule,
        rdate = null,
        exdate = exdate,
      )
    }

    private companion object {
      val UTC_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
    }
  }

  class MemSettings : SettingsRepository {
    var value = Settings()

    override val settings: Flow<Settings>
      get() = flowOf(value)

    override suspend fun current(): Settings = value

    override suspend fun setSources(sources: List<SourceCalendar>) {
      value = value.copy(sources = sources)
    }

    override suspend fun setTarget(target: TargetCalendar, displayName: String) {
      value = value.copy(target = target, targetDisplayName = displayName)
    }

    override suspend fun setInterval(interval: SyncInterval) {
      value = value.copy(interval = interval)
    }

    override suspend fun setKeepRunningDone(done: Boolean) {
      value = value.copy(keepRunningDone = done)
    }

    override suspend fun installUuid(): String = "0123456789abcdef0123456789abcdef"

    override suspend fun clear() {
      value = Settings()
    }
  }

  class MemStatus : StatusRepository {
    var value = SyncStatus()
    val reports = mutableListOf<SyncReport>()

    override val status: Flow<SyncStatus>
      get() = flowOf(value)

    override suspend fun current(): SyncStatus = value

    override suspend fun recordRun(report: SyncReport) {
      reports += report
      value =
        if (report.succeeded) value.copy(lastReport = report, lastSuccessAt = report.finishedAt, consecutiveFailures = 0)
        else value.copy(lastReport = report, consecutiveFailures = value.consecutiveFailures + 1)
    }

    override suspend fun recordAuthRejected(nowMillis: Long) = Unit

    override suspend fun recordLoginSucceeded() = Unit

    override suspend fun setRateLimitedUntil(untilMillis: Long?) {
      value = value.copy(rateLimitedUntil = untilMillis)
    }

    override suspend fun setFailureNotified(notified: Boolean) = Unit

    override suspend fun clear() {
      value = SyncStatus()
    }
  }

  class MemCredentials : CredentialStore {
    var stored: Credentials? = Credentials("user@example.org", "secret", "session")

    override suspend fun load(): Credentials? = stored

    override suspend fun save(credentials: Credentials) {
      stored = credentials
    }

    override suspend fun updateSessionId(sessionId: String) = Unit

    override suspend fun clear() {
      stored = null
    }
  }

  class RecordingLog : TtLog {
    val lines = mutableListOf<String>()

    override fun d(message: String) {
      lines += "D $message"
    }

    override fun i(message: String) {
      lines += "I $message"
    }

    override fun w(message: String, error: Throwable?) {
      lines += "W $message"
    }

    override fun e(message: String, error: Throwable?) {
      lines += "E $message"
    }
  }

  /** The real stores, with a switch that makes mapping writes fail (a crash between calendar and SQLite). */
  class FlakyDatabase(private val inner: SyncDatabase) : SyncDatabase by inner {
    var failMappingWrites = false

    override val mappings: MappingStore =
      object : MappingStore by inner.mappings {
        override fun upsert(mappings: Collection<de.tergelius.ttsync.store.Mapping>) {
          if (failMappingWrites && mappings.isNotEmpty()) throw IllegalStateException("disk full")
          inner.mappings.upsert(mappings)
        }
      }
  }
}
