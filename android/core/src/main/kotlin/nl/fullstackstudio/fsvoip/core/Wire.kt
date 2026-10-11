// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.core

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * "Responses lenient, requests strict" for string enums of the API: an unknown value decodes to the enum's `UNKNOWN`
 * case instead of failing the whole answer, and `UNKNOWN` can never be sent. Usage:
 *
 * ```
 * @Serializable(with = AppRole.Serializer::class)
 * enum class AppRole(override val wire: String) : WireEnum {
 *     USER("user"), ADMIN("admin"), UNKNOWN("");
 *     object Serializer : TolerantEnumSerializer<AppRole>("AppRole", AppRole.entries, UNKNOWN)
 * }
 * ```
 */
interface WireEnum {
    val wire: String
}

open class TolerantEnumSerializer<E>(
    name: String,
    private val values: List<E>,
    private val unknown: E,
    private val strictEncode: Boolean = true,
) : KSerializer<E> where E : Enum<E>, E : WireEnum {
    override val descriptor = PrimitiveSerialDescriptor("nl.fullstackstudio.fsvoip.$name", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): E {
        val raw = decoder.decodeString()

        return values.firstOrNull { it != unknown && it.wire == raw } ?: unknown
    }

    override fun serialize(encoder: Encoder, value: E) {
        require(!strictEncode || value != unknown) { "`unknown` is a decoding fallback and cannot be sent" }
        encoder.encodeString(value.wire)
    }
}
