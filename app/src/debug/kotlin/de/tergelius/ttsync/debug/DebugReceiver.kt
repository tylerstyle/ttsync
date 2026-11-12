package de.tergelius.ttsync.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import de.tergelius.ttsync.AndroidTtLog

/**
 * Debug builds only (DESIGN.md section 8): lets adb trigger runs. Guarded by
 * `android.permission.DUMP` in `src/debug/AndroidManifest.xml`, so only the adb shell can send to
 * it. Phase 5 implements the actions:
 * - [ACTION_SYNC_NOW]: `SyncScheduler.runNow(full = false, trigger = DEBUG)`
 * - [ACTION_FULL_SYNC]: the same with `full = true`
 * - [ACTION_DROP_MAPPINGS]: `SyncEngine.dropMappings()`
 *
 * `adb shell am broadcast -a de.tergelius.ttsync.debug.SYNC_NOW -n de.tergelius.ttsync/.debug.DebugReceiver`
 */
class DebugReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    AndroidTtLog.w("debug action not implemented yet: ${intent.action}")
  }

  companion object {
    const val ACTION_SYNC_NOW = "de.tergelius.ttsync.debug.SYNC_NOW"
    const val ACTION_FULL_SYNC = "de.tergelius.ttsync.debug.FULL_SYNC"
    const val ACTION_DROP_MAPPINGS = "de.tergelius.ttsync.debug.DROP_MAPPINGS"
  }
}
