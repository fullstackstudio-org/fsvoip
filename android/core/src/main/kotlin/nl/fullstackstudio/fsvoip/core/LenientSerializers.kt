// SPDX-License-Identifier: AGPL-3.0-or-later
//
// Small decoding helpers for "a value we cannot read is unknown, not a reason to lose the whole answer" (the iOS `try?`
// decodes). Responses are lenient, requests strict.

package nl.fullstackstudio.fsvoip.core

import java.time.Instant
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull

/** Decodes with [inner]; a value that cannot be read (wrong type, bad shape) becomes `null`. */
open class LenientSerializer<T : Any>(private val inner: KSerializer<T>) : KSerializer<T?> {
    override val descriptor: SerialDescriptor = inner.nullable.descriptor

    override fun deserialize(decoder: Decoder): T? {
        val json = decoder as? JsonDecoder ?: return inner.deserialize(decoder)
        val element = json.decodeJsonElement()

        if (element is JsonNull) return null

        return try {
            json.json.decodeFromJsonElement(inner, element)
        } catch (_: Exception) {
            null
        }
    }

    override fun serialize(encoder: Encoder, value: T?) {
        if (value == null) encoder.encodeNull() else encoder.encodeSerializableValue(inner, value)
    }
}

/** An ISO timestamp the server may send in a shape we cannot read: then it is unknown. */
object LenientInstantSerializer : KSerializer<Instant?> by LenientSerializer(InstantSerializer)

/** A counter from the server: a missing, negative or unreadable number is "nothing". */
object NonNegativeIntSerializer : KSerializer<Int> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("nl.fullstackstudio.fsvoip.NonNegativeInt", PrimitiveKind.INT)

    override fun deserialize(decoder: Decoder): Int {
        val json = decoder as? JsonDecoder ?: return decoder.decodeInt().coerceAtLeast(0)

        return try {
            json.json.decodeFromJsonElement(Int.serializer(), json.decodeJsonElement()).coerceAtLeast(0)
        } catch (_: Exception) {
            0
        }
    }

    override fun serialize(encoder: Encoder, value: Int) = encoder.encodeInt(value)
}
