package de.tergelius.ttsync.calendar

import android.content.Context

/** [CalendarGateway] on the real `CalendarContract` provider. Phase 4 implements it. */
class ProviderCalendarGateway(context: Context) : CalendarGateway {
  private val appContext = context.applicationContext

  override fun hasPermission(): Boolean = notYet()

  override suspend fun calendars(): List<CalendarInfo> = notYet()

  override suspend fun calendar(calendarId: Long): CalendarInfo? = notYet()

  override fun googleSyncState(target: TargetCalendar): GoogleSyncState = notYet()

  override suspend fun countForeignEvents(calendarId: Long): Int = notYet()

  override suspend fun findMarkedRows(target: TargetCalendar): List<MarkedRow> = notYet()

  override suspend fun checkRows(
    target: TargetCalendar,
    expected: Map<Long, String>,
  ): Map<Long, RowCheck> = notYet()

  override suspend fun apply(target: TargetCalendar, ops: List<CalendarOp>): BatchResult = notYet()

  override fun requestUpload(target: TargetCalendar): Unit = notYet()

  private fun notYet(): Nothing = TODO("phase 4: ProviderCalendarGateway (${appContext.packageName})")
}
