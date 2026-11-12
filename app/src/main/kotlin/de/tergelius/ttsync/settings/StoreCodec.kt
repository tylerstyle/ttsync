package de.tergelius.ttsync.settings

import de.tergelius.ttsync.calendar.TargetCalendar
import de.tergelius.ttsync.sync.SyncReport
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * The values DataStore keeps as JSON text: the selected sources, the target and the last report.
 * Pure Kotlin, so JVM tests check what older versions stored.
 *
 * Compatibility rules, for every type stored here:
 * - New fields get a default. Defaults are not written (`encodeDefaults` is off), so a value
 *   without the new field is stored byte for byte as before, and older values decode.
 * - Unknown keys are ignored, so a newer version's value still decodes after a downgrade (the
 *   unknown parts are lost when the older version writes it again).
 * - A value that does not decode reads as missing (set up again), never as an error.
 */
object StoreCodec {
  private val json = Json { ignoreUnknownKeys = true }
  private val SOURCES = ListSerializer(SourceCalendar.serializer())

  fun encodeSources(sources: List<SourceCalendar>): String = json.encodeToString(SOURCES, sources)

  /** The stored selection, or empty if there is none or it does not decode. */
  fun decodeSources(text: String?): List<SourceCalendar> = decodeOrNull(SOURCES, text).orEmpty()

  fun encodeTarget(target: TargetCalendar): String =
    json.encodeToString(TargetCalendar.serializer(), target)

  fun decodeTarget(text: String?): TargetCalendar? = decodeOrNull(TargetCalendar.serializer(), text)

  fun encodeReport(report: SyncReport): String = json.encodeToString(SyncReport.serializer(), report)

  fun decodeReport(text: String?): SyncReport? = decodeOrNull(SyncReport.serializer(), text)

  /** Reads a JSON value, or null if it is missing or no longer decodes. */
  private fun <T> decodeOrNull(serializer: KSerializer<T>, text: String?): T? =
    text?.let {
      try {
        json.decodeFromString(serializer, it)
      } catch (_: SerializationException) {
        null
      } catch (_: IllegalArgumentException) {
        null
      }
    }
}
