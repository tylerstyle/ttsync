package de.tergelius.ttsync.ui.sources

import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import de.tergelius.ttsync.R
import de.tergelius.ttsync.mapping.EventFilter
import de.tergelius.ttsync.mapping.LabelColor
import de.tergelius.ttsync.mapping.LabelNames
import de.tergelius.ttsync.settings.SourceCalendar
import de.tergelius.ttsync.timetree.TtLabel

/*
 * Localized texts of the filters (0.2.0): label and colour names, the summary line and calendar
 * names marked as filtered. The rules live in the pure LabelNames and FilterSummary.
 */

/**
 * The localized name of a named label colour. Each constant has a string `label_color_<constant>`
 * in English and German (checked by a unit test); the copies use [LabelColor.english] instead.
 */
@StringRes
fun LabelColor.nameRes(): Int =
  when (this) {
    LabelColor.EMERALD_GREEN -> R.string.label_color_emerald_green
    LabelColor.MODERN_CYAN -> R.string.label_color_modern_cyan
    LabelColor.DEEP_SKY_BLUE -> R.string.label_color_deep_sky_blue
    LabelColor.PASTEL_BROWN -> R.string.label_color_pastel_brown
    LabelColor.MIDNIGHT_BLACK -> R.string.label_color_midnight_black
    LabelColor.APPLE_RED -> R.string.label_color_apple_red
    LabelColor.FRENCH_ROSE -> R.string.label_color_french_rose
    LabelColor.CORAL_PINK -> R.string.label_color_coral_pink
    LabelColor.BRIGHT_ORANGE -> R.string.label_color_bright_orange
    LabelColor.SOFT_VIOLET -> R.string.label_color_soft_violet
    LabelColor.CYAN -> R.string.label_color_cyan
    LabelColor.LIGHT_BLUE -> R.string.label_color_light_blue
    LabelColor.BLUE -> R.string.label_color_blue
    LabelColor.INDIGO -> R.string.label_color_indigo
    LabelColor.GREEN -> R.string.label_color_green
    LabelColor.DEEP_PURPLE -> R.string.label_color_deep_purple
    LabelColor.DARK_GRAY -> R.string.label_color_dark_gray
    LabelColor.LIGHT_GREEN -> R.string.label_color_light_green
    LabelColor.PURPLE -> R.string.label_color_purple
    LabelColor.LIME -> R.string.label_color_lime
    LabelColor.PINK -> R.string.label_color_pink
    LabelColor.DEEP_ORANGE -> R.string.label_color_deep_orange
    LabelColor.ORANGE -> R.string.label_color_orange
    LabelColor.YELLOW -> R.string.label_color_yellow
    LabelColor.AMBER -> R.string.label_color_amber
  }

/**
 * What the UI calls a label (spec, B3): its current name, else the localized name of its colour,
 * else "Label <id>".
 */
fun labelDisplayName(label: TtLabel, resources: Resources): String =
  LabelNames.display(label) { resources.getString(it.nameRes()) }
    ?: resources.getString(R.string.filter_label_unnamed, label.id)

/** "All", or the filter's summary such as "2 labels · 1 person" ([FilterSummary]). */
fun filterSummaryText(filter: EventFilter?, resources: Resources): String =
  FilterSummary.text(filter, all = resources.getString(R.string.filter_summary_all)) { part, n ->
    resources.getQuantityString(part.pluralRes(), n, n)
  }

@PluralsRes
private fun FilterSummary.Part.pluralRes(): Int =
  when (this) {
    FilterSummary.Part.LABELS -> R.plurals.filter_summary_labels
    FilterSummary.Part.PEOPLE -> R.plurals.filter_summary_people
    FilterSummary.Part.WORDS -> R.plurals.filter_summary_words
    FilterSummary.Part.SKIP_WORDS -> R.plurals.filter_summary_skip_words
  }

/**
 * Selected calendars for a list such as "Family (filtered), Football" (Settings, Status): the name
 * (or "Calendar <id>"), marked when the calendar's filter restricts anything.
 */
fun sourceNames(sources: List<SourceCalendar>, resources: Resources): String =
  sourceNames(
    sources,
    unnamed = { resources.getString(R.string.sources_unnamed, it) },
    filtered = { resources.getString(R.string.sources_filtered_name, it) },
  )

/** [sourceNames] with the texts supplied, for tests. */
fun sourceNames(
  sources: List<SourceCalendar>,
  unnamed: (Long) -> String,
  filtered: (String) -> String,
): String =
  sources.joinToString(", ") { source ->
    val name = source.name.ifBlank { unnamed(source.id) }
    if (source.activeFilter != null) filtered(name) else name
  }
