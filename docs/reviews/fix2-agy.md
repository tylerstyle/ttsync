# Fix Round 2 Code Review (`5a5b10f`) — agy (Antigravity, Gemini)

Reviewed commit `5a5b10f` (`git show 5a5b10f`) covering device retest bug R1, the `fix1-agy.md` resolutions (`SHOULD-FIX-1`, `NIT-1..3`), the safety invariant, and overall readiness for `v0.1.0`.

---

## Findings

- **Blockers:** 0
- **Should-Fix:** 0
- **Nits:** 0

---

## Verification Summary

1. **Device retest bug R1 — `"Synced N s ago"` snackbar (`StatusScreen.kt:106-116`, `StatusViewModel.kt:57-62, 95-96, 283-299`):**
   - `StatusViewModel` now emits a `SyncedNote(id, secondsAgo)` with a monotonically increasing `id` (`++lastNoteId`).
   - `StatusScreen` keys `LaunchedEffect(syncedNote)` on that note and calls `viewModel.onSyncedShown(syncedNote.id)` only *after* `snackbar.showSnackbar(...)` returns, so the effect is no longer self-cancelled before the snackbar displays.
   - `onSyncedShown(id)` checks `if (it.syncedNote?.id == id)` before clearing, so a newer tap while a snackbar is visible replaces the old snackbar without its completion clearing the newer note (`StatusViewModelTest.kt:203-226`).
2. **`SHOULD-FIX-1` — Upload-health check timing (`SyncEngine.kt:472, 507, 558, 622-652`):**
   - `uploadCheckDue` is armed once Step 1 preconditions succeed (`SyncEngine.kt:558`), and `checkUploads()` runs at the end of `Run.execute()` (`SyncEngine.kt:507`) after `body()` finishes (including when a later step fails, e.g. on a TimeTree network error).
   - `db.mappings.all()` is inside the `try` block of `notUploaded(target)` so a database read failure cannot abort the run report.
   - Copies deleted during the run are already gone from `db.mappings`, and copies inserted or updated during the run have `writtenAt = nowMillis()` (starting a new 30-minute grace window). Covered by `SyncEngineTest.copiesTheRunDeletesOrRewritesAreNotCountedAsStale` and `aRunThatFailsAfterThePreconditionsStillReportsPendingUploads` (`SyncEngineTest.kt:811-835`).
3. **`NIT-1` — Deduplicating malformed events on the second pass (`EventFeed.kt:54, 141-150`):**
   - `malformedSeen: HashSet<JsonObject>` ensures malformed entries re-read during the top-overlap pass of a multi-page `FULL` sync are logged once and (when lacking a `uuid`) counted once in `skippedWithoutUuid`, while preserving version comparison for entries that have a `uuid`. Covered by `OkHttpTimeTreeClientTest.malformedEntriesTheSecondPassReadsAgainCountAndLogOnce` (`OkHttpTimeTreeClientTest.kt:395-412`).
4. **`NIT-2` — Non-blocking `ListenableFuture.await()` in `upgradeToFull` (`WorkManagerSyncScheduler.kt:4, 230-245`):**
   - Uses `androidx.concurrent.futures.await` (`androidx.concurrent:concurrent-futures-ktx:1.1.0`), rethrows `CancellationException`, and catches any other `Exception` (such as a pruned work spec) to return `false` and fall back to `enqueueUniqueWork(NOW_WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, nowWork(full = true, trigger))`.
5. **`NIT-3` — Single cleanup path in `UserActions.removeAllCopies()` (`UserActions.kt:234-262`):**
   - When `target != null`, `engine.removeAllCopies(target)` (`stopSyncing = true`) alone clears the selection and sync tables; the `engine.deselect(ids)` + `settings.setSources(emptyList())` path runs only when `target == null` and is wrapped in `restoringOnFailure(current)`. Covered by `UserActionsTest.kt:169-200, 342-350`.
6. **Safety invariant ("never touch rows the app did not create"):**
   - Unchanged and verified both by unit tests and across 11 on-device checkpoints in `docs/DEVICE-TESTS.md`.

---

## Release Verdict

**Ready for `v0.1.0` public release:** no blockers, should-fixes, or nits remain, and both the safety invariant and core sync flows have been verified on-device.
