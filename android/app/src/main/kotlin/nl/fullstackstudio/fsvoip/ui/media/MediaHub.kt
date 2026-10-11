// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.ui.media

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import nl.fullstackstudio.fsvoip.core.AudioBackend
import nl.fullstackstudio.fsvoip.core.AudioStreamer
import nl.fullstackstudio.fsvoip.core.MediaCache
import nl.fullstackstudio.fsvoip.core.MediaServicing
import nl.fullstackstudio.fsvoip.core.MeResponse
import nl.fullstackstudio.fsvoip.core.VoicemailAccess

/**
 * Who may open "Voicemail" and "Opnames" and see the recordings of the history, per paired account, and the one player they share.
 *
 * Like the "Centrale" section, the rights come from the app's `GET /me` ([apply]) and are never stored on the phone: after a
 * restart the parts stay hidden until the server has said so again (fail closed). A 403 from the server hides the part at once. The
 * server enforces every route; this keeps the screens honest.
 */
class MediaHub(
    val service: MediaServicing,
    var gate: MediaAccessGate,
    backend: AudioBackend,
    private val cache: MediaCache,
    scope: CoroutineScope,
    /** A phone call (or ringing call) is going on: the sound pauses and does not start. */
    private val callActive: () -> Boolean = { false },
    io: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.IO,
) {
    data class Access(
        /** [VoicemailAccess.OWN]: only the own box. [VoicemailAccess.ALL]: every box (an admin). [VoicemailAccess.NONE]: no section. */
        val voicemail: VoicemailAccess,
        val recordings: Boolean,
        /** The phone system does not accept changes now (frozen, being set up): no deleting. */
        val readOnly: Boolean,
    ) {
        val hasVoicemail: Boolean get() = voicemail == VoicemailAccess.OWN || voicemail == VoicemailAccess.ALL
        val hasAnything: Boolean get() = hasVoicemail || recordings
    }

    private val _access = MutableStateFlow<Map<String, Access>>(emptyMap())
    private val _lostAccessFor = MutableStateFlow<String?>(null)

    val access: StateFlow<Map<String, Access>> = _access.asStateFlow()

    /** Set when a part was taken away while it was open, so the app can say so. */
    val lostAccessFor: StateFlow<String?> = _lostAccessFor.asStateFlow()

    val player: AudioStreamer = AudioStreamer(backend, cache, scope, isCallActive = callActive, streamFirst = false, io = io)

    /** Asks the app to re-read its accounts (a 401). */
    var onRevoked: (() -> Unit)? = null

    /** Name of a phone number from the customer's contacts and colleagues (for the rows). */
    var nameLookup: (String) -> String? = { null }

    // MARK: Access

    fun access(accountId: String): Access? = _access.value[accountId]

    fun hasVoicemail(accountId: String): Boolean = access(accountId)?.hasVoicemail == true

    fun hasRecordings(accountId: String): Boolean = access(accountId)?.recordings == true

    fun isAvailable(accountId: String): Boolean = access(accountId)?.hasAnything == true

    fun acknowledgeLostAccess() {
        _lostAccessFor.value = null
    }

    /** The answer of `GET /me`. */
    fun apply(me: MeResponse, accountId: String) {
        // Without `capabilities` (an older server) nothing is offered: fail closed.
        val capabilities = me.capabilities

        if (capabilities == null) {
            set(null, accountId)

            return
        }

        val isAdmin = me.effectiveRole == nl.fullstackstudio.fsvoip.core.AppRole.ADMIN
        val voicemail = when {
            capabilities.voicemail == VoicemailAccess.ALL && isAdmin -> VoicemailAccess.ALL
            capabilities.voicemail == VoicemailAccess.ALL || capabilities.voicemail == VoicemailAccess.OWN -> VoicemailAccess.OWN
            else -> VoicemailAccess.NONE
        }
        val value = Access(voicemail, recordings = isAdmin && capabilities.recordings, readOnly = me.pbx?.readOnly ?: false)

        set(value.takeIf { it.hasAnything }, accountId)
    }

    /** `GET /me` said 403: this pairing has no rights any more. */
    fun accessDenied(accountId: String) {
        set(null, accountId)
    }

    /** One part got a 403 (the role changed in between): take just that part away. */
    fun partDenied(part: MediaPart, accountId: String) {
        val value = access(accountId) ?: return
        val reduced = when (part) {
            MediaPart.VOICEMAIL -> value.copy(voicemail = VoicemailAccess.NONE)
            MediaPart.RECORDINGS -> value.copy(recordings = false)
        }

        set(reduced.takeIf { it.hasAnything }, accountId)
    }

    private fun set(value: Access?, accountId: String) {
        val previous = access(accountId)

        if (previous == value) return

        _access.value = if (value == null) _access.value - accountId else _access.value + (accountId to value)

        if (previous == null) return

        val lostVoicemail = previous.hasVoicemail && value?.hasVoicemail != true
        val lostRecordings = previous.recordings && value?.recordings != true

        // What was taken away closes: stop the sound, lock again and say so.
        if (lostVoicemail || lostRecordings) {
            _lostAccessFor.value = accountId
            player.stop()
            gate.lock()
        }
    }

    // MARK: Calls

    /** The phone has a call (or ringing call): the sound pauses and does not start. */
    fun callStateChanged() {
        if (callActive()) player.callActivityChanged()
    }

    /** The account is gone from this phone. */
    fun forget(accountId: String) {
        _access.value = _access.value - accountId
        player.stop()
        cache.clear()
    }
}
