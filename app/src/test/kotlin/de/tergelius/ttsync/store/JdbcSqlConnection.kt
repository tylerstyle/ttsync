package de.tergelius.ttsync.store

import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types

/**
 * [SqlConnection] on sqlite-jdbc, for JVM tests: the same SQLite dialect as the framework, so
 * [SyncSchema] and [SqlSyncDatabase] run unchanged. Transactions nest like the framework's: the
 * outermost one commits, and an exception anywhere rolls the whole one back.
 */
class JdbcSqlConnection(private val connection: Connection) : SqlConnection, AutoCloseable {
  private var depth = 0

  /** Statements run so far, for tests that check what happened. */
  var statements = 0
    private set

  override fun execute(sql: String, args: List<Any?>) {
    statements++
    connection.prepareStatement(sql).use { statement ->
      bind(statement, args)
      statement.execute()
    }
  }

  override fun <T> query(sql: String, args: List<Any?>, read: (SqlRow) -> T): List<T> {
    require(args.none { it == null }) { "query arguments must not be null" }
    return connection.prepareStatement(sql).use { statement ->
      bind(statement, args)
      statement.executeQuery().use { rs ->
        val row = ResultSetRow(rs)
        buildList { while (rs.next()) add(read(row)) }
      }
    }
  }

  override fun <T> transaction(block: () -> T): T {
    if (depth == 0) connection.autoCommit = false
    depth++
    var ok = false
    try {
      val result = block()
      ok = true
      return result
    } finally {
      depth--
      if (depth == 0) {
        if (ok) connection.commit() else connection.rollback()
        connection.autoCommit = true
      }
    }
  }

  override fun close() = connection.close()

  private fun bind(statement: PreparedStatement, args: List<Any?>) {
    args.forEachIndexed { i, arg ->
      val index = i + 1
      when (arg) {
        null -> statement.setNull(index, Types.NULL)
        is Long -> statement.setLong(index, arg)
        is Int -> statement.setLong(index, arg.toLong())
        is String -> statement.setString(index, arg)
        else -> throw IllegalArgumentException("unsupported SQL argument ${arg.javaClass.name}")
      }
    }
  }

  private class ResultSetRow(private val rs: ResultSet) : SqlRow {
    override fun long(index: Int): Long = rs.getLong(index + 1)

    override fun longOrNull(index: Int): Long? = rs.getLong(index + 1).takeUnless { rs.wasNull() }

    override fun string(index: Int): String = checkNotNull(rs.getString(index + 1))

    override fun stringOrNull(index: Int): String? = rs.getString(index + 1)
  }

  companion object {
    /** A fresh private in-memory database. */
    fun inMemory(): JdbcSqlConnection = JdbcSqlConnection(DriverManager.getConnection("jdbc:sqlite::memory:"))

    /** A fresh in-memory [SyncDatabase] with the current schema. */
    fun syncDatabase(): SqlSyncDatabase {
      val sql = inMemory()
      SyncSchema.create(sql)
      return SqlSyncDatabase(sql)
    }
  }
}
