// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.ui.media

/** Demo mode only: which situation the pretend API plays, and which detail screen opens by itself (see `DemoMode.openScreen`). */
object MediaDemoFlags {
    @Volatile var failCalls = false
    @Volatile var failVoicemail = false
    @Volatile var emptyCalls = false
    @Volatile var emptyVoicemail = false
    @Volatile var voicemailUnavailable = false

    /** `callrecording`, `callrecording-gone`, `voicemailplay`, `recordingplay`: opened and played once, then cleared. */
    @Volatile var auto: String? = null

    /** Takes [name] when it is the pending automatic action (so it runs once). */
    fun take(name: String): Boolean {
        if (auto != name) return false

        auto = null

        return true
    }
}
