package de.tergelius.ttsync.store

/**
 * The little SQL access the stores need. [SyncDb] implements it on framework SQLite; JVM tests
 * implement it on JDBC (sqlite-jdbc), so the schema and every store query run unchanged in both.
 *
 * Arguments are `Long`, `Int`, `String` or null. Every call blocks.
 */
interface SqlConnection {
  /** Runs one statement that returns no rows (DDL, INSERT, UPDATE, DELETE). */
  fun execute(sql: String, args: List<Any?> = emptyList())

  /** Runs a SELECT and maps every row with [read]. Arguments must not be null. */
  fun <T> query(sql: String, args: List<Any?> = emptyList(), read: (SqlRow) -> T): List<T>

  /**
   * Runs [block] in one transaction: it commits if [block] returns and rolls back if it throws.
   * Nested calls join the outer transaction; [block] must not suspend.
   */
  fun <T> transaction(block: () -> T): T
}

/** One result row; columns are read by their position in the SELECT. */
interface SqlRow {
  fun long(index: Int): Long

  fun longOrNull(index: Int): Long?

  fun string(index: Int): String

  fun stringOrNull(index: Int): String?
}
