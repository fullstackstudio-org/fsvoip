// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.core

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * A string that must never end up in a log, a crash report or a debugger print-out (SIP password, device token).
 *
 * `toString()` hides the value; the only way to read it is the explicit [reveal]. It serializes to its real value
 * because it has to be stored (encrypted) and sent (request bodies).
 */
@Serializable(with = SecretSerializer::class)
class Secret(private val value: String) {
    fun reveal(): String = value

    val isEmpty: Boolean
        get() = value.isEmpty()

    override fun toString(): String = "Secret(•••)"

    override fun equals(other: Any?): Boolean = other is Secret && other.value == value

    override fun hashCode(): Int = value.hashCode()
}

object SecretSerializer : KSerializer<Secret> {
    override val descriptor = PrimitiveSerialDescriptor("nl.fullstackstudio.fsvoip.Secret", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Secret) = encoder.encodeString(value.reveal())

    override fun deserialize(decoder: Decoder): Secret = Secret(decoder.decodeString())
}
