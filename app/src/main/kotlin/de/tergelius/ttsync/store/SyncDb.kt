package de.tergelius.ttsync.store

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * [SyncDatabase] on framework SQLite (no Room, see DESIGN.md section 2). Phase 4 implements the
 * schema and the stores. Lives in the normal databases folder; backup is off for the whole app.
 */
class SyncDb(context: Context) :
  SQLiteOpenHelper(context.applicationContext, NAME, null, VERSION), SyncDatabase {

  override fun onCreate(db: SQLiteDatabase): Unit = TODO("phase 4: SyncDb schema")

  override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int): Unit =
    TODO("phase 4: SyncDb upgrade $oldVersion -> $newVersion")

  override val snapshots: SnapshotStore
    get() = TODO("phase 4: SnapshotStore")

  override val mappings: MappingStore
    get() = TODO("phase 4: MappingStore")

  override val cursors: CursorStore
    get() = TODO("phase 4: CursorStore")

  override val meta: MetaStore
    get() = TODO("phase 4: MetaStore")

  override fun <T> transaction(block: () -> T): T = TODO("phase 4: transaction")

  override fun wipe(): Unit = TODO("phase 4: wipe")

  companion object {
    const val NAME = "ttsync.db"
    const val VERSION = 1
  }
}
