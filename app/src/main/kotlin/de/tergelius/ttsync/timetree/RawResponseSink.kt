package de.tergelius.ttsync.timetree

/**
 * Debug dump of raw API responses (DESIGN.md section 3, "Debug dump").
 *
 * The `src/debug` implementation (`FileRawResponseSink`) writes each response body to
 * `files/debug/raw/<run>/` and keeps the last [KEEP_RUNS] runs; read it with
 * `adb shell run-as de.tergelius.ttsync`. Release builds use [None]. The variant is picked by
 * `BuildVariant.rawResponseSink` in the debug and release source sets.
 *
 * Contract: never receives login requests or responses, and never headers or cookies. Failures to
 * write are swallowed (a dump must never break a sync).
 */
interface RawResponseSink {
  /** Starts a new run folder and deletes all but the newest [KEEP_RUNS] runs. */
  fun beginRun(runId: String)

  /** Stores one response body of the current run under a file-name-safe [name]. */
  fun record(name: String, body: String)

  /** Deletes every dump (sign-out). */
  fun wipe()

  /** Does nothing. Used in release builds and in tests. */
  object None : RawResponseSink {
    override fun beginRun(runId: String) = Unit

    override fun record(name: String, body: String) = Unit

    override fun wipe() = Unit
  }

  companion object {
    const val KEEP_RUNS = 5
  }
}
