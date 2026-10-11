// SPDX-License-Identifier: AGPL-3.0-or-later
//
// The own extension (`GET`/`PATCH /me/extension`, every role): do-not-disturb, forwarding, voicemail.

package nl.fullstackstudio.fsvoip.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** What to do with one key of a PATCH: leave it out, send an explicit `null`, or send a value. */
sealed interface Change<out T> {
    data object Keep : Change<Nothing>

    data object Clear : Change<Nothing>

    data class To<T>(val value: T) : Change<T>

    val isKept: Boolean get() = this === Keep

    companion object {
        /** null clears, a value sets. */
        fun <T : Any> of(value: T?): Change<T> = if (value == null) Clear else To(value)
    }
}

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
    /** The numbers of the PBX. Calling out with one of them: caller choice. */
    val numbers: List<SelfNumber> = emptyList(),
    /** The number this extension calls out with by default; null = none. */
    val defaultNumber: String? = null,
    /** Where a call may be forwarded to (a colleague, a ring group, a voicemail box, an external number). Never the extension itself. */
    val targets: List<PbxTargetOption> = emptyList(),
)

/**
 * `PATCH /me/extension`: only these keys (the number to call out with is not a setting: the app chooses it per call). `version` is
 * required; at least one other key.
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
    /** No key besides `version`. */
    val isEmpty: Boolean
        get() = dnd == null && forwardAlways.isKept && noAnswerSeconds == null && noAnswerTarget.isKept && voicemailEnabled == null && voicemailToEmail == null && email.isKept

    fun toJson(): JsonObject = buildJsonObject {
        put("version", JsonPrimitive(version))
        dnd?.let { put("dnd", JsonPrimitive(it)) }
        putChange("forwardAlways", forwardAlways) { FsJson.default.encodeToJsonElement(PbxTarget.serializer(), it) }
        noAnswerSeconds?.let { put("noAnswerSeconds", JsonPrimitive(it)) }
        putChange("noAnswerTarget", noAnswerTarget) { FsJson.default.encodeToJsonElement(PbxTarget.serializer(), it) }
        voicemailEnabled?.let { put("voicemailEnabled", JsonPrimitive(it)) }
        voicemailToEmail?.let { put("voicemailToEmail", JsonPrimitive(it)) }
        putChange("email", email) { JsonPrimitive(it) }
    }
}

private fun <T> kotlinx.serialization.json.JsonObjectBuilder.putChange(key: String, change: Change<T>, encode: (T) -> JsonElement) {
    when (change) {
        Change.Keep -> Unit
        Change.Clear -> put(key, JsonNull)
        is Change.To -> put(key, encode(change.value))
    }
}

/**
 * Answer of `PATCH /me/extension`: `extension` is the fresh state, best effort (null when the server could not read it right now or
 * when it cannot be read: that must not hide the fact that the change was saved).
 */
data class SelfExtensionPatchResponse(
    val ok: Boolean,
    val extensionState: SelfExtension? = null,
)
