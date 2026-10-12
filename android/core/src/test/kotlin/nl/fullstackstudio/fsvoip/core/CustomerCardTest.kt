// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.core

import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomerCardTest {
    private fun lookup(contact: Contact?, ambiguous: Boolean = false, orders: Int = 0, requests: Int = 0) =
        ContactLookup(contact = contact, ambiguous = ambiguous, counters = CustomerCounters(openRequests = requests, openOrders = orders))

    private val jansen = Contact(id = "c1", name = " Jansen ", company = "Bakkerij", updatedAt = "t")

    @Test
    fun oneKnownContactBecomesTheCallerContext() {
        val context = CallerContext.from(lookup(jansen, orders = 2, requests = 1))!!

        assertEquals("c1", context.contactId)
        assertEquals("Jansen", context.name)
        assertEquals("Bakkerij", context.company)
        assertEquals(2, context.openOrders)
        assertEquals(1, context.openRequests)
    }

    @Test
    fun unknownAmbiguousOrSuppressedShowsNothing() {
        assertNull(CallerContext.from(lookup(null)))
        assertNull(CallerContext.from(lookup(jansen, ambiguous = true)))
    }

    @Test
    fun aContactWithoutANameIsCalledByItsCompany() {
        assertEquals("Bakkerij", CallerContext.from(lookup(Contact(id = "c", name = "", company = "Bakkerij", updatedAt = "t")))!!.displayName)
    }

    @Test
    fun theLineSaysOnlyWhatTheServerCounted() {
        assertNull(CallerLine.of(null))
        assertFalse(CallerLine.of(CallerContext("c", "Jansen"))!!.hasCounts)
        assertTrue(CallerLine.of(CallerContext("c", "Jansen", openOrders = 2))!!.hasCounts)
    }

    @Test
    fun colleaguesAreNotCustomers() {
        assertFalse(CallerContext.looksLikeExternalNumber("100"))
        assertFalse(CallerContext.looksLikeExternalNumber("1234"))
        assertTrue(CallerContext.looksLikeExternalNumber("0612345678"))
        assertTrue(CallerContext.looksLikeExternalNumber("+31 6 1234 5678"))
    }

    @Test
    fun symbolsFollowTheKind() {
        assertEquals(TimelineSymbol.ORDER, TimelineSymbol.of("shop.order"))
        assertEquals(TimelineSymbol.REQUEST, TimelineSymbol.of("Ticket"))
        assertEquals(TimelineSymbol.CALL, TimelineSymbol.of("voice_call"))
        assertEquals(TimelineSymbol.OTHER, TimelineSymbol.of("something_new"))
    }

    @Test
    fun theSubtitleLeavesOutWhatIsMissingOrUnreadable() {
        val item = TimelineItem(id = "1", kind = "order", title = "x", ref = "#1042", occurredAt = "2026-10-07T09:30:12.345Z")

        assertEquals("#1042 · datum", timelineSubtitle(item) { "datum" })
        assertEquals("#1042", timelineSubtitle(item.copy(occurredAt = "geen tijd")) { "datum" })
        assertEquals("", timelineSubtitle(TimelineItem(id = "2"), { "datum" }))
    }

    private class FakeService(val pages: ArrayDeque<Any>) : CustomerCardServicing {
        val cursors = mutableListOf<String?>()

        override suspend fun lookup(number: String, account: StoredAccount): ContactLookup = error("not used")

        override suspend fun timeline(contactId: String, cursor: String?, account: StoredAccount): ContactTimelinePage {
            cursors += cursor

            return when (val next = pages.removeFirst()) {
                is Exception -> throw next
                is ContactTimelinePage -> next
                else -> error("script")
            }
        }
    }

    private fun item(id: String) = TimelineItem(id = id, title = "Regel $id")

    @Test
    fun theTimelinePagesThroughTheCursorAndShowsAnIdOnce() = runBlocking {
        val service = FakeService(ArrayDeque(listOf(ContactTimelinePage(listOf(item("1"), item("2")), "c1"), ContactTimelinePage(listOf(item("2"), item("3")), null))))
        val model = TimelineModel(service, account(), "contact")

        model.loadFirstPage()
        // Loaded once: opening the screen again asks nothing.
        model.loadFirstPage()

        assertEquals(listOf("1", "2"), model.state.value.items.map { it.id })
        assertTrue(model.state.value.canLoadMore)

        model.loadMore()

        assertEquals(listOf("1", "2", "3"), model.state.value.items.map { it.id })
        assertFalse(model.state.value.canLoadMore)
        assertEquals(listOf<String?>(null, "c1"), service.cursors)
    }

    @Test
    fun aFailureKeepsWhatIsOnScreenAndRetryContinues() = runBlocking {
        val service = FakeService(ArrayDeque(listOf(ContactTimelinePage(listOf(item("1")), "c1"), ApiException.Transport("x"), ContactTimelinePage(listOf(item("2")), null))))
        val model = TimelineModel(service, account(), "contact")

        model.loadFirstPage()
        model.loadMore()

        assertEquals(TimelineModel.Phase.FAILED, model.state.value.phase)
        assertEquals(listOf("1"), model.state.value.items.map { it.id })

        model.retry()

        assertEquals(TimelineModel.Phase.IDLE, model.state.value.phase)
        assertEquals(listOf("1", "2"), model.state.value.items.map { it.id })
    }

    private fun account(): StoredAccount = FsJson.default.decodeFromString(PairResponse.serializer(), java.io.File(System.getProperty("fsvoip.fixtures") ?: "../../shared/fixtures", "pair-response.json").readText())
        .let { StoredAccount.fromPairing(it, Instant.EPOCH) }
}
