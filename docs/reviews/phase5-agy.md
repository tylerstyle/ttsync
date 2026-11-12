# Phase 5 & Phase 6 Code Review (`d2756c2`, `f24355a`) — `android-agy`

Reviewed commits:
- Phase 5 (`d2756c2`, diff `eb65008..d2756c2`): `KeystoreCredentialStore.kt`, `AndroidStores.kt`, `StoredSessionStore.kt`, `WorkManagerSyncScheduler.kt`, `SyncWorker.kt`, `SyncNotifier.kt`, `DebugReceiver.kt`, `AppGraph.kt`, `App.kt`, `UserActions.kt`, manifests, backup XML rules, and tests.
- Phase 6 (`f24355a`): `Navigation.kt`, `NavigationKeys.kt`, `MainActivity.kt`, `ui/**`, and UI/ViewModel tests.

---

## Summary of Focus Areas Checked

1. **Credential security:**
   - `KeystoreCredentialStore` uses `AndroidKeyStore` AES-256-GCM (`AES/GCM/NoPadding`, `PURPOSE_ENCRYPT | PURPOSE_DECRYPT`, `setUserAuthenticationRequired(false)` so background workers can decrypt while the phone is locked).
   - Keystore generates a fresh 12-byte random IV on every `write()`; the file layout `[12-byte IV][ciphertext + 16-byte GCM tag]` is written atomically via `AtomicFile` to `noBackupFilesDir/credentials.enc`.
   - Unreadable/corrupted ciphertexts or invalidated keys (`KeyPermanentlyInvalidatedException`, `UnrecoverableKeyException`, `AEADBadTagException`, `ProviderException`) wipe both the file and the Keystore alias on `read()` and return `null`; on `write()`, broken keys are deleted and regenerated once via `retryWithNewKey()`.
   - `Credentials`, `TtCredentials`, `KeystoreCredentialStore.Payload`, and `LoginUiState` all redact secrets in `toString()`. Logs record only exception class simple names.
   - `LoginViewModel` keeps the password only in memory (no `SavedStateHandle`) and clears `password = ""` immediately upon sign-in.
2. **`DebugReceiver` isolation:**
   - `DebugReceiver.kt` and its `<receiver>` manifest entry live exclusively in `app/src/debug/`. Release builds cannot contain or instantiate it.
   - In debug builds, `app/src/debug/AndroidManifest.xml` protects the receiver with `android:permission="android.permission.DUMP"`, which is a platform `signature|privileged|development` permission held by `adb shell` and not grantable to third-party apps.
3. **WorkManager scheduling & lifecycle:**
   - All scheduling calls serialize on a fair `Mutex` launched with `CoroutineStart.UNDISPATCHED` on `appScope`, preserving caller order.
   - `NOW_WORK` uses `setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)` when `delay == 0L` and falls back to `setInitialDelay(delay)` when pacing is needed (respecting WorkManager's prohibition on expedited + initial delay).
   - `SyncWorker.getForegroundInfo()` provides `ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC` on API 29+ matching the `<service android:name="androidx.work.impl.foreground.SystemForegroundService" android:foregroundServiceType="dataSync" tools:node="merge" />` declaration in `AndroidManifest.xml`.
   - One edge case in `enqueueNow` when `NOW_WORK` is already `RUNNING` is noted in **SHOULD-FIX-2**.
4. **Notification behaviour on Android 13+:**
   - `AndroidSyncNotifier` checks `POST_NOTIFICATIONS` on API 33+ and catches `SecurityException`.
   - Channels (`sync_problems` at `IMPORTANCE_DEFAULT`, `sync_running` at `IMPORTANCE_LOW` with badge disabled) are created in `App.onCreate()`.
   - `PendingIntent`s use `FLAG_IMMUTABLE or FLAG_UPDATE_CURRENT`.
5. **UI, permissions, and removal-failure handling:**
   - Two issues in the removal-failure path (`SyncEngine.removeAllCopies` and `UserActions`) are detailed in **SHOULD-FIX-1**.
   - A re-authentication navigation/reload issue on `SourcesScreen` is detailed in **SHOULD-FIX-3**.
   - A runtime permission denial UX issue in `Permissions.kt` is detailed in **SHOULD-FIX-4**.
6. **Manifest & backup rules:**
   - Only `MainActivity` is exported in `src/main/AndroidManifest.xml`.
   - `android:allowBackup="false"`, `android:usesCleartextTraffic="false"`, and both `backup_rules.xml` and `data_extraction_rules.xml` exclude all domains for cloud backup and device transfer.

---

## Findings

### Blockers

None.

---

### Should-Fix

#### SHOULD-FIX-1: `removeAllCopies` wipes `SyncDb` before checking if the target is writable, and `UserActions` calls `gateway.calendar()` outside `engine.removeAllCopies`
- **Where:**
  - `ttsync/app/src/main/kotlin/de/tergelius/ttsync/sync/SyncEngine.kt:152-162` and `226-238`
  - `ttsync/app/src/main/kotlin/de/tergelius/ttsync/ui/UserActions.kt:216-241` and `253-269`
- **Problem:**
  1. In `SyncEngine.removeAllCopies(target, stopSyncing = true)` (`SyncEngine.kt:155`), `if (stopSyncing) stopSyncing()` runs **before** `targetWritable(target)`, `gateway.findMarkedRows(target)`, and `deleteVerified(...)`. Inside `stopSyncing()` (`SyncEngine.kt:226-238`), it clears `settings.sources` **and** deletes all rows in `db.snapshots`, `db.cursors`, and `db.mappings`. If `targetWritable`, `findMarkedRows`, or `deleteVerified` subsequently fails, `UserActions.restore(current)` restores `settings.sources` in DataStore, **but `db.snapshots` (including tombstones), `db.cursors` (including `CalendarState.PAUSED` states and `since` cursors), and `db.mappings` have already been wiped**. Because `removeAllCopies` holds `LOCK.withLock` (and `UserActions` cancels scheduled work beforehand), no sync can run concurrently while deletes are in progress.
  2. In `UserActions.removeAllCopies()` (`UserActions.kt:223`) and `UserActions.signOut()` (`UserActions.kt:260`), `target.check(gateway.calendar(target.calendarId))` is called **before** `engine.removeAllCopies(target)`. On a real device (`ProviderCalendarGateway`), if the user revoked `READ_CALENDAR`/`WRITE_CALENDAR` and taps "Remove all copies" or "Sign out" (with "Remove copies" checked), `gateway.calendar(...)` throws `SecurityException` directly instead of letting `engine.removeAllCopies(target)` return `RemovalReport(failure = SyncFailure(NO_CALENDAR_PERMISSION))` (which `SyncEngine.targetWritable` already handles alongside `TargetCheck.MISSING -> false`). In `SettingsViewModel.confirmRemoveAll()` (`SettingsViewModel.kt:87-89`), that thrown `SecurityException` lands in `catch (_: Exception)` with `removalFailed = false`, showing the generic `R.string.error_unknown` instead of `R.string.error_removal_failed`.
- **Concrete fix:**
  1. In `SyncEngine.removeAllCopies` (`SyncEngine.kt:152-162`), move `stopSyncing()` to run **after** the deletes succeed (matching `SyncEngine.deselect` at lines 176-187):
     ```kotlin
     override suspend fun removeAllCopies(target: TargetCalendar, stopSyncing: Boolean): RemovalReport =
       LOCK.withLock {
         removal("remove all copies") { counter ->
           if (targetWritable(target)) {
             val rows = calendarCall { gateway.findMarkedRows(target) }
             deleteVerified(target, rows.map { CalendarOp.Delete(it.eventId, it.uuid) }, counter)
           }
           if (stopSyncing) stopSyncing() else db.mappings.clear()
         }
       }
     ```
  2. In `UserActions.removeAllCopies()` (`UserActions.kt:220-237`) and `UserActions.signOut()` (`UserActions.kt:256-265`), delegate the target check to `engine.removeAllCopies(target)` (or wrap the pre-check inside `engine.removeAllCopies` in `FakeEngine` as well) so missing permissions or provider errors always come back as `RemovalReport.failure` rather than an uncaught `SecurityException`.

---

#### SHOULD-FIX-2: Repeated `runNow()` calls while `NOW_WORK` is `RUNNING` build an unbounded work chain
- **Where:** `ttsync/app/src/main/kotlin/de/tergelius/ttsync/sync/WorkManagerSyncScheduler.kt:176-218`
- **Problem:**
  When a `NOW_WORK` request `W1` is currently `RUNNING`, line 211 uses `ExistingWorkPolicy.APPEND_OR_REPLACE`. In WorkManager, `APPEND_OR_REPLACE` only replaces if the existing unique work chain is `CANCELLED` or `FAILED`; while `W1` is `RUNNING`, the first `runNow()` appends `W2` in state `BLOCKED` (`W1 -> W2`), and any subsequent `runNow()` while `W1` is still `RUNNING` appends `W3` behind `W2` (`W1 -> W2 -> W3`), and so on. All appended items will then execute back-to-back after `W1` finishes, rather than coalescing into a single pending follow-up run as described in the class comment (*"A pending request is replaced, and a pending FULL stays FULL"*).
- **Concrete fix:**
  In `enqueueNow` (`WorkManagerSyncScheduler.kt:177-185`), check if there are already `BLOCKED`/`ENQUEUED` items behind a `RUNNING` work item:
  ```kotlin
  val pending =
    existing.filter {
      it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED
    }
  val pendingFull = pending.any { TAG_FULL in it.tags }
  val merged = full || pendingFull
  if (running && pending.isNotEmpty()) {
    if (!merged || pendingFull) {
      val gap = RequestPacer.MANUAL_RUN_GAP.inWholeMilliseconds
      log.i("scheduler: ${SyncScheduler.NOW_WORK} already queued behind running work (full=$merged)")
      return NowRequest(full = merged, expedited = false, delayMillis = gap, afterRunning = true)
    }
    // Upgrade the blocked follow-up to FULL: cancel the blocked leaf so APPEND_OR_REPLACE replaces it.
    pending.forEach { workManager.cancelWorkById(it.id).await() }
  }
  ```

---

#### SHOULD-FIX-3: "Sign in again" from `SourcesScreen` either leaves `SourcesScreen` on `SourcesError.SIGN_IN` or pops back to `StatusRoute`
- **Where:**
  - `ttsync/app/src/main/kotlin/de/tergelius/ttsync/Navigation.kt:154-171`
  - `ttsync/app/src/main/kotlin/de/tergelius/ttsync/ui/sources/SourcesScreen.kt:44-68`
- **Problem:**
  When `SourcesViewModel.load()` fails with `SourcesError.SIGN_IN`, `SourcesScreen` shows the "Sign in again" button, which pushes `LoginRoute(reauth = true)` (`Navigation.kt:72`). After the user signs in on `LoginScreen`:
  1. If `SourcesScreen` was opened during setup (`SourcesRoute(setup = true)`), `actions.signIn` returns `SetupStep.SOURCES` and `AppNavigator.onSetupProgress` calls `resetTo(SourcesRoute(setup = true))`. Because `SourcesRoute(setup = true)` was already in `backStack` and remains in `backStack` after `resetTo`, `rememberViewModelStoreNavEntryDecorator()` reuses the existing `SourcesViewModel` instance whose `state.value.loadError` is still `SourcesError.SIGN_IN`, and `SourcesScreen` has no resume effect to reload. The user lands back on `SourcesScreen` still seeing the expired sign-in error.
  2. If `SourcesScreen` was opened from Settings (`[StatusRoute, SettingsRoute, SourcesRoute(setup = false), LoginRoute(reauth = true)]`), `actions.signIn` returns `SetupStep.DONE` and `onSetupProgress` (`Navigation.kt:159-165`) pops all the way back to `StatusRoute`, kicking the user out of `SourcesRoute(setup = false)` and `SettingsRoute`.
- **Concrete fix:**
  1. In `AppNavigator.onSetupProgress` (`Navigation.kt:156`), handle re-auth returns when there is a caller on the back stack:
     ```kotlin
     when {
       next == SetupStep.LOGIN -> resetTo(LoginRoute())
       from is LoginRoute && from.reauth && canGoBack -> back()
       editing -> back()
       ...
     ```
  2. In `SourcesScreen` (`SourcesScreen.kt:52`), reload when resuming after a `SIGN_IN` error:
     ```kotlin
     LifecycleResumeEffect(viewModel) {
       if (viewModel.state.value.loadError == SourcesError.SIGN_IN) {
         viewModel.load()
       }
       onPauseOrDispose {}
     }
     ```

---

#### SHOULD-FIX-4: Denying a runtime permission on the second system prompt immediately launches system Settings
- **Where:** `ttsync/app/src/main/kotlin/de/tergelius/ttsync/ui/common/Permissions.kt:26-44` and `52-78`
- **Problem:**
  In both `rememberCalendarPermissionRequest` and `rememberNotificationRequest`, the `ActivityResultContracts` callback checks `!granted && !ActivityCompat.shouldShowRequestPermissionRationale(...)` right after the system permission dialog closes and immediately calls `SystemIntents.start(context, ...)`.
  On Android 11+, when the user sees the runtime permission dialog for the second time (`shouldShowRequestPermissionRationale` was `true` when the button was tapped) and taps "Don't allow", Android transitions `shouldShowRequestPermissionRationale` to `false` as the dialog closes. The callback immediately fires and launches the system App Info / Notification Settings activity right after the user explicitly tapped "Don't allow". System Settings should only be opened when the dialog was **not shown** because the permission was already permanently denied *before* the user tapped the button.
- **Concrete fix:**
  Record whether rationale was true before launching the request, and only open system Settings when `shouldShowRequestPermissionRationale` was `false` both before and after `launch`:
  ```kotlin
  @Composable
  fun rememberCalendarPermissionRequest(onResult: (Boolean) -> Unit): () -> Unit {
    val activity = LocalActivity.current
    val context = LocalContext.current
    val currentOnResult by rememberUpdatedState(onResult)
    var hadRationale = remember { false }
    val launcher =
      rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val granted = CalendarPermissions.all { result[it] == true }
        if (
          !granted &&
            !hadRationale &&
            activity != null &&
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, CalendarPermissions[0])
        ) {
          SystemIntents.start(context, SystemIntents.appDetails(context))
        }
        currentOnResult(granted)
      }
    return {
      hadRationale =
        activity != null &&
          ActivityCompat.shouldShowRequestPermissionRationale(activity, CalendarPermissions[0])
      launcher.launch(CalendarPermissions)
    }
  }
  ```
  Apply the same `hadRationale` check in `rememberNotificationRequest` (`Permissions.kt:52-78`).

---

### Nits

#### NIT-1: `DataStoreSettingsRepository.installUuid()` reads `store.data` instead of `store.safeData()`
- **Where:** `ttsync/app/src/main/kotlin/de/tergelius/ttsync/settings/AndroidStores.kt:96`
- **Problem:**
  Line 96 calls `store.data.first()[INSTALL_UUID]` directly rather than `store.safeData().first()[INSTALL_UUID]`, bypassing the `IOException` catch helper defined on line 64.
- **Concrete fix:**
  Use `store.safeData().first()[INSTALL_UUID]?.takeIf(::isInstallUuid)?.let { return it }`.

#### NIT-2: Eager `noBackupFilesDir` lookup and intermediate JSON `String` in `KeystoreCredentialStore`
- **Where:** `ttsync/app/src/main/kotlin/de/tergelius/ttsync/settings/KeystoreCredentialStore.kt:43, 134, 142`
- **Problem:**
  1. Line 43 evaluates `context.applicationContext.noBackupFilesDir` in the constructor, which can perform a synchronous `mkdirs()` check on the main thread when `AppGraph.credentialStore` is first initialized from `MainViewModel`.
  2. Lines 134 and 142 zero out `plain: ByteArray` in `finally`, which is good practice, though `JSON.encodeToString(...).encodeToByteArray()` and `plain.decodeToString()` still allocate an immutable `String` of the JSON payload on the heap.
- **Concrete fix:**
  Make `private val file by lazy { AtomicFile(File(appContext.noBackupFilesDir, FILE_NAME)) }` so the directory access happens on `Dispatchers.IO` inside `read()`/`write()`/`wipe()`.

---

## Resolution

Fixed on branch `fixes1` (android-claude, 2026-11-12), together with the on-device test findings.

| Finding | Outcome | Where |
|---|---|---|
| SHOULD-FIX-1.1: `removeAllCopies` wipes `SyncDb` first | Accepted. `stopSyncing()` (sources, snapshots, cursors, mappings) runs only after the deletes went through, like `deselect`. A failed removal leaves everything as it was. | `SyncEngine.removeAllCopies`; tests `removeAllCopiesOnAnUnwritableTargetDeletesNothingAndChangesNothing`, `removeAllCopiesWithoutCalendarPermissionIsAFailureThatKeepsEverything` |
| SHOULD-FIX-1.2: `gateway.calendar()` outside the engine | Accepted for "Remove all copies" and sign-out: `UserActions` no longer checks the target and leaves that to `engine.removeAllCopies` (a deleted target has nothing to delete, a missing permission is `RemovalReport.failure`). `setTarget` keeps its own check, because a target that can't be written is handled differently there (its copies stay and the switch goes ahead); a `SecurityException` there still ends in `saveFailed`, with the work rescheduled. | `UserActions`; `UserActionsTest.aRevokedPermissionComesBackAsARemovalFailureNotAnException`, `removingAllCopiesFromADeletedTargetLetsTheEngineDecide` |
| SHOULD-FIX-2: repeated `runNow` builds a chain | Accepted, with a different mechanism. The decision is now a pure `WorkManagerSyncScheduler.planNow` (JVM-tested). While a run is going on, the first request is appended as its follow-up and later ones coalesce into it. A FULL request turns a non-FULL follow-up into FULL in place with `WorkManager.updateWork`, which keeps its place in the chain. The suggested fix cancels the blocked leaf instead. As far as WorkManager's `EnqueueRunnable` reads, `APPEND_OR_REPLACE` behind a cancelled leaf deletes every spec of the unique name, the running one included, and starts a new chain that no longer waits for the running sync. With device test F4, "now" requests are never delayed any more: a non-FULL request within 60 s of a successful run starts no run ("Synced N s ago"). | `WorkManagerSyncScheduler`, `SyncScheduler.recentSuccessAgo`; `NowPlanTest` |
| SHOULD-FIX-3: "Sign in again" from Sources | Accepted. `AppNavigator.onSetupProgress` returns from a re-auth login to the screen that asked for it. `SourcesViewModel.onResume` (from a `LifecycleResumeEffect`) reloads a list that failed with `SIGN_IN`. | `Navigation.kt`, `SourcesScreen`/`SourcesViewModel`; `NavigationTest`, `SetupViewModelsTest.sourcesReloadWhenShownAgainAfterSigningIn` |
| SHOULD-FIX-4: Settings opens after a denial in the dialog | Accepted. Both requests remember whether a rationale was due before the launch (`rememberSaveable`, so rotation keeps it). They open Settings only if there was none before and none after, i.e. Android showed no dialog. Not unit-tested (Compose and activity APIs). Remaining gap: a first-ever dialog dismissed without an answer also looks like that. | `ui/common/Permissions.kt` |
| NIT-1: `installUuid` reads `store.data` | Accepted: `safeData()`. | `AndroidStores.kt` |
| NIT-2: eager `noBackupFilesDir`; JSON string | First part accepted: the file is created lazily inside the IO blocks. The store now takes the folder and the key holder (`CredentialKeys`) as parameters, so a JVM test covers it with a software key. That test also checks that sign-out deletes the key alias (device test, step 6). Second part not changed: the password is a `String` in `Credentials` anyway, so dropping the intermediate JSON string gains nothing. | `KeystoreCredentialStore`; `KeystoreCredentialStoreTest` |
