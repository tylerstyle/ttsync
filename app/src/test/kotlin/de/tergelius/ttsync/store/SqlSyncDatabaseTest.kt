package de.tergelius.ttsync.store

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The real schema and store SQL, run on SQLite through JDBC. */
class SqlSyncDatabaseTest {
  private val sql = JdbcSqlConnection.inMemory().also { SyncSchema.create(it) }
  private val db = SqlSyncDatabase(sql)

  @After fun close() = sql.close()

  private fun tables(): Set<String> =
    sql.query("SELECT name FROM sqlite_master WHERE type = 'table'") { it.string(0) }.toSet()

  @Test
  fun schemaHasTheFourTables() {
    assertEquals(SyncSchema.TABLES.toSet(), tables())
    assertEquals(SyncSchema.VERSION, SyncDb.VERSION)
  }

  @Test
  fun snapshotsRoundTripAndReplace() {
    val a = Snapshot("uuid-a", 7, 100, false, """{"title":"Grüße ✓"}""")
    val b = Snapshot("uuid-b", 7, 101, true, "{}")
    val c = Snapshot("uuid-c", 8, 102, false, "{}")
    db.snapshots.upsert(listOf(a, b, c))
    assertEquals(a, db.snapshots.get("uuid-a"))
    assertEquals(listOf(a, b), db.snapshots.forCalendar(7))

    val a2 = a.copy(updatedAt = 200, json = "{}")
    db.snapshots.upsert(listOf(a2))
    assertEquals(a2, db.snapshots.get("uuid-a"))

    db.snapshots.delete(listOf("uuid-b", "unknown"))
    assertEquals(listOf(a2), db.snapshots.forCalendar(7))
    db.snapshots.deleteCalendar(7)
    assertTrue(db.snapshots.forCalendar(7).isEmpty())
    assertEquals(listOf(c), db.snapshots.forCalendar(8))
    assertNull(db.snapshots.get("uuid-a"))
  }

  @Test
  fun mappingsRoundTripIncludingAdoptedAndOpenEnded() {
    val open = Mapping("u1", 7, 15, 1001, Mapping.ADOPTED_HASH, Long.MAX_VALUE, writtenAt = 1234)
    val single = Mapping("u2", 7, 15, 1002, "ab".repeat(32), 1_794_481_200_000)
    val other = Mapping("u3", 8, 15, 1003, "cd".repeat(32), 0)
    db.mappings.upsert(listOf(open, single, other))
    assertEquals(open, db.mappings.get("u1"))
    assertEquals(3, db.mappings.count())
    assertEquals(listOf(open, single), db.mappings.forCalendar(7))
    assertEquals(listOf(open, single, other), db.mappings.all())

    db.mappings.upsert(listOf(single.copy(eventId = 2002)))
    assertEquals(2002, db.mappings.get("u2")!!.eventId)
    assertEquals(3, db.mappings.count())

    db.mappings.delete(listOf("u1"))
    assertNull(db.mappings.get("u1"))
    db.mappings.deleteCalendar(8)
    assertEquals(listOf("u2"), db.mappings.all().map { it.uuid })
    db.mappings.clear()
    assertEquals(0, db.mappings.count())
  }

  @Test
  fun cursorsKeepNullsAndState() {
    val fresh = SyncCursor(7, null, null, CalendarState.ACTIVE, null)
    val paused =
      SyncCursor(8, 1234, 5678, CalendarState.PAUSED, """[{"id":1,"name":"x"}]""", lastFullPages = 4)
    db.cursors.put(fresh)
    db.cursors.put(paused)
    assertEquals(fresh, db.cursors.get(7))
    assertEquals(listOf(fresh, paused), db.cursors.all())
    db.cursors.put(fresh.copy(highWater = 9))
    assertEquals(9L, db.cursors.get(7)!!.highWater)
    db.cursors.delete(7)
    assertNull(db.cursors.get(7))
  }

  @Test
  fun unknownStoredStateReadsAsPaused() {
    sql.execute(
      "INSERT INTO cursor (tt_cal_id, high_water, last_full_at, state, labels_json) VALUES (?, ?, ?, ?, ?)",
      listOf(9L, null, null, "SOMETHING_NEW", null),
    )
    assertEquals(CalendarState.PAUSED, db.cursors.get(9)!!.state)
  }

  @Test
  fun metaPutGetRemove() {
    assertNull(db.meta.get(MetaKeys.RUN_IN_PROGRESS))
    db.meta.put(MetaKeys.RUN_IN_PROGRESS, "run-1")
    db.meta.put(MetaKeys.RUN_IN_PROGRESS, "run-2")
    assertEquals("run-2", db.meta.get(MetaKeys.RUN_IN_PROGRESS))
    db.meta.put(MetaKeys.RUN_IN_PROGRESS, null)
    assertNull(db.meta.get(MetaKeys.RUN_IN_PROGRESS))
  }

  @Test
  fun transactionRollsBackEverythingOnFailure() {
    val m = Mapping("u1", 7, 15, 1, "h", 0)
    assertThrows(IllegalStateException::class.java) {
      db.transaction {
        db.mappings.upsert(listOf(m))
        db.cursors.put(SyncCursor(7, 5, 5, CalendarState.ACTIVE, null))
        error("crash between the writes")
      }
    }
    assertEquals(0, db.mappings.count())
    assertNull(db.cursors.get(7))
  }

  @Test
  fun nestedTransactionsJoinTheOuterOne() {
    assertThrows(IllegalStateException::class.java) {
      db.transaction {
        db.transaction { db.mappings.upsert(listOf(Mapping("u1", 7, 15, 1, "h", 0))) }
        error("outer fails after the inner one returned")
      }
    }
    assertEquals(0, db.mappings.count())

    db.transaction { db.transaction { db.meta.put("k", "v") } }
    assertEquals("v", db.meta.get("k"))
  }

  @Test
  fun wipeEmptiesEveryTable() {
    db.snapshots.upsert(listOf(Snapshot("u", 1, 1, false, "{}")))
    db.mappings.upsert(listOf(Mapping("u", 1, 2, 3, "h", 4)))
    db.cursors.put(SyncCursor(1, 1, 1, CalendarState.ACTIVE, null))
    db.meta.put(MetaKeys.MAPPER_VERSION, "1")
    db.wipe()
    assertNull(db.snapshots.get("u"))
    assertEquals(0, db.mappings.count())
    assertTrue(db.cursors.all().isEmpty())
    assertNull(db.meta.get(MetaKeys.MAPPER_VERSION))
    assertEquals(SyncSchema.TABLES.toSet(), tables())
  }

  @Test
  fun everyVersionHasAMigrationStep() {
    for (version in 1 until SyncSchema.VERSION) {
      assertTrue("missing migration from $version", SyncSchema.MIGRATIONS.containsKey(version))
    }
    SyncSchema.upgrade(sql, SyncSchema.VERSION, SyncSchema.VERSION)
    assertEquals(SyncSchema.TABLES.toSet(), tables())
  }

  @Test
  fun upgradeFromVersion1DropsTheServerCursorButKeepsTheRest() {
    // Version 1 stored the server's `since`, a backwards paging cursor that made INCREMENTAL runs
    // miss changes. The upgrade drops it (null forces one FULL) and keeps state, last FULL, labels.
    val old = JdbcSqlConnection.inMemory()
    try {
      old.transaction {
        V1_CREATE.forEach { old.execute(it) }
        old.execute(
          "INSERT INTO cursor (tt_cal_id, since, last_full_at, state, labels_json) VALUES (?, ?, ?, ?, ?)",
          listOf(7L, 1794465807011L, 1794481200000L, "PAUSED", """[{"id":1,"name":"x"}]"""),
        )
        old.execute(
          "INSERT INTO cursor (tt_cal_id, since, last_full_at, state, labels_json) VALUES (?, ?, ?, ?, ?)",
          listOf(8L, null, null, "ACTIVE", null),
        )
        old.execute(
          "INSERT INTO mapping (uuid, tt_cal_id, target_cal_id, event_id, spec_hash, end_at) " +
            "VALUES (?, ?, ?, ?, ?, ?)",
          listOf("u1", 7L, 15L, 1001L, "h", 5L),
        )
      }
      SyncSchema.upgrade(old, 1, SyncSchema.VERSION)
      val upgraded = SqlSyncDatabase(old)
      assertEquals(
        listOf(
          SyncCursor(7, null, 1794481200000L, CalendarState.PAUSED, """[{"id":1,"name":"x"}]"""),
          SyncCursor(8, null, null, CalendarState.ACTIVE, null),
        ),
        upgraded.cursors.all(),
      )
      // Mappings keep everything; when they were written is unknown.
      assertEquals(Mapping("u1", 7, 15, 1001, "h", 5, writtenAt = null), upgraded.mappings.get("u1"))
      upgraded.mappings.upsert(listOf(Mapping("u2", 7, 15, 1002, "h", 5, writtenAt = 99)))
      assertEquals(99L, upgraded.mappings.get("u2")!!.writtenAt)
      val columns = old.query("PRAGMA table_info(cursor)") { it.string(1) }
      assertEquals(
        listOf("tt_cal_id", "high_water", "last_full_at", "state", "labels_json", "last_full_pages"),
        columns,
      )
      assertEquals(SyncSchema.TABLES.toSet(), old.query("SELECT name FROM sqlite_master WHERE type = 'table'") { it.string(0) }.toSet())
      // The upgraded table works like a fresh one.
      upgraded.cursors.put(SyncCursor(7, 42, 1, CalendarState.ACTIVE, null))
      assertEquals(42L, upgraded.cursors.get(7)!!.highWater)
    } finally {
      old.close()
    }
  }

  @Test
  fun upgradeWithoutAStepChangesNothing() {
    db.meta.put("k", "v")
    assertThrows(IllegalStateException::class.java) {
      SyncSchema.upgrade(sql, SyncSchema.VERSION, SyncSchema.VERSION + 1)
    }
    assertEquals("v", db.meta.get("k"))
  }

  @Test
  fun recreateDropsAppTablesButKeepsAndroidMetadata() {
    sql.execute("CREATE TABLE android_metadata (locale TEXT)")
    sql.execute("CREATE TABLE added_by_a_newer_version (x INTEGER)")
    db.mappings.upsert(listOf(Mapping("u", 1, 2, 3, "h", 4)))
    SyncSchema.recreate(sql)
    assertEquals(SyncSchema.TABLES.toSet() + "android_metadata", tables())
    assertEquals(0, db.mappings.count())
    assertFalse("added_by_a_newer_version" in tables())
  }

  private companion object {
    /** The schema of version 1, as shipped before the high-water mark. */
    val V1_CREATE =
      listOf(
        "CREATE TABLE snapshot (uuid TEXT NOT NULL PRIMARY KEY, tt_cal_id INTEGER NOT NULL, " +
          "updated_at INTEGER NOT NULL, deactivated INTEGER NOT NULL, json TEXT NOT NULL)",
        "CREATE INDEX snapshot_tt_cal_id ON snapshot (tt_cal_id)",
        "CREATE TABLE mapping (uuid TEXT NOT NULL PRIMARY KEY, tt_cal_id INTEGER NOT NULL, " +
          "target_cal_id INTEGER NOT NULL, event_id INTEGER NOT NULL, spec_hash TEXT NOT NULL, " +
          "end_at INTEGER NOT NULL)",
        "CREATE INDEX mapping_tt_cal_id ON mapping (tt_cal_id)",
        "CREATE TABLE cursor (tt_cal_id INTEGER NOT NULL PRIMARY KEY, since INTEGER, " +
          "last_full_at INTEGER, state TEXT NOT NULL, labels_json TEXT)",
        "CREATE TABLE meta (key TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL)",
      )
  }
}
