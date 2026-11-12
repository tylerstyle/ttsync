package de.tergelius.ttsync.timetree

import kotlinx.serialization.json.JsonObject

/**
 * Read-only client for TimeTree's unofficial web API (DESIGN.md section 3). Phase 2 implements it
 * as [OkHttpTimeTreeClient]; engine tests use a fake.
 *
 * Contract:
 * - **Read only.** It never sends anything that changes data in TimeTree. The only non-GET request
 *   is the login (`PUT /auth/email/signin`).
 * - **Errors.** Every method throws only [TtError] (and `CancellationException`).
 * - **Pacing.** Every request first waits on [RequestPacer.awaitSlot].
 * - **Session.** The `_session_id` cookie comes from [TtSessionStore.sessionId] and is sent as a
 *   manual `Cookie` header. A new value set by any data response is saved through
 *   [TtSessionStore.onSessionRotated].
 * - **Re-login** only after a 401 on a data call or a 403 on `/calendars`, at most one login and one
 *   retry per run (see [beginRun]), and only with [TtSessionStore.autoLoginCredentials]. If that is
 *   null, throw [TtError.LoginRequired] without contacting the login endpoint.
 * - **Raw dumps.** Every data response body goes to [RawResponseSink.record]; login responses and
 *   headers never do.
 * - **Privacy.** No credentials, cookies or event content in logs or exception messages.
 *
 * Calls are not concurrent: the sync engine serializes runs with a process-wide mutex, and the
 * interactive [signIn] happens only while no run can start (signed out).
 */
interface TimeTreeClient {
  /**
   * Interactive sign-in from the Login screen: `PUT /auth/email/signin {uid, password, uuid}` with
   * [TtSessionStore.installUuid]. Returns the new `_session_id`. Does not persist anything; the
   * caller stores the credentials and session.
   *
   * @throws TtError.AuthRejected on `-702`
   * @throws TtError.RateLimited on `-495` or HTTP 429
   * @throws TtError.Transient when offline or on 5xx
   */
  suspend fun signIn(email: String, password: String): String

  /**
   * Starts a sync run: resets the per-run budget of one automatic login and one retry, and starts
   * a new raw dump folder through [RawResponseSink.beginRun]. Call once per run before any request.
   */
  fun beginRun(runId: String)

  /**
   * `GET /calendars?since=0`: every calendar the account can see, including deactivated ones (see
   * [TtCalendar.isActive]). The engine calls this on every run to notice lost access.
   */
  suspend fun calendars(): List<TtCalendar>

  /**
   * `GET /calendar/{id}/labels`.
   *
   * @throws TtError.AccessLost on 403/404
   */
  suspend fun labels(calendarId: Long): List<TtLabel>

  /**
   * `GET /calendar/{id}/events/sync[?since=N]`, following `since` while `chunk == true`. [since]
   * null fetches everything (FULL).
   *
   * Never returns a partial list: an error on any page throws and discards the earlier pages. The
   * cursor may decrease; a cursor that repeats, or more than [TimeTreeApi.MAX_PAGES] pages, throws
   * [TtError.ApiChanged], as does a structurally broken page (not JSON, no `events` array, no
   * `chunk`/`since`). Events are deduplicated by uuid, the last occurrence wins.
   *
   * A single event that does not decode (a live event without its times, say) does not fail the
   * fetch: it is left out and reported in [EventsResult.skippedUuids] and
   * [EventsResult.skippedMalformed]. Tombstones without times decode (see [TtEvent]).
   *
   * @throws TtError.AccessLost on 403/404 for this calendar
   */
  suspend fun events(calendarId: Long, since: Long?): EventsResult
}

/** The complete result of one [TimeTreeClient.events] call. */
data class EventsResult(
  /** Live events and tombstones, deduplicated by uuid. */
  val events: List<FetchedEvent>,
  /** The final `since`; the cursor to store once this calendar's writes succeeded. */
  val since: Long,
  /** Number of pages fetched, for the report. */
  val pages: Int,
  /**
   * Uuids whose last occurrence in this fetch did not decode, so they are not in [events]. Their
   * state is unknown: the engine must leave their mappings and copies alone, and a FULL fetch must
   * not treat them as missing (deleted).
   */
  val skippedUuids: Set<String> = emptySet(),
  /**
   * Events left out because they did not decode, for the report. If this is larger than
   * [skippedUuids].size, some had no readable uuid, so a FULL fetch is not complete enough to
   * delete copies of missing uuids for this calendar.
   */
  val skippedMalformed: Int = 0,
)

/** A decoded event plus its raw JSON object, which is what `SnapshotStore` keeps. */
data class FetchedEvent(val event: TtEvent, val json: JsonObject) {
  override fun toString(): String = "FetchedEvent($event)"
}

/** Email and password for an automatic re-login. Never logged. */
data class TtCredentials(val email: String, val password: String) {
  override fun toString(): String = "TtCredentials(<redacted>)"
}

/**
 * What the client needs from persistent storage (implemented in `settings` on top of
 * `CredentialStore`, `SettingsRepository` and `StatusRepository`). It owns the auth-block state of
 * the error table: the client reports login outcomes here and this store decides whether the next
 * automatic login is allowed.
 */
interface TtSessionStore {
  /** 32 lowercase hex characters, generated once per install and renewed on sign-out. */
  suspend fun installUuid(): String

  /** The current `_session_id`, or null if there is none. */
  suspend fun sessionId(): String?

  /** A data response set a new `_session_id`; persist it. */
  suspend fun onSessionRotated(sessionId: String)

  /**
   * Credentials for one automatic re-login, or null if none are stored or automatic logins are
   * blocked (6 h after an [TtError.AuthRejected], or stopped after two in a row).
   */
  suspend fun autoLoginCredentials(): TtCredentials?

  /** An automatic re-login succeeded; persist the session and reset the auth-block state. */
  suspend fun onAutoLoginSucceeded(sessionId: String)

  /** An automatic re-login was rejected (`-702`, or 401 right after it); start or extend the block. */
  suspend fun onAutoLoginRejected()
}
