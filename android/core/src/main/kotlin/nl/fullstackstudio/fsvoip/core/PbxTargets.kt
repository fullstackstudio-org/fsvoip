// SPDX-License-Identifier: AGPL-3.0-or-later
//
// The vocabulary the own extension (`/me/extension`) shares with the PBX section: where a call can be sent to, and whether the PBX
// already runs the latest settings of an object. Kept in this file on purpose: when the pbx slice brings its own copy of these four
// types, delete this file (the shape is the same as the iOS `PbxModels.swift`).

package nl.fullstackstudio.fsvoip.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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

/** A destination. `id` = an object of this PBX (never a PBX uuid), `number` = an external number (`external` only). */
@Serializable
data class PbxTarget(
    val type: TargetType,
    val id: String? = null,
    val number: String? = null,
) {
    companion object {
        val hangup = PbxTarget(TargetType.HANGUP)

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
