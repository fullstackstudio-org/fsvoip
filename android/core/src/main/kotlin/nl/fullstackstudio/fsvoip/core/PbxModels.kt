// SPDX-License-Identifier: AGPL-3.0-or-later
//
// Kotlin mirror of the `/pbx/*` part of `shared/openapi.yaml` (the "Centrale" section for admin pairings): overview and call
// flow, devices, ring groups, opening hours and the routing of a number. The numbers as a chain are in PbxChain.kt.
// Responses decode leniently (unknown fields ignored, unknown enum values -> UNKNOWN); requests are strict (only the keys the
// server knows, `version` always present, UNKNOWN can never be sent). The words are the portal's: a customer sees "toestel",
// "belgroep", "openingstijden", never PBX terms.

package nl.fullstackstudio.fsvoip.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

// MARK: - Overview and flow

@Serializable(with = FlowKind.Serializer::class)
enum class FlowKind(override val wire: String) : WireEnum {
    DEVICE("extension"),
    RING_GROUP("ring_group"),
    QUEUE("queue"),
    IVR("ivr"),
    BUSINESS_HOURS("business_hours"),
    VOICEMAIL("voicemail"),
    RECORDING("recording"),
    HANGUP("hangup"),
    EXTERNAL("external"),

    /** The object this step pointed at no longer exists. */
    MISSING("missing"),

    /** The flow comes back to a step it already visited. */
    LOOP("loop"),

    /** A number without a destination (wire value `none`). */
    NO_DESTINATION("none"),

    /** The preview was cut off here (too large to draw). */
    MORE("more"),
    UNKNOWN("unknown"),
    ;

    object Serializer : TolerantEnumSerializer<FlowKind>("FlowKind", entries, UNKNOWN)
}

@Serializable(with = FlowWhenKind.Serializer::class)
enum class FlowWhenKind(override val wire: String) : WireEnum {
    OPEN("open"),
    CLOSED("closed"),
    NO_ANSWER("no_answer"),
    BUSY("busy"),
    TIMEOUT("timeout"),
    KEY("key"),
    NO_CHOICE("no_choice"),
    FORWARD_ALWAYS("forward_always"),
    UNKNOWN("unknown"),
    ;

    object Serializer : TolerantEnumSerializer<FlowWhenKind>("FlowWhenKind", entries, UNKNOWN)
}

@Serializable
data class FlowWhen(
    val kind: FlowWhenKind,
    /** `no_answer`: after how many seconds. */
    val seconds: Int? = null,
    /** `key`: the pressed key. */
    val digit: String? = null,
)

@Serializable
data class FlowBranch(val `when`: FlowWhen, val node: FlowNode)

/** One step of the call flow of a number ("openingstijden -> belgroep -> voicemail"), as a tree. */
@Serializable
data class FlowNode(
    val kind: FlowKind,
    val id: String? = null,
    val name: String? = null,
    @SerialName("extension") val extensionNumber: String? = null,
    val ofDevice: Boolean = false,
    val number: String? = null,
    /** An extension with do-not-disturb on (and no "always forward"): it will not ring. */
    val dnd: Boolean = false,
    val branches: List<FlowBranch> = emptyList(),
)

/** A phone number of the PBX with where its callers go. `version` goes back with a routing change. */
@Serializable
data class PbxNumber(
    val id: String,
    /** National, without country code (`0850607848`). */
    val number: String,
    /** `null` = the start of the flow of the PBX (the entry). */
    val routing: PbxTarget? = null,
    val blockListId: String? = null,
    val isDefault: Boolean = false,
    val version: Int,
    val sync: SyncState = SyncState.UNKNOWN,
    val recordCalls: Boolean? = null,
    val recordAnnouncementSoundId: String? = null,
    val recordBillingActive: Boolean? = null,
    /** The call flow behind this number. */
    val flow: FlowNode? = null,
)

/** `GET /pbx/overview`. */
@Serializable
data class PbxOverview(
    val pbx: PbxStatus,
    val numbers: List<PbxNumber> = emptyList(),
    /** The flow without a number (the entry of the PBX), for a PBX that has no number yet. */
    val entryFlow: FlowNode? = null,
    val deviceCount: Int = 0,
    /** Registered extensions; `null` = the PBX did not answer. */
    val connectedCount: Int? = null,
    val ringGroupCount: Int = 0,
    /** Calling out is temporarily off (fraud guard or an administrative hold). */
    val outboundBlocked: Boolean = false,
)

// MARK: - Devices

@Serializable
data class DeviceRegistration(
    val connected: Boolean,
    val count: Int,
    val agent: String? = null,
)

@Serializable
data class FollowMeStep(
    val target: PbxTarget,
    val delaySeconds: Int = 0,
    val timeoutSeconds: Int = 25,
)

/** An extension ("toestel") with the settings an admin app may change. */
@Serializable
data class PbxDevice(
    val id: String,
    val name: String,
    /** Goes back with every change (`stale` = changed in the meantime). */
    val version: Int,
    /** Internal number (101, 102, ...). */
    @SerialName("extension") val extensionNumber: String? = null,
    val email: String? = null,
    val voicemailEnabled: Boolean = false,
    val voicemailToEmail: Boolean = false,
    val voicemailAttachFile: Boolean = false,
    /** The own phone number used for calling out; `null` = the first number of the PBX. */
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
    val registration: DeviceRegistration? = null,
)

@Serializable
data class PbxNumberRef(val id: String, val number: String)

/** `GET /pbx/devices`. */
@Serializable
data class PbxDevicesResponse(
    val devices: List<PbxDevice> = emptyList(),
    /** Everything a call can be forwarded to. */
    val targets: List<PbxTargetOption> = emptyList(),
    val numbers: List<PbxNumberRef> = emptyList(),
)

/**
 * `PATCH /pbx/devices/{id}`. Only these keys exist: name, e-mail, recording, suspending and sounds stay in the portal.
 * `version` is required; a [Change.Clear] sends an explicit `null` ("no target" / "first number").
 */
data class PbxDevicePatch(
    val version: Int,
    val voicemailEnabled: Boolean? = null,
    val voicemailToEmail: Boolean? = null,
    val voicemailAttachFile: Boolean? = null,
    val outboundNumberId: Change<String> = Change.Keep,
    val dnd: Boolean? = null,
    val noAnswerSeconds: Int? = null,
    val noAnswerTarget: Change<PbxTarget> = Change.Keep,
    val busyTarget: Change<PbxTarget> = Change.Keep,
    val notRegisteredTarget: Change<PbxTarget> = Change.Keep,
    val forwardAlways: Change<PbxTarget> = Change.Keep,
    val followMe: List<FollowMeStep>? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("version", JsonPrimitive(version))
        voicemailEnabled?.let { put("voicemailEnabled", JsonPrimitive(it)) }
        voicemailToEmail?.let { put("voicemailToEmail", JsonPrimitive(it)) }
        voicemailAttachFile?.let { put("voicemailAttachFile", JsonPrimitive(it)) }
        putChange("outboundNumberId", outboundNumberId) { JsonPrimitive(it) }
        dnd?.let { put("dnd", JsonPrimitive(it)) }
        noAnswerSeconds?.let { put("noAnswerSeconds", JsonPrimitive(it)) }
        putChange("noAnswerTarget", noAnswerTarget, PbxTarget.serializer()::toJson)
        putChange("busyTarget", busyTarget, PbxTarget.serializer()::toJson)
        putChange("notRegisteredTarget", notRegisteredTarget, PbxTarget.serializer()::toJson)
        putChange("forwardAlways", forwardAlways, PbxTarget.serializer()::toJson)
        followMe?.let { steps -> put("followMe", JsonArray(steps.map { FollowMeStep.serializer().toJson(it) })) }
    }
}

// MARK: - Ring groups

@Serializable(with = RingStrategy.Serializer::class)
enum class RingStrategy(override val wire: String) : WireEnum {
    /** Everybody at once. */
    ALL("all"),

    /** One after the other. */
    SEQUENCE("sequence"),

    /** Take turns. */
    ROUND("round"),
    UNKNOWN("unknown"),
    ;

    object Serializer : TolerantEnumSerializer<RingStrategy>("RingStrategy", entries, UNKNOWN)
}

@Serializable
data class RingGroupMember(
    val extensionId: String,
    val delaySeconds: Int = 0,
    val timeoutSeconds: Int = 25,
)

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

/** `GET /pbx/ring-groups`. */
@Serializable
data class PbxRingGroupsResponse(
    val ringGroups: List<PbxRingGroup> = emptyList(),
    /** The extensions that can become a member. */
    val devices: List<PbxDeviceRef> = emptyList(),
    val targets: List<PbxTargetOption> = emptyList(),
)

/** `POST /pbx/ring-groups`. No `version` (it is a new object); `name` is required. */
data class PbxRingGroupCreate(
    val name: String,
    val strategy: RingStrategy? = null,
    val members: List<RingGroupMember>? = null,
    val timeoutTarget: Change<PbxTarget> = Change.Keep,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("name", JsonPrimitive(name))
        strategy?.let { put("strategy", RingStrategy.serializer().toJson(it)) }
        members?.let { list -> put("members", JsonArray(list.map { RingGroupMember.serializer().toJson(it) })) }
        putChange("timeoutTarget", timeoutTarget, PbxTarget.serializer()::toJson)
    }
}

/** `PATCH /pbx/ring-groups/{id}`: `version` required, at least one other key. */
data class PbxRingGroupPatch(
    val version: Int,
    val name: String? = null,
    val strategy: RingStrategy? = null,
    val members: List<RingGroupMember>? = null,
    val timeoutTarget: Change<PbxTarget> = Change.Keep,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("version", JsonPrimitive(version))
        name?.let { put("name", JsonPrimitive(it)) }
        strategy?.let { put("strategy", RingStrategy.serializer().toJson(it)) }
        members?.let { list -> put("members", JsonArray(list.map { RingGroupMember.serializer().toJson(it) })) }
        putChange("timeoutTarget", timeoutTarget, PbxTarget.serializer()::toJson)
    }
}

/** `201` of `POST /pbx/ring-groups`. */
@Serializable
data class PbxRingGroupCreated(val ringGroupId: String)

// MARK: - Opening hours

@Serializable
data class HoursWeekEntry(
    /** 1 = Monday ... 7 = Sunday. */
    val day: Int,
    /** `HH:MM`. */
    val from: String,
    /** `HH:MM`; may be `24:00` (until midnight). */
    val to: String,
)

/** A holiday in the opening hours: a fixed rule (Easter, King's Day, ...) or an own date. */
@Serializable
data class PbxHoliday(
    val name: String,
    /** `YYYY-MM-DD` for an own date. */
    val date: String? = null,
    /** Fixed rule key (see [PbxHoursResponse.holidayRules]). */
    val rule: String? = null,
    /** `null` = the same as outside opening hours. */
    val target: PbxTarget? = null,
)

@Serializable
data class PbxHours(
    val id: String,
    val name: String,
    @SerialName("extension") val extensionNumber: String? = null,
    val timezone: String = "Europe/Amsterdam",
    val week: List<HoursWeekEntry> = emptyList(),
    val openTarget: PbxTarget? = null,
    val closedTarget: PbxTarget? = null,
    val holidays: List<PbxHoliday> = emptyList(),
    val version: Int,
    val sync: SyncState = SyncState.UNKNOWN,
)

@Serializable
data class HolidayRuleLabel(val rule: String, val label: String)

/** `GET /pbx/hours`. */
@Serializable
data class PbxHoursResponse(
    val hours: List<PbxHours> = emptyList(),
    val targets: List<PbxTargetOption> = emptyList(),
    /** The fixed holidays the PBX knows, with a label for the form. */
    val holidayRules: List<HolidayRuleLabel> = emptyList(),
)

/** A holiday in a PATCH: `{ "rule": ... }` or `{ "name": ..., "date": ... }`. */
sealed class PbxHolidayInput {
    data class Rule(val rule: String) : PbxHolidayInput()

    data class Custom(val name: String, val date: String) : PbxHolidayInput()

    fun toJson(): JsonObject = when (this) {
        is Rule -> buildJsonObject { put("rule", JsonPrimitive(rule)) }
        is Custom -> buildJsonObject {
            put("name", JsonPrimitive(name))
            put("date", JsonPrimitive(date))
        }
    }
}

/**
 * `PATCH /pbx/hours/{id}`: week, targets and holidays (the name stays in the portal). `version` required. `holidays` REPLACES
 * the list: send the existing ones along (as [PbxHolidayInput.Rule] / [PbxHolidayInput.Custom]) to keep them.
 */
data class PbxHoursPatch(
    val version: Int,
    val week: List<HoursWeekEntry>? = null,
    val openTarget: Change<PbxTarget> = Change.Keep,
    val closedTarget: Change<PbxTarget> = Change.Keep,
    val holidays: List<PbxHolidayInput>? = null,
    /** What happens on a holiday; clear = the same as outside opening hours. */
    val holidayTarget: Change<PbxTarget> = Change.Keep,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("version", JsonPrimitive(version))
        week?.let { list -> put("week", JsonArray(list.map { HoursWeekEntry.serializer().toJson(it) })) }
        putChange("openTarget", openTarget, PbxTarget.serializer()::toJson)
        putChange("closedTarget", closedTarget, PbxTarget.serializer()::toJson)
        holidays?.let { list -> put("holidays", JsonArray(list.map { it.toJson() })) }
        putChange("holidayTarget", holidayTarget, PbxTarget.serializer()::toJson)
    }
}

// MARK: - Number routing

/** `PATCH /pbx/numbers/{id}/routing`. `target = null` is sent as an explicit `null` = "the start of the flow". */
data class PbxRoutingPatch(val target: PbxTarget?, val version: Int) {
    fun toJson(): JsonObject = buildJsonObject {
        put("target", target?.let { PbxTarget.serializer().toJson(it) } ?: JsonNull)
        put("version", JsonPrimitive(version))
    }
}
