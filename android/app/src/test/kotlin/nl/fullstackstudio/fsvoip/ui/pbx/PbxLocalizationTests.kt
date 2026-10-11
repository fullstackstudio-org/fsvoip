// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.ui.pbx

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Dutch and English strings of the "Centrale" screens: same keys, same placeholders, no telecom jargon. */
class PbxLocalizationTests {
    private fun load(dir: String): Map<String, String> {
        val result = mutableMapOf<String, String>()

        for (name in listOf("strings_pbx.xml", "strings_pbx_ui.xml")) {
            val file = File("src/main/res/$dir/$name")
            Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL).findAll(file.readText()).forEach { result[it.groupValues[1]] = it.groupValues[2] }
        }

        return result
    }

    private val nl = load("values")
    private val en = load("values-en")

    private fun placeholders(text: String) = Regex("""%(\d+\$)?[sd]""").findAll(text).map { it.value.replace(Regex("""^%\d+\$"""), "%") }.toList().sorted()

    @Test fun bothLanguagesHaveTheSameKeys() {
        assertEquals(nl.keys - en.keys, emptySet<String>())
        assertEquals(en.keys - nl.keys, emptySet<String>())
        assertTrue(nl.size > 300)
    }

    @Test fun placeholdersMatchBetweenLanguages() {
        for ((key, value) in nl) assertEquals("placeholders of $key", placeholders(value), placeholders(en.getValue(key)))
    }

    @Test fun theDutchTextsUseNoTelecomJargon() {
        val jargon = listOf("dialplan", "gateway", "trunk", "sofia", "freeswitch", "ivr", "extensie ", "pbx", "sip ")

        for ((key, value) in nl) {
            for (word in jargon) assertTrue("$key contains '$word': $value", !value.lowercase().contains(word))
        }
    }

    @Test fun everyKeyIsUsedByTheAppPrefix() {
        assertTrue(nl.keys.all { it.startsWith("adm_") })
    }
}
