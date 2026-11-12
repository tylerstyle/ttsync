# Fix Round 1 Code Review (`180ad20`) — `android-agy`

Reviewed commit `180ad20` (`git -C ttsync diff 5a34f63 180ad20`) against the on-device test findings (`oneplus8/notes/ttsync-device-test-results.md`), `ttsync/docs/DESIGN.md`, and the Phase 5/6 review resolutions (`ttsync/docs/reviews/phase5-agy.md`).

---

## Summary

- **Blockers:** 0
- **Should-Fix:** 1
- **Nits:** 3

The fix round cleanly and thoroughly resolves the issues discovered during on-device testing:

1. **Incremental feed algorithm & deletion safety (B1):**
   - `EventFeed` (`EventFeed.kt:41-147`) always starts at the top of `GET /calendar/{id}/events/sync` (no `since` query parameter on page 1) and pages backwards via the server's `since` cursor.
   - `SyncCursor.highWater` (`Dtos.kt:134-135`, `SyncEngine.kt:697-698`) tracks the highest `maxOf(updatedAt, deactivatedAt)` seen across events, and incremental runs pass `stopAt = highWater - 2 min` (`TimeTreeApi.FEED_OVERLAP`).
   - Paging stops only after finishing a page that contains at least one event with `updated_at <= stopAt` while `ordered == true` (`EventFeed.kt:85, 102-108`). If any page is out of descending `updated_at` order, `ordered` flips to `false` and the reader falls back to reading the entire feed (`EventFeed.kt:103-106`).
   - Multi-page `FULL` runs (`stopAt == null && pages > 1`) re-read the top of the feed down to `first.newest - 2 min` (`EventFeed.kt:58-60`) and merge by `uuid` keeping the copy with the higher `updated_at` (`EventFeed.kt:116-123`), preventing an event edited mid-paging from jumping to page 1 and being misclassified as deleted by absence.
   - Deletions by absence are strictly gated on `work.provesAbsence = full && result.complete && result.skippedMalformed <= result.skippedUuids.size` (`SyncEngine.kt:695, 761-764, 790`). An `INCREMENTAL` run never deletes by absence even if it happens to reach `chunk == false` (`SyncEngineTest.kt:274-283`).
2. **Adaptive `FULL` policy (`SyncEngine.kt:723-738`):**
   - `EventFeed` records `feedPages` right after the first pass (`EventFeed.kt:57`), before the second overlap pass, so a 3-page feed whose `FULL` run makes 4 requests still records `lastFullPages = 3`.
   - Calendars with `lastFullPages <= SMALL_FEED_PAGES` (`3`, i.e. up to ~300 events) run `FULL` on every sync so silent TimeTree deletions arrive within one sync interval; larger feeds run `INCREMENTAL` and force `FULL` at least every `FULL_INTERVAL_MILLIS` (6 hours).
3. **`SyncDb` schema 1 -> 2 migration (`SyncSchema.kt:66-94`):**
   - Adds `written_at INTEGER` to `mapping` and rebuilds `cursor` via `cursor_v2` (compatible with SQLite < 3.25 on API 26–29), setting `high_water = NULL` to force one `FULL` sync after upgrade while preserving `PAUSED` state, `last_full_at`, and `labels_json`.
   - Tested against the exact v1 DDL in `SqlSyncDatabaseTest.kt:157-203`.
4. **Account visibility & upload-health warning (B2):**
   - `ProviderCalendarGateway.googleSyncState` (`ProviderCalendarGateway.kt:59-74`) checks `AccountManager.getAccountsByType( target.accountType )` before trusting `ContentResolver.getSyncAutomatically`, returning `calendarSync = null` when Android 13+ hides the account from the app.
   - `ProviderCalendarGateway.pendingUploads` (`ProviderCalendarGateway.kt:184-206`) queries `Events.DIRTY = 1` in chunks of 100 and degrades gracefully to `null` if the provider rejects the column.
   - One timing issue in when `notUploaded(target)` is sampled during a sync run is noted in **SHOULD-FIX-1** below.
5. **Phase 5 review resolutions:**
   - **SHOULD-FIX-1 (`removeAllCopies` failure handling):** Resolved in `SyncEngine.kt:506-525` and `UserActions.kt:234-269`.
   - **SHOULD-FIX-2 (`runNow` coalescing):** Resolved in `WorkManagerSyncScheduler.kt:57-91, 189-235` (`planNow` + `WorkManager.updateWork`). Verified against `androidx.work.impl.WorkerUpdater` in `work-runtime:2.12.0` that `updateWork` preserves `WorkInfo.State.BLOCKED` on a chained follow-up work spec and returns `APPLIED_IMMEDIATELY`.
   - **SHOULD-FIX-3 (interactive login budget & raw dump suppression):** Resolved in `OkHttpTimeTreeClient.kt:69-70, 96-119, 146-152, 232-246`.
   - **SHOULD-FIX-4 (Android 12L/13 permission launch):** Resolved in `Navigation.kt:112-119`.
   - **NIT-1 (`DebugReceiver` lookup) & NIT-2 (`KeystoreCredentialStore` lazy dir + alias delete on `clear()`):** Resolved in `DebugReceiver.kt:36-41` and `KeystoreCredentialStore.kt:51-53, 86-93`.
6. **Safety invariant (never touch rows the app did not create):**
   - Untouched and intact: all `Update` and `Delete` operations still go through `ProviderCalendarGateway.apply` (`ProviderCalendarGateway.kt:140-182`) with R1 pre-read marker verification (`EventMarker.parse(row.description) == op.expectedMarker`) and R2 single-row `_ID` URI + SQL precondition (`CALENDAR_ID = ? AND DELETED = 0 AND DESCRIPTION IS ?`).

---

## Should-Fix

### SHOULD-FIX-1: `notUploaded(target)` is evaluated in Step 1 before `applyPlan`, so copies deleted or updated in the current run are still counted as stale un-uploaded rows until the next run
- **Location:** [SyncEngine.kt](../../app/src/main/kotlin/de/tergelius/ttsync/sync/SyncEngine.kt#L554-L555), [SyncEngine.kt](../../app/src/main/kotlin/de/tergelius/ttsync/sync/SyncEngine.kt#L613-L642)
- **Problem:**
  In `DefaultSyncEngine.body()`, `notUploaded = notUploaded(target) ?: 0` is called at line 554 in Step 1 (Preconditions), before `fetch`, `plan`, and `applyPlan`.
  - If a copy was written more than 30 minutes ago (`writtenAt <= now - 30 min`) and still has `Events.DIRTY = 1` when the sync starts, and the current run **deletes** that copy (because the event was deleted in TimeTree, or its calendar was deselected), Step 7 deletes the CalendarProvider row and removes its `Mapping`. However, because `notUploaded` was already computed in Step 1, `SyncReport.notUploaded` (and `SyncWarning.NOT_UPLOADED` on the Status screen) still reports the deleted copy as un-uploaded until the *next* sync run.
  - Similarly, if the current run **updates** that copy in Step 7, `recordResults` resets its `Mapping.writtenAt = nowMillis()` (`SyncEngine.kt:918`), yet Step 1 counted the pre-update `writtenAt` against the 30-minute grace window.
- **Concrete Fix:**
  Compute `notUploaded` in Step 8 (after `applyPlan(target, plans)` has updated `db.mappings` and CalendarProvider), right before checking `gateway.googleSyncState(target)`:
  ```kotlin
  // Step 8: Finish.
  db.meta.remove(MetaKeys.RUN_IN_PROGRESS)
  notUploaded = notUploaded(target) ?: 0
  val syncState = gateway.googleSyncState(target)
  ```
  At Step 8, `db.mappings.forTarget(target.id)` contains only surviving copies, newly inserted/updated copies have `writtenAt = nowMillis()` (within the 30-minute grace period), and untouched copies from earlier runs retain their original `writtenAt <= cutoff`.

---

## Nits

### NIT-1: Malformed events without a `uuid` near the top of the feed are counted twice in `skippedMalformed` on multi-page `FULL` runs
- **Location:** [EventFeed.kt](../../app/src/main/kotlin/de/tergelius/ttsync/timetree/EventFeed.kt#L46-L60), [EventFeed.kt](../../app/src/main/kotlin/de/tergelius/ttsync/timetree/EventFeed.kt#L125-L128)
- **Problem:**
  `skippedUuids` is a `LinkedHashSet<String>` (deduplicated across passes), whereas `skippedWithoutUuid` is an `Int` counter (`EventFeed.kt:46`). When a multi-page `FULL` sync runs its second pass from the top of the feed (`pass(first.newest - TimeTreeApi.FEED_OVERLAP.inWholeMilliseconds)` at `EventFeed.kt:58-60`), any malformed event without a `uuid` on the re-read top page(s) is visited in both passes, incrementing `skippedWithoutUuid` (and `SyncCounts.skipped`) twice and logging `"skipped malformed event without uuid"` twice. (Deletion safety is unaffected because `work.provesAbsence` checks `result.skippedMalformed <= result.skippedUuids.size`, which is `false` for any `skippedWithoutUuid >= 1`.)
- **Concrete Fix:**
  Deduplicate uuid-less malformed JSON objects across passes using a `LinkedHashSet<JsonObject>` (or only count/log them when ` skippedWithoutUuid.add(obj)` returns `true`):
  ```kotlin
  private val skippedWithoutUuid = LinkedHashSet<JsonObject>()
  ```
  and in `read()`:
  ```kotlin
  val skipped = skippedUuids.size + skippedWithoutUuid.size
  ```

### NIT-2: `WorkManagerSyncScheduler.upgradeToFull` blocks an `IO` thread with `update.get()` instead of suspending with `update.await()` and does not guard against a concurrently pruned work spec
- **Location:** [WorkManagerSyncScheduler.kt](../../app/src/main/kotlin/de/tergelius/ttsync/sync/WorkManagerSyncScheduler.kt#L229-L234)
- **Problem:**
  `WorkManagerSyncScheduler.kt:14` already imports `androidx.work.await`, which awaits a `ListenableFuture` without blocking a thread, whereas `withContext(Dispatchers.IO) { update.get() }` blocks an `IO` dispatcher thread. In addition, if a work spec `id` was pruned between `getWorkInfosForUniqueWork` and `updateWork`, `WorkerUpdater.updateWorkImpl` fails the future with `IllegalArgumentException("Worker with $id doesn't exist")`.
- **Concrete Fix:**
  Use `await()` inside `runCatching` so any race falls back cleanly to `enqueueUniqueWork`:
  ```kotlin
  private suspend fun upgradeToFull(ids: List<UUID>, trigger: SyncTrigger): Boolean =
    ids.all { id ->
      val result =
        runCatching { workManager.updateWork(nowWork(full = true, trigger, id = id)).await() }
          .getOrNull()
      result == WorkManager.UpdateResult.APPLIED_IMMEDIATELY
    }
  ```

### NIT-3: `UserActions.removeAllCopies()` calls `engine.deselect(ids)` and `settings.setSources(emptyList())` redundantly after `engine.removeAllCopies(target)`
- **Location:** [UserActions.kt](../../app/src/main/kotlin/de/tergelius/ttsync/ui/UserActions.kt#L238-L250)
- **Problem:**
  When `target != null`, `engine.removeAllCopies(target)` (with default `stopSyncing = true`, `SyncEngine.kt:515-522`) already clears `settings.setSources(emptyList())` and wipes `snapshots`, `cursors`, and `mappings`. Lines 248–250 of `UserActions.kt` then call `engine.deselect(ids)` and `settings.setSources(emptyList())` a second time.
- **Concrete Fix:**
  Move the fallback `engine.deselect(ids)` / `settings.setSources(emptyList())` into the `target == null` branch (or update `FakeEngine.removeAllCopies` in `UiFakes.kt` to clear `sources` when `target == null` as well).

---

## Resolution (fix round 2, android-claude)

- **SHOULD-FIX-1: fixed.** The upload check moved from step 1 to the end of the run
  (`Run.checkUploads()`, called in `execute()` after `body()`). By then copies this run deleted are
  gone, and copies it rewrote have a new `writtenAt`, so neither counts as stale. It differs from
  the suggested spot (step 8) on purpose: it also runs when a later step failed, as long as the
  preconditions passed (the same condition as before). Otherwise a TimeTree outage would hide the
  "not uploaded" warning. The `db.mappings.all()` read moved inside the `try`, so the check never
  fails a run. DESIGN.md section 5, step 1 is updated. Tests:
  `SyncEngineTest.copiesTheRunDeletesOrRewritesAreNotCountedAsStale` (4 stale before, now 2) and
  `aRunThatFailsAfterThePreconditionsStillReportsPendingUploads`.
- **NIT-1: fixed.** `EventFeed` remembers malformed entries by JSON content (`malformedSeen`). An
  entry that the second pass reads again is logged once and, without a uuid, counted once. The
  keep/skip rules for entries with a uuid are unchanged (`skippedUuids` already deduplicated them).
  Test: `OkHttpTimeTreeClientTest.malformedEntriesTheSecondPassReadsAgainCountAndLogOnce`.
- **NIT-2: fixed, with a correction.** In work-runtime 2.12, `androidx.work.await` is only
  `Operation.await()`; there is none for `ListenableFuture`, so the suggested code does not compile.
  The app now declares `androidx.concurrent:concurrent-futures-ktx` 1.1.0, which WorkManager
  already puts on the runtime classpath (the APK gains nothing), and uses its
  `ListenableFuture.await()`. A failed future, such as a pruned work spec, is caught and counts as
  "not upgraded", and the caller then appends a new FULL run. It is a `try`/`catch` that rethrows
  `CancellationException`, not `runCatching`, which would swallow cancellation. No unit test:
  WorkManager does not run in the JVM tests. `planNow` stays covered by `NowPlanTest`.
- **NIT-3: fixed.** With a target, `engine.removeAllCopies(target)` (`stopSyncing`) alone clears the
  selection and the sync data. `deselect` and `setSources([])` now run only without a target, and
  a failure there counts as a failed removal (the settings are restored). Tests:
  `UserActionsTest.removeAllCopiesStopsSyncingAndReturnsToSources` (exact call log, no second
  deselect), `removeAllCopiesWithoutATargetOnlyClearsTheSelection`, and
  `removingAllCopiesFromADeletedTargetLetsTheEngineDecide`.

The same round fixes device retest bug R1 (the "Synced N s ago" snackbar), see
`docs/DEVICE-TESTS.md`.
