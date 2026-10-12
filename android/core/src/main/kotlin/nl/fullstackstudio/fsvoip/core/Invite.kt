// SPDX-License-Identifier: AGPL-3.0-or-later
//
// Rules for "Gebruiker uitnodigen": which extensions may be invited, and how long the link lives. The link itself is a credential and
// stays out of this file's state: the screen holds it only while it is shown.

package nl.fullstackstudio.fsvoip.core

import java.time.Instant
import java.time.OffsetDateTime

object InviteRules {
    /** The extensions that can be invited: all except the one of this very app (the server refuses it: it would replace this app). */
    fun candidates(devices: List<PbxDevice>, ownDeviceId: String?, ownExtensionNumber: String?): List<PbxDevice> = devices.filter { device ->
        when {
            ownDeviceId != null -> device.id != ownDeviceId
            ownExtensionNumber != null -> device.extensionNumber != ownExtensionNumber
            else -> true
        }
    }

    /** Seconds the link still lives; 0 when it is over or the moment cannot be read. */
    fun remainingSeconds(expiresAt: String, now: Instant): Long {
        val end = parse(expiresAt) ?: return 0

        return (end.epochSecond - now.epochSecond).coerceAtLeast(0)
    }

    /** `9:42`, never negative. */
    fun countdown(seconds: Long): String {
        val total = seconds.coerceAtLeast(0)

        return "%d:%02d".format(java.util.Locale.ROOT, total / 60, total % 60)
    }

    private fun parse(text: String): Instant? = runCatching { Instant.parse(text) }.getOrNull() ?: runCatching { OffsetDateTime.parse(text).toInstant() }.getOrNull()
}
