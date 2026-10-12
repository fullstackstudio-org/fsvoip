// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test

class PairingLinkTest {
    private inline fun <reified T : Throwable> rejects(text: String) {
        try {
            PairingLinkParser.parseScanned(text)
            fail("accepted: $text")
        } catch (error: Throwable) {
            if (error !is T) throw AssertionError("$text: expected ${T::class.simpleName}, got $error")
        }
    }

    @Test
    fun acceptsOurLinks() {
        for (text in listOf(
            "https://fullstackstudio.nl/fsvoip/pair?t=$GOOD_TOKEN",
            "https://www.fullstackstudio.nl/fsvoip/pair/?t=$GOOD_TOKEN",
            "HTTPS://FullStackStudio.nl/fsvoip/pair?x=1&t=$GOOD_TOKEN",
            "fsvoip://pair?t=$GOOD_TOKEN",
            "fsvoip:///pair?t=$GOOD_TOKEN",
            "  fsvoip://pair?t=$GOOD_TOKEN\n",
        )) {
            assertEquals(text, GOOD_TOKEN, PairingLinkParser.parseScanned(text).token)
        }
    }

    @Test
    fun rejectsOthers() {
        rejects<PairingLinkException.NotAPairingLink>("http://fullstackstudio.nl/fsvoip/pair?t=$GOOD_TOKEN")
        rejects<PairingLinkException.NotAPairingLink>("https://evil.example/fsvoip/pair?t=$GOOD_TOKEN")
        rejects<PairingLinkException.NotAPairingLink>("https://fullstackstudio.nl.evil.example/fsvoip/pair?t=$GOOD_TOKEN")
        rejects<PairingLinkException.NotAPairingLink>("https://fullstackstudio.nl/other?t=$GOOD_TOKEN")
        rejects<PairingLinkException.NotAPairingLink>("fsvoip://call?t=$GOOD_TOKEN")
        rejects<PairingLinkException.NotAPairingLink>("just text")
        rejects<PairingLinkException.NotAPairingLink>("")
        rejects<PairingLinkException.MissingToken>("fsvoip://pair")
        rejects<PairingLinkException.MissingToken>("fsvoip://pair?t=")
        rejects<PairingLinkException.MalformedToken>("fsvoip://pair?t=fss_vpair_TEST")
        rejects<PairingLinkException.MalformedToken>("fsvoip://pair?t=fss_vapp_FIXTUREpairingFIXTUREpairingFIXTUREpairingFX")
    }

    @Test
    fun tokenNeverPrints() {
        assertFalse(PairingLink(GOOD_TOKEN).toString().contains("FIXTURE"))
    }
}
