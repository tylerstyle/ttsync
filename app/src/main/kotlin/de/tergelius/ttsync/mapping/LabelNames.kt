package de.tergelius.ttsync.mapping

import de.tergelius.ttsync.timetree.TtLabel

/**
 * The colours TimeTree gives a name (from TimeTree 15.35.2's string resources, which are keyed by
 * colour, not by label id). TimeTree shows an unnamed label by the name of its colour: the ten
 * default colours of a new calendar first, then the colours a label can be changed to.
 *
 * [english] is the name the copies use (see [LabelNames.forCopy]). The UI shows a localized name
 * instead: map each constant to a string resource (the constant names are stable keys), so a new
 * language needs no change here.
 */
enum class LabelColor(val rgb: Int, val english: String) {
  EMERALD_GREEN(0x2ECC87, "Emerald green"),
  MODERN_CYAN(0x3DC2C8, "Modern cyan"),
  DEEP_SKY_BLUE(0x47B2F7, "Deep sky blue"),
  PASTEL_BROWN(0x948078, "Pastel brown"),
  MIDNIGHT_BLACK(0x212121, "Midnight black"),
  APPLE_RED(0xE73B3B, "Apple red"),
  FRENCH_ROSE(0xF35F8C, "French rose"),
  CORAL_PINK(0xFB7F77, "Coral pink"),
  BRIGHT_ORANGE(0xFDC02D, "Bright orange"),
  SOFT_VIOLET(0xB38BDC, "Soft violet"),
  CYAN(0x00ACC1, "Cyan"),
  LIGHT_BLUE(0x039BE5, "Light Blue"),
  BLUE(0x1E88E5, "Blue"),
  INDIGO(0x3949AB, "Indigo"),
  GREEN(0x43A047, "Green"),
  DEEP_PURPLE(0x5E35B1, "Deep Purple"),
  DARK_GRAY(0x757575, "Dark Gray"),
  LIGHT_GREEN(0x7CB342, "Light Green"),
  PURPLE(0x8E24AA, "Purple"),
  LIME(0xC0CA33, "Lime"),
  PINK(0xD81B60, "Pink"),
  DEEP_ORANGE(0xF4511E, "Deep Orange"),
  ORANGE(0xFB8C00, "Orange"),
  YELLOW(0xFDD835, "Yellow"),
  AMBER(0xFFB300, "Amber");

  companion object {
    private val BY_RGB = entries.associateBy { it.rgb }

    /** The named colour for a label colour (RGB int; an alpha byte is ignored), or null. */
    fun of(color: Long?): LabelColor? = color?.let { BY_RGB[(it and 0xFFFFFF).toInt()] }
  }
}

/**
 * How a label is called (bug B3: TimeTree's default labels have empty names, so 0.1.x wrote no
 * `Label:` line for them although TimeTree shows them by their colour's name).
 */
object LabelNames {
  /**
   * The text of a copy's `Label:` line: the label's name if it is not blank, else the **English**
   * name of its colour, else null (no line). English on purpose: the copy and its hash must not
   * depend on the phone's language, and TimeTree itself shows the default names in English on
   * German phones. A renamed label shows its new name once the copy is rewritten (next FULL run,
   * since a rename does not change the events).
   */
  fun forCopy(label: TtLabel): String? = display(label) { it.english }

  /**
   * What the UI shows for a label: its name if not blank, else [colorName] of its colour, else
   * null; then the caller shows "Label <id>".
   */
  fun display(label: TtLabel, colorName: (LabelColor) -> String): String? =
    label.name?.takeIf { it.isNotBlank() } ?: colorOf(label)?.let(colorName)

  /** The label's named colour: its current [TtLabel.color], else its default colour. */
  fun colorOf(label: TtLabel): LabelColor? = LabelColor.of(label.color ?: label.defaultColor)
}
