package de.tergelius.ttsync.calendar

import de.tergelius.ttsync.mapping.EventSpec
import de.tergelius.ttsync.mapping.Marker
import de.tergelius.ttsync.store.JdbcSqlConnection
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayRulesTest {
  private val target = 15L
  private val uuid = "ab_cd-1234"

  private fun row(description: String?, calendarId: Long = target, deleted: Boolean = false) =
    ObservedRow(eventId = 1, calendarId = calendarId, deleted = deleted, description = description)

  @Test
  fun onlyAnExactMarkerInTheTargetIsOurs() {
    val ours = "Note\nLabel: x\n[ttsync:$uuid]"
    assertEquals(RowCheck.OURS, GatewayRules.check(row(ours), target, uuid))
    assertEquals(RowCheck.OURS, GatewayRules.check(row("[ttsync:$uuid]"), target, uuid))
    assertEquals(RowCheck.OURS, GatewayRules.check(row("a\r\n[ttsync:$uuid]"), target, uuid))
    assertEquals(RowCheck.MISSING, GatewayRules.check(null, target, uuid))
    assertEquals(RowCheck.OTHER_CALENDAR, GatewayRules.check(row(ours, calendarId = 16), target, uuid))
    assertEquals(RowCheck.DELETED, GatewayRules.check(row(ours, deleted = true), target, uuid))
    for (foreign in
      listOf(
        null,
        "",
        "Dentist",
        "[ttsync:$uuid] then text",
        "[ttsync:$uuid]\nmore",
        "[ttsync:$uuid] ",
        "x[ttsync:$uuid]",
        "[TTSYNC:$uuid]",
        "[ttsync:${uuid.uppercase()}]",
        "[ttsync:other]",
        "[ttsync:${uuid}x]",
      )) {
      assertEquals(foreign, RowCheck.MARKER_MISMATCH, GatewayRules.check(row(foreign), target, uuid))
    }
  }

  @Test
  fun likePatternEscapesWildcards() {
    assertEquals("%[ttsync:ab\\_cd-1234]", GatewayRules.likeEndsWith(uuid))
    assertEquals("a\\%b\\_c\\\\d", GatewayRules.escapeLike("a%b_c\\d"))
    assertEquals(listOf("7", "15", "%[ttsync:ab\\_cd-1234]"), GatewayRules.preconditionArgs(7, 15, uuid))
  }

  /** Runs the R2 precondition on real SQLite: right syntax, and it matches only the expected row. */
  @Test
  fun preconditionSelectsOnlyTheExpectedRowOnSqlite() {
    JdbcSqlConnection.inMemory().use { sql ->
      sql.execute("CREATE TABLE view_events (_id INTEGER PRIMARY KEY, calendar_id INTEGER, deleted INTEGER, description TEXT)")
      val rows =
        listOf(
          Triple(1L, target, "Note\n[ttsync:$uuid]"), // ours
          Triple(2L, target, "Note\n[ttsync:abXcd-1234]"), // differs only where the uuid has '_'
          Triple(3L, 16L, "[ttsync:$uuid]"), // other calendar
          Triple(4L, target, "[ttsync:$uuid] trailing"),
          Triple(5L, target, null),
        )
      for ((id, cal, desc) in rows) {
        sql.execute(
          "INSERT INTO view_events (_id, calendar_id, deleted, description) VALUES (?, ?, 0, ?)",
          listOf(id, cal, desc),
        )
      }
      sql.execute("INSERT INTO view_events VALUES (6, ?, 1, ?)", listOf(target, "[ttsync:$uuid]"))
      fun count(id: Long): Long =
        sql.query(
            "SELECT COUNT(*) FROM view_events WHERE ${GatewayRules.PRECONDITION}",
            GatewayRules.preconditionArgs(id, target, uuid),
          ) {
            it.long(0)
          }
          .single()
      assertEquals(1L, count(1))
      for (id in 2L..6L) assertEquals("row $id", 0L, count(id))
    }
  }

  @Test
  fun valuesHoldExactlyTheManagedColumnsWithExplicitNulls() {
    val single = spec(uuid)
    val update = GatewayRules.updateValues(single)
    assertEquals(EventSpec.MANAGED_COLUMNS.toSet(), update.keys)
    for (column in listOf(EventSpec.COL_DURATION, EventSpec.COL_RRULE, EventSpec.COL_RDATE, EventSpec.COL_EXDATE)) {
      assertTrue(column, update.containsKey(column))
      assertNull(column, update[column])
    }
    assertEquals(single.dtStart, update[EventSpec.COL_DTSTART])
    assertEquals(single.dtEnd, update[EventSpec.COL_DTEND])
    assertEquals(0, update[EventSpec.COL_ALL_DAY])

    val recurring = single.copy(dtEnd = null, duration = "P3600S", rrule = "FREQ=WEEKLY")
    val values = GatewayRules.updateValues(recurring)
    assertTrue(values.containsKey(EventSpec.COL_DTEND))
    assertNull(values[EventSpec.COL_DTEND])

    val insert = GatewayRules.insertValues(single, TargetCalendar(target, "a@example.org", "com.google"))
    assertEquals(update + (GatewayRules.COL_CALENDAR_ID to target), insert)
    assertTrue(EventSpec.MANAGED_COLUMNS.none { it in GatewayRules.FORBIDDEN_COLUMNS })
  }

  @Test
  fun fakeGatewayRefusesForeignRowsAndRollsBackOnAFailedPrecondition() = runTest {
    val gateway = FakeCalendarGateway()
    val info = CalendarInfo(target, "personal", "a@example.org", "com.google", 700, true, true, null)
    gateway.addCalendar(info)
    val t = TargetCalendar.of(info)
    val foreign = gateway.addRow(target, "Dentist", "Bring [ttsync:$uuid] papers")
    val ours = gateway.addRow(target, "copy", Marker.append("n", uuid))
    val before = gateway.rows.toMap()

    val refused = gateway.apply(t, listOf(CalendarOp.Update(foreign, spec(uuid)), CalendarOp.Delete(foreign, uuid)))
    assertEquals(
      BatchResult.Applied(List(2) { OpResult.Rejected(RowCheck.MARKER_MISMATCH) }),
      refused,
    )
    assertEquals(before, gateway.rows.toMap())

    // The user edits our row between the pre-read and the batch: nothing of the batch is written.
    gateway.betweenReadAndApply = { gateway.userEdit(ours, "mine now") }
    val aborted = gateway.apply(t, listOf(CalendarOp.Insert(spec("new-1")), CalendarOp.Update(ours, spec(uuid))))
    assertEquals(BatchResult.Aborted, aborted)
    assertEquals(2, gateway.rows.size)

    gateway.betweenReadAndApply = null
    gateway.calendars[target] = info.copy(accessLevel = 200)
    assertThrows(TargetUnavailableException::class.java) {
      kotlinx.coroutines.runBlocking { gateway.apply(t, listOf(CalendarOp.Insert(spec("new-2")))) }
    }
  }

  companion object {
    fun spec(uuid: String, title: String = "t", start: Long = 1_794_481_200_000) =
      EventSpec(
        uuid = uuid,
        title = title,
        location = null,
        description = Marker.append("note", uuid),
        dtStart = start,
        dtEnd = start + 3_600_000,
        duration = null,
        eventTimezone = "Europe/Berlin",
        eventEndTimezone = null,
        allDay = false,
        rrule = null,
        rdate = null,
        exdate = null,
      )
  }
}
