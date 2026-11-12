package de.tergelius.ttsync.mapping

import de.tergelius.ttsync.timetree.TtEvent

/**
 * Decides which EXDATEs a series needs because of its edited and deleted occurrences (DESIGN.md
 * section 4, "Edited occurrences"): the parent's native EXDATEs first, then exact matches on the
 * original instance start, never a nearest-instance guess. Unmatched cases are counted.
 *
 * Internal helper of [DefaultEventMapper]; phase 3 owns it and may change its signature once the
 * real fixtures show which field holds the original instance start.
 */
internal class OverrideResolver {
  fun resolve(
    parent: TtEvent,
    nativeExdates: Set<String>,
    overrides: List<TtEvent>,
    tombstones: List<TtEvent>,
  ): OverrideResolution =
    TODO(
      "phase 3: OverrideResolver ($parent, ${nativeExdates.size} exdates, " +
        "${overrides.size} overrides, ${tombstones.size} tombstones)"
    )
}

internal data class OverrideResolution(
  /** EXDATE values to add to the parent, in the same format as [NormalizedRecurrence.exdate]. */
  val addedExdates: Set<String>,
  val unmatched: Int,
)
