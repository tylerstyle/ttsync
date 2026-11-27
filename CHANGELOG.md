# Changelog

All notable changes to TT Calendar Sync are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/).

## [0.2.3] - 2026-11-27

### Fixed

- **Removing copies after the phone renumbered its calendars:** "Remove all copies", signing out
  with removal, and switching the target calendar now find the old target by Google's own calendar
  ID when the phone has given it a new internal number (for example after clearing calendar
  storage). Before, the copies stayed behind without a notice. If the calendar cannot be written or
  cannot be told apart, the app keeps the copies and says so.
- **Choosing the same calendar again after such a renumbering** no longer asks to remove its
  copies; they are adopted.
- **Target name after an update:** The calendar name stored with the target is now taken from the
  calendar the app actually writes to.
- **Edited dates of repeating events:** The check that leaves edited single dates alone
  (0.2.2) now treats empty and zero values of Android's "original event" fields as unset, so
  ordinary copies are never mistaken for edited dates on phones that fill these fields that way.

## [0.2.2] - 2026-11-26

### Added

- **Safety guard against sudden mass deletions:** If a full read suddenly shows at least 10 events
  and more than half of a calendar's copies as deleted, the app waits for a second full read at
  least 10 minutes later before removing them. Normal deletions of a few events are not delayed.
- **Stronger target calendar check:** The target calendar is now also identified by Google's own
  calendar ID. If the phone sets up its calendars anew (for example after clearing calendar storage
  or re-adding the Google account), the app stops instead of writing into a different calendar that
  got the old slot; Status shows "Target calendar is gone" so you can choose it again without
  creating duplicates.
- **Clearer status after a failed sync:** Status now shows "Last sync failed, trying again" instead
  of "Syncing normally", displays the actual retry time instead of "Starting soon", and keeps
  "Sync now" usable right away.
- **Full resync confirmation:** Tapping "Full resync" in Settings now shows a "Full resync started"
  message.

### Changed

- **Sign-in and network hardening:** The sign-in screen now blocks screenshots and stays blank in
  the recent-apps switcher; network requests only talk to TimeTree's own host and refuse redirects
  elsewhere; unusually long rate-limit waits from TimeTree are capped at 24 hours and count time
  spent while the phone sleeps.
- **Attendee filters:** If TimeTree returns an event whose attendee list cannot be read, an
  attendee filter leaves that event alone instead of treating it as non-matching.
- **Upload requests (Android 8–12):** Asking Android to upload changes to Google Calendar no longer
  overrides a switched-off calendar sync setting for the Google account.
- **More careful paging:** Reading TimeTree's event lists now handles events changed in the same
  millisecond across page boundaries.

### Fixed

- **"No access" notifications ([#1](https://github.com/tylerstyle/ttsync/issues/1)):** A lost
  TimeTree calendar now triggers one notification per calendar instead of one on every sync (and
  notifies again only if access returns and is lost anew). Status also keeps listing paused
  calendars after a sync that fails early (for example while offline).
- **Edited dates of repeating events:** Editing a single date of a copied repeating event in Google
  Calendar no longer causes the next full sync to delete that date.
- **Events moved into the past:** Moving a copied event (or ending a repeating series) more than
  30 days into the past now moves its copy along instead of leaving the old copy behind.
- **Keeping your sign-in:** Temporary Android Keystore errors no longer wipe the stored sign-in.
- **Saving Target or TimeTree calendars:** Leaving the Target or Sources screen while copies are
  being removed or the target is switching no longer cuts the change short.
- **Failure notifications after re-enabling permission:** If syncing failed while notifications
  were off and you turn them on later, the next failed sync now notifies you.

## [0.2.1] - 2026-11-17

### Changed

- **Deletions arrive right away:** Normal syncs now read TimeTree's change list, so deleted events,
  deleted dates of repeating events, and deleted series disappear from Google Calendar with the
  next sync or "Sync now" instead of waiting up to 6 hours. Lower load on TimeTree (usually one
  request per calendar), with a full read every 6 hours as a safety net and automatic fallback if
  the change list answers unexpectedly.

### Fixed

- **Deleted copies stay deleted:** Copies that Google's own calendar sync puts back right after the
  app deleted them are now watched and deleted again.

## [0.2.0] - 2026-11-12

### Added

- **Filters per TimeTree calendar:** Choose which events are copied under "Events: All" by label,
  attendee, or included/excluded words, with a preview of how many copies a narrower filter will
  remove before saving.
- **Filtered count on Status:** Status now shows how many events your filters left out, and marks
  filtered calendars as "(filtered)".
- **Per-app language (Android 13+):** Set the app to English or German independently of the phone's
  language in Android's App languages settings.

### Changed

- **Unnamed TimeTree labels:** Copies of events with TimeTree's unnamed default labels now show the
  label's colour name (for example `Label: Apple red`). Renaming or recolouring a label in
  TimeTree keeps filters working.

### Fixed

- Status counts and filter summaries wrap cleanly without a trailing separator; dark label swatches
  keep a visible outline in dark mode; tighter spacing for the "Events:" line on Sources; German
  relative times inside parentheses start with a lower-case letter.

## [0.1.1] - 2026-11-12

### Fixed

- Right after a sync, "Last successful sync" on Status no longer briefly reads "In 0 minutes".

### Changed

- Updated README installation instructions for Obtainium, Android's "Install unknown apps" and Play
  Protect scan prompts, certificate verification in Obtainium, and Google's developer verification
  rollout.

## [0.1.0] - 2026-11-12

### Added

- First public release: one-way sync from selected TimeTree calendars into one Google calendar on
  the phone, hourly by default (or every 3 or 6 hours) plus "Sync now".
- Copies timed, all-day, multi-day, and repeating events (including moved and deleted dates),
  location, URL, and note.
- Never changes or deletes events it didn't create (marker-verified writes only).
- Keeps your TimeTree email and password on the phone, encrypted with the Android Keystore.
- Reliability checklist for background syncing; English and German UI.

[0.2.3]: https://github.com/tylerstyle/ttsync/compare/v0.2.2...v0.2.3
[0.2.2]: https://github.com/tylerstyle/ttsync/compare/v0.2.1...v0.2.2
[0.2.1]: https://github.com/tylerstyle/ttsync/compare/v0.2.0...v0.2.1
[0.2.0]: https://github.com/tylerstyle/ttsync/compare/v0.1.1...v0.2.0
[0.1.1]: https://github.com/tylerstyle/ttsync/compare/v0.1.0...v0.1.1
[0.1.0]: https://github.com/tylerstyle/ttsync/releases/tag/v0.1.0
