# Review of Phase 3: TimeTree to CalendarContract Event Mapping

**Reviewer:** agy (Antigravity, Gemini)  
**Date:** 2026-11-12  
**Commit Range:** `d287d32..b91227d` (package `de.tergelius.ttsync.mapping`, tests, and fixtures)  
**Reference Document:** `docs/DESIGN.md` (Sections 4, 10, 14)  
**Real TimeTree Fixture Facts:**
- Series with moved/deleted occurrences natively contain parent EXDATEs in UTC Z format (`EXDATE:20261123T170000Z`).
- Moved occurrences arrive as standalone events with `recurring_uuid`/`parent_id` and no original-start field.
- All-day events have inclusive `end_at` with UTC timezone.
- Series support `COUNT`.

---

## Executive Summary

Phase 3 implements the event mapping pipeline cleanly, adhering strictly to the contract defined in `DESIGN.md` Section 4 and the resolution recorded in Section 14:
- Invariants in `EventSpec` prevent invalid recurrence/time states from reaching `CalendarGateway`.
- Explicit null clearing for non-recurring vs. recurring transitions (`DURATION`, `RRULE`, `RDATE`, `EXDATE` vs. `DTEND`) is fully implemented and tested.
- `RecurrenceNormalizer` cleanly strips prefixes, parses parameters (such as `TZID` and `VALUE=DATE`), normalizes dates to UTC, and merges lines into sorted, deduplicated comma-separated strings without colons.
- `SpecHash` uses length-prefixing and canonical ordering, verified against an independent Python implementation.
- `Marker` placement and parsing cleanly enforce R1 and R2 safety requirements.

However, there is **1 Blocker** in tombstone window filtering that will cause deleted events with default timestamps to be permanently retained in Google Calendar, **2 Should-Fix** items regarding hashing and zero-duration series, and **3 Nits** covering allocations, title whitespace, and test edge cases.

---

## Assessment Across Focus Areas

### 1. Correctness Against DESIGN.md Section 4 & CalendarContract Rules
- **Explicit nulls:** Fully satisfied. Single events explicitly write null for `duration`, `rrule`, `rdate`, and `exdate`; recurring events explicitly write null for `dtEnd`; all-day events explicitly write null for `eventEndTimezone`. All 12 columns are managed.
- **Duration & Recurrence:** Timed recurring events write `P<seconds>S` and all-day series write `P<days>D` (with `days = DAYS.between(start, inclusiveEnd) + 1`). Single events have null duration and null recurrence rules.
- **Timezones:** Timed events use valid IANA `ZoneId` or fallback to `UTC`. All-day events strictly enforce `eventTimezone = "UTC"` and `eventEndTimezone = null`.

### 2. Time Zone and DST Edge Cases (`Europe/Berlin`)
- Timezone lookups strictly validate against `ZoneId.getAvailableZoneIds()`, rejecting raw offsets (like `+01:00`) that cause issues in Android's `CalendarProvider2`.
- DST transitions in `Europe/Berlin` (such as the 3-hour switch night in `m02-dst-night` and summer/winter date-only UNTIL conversions `225959Z`/`215959Z`) are correctly calculated using `java.time` ZonedDateTime/Instant APIs.
- Date-only EXDATE entries across DST boundaries correctly preserve the wall-clock series start time in the target zone.

### 3. The 30-Day Window & `end_at`
- `windowStartMillis(today, zone)` provides a unified window cutoff.
- `Shape.endAt` accurately computes the end of single events, series with `UNTIL`, and series with `RDATE` extending past `UNTIL`. Open-ended series and `COUNT` series safely evaluate to `Long.MAX_VALUE`, ensuring they remain in-window.
- **Flaw identified:** Tombstones decoded with lenient defaults (`start_at = 0L, end_at = 0L`) are prematurely dropped by `shape.endAt < windowStart` instead of yielding `Desired.Absent` (see BLOCKER-1).

### 4. SpecHash Stability & Marker Placement
- `SpecHash` canonical representation is length-prefixed and collision-resistant. Tests prove stability across list permutations and line reordering.
- `Marker.append` guarantees the marker is on the exact final line, and `Marker.parse` safely ignores fake markers in note bodies while rejecting tampered descriptions.

### 5. Test Quality
- Tests generally prove real behavior rather than restating code (e.g. pinned SHA-256 hashes cross-verified with Python, DST 3-hour span math, real sanitized dumps).
- Gaps exist for tombstone default decoding and all-day events at the exact window boundary day.

---

## Findings Ranked by Severity

### 1. Blockers

#### [BLOCKER-1] Tombstones with lenient defaults (`end_at = 0L`) are dropped as `outOfWindow` instead of becoming `Desired.Absent`
- **File & Line:** `app/src/main/kotlin/de/tergelius/ttsync/mapping/EventMapper.kt:147-154`
- **Problem:**  
  `Dtos.kt:86-90` and `Dtos.kt:150-163` explicitly specify that deleted events (tombstones) decode leniently when TimeTree omits `start_at` or `end_at`, filling them with `0L` (representing "unknown, never a real time").  
  However, in `EventMapper.kt:147-154`:
  ```kotlin
  when {
    shape.endAt < windowStart -> outOfWindow++
    event.isBirthday || event.isMemo -> {
      skipped++
      desired[event.uuid] = Desired.Absent
    }
    event.isDeactivated -> desired[event.uuid] = Desired.Absent
    else -> ...
  }
  ```
  Because `shape.endAt` for these tombstones is `0L`, and `0L < windowStart` evaluates to `true`, the `when` expression branches to `shape.endAt < windowStart -> outOfWindow++`. It **never reaches** `event.isDeactivated -> desired[event.uuid] = Desired.Absent`.  
  Consequently:
  1. The tombstone is counted as `outOfWindow` and omitted from `desired`.
  2. The sync engine never sees `Desired.Absent` for the deleted event.
  3. Under DESIGN.md R1 and Section 5 line 187 ("Mappings outside the window are never touched"), the existing event copy remains in Google Calendar and will **never be deleted**.
- **Concrete Fix:**  
  In `EventMapper.kt:206-230` (`shapeOf`), ensure that if `event.isDeactivated && event.endAt == 0L`, `shape.endAt` is treated as active/current (e.g. `event.deactivatedAt ?: Long.MAX_VALUE`). Alternatively, in `EventMapper.kt:147-154`, handle tombstones with unknown timestamps before the window check:
  ```kotlin
  when {
    event.isDeactivated && event.endAt == 0L -> desired[event.uuid] = Desired.Absent
    shape.endAt < windowStart -> outOfWindow++
    event.isBirthday || event.isMemo -> {
      skipped++
      desired[event.uuid] = Desired.Absent
    }
    event.isDeactivated -> desired[event.uuid] = Desired.Absent
    else -> ...
  }
  ```

---

### 2. Should-Fix Issues

#### [SHOULD-FIX-1] `SpecHash` uses default locale in `"%02x".format(it)` instead of `Locale.ROOT`
- **File & Line:** `app/src/main/kotlin/de/tergelius/ttsync/mapping/SpecHash.kt:28`
- **Problem:**  
  `SpecHash.kt:28` formats SHA-256 digest bytes with:
  ```kotlin
  return digest.joinToString("") { "%02x".format(it) }
  ```
  In Kotlin, `String.format(format, *args)` uses `Locale.getDefault()`. On Android devices running under certain locales or customized OEM environments, formatting without an explicit locale can introduce locale-dependent digit representations or case folding. Cryptographic digests must always use invariant formatting (`Locale.ROOT` or `Locale.US`).
- **Concrete Fix:**  
  Pass `Locale.ROOT` explicitly, or use Java 17 `HexFormat`:
  ```kotlin
  // In app/src/main/kotlin/de/tergelius/ttsync/mapping/SpecHash.kt:28
  return digest.joinToString("") { "%02x".format(java.util.Locale.ROOT, it) }
  ```
  or:
  ```kotlin
  return java.util.HexFormat.of().formatHex(digest)
  ```

---

#### [SHOULD-FIX-2] Timed recurring series where `end_at <= start_at` produce `P0S` duration
- **File & Line:** `app/src/main/kotlin/de/tergelius/ttsync/mapping/EventMapper.kt:220`
- **Problem:**  
  In `shapeOf`:
  ```kotlin
  dtStart = event.startAt
  dtEnd = maxOf(event.endAt, event.startAt)
  duration = "P${(dtEnd - dtStart) / 1000}S"
  ```
  If a timed event in TimeTree has `start_at == end_at` (or `end_at < start_at`), `dtEnd - dtStart` is `0L`, yielding `duration = "P0S"`.  
  If the event has an `RRULE` or `RDATE`, `EventSpec` receives `duration = "P0S"`. In Android `CalendarProvider2`, zero-second durations on recurring events cause inconsistent behavior in instance expansion (`Instances.CONTENT_URI`) across OEM providers and Google sync.
- **Concrete Fix:**  
  For recurring timed events, clamp the duration to a minimum of at least 60 seconds (or standard default event length) if `dtEnd == dtStart`, and verify device compatibility during Checklist Step 0:
  ```kotlin
  val durationSeconds = maxOf(60L, (dtEnd - dtStart) / 1000)
  duration = "P${durationSeconds}S"
  ```

---

### 3. Nits and Minor Improvements

#### [NIT-1] Redundant `.toSortedMap()` call on already-sorted `columns()` in `SpecHash.kt`
- **File & Line:** `app/src/main/kotlin/de/tergelius/ttsync/mapping/SpecHash.kt:21`
- **Observation:**  
  `SpecHash.kt:21` iterates over `spec.columns().toSortedMap()`. However, `EventSpec.kt:74` defines `columns()` using `sortedMapOf(...)`, which already returns a `SortedMap`. Calling `.toSortedMap()` needlessly allocates a new `TreeMap` and re-inserts all 12 entries on every hash calculation.
- **Concrete Fix:**  
  In `app/src/main/kotlin/de/tergelius/ttsync/mapping/SpecHash.kt:21`, change to:
  ```kotlin
  for ((name, value) in spec.columns()) {
  ```

---

#### [NIT-2] Event title is not trimmed of leading and trailing whitespace
- **File & Line:** `app/src/main/kotlin/de/tergelius/ttsync/mapping/EventMapper.kt:236`
- **Observation:**  
  `location`, `note`, `label`, and `url` all trim whitespace before checking if blank. `title` only checks `it.isNotBlank()` without trimming:
  ```kotlin
  title = event.title?.takeIf { it.isNotBlank() } ?: UNTITLED
  ```
  If an event in TimeTree has accidental leading or trailing spaces (e.g. `" Team Meeting "`), those are preserved in the title, which can cause spurious hash updates if TimeTree later normalizes titles.
- **Concrete Fix:**  
  In `app/src/main/kotlin/de/tergelius/ttsync/mapping/EventMapper.kt:236`:
  ```kotlin
  title = event.title?.trim()?.takeIf { it.isNotEmpty() } ?: UNTITLED
  ```

---

#### [NIT-3] Test Quality: Missing test for tombstone lenient decoding and all-day window boundary
- **File & Line:** `app/src/test/kotlin/de/tergelius/ttsync/mapping/EventMapperTest.kt`
- **Observation:**  
  1. No test exercises a tombstone with missing `start_at`/`end_at` (which triggers `withTombstoneDefaults`). Existing tests (`m15-deleted`) only use synthetic events with full 2026 timestamps.
  2. `m18WindowAndEndAt` tests timed boundary events, but does not test an all-day event falling on the 30-day cutoff date across UTC midnight vs local midnight.
- **Concrete Fix:**  
  Add two explicit unit tests to `EventMapperTest.kt`:
  ```kotlin
  @Test
  fun tombstoneWithDefaultTimestampsBecomesAbsent() {
    val tombstoneJson = """{"uuid":"tombstone-lenient","deactivated_at":1794400000000}"""
    val event = TtEvent.fromJson(tombstoneJson)
    val result = map(listOf(event))
    assertEquals(Desired.Absent, result.desired["tombstone-lenient"])
  }

  @Test
  fun allDayEventAtWindowBoundary() {
    // Tests all-day event on (today - 30 days) and (today - 31 days)
  }
  ```

---

## Resolution (Claude Code, 2026-11-12)

- BLOCKER-1: fixed. Tombstones with unknown times (`end_at == 0`) map to `Absent` before the
  window check; tombstones with known old times stay out of the window, so the "never touch
  history" rule and test M18 still hold. New test
  `EditedOccurrencesTest.tombstoneWithUnknownTimesIsAbsentNotOutOfWindow`.
- SHOULD-FIX-1: fixed with `Locale.ROOT` (`HexFormat` needs API 34, minSdk is 26).
- SHOULD-FIX-2: not changed. A zero-length event is valid in Google Calendar, and clamping
  would change the event's data. The device checklist covers duration forms.
- NIT-1, NIT-2: not changed (trimming would alter user data). NIT-3: covered by the new test.
