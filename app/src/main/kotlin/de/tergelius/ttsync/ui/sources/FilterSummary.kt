package de.tergelius.ttsync.ui.sources

import de.tergelius.ttsync.mapping.EventFilter

/**
 * The one-line summary of a calendar's filter, e.g. "2 labels · 1 person · 3 words · skips 1 word"
 * (Sources rows). Pure: the caller supplies the localized texts, so the rules are unit-tested and
 * the screen only formats.
 */
object FilterSummary {
  /** The parts of a summary, in display order. */
  enum class Part {
    LABELS,
    PEOPLE,
    WORDS,
    SKIP_WORDS,
  }

  const val SEPARATOR = " · "

  /**
   * The non-empty criteria of [filter] with their counts, after normalization (blank or repeated
   * terms don't count). Empty for no filter or one that restricts nothing (all events).
   */
  fun parts(filter: EventFilter?): List<Pair<Part, Int>> {
    val f = filter?.normalized()?.takeUnless { it.isEmpty } ?: return emptyList()
    return listOf(
        Part.LABELS to f.labelIds.size,
        Part.PEOPLE to f.attendeeIds.size,
        Part.WORDS to f.includeTerms.size,
        Part.SKIP_WORDS to f.excludeTerms.size,
      )
      .filter { (_, count) -> count > 0 }
  }

  /** [all] for no filter, else the [parts] as [part] formats them, joined with [SEPARATOR]. */
  fun text(filter: EventFilter?, all: String, part: (Part, Int) -> String): String =
    parts(filter).takeIf { it.isNotEmpty() }?.joinToString(SEPARATOR) { (p, n) -> part(p, n) }
      ?: all
}
