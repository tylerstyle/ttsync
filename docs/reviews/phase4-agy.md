# Review of Phase 4: Stores, Calendar Gateway, and Sync Engine

**Reviewer:** android-agy (Antigravity / agy)  
**Date:** 2026-11-12  
**Commit Range:** `071ae2d..52065ff` (commits `fc668c8`, `52065ff`, packages `store/`, `calendar/`, `sync/`, tests, and fixtures)  
**Reference Document:** `ttsync/docs/DESIGN.md` (Sections 2, 4, 5, 6, 10, 14)  

---

## Executive Summary

Phase 4 implements the data persistence layer (`SyncDb`), the calendar provider abstraction (`CalendarGateway`), and the core sync engine (`SyncEngine`). This is the most safety-critical phase of the project: the target calendar belongs to the user and already contains their personal events. The hard safety rule is: **the app must never update or delete a CalendarContract row it did not create.**

Our review confirms that the safety invariants are upheld across all update and delete paths:
- **R1 Pre-read & Verification:** Every update and delete operation reads the target row by `_ID` and executes `GatewayRules.check` before issuing any write. A row is only touched if `calendar_id == target`, `deleted == 0`, and `Marker.parse(description)` strictly matches the expected UUID. Operations failing R1 are rejected without being sent to the provider.
- **R2 Precondition & Atomic Rollback:** Writes never use table-wide selections. Updates and deletes target `ContentUris.withAppendedId(Events.CONTENT_URI, id)` and are preceded in the exact same `applyBatch` by an assert query (`Events.CONTENT_URI` with `withExpectedCount(1)` matching `_id`, `calendar_id`, `deleted == 0`, and `description LIKE '%[ttsync:<uuid>]' ESCAPE '\'`). If the precondition fails, `CalendarProvider2` aborts and rolls back the batch atomically.
- **R3 Target Verification:** Inserts target only the verified calendar ID, and every batch re-checks the target calendar's account and access level.
- **R4 Sync Adapter Isolation:** Plain `Events.CONTENT_URI` is used throughout. `CALLER_IS_SYNCADAPTER` is never appended, ensuring Google's `CalendarSyncAdapter` marks rows dirty and uploads them. Only the 12 managed columns (with explicit null clearing) plus `calendar_id` on insert are written; all sync-adapter and metadata columns are strictly forbidden.
- **R5 Calendar Scoping:** All adoption scans and queries are strictly scoped to `calendar_id == target`.
- **Window via `mapping.end_at`:** Event deletions (both tombstones and missing events) are guarded by `mapping.endAt >= windowStart` (30-day cutoff). Events ending prior to the window cutoff are never touched.
- **Paused Calendars & Lost Access:** Paused calendars are skipped during fetch and mapping, their copies are preserved, and their marked rows are excluded from duplicate cleanup.
- **Cursor Discipline:** A calendar's cursor advances only after all its writes have succeeded and their mappings have been committed to SQLite.
- **Safety Test Rigor:** `SyncEngineSafetyTest` runs 60 seeds of 18 random destructive operations (crashes, lost mappings, provider exceptions, concurrent user edits), asserting after every single step that all foreign and user rows remain completely untouched.

There are **0 Blockers**, **1 Should-Fix** (ordering of `settings.setSources()` in `deselect()`), and **3 Nits** (compileStatement reuse in store upserts, memory allocation in foreign event count, and precondition diagnostics).

---

## Assessment Across Focus Areas

### 1. Update & Delete Paths (The Hard Rule)
All 10 update and delete paths were audited:
1. **Adoption:** Updates an existing marked row only after R1 pre-read and R2 assert query match the expected UUID.
2. **Diff Update:** Updates mapped row only when `specHash` changes, guarded by R1 and R2.
3. **Diff Delete:** Verified delete of mapped row for `Desired.Absent`, guarded by R1, R2, and the 30-day window check (`mapping.endAt >= windowStart`).
4. **Full Reconcile (Missing UUID):** When `provesAbsence` is true, deletes mapped row for missing UUIDs only if in-window and not skipped, guarded by R1 and R2.
5. **Full Reconcile (Mapped Row Check):** If an existing copy fails `RowCheck.OURS` (e.g. user deleted it or removed marker), the engine re-adopts another candidate or re-inserts a new copy. The user's modified event is never updated or deleted.
6. **Duplicates (Adoption Scan):** Extra marked rows for live, active UUIDs are deleted via verified deletes (R1 and R2); rows belonging to paused or dead UUIDs are ignored.
7. **Target Change:** `removeAllCopies(oldTarget, stopSyncing = false)` queries marked rows in the old target and deletes them via verified deletes (R1/R2); unmarked user rows are untouched.
8. **Deselect:** Deletes mapped copies of deselected calendars via verified deletes (R1/R2).
9. **Remove All Copies:** Deletes all marked rows in target via verified deletes (R1/R2).
10. **Crash Replay:** When a chunk aborts due to an R2 precondition failure, operations are replayed individually. If an operation fails individually because the row is no longer ours, it is rejected and re-routed to adopt-or-insert. If the row is still ours, it fails the run rather than inserting duplicates.

### 2. Window Handling via `mapping.end_at`
- In `plan()`, both explicit deletions (`Desired.Absent`) and missing UUID deletions (`work.provesAbsence`) check `mapping.endAt >= windowStart`.
- Events ending before `windowStart` (`today - 30.days`) are never planned for deletion.
- In `mapEvents()`, deactivated tombstones without valid times decode leniently, and their deletion eligibility is determined by the mapping table's recorded `end_at`.
- When an event's times change without altering the spec hash, `endAtFixes` updates `mapping.endAt` in `SyncDb`.

### 3. Paused Calendars & Lost Access
- Paused calendars (`cursor.state == CalendarState.PAUSED`) are skipped during fetch.
- `activeWrites` excludes paused calendars, so their marked rows are treated as unmanaged and never deleted as duplicates.
- Complete fetch reconciliation (`work.provesAbsence`) checks `mapping.ttCalId == work.id`, ensuring one calendar's full sync never deletes copies of another (or paused) calendar.
- Losing access (`TtError.AccessLost` or deactivated in `/calendars`) automatically transitions the calendar to `PAUSED` and preserves all its copies.

### 4. Skipped & Malformed UUIDs
- Events skipped by the client due to decoding errors (`result.skippedUuids`) are subtracted from `affected`. No writes are ever planned for them.
- If a full fetch encounters any malformed event without a readable UUID (`result.skippedMalformed > result.skippedUuids.size`), `work.provesAbsence` is set to `false`, preventing the full sync from treating absent UUIDs as deleted.
- Stored snapshots for skipped UUIDs are preserved during full sync (`storedToKeep`).

### 5. Cursor Advancing Discipline
- `SyncCursor.since` advances only inside `commit(work)`.
- `commit(work)` is called only after all operations in `applyPlan(work, ops)` have succeeded and their mappings have been committed to SQLite.
- If `applyPlan` throws (or aborts after `MAX_ROUNDS`), `commit(work)` is bypassed, leaving the cursor unchanged for the next retry.

### 6. Sync Adapter & Column Rules (R4)
- No URI in `ProviderCalendarGateway` appends `CALLER_IS_SYNCADAPTER`. Writes go through standard provider pathways, causing `CalendarProvider2` to set `dirty = 1`.
- `GatewayRules.FORBIDDEN_COLUMNS` strictly bans sync adapter columns (`dirty`, `_sync_id`, `sync_data1..10`, `mutators`, etc.), `deleted`, and non-managed columns (`hasAlarm`, `eventColor`, etc.).
- `GatewayRules.updateValues` validates that exactly `EventSpec.MANAGED_COLUMNS` (12 columns with explicit nulls) plus `calendar_id` on insert are present.

### 7. SQLite Transactions & Thread Safety
- `DefaultSyncEngine` serializes all runs and user actions via a process-wide `Mutex` (`LOCK.withLock`).
- `SqlConnection.transaction` takes a non-suspending lambda (`block: () -> T`), guaranteeing that database transactions execute synchronously on the current thread without coroutine dispatch hops.
- `FrameworkSql` leverages Android's `SQLiteDatabase.beginTransaction()` / `setTransactionSuccessful()` / `endTransaction()`, which natively supports nested transactions.

### 8. Migration Safety
- `SyncSchema.VERSION` is 1.
- `SyncSchema.upgrade` validates that every version step exists in `MIGRATIONS`; missing steps throw `IllegalStateException` and make no changes.
- `SyncSchema.recreate` drops only app tables (filtering out `sqlite_%` internal tables and `android_metadata`) and recreates schema v1 on downgrade.

### 9. Safety Test Coverage
- `SyncEngineSafetyTest` runs 60 random seeds of 18 steps mixing TimeTree changes, user edits in calendar, lost mappings, crashes, and provider errors.
- `assertForeignUntouched()` checks all foreign and user rows after every single step.
- `GatewayRulesTest` verifies exact marker matching, SQL wildcard escaping (`_` and `%`), and real SQLite execution of the R2 precondition.

---

## Findings Ranked by Severity

### 1. Blockers
None.

---

### 2. Should-Fix Issues

#### [SHOULD-FIX-1] In `deselect()`, settings sources are updated before calendar copies are deleted
- **File & Line:** `app/src/main/kotlin/de/tergelius/ttsync/sync/SyncEngine.kt:167-168`
- **Problem:**  
  In `deselect(ttCalIds)`:
  ```kotlin
  val current = settings.current()
  val remaining = current.sources.filterNot { it.id in ttCalIds }
  if (remaining.size != current.sources.size) settings.setSources(remaining)
  val target = current.target
  if (target != null) {
    val copies =
      db.mappings.all().filter { it.ttCalId in ttCalIds && it.targetCalId == target.calendarId }
    if (copies.isNotEmpty() && targetWritable(target)) {
      deleteVerified(target, copies.map { CalendarOp.Delete(it.eventId, it.uuid) }, counter)
    }
  }
  db.transaction { ... }
  ```
  `settings.setSources(remaining)` is executed before `deleteVerified` runs. If `deleteVerified` fails (e.g. `CALENDAR_WRITE_FAILED` due to a SQLite/provider error, or `NO_CALENDAR_PERMISSION`), `removal` catches the failure and returns early without running `db.transaction`.  
  While `SyncDb` preserves its mappings and snapshots, `settings.sources` has already been updated. As a result:
  1. The UI reflects the calendar as deselected (checkbox unchecked).
  2. The copies in Google Calendar were not deleted.
  3. The user cannot re-trigger "Deselect" from the UI because the calendar is already unselected in settings.
- **Concrete Fix:**  
  Update `settings.setSources(remaining)` only after `deleteVerified` succeeds, alongside `db.transaction`:
  ```kotlin
  override suspend fun deselect(ttCalIds: Set<Long>): RemovalReport =
    LOCK.withLock {
      removal("deselect ${ttCalIds.size}") { counter ->
        val current = settings.current()
        val target = current.target
        if (target != null) {
          val copies =
            db.mappings.all().filter { it.ttCalId in ttCalIds && it.targetCalId == target.calendarId }
          if (copies.isNotEmpty() && targetWritable(target)) {
            deleteVerified(target, copies.map { CalendarOp.Delete(it.eventId, it.uuid) }, counter)
          }
        }
        val remaining = current.sources.filterNot { it.id in ttCalIds }
        if (remaining.size != current.sources.size) settings.setSources(remaining)
        db.transaction {
          for (id in ttCalIds) {
            db.snapshots.deleteCalendar(id)
            db.mappings.deleteCalendar(id)
            db.cursors.delete(id)
          }
        }
      }
    }
  ```

---

### 3. Nits

#### [NIT-1] Repeated SQLite statement compilation inside collection upsert loops
- **File & Line:** `app/src/main/kotlin/de/tergelius/ttsync/store/SqlSyncDatabase.kt:47-51` & `101-105`
- **Problem:**  
  In `Snapshots.upsert` and `Mappings.upsert`:
  ```kotlin
  for (m in mappings) {
    sql.execute(
      "INSERT OR REPLACE INTO ${SyncSchema.MAPPING} ($columns) VALUES (?, ?, ?, ?, ?, ?)",
      listOf(m.uuid, m.ttCalId, m.targetCalId, m.eventId, m.specHash, m.endAt),
    )
  }
  ```
  `sql.execute()` invokes `db.compileStatement(sql).use { ... }` on every iteration. While Android caches compiled SQLite statement handles internally, compiling and closing wrapper objects inside a loop of hundreds of items causes minor allocation churn.
- **Concrete Fix:**  
  Provide a batch execution method on `SqlConnection` (e.g. `executeBatch(sql, listArgs)`) that compiles the statement once and executes it repeatedly across the batch.

#### [NIT-2] `countForeignEvents` reads full `DESCRIPTION` strings into memory
- **File & Line:** `app/src/main/kotlin/de/tergelius/ttsync/calendar/ProviderCalendarGateway.kt:66-72`
- **Problem:**  
  `countForeignEvents` queries `Events._ID` and `Events.DESCRIPTION` for all non-deleted events in the target calendar and evaluates `Marker.parse(description)` in Kotlin. For calendars containing thousands of events with long notes, loading all descriptions into memory creates temporary heap pressure.
- **Concrete Fix:**  
  Because `countForeignEvents` is only used on the Target selection screen to count foreign events, the provider query can filter rows using SQL:
  ```sql
  calendar_id = ? AND deleted = 0 AND (description NOT LIKE '%[ttsync:%' OR description IS NULL)
  ```
  Then query only `COUNT(*)` or `_id` without pulling full descriptions.

#### [NIT-3] Add debug log details when single-op retry aborts with `RowCheck.OURS`
- **File & Line:** `app/src/main/kotlin/de/tergelius/ttsync/sync/SyncEngine.kt:301-304`
- **Problem:**  
  When an individual operation aborts during single-op replay, `checkRows` is called. If `check == RowCheck.OURS`, the engine logs:
  `precondition fails on an own row (uuid=...); stopping` and fails with `CALENDAR_WRITE_FAILED`.  
  Including the observed description length and whether it matched the LIKE pattern in debug builds will aid diagnosis if an OEM provider's `newAssertQuery` implementation misbehaves in the field.
- **Concrete Fix:**  
  Add debug logging with the observed row's properties prior to failing.

---

## Resolution (android-claude, phase 5, 2026-11-12)

- SHOULD-FIX-1: fixed. `deselect()` changes the selection only after the deletes went through,
  together with dropping snapshots, mappings and cursors; a failed removal keeps the calendar
  selected and its data, so it can be retried (no run can interleave: the engine mutex is held).
  New test `aFailedDeselectKeepsTheCalendarSelectedSoItCanBeRetried`. `UserActions` now calls
  `deselect` before saving a new selection, checks `RemovalReport.failure` everywhere
  (`saveSources`, `removePaused`, `setTarget`, `removeAllCopies`, `signOut`) and on failure leaves
  selection, target, settings and sign-in as they were (restoring the selection that
  `removeAllCopies(stopSyncing = true)` had cleared) and re-schedules the periodic work.
- NIT-3: done in a minimal form. The engine sees only the `RowCheck`, not the row, so the log line
  now names the operation type, `_ID`, uuid prefix, check result and batch size (no content). The
  description length and LIKE match would need a new gateway method; left for the device test.
- NIT-1, NIT-2: not changed (performance only, small tables).
