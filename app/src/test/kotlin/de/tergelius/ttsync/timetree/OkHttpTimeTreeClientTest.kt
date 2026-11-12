package de.tergelius.ttsync.timetree

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** DESIGN.md section 10, client tests C1–C8, against `mockwebserver3`. */
class OkHttpTimeTreeClientTest {
  private val server = MockWebServer()
  private val store = FakeSessionStore(session = "sess-old")
  private val pacer = RecordingPacer()
  private val sink = RecordingSink()
  private val log = RecordingLog()
  private val thrown = mutableListOf<Throwable>()
  private val now = 1794481200000L
  private lateinit var client: OkHttpTimeTreeClient

  @Before
  fun setUp() {
    server.start()
    client =
      OkHttpTimeTreeClient(
        http = TimeTreeApi.newOkHttpClient(),
        sessions = store,
        pacer = pacer,
        rawSink = sink,
        baseUrl = server.url("/api/v1/"),
        log = log,
        nowMillis = { now },
      )
    client.beginRun("run-1")
  }

  @After
  fun tearDown() {
    server.close()
    // Every request waited for the pacer.
    assertEquals(server.requestCount, pacer.slots)
    // Nothing secret in logs, exception messages (with causes) or dump names.
    val texts =
      log.lines +
        thrown.flatMap { generateSequence(it) { e -> e.cause }.map { e -> e.toString() }.toList() } +
        sink.names
    for (text in texts) for (secret in Fake.SECRETS) {
      assertFalse("'$text' leaks a secret", text.contains(secret))
    }
    // The dump never sees the login.
    assertTrue(sink.records.none { (_, body) -> body.contains(Fake.PASSWORD) })
  }

  private fun take(): RecordedRequest = server.takeRequest()

  private fun RecordedRequest.cookie(): String? = headers["Cookie"]

  // C1 ----------------------------------------------------------------------------------------

  @Test
  fun c1_signInSendsCredentialsAndStableUuid_andReturnsTheCookie() = runTest {
    server.enqueue(response("""{"user":{"id":1}}""", 200, sessionCookie("sess-new1")))
    server.enqueue(response("""{"user":{"id":1}}""", 200, sessionCookie("sess-new2")))

    assertEquals("sess-new1", client.signIn(Fake.EMAIL, Fake.PASSWORD))
    assertEquals("sess-new2", client.signIn(Fake.EMAIL, Fake.PASSWORD))

    val uuids =
      List(2) {
        val request = take()
        assertEquals("PUT", request.method)
        assertEquals("/api/v1/auth/email/signin", request.url.encodedPath)
        assertEquals("web/2.1.0/en", request.headers["X-Timetreea"])
        assertEquals("application/json", request.headers["Content-Type"])
        assertNull("login sends no session", request.cookie())
        val body = TtJson.parseToJsonElement(request.body!!.utf8()).jsonObject
        assertEquals(setOf("uid", "password", "uuid"), body.keys)
        assertEquals(Fake.EMAIL, body["uid"]!!.jsonPrimitive.content)
        assertEquals(Fake.PASSWORD, body["password"]!!.jsonPrimitive.content)
        body["uuid"]!!.jsonPrimitive.content
      }
    assertEquals(listOf(Fake.UUID, Fake.UUID), uuids) // the install uuid, never a fresh one
    assertTrue(uuids.all { Regex("[0-9a-f]{32}").matches(it) })
    // Interactive sign-in persists nothing and is never dumped.
    assertEquals(emptyList<String>(), store.succeeded + store.rotated)
    assertEquals(0, store.rejected)
    assertEquals(emptyList<String>(), sink.names)
  }

  @Test
  fun c1_dataRequestsCarryTheSessionCookieAndClientHeader() = runTest {
    server.enqueue(response(fixture("calendars.json")))

    val calendars = client.calendars()

    assertEquals(listOf(7000001L), calendars.map { it.id })
    assertTrue(calendars.single().isActive)
    val request = take()
    assertEquals("GET", request.method)
    assertEquals("/api/v1/calendars?since=0", request.target)
    assertEquals("_session_id=sess-old", request.cookie())
    assertEquals("web/2.1.0/en", request.headers["X-Timetreea"])
    assertEquals("application/json", request.headers["Content-Type"])
    assertEquals(listOf("run-1"), sink.runs)
    assertEquals(listOf("calendars"), sink.names)
  }

  @Test
  fun signInWithoutCookieIsApiChanged() = runTest {
    server.enqueue(response("""{"user":{}}"""))
    expectError<TtError.ApiChanged>(thrown) { client.signIn(Fake.EMAIL, Fake.PASSWORD) }
  }

  @Test
  fun labelsDecodeFromCalendarLabels() = runTest {
    server.enqueue(response(fixture("labels.json")))

    val labels = client.labels(7000001)

    assertEquals((1L..10L).toList(), labels.map { it.id })
    assertEquals("/api/v1/calendar/7000001/labels", take().target)
    assertEquals(listOf("calendar_7000001_labels"), sink.names)
  }

  // C2–C5 paging --------------------------------------------------------------------------------

  @Test
  fun c2_onePage() = runTest {
    server.enqueue(response(fixture("events-page.json")))

    val result = client.events(7000001, since = null)

    assertEquals("/api/v1/calendar/7000001/events/sync", take().target) // FULL: no since
    assertEquals(1, result.pages)
    assertEquals(1794465807011L, result.since)
    assertEquals(8, result.events.size)
    val override = result.events.single { it.event.isOverride }
    assertTrue(result.events.any { it.event.uuid == override.event.recurringUuid })
    // The raw object is kept verbatim for the snapshot store.
    assertTrue(result.events.all { it.json["uuid"]!!.jsonPrimitive.content == it.event.uuid })
    assertEquals(listOf("calendar_7000001_events_full"), sink.names)
  }

  @Test
  fun c2_incrementalSendsTheCursor() = runTest {
    server.enqueue(response(page(false, 1234, eventJson("a"))))

    val result = client.events(7, since = 1000)

    assertEquals("/api/v1/calendar/7/events/sync?since=1000", take().target)
    assertEquals(1234L, result.since)
    assertEquals(listOf("a"), result.events.map { it.event.uuid })
  }

  @Test
  fun c3_chunkedFollowsSinceAndDeduplicates() = runTest {
    server.enqueue(response(page(true, 200, eventJson("a"), eventJson("b", updatedAt = 1))))
    server.enqueue(response(page(true, 300, eventJson("c"), eventJson("b", updatedAt = 2))))
    server.enqueue(response(page(false, 400, eventJson("d"))))

    val result = client.events(7, since = 100)

    assertEquals(
      listOf("since=100", "since=200", "since=300"),
      List(3) { take().url.encodedQuery },
    )
    assertEquals(3, result.pages)
    assertEquals(400L, result.since)
    assertEquals(listOf("a", "c", "b", "d"), result.events.map { it.event.uuid })
    assertEquals(2L, result.events.single { it.event.uuid == "b" }.event.updatedAt) // last wins
    assertEquals(
      listOf(
        "calendar_7_events_since_100",
        "calendar_7_events_since_200",
        "calendar_7_events_since_300",
      ),
      sink.names,
    )
  }

  @Test
  fun c4_decreasingCursorIsFollowed() = runTest {
    server.enqueue(response(page(true, 500, eventJson("a"))))
    server.enqueue(response(page(true, 300, eventJson("b"))))
    server.enqueue(response(page(false, 200, eventJson("c"))))

    val result = client.events(7, since = null)

    assertEquals(listOf(null, "since=500", "since=300"), List(3) { take().url.encodedQuery })
    assertEquals(listOf("a", "b", "c"), result.events.map { it.event.uuid })
    assertEquals(200L, result.since)
    assertEquals(3, result.pages)
  }

  @Test
  fun c5_repeatedCursorIsApiChanged() = runTest {
    server.enqueue(response(page(true, 500, eventJson("a"))))
    server.enqueue(response(page(true, 300, eventJson("b"))))
    server.enqueue(response(page(true, 500, eventJson("c"))))

    expectError<TtError.ApiChanged>(thrown) { client.events(7, since = null) }
    assertEquals(3, server.requestCount)
  }

  @Test
  fun c5_cursorThatDoesNotMoveIsApiChanged() = runTest {
    server.enqueue(response(page(true, 100, eventJson("a"))))

    expectError<TtError.ApiChanged>(thrown) { client.events(7, since = 100) }
    assertEquals(1, server.requestCount)
  }

  @Test
  fun c5_pagingStopsAfterMaxPages() = runTest {
    server.dispatcher =
      object : Dispatcher() {
        private var since = 0L

        override fun dispatch(request: RecordedRequest): MockResponse =
          response(page(true, ++since, eventJson("e$since")))
      }
    // The real cap is 500 pages; 500 local round trips take ~20 s, so this run uses 20.
    val capped =
      OkHttpTimeTreeClient(
        TimeTreeApi.newOkHttpClient(), store, pacer, sink, server.url("/api/v1/"), maxPages = 20)

    expectError<TtError.ApiChanged>(thrown) { capped.events(7, since = null) }
    assertEquals(20, server.requestCount)
    assertEquals(500, TimeTreeApi.MAX_PAGES)
  }

  // C6 failures mid-paging ---------------------------------------------------------------------

  @Test
  fun c6_serverErrorMidPagingReturnsNoList() = runTest {
    server.enqueue(response(page(true, 200, eventJson("a"))))
    server.enqueue(response("oops", 503))

    val error = expectError<TtError.Transient>(thrown) { client.events(7, since = 100) }
    assertEquals(503, error.httpStatus)
    assertEquals(listOf("calendar_7_events_since_100", "calendar_7_events_since_200_http503"), sink.names)
  }

  @Test
  fun c6_connectionDropMidPagingIsTransient() = runTest {
    server.enqueue(response(page(true, 200, eventJson("a"))))
    // Dropped while the body streams: after the headers, so OkHttp cannot retry it silently.
    server.enqueue(
      MockResponse.Builder()
        .body(page(false, 300, eventJson("b"), eventJson("c")))
        .onResponseBody(SocketEffect.ShutdownConnection)
        .build()
    )

    val error = expectError<TtError.Transient>(thrown) { client.events(7, since = 100) }
    assertNull(error.httpStatus)
  }

  @Test
  fun c6_accessLostMidPaging() = runTest {
    server.enqueue(response(page(true, 200, eventJson("a"))))
    server.enqueue(response(errorBody(-1), 403))

    val error = expectError<TtError.AccessLost>(thrown) { client.events(7, since = 100) }
    assertEquals(7L, error.calendarId)
    assertEquals(403, error.httpStatus)
    assertEquals(0, store.succeeded.size) // 403 on events is not an auth failure
  }

  @Test
  fun c6_malformedPageMidPagingIsApiChanged() = runTest {
    server.enqueue(response(page(true, 200, eventJson("a"))))
    server.enqueue(response("""{"events":[""", 200))

    expectError<TtError.ApiChanged>(thrown) { client.events(7, since = 100) }
  }

  @Test
  fun c6_pageWithoutEventsArrayIsApiChanged() = runTest {
    server.enqueue(response(page(true, 200, eventJson("a"))))
    server.enqueue(response("""{"chunk":false,"since":300}"""))

    expectError<TtError.ApiChanged>(thrown) { client.events(7, since = 100) }
  }

  // Single malformed events and tombstones ----------------------------------------------------

  @Test
  fun malformedLiveEventIsSkippedAndReported_notTheWholeFetch() = runTest {
    val brokenLive = """{"uuid":"bbbbbbbbbbbb","title":"${Fake.SECRET_TITLE}","all_day":false}"""
    val noUuid = """{"title":"${Fake.SECRET_TITLE}","start_at":1}"""
    server.enqueue(response(page(true, 200, eventJson("a"), brokenLive)))
    server.enqueue(response(page(false, 300, noUuid, eventJson("c"))))

    val result = client.events(7, since = 100)

    assertEquals(listOf("a", "c"), result.events.map { it.event.uuid })
    assertEquals(setOf("bbbbbbbbbbbb"), result.skippedUuids)
    assertEquals(2, result.skippedMalformed) // one more without a uuid
    assertEquals(300L, result.since)
    // Only the 8-character uuid prefix is logged, never the content.
    assertTrue(log.lines.any { it.endsWith("skipped malformed event bbbbbbbb") })
    assertTrue(log.lines.any { it.endsWith("skipped malformed event without uuid") })
    assertFalse(log.lines.any { it.contains("bbbbbbbbb") })
  }

  @Test
  fun lastOccurrenceDecidesBetweenGoodAndMalformed() = runTest {
    val broken = { uuid: String -> """{"uuid":"$uuid","all_day":false}""" }
    server.enqueue(response(page(true, 200, eventJson("a"), broken("b"))))
    server.enqueue(response(page(false, 300, broken("a"), eventJson("b"))))

    val result = client.events(7, since = 100)

    assertEquals(listOf("b"), result.events.map { it.event.uuid }) // "a" was broken last
    assertEquals(setOf("a"), result.skippedUuids)
    assertEquals(1, result.skippedMalformed)
  }

  @Test
  fun tombstoneWithoutTimesDecodes() = runTest {
    val tombstone =
      """{"uuid":"t1","recurring_uuid":"p1","parent_id":"p1","deactivated_at":1794470000000,
          "start_at":null}"""
    server.enqueue(response(page(false, 300, eventJson("p1"), tombstone)))

    val result = client.events(7, since = 100)

    val fetched = result.events.single { it.event.uuid == "t1" }
    assertTrue(fetched.event.isDeactivated)
    assertTrue(fetched.event.isOverride)
    assertEquals("p1", fetched.event.recurringUuid)
    assertEquals(0L, fetched.event.startAt)
    assertEquals(1794470000000L, fetched.event.updatedAt)
    assertFalse("raw JSON stays verbatim", fetched.json.containsKey("end_at"))
    assertEquals(emptySet<String>(), result.skippedUuids)
    assertEquals(0, result.skippedMalformed)
  }

  // C7 auth and rate limits --------------------------------------------------------------------

  @Test
  fun c7_oneReloginOn401_thenNoSecondLoginInTheSameRun() = runTest {
    server.enqueue(response(errorBody(-1), 401))
    server.enqueue(response("{}", 200, sessionCookie("sess-new")))
    server.enqueue(response(fixture("calendars.json")))
    server.enqueue(response(errorBody(-1), 401))

    assertEquals(1, client.calendars().size)

    assertEquals("_session_id=sess-old", take().cookie())
    val login = take()
    assertEquals("PUT", login.method)
    assertNull(login.cookie())
    assertEquals("_session_id=sess-new", take().cookie())
    assertEquals(listOf("sess-new"), store.succeeded)
    assertEquals(0, store.rejected)
    assertEquals(emptyList<String>(), store.rotated) // a login is not a rotation
    assertEquals(listOf("calendars_http401", "calendars"), sink.names) // never the login

    // The run's budget is spent: a later 401 fails the run without another login.
    val error = expectError<TtError.Transient>(thrown) { client.labels(7) }
    assertEquals(401, error.httpStatus)
    assertEquals(4, server.requestCount)

    // A new run may log in again.
    client.beginRun("run-2")
    server.enqueue(response(errorBody(-1), 401))
    server.enqueue(response("{}", 200, sessionCookie("sess-new2")))
    server.enqueue(response(fixture("labels.json")))
    assertEquals(10, client.labels(7).size)
    assertEquals(listOf("sess-new", "sess-new2"), store.succeeded)
  }

  @Test
  fun c7_403OnCalendarsAlsoLogsInAgain() = runTest {
    server.enqueue(response(errorBody(-1), 403))
    server.enqueue(response("{}", 200, sessionCookie("sess-new")))
    server.enqueue(response(fixture("calendars.json")))

    assertEquals(1, client.calendars().size)
    assertEquals(listOf("sess-new"), store.succeeded)
  }

  @Test
  fun c7_401RightAfterLoginIsAuthRejected() = runTest {
    server.enqueue(response(errorBody(-1), 401))
    server.enqueue(response("{}", 200, sessionCookie("sess-new")))
    server.enqueue(response(errorBody(-1), 401))

    expectError<TtError.AuthRejected>(thrown) { client.calendars() }
    assertEquals(1, store.rejected)
    assertEquals(3, server.requestCount)
  }

  @Test
  fun c7_minus702OnAutomaticLoginIsAuthRejectedAndRecordedOnce() = runTest {
    server.enqueue(response(errorBody(-1), 401))
    server.enqueue(response(errorBody(-702), 401))

    val error = expectError<TtError.AuthRejected>(thrown) { client.calendars() }
    assertEquals(-702, error.code)
    assertEquals(1, store.rejected)
    assertEquals(emptyList<String>(), store.succeeded)
    assertEquals(2, server.requestCount)
  }

  @Test
  fun c7_minus702OnInteractiveSignInDoesNotTouchTheBlock() = runTest {
    server.enqueue(response(errorBody(-702), 401))

    val error =
      expectError<TtError.AuthRejected>(thrown) { client.signIn(Fake.EMAIL, Fake.PASSWORD) }
    assertEquals(-702, error.code)
    assertEquals(0, store.rejected)
  }

  @Test
  fun c7_noStoredCredentialsIsLoginRequiredWithoutContactingLogin() = runTest {
    store.credentials = null
    server.enqueue(response(errorBody(-1), 401))

    expectError<TtError.LoginRequired>(thrown) { client.calendars() }
    assertEquals(1, server.requestCount)
    assertEquals(0, store.rejected)
  }

  @Test
  fun c7_noSessionAndNoCredentialsSendsNothing() = runTest {
    store.session = null
    store.credentials = null

    expectError<TtError.LoginRequired>(thrown) { client.calendars() }
    assertEquals(0, server.requestCount)
  }

  @Test
  fun c7_noSessionLogsInFirst() = runTest {
    store.session = null
    server.enqueue(response("{}", 200, sessionCookie("sess-new")))
    server.enqueue(response(fixture("calendars.json")))

    assertEquals(1, client.calendars().size)
    assertEquals("PUT", take().method)
    assertEquals("_session_id=sess-new", take().cookie())
  }

  @Test
  fun c7_minus495IsRateLimitedAndBacksOff() = runTest {
    server.enqueue(response(errorBody(-495), 403))

    val error = expectError<TtError.RateLimited>(thrown) { client.calendars() }
    assertNull(error.retryAfter)
    assertEquals(30.minutes, error.backoff)
    assertEquals(listOf(30.minutes), pacer.backOffs)
    assertEquals(0, store.succeeded.size) // not treated as an auth failure
  }

  @Test
  fun c7_minus495OnLoginIsRateLimited() = runTest {
    server.enqueue(response(errorBody(-495), 400))

    expectError<TtError.RateLimited>(thrown) { client.signIn(Fake.EMAIL, Fake.PASSWORD) }
    assertEquals(listOf(30.minutes), pacer.backOffs)
  }

  @Test
  fun c7_429HonoursRetryAfterSeconds() = runTest {
    server.enqueue(response("", 429, "Retry-After" to "7200"))

    val error = expectError<TtError.RateLimited>(thrown) { client.labels(7) }
    assertEquals(2.hours, error.retryAfter)
    assertEquals(2.hours, error.backoff)
    assertEquals(listOf(2.hours), pacer.backOffs)
  }

  @Test
  fun c7_429HonoursRetryAfterDate() = runTest {
    val date =
      DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC)
        .format(Instant.ofEpochMilli(now).plusSeconds(3 * 3600))
    server.enqueue(response("", 429, "Retry-After" to date))

    val error = expectError<TtError.RateLimited>(thrown) { client.events(7, since = null) }
    assertEquals(3.hours, error.retryAfter)
  }

  @Test
  fun c7_429WithoutRetryAfterWaitsThirtyMinutes() = runTest {
    server.enqueue(response("", 429))

    val error = expectError<TtError.RateLimited>(thrown) { client.calendars() }
    assertEquals(30.minutes, error.backoff)
  }

  // Other statuses ----------------------------------------------------------------------------

  @Test
  fun labels404IsAccessLost() = runTest {
    server.enqueue(response("", 404))

    val error = expectError<TtError.AccessLost>(thrown) { client.labels(42) }
    assertEquals(42L, error.calendarId)
    assertEquals(404, error.httpStatus)
  }

  @Test
  fun calendars404IsApiChanged() = runTest {
    server.enqueue(response("", 404))
    expectError<TtError.ApiChanged>(thrown) { client.calendars() }
  }

  @Test
  fun serverErrorIsTransient() = runTest {
    server.enqueue(response("", 502))
    assertEquals(502, expectError<TtError.Transient>(thrown) { client.calendars() }.httpStatus)
  }

  @Test
  fun unexpectedJsonIsApiChanged() = runTest {
    server.enqueue(response("""{"something_else":[]}"""))
    expectError<TtError.ApiChanged>(thrown) { client.calendars() }
  }

  // C8 cookie rotation -------------------------------------------------------------------------

  @Test
  fun c8_rotatedCookieIsSavedAndUsed() = runTest {
    server.enqueue(response(fixture("calendars.json"), 200, sessionCookie("sess-rotated")))
    server.enqueue(response(fixture("labels.json"), 200, sessionCookie("sess-rotated")))

    client.calendars()
    client.labels(7000001)

    assertEquals("_session_id=sess-old", take().cookie())
    assertEquals("_session_id=sess-rotated", take().cookie())
    assertEquals(listOf("sess-rotated"), store.rotated) // the unchanged repeat is not saved again
  }

  @Test
  fun c8_deletedOrRejectedCookiesAreIgnored() = runTest {
    server.enqueue(
      response(fixture("calendars.json"), 200, "Set-Cookie" to "_session_id=; Max-Age=0; path=/")
    )
    server.enqueue(response("", 404, "Set-Cookie" to "other=1; path=/"))
    store.credentials = null
    server.enqueue(response(errorBody(-1), 401, sessionCookie("sess-anonymous")))

    client.calendars()
    expectError<TtError.AccessLost>(thrown) { client.labels(7) }
    expectError<TtError.LoginRequired>(thrown) { client.calendars() }

    assertEquals(emptyList<String>(), store.rotated)
    assertEquals("sess-old", store.session)
  }

  // Phase 2 review fixes -----------------------------------------------------------------------

  @Test
  fun cookiesOfErrorResponsesAreNeverSaved() = runTest {
    // SHOULD-FIX-1: only a 2xx response rotates the session, not a proxy's error page.
    server.enqueue(response("oops", 503, sessionCookie("sess-proxy")))
    server.enqueue(response(errorBody(-1), 404, sessionCookie("sess-404")))
    server.enqueue(response(errorBody(-495), 429, sessionCookie("sess-429")))

    expectError<TtError.Transient>(thrown) { client.calendars() }
    expectError<TtError.AccessLost>(thrown) { client.labels(7) }
    expectError<TtError.RateLimited>(thrown) { client.events(7, since = null) }

    assertEquals(emptyList<String>(), store.rotated)
    assertEquals("sess-old", store.session)
  }

  @Test
  fun anEmptyStoredSessionCountsAsNone() = runTest {
    // NIT-1: no request with `_session_id=`; log in first instead.
    store.session = ""
    server.enqueue(response("{}", 200, sessionCookie("sess-new")))
    server.enqueue(response(fixture("calendars.json")))

    client.calendars()

    assertEquals("PUT", take().method)
    assertEquals("_session_id=sess-new", take().cookie())
    assertEquals(listOf("sess-new"), store.succeeded)
  }

  // Interactive calls (Sources screen) ----------------------------------------------------------

  @Test
  fun interactiveCallsHaveTheirOwnLoginBudgetAndAreNotDumped() = runTest {
    // The run spends its one login.
    server.enqueue(response(errorBody(-1), 401))
    server.enqueue(response("{}", 200, sessionCookie("sess-run")))
    server.enqueue(response(fixture("calendars.json")))
    client.calendars()
    val dumped = sink.names.toList()

    // The session expires again: a screen may still log in once, without touching the run's budget.
    server.enqueue(response(errorBody(-1), 401))
    server.enqueue(response("{}", 200, sessionCookie("sess-screen")))
    server.enqueue(response(fixture("calendars.json")))
    assertEquals(1, client.calendarsInteractive().size)

    assertEquals(listOf("sess-run", "sess-screen"), store.succeeded)
    assertEquals(dumped, sink.names) // the screen's responses are not in the run's dump

    // The run's budget is still spent.
    server.enqueue(response(errorBody(-1), 401))
    expectError<TtError.Transient>(thrown) { client.labels(7) }
    assertEquals(7, server.requestCount)
  }

  @Test
  fun aScreenAndARunAtOnceLogInOnlyOnce() = runTest {
    // Both callers find the session expired; the second one waits and uses the stored new session.
    server.dispatcher =
      object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse =
          when {
            request.method == "PUT" -> response("{}", 200, sessionCookie("sess-new"))
            request.headers["Cookie"] == "_session_id=sess-new" -> response(fixture("calendars.json"))
            else -> response(errorBody(-1), 401)
          }
      }

    val results =
      listOf(
          async(Dispatchers.Default) { client.calendars() },
          async(Dispatchers.Default) { client.calendarsInteractive() },
        )
        .awaitAll()

    assertEquals(listOf(1, 1), results.map { it.size })
    assertEquals(listOf("sess-new"), store.succeeded)
    val logins = List(server.requestCount) { take() }.count { it.method == "PUT" }
    assertEquals(1, logins)
  }
}
