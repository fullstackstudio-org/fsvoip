// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoggingTest {
    @Test
    fun redactsTokensAndSecrets() {
        val cases = listOf(
            "token fss_vapp_abcdefghijkl" to "fss_vapp_abcdefghijkl",
            "fss_vpair_FIXTUREpairingFIXTURE" to "FIXTUREpairing",
            "Authorization: Bearer fss_vapp_secret123" to "secret123",
            "authorization: Digest username=\"102\", response=\"abc\"" to "response",
            "{\"password\":\"hunter2\"}" to "hunter2",
            "password=hunter2&x=1" to "hunter2",
            "pushToken: abcdef1234" to "abcdef1234",
            "a".repeat(64) to "a".repeat(64),
            "fcm cXyZ12345678:APA91bFIXTUREfixtureFIXTUREfixture" to "APA91bFIXTURE",
        )

        for ((input, secret) in cases) {
            val redacted = LogRedactor.redact(input)
            assertFalse("leaked in: $redacted", redacted.contains(secret))
        }
    }

    @Test
    fun loggerRedactsAndFilters() {
        val sink = MemoryLogSink()
        val logger = FsLogger("test", sink, LogLevel.INFO)
        logger.debug("hidden")
        logger.notice("Bearer fss_vapp_abcdefgh")
        assertEquals(1, sink.messages.size)
        assertTrue(sink.messages.single().contains("[redacted]"))
    }

    @Test
    fun ordinaryTextSurvives() {
        assertEquals("Account 3f0c2b1e registered", LogRedactor.redact("Account 3f0c2b1e registered"))
    }
}
