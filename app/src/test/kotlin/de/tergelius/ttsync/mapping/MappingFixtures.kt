package de.tergelius.ttsync.mapping

import de.tergelius.ttsync.timetree.TtEvent
import de.tergelius.ttsync.timetree.TtEventsPage
import de.tergelius.ttsync.timetree.TtJson
import de.tergelius.ttsync.timetree.TtLabel
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * Fixtures under `src/test/resources/fixtures/mapping/`:
 * - `real-20261112/`: the sanitized raw dump of the TimeTree test events (ids, calendar and author
 *   replaced; titles, texts and timestamps kept). Taken on 2026-11-12 in Europe/Berlin.
 * - `synthetic/`: hand-made events in the same page shape, one uuid per test case (`m01-…`).
 * - `rename/`: a synthetic pair modelled on the label rename of 2026-11-12: the labels before and
 *   after two of them were renamed (same ids and colours, names and order changed) and the events,
 *   which a rename does not change.
 */
internal object MappingFixtures {
  val TODAY: LocalDate = LocalDate.of(2026, 11, 12)
  val BERLIN: ZoneId = ZoneId.of("Europe/Berlin")
  const val HOUR = 3_600_000L
  const val DAY = EventSpec.DAY_MS

  const val REAL = "real-20261112"
  /** Real uuids after sanitizing: `%032x` of the position in the page. */
  const val DETAILS = "00000000000000000000000000000001"
  const val ALL_DAY = "00000000000000000000000000000002"
  const val WEEKLY = "00000000000000000000000000000003"
  const val MOVED = "00000000000000000000000000000004"

  private fun text(path: String): String =
    requireNotNull(javaClass.getResource("/fixtures/mapping/$path")) { "missing fixture $path" }
      .readText()

  /** Events of a page fixture, decoded like the client decodes them. */
  fun events(path: String): List<TtEvent> =
    TtJson.decodeFromString(TtEventsPage.serializer(), text(path)).events.map(TtEvent::fromJson)

  fun realEvents(): List<TtEvent> = events("$REAL/events-page-000.json")

  /** Labels in the real `GET /calendar/{id}/labels` shape: `{"calendar_labels": […], "since": N}`. */
  fun labels(path: String): Map<Long, TtLabel> =
    TtJson.parseToJsonElement(text(path))
      .jsonObject
      .getValue("calendar_labels")
      .jsonArray
      .map { TtJson.decodeFromJsonElement(TtLabel.serializer(), it) }
      .associateBy { it.id }

  fun map(
    events: Collection<TtEvent>,
    labels: Map<Long, TtLabel> = emptyMap(),
    today: LocalDate = TODAY,
    zone: ZoneId = BERLIN,
    filter: EventFilter? = null,
  ): MappingResult = DefaultEventMapper().map(MappingInput(events, labels, today, zone, filter))

  fun berlin(y: Int, mo: Int, d: Int, h: Int = 0, mi: Int = 0, s: Int = 0): Long =
    ZonedDateTime.of(y, mo, d, h, mi, s, 0, BERLIN).toInstant().toEpochMilli()

  fun utcDay(y: Int, mo: Int, d: Int): Long = LocalDate.of(y, mo, d).toEpochDay() * DAY
}

internal fun MappingResult.write(uuid: String): Desired.Write =
  when (val d = desired[uuid]) {
    is Desired.Write -> d
    else -> throw AssertionError("$uuid: expected Write, got $d")
  }

internal fun MappingResult.spec(uuid: String): EventSpec = write(uuid).spec

internal fun List<TtEvent>.byUuid(uuid: String): TtEvent = single { it.uuid == uuid }
