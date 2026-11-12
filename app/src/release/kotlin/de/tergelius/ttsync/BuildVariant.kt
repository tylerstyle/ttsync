package de.tergelius.ttsync

import android.content.Context
import de.tergelius.ttsync.timetree.RawResponseSink

/**
 * Release wiring. `src/debug` has the same object with debug behaviour; [AppGraph] calls it
 * without knowing the variant.
 */
internal object BuildVariant {
  /** Release builds never dump API responses. */
  @Suppress("UNUSED_PARAMETER")
  fun rawResponseSink(context: Context): RawResponseSink = RawResponseSink.None
}
