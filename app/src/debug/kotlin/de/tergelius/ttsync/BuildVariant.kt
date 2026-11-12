package de.tergelius.ttsync

import android.content.Context
import de.tergelius.ttsync.timetree.FileRawResponseSink
import de.tergelius.ttsync.timetree.RawResponseSink
import java.io.File

/**
 * Debug-only wiring. `src/release` has the same object with release behaviour; [AppGraph] calls
 * it without knowing the variant.
 */
internal object BuildVariant {
  /** Raw API dumps to `files/debug/raw/<run>/` (DESIGN.md section 3). */
  fun rawResponseSink(context: Context): RawResponseSink =
    FileRawResponseSink(File(context.filesDir, "debug/raw"))
}
