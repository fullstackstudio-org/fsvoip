// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PushRingPolicyTest {
    private fun failure(json: String): Throwable? = runCatching { PushMessage.decode(json) }.exceptionOrNull()

    @Test
    fun aBrokenRingOrAnUnreadableEnvelopeMayStillBeACall() {
        assertTrue(PushMessage.mayBeRing(failure("""{"v":1,"type":"ring"}""")))
        assertTrue(PushMessage.mayBeRing(failure("not json")))
        assertTrue(PushMessage.mayBeRing(failure("""{"v":2,"type":"ring"}""")))
        assertTrue(PushMessage.mayBeRing(null))
    }

    @Test
    fun anUnknownTypeOrABrokenNoticeNeverStartsTheCallService() {
        assertFalse(PushMessage.mayBeRing(failure("""{"v":1,"type":"hologram"}""")))
        // A notice without its href.
        assertFalse(PushMessage.mayBeRing(failure("""{"v":1,"type":"notice","accountId":"a","title":"t","body":"b"}""")))
        assertFalse(PushMessage.mayBeRing(failure("""{"v":1,"type":"refresh"}""")))
    }
}
