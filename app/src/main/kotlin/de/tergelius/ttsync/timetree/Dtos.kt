package de.tergelius.ttsync.timetree

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/*
 * Wire DTOs of the TimeTree web API (DESIGN.md sections 3 and 4).
 *
 * Stability contract for the parallel phases: fields are only ever added, each with a default, so
 * existing callers keep compiling. Phase 2 may refine how a field is decoded (for example a custom
 * serializer if the real dumps send a number where a string is expected) but keeps the Kotlin type.
 *
 * Privacy: event and calendar content (titles, notes, names) must never reach logs, so the
 * toString() of every DTO that carries content is redacted.
 */

/** The single [Json] configuration for TimeTree payloads and for snapshot JSON in `SyncDb`. */
val TtJson: Json = Json {
  ignoreUnknownKeys = true
  coerceInputValues = true
}

/** `GET /calendars?since=0` */
@Serializable
data class TtCalendarsResponse(val calendars: List<TtCalendar>)

/** One TimeTree calendar the account can see. Its [id] is what every other endpoint uses. */
@Serializable
data class TtCalendar(
  val id: Long,
  val name: String? = null,
  @SerialName("deactivated_at") val deactivatedAt: Long? = null,
) {
  /** A deactivated calendar counts as lost access ([TtError.AccessLost]). */
  val isActive: Boolean
    get() = deactivatedAt == null

  override fun toString(): String = "TtCalendar(id=$id, active=$isActive)"
}

/** `GET /calendar/{id}/labels` */
@Serializable
data class TtLabelsResponse(val labels: List<TtLabel>)

/** A label of one calendar. Only the name is used (`Label: <name>` in the description). */
@Serializable
data class TtLabel(val id: Long, val name: String? = null) {
  override fun toString(): String = "TtLabel(id=$id)"
}

/**
 * One page of `GET /calendar/{id}/events/sync[?since=N]`. The events stay raw [JsonObject]s so
 * that each one can be stored verbatim as a snapshot; decode them with [TtEvent.fromJson].
 */
@Serializable
data class TtEventsPage(
  val events: List<JsonObject>,
  val chunk: Boolean,
  val since: Long,
) {
  override fun toString(): String = "TtEventsPage(events=${events.size}, chunk=$chunk, since=$since)"
}

/**
 * A TimeTree event as the mapper needs it. Times are epoch milliseconds. For all-day events
 * [startAt] and [endAt] are UTC midnights and [endAt] is inclusive (a one-day event has
 * `startAt == endAt`).
 *
 * An event with [recurringUuid] or [parentId] is an override (an edited or deleted occurrence of a
 * series). A deleted event has [deactivatedAt] set (a tombstone).
 */
@Serializable
data class TtEvent(
  val uuid: String,
  @SerialName("calendar_id") val calendarId: Long? = null,
  val title: String? = null,
  @SerialName("all_day") val allDay: Boolean,
  @SerialName("start_at") val startAt: Long,
  @SerialName("end_at") val endAt: Long,
  @SerialName("start_timezone") val startTimezone: String? = null,
  @SerialName("end_timezone") val endTimezone: String? = null,
  @SerialName("label_id") val labelId: Long? = null,
  val location: String? = null,
  val url: String? = null,
  val note: String? = null,
  /** iCal lines such as `RRULE:FREQ=WEEKLY;UNTIL=20261231`, `EXDATE;TZID=…:…`, `RDATE:…`. */
  val recurrences: List<String> = emptyList(),
  @SerialName("recurring_uuid") val recurringUuid: String? = null,
  @SerialName("parent_id") val parentId: String? = null,
  val type: Int = 0,
  val category: Int = 1,
  @SerialName("deactivated_at") val deactivatedAt: Long? = null,
  @SerialName("updated_at") val updatedAt: Long,
  @SerialName("created_at") val createdAt: Long? = null,
) {
  val isBirthday: Boolean
    get() = type == TYPE_BIRTHDAY

  val isMemo: Boolean
    get() = category == CATEGORY_MEMO

  val isDeactivated: Boolean
    get() = deactivatedAt != null

  val isOverride: Boolean
    get() = recurringUuid != null || parentId != null

  override fun toString(): String =
    "TtEvent(uuid=${uuid.take(8)}, allDay=$allDay, override=$isOverride, deactivated=$isDeactivated)"

  companion object {
    const val TYPE_BIRTHDAY = 1
    const val CATEGORY_MEMO = 2

    /** Decodes one event (a page entry or a snapshot). Malformed JSON becomes [TtError.ApiChanged]. */
    fun fromJson(json: String): TtEvent =
      try {
        TtJson.decodeFromString(serializer(), json)
      } catch (e: SerializationException) {
        throw TtError.ApiChanged("event JSON did not decode", e)
      } catch (e: IllegalArgumentException) {
        throw TtError.ApiChanged("event JSON did not decode", e)
      }

    /** Decodes one raw page entry. Malformed JSON becomes [TtError.ApiChanged]. */
    fun fromJson(json: JsonObject): TtEvent =
      try {
        TtJson.decodeFromJsonElement(serializer(), json)
      } catch (e: SerializationException) {
        throw TtError.ApiChanged("event JSON did not decode", e)
      } catch (e: IllegalArgumentException) {
        throw TtError.ApiChanged("event JSON did not decode", e)
      }
  }
}

/** Body of `PUT /auth/email/signin`. */
@Serializable
data class TtSignInRequest(val uid: String, val password: String, val uuid: String) {
  override fun toString(): String = "TtSignInRequest(<redacted>)"
}

/** Body of an error response: `{"error":{"code":N,"message":"…"}}`. */
@Serializable
data class TtErrorResponse(val error: TtErrorBody? = null)

@Serializable
data class TtErrorBody(val code: Int? = null, val message: String? = null)
