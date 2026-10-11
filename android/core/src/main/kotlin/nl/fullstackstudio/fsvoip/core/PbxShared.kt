// SPDX-License-Identifier: AGPL-3.0-or-later
//
// The building blocks the `/pbx/*` models share with other parts of the API (the own extension, sounds, ...): the sync state of
// an object, the destination of a call, and `Change` (keep / clear / set). They are mirrors of the Swift `SyncState`,
// `TargetType`, `PbxTarget`, `PbxTargetOption` and `Change`. Responses decode leniently (an unknown enum value becomes
// `UNKNOWN`); requests are strict (`UNKNOWN` can never be sent).

package nl.fullstackstudio.fsvoip.core

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObjectBuilder

/** "Wordt bijgewerkt" / "Niet gelukt": whether the PBX already runs this object's latest settings. */
@Serializable(with = SyncState.Serializer::class)
enum class SyncState(override val wire: String) : WireEnum {
    OK("ok"),
    PENDING("pending"),
    ERROR("error"),
    UNKNOWN("unknown"),
    ;

    object Serializer : TolerantEnumSerializer<SyncState>("SyncState", entries, UNKNOWN)
}

/** What a call can be sent to. */
@Serializable(with = TargetType.Serializer::class)
enum class TargetType(override val wire: String) : WireEnum {
    DEVICE("extension"),
    RING_GROUP("ring_group"),
    QUEUE("queue"),
    IVR("ivr"),
    BUSINESS_HOURS("business_hours"),
    VOICEMAIL("voicemail"),
    RECORDING("recording"),
    HANGUP("hangup"),
    EXTERNAL("external"),
    UNKNOWN("unknown"),
    ;

    object Serializer : TolerantEnumSerializer<TargetType>("TargetType", entries, UNKNOWN)
}

/** A destination. `id` = an object of this PBX (never a PBX uuid), `number` = an external number (`EXTERNAL` only). */
@Serializable
data class PbxTarget(
    val type: TargetType,
    val id: String? = null,
    val number: String? = null,
) {
    companion object {
        val HANGUP = PbxTarget(TargetType.HANGUP)

        fun external(number: String) = PbxTarget(TargetType.EXTERNAL, number = number)

        fun obj(type: TargetType, id: String) = PbxTarget(type, id = id)
    }
}

/** A choice for "where does the call go" (`value` is `type:id`, `external` or `none`). */
@Serializable
data class PbxTargetOption(
    val value: String,
    val type: TargetType,
    val id: String? = null,
    val name: String,
    @SerialName("extension") val extensionNumber: String? = null,
    /** The voicemail of an extension (as opposed to a shared mailbox). */
    val ofDevice: Boolean = false,
)

/** What an explicit "keep / clear / set" of one field of a PATCH body means: left out, an explicit `null`, or a value. */
sealed class Change<out T> {
    /** Left out of the body: unchanged. */
    data object Keep : Change<Nothing>()

    /** An explicit `null`. */
    data object Clear : Change<Nothing>()

    data class Set<T>(val value: T) : Change<T>()

    val isKept: Boolean get() = this === Keep

    companion object {
        /** `null` clears, a value sets. */
        fun <T> of(value: T?): Change<T> = if (value == null) Clear else Set(value)
    }
}

/** Writes a [Change] into a body: nothing for [Change.Keep], `null` for [Change.Clear], the encoded value for [Change.Set]. */
fun <T> JsonObjectBuilder.putChange(key: String, change: Change<T>, encode: (T) -> JsonElement) {
    when (change) {
        Change.Keep -> Unit
        Change.Clear -> put(key, JsonNull)
        is Change.Set -> put(key, encode(change.value))
    }
}

/** The JSON of a serializable value (strict: an `UNKNOWN` enum value throws). */
internal fun <T> KSerializer<T>.toJson(value: T): JsonElement = FsJson.default.encodeToJsonElement(this, value)
