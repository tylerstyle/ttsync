package de.tergelius.ttsync.timetree

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Keeps the client polite (DESIGN.md section 3, "Pacing"). One instance per process.
 *
 * Contract: [awaitSlot] suspends until at least [MIN_GAP] has passed since the previous request
 * started, then reserves the slot. Concurrent callers are served one after the other. Must be
 * testable without real time (inject the clock and the delay function).
 */
interface RequestPacer {
  suspend fun awaitSlot()

  companion object {
    /** Minimum time between two requests. */
    val MIN_GAP: Duration = 1.seconds

    /** "Sync now" starts no earlier than this after the previous run finished. */
    val MANUAL_RUN_GAP: Duration = 60.seconds
  }
}

/** Phase 2 implements this. */
class DefaultRequestPacer(
  private val nowMillis: () -> Long = System::currentTimeMillis,
) : RequestPacer {
  override suspend fun awaitSlot(): Unit = TODO("phase 2: RequestPacer (now=${nowMillis()})")
}
