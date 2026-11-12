# Developing TT Calendar Sync

This page is for contributors: what the project is made of, how it works, and how to build, test
and change it. The user guide is the [README](../README.md).

Other documents:

| Document | What's in it |
|---|---|
| [DESIGN.md](DESIGN.md) | The design record: the v1 plan with the reasons behind each decision; later fixes were folded in. Look there for the details of the API, the mapping rules, the sync algorithm and the device checklist. |
| [DEVICE-TESTS.md](DEVICE-TESTS.md) | What was tested on a real phone, the bugs found and how they were fixed. |
| [RELEASING.md](RELEASING.md) | Signing key, release build, checks, GitHub release, Obtainium. |
| [LICENSES.md](LICENSES.md) | The app's license and every third-party dependency with its license. |
| [reviews/](reviews/) | The design review and the code reviews of each step, each with its resolution. |

## Overview

A single-module Android app (`:app`) written in Kotlin with Jetpack Compose, package
`de.tergelius.ttsync`. There is no server and no Google API:

- it reads TimeTree's **unofficial web API** (`https://timetreeapp.com/api/v1`) with OkHttp, the same
  API TimeTree's website uses;
- it writes the copies into the phone's calendar provider (`CalendarContract`) **without** the
  sync-adapter flag, so Android's Google Calendar sync uploads them like events the user created;
- WorkManager runs the sync in the background, by default every hour.

## Key invariants

Every change must keep these. Most of them have tests that fail if they break.

1. **Never touch a row without our marker.** A copy's description ends with the line
   `[ttsync:<TimeTree uuid>]`. The app updates or deletes a calendar row only by its `_ID`, only after
   reading it back and checking that it is in the target calendar, not deleted, and that its last
   description line is exactly the expected marker. Each write in the `applyBatch` carries that check
   as a `withExpectedCount(1)` precondition. Inserts go only into the target calendar. No update or
   delete ever uses a selection over the whole table. (DESIGN.md section 5, rules R1–R5;
   `calendar/GatewayRules.kt`; `sync/SyncEngineSafetyTest.kt`.)
2. **One way.** TimeTree → calendar only. The app never writes to TimeTree. An edit to a copy is
   overwritten when the TimeTree event changes; a deleted copy is restored.
3. **Read-only, polite API use.** The only non-GET request is the sign-in. At least 1 s between
   requests, at most one re-login per run, back-off on rate limits, and the interval is never shorter
   than 1 hour (that was promised to calendar owners). Don't add features that write to TimeTree or
   poll more often.
4. **Delete only what is proven gone.** Sync deletes a copy only if TimeTree marked the event deleted,
   or if a *complete* (FULL) read of an active calendar no longer contains it, or if the event is
   live but the calendar's filter (0.2.0) excludes it, and only inside the 30-day window. Copies of
   paused calendars (access lost), out-of-window copies and marked rows the app doesn't know are
   left alone. Only explicit user actions (Remove all copies, sign-out with
   removal, changing the target) delete every marked row.
5. **No secrets or personal data in logs, reports or the repo.** Logs carry counts, HTTP status,
   error codes and 8-character uuid prefixes only. Raw API dumps exist only in debug builds and never
   go into git (see [Raw dumps and fixtures](#raw-dumps-and-fixtures)).
6. **A pure core.** `timetree`, `mapping` and `sync/SyncEngine.kt` don't import `android.*`, so plain
   JVM tests cover them.

## Repository layout

```text
app/
  build.gradle.kts            dependencies, versionName, release signing from Gradle properties
  proguard-rules.pro          R8 rules for the release build
  src/main/kotlin/…/ttsync/   the app (packages below)
  src/main/res/               strings (values/ = English, values-de/ = German), icons, backup rules
  src/debug/                  debug-only code: DebugReceiver, raw API dump
  src/release/                release counterpart of the debug wiring (no dumps)
  src/test/                   JVM unit tests and JSON fixtures
  src/testDebug/              unit tests of debug-only code
docs/                         this page, DESIGN, DEVICE-TESTS, RELEASING, LICENSES, reviews/
scripts/device-verify.sh      read-only check of the target calendar on a device (adb)
scripts/release-check.sh      checks a release APK before publishing
tools/tt-probe.py             read-only TimeTree API probe that captures JSON for fixtures
gradle/libs.versions.toml     version catalog
```

## Architecture

Packages under `de.tergelius.ttsync`:

| Package | What it does | Main classes |
|---|---|---|
| `timetree` | Read-only client for TimeTree's web API: sign-in, calendars, labels, the paged event feed; errors; request pacing | `TimeTreeClient`, `OkHttpTimeTreeClient`, `EventFeed`, `Dtos`, `TtError`, `RequestPacer`, `RawResponseSink` |
| `mapping` | Pure functions: TimeTree event → desired calendar row (`EventSpec`), time zones, all-day dates, recurrences, edited occurrences, the marker, the content hash | `EventMapper`, `EventSpec`, `RecurrenceNormalizer`, `OverrideResolver`, `TimeRules`, `Marker`, `SpecHash` |
| `store` | The app's own sync state in SQLite (framework, no Room): `snapshot`, `mapping`, `cursor`, `meta` | `SyncDatabase`, `SyncDb`, `SqlSyncDatabase`, `SyncSchema`, `SqlConnection` |
| `calendar` | The only code that touches `CalendarContract`; enforces the marker rules | `CalendarGateway`, `ProviderCalendarGateway`, `GatewayRules` |
| `sync` | The sync algorithm, the report, WorkManager scheduling and the worker, notifications | `SyncEngine`, `SyncReport`, `SyncScheduler`, `WorkManagerSyncScheduler`, `SyncWorker`, `SyncNotifier` |
| `settings` | User choices and status in DataStore; encrypted credentials (Android Keystore, AES-256-GCM); the TimeTree session | `SettingsRepository`, `StatusRepository`, `AndroidStores`, `CredentialStore`, `KeystoreCredentialStore`, `StoredSessionStore` |
| `ui` | Compose screens and ViewModels: `login`, `sources`, `target`, `reliability` (keep-running step and checklist), `status`, `settings`, `about`, plus `common` and `theme` | `MainViewModel`, `AppViewModelFactory`, `UserActions`, `DeviceChecks` |
| `debug` (`src/debug` only) | adb hooks for testing; not in release builds | `DebugReceiver` |
| root | Application, manual dependency injection (no Hilt), activity, Navigation 3 setup, logging | `App`, `AppGraph`, `MainActivity`, `Navigation`, `NavigationKeys`, `TtLog` |

### Data flow of one sync run

```text
SyncWorker (WorkManager: "ttsync-periodic" or "ttsync-now")
  └─ SyncEngine.run()
       1. preconditions: calendar permission, target calendar unchanged and writable, credentials
       2. TimeTreeClient: GET /calendars, labels, then each calendar's event feed
       3. store: snapshot of every live event (raw JSON) per calendar
       4. EventMapper: desired EventSpec (or Absent) per event, inside the 30-day window
       5. diff: SpecHash against the mapping table → insert / verified update / verified delete
          (adoption scan first in FULL runs, after a crash, or with an empty mapping table)
       6. CalendarGateway.applyBatch in chunks of 50, then mappings in one SQLite transaction
       7. cursor (high-water mark), SyncReport, status; requestSync as a best effort
  └─ Android's Google Calendar sync adapter uploads the rows to the Google account
```

**FULL and INCREMENTAL.** TimeTree's event feed is ordered newest change first and pages backwards.
A FULL run reads the whole feed, so it is the only run that can see a deletion (a missing event).
An INCREMENTAL run reads from the top down to the calendar's high-water mark minus 2 minutes. A
calendar whose feed had at most 3 pages at its last FULL runs FULL every time; a larger one runs
INCREMENTAL with a FULL at least every 6 hours (`SMALL_FEED_PAGES`, `FULL_INTERVAL_MILLIS` in
`SyncEngine.kt`). The engine logs which mode it picked and why.

**Idempotence.** A run that finds nothing changed in TimeTree writes nothing. A crash leaves at most
one marked row without a mapping, which the next run adopts. The cursor only moves after the
writes succeeded. Bumping `EventMapper.VERSION` rewrites every copy once.

**UI.** `MainViewModel` picks the start screen from the setup progress: Login → Sources → Target →
Keep running → Status. `Navigation.kt` holds the Navigation 3 back stack. ViewModels come from
`AppViewModelFactory` (manual DI from `AppGraph`) and call `UserActions` for multi-step actions such as
sign-out or a target change, so every screen runs those steps in the same order.

**Storage.** `SyncDb` (SQLite, schema version 3) holds snapshots, mappings, cursors and meta
values. DataStore holds settings and status. Credentials are encrypted into
`noBackupFilesDir/credentials.enc`. Backups are off for the whole app.

## Tech stack

| Part | Version |
|---|---|
| Android | minSdk 26 (Android 8.0), compileSdk and targetSdk 37 |
| Build | Gradle 9.9.1 (wrapper), Android Gradle Plugin 9.5.1, Java 17 toolchain |
| Language | Kotlin 2.3.20, Compose compiler and kotlinx.serialization plugins |
| UI | Compose BOM 2026.03.01 (UI 1.10.6, Material 3 1.4.0), Activity 1.13.0, Lifecycle 2.10.0, Navigation 3 1.0.1 |
| Core | AndroidX Core 1.18.0, kotlinx.coroutines 1.10.2 |
| Network and JSON | OkHttp 5.5.1, kotlinx.serialization JSON 1.11.0 |
| Background and storage | WorkManager 2.12.0, DataStore Preferences 1.3.0, framework SQLite |
| Tests | JUnit 4.13.2, kotlinx-coroutines-test 1.10.2, OkHttp MockWebServer 3 5.5.1, sqlite-jdbc 3.53.4.0 |

All versions live in [`gradle/libs.versions.toml`](../gradle/libs.versions.toml). Deliberately left
out: Room (four tables don't justify annotation processing), Hilt (manual DI in `AppGraph`), Tink
and security-crypto (one encrypted blob, done with the Keystore directly). DESIGN.md section 2 has
the reasons.

## Build

You need:

- a **JDK 17 or newer** to run Gradle (JDK 21 works). The code compiles with a Java 17 toolchain;
  if no JDK 17 is installed, Gradle downloads one (foojay toolchain resolver);
- the **Android SDK** with `platforms;android-37` and `build-tools;37.0.0`, installed with Android
  Studio, `sdkmanager` or the `android` CLI. Point Gradle at it with `ANDROID_HOME` or with
  `sdk.dir=…` in `local.properties` (gitignored; Android Studio writes it).

```sh
export ANDROID_HOME=~/Android/Sdk     # wherever your SDK is

./gradlew assembleDebug               # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest           # all JVM unit tests
./gradlew lintDebug                   # Android lint
./gradlew assembleDebug testDebugUnitTest lintDebug   # the check to run before every commit
./gradlew testDebugUnitTest --tests 'de.tergelius.ttsync.mapping.*'   # one package
./gradlew installDebug                # onto the connected device
```

`assembleRelease` builds an unsigned, R8-minified APK unless the signing properties are set; see
[RELEASING.md](RELEASING.md). Debug and release builds share the application id
`de.tergelius.ttsync` but are signed with different keys, so one can't update the other. Uninstall
first; the copies stay in the calendar and the new install adopts them by their marker.

Android Studio works too: open the repository root and run the `app` configuration.

## Tests

The unit tests run on the JVM, without an emulator:

| Area | Tests | How |
|---|---|---|
| `timetree` | sign-in, cookies, paging, error mapping, pacing, DTO decoding | OkHttp `mockwebserver3`, JSON fixtures in `src/test/resources/fixtures/timetree/` |
| `mapping` | every mapping rule (DESIGN.md M1–M20), recurrences, edited occurrences, hash stability | synthetic fixtures and anonymized real dumps in `fixtures/mapping/` |
| `store` | schema, migrations, every store query | the same SQL on `sqlite-jdbc` through `JdbcSqlConnection` |
| `calendar` | "is this row ours", target calendar checks | `GatewayRules` is pure Kotlin |
| `sync` | the engine (DESIGN.md E1–E15), safety, scheduling plans, reports | `FakeCalendarGateway` with foreign rows in the target calendar, sharing `GatewayRules` with the real gateway |
| `settings`, `ui` | credential store logic, ViewModels, user actions, checklist | fakes from `ui/UiFakes.kt` |

There are no instrumented tests (`androidTest`) yet. The calendar provider, WorkManager and the
Keystore are covered by the device checklist instead (next sections).

When you change sync or calendar code, add a test that proves foreign rows in the target calendar
stay byte-identical (see `SyncEngineSafetyTest`).

## Debug builds

Debug builds add two things that release builds never contain (`scripts/release-check.sh` checks
the release APK for them):

**`DebugReceiver`** lets adb start runs. It is guarded by `android.permission.DUMP`, so only the
adb shell can reach it. Each command logs one `debug <ACTION>: …` line, and the run then logs
`run started` and `run finished`:

```sh
R=de.tergelius.ttsync/.debug.DebugReceiver
adb shell am broadcast -a de.tergelius.ttsync.debug.SYNC_NOW -n $R                 # like "Sync now"
adb shell am broadcast -a de.tergelius.ttsync.debug.SYNC_NOW --ez full true -n $R  # forced FULL
adb shell am broadcast -a de.tergelius.ttsync.debug.FULL_SYNC -n $R                # same as above
adb shell am broadcast -a de.tergelius.ttsync.debug.DROP_MAPPINGS -n $R            # next run must adopt
adb logcat -s TtSync
```

**Raw API dumps.** Every data response of a run (never the sign-in, never headers) is written to
`files/debug/raw/<run>/<NNN>_<name>.json` in the app's private storage; the last 5 runs are kept, and
sign-out deletes them. Read them with `run-as`:

```sh
adb shell run-as de.tergelius.ttsync ls -R files/debug/raw
adb exec-out run-as de.tergelius.ttsync cat files/debug/raw/<run>/<file>.json > page.json
```

### Raw dumps and fixtures

Raw dumps contain **personal data**: event titles, notes, locations, user ids and names. Never
commit them, and never paste them into issues. `tools/out/` and `**/fixtures/real/` are gitignored
for that reason.

To turn real API responses into test fixtures:

1. Capture them, either from a debug build (above) or with the probe:
   `python3 tools/tt-probe.py`. It asks for the TimeTree email and password (neither is printed or
   stored), signs in, lets you pick one calendar and saves its calendar list, labels and event pages
   to `tools/out/<time>/` (mode 700). It only reads and waits between requests. Use a test calendar
   and don't run it in a loop.
2. Anonymize a copy: replace every title, note, location and URL with neutral text, and every id,
   uuid and user id with placeholders, keeping the structure, timestamps and recurrence lines. See
   `app/src/test/resources/fixtures/mapping/real-20261112/` for the result.
3. Put only the anonymized files under `app/src/test/resources/fixtures/` and check the diff before
   committing.

## Testing on a device

Unit tests can't cover the calendar provider, Google's sync adapter, WorkManager on a real phone or
OEM battery managers. Before a release, run the device checklist in DESIGN.md section 10 (steps
0–14). [DEVICE-TESTS.md](DEVICE-TESTS.md) records the runs so far and what is still untested.

- Use a test TimeTree calendar and a separate Google calendar while developing.
- Sign in on the phone itself. Never send the TimeTree password over adb.
- `scripts/device-verify.sh` is a read-only check of the target calendar. It never writes to the
  device and prints no titles, notes or locations:

  ```sh
  ADB='adb -s <serial>' scripts/device-verify.sh -l             # list calendars and their ids
  ADB='adb -s <serial>' scripts/device-verify.sh <calendar_id>  # our rows, duplicates, others
  ```

  It lists the app's rows (times, recurrence, sync state, marker), duplicate markers, and a
  fingerprint of all other rows. Run it before and after a sync: the same fingerprint means the
  user's own events are unchanged. `ADB` can be any adb prefix, for example
  `ADB='ssh <host> adb -s <serial>'` for a phone connected to another machine.

## Code style

- Kotlin official code style (`kotlin.code.style=official`), formatted like ktfmt's Google style:
  2-space indentation, about 100 columns, trailing commas. No formatter runs in the build, so match
  the surrounding code.
- Keep `android.*` out of `timetree`, `mapping` and `SyncEngine`. Put Android-specific code behind an
  interface (`CalendarGateway`, `SettingsRepository`, `SyncNotifier`, …) so tests can use fakes.
- Every UI text goes into `res/values/strings.xml` **and** `res/values-de/strings.xml`; nothing is
  hard-coded.
- KDoc on classes and non-obvious functions; reference the DESIGN.md section a rule comes from.
- Logs: counts, codes and uuid prefixes only. Release builds keep only warnings and errors.

## Contributing

Issues and pull requests are welcome at <https://github.com/tylerstyle/ttsync>.

- **Bug reports:** phone model, Android version, app version, the message on Status, and what you
  expected. Never post passwords, cookies, raw dumps or private event details.
- **Pull requests:** keep them small and focused. Run
  `./gradlew assembleDebug testDebugUnitTest lintDebug`, add or update tests, update the README for
  user-visible changes and both string files for new texts. Say whether and how you tested on a
  device. Changes to the sync engine or the calendar gateway need a test that shows foreign rows
  stay untouched.
- **Scope:** the app stays one-way and read-only towards TimeTree, gentle on TimeTree's servers and
  free of servers, accounts and tracking. Features that write to TimeTree, poll more often or work
  around TimeTree's limits won't be merged.
- **License:** the project is licensed under the [Apache License 2.0](../LICENSE). By submitting a
  contribution you agree that it is licensed under the same terms (section 5 of the license), and you
  confirm that you have the right to submit it. There is no separate contributor agreement. New
  dependencies must have a license compatible with Apache-2.0; add them to
  [LICENSES.md](LICENSES.md).
- TT Calendar Sync is not affiliated with TimeTree, Inc. Don't use TimeTree's logo or branding in
  the app or the repository.
