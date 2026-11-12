package de.tergelius.ttsync

import android.util.Log

/** [TtLog] on logcat. Release builds strip `Log.v/d/i` with R8 (phase 7), so keep `w`/`e` rare. */
object AndroidTtLog : TtLog {
  override fun d(message: String) {
    Log.d(TtLog.TAG, message)
  }

  override fun i(message: String) {
    Log.i(TtLog.TAG, message)
  }

  override fun w(message: String, error: Throwable?) {
    Log.w(TtLog.TAG, message, error)
  }

  override fun e(message: String, error: Throwable?) {
    Log.e(TtLog.TAG, message, error)
  }
}
