// SPDX-License-Identifier: AGPL-3.0-or-later
//
// The `fsvoip` object every FSVoip push carries (see `shared/push-payload.schema.json`). FCM carries it as a JSON
// string in `data.fsvoip`. A push never contains the SIP password, a device token or a push token.

package nl.fullstackstudio.fsvoip.core

import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class PushCaller(
    /** Caller number as the PBX knows it; `null` = anonymous. */
    val number: String? = null,
    /** Caller name from the PBX caller id; `null` = unknown. */
    val name: String? = null,
)

@Serializable
data class RingPush(
    /** FreeSWITCH call UUID; equals the `X-FSS-Call` header of the matching SIP INVITE. */
    val callRef: String,
    val from: PushCaller,
    val accountId: String,
    val accountLabel: String,
    /** After this moment (about 12 s) a call without INVITE ends as unanswered. */
    @Serializable(with = InstantSerializer::class) val expiresAt: Instant,
)

@Serializable
data class RevokedPush(
    val accountId: String,
    val accountLabel: String,
)

@Serializable
data class RefreshPush(
    val accountId: String,
)

sealed interface PushMessage {
    data class Ring(val ring: RingPush) : PushMessage

    data class Revoked(val revoked: RevokedPush) : PushMessage

    data class Refresh(val refresh: RefreshPush) : PushMessage

    companion object {
        /** Decode the `fsvoip` object itself (JSON text). */
        fun decode(json: String): PushMessage {
            val element = try {
                FsJson.default.parseToJsonElement(json).jsonObject
            } catch (error: Exception) {
                throw PushMessageException.MissingPayload()
            }

            return decode(element)
        }

        fun decode(element: JsonObject): PushMessage {
            val version = element["v"]?.jsonPrimitive?.intOrNull ?: throw PushMessageException.MissingPayload()

            if (version != 1) {
                throw PushMessageException.UnsupportedVersion(version)
            }

            val type = element["type"]?.jsonPrimitive?.contentOrNull ?: throw PushMessageException.MissingPayload()

            try {
                return when (type) {
                    "ring" -> Ring(FsJson.default.decodeFromJsonElement(RingPush.serializer(), element))
                    "revoked" -> Revoked(FsJson.default.decodeFromJsonElement(RevokedPush.serializer(), element))
                    "refresh" -> Refresh(FsJson.default.decodeFromJsonElement(RefreshPush.serializer(), element))
                    else -> throw PushMessageException.UnknownType(type)
                }
            } catch (error: SerializationException) {
                throw PushMessageException.Malformed(type)
            } catch (error: IllegalArgumentException) {
                throw PushMessageException.Malformed(type)
            }
        }

        /** Decode from the `data` of an FCM message, where `fsvoip` is a JSON string. */
        fun decodeFcm(data: Map<String, String>): PushMessage {
            val text = data["fsvoip"] ?: throw PushMessageException.MissingPayload()

            return decode(text)
        }
    }
}

sealed class PushMessageException(message: String) : Exception(message) {
    class MissingPayload : PushMessageException("missing payload")

    class UnsupportedVersion(val version: Int) : PushMessageException("unsupported version $version")

    class UnknownType(val type: String) : PushMessageException("unknown type $type")

    class Malformed(val type: String) : PushMessageException("malformed $type")
}
