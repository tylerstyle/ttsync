package de.tergelius.ttsync.mapping

import de.tergelius.ttsync.mapping.MappingFixtures.events
import de.tergelius.ttsync.mapping.MappingFixtures.map
import de.tergelius.ttsync.timetree.TtLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Bug B3: unnamed labels are called by their colour, like TimeTree does. */
class LabelNamesTest {
  @Test
  fun tableHasTimeTreesTwentyFiveColours() {
    // From TimeTree 15.35.2's string resources (spec of 0.2.0).
    val expected =
      mapOf(
        0x2ECC87 to "Emerald green",
        0x3DC2C8 to "Modern cyan",
        0x47B2F7 to "Deep sky blue",
        0x948078 to "Pastel brown",
        0x212121 to "Midnight black",
        0xE73B3B to "Apple red",
        0xF35F8C to "French rose",
        0xFB7F77 to "Coral pink",
        0xFDC02D to "Bright orange",
        0xB38BDC to "Soft violet",
        0x00ACC1 to "Cyan",
        0x039BE5 to "Light Blue",
        0x1E88E5 to "Blue",
        0x3949AB to "Indigo",
        0x43A047 to "Green",
        0x5E35B1 to "Deep Purple",
        0x757575 to "Dark Gray",
        0x7CB342 to "Light Green",
        0x8E24AA to "Purple",
        0xC0CA33 to "Lime",
        0xD81B60 to "Pink",
        0xF4511E to "Deep Orange",
        0xFB8C00 to "Orange",
        0xFDD835 to "Yellow",
        0xFFB300 to "Amber",
      )
    assertEquals(expected, LabelColor.entries.associate { it.rgb to it.english })
    assertEquals(25, LabelColor.entries.size)
  }

  @Test
  fun colourLookupIgnoresAlphaAndUnknownColours() {
    assertEquals(LabelColor.APPLE_RED, LabelColor.of(0xE73B3B))
    assertEquals(LabelColor.APPLE_RED, LabelColor.of(0xFFE73B3BL))
    assertNull(LabelColor.of(0x123456))
    assertNull(LabelColor.of(null))
  }

  @Test
  fun copyLineUsesTheNameElseTheEnglishColourName() {
    assertEquals("Work", LabelNames.forCopy(TtLabel(3, "Work", color = 0x47B2F7)))
    assertEquals("Deep sky blue", LabelNames.forCopy(TtLabel(3, "", color = 0x47B2F7)))
    assertEquals("Deep sky blue", LabelNames.forCopy(TtLabel(3, "  ", color = 0x47B2F7)))
    assertEquals("Deep sky blue", LabelNames.forCopy(TtLabel(3, null, color = 0x47B2F7)))
    // Recoloured: the current colour counts, not the default one.
    assertEquals("Indigo", LabelNames.forCopy(TtLabel(3, "", color = 0x3949AB, defaultColor = 0x47B2F7)))
    // Recoloured to a colour without a name: no line, never the default colour's name.
    assertNull(LabelNames.forCopy(TtLabel(3, "", color = 0x123456, defaultColor = 0x47B2F7)))
    // No colour at all (cached by 0.1.x): no line, as in 0.1.x.
    assertNull(LabelNames.forCopy(TtLabel(3, "")))
  }

  @Test
  fun uiNamesAreLocalizedByTheCaller() {
    val german = mapOf(LabelColor.BLUE to "Blau")
    fun de(label: TtLabel) = LabelNames.display(label) { german[it] ?: it.english }
    assertEquals("Blau", de(TtLabel(1, "", color = 0x1E88E5)))
    assertEquals("Work", de(TtLabel(1, "Work", color = 0x1E88E5)))
    assertNull(de(TtLabel(1, "", color = 0x123456))) // the UI shows "Label 1"
  }

  @Test
  fun mapperWritesNoLineForAnUnnamedLabelWithoutAKnownColour() {
    val event = events("synthetic/content.json").single { it.uuid == "m16-multiline-label" }
    val labels = mapOf(4L to TtLabel(4, "", color = 0x123456))
    assertEquals("[ttsync:m16-multiline-label]", map(listOf(event), labels).spec(event.uuid).description)
  }
}
