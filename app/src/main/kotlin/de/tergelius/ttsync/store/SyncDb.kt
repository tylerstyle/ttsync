package de.tergelius.ttsync.store

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * [SyncDatabase] on framework SQLite (no Room, see DESIGN.md section 2). Lives in the normal
 * databases folder; backup is off for the whole app.
 *
 * Thin on purpose: the schema is [SyncSchema] and the stores are [SqlSyncDatabase], both pure Kotlin
 * and covered by JVM tests; this class only adapts `SQLiteDatabase` to [SqlConnection]. The database
 * opens lazily on first use, which must be off the main thread.
 */
class SyncDb(context: Context) :
  SQLiteOpenHelper(context.applicationContext, NAME, null, VERSION), SyncDatabase {

  private val stores: SqlSyncDatabase by lazy { SqlSyncDatabase(FrameworkSql(writableDatabase)) }

  override fun onCreate(db: SQLiteDatabase) {
    SyncSchema.create(FrameworkSql(db))
  }

  override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
    SyncSchema.upgrade(FrameworkSql(db), oldVersion, newVersion)
  }

  /** Only after installing an older APK over a newer one: everything here can be rebuilt. */
  override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
    SyncSchema.recreate(FrameworkSql(db))
  }

  override val snapshots: SnapshotStore
    get() = stores.snapshots

  override val mappings: MappingStore
    get() = stores.mappings

  override val cursors: CursorStore
    get() = stores.cursors

  override val meta: MetaStore
    get() = stores.meta

  override fun <T> transaction(block: () -> T): T = stores.transaction(block)

  override fun wipe() {
    stores.wipe()
  }

  companion object {
    const val NAME = "ttsync.db"
    const val VERSION = SyncSchema.VERSION
  }
}

/** [SqlConnection] on a framework [SQLiteDatabase]; transactions nest natively. */
private class FrameworkSql(private val db: SQLiteDatabase) : SqlConnection {
  override fun execute(sql: String, args: List<Any?>) {
    if (args.isEmpty()) {
      db.execSQL(sql)
      return
    }
    db.compileStatement(sql).use { statement ->
      args.forEachIndexed { i, arg ->
        val index = i + 1
        when (arg) {
          null -> statement.bindNull(index)
          is Long -> statement.bindLong(index, arg)
          is Int -> statement.bindLong(index, arg.toLong())
          is String -> statement.bindString(index, arg)
          else -> throw IllegalArgumentException("unsupported SQL argument ${arg.javaClass.name}")
        }
      }
      statement.execute()
    }
  }

  override fun <T> query(sql: String, args: List<Any?>, read: (SqlRow) -> T): List<T> {
    val strings =
      args
        .map {
          requireNotNull(it) { "query arguments must not be null" }
          it.toString()
        }
        .toTypedArray()
    return db.rawQuery(sql, strings).use { cursor ->
      val row = CursorRow(cursor)
      buildList { while (cursor.moveToNext()) add(read(row)) }
    }
  }

  override fun <T> transaction(block: () -> T): T {
    db.beginTransaction()
    try {
      val result = block()
      db.setTransactionSuccessful()
      return result
    } finally {
      db.endTransaction()
    }
  }
}

private class CursorRow(private val cursor: Cursor) : SqlRow {
  override fun long(index: Int): Long = cursor.getLong(index)

  override fun longOrNull(index: Int): Long? = if (cursor.isNull(index)) null else cursor.getLong(index)

  override fun string(index: Int): String = cursor.getString(index)

  override fun stringOrNull(index: Int): String? =
    if (cursor.isNull(index)) null else cursor.getString(index)
}
