// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.core

import java.time.Instant
import java.time.format.DateTimeParseException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json

/**
 * The JSON conventions of the FSVoip API: camelCase keys and ISO-8601 timestamps that the server writes with
 * milliseconds (`2026-10-08T12:34:56.789Z`). Responses are decoded leniently (unknown fields are ignored: additive API
 * changes must not break the app); requests omit absent optional fields.
 */
object FsJson {
    val default: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    fun parseTimestamp(text: String): Instant? = try {
        Instant.parse(text)
    } catch (_: DateTimeParseException) {
        null
    }
}

/** ISO-8601 timestamp, with or without fractional seconds. */
object InstantSerializer : KSerializer<Instant> {
    override val descriptor = PrimitiveSerialDescriptor("nl.fullstackstudio.fsvoip.Instant", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(value.toString())

    override fun deserialize(decoder: Decoder): Instant {
        val text = decoder.decodeString()

        return FsJson.parseTimestamp(text) ?: throw SerializationException("Invalid timestamp")
    }
}
