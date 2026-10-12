// SPDX-License-Identifier: AGPL-3.0-or-later
//
// Kotlin mirror of `/pbx/live`, `/pbx/live/targets`, `/pbx/live/pickup` and `/pbx/live/transfer` in `shared/openapi.yaml`. Every role sees the
// running calls of the PBX, takes a ringing call over to the OWN extension and transfers (blind, internal only) the call the own extension
// answered; an `admin` transfers every answered call. The server decides (`canPickup`, `canTransfer`); the app follows those flags.
//
// 🚨 `ref` is sealed per PBX and different in every response: always send the `ref` of the latest list. There is no push for this; the app
// polls while the screen is visible.

package nl.fullstackstudio.fsvoip.core

import java.time.Instant
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** A list in which one row we cannot read is dropped instead of losing the whole answer. */
open class LenientListSerializer<T>(private val element: KSerializer<T>) : KSerializer<List<T>> {
    private val delegate = ListSerializer(element)
    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun serialize(encoder: Encoder, value: List<T>) = delegate.serialize(encoder, value)

    override fun deserialize(decoder: Decoder): List<T> {
        val json = decoder as? JsonDecoder ?: return delegate.deserialize(decoder)
        val array = json.decodeJsonElement() as? JsonArray ?: return emptyList()

        return array.mapNotNull { runCatching { json.json.decodeFromJsonElement(element, it) }.getOrNull() }
    }
}

@Serializable
enum class LiveState {
    @SerialName("ringing") RINGING,
    @SerialName("active") ACTIVE,
    @SerialName("held") HELD,
    @SerialName("parked") PARKED,
    @SerialName("unknown") UNKNOWN,
}

@Serializable
enum class LiveDirection {
    @SerialName("inbound") INBOUND,
    @SerialName("outbound") OUTBOUND,
    @SerialName("internal") INTERNAL,
    @SerialName("unknown") UNKNOWN,
}

@Serializable
data class LiveParty(val number: String, val name: String? = null) {
    /** `Receptie (101)`, or just the number. */
    val label: String
        get() = if (!name.isNullOrEmpty()) "$name ($number)" else number
}

@Serializable
data class LiveParked(val slot: Int, val retrieveNumber: String? = null)

@Serializable
data class LiveCall(
    /** Sealed, different in every response. */
    val ref: String,
    val direction: LiveDirection = LiveDirection.UNKNOWN,
    val state: LiveState = LiveState.UNKNOWN,
    val callerNumber: String? = null,
    val callerName: String? = null,
    val calleeNumber: String? = null,
    val ringing: List<LiveParty> = emptyList(),
    val answeredBy: LiveParty? = null,
    @Serializable(with = InstantSerializer::class) val startedAt: Instant,
    @Serializable(with = LenientInstantSerializer::class) val answeredAt: Instant? = null,
    val parked: LiveParked? = null,
    /** The restrictive choice when the server leaves a flag out. */
    val canPickup: Boolean = false,
    val canTransfer: Boolean = false,
    /** The call rings at, or runs on, the own extension. */
    val mine: Boolean = false,
) {
    /** Stable between polls (the `ref` is not): rows and open sheets stay put. */
    val id: String
        get() = "${startedAt.toEpochMilli()}|${callerNumber ?: "-"}|${calleeNumber ?: "-"}"

    val isRinging: Boolean get() = state == LiveState.RINGING
    val isParked: Boolean get() = state == LiveState.PARKED
    val isRunning: Boolean get() = state == LiveState.ACTIVE || state == LiveState.HELD

    /** Where the elapsed time counts from: answered, else started. */
    val clockStart: Instant get() = if (isRunning) (answeredAt ?: startedAt) else startedAt

    // Numbers and names of callers do not belong in a log.
    override fun toString(): String = "LiveCall(state=$state)"
}

object LiveCallListSerializer : LenientListSerializer<LiveCall>(LiveCall.serializer())

/** `GET /pbx/live`. */
@Serializable
data class LiveCallsPage(
    /** `false` = live calls are off for this PBX: hide the screen. */
    val enabled: Boolean = false,
    val fresh: Boolean = false,
    @Serializable(with = LiveCallListSerializer::class) val calls: List<LiveCall> = emptyList(),
)

@Serializable
enum class LiveTargetType(val wire: String) {
    @SerialName("extension") EXTENSION("extension"),
    @SerialName("ring_group") RING_GROUP("ring_group"),
    @SerialName("queue") QUEUE("queue"),
    @SerialName("ivr") IVR("ivr"),
    @SerialName("business_hours") BUSINESS_HOURS("business_hours"),
    @SerialName("voicemail") VOICEMAIL("voicemail"),
    @SerialName("unknown") UNKNOWN("unknown"),
}

@Serializable
data class LiveTarget(
    val type: LiveTargetType = LiveTargetType.UNKNOWN,
    val id: String,
    val name: String = "",
    val extension: String = "",
)

object LiveTargetListSerializer : LenientListSerializer<LiveTarget>(LiveTarget.serializer())

/** `GET /pbx/live/targets`. */
@Serializable
data class LiveTargetsPage(
    val readOnly: Boolean = false,
    @Serializable(with = LiveTargetListSerializer::class) val targets: List<LiveTarget> = emptyList(),
) {
    /** An entry of a type this version does not know cannot be chosen: it is left out. */
    val choosable: List<LiveTarget> get() = targets.filter { it.type != LiveTargetType.UNKNOWN }
}

/** `POST /pbx/live/pickup`. The extension is never sent: it comes from the pairing. */
data class LivePickupRequest(val ref: String) {
    fun toJson(): JsonObject = buildJsonObject { put("ref", JsonPrimitive(ref)) }

    override fun toString(): String = "LivePickupRequest(ref=•••)"
}

@Serializable
data class LivePickupResponse(val pickedBy: LiveParty? = null)

/** `POST /pbx/live/transfer`: blind, to an entry of `GET /pbx/live/targets`. */
data class LiveTransferRequest(val ref: String, val targetType: LiveTargetType, val targetId: String) {
    fun toJson(): JsonObject = buildJsonObject {
        put("ref", JsonPrimitive(ref))
        put(
            "target",
            buildJsonObject {
                put("type", JsonPrimitive(targetType.wire))
                put("id", JsonPrimitive(targetId))
            },
        )
    }

    override fun toString(): String = "LiveTransferRequest(ref=•••)"
}

@Serializable
data class LiveTransferResponse(
    /** `internal` or `voicemail`. */
    val destinationType: String? = null,
)
