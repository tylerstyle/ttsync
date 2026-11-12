package de.tergelius.ttsync.timetree

import java.io.File

/**
 * Debug builds only: writes each data response body to `<baseDir>/<runId>/<name>` and keeps the
 * newest [RawResponseSink.KEEP_RUNS] runs (DESIGN.md section 3). Phase 2 implements it. Pure JVM
 * (`java.io.File`), so it can be tested with a temporary folder.
 */
class FileRawResponseSink(private val baseDir: File) : RawResponseSink {
  override fun beginRun(runId: String): Unit = notYet()

  override fun record(name: String, body: String): Unit = notYet()

  override fun wipe(): Unit = notYet()

  private fun notYet(): Nothing = TODO("phase 2: FileRawResponseSink (${baseDir.name})")
}
