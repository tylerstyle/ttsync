# Review of Phase 2: TimeTree Web API Client

**Reviewer:** android-agy (Antigravity / agy)  
**Date:** 2026-11-12  
**Commit Range:** `d287d32..4c7437b` (package `de.tergelius.ttsync.timetree`, `src/debug` raw dump sink, tests, and fixtures)  
**Reference Document:** `ttsync/docs/DESIGN.md` (Sections 3, 6, 10)  

---

## Executive Summary

Phase 2 implements the TimeTree web API client cleanly and robustly, satisfying the non-functional and behavioral requirements in `DESIGN.md` Section 3 and Section 6:
- **Secrets & Privacy:** Email, password, session cookies, and event content (titles, notes, locations, urls) never appear in logs, debug dump folder names, or exception messages. All wire DTOs implement redacted `toString()` implementations. `decodeTt` drops inner serialization causes so JSON fragments never leak via exception cause chains. Login requests and responses completely bypass the debug raw sink.
- **Session & Re-Login Bounding:** The one-relogin-per-run budget (`reloginAvailable`) and post-login detection (`justLoggedIn`) ensure that neither the internal loop in `dataCall` nor multiple calls in a run can loop or hammer TimeTree. A 401 right after login or a `-702` code properly records the 6-hour auth block (`onAutoLoginRejected`).
- **Rate Limiting & Back-Off:** `-495` and HTTP 429 back off for at least 30 minutes (or the server's `Retry-After`), immediately placing the in-memory `DefaultRequestPacer` into refusal mode.
- **Paging Guarantees:** Paging follows decreasing cursors, terminates on `chunk == false`, detects repeated cursors via a `requested` set, and enforces a hard cap of 500 pages. Mid-paging failures immediately abort the run without returning partial lists.
- **Lenient Decoding:** Tombstones missing timestamps decode to designated sentinel values (`start_at = 0L, end_at = 0L, all_day = false, updated_at = deactivated_at`), and malformed live events are skipped and reported without breaking the entire page or fetch.
- **OkHttp Lifecycle:** Requests are executed asynchronously via `suspendCancellableCoroutine`, cancelling the OkHttp `Call` upon coroutine cancellation. `response.use` ensures sockets and streams are always closed, and response bodies are read on OkHttp's dispatcher thread pool.
- **Test Quality:** 38 unit tests cover C1–C8, socket drops, cycle detection, rate limit back-off, and tombstone parsing. Every test asserts via `@After` that pacer slots match request count and that zero secrets leak to logs, exceptions, or dumps.

There are **0 Blockers**, **2 Should-Fix** items (handling of session cookie rotation on non-2xx responses and remaining duration preservation on pacer rate-limit rejection), and **3 Nits** (handling empty session string, stale pacer timestamp retention, and primitive error code decoding).

---

## Assessment Across Focus Areas

### 1. Secrets & Privacy (Password, Email, Cookie, Event Titles)
- **DTOs:** `TtCredentials`, `TtSignInRequest`, `TtEvent`, `FetchedEvent`, `TtCalendar`, `TtLabel`, and `TtEventsPage` all provide explicit, redacted `toString()` overrides. UUIDs are truncated to 8 characters (`take(8)`).
- **Exceptions:** `decodeTt` catches `SerializationException` and `IllegalArgumentException` and constructs a clean `TtError.ApiChanged` without attaching the caught exception as its `cause`. This prevents kotlinx-serialization from quoting sensitive JSON strings in logcat stack traces.
- **Raw Dump Sink:** `login()` never calls `rawSink.record()`. `dataCall` only passes response bodies; request payloads, headers, and cookies are never passed or dumped. Dump file names contain only static endpoint labels, calendar IDs, and cursors.
- **Logging:** All log calls throughout `OkHttpTimeTreeClient` log only HTTP statuses, error codes, counts, and 8-character UUID prefixes.

### 2. Session / Re-Login Logic & Hammer Prevention
- **Run Budget:** `reloginAvailable` is initialized to `true` on `beginRun()` and reset to `false` immediately upon entering `autoLogin()`. Any subsequent 401 in the same run throws `TtError.Transient(status)` without contacting the login endpoint.
- **Loop Termination:** In `dataCall`, `justLoggedIn` tracks whether the immediate prior step was an `autoLogin()`. An auth failure with `justLoggedIn == true` immediately triggers `sessions.onAutoLoginRejected()` and throws `TtError.AuthRejected`. The internal `while (true)` loop can never execute more than 2 iterations.
- **6-Hour Block:** When `-702` or immediate 401 occurs, `onAutoLoginRejected()` is called before throwing `AuthRejected`. Future runs checking `sessions.autoLoginCredentials()` will receive `null` during the 6-hour block and immediately throw `TtError.LoginRequired` without touching the network. Interactive `signIn()` correctly does not trigger `onAutoLoginRejected()`.

### 3. Rate Limiting & Back-Off
- Both `-495` (top-level or inside `error.code`) and HTTP 429 trigger `rateLimited()`.
- `Retry-After` is parsed from either delta-seconds or HTTP RFC-1123 dates, clamped to at least `MIN_BACKOFF` (30 minutes).
- `pacer.backOff(error.backoff)` sets `blockedUntil` monotonically. Any request arriving during this period in `DefaultRequestPacer.awaitSlot()` throws `TtError.RateLimited` without waiting and without making network requests.

### 4. Paging Integrity
- Paging loop checks `!page.chunk` to return.
- The `requested` set tracks every requested cursor; any return of a previously requested cursor immediately throws `TtError.ApiChanged`.
- Decreasing cursors are followed without issue.
- Exceeding `maxPages` (500) throws `TtError.ApiChanged`.
- Deduplication uses `LinkedHashMap`: `byUuid.remove()` before insertion moves updated events to the tail, guaranteeing that the last occurrence wins at its own position.
- Network or decoding failures mid-paging bubble up as exceptions; partial lists are never returned.

### 5. Lenient Tombstone & Malformed Event Decoding
- `TtEvent.fromJson` runs `withTombstoneDefaults()`: if `deactivated_at != null`, missing or null `all_day`, `start_at`, `end_at`, and `updated_at` are populated with safe defaults (`0L` and `deactivated_at`).
- Live events missing required timestamp fields fail with `ApiChanged` inside `events()`, which catches them, skips them, records their UUID in `skippedUuids` (or increments `skippedWithoutUuid`), logs a warning with the 8-char prefix, and continues the fetch.

### 6. OkHttp Usage & Resource Lifecycle
- `TimeTreeApi.newOkHttpClient()` configures connect (15s), read (30s), and call (60s) timeouts matching DESIGN.md Section 3.
- `Call.await()` attaches `cont.invokeOnCancellation { cancel() }` to abort in-flight requests when coroutines are cancelled.
- `response.use { ... }` ensures response streams are closed even if body reading fails with `IOException`.
- Response string reading occurs inside OkHttp's dispatcher thread before resuming the coroutine.

### 7. Test Quality
- 38 unit tests cover all edge cases: standard runs, cursor cycle detection, mid-paging drops, tombstone decoding, 401 re-login isolation, `-702` block handling, and rate limit header parsing.
- Fixture tests test real, sanitized TimeTree payloads.
- TearDown assertion strictly verifies no secrets leak into logs, exception message chains, or dump file names.

---

## Findings Ranked by Severity

### 1. Blockers
None.

---

### 2. Should-Fix Issues

#### [SHOULD-FIX-1] Session cookie rotation is performed on HTTP error responses (`!authFailure` instead of `response.isSuccessful`)
- **File & Line:** `app/src/main/kotlin/de/tergelius/ttsync/timetree/OkHttpTimeTreeClient.kt:150`
- **Problem:**  
  In `dataCall`:
  ```kotlin
  val authFailure = status == 401 || (status == 403 && endpoint is Endpoint.Calendars)
  if (!authFailure) saveRotatedSession(url, response.headers, sessionId)
  ```
  `!authFailure` evaluates to `true` for any non-auth failure HTTP status, including 403 on events/labels, 404, 429, 500, 502, and 503. If a reverse proxy (e.g. Cloudflare, AWS), load balancer, or TimeTree error handler emits a `Set-Cookie: _session_id=...` header on an error response, `saveRotatedSession` will rotate and persist this error cookie via `sessions.onSessionRotated(fresh)`.  
  The sync run will subsequently abort with `Transient`, `AccessLost`, or `RateLimited`, but the stored session will have been overwritten with the invalid error cookie. The next sync run will then start with a broken session, forcing an unnecessary 401 round trip and consuming the run's single auto-login budget.
- **Concrete Fix:**  
  Rotate session cookies only on successful (2xx) responses:
  ```kotlin
  if (response.isSuccessful) saveRotatedSession(url, response.headers, sessionId)
  ```

#### [SHOULD-FIX-2] `TtError.RateLimited.backoff` re-inflates remaining back-off duration back to 30 minutes when thrown by `RequestPacer.checkBackOff()`
- **File & Line:** `app/src/main/kotlin/de/tergelius/ttsync/timetree/TtError.kt:48-49` & `app/src/main/kotlin/de/tergelius/ttsync/timetree/RequestPacer.kt:77-78`
- **Problem:**  
  In `DefaultRequestPacer.kt:77-78`, when a back-off is active, `checkBackOff()` computes the remaining time until expiration:
  ```kotlin
  val remaining = until - nowMillis()
  if (remaining > 0) throw TtError.RateLimited(remaining.milliseconds)
  ```
  However, in `TtError.kt:48-49`, `backoff` is defined as:
  ```kotlin
  val backoff: Duration
    get() = retryAfter?.coerceAtLeast(MIN_BACKOFF) ?: MIN_BACKOFF
  ```
  Where `MIN_BACKOFF = 30.minutes`.  
  When an ongoing back-off has e.g. 3 minutes remaining, `e.retryAfter` is `3.minutes`, but `e.backoff` evaluates to `30.minutes`. If the sync engine (Phase 5) catches `TtError.RateLimited` and schedules the next retry delay using `error.backoff` (standard pattern for rate limits), the delay will be artificially reset to 30 minutes instead of the remaining 3 minutes.
- **Concrete Fix:**  
  Allow `RateLimited` to preserve the exact backoff duration without re-coercing when it represents an ongoing refusal, or provide an explicit constructor parameter:
  ```kotlin
  class RateLimited(
    val retryAfter: Duration?,
    val backoff: Duration = retryAfter?.coerceAtLeast(MIN_BACKOFF) ?: MIN_BACKOFF,
  ) : TtError("rate limited (retryAfter=$retryAfter)")
  ```
  And in `DefaultRequestPacer.kt:78`:
  ```kotlin
  if (remaining > 0) throw TtError.RateLimited(remaining.milliseconds, backoff = remaining.milliseconds)
  ```

---

### 3. Nits

#### [NIT-1] Empty session ID string `""` triggers an unnecessary network round trip and consumes auto-login budget
- **File & Line:** `app/src/main/kotlin/de/tergelius/ttsync/timetree/OkHttpTimeTreeClient.kt:142-144`
- **Problem:**  
  ```kotlin
  var sessionId =
    sessions.sessionId()
      ?: autoLogin(endpoint, reason = "no session").also { justLoggedIn = true }
  ```
  If `sessions.sessionId()` returns an empty string `""` (e.g. if an uninitialized preference or corrupted key returns `""`), the Elvis operator does not trigger because `""` is non-null. The client issues a request with `Cookie: _session_id=`, which is guaranteed to return 401, wasting an HTTP round trip and consuming the run's single auto-login budget before retrying.
- **Concrete Fix:**  
  Use `takeIf { it.isNotEmpty() }`:
  ```kotlin
  var sessionId =
    sessions.sessionId()?.takeIf { it.isNotEmpty() }
      ?: autoLogin(endpoint, reason = "no session").also { justLoggedIn = true }
  ```

#### [NIT-2] `DefaultRequestPacer.backOff` retains expired timestamps in `blockedUntil`
- **File & Line:** `app/src/main/kotlin/de/tergelius/ttsync/timetree/RequestPacer.kt:71-73`
- **Problem:**  
  ```kotlin
  override fun backOff(duration: Duration) {
    if (!duration.isPositive()) return
    val until = nowMillis() + duration.inWholeMilliseconds
    synchronized(this) { blockedUntil = maxOf(blockedUntil ?: until, until) }
  }
  ```
  If `blockedUntil` expired in the past, `blockedUntil` is not cleared. While `nowMillis()` is monotonic and a new `until` (`nowMillis() + duration`) will always be greater than an expired timestamp, retaining the stale timestamp indefinitely in memory is untidy.
- **Concrete Fix:**  
  Only compare against `blockedUntil` if it is still in the future:
  ```kotlin
  override fun backOff(duration: Duration) {
    if (!duration.isPositive()) return
    val now = nowMillis()
    val until = now + duration.inWholeMilliseconds
    synchronized(this) {
      val current = blockedUntil
      blockedUntil = if (current != null && current > now) maxOf(current, until) else until
    }
  }
  ```

#### [NIT-3] `apiErrorCode` does not recognize primitive numbers under `"error"`
- **File & Line:** `app/src/main/kotlin/de/tergelius/ttsync/timetree/OkHttpTimeTreeClient.kt:373`
- **Problem:**  
  `val code = (root["error"] as? JsonObject)?.get("code") ?: root["code"]`  
  If an API gateway or proxy returns an error formatted as `{"error": -702}`, `root["error"] as? JsonObject` is null and `root["code"]` is null, causing `apiErrorCode` to return `null`.
- **Concrete Fix:**  
  Add `root["error"] as? JsonPrimitive` to the fallback chain:
  ```kotlin
  val code =
    (root["error"] as? JsonObject)?.get("code")
      ?: (root["error"] as? JsonPrimitive)
      ?: root["code"]
  ```

---

## Resolution (android-claude, phase 5, 2026-11-12)

- SHOULD-FIX-1: fixed. `dataCall` saves a rotated `_session_id` only from a 2xx response; cookies on
  error responses (auth failures, 4xx, 429, a proxy's 5xx page) are ignored. New test
  `cookiesOfErrorResponsesAreNeverSaved`.
- SHOULD-FIX-2: fixed. `TtError.RateLimited` takes `backoff` as a constructor parameter (default:
  `Retry-After`, at least 30 min); `DefaultRequestPacer` passes the exact remaining time, so the
  engine's and the login's `setRateLimitedUntil` keep it. `DefaultRequestPacerTest` checks it.
- NIT-1: fixed twice: `dataCall` treats an empty session as none (logs in first), and
  `StoredSessionStore.sessionId()` never returns an empty string. New tests
  `anEmptyStoredSessionCountsAsNone`, `StoredSessionStoreTest`.
- NIT-2, NIT-3: not changed (no behaviour difference for real traffic).
- Also in phase 5: request exchanges are serialized by a client lock, and the Sources screen uses
  `calendarsInteractive()` with its own login budget (no double login while a sync runs). Tests
  `interactiveCallsHaveTheirOwnLoginBudgetAndAreNotDumped`, `aScreenAndARunAtOnceLogInOnlyOnce`.
