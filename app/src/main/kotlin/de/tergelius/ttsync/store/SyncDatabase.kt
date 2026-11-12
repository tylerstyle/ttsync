package de.tergelius.ttsync.store

/**
 * The app's own sync state (DESIGN.md section 2): snapshots, mappings, cursors and meta values.
 * Implemented by [SyncDb] (framework SQLite); engine tests use an in-memory fake.
 *
 * Threading: every method blocks and must be called off the main thread (the worker runs on
 * `Dispatchers.IO`). The stores are not suspend functions on purpose: a SQLite transaction is bound
 * to its thread, so [transaction] blocks must not suspend.
 *
 * The mapping table is the app's record of which rows it owns, but it is never trusted alone:
 * before any update or delete the gateway re-checks the row's calendar and marker (R1).
 */
interface SyncDatabase {
  val snapshots: SnapshotStore
  val mappings: MappingStore
  val cursors: CursorStore
  val meta: MetaStore

  /**
   * Runs [block] in one transaction: all its writes commit together, or none do if it throws. The
   * engine writes each chunk's mappings, and each calendar's cursor advance, this way. Nested calls
   * join the outer transaction.
   */
  fun <T> transaction(block: () -> T): T

  /** Deletes every row of every table (sign-out). */
  fun wipe()
}

/** Snapshots per TimeTree event uuid. */
interface SnapshotStore {
  fun get(uuid: String): Snapshot?

  fun forCalendar(ttCalId: Long): List<Snapshot>

  /** Inserts or replaces by uuid. */
  fun upsert(snapshots: Collection<Snapshot>)

  fun delete(uuids: Collection<String>)

  /** Deselect: drops every snapshot of that calendar. */
  fun deleteCalendar(ttCalId: Long)
}

/** Mappings per TimeTree event uuid. */
interface MappingStore {
  fun get(uuid: String): Mapping?

  fun forCalendar(ttCalId: Long): List<Mapping>

  fun all(): List<Mapping>

  fun count(): Int

  /** Inserts or replaces by uuid. */
  fun upsert(mappings: Collection<Mapping>)

  fun delete(uuids: Collection<String>)

  /** Deselect: drops every mapping of that calendar. */
  fun deleteCalendar(ttCalId: Long)

  /** Target change, "Remove all copies", debug `DROP_MAPPINGS`. */
  fun clear()
}

/** One cursor per selected TimeTree calendar. */
interface CursorStore {
  fun get(ttCalId: Long): SyncCursor?

  fun all(): List<SyncCursor>

  /** Inserts or replaces by `ttCalId`. */
  fun put(cursor: SyncCursor)

  fun delete(ttCalId: Long)
}

/** Small key-value pairs, keys in [MetaKeys]. */
interface MetaStore {
  fun get(key: String): String?

  /** Stores [value], or removes the key if it is null. */
  fun put(key: String, value: String?)
}
