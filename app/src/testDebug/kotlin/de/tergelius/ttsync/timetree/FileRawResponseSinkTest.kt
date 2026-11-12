package de.tergelius.ttsync.timetree

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The debug-only raw dump (DESIGN.md section 3). Lives in `testDebug` like its subject. */
class FileRawResponseSinkTest {
  @get:Rule val tmp = TemporaryFolder()

  private val base: File by lazy { File(tmp.root, "debug/raw") }
  private val sink by lazy { FileRawResponseSink(base) }

  @Test
  fun writesBodiesInRequestOrder() {
    sink.beginRun("20261112-080000-0001")
    sink.record("calendars", """{"calendars":[]}""")
    sink.record("calendar_7_events_since_100", "page")
    sink.record("calendar_7_events_since_100", "retried page")

    val dir = File(base, "20261112-080000-0001")
    assertEquals(
      listOf(
        "001_calendars.json",
        "002_calendar_7_events_since_100.json",
        "003_calendar_7_events_since_100.json",
      ),
      dir.list()!!.sorted(),
    )
    assertEquals("""{"calendars":[]}""", File(dir, "001_calendars.json").readText())
    assertEquals("retried page", File(dir, "003_calendar_7_events_since_100.json").readText())
  }

  @Test
  fun keepsTheNewestFiveRuns() {
    for (i in 1..7) {
      sink.beginRun("20261112-08000$i-abcd")
      sink.record("calendars", "run $i")
    }
    assertEquals((3..7).map { "20261112-08000$it-abcd" }, base.list()!!.sorted())

    // The current run is kept even if its name sorts first.
    sink.beginRun("00000000-000000-0000")
    assertEquals(5, base.list()!!.size)
    assertTrue(File(base, "00000000-000000-0000").isDirectory)
    assertTrue(File(base, "20261112-080007-abcd").isDirectory)
  }

  @Test
  fun namesCannotEscapeTheRunFolder() {
    sink.beginRun("../../escape")
    sink.record("../../../etc/passwd", "x")

    val written = base.walkTopDown().filter { it.isFile }.toList()
    assertEquals(1, written.size)
    assertTrue(written.single().canonicalPath.startsWith(base.canonicalPath))
    assertFalse(File(tmp.root, "escape").exists())
  }

  @Test
  fun recordWithoutRunWritesNothing() {
    sink.record("calendars", "x")
    assertFalse(base.exists())
  }

  @Test
  fun wipeDeletesEverything() {
    sink.beginRun("r1")
    sink.record("calendars", "x")
    sink.wipe()
    assertFalse(base.exists())

    sink.record("calendars", "after wipe") // no run any more
    assertFalse(base.exists())
  }

  @Test
  fun failuresAreSwallowed() {
    val blocker = tmp.newFile("not-a-dir")
    val broken = FileRawResponseSink(File(blocker, "raw"))
    broken.beginRun("r1")
    broken.record("calendars", "x")
    broken.wipe()
  }
}
