# Review of TT Calendar Sync Design (v1)

**Reviewer:** android-agy (Antigravity / agy)  
**Date:** 2026-11-12  
**Target Document:** `ttsync/docs/DESIGN.md` (2026-11-12)  
**Inputs Evaluated:**
- `notes/cal-claude-ondevice-answers.md` (TimeTree web API specifics, owner promises)
- `notes/android-ondevice-research.md` (CalendarContract, sync adapter mechanics, background execution, distribution)
- `notes/timetree-device-inventory.md` (Target test environment: OnePlus 8 Pro, LineageOS 24 / Android 17)

---

## Executive Summary

The overall architecture in `DESIGN.md` is well-reasoned and makes sensible platform choices:
- Bypassing Google Calendar REST/OAuth in favor of `CalendarContract` with Google's native sync adapter avoids developer API verification hurdles and token expiration issues.
- Direct AES-GCM via `AndroidKeyStore` avoids large dependencies (Tink) or deprecated libraries (`security-crypto`).
- Using a clean marker in `DESCRIPTION` combined with an internal SQLite mapping table (`SyncDb`) provides solid idempotency and recovery semantics.
- Hard rules R1–R5 establish a strong baseline for protecting foreign events in the target calendar.

However, the design contains **4 Blockers** that will cause runtime data corruption, deleted events, or sync failures, followed by **8 Should-Fix** items and **4 Nits**. These must be resolved before phase 1–4 coding begins.

---

## Findings Ranked by Severity

### 1. Blockers

#### [BLOCKER-1] Invalid RFC 5545 DURATION syntax for timed recurring events (`P<seconds>S` vs `PT<seconds>S`)
- **DESIGN.md Section:** Section 4 ("Event mapping", line 119)
- **Problem:**  
  The table specifies: `DURATION: recurring only: P<seconds>S, or P<days>D for all-day`.  
  Under RFC 5545 §3.3.6 (and RFC 2445), time components (hours, minutes, seconds) **must** be preceded by the `T` designator (e.g. `PT3600S`, `PT1H`). A string like `P3600S` without `T` is syntactically invalid.  
  Android’s internal recurrence parser (`com.android.calendarcommon2.Duration` / `EventRecurrence`) and Google Calendar’s sync parser strictly require `PT<seconds>S` or `PT<hours>H<minutes>M<seconds>S`. Writing `P<seconds>S` will trigger an `InvalidFormatException` in the provider or cause recurrence expansion failure in `CalendarProvider2`, breaking recurring timed events completely.
- **Concrete Fix:**  
  Update Section 4, line 119 to:  
  `DURATION: recurring only: PT<seconds>S (e.g. PT3600S), or P<days>D for all-day`.

---

#### [BLOCKER-2] Adoption Scan deletes paused calendar copies and out-of-window events as "dead UUIDs"
- **DESIGN.md Section:** Section 5 ("Sync algorithm", Step 6 Adoption scan, lines 188–192, and Section 3 line 98)
- **Problem:**  
  Step 6 specifies:
  > Adoption scan (in FULL, after a crash, or with an empty mapping table). Query `calendar_id=target AND deleted=0 AND description LIKE '%[ttsync:%'` and parse the last line.
  > - Live uuid: adopt the row.
  > - Duplicates: keep the lowest `_ID` and delete the rest.
  > - Dead uuid: delete the row.

  This creates two severe failure modes:
  1. **Paused Calendars (`AccessLost`):** When calendar access is lost, Section 3 line 98 and Section 5 line 216 explicitly mandate: *"Pause that calendar and stop all its requests. Keep its copies."* During a subsequent FULL reconcile, events belonging to that paused calendar are not fetched. The Adoption scan iterates over all rows in `calendar_id=target`, flags those UUIDs as "Dead uuid", and deletes them from Google Calendar. This directly violates the guarantee made to the user.
  2. **Historical Events (>30 days old):** Section 1 line 17 specifies: *"Copy only events that end on or after today − 30 days. Older events are never written or touched."* Events older than 30 days are omitted from the active fetch/desired spec. The Adoption scan will classify every marked historical row in Google Calendar as a "Dead uuid" and delete it.
- **Concrete Fix:**  
  1. **Remove row deletion from Step 6 entirely.** The Adoption scan must be purely an *adoption* pass: if a row has a valid `[ttsync:<uuid>]` marker and that UUID is present in the current live fetch without an existing mapping, adopt it into `mapping`.
  2. Deletions must be handled exclusively in the reconciliation diff (Step 5), and **only** for UUIDs that:
     - Were previously recorded in `mapping` for a currently active (non-paused) calendar;
     - Are confirmed absent from that active calendar's complete `since=0` fetch;
     - Have an event end date on or after `today - 30 days`.

---

#### [BLOCKER-3] `mapping` table lacks date/end time, making out-of-window mapping checks impossible during FULL reconcile
- **DESIGN.md Section:** Section 2 ("SyncDb tables", line 68) & Section 5 ("Sync algorithm", lines 186–187)
- **Problem:**  
  Line 187 states: *"Mappings outside the window are never touched."*  
  Line 186 states: *"delete mappings whose uuid is missing from the complete fetch."*  
  However, the `mapping` table schema is defined as:  
  `mapping(uuid PK, tt_cal_id, target_cal_id, event_id, spec_hash)`.  
  It contains no date or timestamp column. When an event naturally ages past the 30-day window, `EventMapper` excludes it from the desired spec. When `SyncEngine` performs a FULL diff, it cannot tell whether an entry in `mapping` is missing because it was deleted in TimeTree or because it ended 31 days ago. Querying `CalendarContract.Events` for every mapping to check its date is slow and fragile (and fails if the event was already deleted).
- **Concrete Fix:**  
  Add `end_at INTEGER NOT NULL` to the `mapping` table schema:  
  `mapping(uuid PK, tt_cal_id, target_cal_id, event_id, spec_hash, end_at)`  
  During the diff in Step 5, `SyncEngine` can directly check `WHERE end_at < (today - 30 days)` in SQLite. Any mapping outside the window is ignored and neither updated nor deleted.

---

#### [BLOCKER-4] Broken heuristic in `OverrideResolver` for recurring overrides and missing deleted-occurrence handling
- **DESIGN.md Section:** Section 4 ("Edited occurrences", lines 145–159)
- **Problem:**  
  `OverrideResolver` relies on a heuristic:
  > It expands the parent without its EXDATEs (lib-recur, in the parent's wall-clock time) within ±60 days of the override and picks the nearest instance. Then:
  > - If the parent already excludes that instance, nothing changes.
  > - If an EXDATE within ±60 days isn't claimed by another override, the occurrence counts as moved, and nothing changes.
  > - Otherwise the parent spec gets an EXDATE for that instance.

  This introduces two critical flaws:
  1. **Wrong Instance Exclusion on Moves:** For daily recurring events, or weekly events with multiple days (e.g. `BYDAY=MO,WE,FR`), picking the "nearest instance" within ±60 days will latch onto the wrong occurrence. For example, if a Monday occurrence is rescheduled to Thursday, the nearest instance in a Mon/Wed/Fri series is Wednesday. `OverrideResolver` will exclude Wednesday's regular meeting and leave Monday's meeting active, creating a ghost instance on Monday and deleting an innocent occurrence on Wednesday.
  2. **Deleted Occurrences Ignored:** When an occurrence is deleted in TimeTree, it arrives as a tombstone (`deactivated_at != null`, `recurring_uuid = <parent>`). Section 4 line 109 states: *"events with deactivated_at set map to null."* Because `OverrideResolver` only processes live overrides, deleted occurrences are completely dropped. If TimeTree does not natively add an EXDATE to the parent, the deleted occurrence will continue to appear in Google Calendar indefinitely.
- **Concrete Fix:**  
  1. Check real TimeTree Web API payloads (per Question 3). TimeForest observed that TimeTree Web **natively appends the `EXDATE` to the parent event** for both rescheduled and deleted occurrences. If the parent already contains the correct `EXDATE`, do not perform any fuzzy expansion. The parent maps with its native `EXDATE`, and the live override maps as a standalone event.
  2. If TimeTree Web does not append the `EXDATE`, synthetic EXDATE generation must never use a ±60-day nearest-instance distance heuristic. Instead, match occurrences strictly by the original instance start time or sequence identifier provided by TimeTree API DTOs, and ensure deactivated occurrences (`deactivated_at != null`) with `recurring_uuid` are also routed to `OverrideResolver` so their corresponding instance is excluded from the parent.

---

### 2. Should-Fix Issues

#### [SHOULD-FIX-1] Missing initial sync after setup completion
- **DESIGN.md Section:** Section 7 ("UI", line 244) & Section 9 ("Background", line 277)
- **Problem:**  
  Periodic work is enqueued with `KEEP` at app start (line 277). In WorkManager, enqueuing a `PeriodicWorkRequest` with `KEEP` does not run immediately; the first execution occurs only after the full interval (1 hour) has elapsed. A user who completes Login → Sources → Target will be left looking at an empty Google Calendar and will assume the app failed.
- **Concrete Fix:**  
  In Section 7 (Target screen completion), explicitly trigger an immediate one-time sync (`SyncWorker` via `OneTimeWorkRequestBuilder` / `ttsync-now` expedited work) upon confirming the target calendar, and navigate to Status.

---

#### [SHOULD-FIX-2] Missing Google Account Sync & Master Sync verification
- **DESIGN.md Section:** Section 5 (Step 1 Preconditions, lines 170–171) & Section 7 (Reliability checklist, lines 247–251)
- **Problem:**  
  Step 1 verifies `SYNC_EVENTS = 1` on the `Calendars` table row. However, it does not check:
  - System-wide Master Auto-Sync: `ContentResolver.getMasterSyncAutomatically()`
  - Account Calendar Sync: `ContentResolver.getSyncAutomatically(account, "com.android.calendar")`
  If either toggle is disabled in Android settings, CalendarProvider writes the dirty rows to SQLite locally, but Google’s sync adapter will never upload them to Google servers.
- **Concrete Fix:**  
  1. In Step 1 Preconditions, check `ContentResolver.getMasterSyncAutomatically()` and `ContentResolver.getSyncAutomatically(targetAccount, "com.android.calendar")`. If disabled, record a specific actionable warning.
  2. In Section 7, add a "Google Calendar Sync" row with a fix button (launching `Settings.ACTION_SYNC_SETTINGS`) to the Reliability checklist.

---

#### [SHOULD-FIX-3] Missing `WRITE_SYNC_SETTINGS` permission in `AndroidManifest.xml`
- **DESIGN.md Section:** Section 8 ("Permissions and manifest", lines 261–263)
- **Problem:**  
  Section 2 line 50 and Section 5 line 195 call `ContentResolver.requestSync(account, "com.android.calendar", extras)` with `SYNC_EXTRAS_MANUAL` and `SYNC_EXTRAS_EXPEDITED`. In addition, reading or verifying sync status requires sync settings permissions. On several Android versions and OEM builds, invoking `requestSync` with manual flags or checking sync status without `android.permission.WRITE_SYNC_SETTINGS` and `READ_SYNC_SETTINGS` can fail silently or throw a `SecurityException`.
- **Concrete Fix:**  
  Add `android.permission.WRITE_SYNC_SETTINGS` and `android.permission.READ_SYNC_SETTINGS` to the manifest permissions list in Section 8. Both are normal protection permissions that require no user prompt.

---

#### [SHOULD-FIX-4] `EVENT_END_TIMEZONE` handling for all-day events
- **DESIGN.md Section:** Section 4 ("Event mapping", line 121)
- **Problem:**  
  Line 121 states: `EVENT_END_TIMEZONE: end_timezone if it is valid and differs; else null`.  
  For all-day events, `EVENT_TIMEZONE` is set to `"UTC"`. If TimeTree sends an `end_timezone` (e.g. `"Europe/Berlin"`), line 121 would write `EVENT_END_TIMEZONE = "Europe/Berlin"`. Under Android `CalendarContract` specifications, all-day events must have their start and end points at UTC midnight and their timezone strictly set to UTC. Writing a non-UTC `EVENT_END_TIMEZONE` on an all-day event can corrupt instance generation in `CalendarProvider2`.
- **Concrete Fix:**  
  Update Section 4 line 121 to explicitly state:  
  `EVENT_END_TIMEZONE: for timed events, end_timezone if valid and differs from start_timezone; for all-day events, always null`.

---

#### [SHOULD-FIX-5] Recurrence updates must explicitly clear fields (`DURATION` vs `DTEND`)
- **DESIGN.md Section:** Section 4 (lines 118–119) & Section 5 (R1, line 198)
- **Problem:**  
  When an existing event in TimeTree changes from recurring to non-recurring (or vice versa), updating the row via `ContentValues` without explicitly setting the opposing fields to `null` leaves stale data in SQLite. If a recurring event becomes a single event, leaving `RRULE` or `DURATION` populated while setting `DTEND` creates an invalid hybrid row that crashes or confuses calendar clients.
- **Concrete Fix:**  
  In `EventMapper` and `ProviderCalendarGateway`, specify that:
  - Non-recurring events must explicitly set `DURATION = null`, `RRULE = null`, `RDATE = null`, `EXDATE = null`.
  - Recurring events must explicitly set `DTEND = null`.

---

#### [SHOULD-FIX-6] Stripping prefixes for `EXDATE` and `RDATE` columns
- **DESIGN.md Section:** Section 4 ("Recurrences", lines 141–142)
- **Problem:**  
  Line 135 explicitly specifies removing the `RRULE:` prefix, but line 141 states only: *"EXDATE and RDATE: all lines go into one sorted, deduplicated, comma-separated column."*  
  In RFC 5545 and TimeTree API DTOs, lines include prefixes such as `EXDATE:20261112T100000Z` or `EXDATE;TZID=...:20261112T100000`. Storing the `EXDATE:` prefix inside the SQLite `exdate` column corrupts the provider’s recurrence parser.
- **Concrete Fix:**  
  Explicitly specify in Section 4 that `EXDATE:` / `EXDATE;...:` and `RDATE:` / `RDATE;...:` prefixes must be stripped, and any `;TZID=` parameters parsed and normalized to UTC format before comma-separating into the database columns.

---

#### [SHOULD-FIX-7] Failure notification limitation when worker is killed by OEM battery savers
- **DESIGN.md Section:** Section 9 ("Background", lines 282–283) & Section 7 (Reliability checklist)
- **Problem:**  
  Section 9 states: *"Failure notification. Appears after 3 failed runs in a row, or when the last success is over 24 h old."*  
  If an aggressive OEM battery killer (e.g. Samsung "Sleeping apps" or Xiaomi MIUI killing apps without Autostart) prevents WorkManager jobs from running, `SyncWorker` is never invoked. Consequently, no code runs to detect the 24-hour timeout or post the notification.
- **Concrete Fix:**  
  1. Note in Section 9 that the 24-hour failure notification can only be triggered when `SyncWorker` executes or when the user launches the app.
  2. In Section 7, elevate the "Battery Unrestricted" item in the Reliability checklist to a prominent setup recommendation rather than an optional checklist item.
  3. For Xiaomi devices (`Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true)`), explicitly guide the user to enable "Background Autostart" in MIUI/HyperOS App Info, as WorkManager periodic work will not fire without it.

---

#### [SHOULD-FIX-8] All-day recurring duration calculation formula
- **DESIGN.md Section:** Section 4 ("Event mapping", line 119)
- **Problem:**  
  `DURATION` for recurring all-day events is specified as `P<days>D`. For a single-day all-day event in TimeTree, `start_at == end_at` (both representing the same UTC date). A naive calculation of `endDate - startDate` yields 0 days (`P0D`), which is invalid under RFC 5545 and rejected by Android.
- **Concrete Fix:**  
  Explicitly specify the duration formula in Section 4:  
  `days = ChronoUnit.DAYS.between(startDate, endDate) + 1`  
  (A single-day all-day recurring event must always have `DURATION = P1D`).

---

### 3. Nits and Minor Improvements

#### [NIT-1] Clarify "Never update or delete by selection" (R2) vs `applyBatch` preconditions
- **DESIGN.md Section:** Section 5 ("Hard rules", R2, line 202)
- **Observation:**  
  R2 states: *"Never update or delete by selection."* The intention is to avoid broad selection queries like `contentResolver.delete(CONTENT_URI, "calendar_id=?", ...)`. However, in `ProviderCalendarGateway.applyBatch()`, attaching a selection clause (`withSelection(Events._ID + "=? AND " + Events.CALENDAR_ID + "=? AND " + Events.DESCRIPTION + " LIKE ?", ...)`) alongside `withExpectedCount(1)` on an ID-targeted URI (`ContentUris.withAppendedId`) provides atomic verification against race conditions.
- **Concrete Fix:**  
  Clarify R2: *"Never update or delete by table-wide selection queries. All modifications must target specific `Events._ID` URIs; selection arguments should only be used as precondition checks within `ContentProviderOperation`."*

---

#### [NIT-2] Handling target calendar deletion on Google Calendar web
- **DESIGN.md Section:** Section 5 (Step 1 Preconditions, line 170) & Section 7 (Status screen)
- **Observation:**  
  If the user deletes the target calendar on calendar.google.com, Google sync removes the local calendar row. Step 1 halts sync. The Status screen should handle this gracefully.
- **Concrete Fix:**  
  Add an explicit UI state on the Status screen: when the target calendar `_ID` is missing from `CalendarContract.Calendars`, display "Target calendar was deleted on Google Calendar" with a one-tap button to open the Target selection screen.

---

#### [NIT-3] Premature inclusion of `lib-recur` before verifying TimeTree web API fixtures
- **DESIGN.md Section:** Section 2 (Dependencies, line 61) & Section 12 (Phase 3)
- **Observation:**  
  `org.dmfs:lib-recur 0.17.1` is added solely for override fuzzy matching. If TimeTree Web API already includes parent `EXDATE`s (as documented in TimeForest), full RRULE expansion on the client is unnecessary for v1.
- **Concrete Fix:**  
  Capture real TimeTree fixture data (Question 3) before locking in the `lib-recur` dependency. If native `EXDATE`s are present, drop `lib-recur` to reduce APK footprint and complexity.

---

#### [NIT-4] Explicit AES-GCM KeyStore IV storage specification
- **DESIGN.md Section:** Section 6 ("Credentials and privacy", lines 222–225)
- **Observation:**  
  When using raw `AndroidKeyStore` AES-256-GCM, the 12-byte initialization vector (IV) generated by `Cipher.init` must be stored alongside the ciphertext.
- **Concrete Fix:**  
  Specify in Section 6 that `credentials.enc` format is `[12-byte IV][Ciphertext + 16-byte GCM Tag]` to prevent implementation ambiguity.

---

## Conclusion & Next Steps

Once the 4 Blockers and key Should-Fix items are adjusted in the design:
1. `ttsync/docs/DESIGN.md` will provide a completely watertight blueprint.
2. Phase 1 (Foundation and interfaces) can proceed with zero risk of architectural rework in mapping, database schema, or background sync.
