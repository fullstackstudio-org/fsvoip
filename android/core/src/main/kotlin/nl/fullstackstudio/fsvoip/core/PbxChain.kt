// SPDX-License-Identifier: AGPL-3.0-or-later
//
// Numbers as a chain (`/pbx/numbers`, API v2): opening hours, welcome message, forwarding, recording. A number is shown as a
// chain; `advanced` means the flow behind the number is more than the chain can show, so it is read-only there (only the name
// and the recording can still be changed). Responses are lenient (an unknown `kind` or `mode` makes the chain read-only, a
// broken `options` becomes empty); requests are strict.

package nl.fullstackstudio.fsvoip.core

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A number is shown as a chain. `ADVANCED` = more than the chain can show: read-only. */
@Serializable(with = ChainMode.Serializer::class)
enum class ChainMode(override val wire: String) : WireEnum {
    SIMPLE("simple"),
    ADVANCED("advanced"),
    UNKNOWN("unknown"),
    ;

    object Serializer : TolerantEnumSerializer<ChainMode>("ChainMode", entries, UNKNOWN)
}

@Serializable(with = ForwardingKind.Serializer::class)
enum class ForwardingKind(override val wire: String) : WireEnum {
    STANDARD("standard"),
    MENU("menu"),
    ADVANCED("advanced"),
    UNKNOWN("unknown"),
    ;

    object Serializer : TolerantEnumSerializer<ForwardingKind>("ForwardingKind", entries, UNKNOWN)
}

/** One line of `GET /pbx/numbers`: the number with a one-line summary of its chain. */
@Serializable
data class PbxNumberEntry(
    val id: String,
    /** National, without country code. */
    val number: String,
    val name: String? = null,
    val mode: ChainMode = ChainMode.UNKNOWN,
    val summary: Summary,
    val sync: SyncState = SyncState.UNKNOWN,
    val version: Int,
) {
    @Serializable
    data class Summary(
        /** A label in the language of the server ("ma-vr 9:00-17:00", "Uit"). */
        val hours: String = "",
        val welcome: Boolean = false,
        val forwarding: ForwardingKind = ForwardingKind.UNKNOWN,
        val recording: Boolean = false,
    )
}

/** `GET /pbx/numbers`. */
@Serializable
data class PbxNumbersPage(val numbers: List<PbxNumberEntry> = emptyList())

// MARK: - Fallback

/**
 * What happens when it is closed or nobody answers. Decoded leniently; encoded strictly: [Other] and [Unknown] are things the
 * chain has no choice for, they are shown read-only and can never be sent back ([isSelectable] is false).
 */
@Serializable(with = FallbackSerializer::class)
sealed class Fallback {
    /** A voicemail box. `boxId == null` on the way out = the shared box of the PBX. */
    data class Voicemail(val boxId: String?, val ofDevice: Boolean) : Fallback()

    data class Message(val soundId: String) : Fallback()

    data class Forward(val number: String) : Fallback()

    data object Hangup : Fallback()

    data class Device(val deviceId: String) : Fallback()

    /** Something else (a queue, a menu, ...): read-only. `target` is what it points at, when the server tells. */
    data class Other(val target: PbxTarget?) : Fallback()

    /** A `mode` this app version does not know. */
    data class Unknown(val mode: String) : Fallback()

    /** Can the user choose this one (and does it survive being sent back)? */
    val isSelectable: Boolean get() = this !is Other && this !is Unknown

    /** The body of a step. Throws for a read-only fallback: it can never leave the app. */
    fun toJson(): JsonObject = when (this) {
        is Voicemail -> buildJsonObject {
            put("mode", JsonPrimitive("voicemail"))
            boxId?.let { put("boxId", JsonPrimitive(it)) }
        }
        is Message -> buildJsonObject {
            put("mode", JsonPrimitive("message"))
            put("soundId", JsonPrimitive(soundId))
        }
        is Forward -> buildJsonObject {
            put("mode", JsonPrimitive("forward"))
            put("number", JsonPrimitive(number))
        }
        Hangup -> buildJsonObject { put("mode", JsonPrimitive("hangup")) }
        is Device -> buildJsonObject {
            put("mode", JsonPrimitive("device"))
            put("deviceId", JsonPrimitive(deviceId))
        }
        is Other, is Unknown -> throw IllegalArgumentException("A read-only fallback cannot be sent")
    }

    companion object {
        /** What a step does when nothing is chosen yet: the shared voicemail box (the server knows which one). */
        val DEFAULT = Voicemail(null, false)

        fun fromJson(element: JsonElement): Fallback {
            val obj = element as? JsonObject ?: return Unknown("")
            val mode = obj["mode"]?.stringOrNull() ?: return Unknown("")

            return when (mode) {
                "voicemail" -> Voicemail(obj["boxId"]?.stringOrNull(), obj["ofDevice"]?.boolOrNull() ?: false)
                "message" -> obj["soundId"]?.stringOrNull()?.let(::Message) ?: Unknown(mode)
                "forward" -> obj["number"]?.stringOrNull()?.let(::Forward) ?: Unknown(mode)
                "hangup" -> Hangup
                "device" -> obj["deviceId"]?.stringOrNull()?.let(::Device) ?: Unknown(mode)
                "other" -> Other(obj["target"]?.let { runCatching { FsJson.default.decodeFromJsonElement(PbxTarget.serializer(), it) }.getOrNull() })
                else -> Unknown(mode)
            }
        }
    }
}

private fun JsonElement.stringOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

private fun JsonElement.boolOrNull(): Boolean? = (this as? JsonPrimitive)?.takeIf { !it.isString }?.contentOrNull?.toBooleanStrictOrNull()

object FallbackSerializer : KSerializer<Fallback> {
    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor

    override fun deserialize(decoder: Decoder): Fallback = Fallback.fromJson((decoder as JsonDecoder).decodeJsonElement())

    override fun serialize(encoder: Encoder, value: Fallback) = (encoder as JsonEncoder).encodeJsonElement(value.toJson())
}

// MARK: - The parts of a chain

@Serializable
data class ChainHolidayDate(
    val name: String,
    /** `YYYY-MM-DD`. */
    val date: String,
)

@Serializable
data class ChainHolidays(
    /** All Dutch national holidays use the "closed" behaviour. */
    val national: Boolean = false,
    /** The fixed holidays that are on (rule names as in [PbxHoursResponse.holidayRules]). */
    val rules: List<String> = emptyList(),
    val dates: List<ChainHolidayDate> = emptyList(),
)

@Serializable
data class ChainHours(
    val id: String,
    val version: Int,
    val week: List<HoursWeekEntry> = emptyList(),
    val holidays: ChainHolidays,
    val closed: Fallback,
    /** `null` = on a holiday the same as outside opening hours. */
    val holiday: Fallback? = null,
    /** Labels of the other numbers that use the same opening hours. */
    val sharedWith: List<String> = emptyList(),
)

@Serializable
data class ChainWelcome(
    val menuId: String,
    val version: Int,
    val soundId: String? = null,
    val sharedWith: List<String> = emptyList(),
)

/** A member of the ring group behind a number. (Not [RingGroupMember]: that one names its extension `extensionId`.) */
@Serializable
data class ChainMember(
    val deviceId: String,
    val delaySeconds: Int = 0,
    val timeoutSeconds: Int = 25,
)

/**
 * A key of a menu. `editable == false`: the app shows it but cannot change it (it points at a queue, say); the server leaves it
 * as it is when the menu is saved. Only digit and target go to the server.
 */
@Serializable
data class ChainKey(
    /** `0`-`9`, `*` or `#`. */
    val digit: String,
    val target: PbxTarget,
    val editable: Boolean = true,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("digit", JsonPrimitive(digit))
        put("target", PbxTarget.serializer().toJson(target))
    }
}

@Serializable
data class ChainStandardForwarding(
    /** `null` = one single extension (no ring group yet); a change turns it into a ring group. */
    val groupId: String? = null,
    val version: Int,
    val strategy: RingStrategy = RingStrategy.ALL,
    val members: List<ChainMember> = emptyList(),
    val unanswered: Fallback,
    val sharedWith: List<String> = emptyList(),
)

@Serializable
data class ChainMenuForwarding(
    val menuId: String,
    val version: Int,
    val greetingSoundId: String? = null,
    /** 0-5. */
    val repeats: Int = 0,
    val timeoutSeconds: Int = 5,
    /** The key that counts when nothing is pressed; `null` = none (then [noChoice]). */
    val defaultKey: String? = null,
    val noChoice: Fallback,
    val keys: List<ChainKey> = emptyList(),
    val sharedWith: List<String> = emptyList(),
)

/**
 * Forwarding of a number: one extension or a ring group ([Standard]), or a menu with keys ([Menu]). [Unknown] = a `kind` this app
 * version does not know (treat like an advanced chain: read-only).
 */
sealed class ChainForwarding {
    data class Standard(val value: ChainStandardForwarding) : ChainForwarding()

    data class Menu(val value: ChainMenuForwarding) : ChainForwarding()

    data class Unknown(val kind: String) : ChainForwarding()

    companion object {
        fun fromJson(element: JsonElement): ChainForwarding? {
            if (element is JsonNull) return null
            val obj = element as? JsonObject ?: return Unknown("")
            val kind = obj["kind"]?.stringOrNull() ?: return Unknown("")

            return try {
                when (kind) {
                    "standard" -> Standard(FsJson.default.decodeFromJsonElement(ChainStandardForwarding.serializer(), obj))
                    "menu" -> Menu(FsJson.default.decodeFromJsonElement(ChainMenuForwarding.serializer(), obj))
                    else -> Unknown(kind)
                }
            } catch (_: kotlinx.serialization.SerializationException) {
                Unknown(kind)
            } catch (_: IllegalArgumentException) {
                Unknown(kind)
            }
        }
    }
}

/** Reads `forwarding`: `null`/absent = none, a `kind` it does not know (or cannot read) = [ChainForwarding.Unknown]. */
object ChainForwardingSerializer : KSerializer<ChainForwarding?> {
    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor

    override fun deserialize(decoder: Decoder): ChainForwarding? = ChainForwarding.fromJson((decoder as JsonDecoder).decodeJsonElement())

    override fun serialize(encoder: Encoder, value: ChainForwarding?) = (encoder as JsonEncoder).encodeJsonElement(JsonNull)
}

/** The price of call recording per number per month. */
@Serializable
data class RecordingCost(
    /** Euro x 10 000 (20000 = EUR 2.00). */
    val priceE4: Int,
    /** Whether `priceE4` includes VAT. */
    val vatIncluded: Boolean = false,
)

@Serializable
data class ChainRecording(
    val enabled: Boolean = false,
    val announcementSoundId: String? = null,
    /** Recording is already billed: changing the announcement needs no new consent. */
    val billingActive: Boolean = false,
    /** `false` = recording cannot be switched on on this PBX (there is no price). */
    val available: Boolean = false,
    /** `null` = free for this customer (no consent needed). */
    val cost: RecordingCost? = null,
)

/** The choices for the pickers of the chain. */
@Serializable
data class ChainOptions(
    val devices: List<Device> = emptyList(),
    val sounds: List<Named> = emptyList(),
    val groups: List<Named> = emptyList(),
) {
    @Serializable
    data class Device(
        val id: String,
        val name: String,
        @SerialName("extension") val extensionNumber: String? = null,
    )

    @Serializable
    data class Named(val id: String, val name: String)
}

@Serializable
data class ChainAdvanced(val summary: List<String> = emptyList())

/** A broken `options` (or `advanced`) must not hide the rest of the chain. */
object LenientChainOptionsSerializer : KSerializer<ChainOptions> {
    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor

    override fun deserialize(decoder: Decoder): ChainOptions {
        val element = (decoder as JsonDecoder).decodeJsonElement()

        return try {
            FsJson.default.decodeFromJsonElement(ChainOptions.serializer(), element)
        } catch (_: kotlinx.serialization.SerializationException) {
            ChainOptions()
        } catch (_: IllegalArgumentException) {
            ChainOptions()
        }
    }

    override fun serialize(encoder: Encoder, value: ChainOptions) =
        (encoder as JsonEncoder).encodeJsonElement(FsJson.default.encodeToJsonElement(ChainOptions.serializer(), value))
}

object LenientChainAdvancedSerializer : KSerializer<ChainAdvanced?> {
    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor

    override fun deserialize(decoder: Decoder): ChainAdvanced? {
        val element = (decoder as JsonDecoder).decodeJsonElement()

        if (element is JsonNull) return null

        return try {
            FsJson.default.decodeFromJsonElement(ChainAdvanced.serializer(), element)
        } catch (_: kotlinx.serialization.SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    override fun serialize(encoder: Encoder, value: ChainAdvanced?) = (encoder as JsonEncoder).encodeJsonElement(
        value?.let { FsJson.default.encodeToJsonElement(ChainAdvanced.serializer(), it) } ?: JsonNull,
    )
}

/** One number as a chain (`GET /pbx/numbers/{id}/chain`, and the answer of every step). */
@Serializable
data class NumberChain(
    val id: String,
    val number: String,
    val name: String? = null,
    val version: Int,
    val mode: ChainMode = ChainMode.UNKNOWN,
    val sync: SyncState = SyncState.UNKNOWN,
    /** `null` = no opening hours (always open). */
    val hours: ChainHours? = null,
    /** `null` = no welcome message. */
    val welcome: ChainWelcome? = null,
    /** `null` when `mode` is `ADVANCED`. */
    @Serializable(with = ChainForwardingSerializer::class) val forwarding: ChainForwarding? = null,
    @Serializable(with = LenientChainAdvancedSerializer::class) val advanced: ChainAdvanced? = null,
    val recording: ChainRecording,
    @Serializable(with = LenientChainOptionsSerializer::class) val options: ChainOptions = ChainOptions(),
) {
    /** Only when `mode` is `ADVANCED`: lines that describe the flow in words. */
    val advancedSummary: List<String>? get() = advanced?.summary

    /** Can the app change this chain step by step? (`ADVANCED` or an unknown kind of forwarding: read-only.) */
    val isEditable: Boolean get() = mode == ChainMode.SIMPLE && forwarding !is ChainForwarding.Unknown
}

// MARK: - Chain steps (requests; strict)

@Serializable
enum class ChainStep(val wire: String) {
    NAME("name"),
    HOURS("hours"),
    WELCOME("welcome"),
    FORWARDING("forwarding"),
    CLOSED("closed"),
}

/**
 * The versions the chain showed ([NumberChain] and its parts), sent back with a step so the server can see a lost update.
 * Left out = the server reads them itself.
 */
data class ChainVersions(
    /** `{ <objectId>: version }`: `ChainHours.id`, `ChainWelcome.menuId`, the group or menu id of the forwarding. */
    val objects: Map<String, Int>? = null,
    /** `NumberChain.version`. */
    val number: Int? = null,
) {
    internal fun into(builder: kotlinx.serialization.json.JsonObjectBuilder) {
        objects?.let { map -> builder.put("versions", JsonObject(map.mapValues { JsonPrimitive(it.value) })) }
        number?.let { builder.put("numberVersion", JsonPrimitive(it)) }
    }
}

/** A body of `PUT /pbx/numbers/{id}/chain/{step}`. */
sealed interface ChainStepRequest {
    val step: ChainStep

    /** Throws for a read-only fallback: such a step never leaves the app. */
    fun toJson(): JsonObject
}

/** Holidays as the app sends them: every part optional (left out = unchanged). */
data class ChainHolidaysInput(
    val national: Boolean? = null,
    val rules: List<String>? = null,
    val dates: List<ChainHolidayDate>? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        national?.let { put("national", JsonPrimitive(it)) }
        rules?.let { list -> put("rules", JsonArray(list.map(::JsonPrimitive))) }
        dates?.let { list -> put("dates", JsonArray(list.map { ChainHolidayDate.serializer().toJson(it) })) }
    }
}

/** `step: name`. A blank name is sent as `null` (the number has no name). */
data class ChainNameStep(val name: String?, val versions: ChainVersions = ChainVersions()) : ChainStepRequest {
    override val step get() = ChainStep.NAME

    override fun toJson(): JsonObject = buildJsonObject {
        val trimmed = name?.trim()
        put("name", if (trimmed.isNullOrEmpty()) JsonNull else JsonPrimitive(trimmed))
        versions.into(this)
    }
}

/** `step: hours`. `enabled == false` unlinks the opening hours (nothing is deleted). */
data class ChainHoursStep(
    val enabled: Boolean,
    val week: List<HoursWeekEntry>? = null,
    val holidays: ChainHolidaysInput? = null,
    /** Only a selectable fallback can be sent ([Fallback.isSelectable]); a read-only one throws on encoding. */
    val closed: Fallback? = null,
    /** [Change.Clear] = on a holiday the same as outside opening hours. */
    val holiday: Change<Fallback> = Change.Keep,
    val versions: ChainVersions = ChainVersions(),
) : ChainStepRequest {
    override val step get() = ChainStep.HOURS

    override fun toJson(): JsonObject = buildJsonObject {
        put("enabled", JsonPrimitive(enabled))
        week?.let { list -> put("week", JsonArray(list.map { HoursWeekEntry.serializer().toJson(it) })) }
        holidays?.let { put("holidays", it.toJson()) }
        closed?.let { put("closed", it.toJson()) }
        putChange("holiday", holiday) { it.toJson() }
        versions.into(this)
    }
}

/** `step: welcome`. `enabled == true` needs a sound (the one that is set, or `soundId`): otherwise `greeting_required`. */
data class ChainWelcomeStep(
    val enabled: Boolean,
    /** [Change.Keep] = unchanged, [Change.Clear] = no sound. */
    val soundId: Change<String> = Change.Keep,
    val versions: ChainVersions = ChainVersions(),
) : ChainStepRequest {
    override val step get() = ChainStep.WELCOME

    override fun toJson(): JsonObject = buildJsonObject {
        put("enabled", JsonPrimitive(enabled))
        putChange("soundId", soundId) { JsonPrimitive(it) }
        versions.into(this)
    }
}

/** `step: forwarding`, `kind: standard`. */
data class ChainStandardForwardingStep(
    val strategy: RingStrategy? = null,
    val members: List<ChainMember>? = null,
    val unanswered: Fallback? = null,
    val versions: ChainVersions = ChainVersions(),
) : ChainStepRequest {
    override val step get() = ChainStep.FORWARDING

    override fun toJson(): JsonObject = buildJsonObject {
        put("kind", JsonPrimitive("standard"))
        strategy?.let { put("strategy", RingStrategy.serializer().toJson(it)) }
        members?.let { list -> put("members", JsonArray(list.map { ChainMember.serializer().toJson(it) })) }
        unanswered?.let { put("unanswered", it.toJson()) }
        versions.into(this)
    }
}

/** `step: forwarding`, `kind: menu`. A menu has at least one key; keys that are not editable stay as they are on the server. */
data class ChainMenuForwardingStep(
    val greetingSoundId: Change<String> = Change.Keep,
    val repeats: Int? = null,
    val timeoutSeconds: Int? = null,
    val defaultKey: Change<String> = Change.Keep,
    val noChoice: Fallback? = null,
    val keys: List<ChainKey>? = null,
    val versions: ChainVersions = ChainVersions(),
) : ChainStepRequest {
    override val step get() = ChainStep.FORWARDING

    override fun toJson(): JsonObject = buildJsonObject {
        put("kind", JsonPrimitive("menu"))
        putChange("greetingSoundId", greetingSoundId) { JsonPrimitive(it) }
        repeats?.let { put("repeats", JsonPrimitive(it)) }
        timeoutSeconds?.let { put("timeoutSeconds", JsonPrimitive(it)) }
        putChange("defaultKey", defaultKey) { JsonPrimitive(it) }
        noChoice?.let { put("noChoice", it.toJson()) }
        keys?.let { list -> put("keys", JsonArray(list.map { it.toJson() })) }
        versions.into(this)
    }
}

/** `step: closed`: only what happens when it is closed / on a holiday. */
data class ChainClosedStep(
    val closed: Fallback? = null,
    /** [Change.Clear] = on a holiday the same as outside opening hours. */
    val holiday: Change<Fallback> = Change.Keep,
    val versions: ChainVersions = ChainVersions(),
) : ChainStepRequest {
    override val step get() = ChainStep.CLOSED

    override fun toJson(): JsonObject = buildJsonObject {
        closed?.let { put("closed", it.toJson()) }
        putChange("holiday", holiday) { it.toJson() }
        versions.into(this)
    }
}

/** `PATCH /pbx/numbers/{id}/recording`. */
data class NumberRecordingPatch(
    val enabled: Boolean,
    /** [Change.Keep] = unchanged, [Change.Clear] = no announcement. */
    val announcementSoundId: Change<String> = Change.Keep,
    /** The customer accepted [ChainRecording.cost]. Without it, switching on a paid recording is `cost_not_accepted`. */
    val costAccepted: Boolean? = null,
    /** `NumberChain.version`. */
    val version: Int,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("enabled", JsonPrimitive(enabled))
        putChange("announcementSoundId", announcementSoundId) { JsonPrimitive(it) }
        costAccepted?.let { put("costAccepted", JsonPrimitive(it)) }
        put("version", JsonPrimitive(version))
    }
}
