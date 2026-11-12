package de.tergelius.ttsync.ui

import de.tergelius.ttsync.R
import de.tergelius.ttsync.mapping.LabelColor
import de.tergelius.ttsync.ui.sources.nameRes
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Every label colour has a localized name (spec, B3): `label_color_<constant>` in English and
 * German, and [nameRes] points at it.
 */
class LabelColorStringsTest {
  /** TimeTree's German names (TimeTree 15.35.2), without its "Morden cyan" typo. */
  private val german =
    mapOf(
      LabelColor.EMERALD_GREEN to "Emerald green",
      LabelColor.MODERN_CYAN to "Modern cyan",
      LabelColor.DEEP_SKY_BLUE to "Deep sky blue",
      LabelColor.PASTEL_BROWN to "Pastel brown",
      LabelColor.MIDNIGHT_BLACK to "Midnight black",
      LabelColor.APPLE_RED to "Apple red",
      LabelColor.FRENCH_ROSE to "French rose",
      LabelColor.CORAL_PINK to "Coral pink",
      LabelColor.BRIGHT_ORANGE to "Bright orange",
      LabelColor.SOFT_VIOLET to "Soft violet",
      LabelColor.CYAN to "Cyan",
      LabelColor.LIGHT_BLUE to "Hellblau",
      LabelColor.BLUE to "Blau",
      LabelColor.INDIGO to "Indigo",
      LabelColor.GREEN to "Grün",
      LabelColor.DEEP_PURPLE to "Dunkelviolett",
      LabelColor.DARK_GRAY to "Dunkelgrau",
      LabelColor.LIGHT_GREEN to "Hellgrün",
      LabelColor.PURPLE to "Violett",
      LabelColor.LIME to "Lindgrün",
      LabelColor.PINK to "Rosa",
      LabelColor.DEEP_ORANGE to "Tieforange",
      LabelColor.ORANGE to "Orange",
      LabelColor.YELLOW to "Gelb",
      LabelColor.AMBER to "Bernsteinfarben",
    )

  private fun key(color: LabelColor) = "label_color_${color.name.lowercase()}"

  @Test
  fun everyColourHasAnEnglishNameLikeTheCopies() {
    val strings = strings("values")
    for (color in LabelColor.entries) {
      assertEquals(color.name, color.english, strings[key(color)])
    }
  }

  @Test
  fun everyColourHasTheGermanNameFromTimeTree() {
    val strings = strings("values-de")
    assertEquals(LabelColor.entries.toSet(), german.keys)
    for (color in LabelColor.entries) {
      assertEquals(color.name, german.getValue(color), strings[key(color)])
    }
  }

  @Test
  fun nameResPointsAtTheColoursString() {
    for (color in LabelColor.entries) {
      val id = R.string::class.java.getField(key(color)).getInt(null)
      assertEquals(color.name, id, color.nameRes())
    }
    assertEquals(LabelColor.entries.size, LabelColor.entries.map { it.nameRes() }.toSet().size)
  }

  /** The `<string>` entries of `src/main/res/<dir>/strings.xml`, by name. */
  private fun strings(dir: String): Map<String, String> {
    // Unit tests run in the module directory; also allow the repository root.
    val file =
      listOf("src/main/res/$dir/strings.xml", "app/src/main/res/$dir/strings.xml")
        .map(::File)
        .firstOrNull { it.isFile }
    assertTrue("strings.xml of $dir not found", file != null)
    val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
    val nodes = doc.getElementsByTagName("string")
    return (0 until nodes.length)
      .map { nodes.item(it) as Element }
      .associate { it.getAttribute("name") to it.textContent }
  }
}
