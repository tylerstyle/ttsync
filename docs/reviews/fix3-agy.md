# Fix Round 3 Review (Post-Obtainium Test) — `android-agy`

**Verdict: Ready to commit**

Reviewed the fix round 3 diff against the results of the device test that installed the published v0.1.0 through Obtainium (`notes/ttsync-obtainium-test-results.md`).

---

## Findings

1. **`val now = maxOf(nowMillis, status.lastSuccessAt ?: nowMillis)` in `StatusUiState.derive` ([StatusViewModel.kt](../../app/src/main/kotlin/de/tergelius/ttsync/ui/status/StatusViewModel.kt#L121)) is safe across all consumers:**
   - **`lastSuccessAt` relative text ([StatusScreen.kt](../../app/src/main/kotlin/de/tergelius/ttsync/ui/status/StatusScreen.kt#L340)):** When a sync finishes between two ticks of `minuteTicker` (`lastSuccessAt > nowMillis`), `now` advances to `lastSuccessAt`, so `DateUtils.getRelativeTimeSpanString(lastSuccessAt, now, MINUTE_IN_MILLIS)` receives equal timestamps (`now >= time`) and renders `"0 minutes ago"` instead of `"In 0 minutes"` (O1). Once the next minute tick arrives (`nowMillis >= lastSuccessAt`), `now == nowMillis`.
   - **`stale` ([StatusViewModel.kt](../../app/src/main/kotlin/de/tergelius/ttsync/ui/status/StatusViewModel.kt#L173), [StatusRepository.kt](../../app/src/main/kotlin/de/tergelius/ttsync/settings/StatusRepository.kt#L60-L61)):** `isStale(now)` checks `now - lastSuccessAt > 24.hours`. Whenever `lastSuccessAt > nowMillis`, `now - lastSuccessAt == 0` (`false`, matching `nowMillis - lastSuccessAt < 0`); whenever `lastSuccessAt <= nowMillis`, `now == nowMillis`.
   - **`health` ([StatusViewModel.kt](../../app/src/main/kotlin/de/tergelius/ttsync/ui/status/StatusViewModel.kt#L151), [StatusRepository.kt](../../app/src/main/kotlin/de/tergelius/ttsync/settings/StatusRepository.kt#L73-L84)):** `SyncStatus.health` only passes `nowMillis` to `isStale(nowMillis)`, so its result is unchanged.
   - **`rateLimitedUntil` ([StatusViewModel.kt](../../app/src/main/kotlin/de/tergelius/ttsync/ui/status/StatusViewModel.kt#L180)):** A successful run clears `rateLimitedUntil` to `null` (`SyncEngine.kt:619`). Even if a subsequent run within the same minute fails with `RATE_LIMITED`, its `rateLimitedUntil` is at least `30 minutes` after that run (`> lastSuccessAt == now`), and using `lastSuccessAt` as the floor of `now` reflects actual elapsed wall-clock time better than the lagging minute tick.
   - **`nextRunAt` ([StatusScreen.kt](../../app/src/main/kotlin/de/tergelius/ttsync/ui/status/StatusScreen.kt#L355)):** After a run completes, the real time is at least `lastSuccessAt`, so measuring `formatRelative(state.nextRunAt, state.nowMillis)` from `maxOf(nowMillis, lastSuccessAt)` is up to 59 s closer to real wall-clock time and cannot push a future scheduled run (`>= 1 h` out) into the past.
   - **Unit test coverage ([StatusViewModelTest.kt](../../app/src/test/kotlin/de/tergelius/ttsync/ui/StatusViewModelTest.kt#L265-L275)):** Verifies both `justNow = NOW + 20_000` (`state.nowMillis == justNow`, `!state.stale`, `SyncHealth.OK`) and that an older `lastSuccessAt` leaves `state.nowMillis == NOW`.

2. **`README.md` ([README.md](../../README.md#L64-L105), [README.md](../../README.md#L330-L339)) and `docs/DEVICE-TESTS.md` ([DEVICE-TESTS.md](../../docs/DEVICE-TESTS.md#L58-L96)) accurately reflect the Obtainium v0.1.0 test:**
   - Covers Obtainium 1.7.4's exact button labels (**Add** -> *App source URL* -> **+** -> listed as `"ttsync"` before install -> **Install**), first-start dialogs, the "Install unknown apps" flow, Play Protect's **App scan recommended** behavior (including auto-starting the scan after ~15 s and the fallback "unknown developer" wording), Google's 2026/2027 developer verification note, and Obtainium's *Certificate Hash* check in both the English and German sections.
   - `docs/DEVICE-TESTS.md` cleanly records Run 3, marks R1 as verified on-device, and documents O1 and its fix.
