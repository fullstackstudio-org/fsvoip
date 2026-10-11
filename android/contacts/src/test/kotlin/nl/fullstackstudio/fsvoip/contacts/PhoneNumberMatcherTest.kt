// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.contacts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneNumberMatcherTest {
    private fun exact(number: String): String? = PhoneNumberMatcher.key(number)?.exact

    @Test
    fun allWrittenFormsOfOneDutchMobileNumberAreTheSame() {
        val forms = listOf(
            "0612345678", "06 12 34 56 78", "06-12345678", "+31612345678", "+31 6 12345678", "0031612345678", "0031 (0)6 12345678",
            "31612345678", "+31 (0)6 12345678", "sip:0612345678@voorbeeld.powervoip.nl", "tel:+31612345678", "+310612345678",
        )

        for (form in forms) assertEquals(form, "+31612345678", exact(form))
    }

    @Test
    fun landlineWithAreaCode() {
        assertEquals("+31701234567", exact("070 123 45 67"))
        assertEquals("+31701234567", exact("+31 70 123 4567"))
        assertEquals("+31701234567", exact("0031701234567"))
    }

    @Test
    fun foreignNumbersKeepTheirCountryCode() {
        assertEquals("+32471234567", exact("+32 471 23 45 67"))
        assertEquals("+32471234567", exact("0032471234567"))
        assertEquals("+49301234567", exact("+49 30 1234567"))
    }

    @Test
    fun shortNumbersAndCodesStayAsTyped() {
        assertEquals("101", exact("101"))
        assertEquals("112", exact("112"))
        assertEquals("1234", exact("1234"))
        assertEquals("*97", exact("*97"))
        assertEquals("#31#", exact("#31#"))
        assertNull(PhoneNumberMatcher.key("112")?.tail)
        assertNull(PhoneNumberMatcher.key("101")?.tail)
    }

    @Test
    fun nothingToMatch() {
        assertNull(PhoneNumberMatcher.key(""))
        assertNull(PhoneNumberMatcher.key("Anoniem"))
        assertNull(PhoneNumberMatcher.key("+"))
    }

    @Test
    fun tailIsTheLastNineDigits() {
        assertEquals("612345678", PhoneNumberMatcher.key("0612345678")?.tail)
        assertEquals("612345678", PhoneNumberMatcher.key("+31612345678")?.tail)
        assertEquals(true, PhoneNumberMatcher.key("0612345678")?.isCountryAssumed)
        assertEquals(false, PhoneNumberMatcher.key("+31612345678")?.isCountryAssumed)
    }

    @Test
    fun e164ForTheServer() {
        assertEquals("+31612345678", PhoneNumberMatcher.e164("06 12 34 56 78"))
        assertEquals("+32471234567", PhoneNumberMatcher.e164("+32 471 23 45 67"))
        assertNull("an extension is sent as typed", PhoneNumberMatcher.e164("101"))
    }

    @Test
    fun same() {
        assertTrue(PhoneNumberMatcher.same("0612345678", "+31 6 12345678"))
        assertFalse(PhoneNumberMatcher.same("0612345678", "0612345679"))
        assertFalse(PhoneNumberMatcher.same("101", "0101"))
    }
}
