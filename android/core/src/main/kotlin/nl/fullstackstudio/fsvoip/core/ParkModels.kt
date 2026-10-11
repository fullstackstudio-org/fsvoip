// SPDX-License-Identifier: AGPL-3.0-or-later
//
// Kotlin mirror of `/calls/park` and `/parked`. Parking puts the own running call on hold in a numbered slot; picking it up
// is dialling `retrieveNumber` (`*5901`) as a normal call. After the park time-out the call rings back at the extension that
// parked it.
//
// 🚨 `park_uncertain`: the PBX failed while parking and the call MAY be parked. Never park again: refresh the list.

package nl.fullstackstudio.fsvoip.core

import java.time.Instant
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** A timestamp the PBX sent in a shape we cannot read is "unknown", not a reason to lose the whole list. */
object LenientInstantSerializer : KSerializer<Instant?> {
    override val descriptor = PrimitiveSerialDescriptor("nl.fullstackstudio.fsvoip.LenientInstant", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Instant?) = encoder.encodeString(value?.toString() ?: "")

    override fun deserialize(decoder: Decoder): Instant? = try {
        FsJson.parseTimestamp(decoder.decodeString())
    } catch (_: Exception) {
        null
    }
}

/** `POST /calls/park`. `callId` is the SIP `Call-ID` of the leg of the app; the extension that parks comes from the pairing. */
data class ParkRequest(val callId: String) {
    fun toJson(): JsonObject = buildJsonObject { put("callId", JsonPrimitive(callId)) }

    override fun toString(): String = "ParkRequest(callId=•••)"
}

@Serializable
data class ParkedBy(
    /** The app pairing that parked it; `null` = parked from another phone of the PBX. */
    val deviceId: String? = null,
    val name: String = "",
)

@Serializable
data class ParkedCall(
    /** Opaque, sealed per PBX (never a channel id). Goes into `DELETE /parked/{id}`. */
    val id: String,
    val slot: Int,
    /** The number that picks the call up when dialled as a normal call (`*5901`); `null` = unknown. */
    val retrieveNumber: String? = null,
    val callerNumber: String? = null,
    val callerName: String? = null,
    @Serializable(with = LenientInstantSerializer::class) val parkedAt: Instant? = null,
    /** After this the call rings back at the extension that parked it. */
    @Serializable(with = LenientInstantSerializer::class) val expiresAt: Instant? = null,
    /** `null` = parked outside FullStack Studio. */
    val parkedBy: ParkedBy? = null,
    /** Parked by this pairing. */
    val mine: Boolean = false,
)

/** `GET /parked`. */
@Serializable
data class ParkedCallsPage(
    val calls: List<ParkedCall> = emptyList(),
    /** `false` = parking does not work on this PBX right now: hide the section. */
    val available: Boolean = false,
)
