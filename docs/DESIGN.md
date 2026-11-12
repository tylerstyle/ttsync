# TT Calendar Sync: design record (v1)

> **This is the design record**, not the starting point. For an overview of the code, how to build
> and test it and how to contribute, read [DEVELOPMENT.md](DEVELOPMENT.md). The user guide is the
> [README](../README.md).

This document was written as the v1 implementation plan and records why things are the way they are.
Later fixes were folded in (device test results in [DEVICE-TESTS.md](DEVICE-TESTS.md)), but class
lists and phase notes reflect the plan: where the code differs in naming, the code wins. Version
0.2.0 added event filters per calendar (section 4a).

Status: plan of 2026-11-12, revised the same day after the design review
([reviews/design-agy.md](reviews/design-agy.md), outcomes in section 14). Everything here was decided
for v1, except the items marked **decided by fixtures** and the questions in section 13.
Decisions by the project owner: the app is named "TT Calendar Sync", the license is Apache-2.0
(`LICENSE`), copies are kept when access is lost, and `ttsync/` is its own git repo, published at
<https://github.com/tylerstyle/ttsync>.
Sources: the project's own research notes on TimeTree's web API, `CalendarContract`, background
execution and release options (not published), the inventory of the test phone, and the public
projects eoleedi/TimeTree-Exporter, yehzu/timetree-sync and TimeForest (`docs/internals.md`).

## 1. Scope

v1 does:
- Sign in to TimeTree with email and password. The web API is unofficial, and the app only reads it.
- Copy events one way from the chosen TimeTree calendars into one writable Google calendar on the phone.
  Writes go through `CalendarContract` without the sync-adapter flag, so Google's sync uploads them.
- Handle timed, all-day, multi-day and recurring events, edited occurrences and deletions. Skip
  birthdays (`type 1`) and memos (`category 2`).
- Copy only events that end on or after today − 30 days. Older events are never written or touched.
- Sync every hour. Reconcile fully on every run for a small calendar, otherwise at least every 6 h
  (section 5). "Sync now" runs at any time.
- Show a status screen, a failure notification and a reliability checklist. Sign-out can remove the copies.
- English (default) and German.
- Since 0.2.0: per selected calendar, an optional filter that copies only some of its events, by
  label, attendee or words (section 4a). No filter copies everything, as before.

Not in v1:
- Writing to TimeTree.
- Social-login accounts (the README has a workaround), cookie paste, WebView login, CSRF fallback.
- Comments, attachments, reminders. Attendees and colours are not written to the copies (since
  0.2.0 labels and attendees can choose which events are copied).
- Filters (0.2.0): no Google event colours, no virtual members (child, pet, car, other) as
  attendees (no sample data), no patterns or regular expressions.
- Non-Google target calendars, OAuth/REST, any server, root, accessibility or notification capture.
- Two-way sync: an edit to a copy lasts until the TimeTree event changes.

## 2. Architecture

Packages under `de.tergelius.ttsync`. Keep the template's `MainActivity`, `ui/theme` and Nav3. Delete
`data/DataRepository.kt`, `ui/main/*` and their tests.

| Package | Classes |
|---|---|
| `timetree` | `TimeTreeClient`, `@Serializable` DTOs, `TtError`, `RequestPacer`, `RawResponseSink` |
| `mapping` | `EventMapper`, `EventSpec`, `RecurrenceNormalizer`, `OverrideResolver`, `Marker`, `SpecHash`; 0.2.0: `EventFilter`, `LabelNames` |
| `sync` | `SyncEngine`, `SyncReport`, `SyncWorker`, `SyncScheduler`, `SyncNotifier` |
| `store` | `SyncDb` (framework `SQLiteOpenHelper`), `SnapshotStore`, `MappingStore` |
| `calendar` | `CalendarGateway`, `ProviderCalendarGateway`; `FakeCalendarGateway` (tests) |
| `settings` | `SettingsRepository`, `StatusRepository` (DataStore), `CredentialStore` |
| `ui` | `login`, `sources` (0.2.0: with the filter editor), `target`, `status`, `reliability`, `settings`, `about` + ViewModels |
| root | `App` (notification channels), `AppGraph` (manual DI, no Hilt) |

`timetree`, `mapping` and `SyncEngine` don't import `android.*`, so plain JVM tests cover them.

Data flow: `SyncWorker` → `SyncEngine.run()` → `TimeTreeClient` → `SnapshotStore` (each live event's
JSON) → `EventMapper` (desired `EventSpec` per event) → hash diff against `MappingStore` →
`CalendarGateway` (verified writes) → mappings, cursor, status →
`requestSync(account, "com.android.calendar", EXPEDITED)`.

Threading: `CoroutineWorker` on `Dispatchers.IO`. A process-wide `Mutex` keeps the periodic run and
"Sync now" apart. The UI collects DataStore and `WorkInfo` flows.

| Dependency | Version | Why |
|---|---|---|
| OkHttp | 5.5.1 | Timeouts, debug-dump interceptor, `mockwebserver3` tests. Rejected: `HttpURLConnection` (clumsy). |
| kotlinx-serialization-json | 1.11.0 | The plugin is already there. 1.11 is built on Kotlin 2.3.20; 1.12+ needs Kotlin 2.4.10. |
| work-runtime | 2.12.0 | Periodic work, `CoroutineWorker` included. |
| datastore-preferences | 1.3.0 | Settings and status as Flows. |
| org.dmfs:lib-recur | 0.17.1 | **Optional, decided by fixtures** (section 4, edited occurrences). Add it only if the real dumps need RRULE expansion, e.g. for the end of a COUNT series. Not in phase 1. |

Room is out: KSP 2.3.10+ handles AGP 9's built-in Kotlin, but 2.3.13 has a concurrency regression, and
four tables don't justify annotation processing. Tink is out too (see section 6).

`SyncDb` tables:
- `snapshot(uuid PK, tt_cal_id, updated_at, deactivated, json)`: live events, plus tombstones of
  deactivated occurrences that have a `recurring_uuid` (section 4, edited occurrences).
- `mapping(uuid PK, tt_cal_id, target_cal_id, event_id, spec_hash, end_at, written_at)`: `end_at` is
  the last written end of the copy in UTC ms. For a series it is the end of its last instance, or
  `Long.MAX_VALUE` if the series is open-ended or its end is unknown. Every window check uses `end_at`.
  `written_at` is when the app last wrote the row (null if unknown); it dates the upload check
  (section 5, step 1).
- `cursor(tt_cal_id PK, high_water, last_full_at, state, labels_json, last_full_pages, filter_hash)`:
  `high_water` is the calendar's high-water mark (section 3, Events); `last_full_pages` is the size of
  its feed at the last FULL, which picks FULL or INCREMENTAL (section 5). Schema 2 replaced schema
  1's `since`, the server's cursor, which the upgrade drops (null forces one FULL). Schema 3 (0.2.0)
  adds `filter_hash`, the fingerprint of the filter the copies follow (null: no filter).
- `meta(key PK, value)`

## 3. TimeTree client

All requests go to `https://timetreeapp.com/api/v1` with the headers `X-Timetreea: web/2.1.0/en` and
`Content-Type: application/json`. The User-Agent is OkHttp's default.

- **Login.** `PUT /auth/email/signin {uid, password, uuid}`.
  - The `uuid` is 32 hex characters, generated once per install and renewed on sign-out.
  - The session is the `_session_id` cookie. The client sends it as a manual `Cookie` header and saves
    any new value a response sets.
- **Calendars.** `GET /calendars?since=0` on every run. This also shows when access is gone.
- **Labels.** `GET /calendar/{id}/labels`, cached and refreshed on a full reconcile or when a label is
  unknown.
- **Events.** `GET /calendar/{id}/events/sync[?since=N]`. Device test 2026-11-12: the feed is ordered
  by `updated_at`, **newest change first**, and pages *backwards*. A request without `since` returns
  the newest page; its `since` is the page's oldest `updated_at` − 1 and asks for the next older page;
  `chunk == false` marks the oldest page. `?since=<last since>` returns nothing new, so `since` is never
  stored. Every read starts at the top of the feed:
  - FULL reads every page. If that took more than one page, it reads the top once more, down to 2 min
    below the newest change it saw, so an event edited during the read (it moves to the top, possibly
    out of a page not read yet) does not look deleted.
  - INCREMENTAL stops after the first page that holds an event with `updated_at` ≤ the calendar's
    high-water mark − 2 min. The high-water mark is the largest `max(updated_at, deactivated_at)` of all
    events of the complete fetches so far (0 after a FULL of an empty calendar). Re-reading a few known
    events is harmless: the hash diff writes nothing for them. If the feed turns out not to be ordered
    newest first, the read goes on to the end.
  - A uuid seen twice keeps its newest version. Fail with `ApiChanged` if a cursor repeats or after 500
    pages. Never return partial lists.
- **Re-login.** Only after a 401 on a data call or a 403 on `/calendars`. At most one login and one
  retry per run.
- **Pacing.** At least 1 s between requests. "Sync now" within 60 s of a successful run starts no run
  (Status says "Synced N s ago") instead of a delayed one, which can wait half an hour while the app
  is in the background (device test F4). An hourly run is 1 request plus, per calendar, the labels and
  its pages when it is small (a FULL, section 5), or 1 page when it is larger (more only after many
  changes). Timeouts: connect 15 s, read 30 s, call 60 s.

| Signal | Error | Reaction |
|---|---|---|
| login `-702` | `AuthRejected` | Wrong password or a temporary block. No automatic login for 6 h. A second one in a row stops automatic logins until the user signs in. Notify. |
| `-495`, HTTP 429 | `RateLimited` | Retry after at least 30 min, or after `Retry-After`. |
| 401 right after login | `AuthRejected` | As above. |
| calendar missing or deactivated, or 403/404 on its events | `AccessLost` | Pause that calendar and stop all its requests. Keep its copies (user decision). Notify. |
| 5xx, IO error, timeout | `Transient` | Retry. |
| unexpected JSON | `ApiChanged` | Fail and notify "update the app". |

Debug dump: the `src/debug` `RawResponseSink` writes each response body to `files/debug/raw/<run>/`
(never login, never headers) and keeps 5 runs. Read it with `adb shell run-as de.tergelius.ttsync`. In
release builds it does nothing.

## 4. Event mapping

`EventMapper` is pure: (snapshots, labels, target, today) → `Map<uuid, Desired>`, where `Desired` is one of:
- `Write(spec: EventSpec, endAt: Long)`: the row must exist with exactly this content.
- `Absent`: the row must not exist. Used for birthdays, memos, events with `deactivated_at` set and
  (0.2.0) live events that the calendar's filter excludes, which the filter checks last.

Events outside the window (`endAt` before today − 30 days) are not in the map at all, so they are never
touched.

| Column | Value |
|---|---|
| `TITLE` | `title`, or `(TimeTree)` if it is blank |
| `EVENT_LOCATION` | `location`, or null |
| `DESCRIPTION` | `note`, then `Label: <name>` (0.2.0: an unnamed label by the English name of its colour), then `URL: <url>`; last line `[ttsync:<uuid>]` |
| `DTSTART` | timed: `start_at`; all-day: UTC midnight of the start date |
| `DTEND` | timed: `max(end_at, start_at)`; all-day: UTC midnight of the inclusive end date + 1 day; recurring: null |
| `DURATION` | recurring only: `P<seconds>S` for timed events; `P<days>D` for all-day, with `days = DAYS.between(startDate, inclusiveEndDate) + 1` (a one-day event is `P1D`) |
| `EVENT_TIMEZONE` | timed: `start_timezone` if it is a valid `ZoneId`, else `UTC`; all-day: `UTC` |
| `EVENT_END_TIMEZONE` | timed: `end_timezone` if it is valid and differs from the start zone, else null; all-day: always null |
| `ALL_DAY`, `CALENDAR_ID` | 0/1; the target calendar |
| `RRULE`, `RDATE`, `EXDATE` | recurring: see below; single: null |

`P<seconds>S` without the `T` is deliberate. Android's `calendarcommon2` Duration parser skips `T`,
and Google's own sync writes durations in this form. Device checklist step 0 confirms the form on the phone.

**Explicit clearing.** Every `EventSpec` holds all the managed columns from the table above, and the
gateway writes a null wherever a value is missing. A single event sets `DURATION`, `RRULE`, `RDATE` and
`EXDATE` to null. A recurring event sets `DTEND` to null. A change between single and recurring therefore
leaves no stale fields behind.

Never written:
- Reminders. TimeTree's alerts belong to the owner; the Google calendar's default notifications apply.
- Attendees. Google would send invitations.
- ExtendedProperties, sync columns, `CUSTOM_APP_*`, `UID_2445`, colours.

**All-day dates.** Use the UTC date of the timestamp. If it isn't at a UTC midnight, use the date in
`start_timezone` instead. The end is inclusive, so a one-day event has `start_at == end_at`, and the end
date is never earlier than the start date.

**Recurrences**:
- `RRULE`:
  - Remove the `RRULE:` prefix.
  - Timed events: a date-only `UNTIL` becomes 23:59:59 in `start_timezone`, written in UTC with `Z`. A
    local `UNTIL` without `Z` is converted to UTC.
  - All-day events: `UNTIL` becomes a date only.
  - `COUNT` together with `UNTIL`: drop `COUNT`.
  - Keep only the first RRULE line.
- `EXDATE` and `RDATE`:
  - Strip the property name and its parameters up to the first `:`. That covers both `EXDATE:…` and
    `EXDATE;TZID=Europe/Berlin:…`, and the same forms for `RDATE`.
  - Read each value in the zone its `TZID` names (`start_timezone` when there is none). A trailing `Z`
    means UTC, and `VALUE=DATE` means a date.
  - Timed values become UTC `yyyyMMdd'T'HHmmss'Z'`. All-day values become `yyyyMMdd`; check that form
    on the device.
  - Merge all lines into one sorted, deduplicated, comma-separated column. The column never contains a
    prefix or a `TZID`.
- Other lines: dropped and counted in `SyncReport`.
- `endAt` for the window and for `mapping.end_at`: a single event uses its end. A series with `UNTIL`
  uses its last possible end (`UNTIL` + duration). A series that is open-ended or uses `COUNT` gets
  `Long.MAX_VALUE`, unless lib-recur is adopted.

**Edited occurrences (final rule decided by fixtures).** An event with `recurring_uuid` or `parent_id`
is an override. A live override is written as a single event with no RRULE. Sources disagree on whether
TimeTree also adds an EXDATE to the parent: TimeForest saw TimeTree Web add it for both moved and deleted
occurrences, while yehzu found it missing.

`OverrideResolver` receives the parent, its live overrides, and its tombstones (deactivated occurrences
with a `recurring_uuid`). Those tombstones are kept in `snapshot`. The resolver applies these rules in order:
1. **Native EXDATE first.** The parent's own EXDATE values (normalized as above) are always used as they are.
2. **Exact match only.** If an override or tombstone has a field with the original instance start, and
   the parent doesn't already exclude that exact instant, add an EXDATE for that instant. Which field
   holds it, if any, is decided by fixtures.
3. **No guessing.** Otherwise add no EXDATE: there is no nearest-instance heuristic. Count the case in
   `SyncReport.unmatchedOverrides` (shown on Status), since it can leave a duplicate or a stale occurrence.

The raw API dumps of the test data settle which rule applies. The test data, created in a test
TimeTree calendar: a weekly series with one moved and one deleted occurrence, a two-day all-day event,
and an event with a label, note, URL and location. Phase 3 finalizes this rule from those dumps.

Further handling:
- If the parent is missing, the override stays standalone.
- A changed override or tombstone marks its parent as affected.

Rejected: `CONTENT_EXCEPTION_URI`, which needs the same original-instance time and ties rows to the
parent's `_ID`.

**Hash.** SHA-256 over `MAPPER_VERSION` and the sorted `EventSpec` fields. Write only when it changes.
Bumping the version rewrites every row once.

## 4a. Filters (0.2.0)

Each selected TimeTree calendar can have a filter that decides which of its events are copied
(`mapping/EventFilter`, stored as `SourceCalendar.filter`). No filter, or one with nothing set,
copies every event, as 0.1.x did. The owner decided the rules on 2026-11-12.

**Criteria.**
- **Labels:** the event's `label_id` is one of the chosen labels. An event without a label never
  matches a label filter.
- **Attendees:** one of the event's `attendees` (user ids) is one of the chosen members. TimeTree
  calls them "Attendees" on an event and "Members" on the calendar (`calendar_users`, embedded in
  `GET /calendars`, so no extra request).
- **Words:** "Only if it contains …" and "Skip if it contains …", each a list of terms (separated
  by commas in the UI). A term matches if the title, the note or the location contains it,
  ignoring case; both sides are NFC-normalized, terms are trimmed and blank ones dropped.

**Combining.** An empty criterion restricts nothing. Criteria combine with AND, the values within
one criterion with OR (any of the labels, any of the members, any of the words). A skip word always
wins.

**Labels by id, never by name.** Users rename labels, and a rename changes only the label's `name`
(API check of 2026-11-12: ids, colours and the events' `label_id` and `updated_at` stay the same),
so a filter survives it. The UI always shows the current name. TimeTree shows an unnamed label
(`name: ""`) by the name of its colour, keyed by colour, not by id; labels can be recoloured, so
`LabelNames` reads the colour from the label (`color`, else `default_color`) and knows TimeTree's 25
colour names (bug B3):
- the filter editor: the name, else the colour's name in the phone's language (English or German,
  as TimeTree's German app: the ten default colours keep their English names), else "Label <id>";
- a copy's `Label:` line: the name, else the **English** colour name, else no line. English on
  purpose, so the copy and its hash don't depend on the phone's language. A renamed label reaches
  the copies with their next FULL run, because a rename doesn't change the events.

Relabelling an event, unlike renaming a label, is an edit of the event: it bumps the event's
`updated_at` (device run 5), so an INCREMENTAL run sees it, and the copy appears or goes with the
next run.

**Where the filter applies.** The mapper checks it last, after tombstones, the window, deactivated
events, birthdays and memos, so those behave exactly as without a filter. A live, in-window event
the filter excludes is `Absent` and counted as `filtered` (`SyncCounts.filtered`, "Filtered N" on
Status).

**Edited occurrences.** Every event is judged on its own fields: an edited occurrence (override) has
its own label, attendees and title.
- The series matches, the occurrence doesn't: that occurrence is simply missing. The series is
  resolved as for any edited occurrence (section 4): its EXDATE removes the original date, and a
  filtered override still counts towards `unmatchedOverrides` when it can't be matched.
- The series doesn't match, the occurrence does: only that occurrence is copied, as a standalone
  event, like an override whose parent is missing.

**Changing a filter.**
- The settings hold the filter; the sync state holds only its fingerprint (`cursor.filter_hash`, a
  SHA-256 of a canonical form), so no search terms are stored in clear text in SQLite.
- A different fingerprint makes that calendar's next run FULL ("filter changed"): copies of newly
  excluded events must go and newly included events must be copied, whether they changed or not.
  The hash is stored with the cursor only after that calendar's writes succeeded, so a failed run
  tries FULL again. Saving a changed filter also starts a FULL "Sync now". An equivalent filter
  (same ids, the same terms in another order) forces nothing.
- Before saving a filter that would delete copies, Sources asks "N copies no longer match and will
  be removed from <target>." N comes from `SyncEngine.filterImpact`: read-only, it maps the stored
  snapshots with the new filter and counts the in-window mapped copies in the current target that
  it would exclude (unmapped leftovers are not counted). There is no question when N is 0, and the
  question is merged with the one for deselected calendars.
- A filtered copy is deleted by the diff like any `Absent` event: only if it is mapped, in the window
  and its calendar is active (section 5, step 5). A paused calendar's copies are never touched by
  its filter. A copy that ended before the window stays.

**Leftover cleanup.** After a reinstall or lost mappings, the copies of excluded events have no
mapping, so the diff can't delete them. The adoption scan (section 5, step 6) deletes such a marked
row with an R1-verified delete when its uuid is live, in the window and excluded by the filter of
its active calendar. Rows of unknown, dead, paused or out-of-window uuids are still left alone. This
changes the BLOCKER-2 decision of the design review (section 14); the owner approved it on
2026-11-12.

**Lenient decoding.** The fields only the filters use (a label's colours and order, a calendar's
members, an event's attendees and author) decode leniently: a value that doesn't decode becomes
null, and a list keeps the elements that do. A strict field in an unexpected shape would mark the
event as malformed and skip it for good, or fail `GET /calendars` with `ApiChanged` so that no run
succeeds; neither is acceptable for data the copies hardly depend on. Labels cached by 0.1.x
(`cursor.labels_json`) have no colours and decode too; the next FULL refreshes them.

**Storage and downgrade.** A null filter is not written, so an unfiltered selection is stored byte
for byte as in 0.1.x, and 0.1.x settings read as unfiltered. 0.1.x ignores the `filter` key, so going
back to 0.1.x drops the filters and copies every event again.

## 5. Sync algorithm

`FULL` runs on a calendar's first sync, after a mapper version bump, after the selection changes,
after its filter changes (0.2.0, `cursor.filter_hash`), and on "Full resync". Beyond that, the size of its feed at the last FULL decides (`cursor.last_full_pages`,
the first pass only). Deleted events seem to vanish from the feed instead of coming back as tombstones
(step 3), so only a FULL sees a deletion:
- **Small calendar**, at most `SMALL_FEED_PAGES` = 3 pages: every run is FULL. That is a few requests
  per hour, and deletions reach Google within the sync interval, like every other change.
- **Larger calendar**: runs are `INCREMENTAL`, with a FULL at least every `FULL_INTERVAL` = 6 h. A
  deletion takes up to 6 h.

The engine logs the mode and why, e.g. `calendar 1: FULL (small feed, pages=1)` or
`calendar 1: INCREMENTAL (last FULL 42 min ago, pages=5)`. Both thresholds are constants in
`DefaultSyncEngine`.

1. **Preconditions.**
   - The app has calendar permission.
   - The target calendar still has the same `_ID`, `ACCOUNT_NAME` and `ACCOUNT_TYPE=com.google`, with
     access ≥ 500 and `SYNC_EVENTS=1`.
   - Credentials are stored.

   If one fails, stop with an error the user must act on. A missing target calendar (for example, deleted
   on calendar.google.com) gets its own Status state, "Target calendar was deleted", with a button to the
   Target screen.

   Also check `ContentResolver.getMasterSyncAutomatically()` and
   `getSyncAutomatically(account, "com.android.calendar")`. If either is off, the run continues: rows are
   written and upload once sync is back on. Status shows the warning "Google sync is off, copies stay on
   this phone", and the checklist marks the item. Since Android 13 the account check answers "off" (and
   `requestSync` does nothing) for an account the app cannot see, and the Google account usually is not
   visible to the app (device test B2). So the account switch counts only if the account is in
   `AccountManager.getAccountsByType("com.google")`; otherwise it is unknown and gives no warning.
   Instead, at its end (after step 8, also when a later step failed) the run counts the mapped copies
   written more than 30 min ago (`mapping.written_at`) that are still `dirty = 1`; counted at the end,
   copies the run deleted or rewrote do not count as stale. If there are any (and sync is not known to
   be off), Status warns "N changes not uploaded to Google yet" and the checklist marks the item. A
   provider that does not show `dirty` gives no signal and no failure.
2. **Crash check.** If `meta.run_in_progress` is still set, this run includes the adoption scan (step
   6) even when it is INCREMENTAL. Then set the flag.
3. **Fetch.** Call `GET /calendars` and mark lost calendars. Fetch each active calendar's feed from the
   top: INCREMENTAL down to its high-water mark (section 3), everything in FULL. No high-water mark
   means FULL. Store live events as snapshots, keep tombstones of occurrences that have a
   `recurring_uuid`, and drop all other deleted or skipped events. Not settled yet: whether a deleted
   event shows up in the feed as a tombstone (`deactivated_at`). In the device test the FULL after a
   deletion fetched 7 of the 8 events, so the deleted one was apparently left out. Without a tombstone,
   a deletion arrives with the next FULL (see above: within the sync interval for a small calendar,
   within 6 h otherwise).
4. **Affected events.** INCREMENTAL: the changed uuids and the parents of changed overrides and
   tombstones. FULL: everything in that calendar.
5. **Diff.** This is the only step that deletes during sync. "In window" means `mapping.end_at` ≥ today −
   30 days, and "active" means the TimeTree calendar is not paused.
   - `Write`, no mapping: adopt a marked row from the step 6 index, otherwise insert.
   - `Write`, mapping with a different hash: verified update.
   - `Absent`, mapping in window, calendar active: verified delete.
   - FULL only: a mapping of an active calendar whose uuid is missing from that calendar's complete
     fetch, and which is in window, gets a verified delete (an INCREMENTAL fetch never proves absence).
     FULL also checks that each in-window mapped row still exists and is ours, and re-adopts or
     re-inserts it if not.
   - Never touched: uuids not in the `Desired` map (out of window), mappings with an old `end_at`, and
     anything belonging to a paused calendar.
6. **Adoption scan** (in FULL, after a crash, or with an empty mapping table). It runs after mapping
   and before the diff's writes, and it builds the marker index the diff uses. This step only adopts.
   Query `calendar_id=target AND deleted=0 AND description LIKE '%[ttsync:%'` and parse the last line.
   - The uuid is `Write` in an active calendar and has no mapping: adopt the row with `spec_hash=""`, so
     the diff brings it up to date with one verified update.
   - Several rows for a uuid that is live and active: keep the lowest `_ID` and delete the others with
     verified deletes.
   - 0.2.0: an unmapped row of a uuid that is live and in window but excluded by the filter of its
     active calendar is deleted with a verified delete (leftovers after a reinstall or lost mappings;
     a mapped one is deleted by the diff).
   - Any other marked row (unknown, dead, paused or out-of-window uuid) is left alone and counted in
     `SyncReport.unmanagedMarked`.
7. **Apply** with `applyBatch` in chunks of 50. Each chunk's mappings go in one SQLite transaction.
8. **Finish.** Advance a calendar's cursor (its high-water mark) only after its writes succeeded. Clear
   the flag, save the report, and call `requestSync` as a best effort only (see step 1).

**Hard rules** (in `ProviderCalendarGateway`):
- **R1.** Update or delete only by `_ID`, and only after reading the row and checking `calendar_id ==
  target`, `deleted == 0`, and the last description line `[ttsync:<expected uuid>]`. If a check fails,
  write nothing and send the uuid back through adopt-or-insert. This covers `_ID`s reused after a
  storage reset and markers removed by users.
- **R2.** Never update or delete with a selection over the whole table. Every operation targets
  `ContentUris.withAppendedId(Events.CONTENT_URI, id)`. A selection is used only as a precondition:
  `_id=? AND calendar_id=? AND deleted=0 AND description LIKE '%[ttsync:<uuid>]'` with
  `withExpectedCount(1)`. If a precondition fails, the whole chunk aborts. The engine then replays that
  chunk one operation at a time, and the failing operation goes back through adopt-or-insert.
- **R3.** Insert only into the target calendar, after the step 1 checks.
- **R4.** Never use `CALLER_IS_SYNCADAPTER`. Never write Attendees, Reminders, ExtendedProperties or
  Calendars.
- **R5.** Every query for our rows is limited to `calendar_id = target`.

Known v1 behaviour (device test): TimeTree's default labels have empty names (the app shows them
localized), so in 0.1.x they added no `Label:` line; only renamed labels did. Since 0.2.0 (B3) an
unnamed label adds the English name of its colour, as TimeTree shows it. Adoption rewrites each adopted row
once (`spec_hash=""`), which means one upload per row even when nothing changed.

This makes a run idempotent: if nothing changed in TimeTree, it writes nothing. A crash leaves at most
one marked row without a mapping, which the next run adopts. The cursor never moves past data that wasn't
written.

User actions:
- **Changing the target calendar.** After a confirm, delete our rows in the old calendar (R1), clear the
  mappings, then run FULL. `calendar_id` is never changed in place.
- **Deselecting a TimeTree calendar.** After a confirm, delete its copies, snapshots, mappings and cursor.
- **Losing access.** Pause the calendar, keep its copies, and notify with a "Remove copies" action. When
  no calendar is left, cancel the periodic work.
- **Deleting a copy in Google.** It comes back on the next FULL. The README says to deselect instead.
- **Explicit removals.** "Remove all copies", removal at sign-out, and changing the target are the only
  actions that also delete unmapped marked rows. Each one deletes every row in that calendar that
  carries our marker, using R1 with the uuid read from the marker, and only after the user confirms.

## 6. Credentials and privacy

- **CredentialStore.** A non-exportable AES-256-GCM AndroidKeyStore key without user authentication
  (background runs need it) encrypts `{email, password, sessionId}` into
  `noBackupFilesDir/credentials.enc`, laid out as `[12-byte IV][ciphertext + 16-byte GCM tag]` and
  written with `AtomicFile`. If decryption fails, wipe and ask for sign-in. Rejected: Tink
  (about 1 MB for one blob), security-crypto (deprecated).
- **Password.** Stored so the app can sign in again when the ~14-day session ends; About says so.
- **Backup.** `allowBackup="false"`, and both backup XML files exclude everything.
- **Sign-out.** Asks whether to remove the copies. Then it cancels work and deletes the key, credentials,
  DataStore, `SyncDb` and the debug files.
- **Logs.** Only counts, HTTP status, error codes and 8-character uuid prefixes; never credentials,
  cookies or event content. R8 strips `Log.v/d/i` from release builds.
- **Raw dumps.** Debug builds only, the last 5 runs, wiped on sign-out.

## 7. UI

Compose with Nav3. The start screen follows setup progress: Login → Sources → Target → Keep running →
Status.

1. **Login.** Shows these errors: rejected/blocked, rate-limited, offline. Hint: "Google/Apple sign-in
   won't work. Add a password in TimeTree or use a separate invited account."
2. **Sources.** Checkboxes from `/calendars`, with the note "Only pick calendars whose owner agreed."
   0.2.0: each selected calendar that TimeTree still lists has a line "Events: All ›", or its
   filter's summary such as "Events: 2 labels · 1 person · 3 words · skips 1 word". It opens the
   **filter editor** (section 4a) in place of the list, titled "Choose events":
   - a short explanation: an empty section restricts nothing, sections combine (an event must
     match all of them), and a word from "Skip if it contains" always wins;
   - **Labels:** checkboxes in TimeTree's display order (`order`), each with a colour dot and the
     current display name. The dot's accessibility description is the colour's name, unless the row
     already shows it;
   - **Attendees:** the calendar's members as checkboxes. A member who left the calendar is listed
     only while the filter selects them, marked "Left the calendar"; so is an id TimeTree no
     longer lists, so nothing in a filter is hidden;
   - **Words:** "Only if it contains" and "Skip if it contains", several words separated by
     commas, with a hint that they are searched in title, note and location, ignoring case;
   - **Clear** (back to all events) and **Done**. Done and Back return to the list and keep the
     edit as a draft; it is stored with the list's Save (Next during setup), so a filter can be
     set before the first sync. A stored filter is kept unless the user edits it.

   Save asks once for everything that would delete copies: the deselected calendars and "N copies
   no longer match and will be removed from <target>." Nothing is asked when no copy would go.
3. **Target.** Requests the calendar permission. Lists `com.google` calendars with access ≥ 500 and
   `SYNC_EVENTS=1`, each with "N other events stay untouched". Recommends a dedicated calendar; Refresh
   button. **Confirming the target enqueues an immediate FULL sync (`ttsync-now`) and the periodic work.**
   Periodic work enqueued with `KEEP` only runs after a full interval.
4. **Keep running** (a setup step, not an optional extra):
   - Asks for "battery unrestricted" (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`).
   - On Xiaomi, Redmi and POCO (`Build.MANUFACTURER`), it also asks for **Autostart**. It tries the
     MIUI/HyperOS autostart screen by its component and falls back to app details, because periodic work
     never fires without Autostart there.
   - The user can skip, but Status then shows a warning.
5. **Status** (home):
   - The state: OK, running, needs attention, paused, or "Target calendar was deleted" (with a button
     to Target).
   - Last successful sync, shown in red when it is more than 24 h ago, and the next run
     (`WorkInfo.nextScheduleTimeMillis`).
   - Counts: added, updated, deleted, skipped, plus `unmatchedOverrides`; 0.2.0: "Filtered N" when
     filters left out events. The line "Copying … into …" marks filtered calendars, e.g.
     "Family (filtered)".
   - Warnings: Google sync is off; N changes not uploaded to Google yet (section 5, step 1); battery is
     not unrestricted.
   - The error, with a fix button.
   - Sync now.
6. **Reliability**, one ✓/✗ row with a fix button each:
   - calendar permission;
   - notifications;
   - **Google Calendar sync**: master sync and the account's calendar sync, or, when Android hides
     the account's switch, whether the copies get uploaded (section 5, step 1); fixed through
     `Settings.ACTION_SYNC_SETTINGS`;
   - battery unrestricted;
   - Autostart (Xiaomi family only);
   - background restriction;
   - "Pause app activity if unused" (`IntentCompat.createManageUnusedAppRestrictionsIntent`);
   - `dontkillmyapp.com/<manufacturer>`;
   - standby bucket (diagnosis only).
7. **Settings**: interval 1 h (default), 3 h or 6 h, never shorter, as owners were promised; Sources
   (the list marks filtered calendars);
   Target; Full resync; Remove all copies; Sign out. The adaptive FULL (section 5) keeps that promise:
   it never adds runs, and a run that reads a small calendar completely costs a few requests, the
   same order as an incremental one.
8. **About/privacy.** Data flow, no server or analytics, password storage, unofficial-API disclaimer,
   version, certificate SHA-256, source, license.

Strings: English in `values/` is the default, German goes in `values-de/`. No UI text is hard-coded.
AGP generates a locale config from these folders (`generateLocaleConfig`; `res/resources.properties`
names the language of `values/`), so on Android 13+ the user can pick the app's language under
Settings → App languages.

## 8. Permissions and manifest

- **Permissions:** `INTERNET`; `READ_CALENDAR` and `WRITE_CALENDAR` (asked on Target);
  `POST_NOTIFICATIONS` (API 33+, asked on Status); `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`;
  `FOREGROUND_SERVICE_DATA_SYNC`; `READ_SYNC_SETTINGS` (for the master and account sync checks);
  `WRITE_SYNC_SETTINGS`, declared defensively for `requestSync` on OEM builds. Both sync permissions are
  normal permissions with no prompt, and the app never changes sync settings itself.
- **Foreground service type.** WorkManager's `SystemForegroundService` gets
  `foregroundServiceType="dataSync"`, because expedited work on API 26–30 runs as a foreground service.
- **From WorkManager.** Network-state, wake-lock, boot and FGS permissions, and rescheduling after a
  reboot. No boot receiver of our own.
- **Exported components.** Only the launcher. The debug manifest adds `debug.DebugReceiver`, guarded by
  `android.permission.DUMP` so only adb shell can reach it. Its actions are `SYNC_NOW`, `FULL_SYNC` and
  `DROP_MAPPINGS`.
- **Notification channels.** `sync_problems` and `sync_running`.

## 9. Background

- **Periodic work** (`ttsync-periodic`). Runs at the set interval with 15 min flex, needs
  `NetworkType.CONNECTED`, and backs off exponentially from 15 min. Enqueued with `UPDATE` when settings
  change and `KEEP` at app start. Each run is FULL or INCREMENTAL per calendar (section 5): a small
  calendar is read completely every time, a larger one at least every 6 h.
- **Sync now** (`ttsync-now`). Expedited, falling back to a normal request, never delayed (section 3,
  Pacing). While a run is going on, the first request becomes its one follow-up and later ones merge
  into it (FULL wins). `getForegroundInfo()` shows `sync_running`.
- **Retries.** Transient and rate-limit errors retry while `runAttemptCount < 3`. Errors the user must
  fix fail and notify at once.
- **Failure notification.** Appears after 3 failed runs in a row, or when the last success is over 24 h
  old. There is only ever one, and the next success clears it.
  - *Limitation:* the check runs only when a worker runs or the user opens the app. If an OEM battery
    killer stops the jobs, no code runs and no notification appears.
  - *Mitigation:* the "Keep running" setup step, Xiaomi Autostart, and the red "last success" on Status.
    The README explains this.
- **First sync.** Confirming the target, or changing sources or target, enqueues `ttsync-now` as FULL.
  The user doesn't wait an hour.

## 10. Testing

JVM tests read fixtures from `app/src/test/resources/fixtures/`: real debug dumps, with personal text
replaced.

Mapping:
- M1: timed event in Europe/Berlin. M2: a DST change, and a different end time zone.
- M3–M5: all-day 1-day, all-day 3-day, `start_at` not at midnight. All of them have
  `EVENT_END_TIMEZONE` null.
- M6: date-only UNTIL in winter/summer (`225959Z`/`215959Z`).
- M7–M9: local UNTIL; COUNT with UNTIL; EXDATE/RDATE lines in the forms `EXDATE:…Z`, `EXDATE;TZID=…:…`
  and `VALUE=DATE` (no prefix or TZID survives).
- M10: yearly all-day with `start_at == end_at` gives `P1D`; a 2-day series gives `P2D`.
- M11–M14, from the real fixtures:
  - parent with a native EXDATE for a moved occurrence and for a deleted one;
  - override carrying an original-start field, if one exists (exact EXDATE);
  - override with neither, so no EXDATE and `unmatchedOverrides` = 1;
  - deactivated override tombstone;
  - missing parent.
- M15: birthday, memo, deactivated. M16: blank fields, label, fake marker in a note.
- M17: invalid time zone. M18: out of window, and `endAt` for UNTIL, COUNT and open series.
- M19: hash stability and version bump.
- M20: a switch from single to recurring and back writes explicit nulls.

Client (mockwebserver3):
- C1: login request and cookie, stable uuid. C2–C5: one page; FULL paging backwards plus the second
  pass from the top; INCREMENTAL from the top, stopping at a known event; a feed out of order; repeated
  cursor; page cap.
- C6: error mid-paging returns no list. C7: `-702`, `-495`, 429 with `Retry-After`, one re-login on 401.
- C8: cookie rotation.

Engine (a fake gateway with 7 foreign rows in the target calendar):
- E1: first sync; the foreign rows stay byte-identical. E2: a second run writes nothing.
- E3–E4: edit, delete. E5: a reused `_ID` on a foreign row stays untouched.
- E6: wiped mappings get adopted. E7: crash between insert and mapping.
- E8: duplicate markers. Extras are deleted only when the uuid is live and active.
- E9: target change. E10: deselect.
- E11: lost access. A FULL run plus an adoption scan delete nothing from the paused calendar.
- E12: a failed run keeps the cursor.
- Incremental after FULL picks up an edit, a new event and a tombstone; it stops after the first page
  when the top of the feed is known; an event edited during a FULL read is not deleted.
- Adaptive FULL: a small feed is FULL on every run (a deletion arrives with the next run), a larger
  one INCREMENTAL with a FULL after 6 h; the recorded feed size switches the mode.
- E13: a mapping with an old `end_at` that is missing from the full fetch stays untouched.
- E14: marked rows with unknown or out-of-window uuids stay untouched, even in a FULL run on an empty
  mapping table.
- E15: a failed `withExpectedCount` precondition aborts the chunk, which is replayed one operation at a time.

Filters (0.2.0, section 4a):
- F1 (`EventFilterTest`): each criterion, AND between criteria and OR within one, skip wins, case
  and Unicode normalization; the fingerprint ignores order and normalization; no search terms in
  `toString` or the fingerprint.
- F2 (`FilterMappingTest`): excluded events are `Absent`, counted and listed; tombstones, the
  window, birthdays and memos behave as without a filter; the override rule both ways; a filtered
  override still counts for its series.
- F3 (`LabelNamesTest`, `LabelColorStringsTest`): TimeTree's 25 colours; the copy's line uses the
  name, else the English colour name; every colour has an English and a German string.
- F4 (`StoreCodecTest`, `SqlSyncDatabaseTest`): 0.1.x settings and reports decode, and unfiltered
  sources stay byte-identical; schema 2 → 3 keeps every row and forces no FULL.
- F5 (`SyncEngineFilterTest`): narrowing runs FULL once and deletes exactly the excluded copies;
  widening copies them again; an equivalent filter forces nothing; a failed run keeps the old hash;
  leftovers after lost mappings or a reinstall are deleted, while unknown, out-of-window and paused
  rows are never touched; `filterImpact` is read-only and counts only in-window copies in the
  current target; a renamed label keeps the filter. The random `SyncEngineSafetyTest` also changes
  filters.
- F6 (`FilterEditorStateTest`, `FilterSummaryTest`, `SourcesFilterTest`): the editor's order,
  former members, toggling, clearing and normalizing; the summary; a filter set during setup is
  stored before the first sync; filters survive the Sources save; the question comes only when
  copies would go, and once together with deselected calendars.

**On the device.** `A` stands for the adb command of the test phone, for example `adb -s <serial>`
(or `ssh <host> adb -s <serial>` when the phone is connected to another machine), and `<target>` for
the `_id` of the target calendar (`scripts/device-verify.sh -l` lists them). The test setup's target
calendar held 7 events of its own. `scripts/device-verify.sh` wraps steps 1, 5 and 6.

0. **DURATION form.** Read how Google's sync stores durations, without titles:
   `A shell content query --uri content://com.android.calendar/events --projection
   _id:allDay:duration:rrule --where "rrule IS NOT NULL"`. The timed rows must match `P<n>S`; if they
   show `PT…`, switch `EventSpec` to that form before any write. Also check that master sync and calendar
   sync are on for the account (Settings → Accounts).
1. **Baseline.** `A shell content query --uri content://com.android.calendar/events --projection
   _id:title:dtstart:dtend:duration:eventTimezone:allDay:rrule:exdate:deleted:dirty:_sync_id --where
   "calendar_id=<target>"`. Save the output; in the test setup it shows 7 rows.
2. **Install.** `A install -r` the APK; `A shell pm grant de.tergelius.ttsync
   android.permission.READ_CALENDAR` (likewise `WRITE_CALENDAR`, `POST_NOTIFICATIONS`);
   `A shell dumpsys deviceidle whitelist +de.tergelius.ttsync`.
3. **Setup.** The user signs in on the phone (the password never goes over adb) and picks the TimeTree
   calendar and the target calendar.
4. **Sync.** `A shell am broadcast -a de.tergelius.ttsync.debug.SYNC_NOW -n
   de.tergelius.ttsync/.debug.DebugReceiver`, then wait for `run finished` in `A logcat -s TtSync`.
5. **Check the copies** (`calendar_id=<target> AND description LIKE '%[ttsync:%'`): count = live in-window
   events in the raw dump; `dtstart` = 1794481200000 (2026-11-12 12:00 Europe/Berlin) + n·86400000, up
   to 1794740400000 (2026-11-15); `eventTimezone=Europe/Berlin`; correct `dtend`.
6. **Foreign events.** The 7 baseline rows are unchanged.
7. **Upload.** About 2 min later the copies show `dirty=0` and a `_sync_id`. The user checks
   calendar.google.com.
8. **No-op run.** SYNC_NOW again: the report is 0/0/0.
9. **Changes.** The user edits a time and a title in TimeTree and deletes an event. After a sync, the same
   `_ID`s are updated and the deleted row is gone.
10. **Recurring.** Use the recurring test data and check `instances/when/<begin>/<end>`:
    no duplicate on the moved occurrence's original day, the deleted occurrence is gone, the two-day
    all-day event covers exactly two days, and `unmatchedOverrides` is 0.
11. **Adoption.** `DROP_MAPPINGS`, then sync: nothing is inserted.
12. **Periodic job.** Find it in `A shell dumpsys jobscheduler`, then run
    `A shell cmd jobscheduler run -f de.tergelius.ttsync <id>`.
13. **Uninstall.** `A uninstall de.tergelius.ttsync`: the copies stay, which is expected because Android
    runs no app code on uninstall. Reinstall and sync: nothing inserted. Then "Remove all copies": no
    marked rows left, and the baseline is unchanged.
14. **Release build.** Repeat steps 2–8 with the signed APK. Check the certificate with
    `apksigner verify --print-certs`.

**Filters (0.2.0).** The test calendar needs events with different labels (one label renamed in
TimeTree, one left unnamed), different attendees, a few words in titles, notes and locations, and
a weekly series with one occurrence edited to another label. Take the step 1 baseline first; the
target's own events must stay unchanged throughout (`scripts/device-verify.sh` fingerprint).

15. **Update.** Install 0.2.0 over 0.1.1 (Obtainium or `A install -r`): sign-in, settings and copies
    are kept, no setup step comes back, and the first run is a normal one. At the next FULL, the
    copies of unnamed labels are updated once to a `Label: <English colour name>` line (B3); the
    others are not rewritten.
16. **Set a filter.** Settings → TimeTree calendars → "Events: All" → tick one label → Done → Save.
    The dialog says "N copies no longer match and will be removed from <target>." with N = the
    in-window copies with other labels. Confirm: a FULL run deletes exactly those, Status shows
    "Filtered N", Settings and Status show the calendar as "(filtered)". Cancel instead: nothing
    changes.
17. **Criteria.** Attendees, "Only if it contains" and "Skip if it contains" (upper and lower case
    mixed, two words separated by a comma), alone and combined: the copies follow section 4a. The
    edited occurrence follows its own label (the series lacks it, or it is copied alone).
18. **Rename and widen.** Rename the chosen label in TimeTree: the filter still applies, and the
    next FULL writes the new name. Then Clear → Done → Save: no dialog, the copies come back.
19. **Setup with a filter, leftovers.** Sign out keeping the copies, sign in again, set a filter on
    the Sources step before choosing the target: the first sync copies only matching events, adopts
    their old copies and deletes the old copies of excluded events (leftover cleanup); nothing else
    in the target changes. `DROP_MAPPINGS` followed by a sync: the same result.
20. **Screens.** The editor in English and German (German names for the 15 extra colours, English
    for the ten defaults), with TalkBack (a label row reads its name, its colour and its state), the
    system back gesture (returns to the list and keeps the edit), and a former member (listed only
    while selected, "Left the calendar").
21. **Relabelling.** Give an event another label in TimeTree: its copy goes (or comes) with the
    next sync, and relabelling it back reverses that. Device run 5 showed that relabelling bumps
    the event's `updated_at`, so INCREMENTAL runs see it too; renaming a label changes only the
    labels' `updated_at`, so the new name follows with the next FULL run.

## 11. Release

- **Signing key.** One RSA 4096 key (alias `ttsync`, 10950 days) at `~/keys/ttsync/release.jks` on
  the build machine, mode 600, outside every repo. Back it up offline, with its passwords, in the user's password
  manager: without it there are no updates and no developer-verification registration.
- **Build.** Gradle reads `ttsync.signing.*` from `~/.gradle/gradle.properties` and builds unsigned
  without them; no key in CI. Release uses R8 and resource shrinking. Add `app/proguard-rules.pro` (the
  template references it, but it is missing).
- **Repo and license.** `ttsync/` is its own git repo, published as
  <https://github.com/tylerstyle/ttsync>. Apache-2.0, see `LICENSE`; third-party licenses in
  [LICENSES.md](LICENSES.md).
- **Versions.** Semver `versionName` (starting at 0.1.0). `versionCode = major·10000 + minor·100 + patch`.
  Tags look like `v0.1.0`.
- **Publishing.**
  - `gh release create v0.1.0 ttsync-0.1.0.apk`, with the APK's SHA-256 in the notes.
  - Obtainium tracks the repo URL. One APK per release and the same key mean updates install in place.
  - IzzyOnDroid later. No F-Droid.
- **Developer verification.** Not enforced in Germany before the undated 2027 rollout, so v1 stays
  unregistered; decide before then. Publish the certificate SHA-256 in the README now.

**README outline:** what it is and isn't (one-way, read-only, unofficial, not affiliated with
TimeTree); requirements; **Google/Apple sign-in workaround** (set an email and a password in TimeTree, section 13,
or have the owner invite a separate email account); install via Releases or Obtainium with the
certificate fingerprint; setup; keeping it running; what to expect (about hourly, edits to copies are
overwritten, deleted copies return, reminders follow the calendar's defaults); privacy; uninstall
("Remove all copies" first); troubleshooting; license.

## 12. Work breakdown

Commands run in `ttsync/` with `ANDROID_HOME=~/Android/Sdk`.

| # | Phase | Main files | Verify | After |
|---|---|---|---|---|
| 1 | Foundation: dependencies (no lib-recur), manifest, backup rules, `App`/`AppGraph`, interfaces and data classes (`EventSpec`, `Desired` with `Write(spec, endAt)` and `Absent`, `CalendarGateway`, store APIs with `mapping.end_at` and snapshot tombstones, `TtError`, `SyncReport` with `unmatchedOverrides`/`unmanagedMarked`), debug/release source sets, remove sample code | `libs.versions.toml`, `app/build.gradle.kts`, manifest, `res/xml/*` | `./gradlew assembleDebug testDebugUnitTest lintDebug` | – |
| 2 | TimeTree client, raw dump, C1–C8 | `timetree/*`, `src/debug/…` | `./gradlew testDebugUnitTest --tests 'de.tergelius.ttsync.timetree.*'` | 1 |
| 3 | Mapper, recurrence, hash, M1–M20. The override rule and the lib-recur question are finalized from the real raw dumps; M11–M14 wait for them | `mapping/*`, fixtures | `… --tests 'de.tergelius.ttsync.mapping.*'` | 1; fixtures |
| 4 | `SyncDb`, gateways, `SyncEngine`, R1–R5, E1–E15 | `store/*`, `calendar/*`, `sync/SyncEngine.kt` | `… --tests 'de.tergelius.ttsync.sync.*'` | 1 |
| 5 | Credentials, DataStore, worker, scheduler, notifications, debug receiver | `settings/*`, `sync/Sync{Worker,Scheduler,Notifier}.kt` | `./gradlew assembleDebug testDebugUnitTest`, then install, SYNC_NOW, logcat | 2–4 |
| 6 | UI, ViewModels, Nav3, en/de strings, checklist | `ui/**`, `Navigation*.kt`, `res/values*/` | `./gradlew assembleDebug lintDebug`, then an emulator walkthrough | 1; wire up after 5 |
| 7 | Device checklist, fixes, signing, README, `scripts/device-verify.sh`, v0.1.0 | signing config, `proguard-rules.pro`, `README.md` | checklist 0–14, `./gradlew assembleRelease`, `apksigner verify` | 5, 6 |

**Parallel:** phases 2, 3, 4 and 6 can run side by side after phase 1, all against phase 1's
interfaces. Phase 5 needs 2–4. Phase 7 comes last.

## 13. Open questions

Resolved:
- Access lost: keep the copies and notify.
- The app is named "TT Calendar Sync" and licensed under Apache-2.0.
- `ttsync/` is its own git repo.
- The test data was created in a test TimeTree calendar (see [DEVICE-TESTS.md](DEVICE-TESTS.md)).
- The Google/Apple sign-in workaround: in the TimeTree app, More → Manage Account → Change email
  address, then Change password; then sign in with that email and password. It is in the README.
- The repository is <https://github.com/tylerstyle/ttsync>.
- 0.2.0: relabelling an event bumps its `updated_at`, so INCREMENTAL runs see it; renaming a label
  doesn't touch the events (device run 5, section 4a).

Still open: none.

## 14. Review resolution

From the design review ([reviews/design-agy.md](reviews/design-agy.md)), with the decisions taken:

| Finding | Outcome | Where |
|---|---|---|
| BLOCKER-1: `P<n>S` should be `PT<n>S` | Rejected as a blocker. Android's `calendarcommon2` Duration parser skips `T`, and Google's sync writes `P<n>S`. The form is kept and checked on the device. | 4, checklist step 0 |
| BLOCKER-2: adoption scan deletes paused and old copies | Accepted. The adoption scan only adopts. Sync deletes only in the diff, and only for mapped uuids of active calendars that are `Absent`, or missing from a complete fetch, and in window. Duplicates are deleted only for live, active uuids. Changed in 0.2.0 by the owner: the scan also deletes unmapped rows of live, in-window events that an active calendar's filter excludes (step 6). | 5 (steps 5–6), E11, E13, E14 |
| BLOCKER-3: `mapping` has no end date | Accepted. `mapping.end_at` was added (`tt_cal_id` was already there), and every window check uses it. | 2, 4, 5 |
| BLOCKER-4: nearest-instance heuristic; tombstones ignored | Accepted. No heuristic: native EXDATE first, then an exact match by the original start if the API provides one, otherwise nothing and a count. Tombstones go through the resolver. The final rule and lib-recur are **decided by fixtures**. | 2, 4, 12 (phase 3) |
| SHOULD-FIX-1: no first sync after setup | Accepted. Confirming the target enqueues a FULL `ttsync-now`. | 7, 9 |
| SHOULD-FIX-2: master and account sync not checked | Accepted. Precondition warning, Status warning, checklist row. | 5 (step 1), 7 |
| SHOULD-FIX-3: sync-settings permissions | Accepted: `READ_SYNC_SETTINGS`, `WRITE_SYNC_SETTINGS`. | 8 |
| SHOULD-FIX-4: all-day `EVENT_END_TIMEZONE` | Accepted. Always null for all-day events. | 4 |
| SHOULD-FIX-5: stale fields after a single ↔ recurring change | Accepted. Explicit nulls for every managed column. | 4, M20 |
| SHOULD-FIX-6: EXDATE/RDATE prefixes and TZID | Accepted. Prefixes and parameters are stripped, and TZID values are normalized to UTC. | 4, M9 |
| SHOULD-FIX-7: notification can't fire if jobs are killed | Accepted. The limitation is documented; "Keep running" became a setup step, and Xiaomi Autostart is covered. | 7, 9 |
| SHOULD-FIX-8: `P0D` for one-day all-day series | Accepted. `days = DAYS.between(start, inclusiveEnd) + 1`. | 4, M10 |
| NIT-1: R2 vs `applyBatch` preconditions | Accepted. Operations by `_ID` URI, with selection only as a `withExpectedCount(1)` precondition. | 5 (R2), E15 |
| NIT-2: target calendar deleted on the web | Accepted. Dedicated Status state with a button to Target. | 5 (step 1), 7 |
| NIT-3: lib-recur too early | Covered by BLOCKER-4. Optional, decided by fixtures, not in phase 1. | 2 |
| NIT-4: credentials file layout | Accepted. `[12-byte IV][ciphertext + 16-byte GCM tag]`. | 6 |

### 0.2.0: filters

The owner's decisions of 2026-11-12 (section 4a) and the choices made while building them; the code
reviews of 0.2.0 get their own files in [reviews/](reviews/).

| Topic | Decision | Where |
|---|---|---|
| BLOCKER-2 revisited | Leftover cleanup approved by the owner: the adoption scan also deletes unmapped rows of live, in-window events that an active calendar's filter excludes. Unknown, dead, paused and out-of-window rows stay untouched. | 4a, 5 (step 6), F5 |
| Labels | By id, never by name; the UI shows the current name or the colour's name. | 4a, 7.2 |
| B3 | Unnamed labels: the copies use the English colour name. No `EventMapper.VERSION` bump: only copies with unnamed labels are rewritten, at their next FULL. | 4, 4a |
| New API fields | Decoded leniently, so a surprise in data the copies hardly need never skips an event or fails `GET /calendars`. | 4a |
| `filterImpact` | Read-only and without the run lock, so the question isn't blocked by a running sync; it counts mapped copies only. | 4a |
| Fingerprint | Keeps the terms' case: a case-only change of a term costs one extra FULL run. "filter changed" ranks after "no high-water mark" and "no FULL recorded" among the FULL reasons. | 4a, 5 |
| Editing | The editor works on a draft that Sources saves with the selection, so one question covers deselected calendars and narrower filters, and a filter can be set during setup. Back keeps the edit, like Done; leaving Sources without Save drops it. | 7.2 |
| Word lists | One text field per list, words separated by commas (a word can't contain a comma). | 7.2 |
| Downgrade | 0.1.x ignores the filters and copies every event again (README). | 4a |
