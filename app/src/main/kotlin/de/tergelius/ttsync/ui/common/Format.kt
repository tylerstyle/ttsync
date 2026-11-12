package de.tergelius.ttsync.ui.common

import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

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

/** "5 minutes ago", "in 40 minutes". */
fun formatRelative(millis: Long, nowMillis: Long): String =
  DateUtils.getRelativeTimeSpanString(millis, nowMillis, DateUtils.MINUTE_IN_MILLIS).toString()
