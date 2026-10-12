// SPDX-License-Identifier: AGPL-3.0-or-later
//
// Kotlin mirror of the rest of the `/pbx` routes of `shared/openapi.yaml`: the call flow ("Belstroom"), opening hours, numbers as a
// chain, call recording and sounds. Responses are decoded leniently; requests are built by hand so an absent key stays absent.

package nl.fullstackstudio.fsvoip.core

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull

// MARK: Overview (the call flow)

@Serializable
enum class FlowKind {
    @SerialName("extension") DEVICE,
    @SerialName("ring_group") RING_GROUP,
    @SerialName("queue") QUEUE,
    @SerialName("ivr") IVR,
    @SerialName("business_hours") BUSINESS_HOURS,
    @SerialName("voicemail") VOICEMAIL,
    @SerialName("recording") RECORDING,
    @SerialName("hangup") HANGUP,
    @SerialName("external") EXTERNAL,
    @SerialName("missing") MISSING,
    @SerialName("loop") LOOP,
    @SerialName("none") NONE,
    @SerialName("more") MORE,
    @SerialName("unknown") UNKNOWN,
}

@Serializable
enum class FlowWhenKind {
    @SerialName("open") OPEN,
    @SerialName("closed") CLOSED,
    @SerialName("no_answer") NO_ANSWER,
    @SerialName("busy") BUSY,
    @SerialName("timeout") TIMEOUT,
    @SerialName("key") KEY,
    @SerialName("no_choice") NO_CHOICE,
    @SerialName("forward_always") FORWARD_ALWAYS,
    @SerialName("unknown") UNKNOWN,
}

@Serializable
data class FlowWhen(val kind: FlowWhenKind = FlowWhenKind.UNKNOWN, val seconds: Int? = null, val digit: String? = null)

@Serializable
data class FlowBranch(@SerialName("when") val condition: FlowWhen = FlowWhen(), val node: FlowNode = FlowNode())

/** One step of the call flow of a number, as a tree. */
@Serializable
data class FlowNode(
    val kind: FlowKind = FlowKind.UNKNOWN,
    val id: String? = null,
    val name: String? = null,
    @SerialName("extension") val extensionNumber: String? = null,
    val ofDevice: Boolean = false,
    val number: String? = null,
    val dnd: Boolean = false,
    val branches: List<FlowBranch> = emptyList(),
)

@Serializable
data class PbxOverviewNumber(
    val id: String,
    val number: String,
    val routing: PbxTarget? = null,
    val blockListId: String? = null,
    val isDefault: Boolean = false,
    val version: Int,
    val sync: SyncState = SyncState.UNKNOWN,
    val recordCalls: Boolean = false,
    val recordAnnouncementSoundId: String? = null,
    val recordBillingActive: Boolean = false,
    val flow: FlowNode = FlowNode(),
)

@Serializable
data class PbxOverview(
    val pbx: PbxStatus,
    val numbers: List<PbxOverviewNumber> = emptyList(),
    val entryFlow: FlowNode? = null,
    val deviceCount: Int = 0,
    /** `null` = the PBX did not answer. */
    val connectedCount: Int? = null,
    val ringGroupCount: Int = 0,
    /** Calling out is temporarily off (fraud guard or administrative hold). */
    val outboundBlocked: Boolean = false,
)

/** `PATCH /pbx/numbers/{id}/routing`: [Change.Clear] = the start of the flow of the PBX. */
data class PbxRoutingPatch(val target: Change<PbxTarget>, val version: Int) {
    fun toJson(): JsonObject = buildJsonObject {
        // `target` is required, also when it is null.
        when (target) {
            Change.Keep, Change.Clear -> put("target", JsonNull)
            is Change.Set -> put("target", target.value.toJson())
        }
        put("version", JsonPrimitive(version))
    }
}

// MARK: Opening hours

@Serializable
data class HoursWeekEntry(val day: Int, val from: String, val to: String) {
    fun toJson(): JsonObject = buildJsonObject {
        put("day", JsonPrimitive(day))
        put("from", JsonPrimitive(from))
        put("to", JsonPrimitive(to))
    }
}

@Serializable
data class PbxHoliday(
    val name: String = "",
    /** `YYYY-MM-DD` for an own date. */
    val date: String? = null,
    /** A fixed holiday (`christmas_day`). */
    val rule: String? = null,
    val target: PbxTarget? = null,
)

@Serializable
data class PbxHoursItem(
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
data class HolidayRuleInfo(val rule: String, val label: String = "")

@Serializable
data class PbxHours(
    val hours: List<PbxHoursItem> = emptyList(),
    val targets: List<PbxTargetOption> = emptyList(),
    val holidayRules: List<HolidayRuleInfo> = emptyList(),
)

/** A holiday as `PATCH /pbx/hours/{id}` takes it: a fixed rule or an own date. */
sealed interface HolidayInput {
    data class Rule(val rule: String) : HolidayInput

    data class Custom(val name: String, val date: String) : HolidayInput

    fun toJson(): JsonObject = buildJsonObject {
        when (this@HolidayInput) {
            is Rule -> put("rule", JsonPrimitive(rule))
            is Custom -> {
                put("name", JsonPrimitive(name))
                put("date", JsonPrimitive(date))
            }
        }
    }
}

/** `PATCH /pbx/hours/{id}`. `week` and `holidays` REPLACE the whole list. */
data class PbxHoursPatch(
    val version: Int,
    val week: List<HoursWeekEntry>? = null,
    val openTarget: Change<PbxTarget> = Change.Keep,
    val closedTarget: Change<PbxTarget> = Change.Keep,
    val holidays: List<HolidayInput>? = null,
    val holidayTarget: Change<PbxTarget> = Change.Keep,
) {
    val isEmpty: Boolean
        get() = toJson().size <= 1

    fun toJson(): JsonObject = buildJsonObject {
        put("version", JsonPrimitive(version))
        week?.let { list -> put("week", JsonArray(list.map { it.toJson() })) }
        putTarget("openTarget", openTarget)
        putTarget("closedTarget", closedTarget)
        holidays?.let { list -> put("holidays", JsonArray(list.map { it.toJson() })) }
        putTarget("holidayTarget", holidayTarget)
    }
}

// MARK: Numbers as a chain

@Serializable
enum class ChainMode {
    @SerialName("simple") SIMPLE,
    @SerialName("advanced") ADVANCED,
}

@Serializable
enum class ForwardingKind {
    @SerialName("standard") STANDARD,
    @SerialName("menu") MENU,
    @SerialName("advanced") ADVANCED,
    @SerialName("unknown") UNKNOWN,
}

@Serializable
data class NumberSummary(
    /** A label in the language of the server (`ma-vr 9:00-17:00`, `Uit`). */
    val hours: String = "",
    val welcome: Boolean = false,
    val forwarding: ForwardingKind = ForwardingKind.UNKNOWN,
    val recording: Boolean = false,
)

@Serializable
data class PbxNumberEntry(
    val id: String,
    val number: String,
    val name: String? = null,
    val mode: ChainMode = ChainMode.ADVANCED,
    val summary: NumberSummary = NumberSummary(),
    val sync: SyncState = SyncState.UNKNOWN,
    val version: Int,
)

@Serializable
data class PbxNumbersPage(val numbers: List<PbxNumberEntry> = emptyList())

/** What happens when it is closed or nobody answers. [Other] is something the chain has no choice for: shown, never sent. */
@Serializable(with = FallbackSerializer::class)
sealed interface Fallback {
    data class Voicemail(val boxId: String? = null, val ofDevice: Boolean = false) : Fallback

    data class Message(val soundId: String) : Fallback

    data class Forward(val number: String) : Fallback

    data object Hangup : Fallback

    data class Device(val deviceId: String) : Fallback

    data class Other(val target: PbxTarget? = null) : Fallback

    /** The chain can send this one back (everything but [Other]). */
    val isSelectable: Boolean
        get() = this !is Other

    /** The body of a `FallbackInput`; `null` for [Other] (never sent). */
    fun toJson(): JsonObject? = when (this) {
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
        is Other -> null
    }

    companion object {
        fun fromJson(element: JsonElement): Fallback {
            val obj = element as? JsonObject ?: return Other()

            return when (obj["mode"]?.let { (it as? JsonPrimitive)?.contentOrNull }) {
                "voicemail" -> Voicemail((obj["boxId"] as? JsonPrimitive)?.contentOrNull, (obj["ofDevice"] as? JsonPrimitive)?.booleanOrNull ?: false)
                "message" -> (obj["soundId"] as? JsonPrimitive)?.contentOrNull?.let { Message(it) } ?: Other()
                "forward" -> (obj["number"] as? JsonPrimitive)?.contentOrNull?.let { Forward(it) } ?: Other()
                "hangup" -> Hangup
                "device" -> (obj["deviceId"] as? JsonPrimitive)?.contentOrNull?.let { Device(it) } ?: Other()
                else -> Other(obj["target"]?.let { runCatching { FsJson.default.decodeFromJsonElement(PbxTarget.serializer(), it) }.getOrNull() })
            }
        }
    }
}

object FallbackSerializer : KSerializer<Fallback> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("nl.fullstackstudio.fsvoip.Fallback")

    override fun deserialize(decoder: Decoder): Fallback = Fallback.fromJson((decoder as JsonDecoder).decodeJsonElement())

    override fun serialize(encoder: Encoder, value: Fallback) {
        (encoder as JsonEncoder).encodeJsonElement(value.toJson() ?: JsonNull)
    }
}

@Serializable
data class ChainHolidayDate(val name: String = "", val date: String = "") {
    fun toJson(): JsonObject = buildJsonObject {
        put("name", JsonPrimitive(name))
        put("date", JsonPrimitive(date))
    }
}

@Serializable
data class ChainHolidays(
    /** All Dutch national holidays use the "closed" behaviour. */
    val national: Boolean = false,
    /** The fixed holidays that are on. */
    val rules: List<String> = emptyList(),
    val dates: List<ChainHolidayDate> = emptyList(),
)

@Serializable
data class ChainHours(
    val id: String,
    val version: Int,
    val week: List<HoursWeekEntry> = emptyList(),
    val holidays: ChainHolidays = ChainHolidays(),
    @Serializable(with = FallbackSerializer::class) val closed: Fallback = Fallback.Hangup,
    /** `null` = on a holiday the same as outside opening hours. */
    @Serializable(with = FallbackSerializer::class) val holiday: Fallback? = null,
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

@Serializable
data class ChainMember(val deviceId: String, val delaySeconds: Int = 0, val timeoutSeconds: Int = 25) {
    fun toJson(): JsonObject = buildJsonObject {
        put("deviceId", JsonPrimitive(deviceId))
        put("delaySeconds", JsonPrimitive(delaySeconds))
        put("timeoutSeconds", JsonPrimitive(timeoutSeconds))
    }
}

@Serializable
data class ChainKey(
    val digit: String,
    val target: PbxTarget = PbxTarget(),
    /** `false` = a key the app shows but cannot change; it stays as it is when the menu is saved. */
    val editable: Boolean = true,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("digit", JsonPrimitive(digit))
        put("target", target.toJson())
        // Only a key the app cannot change says so; the server leaves it as it is.
        if (!editable) put("editable", JsonPrimitive(false))
    }
}

@Serializable(with = ChainForwardingSerializer::class)
sealed interface ChainForwarding {
    val version: Int
    val sharedWith: List<String>

    @Serializable
    data class Standard(
        /** `null` = one single extension (no ring group yet). */
        val groupId: String? = null,
        override val version: Int = 1,
        val strategy: RingStrategy = RingStrategy.ALL,
        val members: List<ChainMember> = emptyList(),
        @Serializable(with = FallbackSerializer::class) val unanswered: Fallback = Fallback.Hangup,
        override val sharedWith: List<String> = emptyList(),
    ) : ChainForwarding

    @Serializable
    data class Menu(
        val menuId: String,
        override val version: Int = 1,
        val greetingSoundId: String? = null,
        val repeats: Int = 0,
        val timeoutSeconds: Int = 8,
        val defaultKey: String? = null,
        @Serializable(with = FallbackSerializer::class) val noChoice: Fallback = Fallback.Hangup,
        val keys: List<ChainKey> = emptyList(),
        override val sharedWith: List<String> = emptyList(),
    ) : ChainForwarding

    /** A kind this app version does not know. */
    data object Unknown : ChainForwarding {
        override val version: Int = 0
        override val sharedWith: List<String> = emptyList()
    }
}

object ChainForwardingSerializer : KSerializer<ChainForwarding> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("nl.fullstackstudio.fsvoip.ChainForwarding")

    override fun deserialize(decoder: Decoder): ChainForwarding {
        val element = (decoder as JsonDecoder).decodeJsonElement()
        val kind = ((element as? JsonObject)?.get("kind") as? JsonPrimitive)?.contentOrNull

        return try {
            when (kind) {
                "standard" -> FsJson.default.decodeFromJsonElement(ChainForwarding.Standard.serializer(), element)
                "menu" -> FsJson.default.decodeFromJsonElement(ChainForwarding.Menu.serializer(), element)
                else -> ChainForwarding.Unknown
            }
        } catch (_: Exception) {
            ChainForwarding.Unknown
        }
    }

    override fun serialize(encoder: Encoder, value: ChainForwarding) {
        (encoder as JsonEncoder).encodeJsonElement(JsonNull)
    }
}

@Serializable
data class ChainRecording(
    val enabled: Boolean = false,
    val announcementSoundId: String? = null,
    /** Recording is already being billed; changing the announcement then needs no new consent. */
    val billingActive: Boolean = false,
    /** `false` = recording cannot be switched on on this PBX (there is no price). */
    val available: Boolean = false,
    /** `null` = free for this customer (no consent needed). */
    val cost: RecordingCost? = null,
)

@Serializable
data class ChainDevice(val id: String, val name: String, @SerialName("extension") val extensionNumber: String? = null)

@Serializable
data class ChainNamed(val id: String, val name: String)

@Serializable
data class ChainOptions(
    val devices: List<ChainDevice> = emptyList(),
    val sounds: List<ChainNamed> = emptyList(),
    val groups: List<ChainNamed> = emptyList(),
)

@Serializable
data class ChainAdvanced(val summary: List<String> = emptyList())

@Serializable
data class NumberChain(
    val id: String,
    val number: String,
    val name: String? = null,
    val version: Int,
    val mode: ChainMode = ChainMode.ADVANCED,
    val sync: SyncState = SyncState.UNKNOWN,
    val hours: ChainHours? = null,
    val welcome: ChainWelcome? = null,
    val forwarding: ChainForwarding? = null,
    val advanced: ChainAdvanced? = null,
    val recording: ChainRecording = ChainRecording(),
    val options: ChainOptions = ChainOptions(),
) {
    val isEditable: Boolean
        get() = mode == ChainMode.SIMPLE

    /** The versions of the parts of the chain as it shows them (`{ <objectId>: version }`), sent back with a step. */
    val versions: Map<String, Int>
        get() = buildMap {
            hours?.let { put(it.id, it.version) }
            welcome?.let { put(it.menuId, it.version) }

            when (val forwarding = forwarding) {
                is ChainForwarding.Standard -> forwarding.groupId?.let { put(it, forwarding.version) }
                is ChainForwarding.Menu -> put(forwarding.menuId, forwarding.version)
                else -> Unit
            }
        }
}

// MARK: Chain steps (`PUT /pbx/numbers/{id}/chain/{step}`)

/** A change of one step of a chain. [path] is the `step` of the URL. */
sealed interface ChainStep {
    val path: String

    fun body(): JsonObject

    /** The objects of the chain this step changes: only their versions go back. */
    fun versionIds(chain: NumberChain): List<String> = emptyList()

    data class Name(val name: String?) : ChainStep {
        override val path = "name"

        override fun body() = buildJsonObject { put("name", name?.let(::JsonPrimitive) ?: JsonNull) }
    }

    /** `enabled = false` unlinks the opening hours (the object stays). */
    data class Hours(
        val enabled: Boolean,
        val week: List<HoursWeekEntry>? = null,
        val nationalHolidays: Boolean? = null,
        val holidayRules: List<String>? = null,
        val holidayDates: List<ChainHolidayDate>? = null,
        val closed: Fallback? = null,
        /** [Change.Clear] = on a holiday the same as outside opening hours. */
        val holiday: Change<Fallback> = Change.Keep,
    ) : ChainStep {
        override val path = "hours"

        override fun versionIds(chain: NumberChain) = listOfNotNull(chain.hours?.id)

        override fun body() = buildJsonObject {
            put("enabled", JsonPrimitive(enabled))
            week?.let { list -> put("week", JsonArray(list.map { it.toJson() })) }

            if (nationalHolidays != null || holidayRules != null || holidayDates != null) {
                put(
                    "holidays",
                    buildJsonObject {
                        nationalHolidays?.let { put("national", JsonPrimitive(it)) }
                        holidayRules?.let { list -> put("rules", JsonArray(list.map(::JsonPrimitive))) }
                        holidayDates?.let { list -> put("dates", JsonArray(list.map { it.toJson() })) }
                    },
                )
            }

            closed?.toJson()?.let { put("closed", it) }
            putFallback("holiday", holiday)
        }
    }

    data class Welcome(val enabled: Boolean, val soundId: Change<String> = Change.Keep) : ChainStep {
        override val path = "welcome"

        override fun versionIds(chain: NumberChain) = listOfNotNull(chain.welcome?.menuId)

        override fun body() = buildJsonObject {
            put("enabled", JsonPrimitive(enabled))
            putText("soundId", soundId)
        }
    }

    data class Closed(val closed: Fallback? = null, val holiday: Change<Fallback> = Change.Keep) : ChainStep {
        override val path = "closed"

        override fun versionIds(chain: NumberChain) = listOfNotNull(chain.hours?.id)

        override fun body() = buildJsonObject {
            closed?.toJson()?.let { put("closed", it) }
            putFallback("holiday", holiday)
        }
    }

    data class ForwardingStandard(
        val strategy: RingStrategy? = null,
        val members: List<ChainMember>? = null,
        val unanswered: Fallback? = null,
    ) : ChainStep {
        override val path = "forwarding"

        override fun versionIds(chain: NumberChain) = listOfNotNull((chain.forwarding as? ChainForwarding.Standard)?.groupId)

        override fun body() = buildJsonObject {
            put("kind", JsonPrimitive("standard"))
            strategy?.let { put("strategy", JsonPrimitive(it.wire)) }
            members?.let { list -> put("members", JsonArray(list.map { it.toJson() })) }
            unanswered?.toJson()?.let { put("unanswered", it) }
        }
    }

    data class ForwardingMenu(
        val greetingSoundId: Change<String> = Change.Keep,
        val repeats: Int? = null,
        val timeoutSeconds: Int? = null,
        val defaultKey: Change<String> = Change.Keep,
        val noChoice: Fallback? = null,
        val keys: List<ChainKey>? = null,
    ) : ChainStep {
        override val path = "forwarding"

        override fun versionIds(chain: NumberChain) = listOfNotNull((chain.forwarding as? ChainForwarding.Menu)?.menuId)

        override fun body() = buildJsonObject {
            put("kind", JsonPrimitive("menu"))
            putText("greetingSoundId", greetingSoundId)
            repeats?.let { put("repeats", JsonPrimitive(it)) }
            timeoutSeconds?.let { put("timeoutSeconds", JsonPrimitive(it)) }
            putText("defaultKey", defaultKey)
            noChoice?.toJson()?.let { put("noChoice", it) }
            keys?.let { list -> put("keys", JsonArray(list.map { it.toJson() })) }
        }
    }

    /** The request: the step's own keys plus the versions of the parts it changes, as the chain showed them. */
    fun request(chain: NumberChain): JsonObject {
        val own = body()
        val versions = versionIds(chain).mapNotNull { id -> chain.versions[id]?.let { id to it } }

        return buildJsonObject {
            own.forEach { (key, value) -> put(key, value) }
            if (versions.isNotEmpty()) put("versions", buildJsonObject { versions.forEach { (id, version) -> put(id, JsonPrimitive(version)) } })
            put("numberVersion", JsonPrimitive(chain.version))
        }
    }
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putFallback(key: String, change: Change<Fallback>) {
    when (change) {
        Change.Keep -> Unit
        Change.Clear -> put(key, JsonNull)
        is Change.Set -> change.value.toJson()?.let { put(key, it) }
    }
}

/** `PATCH /pbx/numbers/{id}/recording`. */
data class NumberRecordingPatch(
    val enabled: Boolean,
    /** [Change.Clear] = no announcement; [Change.Keep] = unchanged. */
    val announcementSoundId: Change<String> = Change.Keep,
    val costAccepted: Boolean? = null,
    val version: Int,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("enabled", JsonPrimitive(enabled))
        putText("announcementSoundId", announcementSoundId)
        costAccepted?.let { put("costAccepted", JsonPrimitive(it)) }
        put("version", JsonPrimitive(version))
    }
}

// MARK: Sounds

@Serializable
data class SoundUse(val kind: String = "", val name: String = "")

@Serializable
data class PbxSound(
    val id: String,
    val name: String,
    /** Always `null` for now: the app reads the duration from the audio. */
    val durationSeconds: Double? = null,
    /** `false` = there is no copy to play (a sound from before this feature); the PBX still uses it. */
    val playable: Boolean = false,
    val uses: List<SoundUse> = emptyList(),
    val contentType: String? = null,
    val byteSize: Long? = null,
    val sync: SyncState = SyncState.UNKNOWN,
)

@Serializable
data class PbxSoundsPage(val sounds: List<PbxSound> = emptyList())

@Serializable
data class SoundUploadResponse(val soundId: String)
