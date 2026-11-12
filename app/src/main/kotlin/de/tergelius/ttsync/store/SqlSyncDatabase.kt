package de.tergelius.ttsync.store

/**
 * The four stores on any [SqlConnection] whose schema is [SyncSchema]. [SyncDb] uses it on
 * framework SQLite; JVM tests use it on JDBC.
 */
class SqlSyncDatabase(private val sql: SqlConnection) : SyncDatabase {

  override val snapshots: SnapshotStore = Snapshots()
  override val mappings: MappingStore = Mappings()
  override val cursors: CursorStore = Cursors()
  override val meta: MetaStore = Meta()

  override fun <T> transaction(block: () -> T): T = sql.transaction(block)

  override fun wipe() {
    sql.transaction { SyncSchema.TABLES.forEach { sql.execute("DELETE FROM $it") } }
  }

  private inner class Snapshots : SnapshotStore {
    private val columns = "uuid, tt_cal_id, updated_at, deactivated, json"

    private fun read(row: SqlRow) =
      Snapshot(
        uuid = row.string(0),
        ttCalId = row.long(1),
        updatedAt = row.long(2),
        deactivated = row.long(3) != 0L,
        json = row.string(4),
      )

    override fun get(uuid: String): Snapshot? =
      sql.query("SELECT $columns FROM ${SyncSchema.SNAPSHOT} WHERE uuid = ?", listOf(uuid), ::read)
        .firstOrNull()

    override fun forCalendar(ttCalId: Long): List<Snapshot> =
      sql.query(
        "SELECT $columns FROM ${SyncSchema.SNAPSHOT} WHERE tt_cal_id = ? ORDER BY uuid",
        listOf(ttCalId),
        ::read,
      )

    override fun upsert(snapshots: Collection<Snapshot>) {
      if (snapshots.isEmpty()) return
      sql.transaction {
        for (s in snapshots) {
          sql.execute(
            "INSERT OR REPLACE INTO ${SyncSchema.SNAPSHOT} ($columns) VALUES (?, ?, ?, ?, ?)",
            listOf(s.uuid, s.ttCalId, s.updatedAt, if (s.deactivated) 1L else 0L, s.json),
          )
        }
      }
    }

    override fun delete(uuids: Collection<String>) {
      if (uuids.isEmpty()) return
      sql.transaction {
        uuids.forEach { sql.execute("DELETE FROM ${SyncSchema.SNAPSHOT} WHERE uuid = ?", listOf(it)) }
      }
    }

    override fun deleteCalendar(ttCalId: Long) {
      sql.execute("DELETE FROM ${SyncSchema.SNAPSHOT} WHERE tt_cal_id = ?", listOf(ttCalId))
    }
  }

  private inner class Mappings : MappingStore {
    private val columns = "uuid, tt_cal_id, target_cal_id, event_id, spec_hash, end_at"

    private fun read(row: SqlRow) =
      Mapping(
        uuid = row.string(0),
        ttCalId = row.long(1),
        targetCalId = row.long(2),
        eventId = row.long(3),
        specHash = row.string(4),
        endAt = row.long(5),
      )

    override fun get(uuid: String): Mapping? =
      sql.query("SELECT $columns FROM ${SyncSchema.MAPPING} WHERE uuid = ?", listOf(uuid), ::read)
        .firstOrNull()

    override fun forCalendar(ttCalId: Long): List<Mapping> =
      sql.query(
        "SELECT $columns FROM ${SyncSchema.MAPPING} WHERE tt_cal_id = ? ORDER BY uuid",
        listOf(ttCalId),
        ::read,
      )

    override fun all(): List<Mapping> =
      sql.query("SELECT $columns FROM ${SyncSchema.MAPPING} ORDER BY uuid", emptyList(), ::read)

    override fun count(): Int =
      sql.query("SELECT COUNT(*) FROM ${SyncSchema.MAPPING}") { it.long(0) }.single().toInt()

    override fun upsert(mappings: Collection<Mapping>) {
      if (mappings.isEmpty()) return
      sql.transaction {
        for (m in mappings) {
          sql.execute(
            "INSERT OR REPLACE INTO ${SyncSchema.MAPPING} ($columns) VALUES (?, ?, ?, ?, ?, ?)",
            listOf(m.uuid, m.ttCalId, m.targetCalId, m.eventId, m.specHash, m.endAt),
          )
        }
      }
    }

    override fun delete(uuids: Collection<String>) {
      if (uuids.isEmpty()) return
      sql.transaction {
        uuids.forEach { sql.execute("DELETE FROM ${SyncSchema.MAPPING} WHERE uuid = ?", listOf(it)) }
      }
    }

    override fun deleteCalendar(ttCalId: Long) {
      sql.execute("DELETE FROM ${SyncSchema.MAPPING} WHERE tt_cal_id = ?", listOf(ttCalId))
    }

    override fun clear() {
      sql.execute("DELETE FROM ${SyncSchema.MAPPING}")
    }
  }

  private inner class Cursors : CursorStore {
    private val columns = "tt_cal_id, since, last_full_at, state, labels_json"

    private fun read(row: SqlRow) =
      SyncCursor(
        ttCalId = row.long(0),
        since = row.longOrNull(1),
        lastFullAt = row.longOrNull(2),
        state = stateOf(row.string(3)),
        labelsJson = row.stringOrNull(4),
      )

    override fun get(ttCalId: Long): SyncCursor? =
      sql.query(
          "SELECT $columns FROM ${SyncSchema.CURSOR} WHERE tt_cal_id = ?",
          listOf(ttCalId),
          ::read,
        )
        .firstOrNull()

    override fun all(): List<SyncCursor> =
      sql.query("SELECT $columns FROM ${SyncSchema.CURSOR} ORDER BY tt_cal_id", emptyList(), ::read)

    override fun put(cursor: SyncCursor) {
      sql.execute(
        "INSERT OR REPLACE INTO ${SyncSchema.CURSOR} ($columns) VALUES (?, ?, ?, ?, ?)",
        listOf(cursor.ttCalId, cursor.since, cursor.lastFullAt, cursor.state.name, cursor.labelsJson),
      )
    }

    override fun delete(ttCalId: Long) {
      sql.execute("DELETE FROM ${SyncSchema.CURSOR} WHERE tt_cal_id = ?", listOf(ttCalId))
    }
  }

  private inner class Meta : MetaStore {
    override fun get(key: String): String? =
      sql.query("SELECT value FROM ${SyncSchema.META} WHERE key = ?", listOf(key)) { it.string(0) }
        .firstOrNull()

    override fun put(key: String, value: String?) {
      if (value == null) {
        sql.execute("DELETE FROM ${SyncSchema.META} WHERE key = ?", listOf(key))
      } else {
        sql.execute(
          "INSERT OR REPLACE INTO ${SyncSchema.META} (key, value) VALUES (?, ?)",
          listOf(key, value),
        )
      }
    }
  }

  companion object {
    /**
     * Reads a stored [CalendarState]. A name this version does not know (written by a newer one)
     * counts as [CalendarState.PAUSED]: no requests and no writes is the safe reading.
     */
    fun stateOf(name: String): CalendarState =
      CalendarState.entries.firstOrNull { it.name == name } ?: CalendarState.PAUSED
  }
}
