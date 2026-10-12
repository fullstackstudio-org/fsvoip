// SPDX-License-Identifier: AGPL-3.0-or-later
//
// Kotlin mirror of the role/capabilities part of `GET /me` and of the own extension (`/me/extension`) in
// `shared/openapi.yaml`. Responses are decoded leniently (an unknown enum value becomes the restrictive default, see
// `FsJson.coerceInputValues`); requests are built by hand so an absent key stays absent.

package nl.fullstackstudio.fsvoip.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** The role of this pairing. `admin` may manage the PBX from the app; everything else is `user`. */
@Serializable
enum class AppRole {
    @SerialName("user") USER,
    @SerialName("admin") ADMIN,
    @SerialName("unknown") UNKNOWN,
}

@Serializable
enum class VoicemailAccess {
    @SerialName("all") ALL,
    @SerialName("own") OWN,
    @SerialName("none") NONE,
}

@Serializable
enum class CallsAccess {
    /** Every call of the PBX, with recordings (admin). */
    @SerialName("all") ALL,

    /** Every call of the PBX without recordings: the team history (`user`). */
    @SerialName("team") TEAM,

    /** Only the calls of the own extension (a server from before the team history). */
    @SerialName("own") OWN,
}

@Serializable
enum class SoundsAccess {
    @SerialName("manage") MANAGE,
    @SerialName("none") NONE,
}

@Serializable
data class ContactCapabilities(
    val read: Boolean = true,
    val write: Boolean = true,
    /** Deleting is the restricted one: a missing value means no. */
    val delete: Boolean = false,
)

/** Every missing or unknown value falls back to the restrictive choice. */
@Serializable
data class AppCapabilities(
    /** The "Centrale" section (`/pbx/...`). */
    val pbxManage: Boolean = false,
    /** Call recordings (`/calls/{id}/recording`). */
    val recordings: Boolean = false,
    val voicemail: VoicemailAccess = VoicemailAccess.NONE,
    val calls: CallsAccess = CallsAccess.OWN,
    val contacts: ContactCapabilities = ContactCapabilities(),
    /** `GET`/`PATCH /me/extension`: the own extension (every role). */
    val selfExtension: Boolean = false,
    val sounds: SoundsAccess = SoundsAccess.NONE,
    /** Invite a colleague (`POST /pbx/devices/{id}/app-pairing`). */
    val invite: Boolean = false,
    /** Parking works on this PBX; `false` = do not show the park button or the "On hold" tab. */
    val park: Boolean = false,
    /** Live calls (`/pbx/live/...`) work on this PBX; `false` = do not show the Live section. Every role may use it. */
    val live: Boolean = false,
    /** The PBX understands `X-FSS-From`: only then may the app choose the number to call out with. */
    val callerChoice: Boolean = false,
)

@Serializable
enum class PbxState {
    @SerialName("active") ACTIVE,
    @SerialName("frozen") FROZEN,
    @SerialName("setup") SETUP,
    @SerialName("error") ERROR,
    @SerialName("unknown") UNKNOWN,
}

/** The PBX as the app sees it in `/me`. `readOnly`: frozen, still being set up or in an error state. */
@Serializable
data class PbxStatus(
    val name: String,
    val state: PbxState = PbxState.UNKNOWN,
    /** Fail closed: an unknown or missing flag means the app does not offer changes. */
    val readOnly: Boolean = true,
)

// MARK: The own extension

@Serializable
enum class SyncState {
    @SerialName("ok") OK,
    @SerialName("pending") PENDING,
    @SerialName("error") ERROR,
    @SerialName("unknown") UNKNOWN,
}

/** What a call can be sent to. */
@Serializable
enum class TargetType(val wire: String) {
    @SerialName("extension") DEVICE("extension"),
    @SerialName("ring_group") RING_GROUP("ring_group"),
    @SerialName("queue") QUEUE("queue"),
    @SerialName("ivr") IVR("ivr"),
    @SerialName("business_hours") BUSINESS_HOURS("business_hours"),
    @SerialName("voicemail") VOICEMAIL("voicemail"),
    @SerialName("recording") RECORDING("recording"),
    @SerialName("hangup") HANGUP("hangup"),
    @SerialName("external") EXTERNAL("external"),
    @SerialName("unknown") UNKNOWN("unknown"),
}

/** A destination. `id` = an object of this PBX (never a PBX uuid), `number` = an external number (`external` only). */
@Serializable
data class PbxTarget(
    val type: TargetType = TargetType.UNKNOWN,
    val id: String? = null,
    val number: String? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("type", JsonPrimitive(type.wire))
        id?.let { put("id", JsonPrimitive(it)) }
        number?.let { put("number", JsonPrimitive(it)) }
    }

    companion object {
        fun external(number: String) = PbxTarget(TargetType.EXTERNAL, number = number)

        fun obj(type: TargetType, id: String) = PbxTarget(type, id = id)
    }
}

/** A choice for "where does the call go" (`value` is `type:id`, `external` or `none`). */
@Serializable
data class PbxTargetOption(
    val value: String,
    val type: TargetType = TargetType.UNKNOWN,
    val id: String? = null,
    val name: String,
    @SerialName("extension") val extensionNumber: String? = null,
    /** The voicemail of an extension (as opposed to a shared mailbox). */
    val ofDevice: Boolean = false,
)

/** A number of the PBX as `GET /me/extension` lists it. */
@Serializable
data class SelfNumber(
    val id: String,
    /** National, without country code (`0850607848`). */
    val number: String,
    val name: String? = null,
    /** The default number of the PBX. */
    val isDefault: Boolean = false,
)

/** The own extension: do not disturb, forwarding, voicemail. Every role may read and change it. */
@Serializable
data class SelfExtension(
    val id: String,
    val name: String,
    @SerialName("extension") val extensionNumber: String? = null,
    val dnd: Boolean = false,
    val forwardAlways: PbxTarget? = null,
    val noAnswerSeconds: Int = 25,
    val noAnswerTarget: PbxTarget? = null,
    val voicemailEnabled: Boolean = false,
    val voicemailToEmail: Boolean = false,
    val email: String? = null,
    val sync: SyncState = SyncState.UNKNOWN,
    /** Goes back with a change; another version is `409 stale`. */
    val version: Int,
    val numbers: List<SelfNumber> = emptyList(),
    /** The number this extension calls out with by default; `null` = none. */
    val defaultNumber: String? = null,
    /** Where a call may be forwarded to. Never the extension itself. */
    val targets: List<PbxTargetOption> = emptyList(),
)

/** Answer of `PATCH /me/extension`: `extension` is the fresh state, best effort (`null` when the server could not read it). */
@Serializable
data class SelfExtensionPatchResponse(
    val ok: Boolean,
    val extension: JsonElement? = null,
) {
    /** The fresh state, or `null` when the server left it out or it cannot be read. */
    fun state(): SelfExtension? = extension?.takeIf { it !is JsonNull }?.let {
        try {
            FsJson.default.decodeFromJsonElement(SelfExtension.serializer(), it)
        } catch (_: Exception) {
            null
        }
    }
}

/** A field of a PATCH body: [Keep] leaves the key out, [Clear] sends an explicit `null`, [Set] sends the value. */
sealed interface Change<out T> {
    data object Keep : Change<Nothing>

    data object Clear : Change<Nothing>

    data class Set<T>(val value: T) : Change<T>

    companion object {
        /** `null` clears, a value sets. */
        fun <T : Any> of(value: T?): Change<T> = if (value == null) Clear else Set(value)
    }
}

internal fun kotlinx.serialization.json.JsonObjectBuilder.putText(key: String, change: Change<String>) {
    when (change) {
        Change.Keep -> Unit
        Change.Clear -> put(key, JsonNull)
        is Change.Set -> put(key, JsonPrimitive(change.value))
    }
}

internal fun kotlinx.serialization.json.JsonObjectBuilder.putTarget(key: String, change: Change<PbxTarget>) {
    when (change) {
        Change.Keep -> Unit
        Change.Clear -> put(key, JsonNull)
        is Change.Set -> put(key, change.value.toJson())
    }
}

/**
 * `PATCH /me/extension`: only these keys (the number to call out with is not a setting: the app chooses it per call).
 * `version` is required; at least one other key.
 */
data class SelfExtensionPatch(
    val version: Int,
    val dnd: Boolean? = null,
    /** [Change.Clear] = no forwarding. */
    val forwardAlways: Change<PbxTarget> = Change.Keep,
    val noAnswerSeconds: Int? = null,
    val noAnswerTarget: Change<PbxTarget> = Change.Keep,
    val voicemailEnabled: Boolean? = null,
    val voicemailToEmail: Boolean? = null,
    val email: Change<String> = Change.Keep,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("version", JsonPrimitive(version))
        dnd?.let { put("dnd", JsonPrimitive(it)) }
        putTarget("forwardAlways", forwardAlways)
        noAnswerSeconds?.let { put("noAnswerSeconds", JsonPrimitive(it)) }
        putTarget("noAnswerTarget", noAnswerTarget)
        voicemailEnabled?.let { put("voicemailEnabled", JsonPrimitive(it)) }
        voicemailToEmail?.let { put("voicemailToEmail", JsonPrimitive(it)) }
        putText("email", email)
    }
}

// MARK: Caller choice (`X-FSS-From`)

/**
 * "Calling out via": the SIP header that tells the PBX which of its numbers an outgoing call uses. 🚨 Only sent when
 * [AppCapabilities.callerChoice] is true; a PBX without the dialplan would forward an unknown header to the provider.
 */
object CallerChoice {
    const val HEADER_NAME = "X-FSS-From"

    /** A national number of the PBX: ten digits, the first a 0 and the second not (`0850607848`). */
    fun isValidNumber(number: String): Boolean =
        number.length == 10 && number.all { it in '0'..'9' } && number[0] == '0' && number[1] != '0'

    /**
     * The number to put in the header: only when the PBX supports the choice AND [number] is a number it lists
     * (`numbers` = [SelfExtension.numbers]). Otherwise `null`: no header at all.
     */
    fun header(choosing: String?, capabilities: AppCapabilities?, numbers: List<SelfNumber>): String? {
        if (capabilities?.callerChoice != true || choosing == null || !isValidNumber(choosing) || numbers.none { it.number == choosing }) {
            return null
        }

        return choosing
    }
}
