// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.core

import java.security.SecureRandom
import java.util.UUID

/**
 * Identity of THIS installation of the app (not of an account).
 *
 * - `installId`: 16 lowercase hex characters. Sent with `POST /pair`, used as the Contact URI marker `fss-dev=<installId>`
 *   and in the User-Agent, so the PBX push gate can recognise this phone (plan D4).
 * - `sipInstanceId`: a UUID for the RFC 5626 `+sip.instance` Contact parameter.
 *
 * Both are created once and kept in the encrypted store: a backup restored on another phone gets a new identity.
 */
data class InstallIdentity(val installId: String, val sipInstanceId: String) {
    companion object {
        const val INSTALL_ID_KEY = "install.id"
        const val SIP_INSTANCE_KEY = "install.sip-instance"

        /** Load the identity, creating (and storing) the missing parts. A stored value that is not well formed is replaced. */
        fun load(store: SecretStore, random: () -> ByteArray = { secureRandomBytes(8) }): InstallIdentity {
            var installId = store.get(INSTALL_ID_KEY)?.toString(Charsets.UTF_8)
            var sipInstanceId = store.get(SIP_INSTANCE_KEY)?.toString(Charsets.UTF_8)

            if (installId == null || !isValidInstallId(installId)) {
                installId = random().toHex()
                store.set(INSTALL_ID_KEY, installId.toByteArray(Charsets.UTF_8))
            }

            if (sipInstanceId == null || runCatching { UUID.fromString(sipInstanceId) }.isFailure) {
                sipInstanceId = UUID.randomUUID().toString()
                store.set(SIP_INSTANCE_KEY, sipInstanceId.toByteArray(Charsets.UTF_8))
            }

            return InstallIdentity(installId, sipInstanceId)
        }

        /** The server accepts 8-32 hex characters. */
        fun isValidInstallId(value: String): Boolean =
            value.length in 8..32 && value.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

        fun secureRandomBytes(count: Int): ByteArray = ByteArray(count).also { SecureRandom().nextBytes(it) }

        fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }
}
