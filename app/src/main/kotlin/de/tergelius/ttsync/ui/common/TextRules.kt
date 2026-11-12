package de.tergelius.ttsync.ui.common

import java.util.Locale

/*
 * Pure text rules for the screens, unit-tested without Android (layout findings L1 and L4 of
 * device run 5).
 */

private const val NBSP = ' '

/**
 * A list such as "Added 0 · Updated 0 · Skipped 1" that wraps only between its items, with the
 * separator starting the next line, never ending a line: spaces inside an item become no-break
 * spaces, and the separator is glued to the item after it ("Skipped 1 ·⍽Filtered 8" may break only
 * before the "·"). A text without [separator] is returned as it is.
 */
fun wrapBetweenItems(text: String, separator: String = " · "): String {
  val items = text.split(separator)
  if (items.size < 2) return text
  val mark = separator.trim()
  return items.joinToString(" $mark$NBSP") { it.replace(' ', NBSP) }
}

/**
 * A relative time ("Vor 1 Minute", "In 40 minutes") for use inside a sentence or parentheses: its
 * first letter in lower case, in [locale]. [isRelative] is false for an absolute date (Android
 * gives one for times a week or more away), which is kept as it is ("Nov 5, 2026").
 */
fun relativeInText(relative: String, isRelative: Boolean, locale: Locale): String =
  if (isRelative) relative.replaceFirstChar { it.lowercase(locale) } else relative
