# Documentation Update Review (v0.1.0 → v0.1.1 Update Test) — `android-agy`

**Verdict: Ready to commit**

Reviewed the documentation diff across [README.md](../../README.md) and [docs/DEVICE-TESTS.md](../DEVICE-TESTS.md) against the v0.1.0 → v0.1.1 Obtainium update test and the corrected v0.1.0 Obtainium install test.

---

## Findings

1. **Play Protect scan flow corrected without overclaiming ([README.md](../../README.md), [docs/DEVICE-TESTS.md](../DEVICE-TESTS.md)):**
   - Removes the earlier claim that Play Protect starts the cloud scan automatically after ~15 s.
   - Both the English and German sections of [README.md](../../README.md) now accurately state that the user taps **Scan app** and, once the scan finishes, taps **Install**, and that Play Protect prompts again on every update.
   - Run 3 in [docs/DEVICE-TESTS.md](../DEVICE-TESTS.md) is updated to match the corrected observation.

2. **Obtainium update and adoption workflow accurately described ([README.md](../../README.md)):**
   - Step 4 under "With Obtainium" matches the observed UI (`"0.1.0 → v0.1.1"` with **Update**, system `"Update this app?"` prompt, Play Protect scan prompt, and in-place preservation of sign-in, settings, and copies).
   - The note that Obtainium adopts an already-installed release from the releases page without reinstalling matches the Run 4 setup and `installerPackageName` transition.

3. **Run 4 and remaining untested items accurately recorded ([docs/DEVICE-TESTS.md](../DEVICE-TESTS.md)):**
   - Run 4 documents all 5 baseline checkpoints, the in-place update (`versionCode` 101, unchanged `firstInstallTime` and `appId`, preserved permissions, battery exemption, WorkManager job, sign-in, settings, and byte-identical copies), and on-device verification of the `O1` fix (`"(0 minutes ago)"`) and `R1` (`"Synced 14 s ago, nothing to do"`).
   - The "Not tested yet" section removes `O1` and the Obtainium update flow, and explicitly notes that Obtainium's background update checks and notifications remain untested because updates in Runs 3 and 4 were triggered on add / pull-to-refresh with Obtainium notifications denied.
