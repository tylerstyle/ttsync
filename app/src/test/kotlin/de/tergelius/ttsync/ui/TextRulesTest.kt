package de.tergelius.ttsync.ui

import de.tergelius.ttsync.ui.common.relativeInText
import de.tergelius.ttsync.ui.common.wrapBetweenItems
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Layout findings L1 and L4 of device run 5. */
class TextRulesTest {
  private val nbsp = ' '

  /** Where a line may break: after an ordinary space (no-break spaces glue). */
  private fun lines(text: String) = text.split(' ')

  @Test
  fun countsBreakOnlyBeforeASeparator() {
    val text = wrapBetweenItems("Added 0 · Updated 0 · Deleted 0 · Skipped 1 · Filtered 8")
    assertEquals(
      listOf("Added${nbsp}0", "·${nbsp}Updated${nbsp}0", "·${nbsp}Deleted${nbsp}0") +
        listOf("·${nbsp}Skipped${nbsp}1", "·${nbsp}Filtered${nbsp}8"),
      lines(text),
    )
    // No piece ends with the separator, so no line can.
    assertFalse(lines(text).any { it.endsWith("·") })
    // It still reads the same.
    assertEquals(
      "Added 0 · Updated 0 · Deleted 0 · Skipped 1 · Filtered 8",
      text.replace(nbsp, ' '),
    )
  }

  @Test
  fun germanAndTheSourcesSummaryWrapTheSameWay() {
    val de = wrapBetweenItems("Hinzugefügt 0 · Übersprungen 1 · Gefiltert 8")
    assertEquals(
      listOf("Hinzugefügt${nbsp}0", "·${nbsp}Übersprungen${nbsp}1", "·${nbsp}Gefiltert${nbsp}8"),
      lines(de),
    )
    val events = wrapBetweenItems("Events: 2 labels · skips 1 word")
    assertEquals(
      listOf("Events:${nbsp}2${nbsp}labels", "·${nbsp}skips${nbsp}1${nbsp}word"),
      lines(events),
    )
  }

  @Test
  fun aTextWithoutSeparatorIsUnchanged() {
    assertEquals("Events: All", wrapBetweenItems("Events: All"))
    assertEquals("", wrapBetweenItems(""))
  }

  @Test
  fun relativeTimesStartLowerCaseInsideASentence() {
    val de = Locale.GERMAN
    assertEquals("vor 1 Minute", relativeInText("Vor 1 Minute", isRelative = true, de))
    assertEquals("in 40 Minuten", relativeInText("In 40 Minuten", isRelative = true, de))
    assertEquals("gestern", relativeInText("Gestern", isRelative = true, de))
    // English: "5 minutes ago" stays as it is; "In 40 minutes" reads "(in 40 minutes)".
    val en = Locale.ENGLISH
    assertEquals("5 minutes ago", relativeInText("5 minutes ago", isRelative = true, en))
    assertEquals("in 40 minutes", relativeInText("In 40 minutes", isRelative = true, en))
    assertEquals("0 minutes ago", relativeInText("0 minutes ago", isRelative = true, en))
  }

  @Test
  fun absoluteDatesKeepTheirCapitals() {
    assertEquals("Nov 5, 2026", relativeInText("Nov 5, 2026", isRelative = false, Locale.ENGLISH))
    assertEquals("5. Nov. 2026", relativeInText("5. Nov. 2026", isRelative = false, Locale.GERMAN))
  }
}
