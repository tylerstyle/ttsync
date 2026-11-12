package de.tergelius.ttsync.timetree

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultRequestPacerTest {
  /** A fake clock that only moves when the pacer delays or the test advances it. */
  private class FakeTime(var now: Long = 10_000) {
    val delays = mutableListOf<Duration>()

    suspend fun delay(d: Duration) {
      delays += d
      now += d.inWholeMilliseconds
    }
  }

  @Test
  fun keepsOneSecondBetweenRequests() = runTest {
    val time = FakeTime()
    val pacer = DefaultRequestPacer(nowMillis = { time.now }, delay = time::delay)

    pacer.awaitSlot() // first request: immediate
    time.now += 300
    pacer.awaitSlot() // 300 ms later: waits the remaining 700 ms
    time.now += 5_000
    pacer.awaitSlot() // long after: immediate

    assertEquals(listOf(700.toLong()), time.delays.map { it.inWholeMilliseconds })
  }

  @Test
  fun concurrentCallersAreServedOneAfterTheOther() = runTest {
    val pacer = DefaultRequestPacer(nowMillis = { testScheduler.currentTime })

    val starts =
      List(3) {
          async {
            pacer.awaitSlot()
            currentTime
          }
        }
        .awaitAll()

    assertEquals(listOf(0L, 1_000L, 2_000L), starts.sorted())
  }

  @Test
  fun clockJumpingBackwardsWaitsAtMostOneGap() = runTest {
    val time = FakeTime(now = 1_000_000)
    val pacer = DefaultRequestPacer(nowMillis = { time.now }, delay = time::delay)

    pacer.awaitSlot()
    time.now = 0
    pacer.awaitSlot()

    assertEquals(listOf(1.seconds), time.delays)
  }

  @Test
  fun backOffRefusesRequestsUntilItEnds() = runTest {
    val time = FakeTime()
    val pacer = DefaultRequestPacer(nowMillis = { time.now }, delay = time::delay)

    pacer.backOff(30.minutes)
    time.now += 10.minutes.inWholeMilliseconds
    try {
      pacer.awaitSlot()
      fail("expected RateLimited")
    } catch (e: TtError.RateLimited) {
      assertEquals(20.minutes, e.retryAfter)
      assertTrue(e.backoff >= TtError.RateLimited.MIN_BACKOFF)
    }

    pacer.backOff(1.minutes) // a shorter back-off never shortens a longer one
    time.now += 19.minutes.inWholeMilliseconds
    try {
      pacer.awaitSlot()
      fail("expected RateLimited")
    } catch (e: TtError.RateLimited) {
      assertEquals(1.minutes, e.retryAfter)
    }

    time.now += 1.minutes.inWholeMilliseconds
    pacer.awaitSlot() // over
  }
}
