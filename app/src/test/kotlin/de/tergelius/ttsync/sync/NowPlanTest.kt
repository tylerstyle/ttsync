package de.tergelius.ttsync.sync

import androidx.work.WorkInfo.State
import de.tergelius.ttsync.sync.WorkManagerSyncScheduler.Companion.planNow
import de.tergelius.ttsync.sync.WorkManagerSyncScheduler.NowPlan
import de.tergelius.ttsync.sync.WorkManagerSyncScheduler.NowWork
import de.tergelius.ttsync.timetree.RequestPacer
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [WorkManagerSyncScheduler.planNow]: what a "now" request does with the unique work as it stands
 * (phase 5 review SHOULD-FIX-2, device test F4).
 */
class NowPlanTest {
  private val running = NowWork(UUID.randomUUID(), State.RUNNING, full = false)

  private fun followUp(full: Boolean = false, state: State = State.BLOCKED) =
    NowWork(UUID.randomUUID(), state, full)

  @Test
  fun idleEnqueuesExpeditedWork() {
    assertEquals(NowPlan.Enqueue(full = false, afterRunning = false), planNow(emptyList(), false, null))
    assertEquals(NowPlan.Enqueue(full = true, afterRunning = false), planNow(emptyList(), true, null))
    val finished = listOf(NowWork(UUID.randomUUID(), State.SUCCEEDED, full = true))
    assertEquals(NowPlan.Enqueue(full = false, afterRunning = false), planNow(finished, false, null))
  }

  @Test
  fun rightAfterASuccessfulRunANonFullRequestNeedsNoRun() {
    assertEquals(NowPlan.RecentlySynced(20_000), planNow(emptyList(), false, 20_000))
    // A FULL request (setup, sources, target, "Full resync") always runs.
    assertEquals(NowPlan.Enqueue(full = true, afterRunning = false), planNow(emptyList(), true, 20_000))
    // So does a pending FULL, which the request then keeps.
    val pendingFull = listOf(followUp(full = true, state = State.ENQUEUED))
    assertEquals(NowPlan.Enqueue(full = true, afterRunning = false), planNow(pendingFull, false, 20_000))
  }

  @Test
  fun aPendingRequestIsReplacedAndAPendingFullStaysFull() {
    val pending = listOf(followUp(full = true, state = State.ENQUEUED))
    assertEquals(NowPlan.Enqueue(full = true, afterRunning = false), planNow(pending, false, null))
  }

  @Test
  fun theFirstRequestDuringARunBecomesItsFollowUp() {
    assertEquals(NowPlan.Enqueue(full = false, afterRunning = true), planNow(listOf(running), false, null))
    // Even right after a run: the running one may have started before the change.
    assertEquals(
      NowPlan.Enqueue(full = false, afterRunning = true),
      planNow(listOf(running), false, 5_000),
    )
  }

  @Test
  fun laterRequestsDuringARunCoalesceIntoTheOneFollowUp() {
    var queue = listOf(running)
    var enqueued = 0
    repeat(5) {
      when (val plan = planNow(queue, full = false, recentSuccessAgo = null)) {
        is NowPlan.Enqueue -> {
          enqueued++
          queue = queue + followUp(full = plan.full)
        }
        is NowPlan.Coalesce -> Unit
        else -> throw AssertionError("unexpected $plan")
      }
    }
    assertEquals(1, enqueued)
    assertEquals(2, queue.size)
  }

  @Test
  fun fullWinsOverAWaitingFollowUp() {
    val partial = followUp(full = false)
    assertEquals(
      NowPlan.UpgradeToFull(listOf(partial.id)),
      planNow(listOf(running, partial), full = true, recentSuccessAgo = null),
    )
    val full = followUp(full = true)
    assertEquals(NowPlan.Coalesce(full = true), planNow(listOf(running, full), false, null))
    assertEquals(NowPlan.Coalesce(full = true), planNow(listOf(running, full), true, null))
  }

  @Test
  fun recentSuccessAgoOnlyForASuccessWithinTheGap() {
    val now = 1_794_481_200_000L
    val gap = RequestPacer.MANUAL_RUN_GAP.inWholeMilliseconds
    fun finished(at: Long, failure: SyncFailure? = null) =
      SyncReport("r", SyncTrigger.MANUAL, startedAt = at - 5_000, finishedAt = at, failure = failure)
    assertEquals(30_000L, SyncScheduler.recentSuccessAgo(finished(now - 30_000), now))
    assertNull(SyncScheduler.recentSuccessAgo(finished(now - gap), now))
    assertNull(SyncScheduler.recentSuccessAgo(finished(now - 1_000, SyncFailure(SyncProblem.NETWORK)), now))
    assertNull(SyncScheduler.recentSuccessAgo(finished(now + 1_000), now)) // clock went back
    assertNull(SyncScheduler.recentSuccessAgo(null, now))
  }
}
