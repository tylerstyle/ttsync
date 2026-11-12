package de.tergelius.ttsync.mapping

import de.tergelius.ttsync.timetree.TtEvent

/**
 * Edited and deleted occurrences of a series (DESIGN.md section 4, "Edited occurrences"), with the
 * rule the real API dumps of 2026-11-12 settled:
 * - TimeTree itself adds an EXDATE to the parent for every moved **and** every deleted occurrence
 *   (`"EXDATE:20261123T170000Z"`, UTC form). A deleted plain occurrence leaves no event at all,
 *   only that EXDATE.
 * - A moved occurrence is a separate event with its own uuid, `recurring_uuid` and `parent_id` set
 *   to the parent's uuid, `start_at` at the new time and empty `recurrences`. No field holds the
 *   original instance start.
 *
 * So the parent's own (native) EXDATEs are used as they are (rule 1), and nothing is ever added:
 * without an original-start field there is no exact match (rule 2), and there is no guessing
 * (rule 3). The mapper writes each live override as a standalone single event.
 *
 * What remains is the count for `SyncReport.unmatchedOverrides`. Every override or tombstone needs
 * one EXDATE on its parent for its original instance, so a parent with fewer EXDATEs than
 * overrides and tombstones still shows at least the difference as stale original instances. That
 * difference is counted. It is a lower bound: EXDATEs of deleted occurrences can hide a missing one.
 *
 * If a future dump shows an original-start field, add it to `TtEvent` and add an EXDATE for each
 * override whose original instance the parent does not already exclude (DESIGN.md rule 2).
 *
 * Internal helper of [DefaultEventMapper]. Pure.
 */
internal class OverrideResolver {
  /**
   * @param nativeExdates the parent's own normalized EXDATE values
   * @param overrides live overrides of the parent
   * @param tombstones deactivated overrides of the parent
   */
  fun resolve(
    nativeExdates: Collection<String>,
    overrides: List<TtEvent>,
    tombstones: List<TtEvent>,
  ): OverrideResolution {
    val needed = overrides.size + tombstones.size
    return OverrideResolution(unmatched = maxOf(0, needed - nativeExdates.toSet().size))
  }
}

internal data class OverrideResolution(
  /** Overrides and tombstones whose original instance is not excluded by the parent. */
  val unmatched: Int
)
