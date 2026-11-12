package de.tergelius.ttsync.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import de.tergelius.ttsync.AppGraph
import de.tergelius.ttsync.appGraph
import de.tergelius.ttsync.sync.SyncScheduler
import de.tergelius.ttsync.sync.SyncTrigger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Debug builds only (DESIGN.md section 8): lets adb trigger runs. Guarded by
 * `android.permission.DUMP` in `src/debug/AndroidManifest.xml`, so only the adb shell can send to
 * it. Actions:
 * - [ACTION_SYNC_NOW]: `SyncScheduler.runNow(full = false, trigger = DEBUG)`; `--ez full true`
 *   makes it FULL.
 * - [ACTION_FULL_SYNC]: the same with `full = true`.
 * - [ACTION_DROP_MAPPINGS]: `SyncEngine.dropMappings()`, so the next run must adopt.
 *
 * Each action logs one line `debug <ACTION>: <result>` with tag `TtSync` (no secrets, no event
 * content) and, for `am broadcast`, also returns it as the broadcast's result data. The sync itself
 * then logs `run started` and `run finished` lines.
 *
 * `adb shell am broadcast -a de.tergelius.ttsync.debug.SYNC_NOW -n de.tergelius.ttsync/.debug.DebugReceiver`
 */
class DebugReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val graph = context.appGraph
    val action = intent.action
    val name = action?.substringAfterLast('.') ?: "?"
    val full = intent.getBooleanExtra(EXTRA_FULL, false)
    val ordered = isOrderedBroadcast
    val pending = goAsync()
    graph.appScope.launch {
      try {
        val line =
          try {
            withTimeout(TIMEOUT) { handle(graph, action, full) }
          } catch (_: TimeoutCancellationException) {
            "timed out after ${TIMEOUT.inWholeSeconds} s (a sync is probably running); try again"
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            "failed (${e.javaClass.simpleName})"
          }
        val result = "debug $name: $line"
        graph.log.i(result)
        if (ordered) pending.resultData = result
      } finally {
        pending.finish()
      }
    }
  }

  private suspend fun handle(graph: AppGraph, action: String?, full: Boolean): String =
    when (action) {
      ACTION_SYNC_NOW -> enqueue(graph, full)
      ACTION_FULL_SYNC -> enqueue(graph, full = true)
      ACTION_DROP_MAPPINGS -> {
        val count = withContext(Dispatchers.IO) { graph.syncDatabase.mappings.count() }
        graph.syncEngine.dropMappings()
        "$count mappings dropped; the next run adopts the marked rows (expect added=0)"
      }
      else -> "unknown action"
    }

  private suspend fun enqueue(graph: AppGraph, full: Boolean): String {
    val r = graph.workScheduler.runNowAndWait(full = full, trigger = SyncTrigger.DEBUG)
    r.recentlySyncedAgoMillis?.let {
      return "${SyncScheduler.NOW_WORK} not enqueued: synced ${it / 1000} s ago (no run needed)"
    }
    return "${SyncScheduler.NOW_WORK} enqueued full=${r.full} afterRunning=${r.afterRunning} " +
      "coalesced=${r.coalesced}; wait for 'run finished'"
  }

  companion object {
    const val ACTION_SYNC_NOW = "de.tergelius.ttsync.debug.SYNC_NOW"
    const val ACTION_FULL_SYNC = "de.tergelius.ttsync.debug.FULL_SYNC"
    const val ACTION_DROP_MAPPINGS = "de.tergelius.ttsync.debug.DROP_MAPPINGS"

    /** Boolean extra for [ACTION_SYNC_NOW]: run FULL. */
    const val EXTRA_FULL = "full"

    /** Below the 60 s a background broadcast may take. */
    private val TIMEOUT = 50.seconds
  }
}
