# On-device tests

Results of the device checklist in [DESIGN.md](DESIGN.md) section 10. How to run such tests:
[DEVELOPMENT.md](DEVELOPMENT.md#testing-on-a-device).

Five runs on 2026-11-12, all on a OnePlus 8 Pro (IN2023) with LineageOS 24.0 (Android 17, SDK 37),
driven over adb by a device-test agent (Claude Code). The user signed in on the phone; nothing was
dumped or screenshotted while a login screen was open. Source: TimeTree test calendar with simple
timed events, "TT test weekly" (weekly series with one deleted and one moved occurrence),
"TT test details" (location, note, URL, label) and "TT test all-day" (two days). Target: the user's
Google calendar, which already held 7 events of its own.

**Safety invariant held in every run:** the target's 7 own events were hashed before the run and
compared at every checkpoint (8 in run 1, 3 each in runs 2 and 3, 5 in run 4, 4 in run 5): never
changed. After each run the target held
only those 7 events again.

## Run 1: debug build `d2756c2`

| Area | Result |
|---|---|
| Install, calendar permissions, setup (sign-in, source, target) | Pass: confirming the target starts a FULL run and the periodic job |
| First sync: 8 copies (timed, series with EXDATE and moved occurrence, details, all-day) | Pass; no `Label:` line (B3) |
| Target's own events untouched | Pass (8/8 checkpoints) |
| Upload to Google (`dirty=0`, `_sync_id` set within ~2 min; edits and deletes too) | Pass |
| No-op runs write nothing | Pass |
| Instances in the provider (series exceptions, moved occurrence, all-day span) | Pass |
| Move, rename and delete in TimeTree, then "Sync now" (INCREMENTAL) | **Fail (B1)**: nothing arrived |
| The same changes with "Full resync" | Pass: same rows updated in place, deleted row purged by Google |
| Adoption after dropping the mappings, and after uninstall + reinstall | Pass: no duplicates |
| Periodic job | Pass (natural run); cannot be forced early (F7) |
| Second "Sync now" within 60 s | Pass, but slow in the background (F4) |
| Session survives force-stop; expedited work starts within 60 ms | Pass |
| "Remove all copies"; sign-out wipes credentials, settings and the database | Pass (Keystore key not checkable without root) |
| Google sync state | **Fail (B2)**: false "Google sync is off" warning |
| Logs: no email, password, cookie, session or event text | Pass |
| Release build | Not run |

## Run 2: fix round 1 (`f07bfd7`), debug then release

| Area | Result |
|---|---|
| Debug installed as an update: permissions and battery exemption kept, `SyncDb` migration 1 to 2 | Pass |
| First run FULL (setup), later runs FULL because the feed is small (1 page) | Pass |
| Copies match TimeTree's current state, including run 1's changes | Pass |
| No false Google-sync warning; checklist row OK; no "not uploaded" warning | Pass (**B2 fixed**) |
| Create, edit and delete in TimeTree, then one "Sync now" (not forced FULL) | Pass (**B1 fixed**): added 1, updated 1 (same row), deleted 1 |
| "Sync now" twice within 60 s via the debug receiver | Pass: second request not enqueued |
| "Sync now" twice within 60 s in the UI | **Partial (R1)**: no run queued (correct), but no "Synced N s ago" snackbar |
| Series instances, all-day span | Pass |
| Sign-out keeping the copies | Pass: copies stay, uploaded |
| Release: debug uninstalled (copies stay), release installed, no `DebugReceiver` | Pass |
| Release: sign-in, adoption of the existing copies without duplicates | Pass |
| Release: About shows the release signing certificate | Pass: matches the release key |
| Release: logs | Pass: no `TtSync` lines at all |
| Release: "Sync now" more than 60 s after a run | Pass |
| "Remove all copies", uninstall | Pass: no marked rows left |

## Run 3: published release 0.1.0, installed through Obtainium

The way a user installs it: Obtainium 1.7.4 (its APK and certificate checked against its README),
then **Add** → `https://github.com/tylerstyle/ttsync` → **Install**.

| Area | Result |
|---|---|
| Obtainium finds v0.1.0 and picks `ttsync-0.1.0.apk` (ignores the `.sha256` and mapping assets); no warnings or extra questions | Pass |
| Install dialogs: "Install unknown apps" for Obtainium, "Install this app?", Play Protect "App scan recommended" | Pass: after "Scan app" Play Protect uploaded the APK, verdict SAFE, then "Install". README updated |
| Installed APK byte-identical to the release asset, release certificate, versionCode 100, installer Obtainium | Pass |
| About and Obtainium's "Certificate Hash" show the release fingerprint | Pass |
| Notifications denied | Pass: Status keeps "Syncing normally" and adds a "Notifications are off" warning with **Turn on**; the checklist lists it as a problem |
| First sync after setup, copies match TimeTree, all uploaded (`dirty=0`) within 2 min | Pass |
| "Sync now" right after a run | Pass (**R1 fixed**): "Synced 7 s ago, nothing to do" |
| Release logs | Pass: no `TtSync` lines, nothing private from the app |
| Obtainium update check | Pass: "v0.1.0 Installed / Latest", no update offered, no version-detection error |
| "Remove all copies", Google purges them, uninstall | Pass: no marked rows left |
| "Last successful sync" right after a run | **Fail (O1)**: "(In 0 minutes)" |

## Run 4: update 0.1.0 → 0.1.1 through Obtainium

Obtainium 1.7.4 can't install an older release, so the verified 0.1.0 release APK was installed with
adb, the way a user who downloaded it from the releases page has it, and then added in Obtainium.

| Area | Result |
|---|---|
| Obtainium adopts an app installed outside it | Pass: right after adding the URL, "0.1.0 Installed / v0.1.1 Latest", no questions |
| Set up 0.1.0: sign-in, Family → personal, notifications allowed, first sync, all copies uploaded | Pass |
| Update offered | Pass: the row shows "0.1.0 → v0.1.1" with **Update** |
| Update dialogs: "Update this app?", Play Protect "App scan recommended" again, "Scan app" → SAFE → "Install" | Pass: no uninstall. README now says every update may show the scan prompt |
| Installed in place: APK byte-identical to the 0.1.1 asset, same certificate, versionCode 101, `firstInstallTime` unchanged, same app id, installer now Obtainium | Pass |
| Kept: permissions, battery exemption, the same WorkManager job, run history, sign-in (no login screen), settings | Pass |
| Copies unchanged by the update and by the next "Sync now" (same `_id`s and `_sync_id`s, Added 0 · Updated 0 · Deleted 0, no duplicates) | Pass |
| "Last successful sync" right after a run | Pass (**O1 fixed**): "(0 minutes ago)" |
| "Sync now" within 60 s, About, release logs | Pass: "Synced 14 s ago, nothing to do"; Version 0.1.1, same certificate; no `TtSync` lines |
| Obtainium after the update | Pass: "v0.1.1 Installed / Latest", no update offered |
| "Remove all copies", Google purges them, uninstall | Pass: no marked rows left |

## Run 5: 0.2.0 event filters, debug build `7e9828b`

The steps of [DESIGN.md](DESIGN.md) section 10 for the filters, in the dark theme, in English and
with the app's language set to German. The test calendar was extended: of its labels, two were
renamed in TimeTree (label A and label B), the others kept TimeTree's unnamed default labels; its
events ("Test A", "Test B", …) carry different labels, attendees and words, and the weekly series
is there as before. Target: the user's Google calendar with its 7 own events.

| Area | Result |
|---|---|
| Setup with a filter on one label (label A, renamed), set on the Sources step before the target | Pass: exactly 1 copy; Status "Filtered 8" |
| Widening the filter, leaving the editor with the back gesture | Pass: the editor's draft is kept; Save asks nothing and adds the second copy |
| "Remove copies?" when narrowing (counts 1, 1, 4 and 7) | Pass: each count equalled the copies actually deleted |
| No question when widening, when clearing, or for an attendee filter that removes nothing | Pass |
| "Only if it contains", "Skip if it contains" | Pass |
| A filtered weekly series | Pass: it keeps its EXDATE and its moved occurrence |
| B3: the copies' `Label:` line | Pass (**B3 verified**): unnamed labels as "Label: Emerald green" and "Label: Apple red", renamed labels by their new names |
| Relabelling an event in TimeTree, then relabelling it back | Pass: its copy goes with the next sync and comes back with the one after |
| Renaming a label that the filter uses | Pass: it stays selected, shown with its new name; the copy's `Label:` line follows after the next sync |
| German UI | Pass |
| Target's own 7 events | Pass: unchanged at 4 checkpoints |
| Layout | Five findings, L1–L5 below |

**API answer** (the open question of DESIGN.md section 13): relabelling an event bumps its
`updated_at`, so an INCREMENTAL run sees it like any other edit. Renaming a label doesn't touch the
events, only the labels' `updated_at`, so the new name reaches the copies with the next FULL run
(every run for the test calendar, which is small).

## Bugs and fixes

| ID | Severity | Problem | Fix |
|---|---|---|---|
| B1 | High | The feed is ordered newest change first, and its `since` pages *backwards*. The app stored it as an incremental cursor, so INCREMENTAL runs never saw a change; only FULL runs did. | Fix round 1 (`180ad20`): every read starts at the top of the feed and stops at the stored high-water mark (newest `updated_at`/`deactivated_at` seen) minus a 2 min overlap. Only a complete FULL read may delete by absence. Calendars of up to 3 pages run FULL every time, larger ones at least every 6 h. Verified in run 2. |
| B2 | Medium | Since Android 13, `getSyncAutomatically` answers "off" and `requestSync` does nothing for an account the app cannot see, which is the normal case for the Google account: a false "Google sync is off" on every run. | Fix round 1: the account switch counts only if the account is visible to the app, otherwise it is unknown and gives no warning. Instead the app warns when its copies are still `dirty` 30 min after it wrote them. `requestSync` is best effort. Verified in run 2. |
| F4 | Info | A second "Sync now" within 60 s was queued as delayed, non-expedited work. With the app in the background it waited until the app came back to the foreground. | Fix round 1: "now" work is always expedited and never delayed; a "Sync now" within 60 s of a successful run queues nothing and Status says "Synced N s ago". Verified in run 2 through the debug receiver; the UI note is R1. |
| R1 | Low | The "Synced N s ago" snackbar never showed: Status cleared the note, which was its `LaunchedEffect` key, before `showSnackbar`, so Compose cancelled the effect. | Fix round 2: the note is a one-off `SyncedNote` with an id, consumed only after `showSnackbar` returns; a newer note is never cleared by an older one (`StatusViewModelTest`). Verified in run 3. |
| O1 | Low | Right after a run, "Last successful sync" read "(In 0 minutes)": Status's clock ticks once a minute, so a run that finished after the last tick lay in its future. | 0.1.1: Status takes the newer of the clock and the last success as "now" (`StatusViewModelTest`). Verified in run 4. |
| B3 | Low | TimeTree's default labels have empty names (the apps show them by the name of their colour), so copies get no `Label:` line. | 0.2.0: the copies' `Label:` line uses the label's name, else the English name of its colour (TimeTree's 25 colour names, by colour, not by label id). Copies with unnamed labels are rewritten once at their next FULL. The filter editor shows the same names, localized. Verified in run 5. |
| L1 | Low | Status's last-run counts wrapped with a "·" left at the end of a line ("… · Skipped 1 ·" / "Filtered 8"). | 0.2.0 fix round (`b13b86e`): the counts, and the "Events: …" summary on Sources, wrap only between items, with the "·" starting the next line. Not yet verified on the device. |
| L2 | Low | The swatch of a "Midnight black" label was nearly invisible in the dark theme. | 0.2.0 fix round: every swatch has a 1 dp outline. Not yet verified on the device. |
| L3 | Low | Extra space under the "Events: …" line on Sources. | 0.2.0 fix round: the line sits right under its calendar's name, like a subtitle. Not yet verified on the device. |
| L4 | Low | German relative times in parentheses started with a capital: "(Vor 1 Minute)". | 0.2.0 fix round: the first letter is lower case, locale-aware: "(vor 1 Minute)"; in English "(5 minutes ago)" is unchanged and "(In 40 minutes)" becomes "(in 40 minutes)". Not yet verified on the device. |
| L5 | Low | Android 13+'s Settings → App languages didn't offer the app: no locale config. | 0.2.0 fix round: AGP generates one for English and German. Not yet verified on the device. |

Other findings, no change needed: Google's sync adapter rewrites the copies after upload (EXDATE in
local-time form, `WKST=MO`, default reminders); the app hashes what it writes, not what it reads
back, so this causes no rewrite loop. Adoption rewrites each adopted row once, by design. A forced
early periodic run is refused by WorkManager (F7), so only natural periodic runs can be observed.

## Not tested yet

- **The 0.2.0 release build as an update over 0.1.1 through Obtainium** ([DESIGN.md](DESIGN.md)
  section 10, step 15, with the release APK): runs after the release.
- **The layout fixes L1–L5** of the 0.2.0 fix round.

- **Feeds of more than 3 pages** (about 300 events) on a real calendar: the high-water INCREMENTAL
  path and the 6-hour FULL ran only in unit tests. The test calendar always fit on one page.
- **A failure notification while notifications are denied**: run 3 checked the warning on Status
  and in the checklist, but no failure was due, so no notification was missed.
- **Other vendors and Android versions**: only LineageOS 24 on one phone; no vendor battery
  managers (Samsung, Xiaomi, OxygenOS, ...), no Android 8-16.
- Obtainium's background update checks and update notifications (its notification permission was
  denied in runs 3 and 4; updates were found by pull-to-refresh).
- A periodic run of the fixed build, and the Keystore key removal at sign-out (needs root).
