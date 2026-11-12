package de.tergelius.ttsync.timetree

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * Lenient decoding for the fields that only the filters and their screen use (0.2.0): a calendar's
 * labels and members, an event's attendees and author, a label's colours and order.
 *
 * Why: a strict field that TimeTree sends in an unexpected shape fails the whole object. For an
 * event that means it is skipped as malformed and never copied; for `GET /calendars` it means
 * `TtError.ApiChanged` and no run succeeds. Neither is acceptable for data the copies hardly
 * depend on, so a value that does not decode becomes null, and a list keeps the elements that do.
 */

/** Decodes [element]; null if it is JSON null or does not decode. */
private fun <T> JsonDecoder.decodeOrNull(serializer: KSerializer<T>, element: JsonElement): T? {
  if (element is JsonNull) return null
  // The decoder does not fail cleanly on a wrong shape (an array where a number is expected throws
  // IndexOutOfBoundsException), so the shape is checked first.
  val shapeFits =
    when (serializer.descriptor.kind) {
      is PrimitiveKind -> element is JsonPrimitive
      StructureKind.CLASS -> element is JsonObject
      else -> true
    }
  if (!shapeFits) return null
  return try {
    json.decodeFromJsonElement(serializer, element)
  } catch (_: SerializationException) {
    null
  } catch (_: IllegalArgumentException) {
    null
  }
}

/**
 * A nullable value that reads as null when it does not decode. Use it only on nullable properties
 * with a default (`= null`).
 */
internal open class LenientSerializer<T : Any>(private val inner: KSerializer<T>) : KSerializer<T?> {
  private val strict = inner.nullable

  override val descriptor: SerialDescriptor = strict.descriptor

  override fun deserialize(decoder: Decoder): T? {
    val json = decoder as? JsonDecoder ?: return strict.deserialize(decoder)
    return json.decodeOrNull(inner, json.decodeJsonElement())
  }

  override fun serialize(encoder: Encoder, value: T?) = strict.serialize(encoder, value)
}

/**
 * A list that keeps the elements that decode and drops the others; anything but an array (null
 * included) reads as an empty list. Use it only on list properties with a default.
 */
internal open class LenientListSerializer<T : Any>(private val element: KSerializer<T>) :
  KSerializer<List<T>> {
  private val strict = ListSerializer(element)

  override val descriptor: SerialDescriptor = strict.descriptor

  override fun deserialize(decoder: Decoder): List<T> {
    val json = decoder as? JsonDecoder ?: return strict.deserialize(decoder)
    val array = json.decodeJsonElement() as? JsonArray ?: return emptyList()
    return array.mapNotNull { json.decodeOrNull(element, it) }
  }

  override fun serialize(encoder: Encoder, value: List<T>) = strict.serialize(encoder, value)
}

internal object LenientLong : LenientSerializer<Long>(Long.serializer())

internal object LenientInt : LenientSerializer<Int>(Int.serializer())

internal object LenientLongList : LenientListSerializer<Long>(Long.serializer())

internal object LenientLabelList : LenientListSerializer<TtLabel>(TtLabel.serializer())

internal object LenientUserList : LenientListSerializer<TtCalendarUser>(TtCalendarUser.serializer())
