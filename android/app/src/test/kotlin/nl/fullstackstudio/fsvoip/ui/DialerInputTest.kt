// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.ui

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DialerInputTest {
    @Test
    fun typing() {
        var input = DialerInput()
        input = input.press('+').press('3').press('1').press('x').press('+')
        assertEquals("+31", input.number)
        assertTrue(input.canCall)
        input = input.deleteLast().deleteLast().deleteLast()
        assertTrue(input.isEmpty)
        assertFalse(input.canCall)
    }

    @Test
    fun longPressZeroAndPaste() {
        assertEquals("+", DialerInput().longPressZero().number)
        assertEquals("10", DialerInput("1").longPressZero().number)
        assertEquals("+31701234567", DialerInput().paste("+31 (0)70-123 45 67").number)
        assertEquals(32, (1..40).fold(DialerInput()) { input, _ -> input.press('1') }.number.length)
    }

    @Test
    fun recentFormatting() {
        assertEquals("0:42", RecentFormat.duration(42))
        assertEquals("5:12", RecentFormat.duration(312))
        assertEquals("1:02:03", RecentFormat.duration(3723))

        val zone = ZoneId.of("Europe/Amsterdam")
        val today = LocalDate.of(2026, 10, 8)
        assertEquals("Gisteren", RecentFormat.`when`(Instant.parse("2026-10-07T10:00:00Z"), "Gisteren", zone, today))
    }
}
