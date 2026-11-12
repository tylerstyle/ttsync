package de.tergelius.ttsync.timetree

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

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

/**
 * One TimeTree calendar the account can see. Its [id] is what every other endpoint uses.
 *
 * [labels] and [users] come embedded in the same response (no extra request) and are for the
 * filter screen: the labels are the same objects as `GET /calendar/{id}/labels` returns, and
 * [users] are the calendar's members ("Members" in TimeTree), whose ids appear in
 * [TtEvent.attendees]. Both decode leniently ([LenientListSerializer]), so a surprise there never
 * fails the calendar list.
 */
@Serializable
data class TtCalendar(
  val id: Long,
  val name: String? = null,
  @SerialName("deactivated_at") val deactivatedAt: Long? = null,
  @SerialName("calendar_labels")
  @Serializable(with = LenientLabelList::class)
  val labels: List<TtLabel> = emptyList(),
  @SerialName("calendar_users")
  @Serializable(with = LenientUserList::class)
  val users: List<TtCalendarUser> = emptyList(),
) {
  /** A deactivated calendar counts as lost access ([TtError.AccessLost]). */
  val isActive: Boolean
    get() = deactivatedAt == null

  override fun toString(): String =
    "TtCalendar(id=$id, active=$isActive, labels=${labels.size}, users=${users.size})"
}

/**
 * A member of a calendar (`calendar_users`). [userId] is what [TtEvent.attendees] and
 * [TtEvent.authorId] refer to. A member who left has [deactivatedAt] set. [role] is TimeTree's
 * code (1 for the owner in the dumps); it is not interpreted.
 */
@Serializable
data class TtCalendarUser(
  @SerialName("user_id") val userId: Long,
  val name: String? = null,
  @SerialName("deactivated_at")
  @Serializable(with = LenientLong::class)
  val deactivatedAt: Long? = null,
  @Serializable(with = LenientInt::class) val role: Int? = null,
) {
  val isActive: Boolean
    get() = deactivatedAt == null

  /** Redacted: names and user ids are personal data. */
  override fun toString(): String = "TtCalendarUser(<redacted>, active=$isActive)"
}

/**
 * `GET /calendar/{id}/labels`. The API sends the list as `calendar_labels` (real dump, 2026-11-12);
 * `labels` is accepted too. Labels only add a line to the description, so a response without either
 * key is an empty list rather than [TtError.ApiChanged]. Unnamed labels have `name: ""`.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class TtLabelsResponse(
  @JsonNames("calendar_labels") val labels: List<TtLabel> = emptyList(),
)

/**
 * A label of one calendar (ids 1–10 in the dumps). Filters refer to labels by [id] only, never by
 * name: users rename labels, and renaming changes only [name] (device check 2026-11-12).
 *
 * TimeTree shows an unnamed label (`name: ""`) by the name of its [color], so does the app
 * (`mapping.LabelNames`). Colours are RGB ints (`0x2ECC87`); a label can be recoloured, so [color]
 * may differ from [defaultColor]. [order] is the display order, which the user can change. The
 * three are null in labels cached by 0.1.x (`cursor.labels_json`) and decode leniently.
 */
@Serializable
data class TtLabel(
  val id: Long,
  val name: String? = null,
  @Serializable(with = LenientLong::class) val color: Long? = null,
  @SerialName("default_color")
  @Serializable(with = LenientLong::class)
  val defaultColor: Long? = null,
  @Serializable(with = LenientInt::class) val order: Int? = null,
) {
  override fun toString(): String = "TtLabel(id=$id)"
}

/**
 * One page of `GET /calendar/{id}/events/sync[?since=N]`, newest change first. [since] asks for the
 * next older page and [chunk] is false on the oldest one (see [EventFeed]). The events stay raw
 * [JsonObject]s so that each one can be stored verbatim as a snapshot; decode them with
 * [TtEvent.fromJson].
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
 *
 * Tombstones decode leniently: if one lacks `all_day`, `start_at`, `end_at` or `updated_at` (or
 * sends null), it gets [allDay] false, [startAt] and [endAt] 0 (meaning "unknown", never a real
 * time) and [updatedAt] = [deactivatedAt]. Only [uuid], [recurringUuid], [parentId] and
 * [deactivatedAt] of a tombstone are reliable. A live event without these fields is malformed.
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
  /**
   * User ids of the event's attendees ("Attendees" in TimeTree), matching
   * [TtCalendarUser.userId]. Lenient: ids that are not numbers are dropped, never the event.
   */
  @Serializable(with = LenientLongList::class) val attendees: List<Long> = emptyList(),
  /** User id of whoever created the event; null if unknown. Lenient like [attendees]. */
  @SerialName("author_id")
  @Serializable(with = LenientLong::class)
  val authorId: Long? = null,
) {
  val isBirthday: Boolean
    get() = type == TYPE_BIRTHDAY

  val isMemo: Boolean
    get() = category == CATEGORY_MEMO

  val isDeactivated: Boolean
    get() = deactivatedAt != null

  val isOverride: Boolean
    get() = recurringUuid != null || parentId != null

  /**
   * When this version of the event came to be (TimeTree's clock): the later of [updatedAt] and
   * [deactivatedAt]. The sync cursor's high-water mark is the largest of these (DESIGN.md section 5).
   */
  val changedAt: Long
    get() = maxOf(updatedAt, deactivatedAt ?: updatedAt)

  override fun toString(): String =
    "TtEvent(uuid=${uuid.take(8)}, allDay=$allDay, override=$isOverride, deactivated=$isDeactivated)"

  companion object {
    const val TYPE_BIRTHDAY = 1
    const val CATEGORY_MEMO = 2

    /**
     * Decodes one event (a snapshot or a page entry), with the tombstone defaults above. Malformed
     * JSON becomes [TtError.ApiChanged].
     */
    fun fromJson(json: String): TtEvent =
      decodeTt("event") {
        val element = TtJson.parseToJsonElement(json)
        if (element !is JsonObject) throw SerializationException("event is not an object")
        TtJson.decodeFromJsonElement(serializer(), withTombstoneDefaults(element))
      }

    /** Decodes one raw page entry. Malformed JSON becomes [TtError.ApiChanged]. */
    fun fromJson(json: JsonObject): TtEvent =
      decodeTt("event") { TtJson.decodeFromJsonElement(serializer(), withTombstoneDefaults(json)) }

    /** Fills a tombstone's missing or null required fields; any other object is returned as is. */
    private fun withTombstoneDefaults(json: JsonObject): JsonObject {
      val deactivatedAt = (json["deactivated_at"] as? JsonPrimitive)?.longOrNull ?: return json
      fun missing(key: String) = json[key].let { it == null || it is JsonNull }
      val defaults =
        listOf(
            "all_day" to JsonPrimitive(false),
            "start_at" to JsonPrimitive(0L),
            "end_at" to JsonPrimitive(0L),
            "updated_at" to JsonPrimitive(deactivatedAt),
          )
          .filter { (key, _) -> missing(key) }
      return if (defaults.isEmpty()) json else JsonObject(json + defaults)
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

/**
 * Runs a kotlinx-serialization decode and turns a failure into [TtError.ApiChanged].
 *
 * The decoder's exception is deliberately not kept as the cause: its message can quote the JSON
 * input, which may hold event content, and causes end up in logs. Only the exception's class name
 * survives. Debug builds keep the raw response in the dump for diagnosis.
 */
internal inline fun <T> decodeTt(what: String, decode: () -> T): T =
  try {
    decode()
  } catch (e: SerializationException) {
    throw TtError.ApiChanged("$what JSON did not decode (${e::class.simpleName})")
  } catch (e: IllegalArgumentException) {
    throw TtError.ApiChanged("$what JSON did not decode (${e::class.simpleName})")
  }
