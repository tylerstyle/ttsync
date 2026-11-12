package de.tergelius.ttsync.timetree

import java.io.File

/**
 * Debug builds only: writes each data response body to `<baseDir>/<runId>/<NNN>_<name>.json` and
 * keeps the newest [RawResponseSink.KEEP_RUNS] runs (DESIGN.md section 3). The counter prefix keeps
 * the request order and stops a retried request from overwriting the first answer. Run ids from
 * `SyncRequest.newRunId` sort by time, so "newest" means the greatest name; the current run is
 * always kept.
 *
 * Pure JVM (`java.io.File`), so it can be tested with a temporary folder. Every failure is
 * swallowed: a dump must never break a sync. The client never passes login traffic or headers.
 */
class FileRawResponseSink(private val baseDir: File) : RawResponseSink {
  private var runDir: File? = null
  private var counter = 0

  @Synchronized
  override fun beginRun(runId: String) {
    counter = 0
    runDir = null
    try {
      val dir = File(baseDir, safeName(runId).ifEmpty { "run" })
      if (!dir.isDirectory && !dir.mkdirs()) return
      runDir = dir
      val others =
        baseDir.listFiles()?.filter { it.isDirectory && it.name != dir.name }.orEmpty()
      others
        .sortedByDescending { it.name }
        .drop(RawResponseSink.KEEP_RUNS - 1)
        .forEach { it.deleteRecursively() }
    } catch (e: Exception) {
      // Debug aid only.
    }
  }

  @Synchronized
  override fun record(name: String, body: String) {
    val dir = runDir ?: return
    try {
      counter++
      val prefix = counter.toString().padStart(3, '0')
      val file = File(dir, "${prefix}_${safeName(name).ifEmpty { "response" }}.json")
      file.writeText(body)
    } catch (e: Exception) {
      // Debug aid only.
    }
  }

  @Synchronized
  override fun wipe() {
    runDir = null
    counter = 0
    try {
      baseDir.deleteRecursively()
    } catch (e: Exception) {
      // Debug aid only.
    }
  }

  private fun safeName(name: String): String = name.replace(UNSAFE, "_").trim('_', '.').take(120)

  private companion object {
    val UNSAFE = Regex("[^A-Za-z0-9_.-]")
  }
}
