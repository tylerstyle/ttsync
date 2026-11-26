package de.tergelius.ttsync.sync

import de.tergelius.ttsync.store.CalendarState

/**
 * Which calendars get the "No access to a TimeTree calendar" notification after a run, once per
 * loss of access (issue #1). Pure, so it is tested without the worker.
 *
 * The worker used to compare with the previous run's report only. A run that fails early (no
 * network, say) stores a report without calendars, so the run after it took a calendar that was
 * still paused for newly paused and notified again. Now the calendars already notified are stored
 * ([de.tergelius.ttsync.settings.SyncStatus.accessLostNotified]):
 * - a calendar the report shows paused and that isn't in that set is notified and added;
 * - a calendar leaves the set only when a report shows it active again (or it is no longer
 *   selected; deselecting drops it at once, `StatusRepository.forgetCalendars`). A report that
 *   lists no calendars, or only some (a run that failed halfway), changes
 *   nothing for the others.
 */
object AccessLostNotices {
  data class Decision(
    /** Calendars to notify now, in report order. */
    val notify: List<Long>,
    /** The calendars notified so far, to store. */
    val notified: Set<Long>,
  )

  /**
   * @param notified the stored set, or null if none was stored yet (before 0.2.2): then the
   *   previous report's paused calendars count as notified, as the old rule had it
   * @param selected the calendars currently selected in Sources
   */
  fun decide(
    report: SyncReport,
    notified: Set<Long>?,
    previousReport: SyncReport?,
    selected: Set<Long>,
  ): Decision {
    val known = notified ?: previousReport?.withState(CalendarState.PAUSED).orEmpty().toSet()
    val notify = report.withState(CalendarState.PAUSED).filter { it !in known }.distinct()
    val active = report.withState(CalendarState.ACTIVE).toSet()
    val notified = known - active + notify
    // No selection at all means the settings could not be read (a run needs a calendar), not that
    // everything was deselected: then nothing is dropped (review 2026-11, B3).
    return Decision(notify, if (selected.isEmpty()) notified else notified.filterTo(LinkedHashSet()) { it in selected })
  }

  private fun SyncReport.withState(state: CalendarState): List<Long> =
    calendars.filter { it.state == state }.map { it.ttCalId }
}
