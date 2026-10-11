// SPDX-License-Identifier: AGPL-3.0-or-later
//
// Shared pieces of "Recent" (history), "Voicemail" and "Opnames": the rights per account, the local access check, banners and the
// formats of time. Nothing here touches Android classes, so it runs in plain unit tests.

package nl.fullstackstudio.fsvoip.ui.media

import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import nl.fullstackstudio.fsvoip.core.MediaFailure
import nl.fullstackstudio.fsvoip.core.PlaybackSpeed

enum class MediaKind { RECORDING, VOICEMAIL }

/** Which part of the media rights a 403 took away. */
enum class MediaPart { VOICEMAIL, RECORDINGS }

/** What the player bar shows about the item that plays. */
data class NowPlaying(val id: String, val title: String, val subtitle: String)

/** What a banner says. The screens turn it into a sentence; no server text, no telecom words. */
sealed interface MediaMessage {
    data class Failure(val failure: MediaFailure, val kind: MediaKind) : MediaMessage

    /** The phone system is locked: nothing can be deleted. */
    data object DeleteReadOnly : MediaMessage

    /** The phone has no passcode / screen lock: the protected parts cannot be opened. */
    data object NoScreenLock : MediaMessage
}

data class MediaBanner(val message: MediaMessage, val isError: Boolean = true)

// MARK: The local access check

/** The reason shown with the local check (the screen lock prompt). */
enum class GateReason { RECORDINGS, VOICEMAIL }

enum class GateResult { UNLOCKED, CANCELLED, FAILED, UNAVAILABLE }

/**
 * The local check in front of what is not yours alone (a colleague's voicemail, the recordings): screen lock / fingerprint, valid
 * for five minutes together with the "Centrale" section. The check itself belongs to the local-access slice; this is the shape the
 * media screens need. [OpenAccessGate] lets everything through until the real gate is plugged in ([MediaWiring.gateFactory]).
 */
interface MediaAccessGate {
    val isUnlocked: Boolean

    suspend fun ensureUnlocked(reason: GateReason): GateResult

    /** The user is working in a protected part: the five minutes start over. */
    fun touch()

    fun lock()
}

class OpenAccessGate : MediaAccessGate {
    override val isUnlocked: Boolean get() = true

    override suspend fun ensureUnlocked(reason: GateReason): GateResult = GateResult.UNLOCKED

    override fun touch() = Unit

    override fun lock() = Unit
}

// MARK: Formats

object MediaFormat {
    /** `83` → `1:23`, `3725` → `1:02:05`. */
    fun clock(seconds: Double): String {
        val total = seconds.coerceAtLeast(0.0).toLong()
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        val secs = total % 60

        return if (hours > 0) "%d:%02d:%02d".format(Locale.ROOT, hours, minutes, secs) else "%d:%02d".format(Locale.ROOT, minutes, secs)
    }

    /** `1×`, `1,5×`, `2×` (comma or point by language). */
    fun speed(speed: PlaybackSpeed, locale: Locale = Locale.getDefault()): String {
        val rate = speed.rate
        val text = if (rate == rate.toLong().toDouble()) rate.toLong().toString() else java.text.NumberFormat.getInstance(locale).also {
            it.maximumFractionDigits = 1
            it.minimumFractionDigits = 0
        }.format(rate)

        return "$text×"
    }

    /** `2026-10` → `oktober 2026`; anything else comes back as it was. */
    fun month(key: String, locale: Locale = Locale.getDefault()): String {
        val parts = key.split("-")
        val year = parts.getOrNull(0)?.toIntOrNull()
        val month = parts.getOrNull(1)?.toIntOrNull()

        if (parts.size != 2 || year == null || month == null || month !in 1..12) return key

        return "${LocalDate.of(year, month, 1).month.getDisplayName(TextStyle.FULL_STANDALONE, locale)} $year"
    }

    /** `0:42`, `5:12`, `1:02:03`. */
    fun duration(seconds: Long): String = clock(seconds.toDouble())
}
