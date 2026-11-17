package de.tergelius.ttsync.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import de.tergelius.ttsync.AppGraph
import de.tergelius.ttsync.appGraph
import de.tergelius.ttsync.sync.SyncRequest
import de.tergelius.ttsync.sync.SyncScheduler
import de.tergelius.ttsync.sync.SyncTrigger
import de.tergelius.ttsync.timetree.OkHttpTimeTreeClient
import de.tergelius.ttsync.timetree.ProbeList
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
 * - [ACTION_PROBE_EVENTS]: reads raw pages of one calendar's event list with the stored session
 *   ([OkHttpTimeTreeClient.probeEvents]) into `files/debug/raw/<run id>-probe/`. Extras: `--el
 *   calendar <id>` (required), `--es list sync|list` (default `list`: `/calendar/{id}/events`;
 *   `sync`: the feed syncs read), `--el since <ms>` (default: the list's top), `--ei pages <n>`
 *   (default [PROBE_PAGES], at most [PROBE_MAX_PAGES]). Not during a sync. It needs only a signed-in
 *   session, not a finished setup, and writes nothing to any calendar.
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
            withTimeout(TIMEOUT) { handle(graph, action, full, intent) }
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

  private suspend fun handle(graph: AppGraph, action: String?, full: Boolean, intent: Intent): String =
    when (action) {
      ACTION_SYNC_NOW -> enqueue(graph, full)
      ACTION_FULL_SYNC -> enqueue(graph, full = true)
      ACTION_DROP_MAPPINGS -> {
        val count = withContext(Dispatchers.IO) { graph.syncDatabase.mappings.count() }
        graph.syncEngine.dropMappings()
        "$count mappings dropped; the next run adopts the marked rows (expect added=0)"
      }
      ACTION_PROBE_EVENTS -> probe(graph, intent)
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

  private suspend fun probe(graph: AppGraph, intent: Intent): String {
    val client = graph.timeTreeClient as? OkHttpTimeTreeClient ?: return "no HTTP client"
    val calendarId = intent.getLongExtra(EXTRA_CALENDAR, -1L)
    if (calendarId <= 0) return "missing --el $EXTRA_CALENDAR <id>"
    val list =
      when (val name = intent.getStringExtra(EXTRA_LIST) ?: ProbeList.EVENTS.dumpName) {
        ProbeList.EVENTS.dumpName -> ProbeList.EVENTS
        ProbeList.SYNC.dumpName -> ProbeList.SYNC
        else -> return "unknown --es $EXTRA_LIST $name (list or sync)"
      }
    val since = if (intent.hasExtra(EXTRA_SINCE)) intent.getLongExtra(EXTRA_SINCE, 0L) else null
    val maxPages = intent.getIntExtra(EXTRA_PAGES, PROBE_PAGES).coerceIn(1, PROBE_MAX_PAGES)
    val runId = SyncRequest.newRunId(System.currentTimeMillis()) + "-probe"
    val pages = client.probeEvents(calendarId, list, since, maxPages, runId)
    val summary =
      pages.withIndex().joinToString("; ") { (i, p) ->
        "page ${i + 1}: events=${p.events} chunk=${p.chunk} since=${p.since} keys=${p.keys}"
      }
    return "${list.path} calendar=$calendarId since=${since ?: "top"} pages=${pages.size}: $summary; " +
      "dump files/debug/raw/$runId/"
  }

  companion object {
    const val ACTION_SYNC_NOW = "de.tergelius.ttsync.debug.SYNC_NOW"
    const val ACTION_FULL_SYNC = "de.tergelius.ttsync.debug.FULL_SYNC"
    const val ACTION_DROP_MAPPINGS = "de.tergelius.ttsync.debug.DROP_MAPPINGS"
    const val ACTION_PROBE_EVENTS = "de.tergelius.ttsync.debug.PROBE_EVENTS"

    /** Boolean extra for [ACTION_SYNC_NOW]: run FULL. */
    const val EXTRA_FULL = "full"

    /** Extras for [ACTION_PROBE_EVENTS]. */
    const val EXTRA_CALENDAR = "calendar"
    const val EXTRA_LIST = "list"
    const val EXTRA_SINCE = "since"
    const val EXTRA_PAGES = "pages"

    /** Pages one probe reads by default and at most; the pacer spaces them 1 s apart. */
    private const val PROBE_PAGES = 10
    private const val PROBE_MAX_PAGES = 40

    /** Below the 60 s a background broadcast may take. */
    private val TIMEOUT = 50.seconds
  }
}
