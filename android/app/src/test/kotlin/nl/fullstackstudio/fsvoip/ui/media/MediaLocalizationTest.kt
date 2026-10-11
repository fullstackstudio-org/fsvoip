// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.ui.media

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Dutch and English strings of the calls slice have the same keys and the same placeholders, and no telecom jargon. */
class MediaLocalizationTest {
    private fun strings(folder: String): Map<String, String> {
        val file = listOf("src/main/res/$folder/strings_calls.xml", "app/src/main/res/$folder/strings_calls.xml").map(::File).first { it.isFile }
        val regex = Regex("""<string name="([^"]+)">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)

        return regex.findAll(file.readText()).associate { it.groupValues[1] to it.groupValues[2] }
    }

    private fun placeholders(text: String): List<String> = Regex("""%(?:\d+\$)?[sd]""").findAll(text).map { it.value }.sorted().toList()

    @Test
    fun bothLanguagesHaveTheSameKeysAndPlaceholders() {
        val nl = strings("values")
        val en = strings("values-en")

        assertTrue(nl.size > 100)
        assertEquals(nl.keys, en.keys)

        for ((key, text) in nl) {
            assertEquals(key, placeholders(text), placeholders(en.getValue(key)))
            assertFalse(key, text.isEmpty())
            assertFalse(key, en.getValue(key).isEmpty())
        }
    }

    @Test
    fun multiplePlaceholdersAreNumbered() {
        for ((key, text) in strings("values") + strings("values-en")) {
            if (placeholders(text).size > 1) assertTrue("$key: $text", text.contains("%1$"))
        }
    }

    @Test
    fun noTelecomJargon() {
        for ((key, text) in strings("values")) {
            for (word in listOf("extensie", "ivr", "trunk", "gateway", "sip", "dialplan", "pbx")) {
                assertNull("$key: $text", Regex("\\b$word\\b", RegexOption.IGNORE_CASE).find(text))
            }
        }
    }
}
