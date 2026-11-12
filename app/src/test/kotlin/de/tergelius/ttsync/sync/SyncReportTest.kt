package de.tergelius.ttsync.sync

import de.tergelius.ttsync.store.CalendarState
import kotlin.random.Random
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncReportTest {
  @Test
  fun reportRoundTripsThroughJson() {
    val report =
      SyncReport(
        runId = "20261112-080000-00ff",
        trigger = SyncTrigger.PERIODIC,
        startedAt = 1,
        finishedAt = 2,
        failure = SyncFailure(SyncProblem.RATE_LIMITED, code = -495, retryAfterMillis = 1_800_000),
        counts = SyncCounts(added = 3, filtered = 2),
        calendars = listOf(CalendarReport(7, SyncMode.FULL, CalendarState.ACTIVE, SyncCounts(added = 3, filtered = 2))),
        warnings = setOf(SyncWarning.GOOGLE_SYNC_OFF),
      )
    val json = Json.encodeToString(SyncReport.serializer(), report)
    assertEquals(report, Json.decodeFromString(SyncReport.serializer(), json))
    assertFalse(report.succeeded)
  }

  @Test
  fun countsAdd() {
    val sum = SyncCounts(added = 1, deleted = 2, filtered = 4) + SyncCounts(updated = 3, unmatchedOverrides = 1, filtered = 1)
    assertEquals(6, sum.writes)
    assertEquals(1, sum.unmatchedOverrides)
    assertEquals(5, sum.filtered)
    assertEquals(0, SyncCounts().writes)
  }

  @Test
  fun problemClasses() {
    assertTrue(SyncProblem.NETWORK.retryable)
    assertTrue(SyncProblem.TARGET_DELETED.userMustAct)
    assertFalse(SyncProblem.TARGET_DELETED.retryable)
  }

  @Test
  fun runIdIsSortableAndFileNameSafe() {
    val id = SyncRequest.newRunId(1_794_481_200_000, Random(1))
    assertTrue(id, Regex("""20261112-110000-[0-9a-f]{4}""").matches(id))
  }
}
