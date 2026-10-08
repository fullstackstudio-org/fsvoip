// SPDX-License-Identifier: AGPL-3.0-or-later
//
// Kotlin mirror of `shared/openapi.yaml` (API v1). Keep the two in sync; the contract tests decode
// `shared/fixtures/*` with these types.

package nl.fullstackstudio.fsvoip.core

import java.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

@Serializable
enum class ApiPlatform {
    @SerialName("ios") IOS,
    @SerialName("android") ANDROID,
}

@Serializable
enum class PushKind(val wire: String) {
    /** iOS PushKit (VoIP) token. */
    @SerialName("apns_voip") APNS_VOIP("apns_voip"),

    /** Firebase Cloud Messaging (Android). */
    @SerialName("fcm") FCM("fcm"),
    ;

    companion object {
        fun fromWire(value: String?): PushKind? = entries.firstOrNull { it.wire == value }
    }
}

@Serializable
enum class PushEnvironment(val wire: String) {
    @SerialName("production") PRODUCTION("production"),
    @SerialName("sandbox") SANDBOX("sandbox"),
    ;

    companion object {
        fun fromWire(value: String?): PushEnvironment? = entries.firstOrNull { it.wire == value }
    }
}

/** Transport of the SIP connection as the server tells us (`sip.transport`). */
@Serializable
enum class ServerTransport {
    @SerialName("udp") UDP,
    @SerialName("tcp") TCP,
    @SerialName("tls") TLS,
}

// MARK: Pairing

@Serializable
data class DeviceInfo(
    val platform: ApiPlatform,
    val model: String? = null,
    val osVersion: String? = null,
    val appVersion: String? = null,
    val sipInstanceId: String? = null,
    val installId: String? = null,
    val pushToken: String? = null,
    val pushKind: PushKind? = null,
    val pushEnv: PushEnvironment? = null,
    /** iOS only. Android has one FCM token for everything and never sends this. */
    val alertPushToken: String? = null,
) {
    override fun toString(): String = "DeviceInfo(platform=$platform, model=$model, installId=$installId, push=${pushKind != null})"
}

@Serializable
data class PairRequest(
    val token: String,
    val device: DeviceInfo,
) {
    override fun toString(): String = "PairRequest(token=•••, device=$device)"
}

@Serializable
data class PairedDevice(
    val id: String,
    val installId: String,
)

@Serializable
data class AccountInfo(
    /** Id of this app pairing; equals `PairedDevice.id` and is what pushes call `accountId`. */
    val id: String,
    /** What the app shows: the alias if set, otherwise `<centrale> · <toestel>`. */
    val label: String,
    /** The alias chosen on the phone (only present in `GET /me`). */
    val labelOverride: String? = null,
    val pbxName: String,
    val extensionName: String,
    val extensionNumber: String? = null,
    val customerName: String,
)

/** The SIP credentials, returned exactly once by `POST /pair`. */
@Serializable
data class SipCredentials(
    val username: String,
    val password: Secret,
    /** SIP domain of the tenant (registrar domain / realm). NOT the outbound proxy. */
    val domain: String,
    /** Outbound proxy host. */
    val proxy: String,
    val port: Int,
    val transport: ServerTransport,
    /** DNS SRV records exist for the proxy. */
    val srv: Boolean,
)

/** `sip` in `GET /me`: the server side only, no username or password. */
@Serializable
data class SipServer(
    val domain: String,
    val proxy: String,
    val port: Int,
    val transport: ServerTransport,
    val srv: Boolean,
)

@Serializable
data class ContactsInfo(
    /** Internal contacts (the other extensions) are available via `GET /me`. */
    @SerialName("internal") val hasInternal: Boolean,
    /** Number of customer contact lists in the portal. */
    val listsAvailable: Int,
)

@Serializable
data class PairResponse(
    val deviceToken: Secret,
    val device: PairedDevice,
    val account: AccountInfo,
    val sip: SipCredentials,
    val contacts: ContactsInfo,
)

// MARK: /me

@Serializable
data class InternalContact(
    val number: String,
    val name: String,
)

@Serializable
data class PushStatus(
    val registered: Boolean,
    /** Raw value: informational, a value this app version does not know must not make the whole `/me` fail. */
    @SerialName("kind") val kindRaw: String? = null,
    @SerialName("env") val envRaw: String? = null,
    /** The push service rejected the token: the app must register a fresh one. */
    val invalid: Boolean,
) {
    val kind: PushKind?
        get() = PushKind.fromWire(kindRaw)

    val env: PushEnvironment?
        get() = PushEnvironment.fromWire(envRaw)
}

@Serializable
data class MeResponse(
    val account: AccountInfo,
    /** `null` when the server could not determine the PBX connection details right now. */
    val sip: SipServer? = null,
    @SerialName("internal") val internalContacts: List<InternalContact>,
    val contacts: ContactsInfo,
    val push: PushStatus,
    @Serializable(with = InstantSerializer::class) val serverTime: Instant,
)

/** `PATCH /me`. The key is always sent; `null` clears the alias. */
data class MePatchRequest(val labelOverride: String?) {
    fun toJson(): JsonObject = buildJsonObject {
        put("labelOverride", labelOverride?.let(::JsonPrimitive) ?: JsonNull)
    }
}

@Serializable
data class MePatchResponse(
    val label: String,
    val labelOverride: String? = null,
)

/** `PUT /push-token`. Android sends its FCM token as `pushToken` with `pushKind: fcm`; there is no alert token. */
data class PushTokenUpdate(
    val pushKind: PushKind?,
    val pushToken: String?,
    val pushEnv: PushEnvironment? = null,
    val alertPushToken: String? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        if (pushToken == null) {
            // An explicit null is the documented way to unregister.
            put("pushToken", JsonNull)
            return@buildJsonObject
        }

        pushKind?.let { put("pushKind", JsonPrimitive(it.wire)) }
        put("pushToken", JsonPrimitive(pushToken))
        pushEnv?.let { put("pushEnv", JsonPrimitive(it.wire)) }
        alertPushToken?.let { put("alertPushToken", JsonPrimitive(it)) }
    }

    override fun toString(): String = "PushTokenUpdate(kind=$pushKind, token=${if (pushToken == null) "none" else "•••"})"

    companion object {
        /** Unregister push for this installation (`{"pushToken": null}`). */
        val CLEAR = PushTokenUpdate(pushKind = null, pushToken = null)
    }
}

@Serializable
data class OkResponse(val ok: Boolean)

/** Error body of every non-2xx answer. */
@Serializable
data class ApiErrorBody(
    val error: String,
    val message: String? = null,
    val retryable: Boolean? = null,
)
