// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.core

import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun account(number: String? = "102") = StoredAccount(
    id = "acc", label = "L", pbxName = "P", extensionName = "Jan", extensionNumber = number, customerName = "C", deviceToken = Secret("t"), installId = "i",
    sip = SipCredentials("102", Secret("p"), "d", "x", 5060, ServerTransport.TCP, true), pairedAtMillis = 0,
)

private class FakeMedia : MediaServicing {
    val calls = mutableListOf<String?>()
    var failure: Exception? = null
    var deleteFailure: Exception? = null
    val deleted = mutableListOf<String>()
    var voicemailPage: VoicemailPage = FsJson.default.decodeFromString(VoicemailPage.serializer(), Fixtures.text("voicemail-page.json"))

    override suspend fun calls(account: StoredAccount, month: String?): PbxCallsPage {
        calls += month
        failure?.let { throw it }
        val base = FsJson.default.decodeFromString(PbxCallsPage.serializer(), Fixtures.text("calls-page-team.json"))

        return if (month == "2026-09") base.copy(month = "2026-09", calls = listOf(base.calls[0].copy(id = "older", startedSort = "2026-09-20T10:00:00.000Z"))) else base
    }

    override suspend fun voicemail(account: StoredAccount, boxId: String?): VoicemailPage {
        failure?.let { throw it }
        return voicemailPage
    }

    override suspend fun deleteVoicemail(account: StoredAccount, boxId: String, ref: String) {
        deleteFailure?.let { throw it }
        deleted += "$boxId/$ref"
    }

    override fun voicemailSource(account: StoredAccount, boxId: String, ref: String) = MediaSource("https://example.test/$boxId/$ref/audio", mapOf("Authorization" to "Bearer t"))

    override fun recordingSource(account: StoredAccount, callId: String) = MediaSource("https://example.test/calls/$callId/recording", mapOf("Authorization" to "Bearer t"))
}

class HistoryModelTest {
    private fun local(startedAt: String) = HistoryEntry.of(
        RecentCall(
            id = "l1", number = "0612345678", name = "Piet", accountId = "acc", accountLabel = "L", direction = RecentCall.Direction.OUTGOING,
            outcome = RecentCall.Outcome.ANSWERED, startedAtMillis = Instant.parse(startedAt).toEpochMilli(), durationSeconds = 12,
        ),
        extensionName = "Jan", whenLabel = "net", durationLabel = "0:12",
    )

    @Test
    fun theTeamHistoryComesInNewestFirstAndKeepsTheLaterCallsOfThisPhone() = runTest {
        val model = HistoryModel(FakeMedia())
        model.load(account())

        assertEquals(HistoryModel.LoadState.LOADED, model.state("acc"))
        val server = model.entries("acc", emptyList())
        assertEquals(3, server.size)
        assertEquals(listOf("acc/33333333-aaaa-4bbb-8ccc-000000000001", "acc/33333333-aaaa-4bbb-8ccc-000000000002", "acc/33333333-aaaa-4bbb-8ccc-000000000003"), server.map { it.id })
        assertEquals("Pieter Jansen", server[0].extensionName)
        assertTrue(server[2].isMissed)
        assertFalse(server[0].isMissed)
        assertEquals(HistoryEntry.Kind.OUTGOING, server[1].kind)
        assertEquals("0:48", server[1].durationLabel)
        // A call nobody picked up has no duration to show.
        assertEquals("", server[2].durationLabel)

        // A call of this phone the PBX has not caught up with is added; an older one is the PBX's already.
        val merged = model.entries("acc", listOf(local("2026-10-12T09:00:00Z"), local("2026-10-01T09:00:00Z").copy(id = "local/old")))
        assertEquals(4, merged.size)
        assertEquals("local/l1", merged.first().id)
    }

    @Test
    fun withoutAnAnswerTheCallsOfThisPhoneAreShown() = runTest {
        val media = FakeMedia().apply { failure = ApiException.Transport("Timeout") }
        val model = HistoryModel(media)
        model.load(account())

        assertEquals(HistoryModel.LoadState.UNAVAILABLE, model.state("acc"))
        assertEquals(listOf("local/l1"), model.entries("acc", listOf(local("2020-01-01T00:00:00Z"))).map { it.id })

        // A later failure does not take a good list away.
        media.failure = null
        model.load(account())
        media.failure = ApiException.Transport("Timeout")
        model.load(account())
        assertEquals(HistoryModel.LoadState.LOADED, model.state("acc"))
    }

    @Test
    fun olderMonthsAreLoadedOneByOne() = runTest {
        val media = FakeMedia()
        val model = HistoryModel(media)
        model.load(account())

        assertEquals("2026-09", model.nextMonth("acc"))
        model.loadMore(account())
        assertEquals(listOf(null, "2026-09"), media.calls)
        assertNull(model.nextMonth("acc"))
        assertEquals(4, model.entries("acc", emptyList()).size)

        // A refresh replaces the newest month and keeps the older one.
        model.load(account())
        assertEquals(4, model.entries("acc", emptyList()).size)
    }

    @Test
    fun theMissedFilter() = runTest {
        val model = HistoryModel(FakeMedia())
        model.load(account())
        val entries = model.entries("acc", emptyList())

        assertEquals(1, HistoryModel.filtered(entries, HistoryEntry.Filter.MISSED).size)
        assertEquals(1, HistoryModel.filtered(entries, HistoryEntry.Filter.ALL, HistoryEntry.Kind.OUTGOING).size)
    }
}

private class ScriptedAuth(var result: LocalAuthResult = LocalAuthResult.SUCCESS, var availability: LocalAuthAvailability = LocalAuthAvailability.AVAILABLE) : LocalAuthenticating {
    var prompts = 0

    override fun availability() = availability

    override suspend fun evaluate(reason: String): LocalAuthResult {
        prompts++
        return result
    }
}

class VoicemailAllBoxesTest {
    private val ownBox = "7a1d9c3e-5b2f-4e8a-9c01-6d3e8f2a4b57"
    private val sharedBox = "2f4a6c8e-1b3d-4f5a-8c7e-9a0b1c2d3e4f"

    private fun admin(media: FakeMedia, auth: ScriptedAuth, clock: () -> Long = { 0L }, access: VoicemailAccess = VoicemailAccess.ALL) =
        VoicemailModel(account("102"), media, { access }, LocalAccessGate(auth, clock), { "reason" }) { true }

    @Test
    fun anAdministratorStartsOnTheOwnBoxAndOthersAreLocked() = runTest {
        val model = admin(FakeMedia(), ScriptedAuth())
        model.load()
        val page = model.page.value!!

        assertTrue(model.canChooseBox)
        assertEquals(ownBox, model.ownBoxId(page))
        assertEquals(VoicemailScope.Own, model.scope.value)
        assertEquals(listOf("v1.Zm9vYmFyYmF6cXV4"), model.messages(page).map { it.ref })
        assertFalse(model.needsUnlock)
    }

    @Test
    fun allVoicemailOpensOnlyAfterTheLocalCheck() = runTest {
        val auth = ScriptedAuth(LocalAuthResult.CANCELLED)
        val model = admin(FakeMedia(), auth)
        model.load()
        val page = model.page.value!!

        // Cancelled: nothing changes.
        model.select(VoicemailScope.All)
        assertEquals(VoicemailScope.Own, model.scope.value)
        assertEquals(1, model.messages(page).size)

        auth.result = LocalAuthResult.SUCCESS
        model.select(VoicemailScope.All)
        assertEquals(VoicemailScope.All, model.scope.value)
        assertEquals(2, model.messages(page).size)
        assertTrue(model.isOwn(page, page.messages[0]))
        assertFalse(model.isOwn(page, page.messages[1]))

        model.select(VoicemailScope.Box(sharedBox))
        assertEquals(listOf("v1.cXV1eGNvcmdlZ3JhdWx0"), model.messages(page).map { it.ref })
        assertTrue(model.showsBoxName(page, page.messages[1]))
    }

    @Test
    fun theFiveMinutesRunOutAndTheScreenFallsBackToTheOwnBox() = runTest {
        var clock = 0L
        val model = admin(FakeMedia(), ScriptedAuth(), { clock })
        model.load()
        val page = model.page.value!!

        model.select(VoicemailScope.All)
        clock += 4 * 60_000
        assertFalse(model.refreshLock())
        assertEquals(2, model.messages(page).size)

        clock += 6 * 60_000
        assertTrue(model.refreshLock())
        assertEquals(VoicemailScope.Own, model.scope.value)
        assertFalse(model.othersUnlocked.value)
        assertEquals(1, model.messages(page).size)
    }

    @Test
    fun aColleaguesMessageIsDeletedOnlyWhileTheCheckIsOpen() = runTest {
        val media = FakeMedia()
        val auth = ScriptedAuth(LocalAuthResult.CANCELLED)
        val model = admin(media, auth)
        model.load()
        val shared = model.page.value!!.messages[1]

        // Locked, and the prompt is cancelled: nothing is deleted.
        assertFalse(model.delete(shared))
        assertTrue(media.deleted.isEmpty())

        auth.result = LocalAuthResult.SUCCESS
        model.select(VoicemailScope.All)
        assertTrue(model.delete(shared))
        assertEquals(listOf("$sharedBox/${shared.ref}"), media.deleted)
    }

    @Test
    fun noScreenLockIsSaidAndTheOthersStayClosed() = runTest {
        val model = admin(FakeMedia(), ScriptedAuth(availability = LocalAuthAvailability.NO_SCREEN_LOCK))
        model.load()

        model.select(VoicemailScope.All)
        assertTrue(model.noScreenLock.value)
        assertEquals(VoicemailScope.Own, model.scope.value)
    }

    @Test
    fun aUserNeverChoosesABoxEvenWithAGate() = runTest {
        val auth = ScriptedAuth()
        val model = admin(FakeMedia(), auth, access = VoicemailAccess.OWN)
        model.load()

        model.select(VoicemailScope.All)
        assertEquals(VoicemailScope.Own, model.scope.value)
        assertEquals(0, auth.prompts)
        assertFalse(model.canChooseBox)
    }

    @Test
    fun anAdministratorWithoutAnOwnBoxStartsOnTheLockedAll() = runTest {
        val media = FakeMedia()
        media.voicemailPage = media.voicemailPage.copy(boxes = media.voicemailPage.boxes.filter { it.shared })
        val model = admin(media, ScriptedAuth())
        model.load()

        assertEquals(VoicemailScope.All, model.scope.value)
        assertTrue(model.needsUnlock)
        assertTrue(model.messages(model.page.value!!).isEmpty())
    }
}

class VoicemailModelTest {
    @Test
    fun anAdminPageShowsTheOwnBoxOnly() = runTest {
        val media = FakeMedia()
        val model = VoicemailModel(account("102"), media) { true }
        model.load()
        val page = model.page.value!!

        // Two boxes: the shared one and the own one (number 102). Only the own message shows.
        assertEquals("7a1d9c3e-5b2f-4e8a-9c01-6d3e8f2a4b57", model.ownBoxId(page))
        assertEquals(listOf("v1.Zm9vYmFyYmF6cXV4"), model.messages(page).map { it.ref })
    }

    @Test
    fun aUserPageHasOneBox() = runTest {
        val media = FakeMedia()
        media.voicemailPage = FsJson.default.decodeFromString(VoicemailPage.serializer(), Fixtures.text("voicemail-page-unavailable.json"))
        val model = VoicemailModel(account("102"), media) { true }
        model.load()

        assertFalse(model.page.value!!.available)
        assertEquals("7a1d9c3e-5b2f-4e8a-9c01-6d3e8f2a4b57", model.ownBoxId(model.page.value!!))
    }

    @Test
    fun noVoicemailAccessMeansNoRequest() = runTest {
        val media = FakeMedia().apply { failure = IllegalStateException("must not be asked") }
        val model = VoicemailModel(account(), media) { false }
        model.load()

        assertTrue(model.page.value!!.messages.isEmpty())
        assertNull(model.failure.value)
    }

    @Test
    fun deletingRemovesTheMessageAndAGoneOneToo() = runTest {
        val media = FakeMedia()
        val model = VoicemailModel(account("102"), media) { true }
        model.load()
        val message = model.messages(model.page.value!!).single()

        assertTrue(model.delete(message))
        assertEquals(listOf("${message.boxId}/${message.ref}"), media.deleted)
        assertTrue(model.messages(model.page.value!!).isEmpty())

        // Deleted on another phone already: the list was out of date, nothing to tell the user.
        media.deleteFailure = ApiException.NotFound()
        assertTrue(model.delete(message))
        assertNull(model.banner.value)

        media.deleteFailure = ApiException.Conflict("read_only", null, null)
        assertFalse(model.delete(message))
        assertEquals(MediaFailure.ReadOnly, model.banner.value)
    }

    @Test
    fun aFailureWithAGoodListIsABannerNotAnEmptyScreen() = runTest {
        val media = FakeMedia()
        val model = VoicemailModel(account("102"), media) { true }
        model.load()

        media.failure = ApiException.Unavailable(true, null)
        model.load()
        assertEquals(MediaFailure.Unavailable, model.banner.value)
        assertNull(model.failure.value)
        assertFalse(model.page.value!!.messages.isEmpty())

        val fresh = VoicemailModel(account(), FakeMedia().apply { failure = ApiException.Transport("x") }) { true }
        fresh.load()
        assertEquals(MediaFailure.Offline, fresh.failure.value)
        assertNull(fresh.page.value)
    }

    @Test
    fun daysLeft() {
        val model = VoicemailModel(account(), FakeMedia()) { true }
        val message = VoicemailMessage(ref = "r", boxId = "b", daysLeft = -1)
        assertTrue(model.isGone(message))
        assertFalse(model.isGone(message.copy(daysLeft = 0)))
        assertFalse(model.isGone(message.copy(daysLeft = null)))
    }
}

private class FakeBackend : AudioBackend {
    override var listener: AudioBackendListener? = null
    val log = mutableListOf<String>()

    override fun prepare(source: MediaSource, playWhenReady: Boolean) { log += "prepare $playWhenReady" }
    override fun play() { log += "play" }
    override fun pause() { log += "pause" }
    override fun seekTo(positionMs: Long) { log += "seek $positionMs" }
    override fun setSpeed(speed: Float) { log += "speed $speed" }
    override fun stop() { log += "stop" }
}

class AudioPlayerModelTest {
    private val source = MediaSource("https://example.test/a", emptyMap())

    @Test
    fun playPauseAndEnd() {
        val backend = FakeBackend()
        val player = AudioPlayerModel(backend)

        player.play("m1", source)
        assertEquals(PlayerSnapshot.Status.LOADING, player.snapshot.value.status)

        backend.listener!!.onReady(21_000)
        backend.listener!!.onPlayingChanged(true)
        backend.listener!!.onPosition(5_000)
        assertEquals(PlayerSnapshot.Status.PLAYING, player.snapshot.value.status)
        assertEquals(21_000, player.snapshot.value.durationMs)

        player.toggle()
        assertEquals("pause", backend.log.last())
        backend.listener!!.onPlayingChanged(false)
        assertEquals(PlayerSnapshot.Status.PAUSED, player.snapshot.value.status)
        assertEquals(5_000, player.snapshot.value.positionMs)

        player.toggle()
        assertEquals("play", backend.log.last())

        backend.listener!!.onPlayingChanged(true)
        backend.listener!!.onEnded()
        backend.listener!!.onPlayingChanged(false)
        assertEquals(PlayerSnapshot.Status.ENDED, player.snapshot.value.status)

        // After the end, play starts from the beginning.
        player.toggle()
        assertEquals(listOf("seek 0", "play"), backend.log.takeLast(2))
    }

    @Test
    fun seekingStaysInsideTheAudio() {
        val backend = FakeBackend()
        val player = AudioPlayerModel(backend)
        player.play("m1", source)
        backend.listener!!.onReady(21_000)

        player.skip(-10_000)
        assertEquals("seek 0", backend.log.last())
        player.seekTo(99_000)
        assertEquals("seek 21000", backend.log.last())
        assertEquals(21_000, player.snapshot.value.positionMs)
    }

    @Test
    fun aFailureIsKeptForTheScreen() {
        val backend = FakeBackend()
        val player = AudioPlayerModel(backend)
        player.play("m1", source)

        backend.listener!!.onError(MediaFailure.Gone)
        assertEquals(PlayerSnapshot.Status.FAILED, player.snapshot.value.status)
        assertEquals(MediaFailure.Gone, player.snapshot.value.failure)

        // A late "playing" callback does not bring it back.
        backend.listener!!.onPlayingChanged(true)
        assertEquals(PlayerSnapshot.Status.FAILED, player.snapshot.value.status)

        player.stop()
        assertFalse(player.snapshot.value.isActive)
    }

    @Test
    fun theSpeedSurvivesTheNextMessage() {
        val backend = FakeBackend()
        val player = AudioPlayerModel(backend)
        player.setSpeed(1.5f)
        player.play("m1", source)

        assertEquals(1.5f, player.snapshot.value.speed)
        assertEquals("speed 1.5", backend.log.last())
    }

    @Test
    fun failuresForHttpStatuses() {
        assertEquals(MediaFailure.Gone, MediaFailure.forHttpStatus(410))
        assertEquals(MediaFailure.AccessDenied, MediaFailure.forHttpStatus(403))
        assertEquals(MediaFailure.Revoked, MediaFailure.forHttpStatus(401))
        assertEquals(MediaFailure.RateLimited(30), MediaFailure.forHttpStatus(429, 30))
        assertEquals(MediaFailure.Unavailable, MediaFailure.forHttpStatus(503))
        assertEquals("0:42", MediaFormat.clock(42_900))
        assertEquals("1:02:03", MediaFormat.clock(3_723_000))
    }
}
