// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.ui

import kotlinx.coroutines.runBlocking
import nl.fullstackstudio.fsvoip.R
import nl.fullstackstudio.fsvoip.AppModel
import nl.fullstackstudio.fsvoip.core.ApiException
import nl.fullstackstudio.fsvoip.core.AppCapabilities
import nl.fullstackstudio.fsvoip.core.CallerContext
import nl.fullstackstudio.fsvoip.core.CustomerCardServicing
import nl.fullstackstudio.fsvoip.core.CallerLookup
import nl.fullstackstudio.fsvoip.core.InMemoryKeyValueStore
import nl.fullstackstudio.fsvoip.core.KeyValuePreferencesStore
import nl.fullstackstudio.fsvoip.core.NoticeDestination
import nl.fullstackstudio.fsvoip.core.OutboundNumber
import nl.fullstackstudio.fsvoip.core.OutboundNumbers
import nl.fullstackstudio.fsvoip.core.OutboundNumbersServicing
import nl.fullstackstudio.fsvoip.core.Secret
import nl.fullstackstudio.fsvoip.core.ServerTransport
import nl.fullstackstudio.fsvoip.core.SipCredentials
import nl.fullstackstudio.fsvoip.core.StoredAccount
import nl.fullstackstudio.fsvoip.core.TimelineItem
import nl.fullstackstudio.fsvoip.core.TimelinePage
import nl.fullstackstudio.fsvoip.sipengine.CallOptions
import nl.fullstackstudio.fsvoip.tab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private fun account(id: String = "a") = StoredAccount(
    id = id, label = "Jan", pbxName = "Voorbeeld Bouw", extensionName = "Jan de Vries", extensionNumber = "102", customerName = "Voorbeeld",
    deviceToken = Secret("fss_vapp_x"), installId = "a1b2c3d4e5f60718",
    sip = SipCredentials("102", Secret("pw"), "x.powervoip.nl", "sip.powervoip.nl", 5061, ServerTransport.TLS, true), pairedAtMillis = 1_790_000_000_000,
)

private class FakeNumbers : OutboundNumbersServicing {
    var requests = 0
    var result = OutboundNumbers(listOf(OutboundNumber("1", "0850607848", "Hoofdnummer", true), OutboundNumber("2", "0850607849")), "0850607848")

    override suspend fun numbers(account: StoredAccount): OutboundNumbers {
        requests++

        return result
    }
}

class OutboundChoiceModelTest {
    private lateinit var service: FakeNumbers
    private lateinit var preferences: KeyValuePreferencesStore
    private lateinit var outbound: OutboundChoiceModel
    private var callerChoice = true

    @Before
    fun setUp() {
        service = FakeNumbers()
        preferences = KeyValuePreferencesStore(InMemoryKeyValueStore())
        callerChoice = true
        outbound = OutboundChoiceModel(service, preferences) { AppCapabilities(callerChoice = callerChoice) }
    }

    @Test
    fun theChoiceBecomesTheFromNumberOfTheNextCall() = runBlocking {
        outbound.load(account())

        assertEquals("nothing chosen yet: the extension's own default number", CallOptions.NONE, outbound.options("a"))

        outbound.select("0850607849", "a")

        assertEquals(CallOptions(fromNumber = "0850607849"), outbound.options("a"))
        assertEquals("0850607849", outbound.options("a").headers.single().value)
        assertEquals("the choice is kept per account", "0850607849", preferences.preferences("a").outboundNumber)
    }

    @Test
    fun theChoiceSurvivesARestart() = runBlocking {
        outbound.load(account())
        outbound.select("0850607849", "a")

        // A new model on the same stored preferences: "kill the app, reopen".
        val reopened = OutboundChoiceModel(service, preferences) { AppCapabilities(callerChoice = true) }
        reopened.load(account())

        assertEquals("0850607849", reopened.selected("a")?.number)
    }

    @Test
    fun theDefaultNumberIsShownUntilSomethingIsChosen() = runBlocking {
        outbound.load(account())

        assertEquals("0850607848", outbound.selected("a")?.number)
        assertEquals("Hoofdnummer · 085 060 7848", outbound.label("a", "Standaardnummer"))
        assertTrue(outbound.canChoose("a"))

        outbound.select("0850607849", "a")
        assertEquals("a number without a name is just the number", "085 060 7849", outbound.label("a", "Standaardnummer"))
    }

    @Test
    fun switchingNumbersNeverTouchesTheNetwork() = runBlocking {
        outbound.load(account())
        val before = service.requests

        outbound.select("0850607849", "a")
        outbound.select("0850607848", "a")
        outbound.select("0850607849", "a")

        assertEquals(before, service.requests)
    }

    @Test
    fun withoutTheCapabilityThereIsNoChooserAndNoHeader() = runBlocking {
        callerChoice = false
        preferences.setPreferences(preferences.preferences("a").copy(outboundNumber = "0850607849"), "a")

        outbound.load(account())

        assertEquals("no capability, no request", 0, service.requests)
        assertFalse(outbound.canChoose("a"))
        assertFalse(outbound.offersChoice("a"))
        assertEquals("Standaardnummer", outbound.label("a", "Standaardnummer"))
        assertEquals("even an old stored choice sends nothing", CallOptions.NONE, outbound.options("a"))
        assertTrue(outbound.options("a").headers.isEmpty())
    }

    @Test
    fun anUnknownCapabilityIsTreatedAsNo() = runBlocking {
        outbound.capabilities = { null }

        outbound.load(account())

        assertFalse(outbound.canChoose("a"))
        assertEquals(CallOptions.NONE, outbound.options("a"))
    }

    @Test
    fun oneNumberIsALabelOnly() = runBlocking {
        service.result = OutboundNumbers(listOf(OutboundNumber("1", "0850607848", "Hoofdnummer", true)), "0850607848")

        outbound.load(account())

        assertFalse("nothing to choose from: no chevron", outbound.canChoose("a"))
        assertEquals("Hoofdnummer · 085 060 7848", outbound.label("a", "Standaardnummer"))
    }

    @Test
    fun aNumberThePbxNoLongerListsFallsBackToTheDefault() = runBlocking {
        outbound.load(account())
        outbound.select("0850607849", "a")

        service.result = OutboundNumbers(listOf(OutboundNumber("1", "0850607848", null, true), OutboundNumber("3", "0201234567")), "0850607848")
        outbound.load(account())

        assertEquals(CallOptions.NONE, outbound.options("a"))
        assertEquals("0850607848", outbound.selected("a")?.number)
    }

    @Test
    fun aChoiceIsOnlyAcceptedFromTheListedNumbers() = runBlocking {
        outbound.load(account())

        outbound.select("0612345678", "a")

        assertNull(preferences.preferences("a").outboundNumber)
        assertEquals(CallOptions.NONE, outbound.options("a"))
    }

    @Test
    fun theChoiceIsPerAccount() = runBlocking {
        outbound.load(account("a"))
        outbound.load(account("b"))
        outbound.select("0850607849", "a")

        assertEquals("0850607849", outbound.options("a").fromNumber)
        assertNull(outbound.options("b").fromNumber)
    }

    @Test
    fun aFailedLoadKeepsWhatIsKnown() = runBlocking {
        outbound.load(account())
        val failing = object : OutboundNumbersServicing {
            override suspend fun numbers(account: StoredAccount): OutboundNumbers = throw ApiException.Transport("down")
        }
        val flaky = OutboundChoiceModel(failing, preferences) { AppCapabilities(callerChoice = true) }
        flaky.load(account())

        assertFalse(flaky.canChoose("a"))
        assertTrue(outbound.canChoose("a"))
    }
}

/** The pretend resources: the Dutch texts of the call card. */
private fun dutch(id: Int, count: Int?, text: String?): String = when (id) {
    R.string.callcard_prefix -> "Klant in website: $text"
    R.string.callcard_known -> "Klant in website"
    R.string.callcard_orders_one -> "$count open bestelling"
    R.string.callcard_orders_other -> "$count open bestellingen"
    R.string.callcard_requests_one -> "$count open verzoek"
    R.string.callcard_requests_other -> "$count open verzoeken"
    else -> error("unexpected string $id")
}

class CallerLineTest {
    private fun context(orders: Int, requests: Int) = CallerContext("c", "Jansen", openRequests = requests, openOrders = orders)

    @Test
    fun theLineShowsOnlyWhatTheServerCounted() {
        assertEquals("Klant in website: 2 open bestellingen, 1 open verzoek", CallerLine.text(context(2, 1), ::dutch))
        assertEquals("Klant in website: 1 open bestelling", CallerLine.text(context(1, 0), ::dutch))
        assertEquals("Klant in website: 3 open verzoeken", CallerLine.text(context(0, 3), ::dutch))
    }

    @Test
    fun aKnownContactWithNothingOpenStillSaysSo() {
        assertEquals("Klant in website", CallerLine.text(context(0, 0), ::dutch))
    }

    @Test
    fun noContextNoLine() {
        assertNull(CallerLine.text(null, ::dutch))
    }
}

class TimelineModelTest {
    private val account = account()

    private fun item(id: String) = TimelineItem(id, "order", "Bestelling $id", occurredAt = "2026-10-09T14:02:11.000Z")

    private class FakeCards(val pages: MutableList<() -> TimelinePage>) : CustomerCardServicing {
        val cursors = mutableListOf<String?>()

        override suspend fun lookup(number: String, account: StoredAccount) = CallerLookup(contact = null)

        override suspend fun timeline(contactId: String, cursor: String?, limit: Int, account: StoredAccount): TimelinePage {
            cursors += cursor

            return if (pages.isEmpty()) throw ApiException.Transport("none") else pages.removeAt(0)()
        }
    }

    @Test
    fun pagesAreAppendedWithoutDuplicatesAndTheCursorIsFollowed() = runBlocking {
        val service = FakeCards(mutableListOf({ TimelinePage(listOf(item("1"), item("2")), "c1") }, { TimelinePage(listOf(item("2"), item("3")), null) }))
        val model = TimelineModel(service, account, "c")

        model.loadFirstPage()
        assertEquals(listOf("1", "2"), model.items.value.map { it.id })
        assertTrue(model.canLoadMore)

        model.loadMore()
        assertEquals("an overlapping line is shown once", listOf("1", "2", "3"), model.items.value.map { it.id })
        assertFalse(model.canLoadMore)
        assertEquals(listOf(null, "c1"), service.cursors)
    }

    @Test
    fun theFirstPageLoadsOnlyOnce() = runBlocking {
        val service = FakeCards(mutableListOf({ TimelinePage(listOf(item("1")), null) }))
        val model = TimelineModel(service, account, "c")

        model.loadFirstPage()
        model.loadFirstPage()

        assertEquals(1, service.cursors.size)
    }

    @Test
    fun aFailureKeepsWhatIsOnScreenAndCanBeRetried() = runBlocking {
        val service = FakeCards(mutableListOf({ TimelinePage(listOf(item("1")), "c1") }, { throw ApiException.Unavailable(true, null) }, { TimelinePage(listOf(item("2")), null) }))
        val model = TimelineModel(service, account, "c")

        model.loadFirstPage()
        model.loadMore()
        assertEquals(TimelineModel.Phase.FAILED, model.phase.value)
        assertEquals(listOf("1"), model.items.value.map { it.id })

        model.loadMore()
        assertEquals(TimelineModel.Phase.IDLE, model.phase.value)
        assertEquals(listOf("1", "2"), model.items.value.map { it.id })
    }

    @Test
    fun aFailedFirstLoadCanBeRetried() = runBlocking {
        val service = FakeCards(mutableListOf({ throw ApiException.Transport("offline") }, { TimelinePage(listOf(item("1")), null) }))
        val model = TimelineModel(service, account, "c")

        model.loadFirstPage()
        assertEquals(TimelineModel.Phase.FAILED, model.phase.value)
        assertFalse(model.hasLoaded.value)

        model.loadFirstPage()
        assertEquals(listOf("1"), model.items.value.map { it.id })
        assertTrue(model.hasLoaded.value)
    }

    @Test
    fun iconsPerKindAndASafeFallback() {
        assertEquals(TimelineKind.ORDER, TimelineKind.of("order"))
        assertEquals(TimelineKind.REQUEST, TimelineKind.of("TICKET"))
        assertEquals(TimelineKind.OTHER, TimelineKind.of("something_new"))
    }

    @Test
    fun theSubtitleJoinsTheReferenceAndTheDate() {
        val zone = java.time.ZoneId.of("Europe/Amsterdam")
        val withRef = TimelineItem("1", "order", "t", occurredAt = "not a date", ref = "W-1042")
        assertEquals("W-1042", TimelineFormat.subtitle(withRef, zone))

        val bare = TimelineItem("2", "order", "t", occurredAt = "not a date")
        assertEquals("", TimelineFormat.subtitle(bare, zone))

        val dated = TimelineItem("3", "order", "t", occurredAt = "2026-10-09T14:02:11.000Z", ref = "W-1")
        assertTrue(TimelineFormat.subtitle(dated, zone).startsWith("W-1 · "))
    }
}

class NoticeNavigationTest {
    @Test
    fun theDestinationsOpenTheirTabs() {
        assertEquals(AppModel.Tab.DIALER, NoticeDestination.HOME.tab())
        assertEquals(AppModel.Tab.RECENTS, NoticeDestination.RECENTS.tab())
        assertEquals(AppModel.Tab.VOICEMAIL, NoticeDestination.VOICEMAIL.tab())
        assertEquals(AppModel.Tab.CONTACTS, NoticeDestination.CONTACTS.tab())
    }
}
