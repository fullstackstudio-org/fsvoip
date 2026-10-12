// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PbxVocabularyTest {
    private val state = FsJson.default.decodeFromString(SelfExtension.serializer(), Fixtures.text("self-extension.json"))

    @Test
    fun numbersAreGroupedLikeTheIosApp() {
        assertEquals("085 060 7848", PbxVocabulary.formatNumber("0850607848"))
        assertEquals("102", PbxVocabulary.formatNumber("102"))
        assertEquals("+31850607848", PbxVocabulary.formatNumber("+31850607848"))
        assertEquals("Hoofdnummer · 085 060 7848", PbxVocabulary.numberTitle(state.numbers[0]))
        assertEquals("085 060 7849", PbxVocabulary.numberTitle(state.numbers[1]))
    }

    @Test
    fun destinationsAreShownByName() {
        val options = state.targets
        val none = "Niet ingesteld"
        val gone = "Bestaat niet meer"
        val reception = PbxTarget.obj(TargetType.DEVICE, "5c0a8e1f-2d7b-4a39-8f46-0b1c2d3e4f50")

        assertEquals("Receptie · 100", PbxVocabulary.targetLabel(reception, options, none, gone))
        assertEquals("Iedereen · 200", PbxVocabulary.targetLabel(PbxTarget.obj(TargetType.RING_GROUP, "9e8d7c6b-5a49-4382-b1a0-f9e8d7c6b5a4"), options, none, gone))
        assertEquals("+31612345678", PbxVocabulary.targetLabel(PbxTarget.external("+31612345678"), options, none, gone))
        assertEquals(none, PbxVocabulary.targetLabel(null, options, none, gone))
        assertEquals(gone, PbxVocabulary.targetLabel(PbxTarget.obj(TargetType.DEVICE, "weg"), options, none, gone))
    }

    @Test
    fun anOptionBecomesATarget() {
        val reception = state.targets[0]
        assertEquals(PbxTarget.obj(TargetType.DEVICE, reception.id!!), PbxVocabulary.targetFor(reception))

        val external = state.targets.last()
        assertEquals(PbxTarget.external("+31612345678"), PbxVocabulary.targetFor(external, "+31 6 1234 5678"))
        assertNull(PbxVocabulary.targetFor(external, "abc"))
        assertNull(PbxVocabulary.targetFor(external, null))
    }

    @Test
    fun externalNumbersAreCleanedAndChecked() {
        assertEquals("0612345678", PbxVocabulary.normalizeExternalNumber("06-12 34 56 78"))
        assertEquals("+31612345678", PbxVocabulary.normalizeExternalNumber(" +31 (0)6 12345678".replace("(0)", "")))
        assertNull(PbxVocabulary.normalizeExternalNumber("12345"))
        assertNull(PbxVocabulary.normalizeExternalNumber("+31+6123456"))
        assertNull(PbxVocabulary.normalizeExternalNumber("1".repeat(16)))
    }
}
