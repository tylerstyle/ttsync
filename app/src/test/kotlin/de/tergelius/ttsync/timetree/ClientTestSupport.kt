package de.tergelius.ttsync.timetree

import de.tergelius.ttsync.TtLog
import kotlin.time.Duration
import mockwebserver3.MockResponse
import org.junit.Assert.fail

/** Test doubles and builders shared by the TimeTree client tests. */
internal object Fake {
  const val EMAIL = "me@example.org"
  const val PASSWORD = "hunter2-secret"
  const val UUID = "0123456789abcdef0123456789abcdef"
  const val SECRET_TITLE = "Secret dentist"

  /** Strings that must never show up in logs or exception messages. */
  val SECRETS = listOf(PASSWORD, EMAIL, "sess-", SECRET_TITLE)
}

internal class FakeSessionStore(
  var session: String? = null,
  var credentials: TtCredentials? = TtCredentials(Fake.EMAIL, Fake.PASSWORD),
) : TtSessionStore {
  val rotated = mutableListOf<String>()
  val succeeded = mutableListOf<String>()
  var rejected = 0
  var uuidCalls = 0

  override suspend fun installUuid(): String {
    uuidCalls++
    return Fake.UUID
  }

  override suspend fun sessionId(): String? = session

  override suspend fun onSessionRotated(sessionId: String) {
    rotated += sessionId
    session = sessionId
  }

  override suspend fun autoLoginCredentials(): TtCredentials? = credentials

  override suspend fun onAutoLoginSucceeded(sessionId: String) {
    succeeded += sessionId
    session = sessionId
  }

  override suspend fun onAutoLoginRejected() {
    rejected++
  }
}

internal class RecordingPacer : RequestPacer {
  var slots = 0
  val backOffs = mutableListOf<Duration>()

  override suspend fun awaitSlot() {
    slots++
  }

  override fun backOff(duration: Duration) {
    backOffs += duration
  }
}

internal class RecordingSink : RawResponseSink {
  val runs = mutableListOf<String>()
  val records = mutableListOf<Pair<String, String>>()

  val names: List<String>
    get() = records.map { it.first }

  override fun beginRun(runId: String) {
    runs += runId
  }

  override fun record(name: String, body: String) {
    records += name to body
  }

  override fun wipe() {
    records.clear()
  }
}

internal class RecordingLog : TtLog {
  val lines = mutableListOf<String>()

  override fun d(message: String) {
    lines += message
  }

  override fun i(message: String) {
    lines += message
  }

  override fun w(message: String, error: Throwable?) {
    lines += message + (error?.let { " $it" } ?: "")
  }

  override fun e(message: String, error: Throwable?) {
    lines += message + (error?.let { " $it" } ?: "")
  }
}

internal fun response(
  body: String,
  code: Int = 200,
  vararg headers: Pair<String, String>,
): MockResponse =
  MockResponse.Builder()
    .code(code)
    .body(body)
    .apply { headers.forEach { (name, value) -> addHeader(name, value) } }
    .build()

internal fun sessionCookie(value: String) =
  "Set-Cookie" to "_session_id=$value; path=/; secure; HttpOnly; SameSite=Lax"

internal fun errorBody(code: Int) = """{"error":{"code":$code,"message":"nope"}}"""

/** A minimal live event as the API sends it (see the real-shape fixture events-page.json). */
internal fun eventJson(uuid: String, updatedAt: Long = 1794465807011, title: String = "Event"): String =
  """{"id":"$uuid","uuid":"$uuid","calendar_id":7,"category":1,"type":0,"title":"$title",
     "all_day":false,"start_at":1794924000000,"start_timezone":"Europe/Berlin",
     "end_at":1794927600000,"end_timezone":"Europe/Berlin","label_id":1,"location":"",
     "recurrences":[],"recurring_uuid":null,"parent_id":null,"deactivated_at":null,
     "updated_at":$updatedAt,"created_at":1794465807011}"""

internal fun page(chunk: Boolean, since: Long, vararg events: String): String =
  """{"since":$since,"chunk":$chunk,"events":[${events.joinToString(",")}]}"""

internal fun fixture(name: String): String =
  checkNotNull(Fake::class.java.getResource("/fixtures/timetree/$name")) { "missing fixture $name" }
    .readText()

/** Runs [block], expects a [T] and returns it; any other outcome fails the test. */
internal inline fun <reified T : Throwable> expectError(
  thrown: MutableList<Throwable>,
  block: () -> Unit,
): T {
  try {
    block()
  } catch (e: Throwable) {
    thrown += e
    if (e is T) return e
    throw AssertionError("expected ${T::class.simpleName}, got $e", e)
  }
  fail("expected ${T::class.simpleName}, but nothing was thrown")
  throw IllegalStateException()
}
