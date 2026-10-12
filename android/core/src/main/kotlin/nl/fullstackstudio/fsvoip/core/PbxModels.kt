// SPDX-License-Identifier: AGPL-3.0-or-later
//
// Kotlin mirror of the `/pbx/*` routes of `shared/openapi.yaml` that an admin pairing may use: extensions ("Toestellen") and ring
// groups ("Belgroepen"). Responses are decoded leniently; requests are built by hand so an absent key stays absent.

package nl.fullstackstudio.fsvoip.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** The price of call recording per number per month: euro x 10 000, and whether the VAT is in it. */
@Serializable
data class RecordingCost(val priceE4: Int, val vatIncluded: Boolean = false)

/** Where something is still used (`409 in_use`): the kind and the name the customer chose. */
@Serializable
data class UsePlace(val kind: String = "", val name: String = "")

@Serializable
enum class RingStrategy(val wire: String) {
    @SerialName("all") ALL("all"),
    @SerialName("sequence") SEQUENCE("sequence"),
    @SerialName("round") ROUND("round"),
}

// MARK: Extensions

@Serializable
data class PbxRegistration(
    val connected: Boolean = false,
    val count: Int = 0,
    val agent: String? = null,
)

@Serializable
data class FollowMeStep(
    val target: PbxTarget = PbxTarget(),
    val delaySeconds: Int = 0,
    val timeoutSeconds: Int = 25,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("target", target.toJson())
        put("delaySeconds", JsonPrimitive(delaySeconds))
        put("timeoutSeconds", JsonPrimitive(timeoutSeconds))
    }
}

@Serializable
data class PbxDevice(
    val id: String,
    val name: String,
    val version: Int,
    @SerialName("extension") val extensionNumber: String? = null,
    val email: String? = null,
    val voicemailEnabled: Boolean = false,
    val voicemailToEmail: Boolean = false,
    val voicemailAttachFile: Boolean = false,
    val outboundNumberId: String? = null,
    val dnd: Boolean = false,
    val suspended: Boolean = false,
    val recordCalls: Boolean = false,
    val noAnswerSeconds: Int = 25,
    val noAnswerTarget: PbxTarget? = null,
    val busyTarget: PbxTarget? = null,
    val notRegisteredTarget: PbxTarget? = null,
    val forwardAlways: PbxTarget? = null,
    val followMe: List<FollowMeStep> = emptyList(),
    val sync: SyncState = SyncState.UNKNOWN,
    /** `null` = unknown (the PBX did not answer). */
    val registration: PbxRegistration? = null,
)

@Serializable
data class NumberRef(val id: String, val number: String)

@Serializable
data class PbxDevices(
    val devices: List<PbxDevice> = emptyList(),
    val targets: List<PbxTargetOption> = emptyList(),
    val numbers: List<NumberRef> = emptyList(),
)

/** `PATCH /pbx/devices/{id}`: only what changed, plus the `version` the screen showed. */
data class PbxDevicePatch(
    val version: Int,
    val voicemailEnabled: Boolean? = null,
    val voicemailToEmail: Boolean? = null,
    val voicemailAttachFile: Boolean? = null,
    /** [Change.Clear] = the first number of the PBX. */
    val outboundNumberId: Change<String> = Change.Keep,
    val dnd: Boolean? = null,
    val noAnswerSeconds: Int? = null,
    val noAnswerTarget: Change<PbxTarget> = Change.Keep,
    val busyTarget: Change<PbxTarget> = Change.Keep,
    val notRegisteredTarget: Change<PbxTarget> = Change.Keep,
    val forwardAlways: Change<PbxTarget> = Change.Keep,
    val followMe: List<FollowMeStep>? = null,
) {
    /** Nothing but the version: the server answers `400` to that, so the screen does not send it. */
    val isEmpty: Boolean
        get() = toJson().size <= 1

    fun toJson(): JsonObject = buildJsonObject {
        put("version", JsonPrimitive(version))
        voicemailEnabled?.let { put("voicemailEnabled", JsonPrimitive(it)) }
        voicemailToEmail?.let { put("voicemailToEmail", JsonPrimitive(it)) }
        voicemailAttachFile?.let { put("voicemailAttachFile", JsonPrimitive(it)) }
        putText("outboundNumberId", outboundNumberId)
        dnd?.let { put("dnd", JsonPrimitive(it)) }
        noAnswerSeconds?.let { put("noAnswerSeconds", JsonPrimitive(it)) }
        putTarget("noAnswerTarget", noAnswerTarget)
        putTarget("busyTarget", busyTarget)
        putTarget("notRegisteredTarget", notRegisteredTarget)
        putTarget("forwardAlways", forwardAlways)
        followMe?.let { steps -> put("followMe", JsonArray(steps.map { it.toJson() })) }
    }
}

// MARK: Ring groups

@Serializable
data class RingGroupMember(
    val extensionId: String,
    val delaySeconds: Int = 0,
    val timeoutSeconds: Int = 25,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("extensionId", JsonPrimitive(extensionId))
        put("delaySeconds", JsonPrimitive(delaySeconds))
        put("timeoutSeconds", JsonPrimitive(timeoutSeconds))
    }
}

@Serializable
data class PbxRingGroup(
    val id: String,
    val name: String,
    val version: Int,
    @SerialName("extension") val extensionNumber: String? = null,
    val strategy: RingStrategy = RingStrategy.ALL,
    val members: List<RingGroupMember> = emptyList(),
    val timeoutTarget: PbxTarget? = null,
    val sync: SyncState = SyncState.UNKNOWN,
)

@Serializable
data class PbxDeviceRef(
    val id: String,
    val name: String,
    @SerialName("extension") val extensionNumber: String? = null,
)

@Serializable
data class PbxRingGroups(
    val ringGroups: List<PbxRingGroup> = emptyList(),
    val devices: List<PbxDeviceRef> = emptyList(),
    val targets: List<PbxTargetOption> = emptyList(),
)

@Serializable
data class PbxRingGroupCreated(val ringGroupId: String)

/** `POST /pbx/ring-groups`. */
data class PbxRingGroupCreate(
    val name: String,
    val strategy: RingStrategy = RingStrategy.ALL,
    val members: List<RingGroupMember> = emptyList(),
    val timeoutTarget: Change<PbxTarget> = Change.Keep,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("name", JsonPrimitive(name))
        put("strategy", JsonPrimitive(strategy.wire))
        put("members", JsonArray(members.map { it.toJson() }))
        putTarget("timeoutTarget", timeoutTarget)
    }
}

/** `PATCH /pbx/ring-groups/{id}`. */
data class PbxRingGroupPatch(
    val version: Int,
    val name: String? = null,
    val strategy: RingStrategy? = null,
    val members: List<RingGroupMember>? = null,
    val timeoutTarget: Change<PbxTarget> = Change.Keep,
) {
    val isEmpty: Boolean
        get() = toJson().size <= 1

    fun toJson(): JsonObject = buildJsonObject {
        put("version", JsonPrimitive(version))
        name?.let { put("name", JsonPrimitive(it)) }
        strategy?.let { put("strategy", JsonPrimitive(it.wire)) }
        members?.let { list -> put("members", JsonArray(list.map { it.toJson() })) }
        putTarget("timeoutTarget", timeoutTarget)
    }
}

/** Raw JSON value helper for the places and the blocked numbers of an error body. */
internal fun JsonElement?.asPlaces(): List<UsePlace> = (this as? JsonArray)?.mapNotNull {
    runCatching { FsJson.default.decodeFromJsonElement(UsePlace.serializer(), it) }.getOrNull()
}.orEmpty()
