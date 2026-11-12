package de.tergelius.ttsync.ui.common

import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import java.util.Locale
import kotlin.math.abs

/** Date and time in the user's locale and 12/24 h setting, e.g. "12 Nov, 14:03". */
@Composable
fun formatDateTime(millis: Long): String =
  DateUtils.formatDateTime(
    LocalContext.current,
    millis,
    DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH,
  )

/** Time only, e.g. "14:03". */
@Composable
fun formatTime(millis: Long): String =
  DateUtils.formatDateTime(LocalContext.current, millis, DateUtils.FORMAT_SHOW_TIME)

/**
 * "5 minutes ago", "in 40 minutes", "vor 1 Minute": for use in parentheses, so the first letter is
 * lower case ([relativeInText]; Android capitalizes it, e.g. "Vor 1 Minute"). A week or more away,
 * Android gives a date instead, which stays as it is.
 */
fun formatRelative(millis: Long, nowMillis: Long, locale: Locale = Locale.getDefault()): String =
  relativeInText(
    DateUtils.getRelativeTimeSpanString(millis, nowMillis, DateUtils.MINUTE_IN_MILLIS).toString(),
    isRelative = abs(nowMillis - millis) < DateUtils.WEEK_IN_MILLIS,
    locale = locale,
  )
