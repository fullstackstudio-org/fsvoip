// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.ui.media

import java.util.Locale
import kotlinx.coroutines.runBlocking
import nl.fullstackstudio.fsvoip.core.ApiException
import nl.fullstackstudio.fsvoip.core.AudioBackendEvent
import nl.fullstackstudio.fsvoip.core.AudioSource
import nl.fullstackstudio.fsvoip.core.MediaFailure
import nl.fullstackstudio.fsvoip.core.PlaybackSpeed
import nl.fullstackstudio.fsvoip.core.PlayerState
import nl.fullstackstudio.fsvoip.core.RecentCall
import nl.fullstackstudio.fsvoip.core.StoredAccount
import nl.fullstackstudio.fsvoip.core.VoicemailAccess
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MediaModelsTest {
    private lateinit var env: MediaTestEnvironment

    private val service get() = env.service
    private val hub get() = env.hub
    private val backend get() = env.backend
    private val gate get() = env.gate

    @Before
    fun setUp() {
        env = MediaTestEnvironment()
    }

    @After
    fun tearDown() {
        env.close()
    }

    private fun admin(id: String = "admin-1") = hub.apply(MediaFixtures.adminMe, id)

    private fun user(id: String = "user-1") = hub.apply(MediaFixtures.userMe, id)

    private fun voicemailModel(id: String = "admin-1", number: String? = "102") = VoicemailModel(id, number, hub)

    // MARK: Rights from /me

    @Test
    fun anAdminGetsVoicemailAndRecordings() {
        admin()

        assertEquals(MediaHub.Access(VoicemailAccess.ALL, recordings = true, readOnly = false), hub.access("admin-1"))
        assertTrue(hub.hasVoicemail("admin-1"))
        assertTrue(hub.hasRecordings("admin-1"))
    }

    @Test
    fun aUserGetsOnlyTheOwnVoicemailAndNoRecordings() {
        user()

        assertEquals(VoicemailAccess.OWN, hub.access("user-1")?.voicemail)
        assertTrue(hub.hasVoicemail("user-1"))
        assertFalse("recordings are admin only", hub.hasRecordings("user-1"))
    }

    @Test
    fun aUserPairingNeverGetsRecordingsEvenIfTheServerSaidSo() {
        // A (wrong) answer that says recordings for a user: the role wins.
        val me = MediaFixtures.userMe.copy(capabilities = MediaFixtures.userMe.capabilities!!.copy(recordings = true))
        hub.apply(me, "user-1")

        assertFalse(hub.hasRecordings("user-1"))
    }

    @Test
    fun aFrozenPhoneSystemIsReadOnly() {
        hub.apply(MediaFixtures.frozenMe, "a")

        assertEquals(true, hub.access("a")?.readOnly)
    }

    @Test
    fun withoutCapabilitiesNothingIsOffered() {
        hub.apply(MediaFixtures.oldServerMe, "old")

        assertFalse("fail closed: an older server gives no section", hub.isAvailable("old"))
    }

    @Test
    fun aRoleThatNoLongerHasRecordingsKeepsVoicemailAndSaysSo() {
        admin()
        val me = MediaFixtures.adminMe.copy(capabilities = MediaFixtures.adminMe.capabilities!!.copy(recordings = false))
        hub.apply(me, "admin-1")

        assertFalse(hub.hasRecordings("admin-1"))
        assertTrue(hub.hasVoicemail("admin-1"))
        assertEquals("admin-1", hub.lostAccessFor.value)
        assertEquals(1, gate.locks)
    }

    @Test
    fun a403OnTheMeCallHidesEverythingAndLocks() = runBlocking {
        admin()
        gate.ensureUnlocked(GateReason.VOICEMAIL)
        hub.accessDenied("admin-1")

        assertFalse(hub.isAvailable("admin-1"))
        assertFalse(gate.isUnlocked)
    }

    // MARK: Voicemail, user

    private fun onlyOwnBox() {
        service.voicemailPage = service.voicemailPage.let { page ->
            page.copy(boxes = listOf(page.boxes[1]), messages = page.messages.filter { it.boxId == MediaFixtures.OWN_BOX })
        }
    }

    @Test
    fun aUserSeesAllMessagesWithoutAnyCheck() = runBlocking {
        user()
        onlyOwnBox()
        val model = voicemailModel("user-1")
        model.load()

        assertEquals(2, model.messages.size)
        assertFalse(model.canChooseBox)
        assertFalse(model.needsUnlock)
        assertEquals("the own voicemail never asks for the screen lock", 0, gate.evaluations)
        assertEquals(1, service.requestedBoxes.size)
        assertNull("no box: the server decides what a user sees", service.requestedBoxes[0])
    }

    @Test
    fun playingAMessageGivesTheTokenInTheHeadersOnly() = runBlocking {
        user()
        onlyOwnBox()
        val model = voicemailModel("user-1")
        model.load()
        val message = model.messages.first()

        model.play(message)

        assertEquals(message.id, hub.player.currentId.value)
        assertEquals(message.id, model.nowPlaying?.id)
        // The audio is downloaded through the API client and played from the file.
        assertEquals(1, service.count("download"))
        val loaded = backend.loaded.last() as AudioSource.LocalFile
        assertEquals("wav", loaded.file.extension)
    }

    // MARK: Voicemail, admin

    @Test
    fun anAdminStartsOnTheOwnBoxAndNeverSeesOthersWhileLocked() = runBlocking {
        admin()
        val model = voicemailModel()
        model.load()

        assertEquals(MediaFixtures.OWN_BOX, model.ownBoxId)
        assertEquals(VoicemailModel.Scope.Own, model.scope)
        assertEquals(setOf(MediaFixtures.OWN_BOX), model.messages.map { it.boxId }.toSet())
        assertEquals(2, model.messages.size)
        assertEquals(0, gate.evaluations)
        assertTrue(model.canChooseBox)
    }

    @Test
    fun askingForAllOpensAfterTheLocalCheck() = runBlocking {
        admin()
        val model = voicemailModel()
        model.load()

        model.select(VoicemailModel.Scope.All)

        assertEquals(1, gate.evaluations)
        assertEquals(VoicemailModel.Scope.All, model.scope)
        assertEquals(4, model.messages.size)

        model.select(VoicemailModel.Scope.Box(MediaFixtures.COLLEAGUE_BOX))
        assertEquals("inside the five minutes nobody is asked again", 1, gate.evaluations)
        assertEquals(listOf(MediaFixtures.COLLEAGUE_BOX), model.messages.map { it.boxId })
    }

    @Test
    fun aCancelledCheckKeepsTheOwnBox() = runBlocking {
        admin()
        gate.results += GateResult.CANCELLED
        val model = voicemailModel()
        model.load()

        model.select(VoicemailModel.Scope.All)

        assertEquals(VoicemailModel.Scope.Own, model.scope)
        assertEquals(2, model.messages.size)
        assertFalse(model.needsUnlock)
    }

    @Test
    fun aPhoneWithoutScreenLockCannotOpenOthers() = runBlocking {
        admin()
        gate.results += GateResult.UNAVAILABLE
        val model = voicemailModel()
        model.load()

        model.select(VoicemailModel.Scope.All)

        assertEquals(VoicemailModel.Scope.Own, model.scope)
        assertTrue(model.noPasscode)
    }

    @Test
    fun whenTheFiveMinutesAreOverTheScreenFallsBackAndStopsAColleaguesMessage() = runBlocking {
        admin()
        val model = voicemailModel()
        model.load()
        model.select(VoicemailModel.Scope.All)
        val colleague = model.messages.first { it.boxId == MediaFixtures.COLLEAGUE_BOX }
        model.play(colleague)
        assertNotNull(model.nowPlaying)

        gate.expire()
        model.refreshLock()

        assertEquals(VoicemailModel.Scope.Own, model.scope)
        assertEquals(2, model.messages.size)
        assertNull("the colleague's message stopped", hub.player.currentId.value)
        assertNull(model.nowPlaying)
    }

    @Test
    fun anAdminWithoutAnOwnBoxSeesALockUntilUnlocked() = runBlocking {
        admin()
        val model = voicemailModel(number = null)
        model.load()

        assertEquals(VoicemailModel.Scope.All, model.scope)
        assertTrue(model.needsUnlock)
        assertTrue(model.messages.isEmpty())

        model.unlock()

        assertFalse(model.needsUnlock)
        assertEquals(4, model.messages.size)
    }

    // MARK: Deleting

    @Test
    fun deletingInTheOwnBoxNeedsNoCheck() = runBlocking {
        user()
        onlyOwnBox()
        val model = voicemailModel("user-1")
        model.load()
        val message = model.messages.first()
        model.play(message)

        model.delete(message)

        assertEquals(listOf(message.boxId to message.ref), service.deleted)
        assertEquals(1, model.messages.size)
        assertNull("what is being deleted stops first", hub.player.currentId.value)
        assertEquals(0, gate.evaluations)
    }

    @Test
    fun deletingAColleaguesMessageNeedsTheOpenCheck() = runBlocking {
        admin()
        val model = voicemailModel()
        model.load()
        model.select(VoicemailModel.Scope.All)
        val colleague = model.messages.first { it.boxId == MediaFixtures.COLLEAGUE_BOX }

        gate.expire()
        gate.results += GateResult.CANCELLED
        model.delete(colleague)

        assertTrue("nothing was deleted without a fresh check", service.deleted.isEmpty())
        assertEquals("it asked again instead", 2, gate.evaluations)
    }

    @Test
    fun aFrozenPhoneSystemBlocksDeleting() = runBlocking {
        hub.apply(MediaFixtures.frozenMe, "admin-1")
        val model = voicemailModel()
        model.load()
        val message = model.messages.first()

        assertTrue(model.isReadOnly)

        model.delete(message)

        assertTrue(service.deleted.isEmpty())
        assertEquals(MediaMessage.DeleteReadOnly, model.banner?.message)
    }

    @Test
    fun a409ReadOnlyOnDeleteBlocksFurtherDeleting() = runBlocking {
        user()
        onlyOwnBox()
        service.fail("deleteVoicemail", ApiException.ReadOnly())
        val model = voicemailModel("user-1")
        model.load()
        val message = model.messages.first()

        model.delete(message)

        assertEquals("the message stays", 2, model.messages.size)
        assertTrue(model.isReadOnly)
        assertEquals(true, model.banner?.isError)
    }

    @Test
    fun deletingSomethingAlreadyGoneJustRemovesItFromTheList() = runBlocking {
        user()
        onlyOwnBox()
        service.fail("deleteVoicemail", ApiException.NotFound())
        val model = voicemailModel("user-1")
        model.load()
        val message = model.messages.first()

        model.delete(message)

        assertEquals(1, model.messages.size)
        assertNull(model.banner)
    }

    // MARK: Errors

    @Test
    fun a403OnLoadingTakesVoicemailAway() = runBlocking {
        admin()
        service.fail("voicemail", ApiException.Forbidden())
        val model = voicemailModel()
        model.load()

        assertFalse(hub.hasVoicemail("admin-1"))
        assertTrue("just that part goes", hub.hasRecordings("admin-1"))
        assertEquals(MediaFailure.AccessDenied, model.failure)
    }

    @Test
    fun offlineKeepsTheListAndSaysSo() = runBlocking {
        admin()
        val model = voicemailModel()
        model.load()
        service.fail("voicemail", ApiException.Transport("offline"))
        model.reload()

        assertEquals(2, model.messages.size)
        assertEquals(MediaMessage.Failure(MediaFailure.Offline, MediaKind.VOICEMAIL), model.banner?.message)
    }

    @Test
    fun theFirstLoadFailingShowsTheFailure() = runBlocking {
        user()
        service.fail("voicemail", ApiException.RateLimited(30))
        val model = voicemailModel("user-1")
        model.load()

        assertNull(model.page)
        assertEquals(MediaFailure.RateLimited(30), model.failure)
    }

    @Test
    fun aPbxThatDoesNotAnswerIsANoticeNotAnEmptyList() = runBlocking {
        user()
        service.voicemailPage = service.voicemailPage.copy(boxes = emptyList(), messages = emptyList(), available = false)
        val model = voicemailModel("user-1")
        model.load()

        assertTrue(model.isUnavailable)
    }

    @Test
    fun aMessageThatTurnsOutToBeGoneIsMarkedAndNotPlayableAgain() = runBlocking {
        user()
        onlyOwnBox()
        val model = voicemailModel("user-1")
        model.load()
        val message = model.messages.first()

        // The download says 410.
        service.fail("download", ApiException.Gone())
        model.play(message)

        assertEquals(PlayerState.Failed(MediaFailure.Gone), hub.player.state.value)
        assertTrue(model.isGone(message))

        val loads = backend.loaded.size
        model.play(message)
        assertEquals("a gone message is not requested again", loads, backend.loaded.size)
        assertEquals(1, service.count("download"))
    }

    @Test
    fun aMessageWithNegativeDaysLeftIsGone() = runBlocking {
        user()
        onlyOwnBox()
        val model = voicemailModel("user-1")
        model.load()

        assertFalse(model.isGone(model.messages.first()))
        assertTrue(model.isGone(model.messages.first().copy(daysLeft = -1)))
    }

    @Test
    fun theRowsNameTheCaller() = runBlocking {
        user()
        onlyOwnBox()
        val model = voicemailModel("user-1")
        model.load()
        val message = model.messages.first()

        assertEquals("070 123 45 67", model.title(message, "Anoniem"))
        hub.nameLookup = { if (it == "070 123 45 67") "Bakkerij Smit" else null }
        assertEquals("Bakkerij Smit", model.title(message, "Anoniem"))
        assertEquals("Anoniem", model.title(message.copy(caller = null), "Anoniem"))
        assertEquals("Piet", model.title(message.copy(callerName = "Piet"), "Anoniem"))
    }

    // MARK: Recordings

    private val words = RecordingWords("Anoniem", "Inkomend", "Uitgaand", "Intern")

    @Test
    fun recordingsListsOnlyRecordedCallsOfTheMonth() = runBlocking {
        admin()
        val model = RecordingsModel("admin-1", hub)
        model.load()

        assertEquals(2, model.rows.size)
        assertTrue(model.rows.all { it.hasRecording })
        assertEquals(listOf("2026-10", "2026-09"), model.months)
        assertEquals(1, service.requestedMonths.size)
        assertNull("no month = the current one", service.requestedMonths[0])
    }

    @Test
    fun choosingAnotherMonthLoadsItAndShowsExpiredRecordingsAsGone() = runBlocking {
        admin()
        val model = RecordingsModel("admin-1", hub)
        model.load()

        model.select("2026-09")

        assertEquals("2026-09", service.requestedMonths.last())
        assertEquals(1, model.rows.size)
        assertTrue("past retention: 'Niet meer beschikbaar'", model.isGone(model.rows[0]))

        val loads = backend.loaded.size
        model.play(model.rows[0], words)
        assertEquals(loads, backend.loaded.size)
    }

    @Test
    fun playingARecordingAsksTheRecordingRoute() = runBlocking {
        admin()
        val model = RecordingsModel("admin-1", hub)
        model.load()
        val call = model.rows.first()

        model.play(call, words)

        assertEquals(1, service.count("recordingSource"))
        assertEquals(call.id, model.nowPlaying?.id)
        assertEquals(1, service.count("download"))
    }

    @Test
    fun a410WhilePlayingARecordingMarksItGone() = runBlocking {
        admin()
        val model = RecordingsModel("admin-1", hub)
        model.load()
        val call = model.rows.first()
        service.fail("download", ApiException.Gone())

        model.play(call, words)

        assertTrue(model.isGone(call))
        assertEquals(PlayerState.Failed(MediaFailure.Gone), hub.player.state.value)
    }

    @Test
    fun a403OnTheRecordingTakesRecordingsAwayButNotVoicemail() = runBlocking {
        admin()
        val model = RecordingsModel("admin-1", hub)
        model.load()
        service.fail("download", ApiException.Forbidden())

        model.play(model.rows.first(), words)

        assertFalse(hub.hasRecordings("admin-1"))
        assertTrue(hub.hasVoicemail("admin-1"))
    }

    @Test
    fun a429WhilePlayingExplainsAndOffersRetry() = runBlocking {
        admin()
        val model = RecordingsModel("admin-1", hub)
        model.load()
        service.fail("download", ApiException.RateLimited(20))

        model.play(model.rows.first(), words)

        val state = hub.player.state.value as PlayerState.Failed
        assertEquals(MediaFailure.RateLimited(20), state.failure)
        assertTrue(state.failure.isRetryable)
    }

    @Test
    fun recordingRowsAreTitledAndDescribed() = runBlocking {
        admin()
        val model = RecordingsModel("admin-1", hub)
        model.load()
        val call = model.rows.first()

        assertEquals("070 123 45 67", model.title(call, words))
        assertEquals("9 okt 09:12 · 2:05 · Inkomend · Jan de Vries", model.subtitle(call, words))
        assertEquals("Jan de Vries", model.title(call.copy(number = null), words))
        assertEquals("Anoniem", model.title(call.copy(number = null, extensionName = null), words))
    }

    // MARK: Calls

    @Test
    fun nothingPlaysWhileACallIsGoingAndACallPausesWhatPlays() = runBlocking {
        admin()
        val model = RecordingsModel("admin-1", hub)
        model.load()
        val call = model.rows.first()

        model.play(call, words)
        backend.emit(AudioBackendEvent.Ready(30.0))
        assertEquals(PlayerState.Playing, hub.player.state.value)

        env.callActive = true
        hub.callStateChanged()
        assertEquals(PlayerState.Paused, hub.player.state.value)

        hub.player.resume()
        assertEquals(PlayerState.Failed(MediaFailure.CallInProgress), hub.player.state.value)

        val loads = backend.loaded.size
        model.play(call, words)
        assertEquals("not even requested during a call", loads, backend.loaded.size)
    }

    // MARK: Words

    @Test
    fun clockAndSpeedFormats() {
        assertEquals("0:00", MediaFormat.clock(0.0))
        assertEquals("1:23", MediaFormat.clock(83.9))
        assertEquals("1:02:05", MediaFormat.clock(3725.0))
        assertEquals("1,5×", MediaFormat.speed(PlaybackSpeed.FAST, Locale("nl", "NL")))
        assertEquals("1.5×", MediaFormat.speed(PlaybackSpeed.FAST, Locale.US))
        assertEquals("1×", MediaFormat.speed(PlaybackSpeed.NORMAL, Locale("nl", "NL")))
        assertEquals("2×", MediaFormat.speed(PlaybackSpeed.FASTER, Locale.US))
        assertEquals("oktober 2026", MediaFormat.month("2026-10", Locale("nl", "NL")))
        assertEquals("not a month", MediaFormat.month("not a month"))
        assertEquals("2026-13", MediaFormat.month("2026-13"))
    }

    @Test
    fun historyDurations() {
        assertEquals("0:42", MediaFormat.duration(42))
        assertEquals("5:12", MediaFormat.duration(312))
        assertEquals("1:02:03", MediaFormat.duration(3723))
    }

    // MARK: History

    private fun account(id: String = "admin-1") = StoredAccount(
        id = id, label = "Jan", pbxName = "Voorbeeld", extensionName = "Jan", extensionNumber = "102", customerName = "Voorbeeld",
        deviceToken = nl.fullstackstudio.fsvoip.core.Secret("t"), installId = "a1b2c3d4e5f60718",
        sip = nl.fullstackstudio.fsvoip.core.SipCredentials("102", nl.fullstackstudio.fsvoip.core.Secret("pw"), "x.powervoip.nl", "sip.powervoip.nl", 5061, nl.fullstackstudio.fsvoip.core.ServerTransport.TLS, true),
        pairedAtMillis = 1_790_000_000_000L,
    )

    private fun history() = HistoryModel(hub, localWhen = { "lokaal" })

    private fun entry(direction: String, outcome: String, recording: Boolean = false, accountId: String = "a") =
        HistoryEntry.fromItem(MediaFixtures.item(MediaFixtures.call(1, recording, false, direction, outcome, sort = "2026-10-08T07:12:41.000Z")), accountId)

    @Test
    fun anUnansweredIncomingCallIsMissedAndAnUnansweredOutgoingCallIsNot() {
        assertTrue(entry("inbound", "missed").isMissed)
        assertTrue(entry("inbound", "voicemail").isMissed)
        assertFalse(entry("outbound", "missed").isMissed)
        assertFalse(entry("inbound", "answered").isMissed)
    }

    @Test
    fun theRowShowsWhoHandledTheCall() {
        val entry = entry("inbound", "answered")

        assertEquals("Jan de Vries", entry.extensionName)
        assertEquals("2:05", entry.durationLabel)
        assertEquals("a/11111111-aaaa-4bbb-8ccc-000000000001", entry.id)
        // Only an answered call has a duration.
        assertEquals("", entry("inbound", "missed").durationLabel)
    }

    @Test
    fun theMissedFilterAndTheKindFilter() {
        val entries = listOf(entry("inbound", "missed"), entry("outbound", "answered"), entry("inbound", "answered"))

        assertEquals(3, HistoryFilter.apply(entries, HistoryEntry.Filter.ALL).size)
        assertEquals(1, HistoryFilter.apply(entries, HistoryEntry.Filter.MISSED).size)
        assertEquals(1, HistoryFilter.apply(entries, HistoryEntry.Filter.ALL, HistoryEntry.Kind.OUTGOING).size)
        assertEquals(0, HistoryFilter.apply(entries, HistoryEntry.Filter.MISSED, HistoryEntry.Kind.OUTGOING).size)
    }

    @Test
    fun theServersCallsComeFirstAndNewerLocalCallsAreAdded() = runBlocking {
        val history = history()
        history.load("admin-1")

        val newest = 1_800_000_000_000L
        val local = listOf(
            RecentCall(number = "0612345678", accountId = "admin-1", accountLabel = "Jan", direction = RecentCall.Direction.OUTGOING, outcome = RecentCall.Outcome.ANSWERED, startedAtMillis = newest, durationSeconds = 30),
            RecentCall(number = "0612345679", accountId = "admin-1", accountLabel = "Jan", direction = RecentCall.Direction.OUTGOING, outcome = RecentCall.Outcome.ANSWERED, startedAtMillis = 1_000_000_000_000L, durationSeconds = 30),
            RecentCall(number = "0612345680", accountId = "other", accountLabel = "Jan", direction = RecentCall.Direction.OUTGOING, outcome = RecentCall.Outcome.ANSWERED, startedAtMillis = newest, durationSeconds = 30),
        )
        val entries = history.entries("admin-1", local, "Jan")

        assertEquals(HistoryModel.LoadState.LOADED, history.state("admin-1"))
        assertEquals("only the newer call of this account, which the PBX has not caught up with", listOf("0612345678"), entries.filter { it.isLocal }.map { it.number })
        assertTrue("newest first", entries.first().isLocal)
        assertEquals(3, entries.count { !it.isLocal })
        assertEquals("lokaal", entries.first().whenLabel)
    }

    @Test
    fun whenThePbxDoesNotAnswerThePhonesCallsAreShown() = runBlocking {
        service.fail("calls", ApiException.Transport("offline"))
        val history = history()
        history.load("admin-1")

        val local = listOf(RecentCall(number = "0612345678", name = "Piet", accountId = "admin-1", accountLabel = "Jan", direction = RecentCall.Direction.INCOMING, outcome = RecentCall.Outcome.MISSED, startedAtMillis = System.currentTimeMillis(), durationSeconds = 0))
        val entries = history.entries("admin-1", local, "Jan")

        assertEquals(HistoryModel.LoadState.UNAVAILABLE, history.state("admin-1"))
        assertEquals(1, entries.size)
        assertTrue(entries[0].isMissed)
        assertEquals("Piet", history.title(entries[0]))
    }

    @Test
    fun olderMonthsAreLoadedOnRequest() = runBlocking {
        val history = history()
        history.load("admin-1")

        assertEquals("2026-09", history.nextMonth("admin-1"))

        history.loadMore("admin-1")

        assertNull(history.nextMonth("admin-1"))
        assertEquals(4, history.entries("admin-1", emptyList(), null).size)
    }

    @Test
    fun aRefreshKeepsTheOlderMonthsTheUserOpened() = runBlocking {
        val history = history()
        history.load("admin-1")
        history.loadMore("admin-1")
        history.load("admin-1")

        assertEquals(4, history.entries("admin-1", emptyList(), null).size)
        assertEquals(listOf("2026-10", "2026-09"), history.pages.value["admin-1"]!!.map { it.month })
    }

    @Test
    fun theTitleIsTheNameOfAContactElseTheNumber() = runBlocking {
        val history = history()
        history.anonymousTitle = "Onbekend nummer"
        history.nameLookup = { if (it == "070 123 45 67") "Bakkerij Smit" else null }

        assertEquals("Bakkerij Smit", history.title(entry("inbound", "answered")))
        assertEquals("Onbekend nummer", history.title(entry("inbound", "answered").copy(number = null)))
        assertEquals("0612345678", history.title(entry("inbound", "answered").copy(number = "0612345678")))
    }

    @Test
    fun aUserNeverGetsARecordingEvenIfTheServerSaidSo() = runBlocking {
        user("user-1")
        val history = history()
        val entry = entry("inbound", "answered", recording = true, accountId = "user-1")

        assertTrue(entry.hasRecording)
        assertFalse(history.canPlayRecording(entry))

        history.play(entry)

        assertNull(history.nowPlaying.value)
        assertEquals("the recording is not even asked for", 0, service.count("recordingSource"))
    }

    @Test
    fun anAdminCanPlayARecordingAfterTheLocalCheck() = runBlocking {
        admin("admin-1")
        val history = history()
        val entry = entry("inbound", "answered", recording = true, accountId = "admin-1")

        assertTrue(history.canPlayRecording(entry))

        history.play(entry)

        assertEquals(1, gate.evaluations)
        assertEquals(1, service.count("recordingSource"))
        assertNotNull(history.nowPlaying.value)
    }

    @Test
    fun aCancelledCheckPlaysNothing() = runBlocking {
        admin("admin-1")
        gate.results += GateResult.CANCELLED
        val history = history()

        history.play(entry("inbound", "answered", recording = true, accountId = "admin-1"))

        assertNull(history.nowPlaying.value)
        assertEquals(0, service.count("recordingSource"))
    }

    @Test
    fun aCallWithoutARecordingOffersNone() {
        admin("admin-1")

        assertFalse(history().canPlayRecording(entry("inbound", "answered", recording = false, accountId = "admin-1")))
    }

    @Test
    fun anExpiredRecordingIsShownToAdminsOnlyAndCannotBePlayed() {
        admin("admin-1")
        user("user-1")
        val history = history()
        val expired = HistoryEntry.fromItem(MediaFixtures.item(MediaFixtures.call(3, false, true)), "admin-1")

        assertTrue(history.hasExpiredRecording(expired))
        assertTrue(history.isRecordingGone(expired))
        assertFalse(history.hasExpiredRecording(expired.copy(accountId = "user-1")))
        assertFalse(history.canPlayRecording(expired))
    }

    @Test
    fun aGoneRecordingIsRememberedAfterTheServerSaid410() = runBlocking {
        admin("admin-1")
        service.fail("download", ApiException.Gone())
        val history = history()
        val entry = entry("inbound", "answered", recording = true, accountId = "admin-1")

        history.play(entry)

        assertTrue(history.isRecordingGone(entry))
        assertEquals(PlayerState.Failed(MediaFailure.Gone), hub.player.state.value)
    }

    @Test
    fun revokedFromTheListAsksTheAppToReread() = runBlocking {
        var revoked = 0
        hub.onRevoked = { revoked++ }
        service.fail("calls", ApiException.Unauthorized())
        val history = history()

        history.load("admin-1")

        assertEquals(1, revoked)
        assertEquals(HistoryModel.LoadState.UNAVAILABLE, history.state("admin-1"))
    }

    @Test
    fun stopPlayerClearsTheNowPlayingOfTheHistory() = runBlocking {
        admin("admin-1")
        val history = history()
        history.play(entry("inbound", "answered", recording = true, accountId = "admin-1"))
        assertNotNull(history.nowPlaying.value)

        history.stopPlayer()

        assertNull(history.nowPlaying.value)
    }
}
