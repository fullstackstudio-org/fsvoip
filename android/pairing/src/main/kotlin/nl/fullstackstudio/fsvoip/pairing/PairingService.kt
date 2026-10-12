// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.pairing

import java.time.Instant
import nl.fullstackstudio.fsvoip.core.AccountStore
import nl.fullstackstudio.fsvoip.core.ApiPlatform
import nl.fullstackstudio.fsvoip.core.DeviceInfo
import nl.fullstackstudio.fsvoip.core.FsLogger
import nl.fullstackstudio.fsvoip.core.FsVoipApiClient
import nl.fullstackstudio.fsvoip.core.PairRequest
import nl.fullstackstudio.fsvoip.core.PushKind
import nl.fullstackstudio.fsvoip.core.StoredAccount

/** What the app tells the server about itself when pairing. */
data class DeviceDescriptor(
    val model: String?,
    val osVersion: String?,
    val appVersion: String?,
    val installId: String,
    val sipInstanceId: String? = null,
    /** The FCM token, when Firebase is configured and gave one. */
    val pushToken: String? = null,
) {
    /** Android: platform `android`, push kind `fcm`, no environment and no separate alert token. */
    fun toDeviceInfo(): DeviceInfo = DeviceInfo(
        platform = ApiPlatform.ANDROID,
        model = model?.take(64),
        osVersion = osVersion?.take(32),
        appVersion = appVersion?.take(32),
        sipInstanceId = sipInstanceId,
        installId = installId,
        pushToken = pushToken,
        pushKind = if (pushToken == null) null else PushKind.FCM,
    )

    override fun toString(): String = "DeviceDescriptor(model=$model, installId=$installId, push=${pushToken != null})"
}

/** Exchanges a pairing link for a stored account (used by [AccountService.pair]). */
class PairingService(
    private val api: FsVoipApiClient,
    private val accounts: AccountStore,
    private val logger: FsLogger = FsLogger("pairing"),
) {
    /** `POST /pair`, then keep the result (SIP password and device token) in the encrypted store only. */
    suspend fun pair(link: PairingLink, device: DeviceDescriptor, now: Instant = Instant.now()): StoredAccount {
        val response = api.pair(PairRequest(link.token, device.toDeviceInfo()))
        val account = StoredAccount.fromPairing(response, now)

        accounts.save(account)
        logger.notice("Paired account ${account.id}")

        return account
    }
}
