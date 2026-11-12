package de.tergelius.ttsync.store

/**
 * The `SyncDb` schema (DESIGN.md section 2) and its migrations. Pure Kotlin on [SqlConnection], so
 * the JVM tests run exactly these statements.
 *
 * Every table is a cache that the app can rebuild: snapshots and cursors come back with a FULL
 * sync, and mappings come back through the adoption scan (the marker in each copy's description).
 * That is why a downgrade simply recreates the schema.
 *
 * To change the schema: bump [VERSION], add the step `VERSION - 1 → VERSION` to [MIGRATIONS], and
 * update [CREATE] so a fresh install ends up with the same tables.
 */
object SyncSchema {
  const val VERSION = 1

  const val SNAPSHOT = "snapshot"
  const val MAPPING = "mapping"
  const val CURSOR = "cursor"
  const val META = "meta"

  val TABLES: List<String> = listOf(SNAPSHOT, MAPPING, CURSOR, META)

  /** The schema of [VERSION]. */
  val CREATE: List<String> =
    listOf(
      """
      CREATE TABLE $SNAPSHOT (
        uuid TEXT NOT NULL PRIMARY KEY,
        tt_cal_id INTEGER NOT NULL,
        updated_at INTEGER NOT NULL,
        deactivated INTEGER NOT NULL,
        json TEXT NOT NULL
      )
      """,
      "CREATE INDEX snapshot_tt_cal_id ON $SNAPSHOT (tt_cal_id)",
      """
      CREATE TABLE $MAPPING (
        uuid TEXT NOT NULL PRIMARY KEY,
        tt_cal_id INTEGER NOT NULL,
        target_cal_id INTEGER NOT NULL,
        event_id INTEGER NOT NULL,
        spec_hash TEXT NOT NULL,
        end_at INTEGER NOT NULL
      )
      """,
      "CREATE INDEX mapping_tt_cal_id ON $MAPPING (tt_cal_id)",
      """
      CREATE TABLE $CURSOR (
        tt_cal_id INTEGER NOT NULL PRIMARY KEY,
        since INTEGER,
        last_full_at INTEGER,
        state TEXT NOT NULL,
        labels_json TEXT
      )
      """,
      "CREATE TABLE $META (key TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL)",
    )

  /**
   * Upgrade steps: the key is the version a step starts from, the value the statements that lift it
   * to `key + 1`. Version 1 is the first schema, so there are none yet.
   */
  val MIGRATIONS: Map<Int, List<String>> = emptyMap()

  fun create(sql: SqlConnection) {
    sql.transaction { CREATE.forEach { sql.execute(it.trimIndent()) } }
  }

  /**
   * Runs every step from [from] to [to].
   *
   * @throws IllegalStateException if a step is missing (a bug: nothing is changed then)
   */
  fun upgrade(sql: SqlConnection, from: Int, to: Int) {
    require(from <= to) { "not an upgrade: $from -> $to" }
    val steps = (from until to).map { version ->
      MIGRATIONS[version] ?: throw IllegalStateException("no SyncDb migration from version $version")
    }
    sql.transaction { steps.flatten().forEach { sql.execute(it.trimIndent()) } }
  }

  /**
   * Drops every app table, including ones a newer version added, and creates [VERSION]
   * (downgrade). Keeps SQLite's own tables and Android's `android_metadata`.
   */
  fun recreate(sql: SqlConnection) {
    sql.transaction {
      val tables =
        sql.query(
          "SELECT name FROM sqlite_master WHERE type = 'table' " +
            "AND name NOT LIKE 'sqlite~_%' ESCAPE '~' AND name != 'android_metadata'"
        ) {
          it.string(0)
        }
      tables.forEach { sql.execute("DROP TABLE IF EXISTS \"$it\"") }
      create(sql)
    }
  }
}
