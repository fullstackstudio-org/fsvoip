// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.sipengine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TypesTest {
    private fun config(srv: Boolean) = SipAccountConfig(
        id = SipAccountId("a"),
        username = "102",
        password = SipSecret("hunter2"),
        domain = "acme.powervoip.nl",
        proxy = "sip.powervoip.nl",
        port = 5060,
        transport = SipTransport.TCP,
        useSrv = srv,
        installId = "a1b2c3d4e5f60718",
    )

    @Test
    fun identityRouteAndMarker() {
        assertEquals("sip:102@acme.powervoip.nl", config(true).identity)
        assertEquals("sip:sip.powervoip.nl;transport=tcp", config(true).route)
        assertEquals("sip:sip.powervoip.nl:5060;transport=tcp", config(false).route)
        assertEquals("fss-dev=a1b2c3d4e5f60718", config(true).contactMarker)
        assertEquals(120, config(true).expiresSeconds)
    }

    @Test
    fun secretsNeverPrint() {
        assertFalse(SipSecret("hunter2").toString().contains("hunter2"))
        assertFalse(config(true).toString().contains("hunter2"))
        assertEquals("hunter2", SipSecret("hunter2").reveal())
    }

    @Test
    fun dtmfDigits() {
        for (c in "0123456789*#ABCD") assertNotNull(DtmfDigit.of(c))
        assertNull(DtmfDigit.of('x'))
        assertNull(DtmfDigit.of('+'))
    }

    @Test
    fun endedState() {
        assertTrue(CallState.Ended(CallEndReason.Busy).isEnded)
        assertFalse(CallState.Active.isEnded)
    }
}
