package de.tergelius.ttsync

/**
 * Logging for code that must stay free of `android.*` (`timetree`, `mapping`, `SyncEngine`). The
 * app passes [AndroidTtLog] (tag [TAG]); tests pass [None] or a recording fake.
 *
 * Privacy rule (DESIGN.md section 6): messages carry only counts, HTTP status, error codes and
 * 8-character uuid prefixes ([short]); never credentials, cookies, calendar names or event content.
 * Exceptions passed to [w] and [e] must follow the same rule in their messages (`TtError` does).
 */
interface TtLog {
  fun d(message: String)

  fun i(message: String)

  fun w(message: String, error: Throwable? = null)

  fun e(message: String, error: Throwable? = null)

  object None : TtLog {
    override fun d(message: String) = Unit

    override fun i(message: String) = Unit

    override fun w(message: String, error: Throwable?) = Unit

    override fun e(message: String, error: Throwable?) = Unit
  }

  companion object {
    /** `adb logcat -s TtSync` */
    const val TAG = "TtSync"

    /** The only form in which a uuid may appear in a log line. */
    fun short(uuid: String): String = uuid.take(8)
  }
}
