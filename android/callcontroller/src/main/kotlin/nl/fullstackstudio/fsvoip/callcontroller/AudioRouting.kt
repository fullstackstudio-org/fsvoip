// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.callcontroller

/** Speaker on/off. On Android the active Telecom connection owns the route (`TelecomCallSystem`). */
interface AudioRouting {
    fun setSpeaker(on: Boolean)

    /** Whether the speaker is the current output (it can change underneath us, e.g. Bluetooth connects). */
    val isSpeakerActive: Boolean
}

/** Remembers the choice only (tests, previews). */
class MemoryAudioRouting : AudioRouting {
    override var isSpeakerActive: Boolean = false
        private set

    override fun setSpeaker(on: Boolean) {
        isSpeakerActive = on
    }
}
