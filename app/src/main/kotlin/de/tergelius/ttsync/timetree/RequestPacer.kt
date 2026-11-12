package de.tergelius.ttsync.timetree

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keeps the client polite (DESIGN.md section 3, "Pacing"). One instance per process.
 *
 * Contract: [awaitSlot] suspends until at least [MIN_GAP] has passed since the previous request
 * started, then reserves the slot. Concurrent callers are served one after the other. Must be
 * testable without real time (inject the clock and the delay function).
 *
 * Back-off: after [backOff] (the client calls it for `-495` and HTTP 429), [awaitSlot] throws
 * [TtError.RateLimited] without waiting until the back-off has passed, so nothing in this process
 * contacts TimeTree in the meantime, not even an interactive sign-in. The persistent "rate limited
 * until" state that survives a process restart is the engine's job (`StatusRepository`).
 */
interface RequestPacer {
  /** @throws TtError.RateLimited while a [backOff] is in effect */
  suspend fun awaitSlot()

  /** The server asked us to slow down: refuse every request for [duration]. */
  fun backOff(duration: Duration) {}

  companion object {
    /** Minimum time between two requests. */
    val MIN_GAP: Duration = 1.seconds

    /** "Sync now" starts no earlier than this after the previous run finished. */
    val MANUAL_RUN_GAP: Duration = 60.seconds
  }
}

/**
 * The process-wide [RequestPacer]. [nowMillis] must be monotonic (the default is), [delay] is
 * replaceable for tests.
 */
class DefaultRequestPacer(
  private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
  private val delay: suspend (Duration) -> Unit = { kotlinx.coroutines.delay(it) },
  private val minGap: Duration = RequestPacer.MIN_GAP,
) : RequestPacer {
  private val mutex = Mutex()

  /** Start of the previous request, or null before the first one. Guarded by [mutex]. */
  private var lastStart: Long? = null

  /** No request before this time ([nowMillis] scale), or null. */
  @Volatile private var blockedUntil: Long? = null

  override suspend fun awaitSlot() {
    mutex.withLock {
      checkBackOff()
      val last = lastStart
      if (last != null) {
        // Capped at minGap so that a clock that jumps backwards cannot stall requests.
        val waitMs = (last + minGap.inWholeMilliseconds - nowMillis())
          .coerceIn(0, minGap.inWholeMilliseconds)
        if (waitMs > 0) delay(waitMs.milliseconds)
      }
      checkBackOff()
      lastStart = nowMillis()
    }
  }

  override fun backOff(duration: Duration) {
    if (!duration.isPositive()) return
    val until = nowMillis() + duration.inWholeMilliseconds
    synchronized(this) { blockedUntil = maxOf(blockedUntil ?: until, until) }
  }

  private fun checkBackOff() {
    val until = blockedUntil ?: return
    val remaining = until - nowMillis()
    // The remaining time as it is: an ongoing back-off must not be stretched to MIN_BACKOFF again.
    if (remaining > 0) throw TtError.RateLimited(remaining.milliseconds, backoff = remaining.milliseconds)
  }
}
