// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.core

import java.io.File
import java.time.LocalDateTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeSoundService : SoundServicing {
    val calls = mutableListOf<String>()
    val uploads = mutableListOf<Triple<String, String, Int>>()
    val failures = mutableMapOf<String, ArrayDeque<Exception>>()
    var items = mutableListOf(
        Sound("s-2", "Welkom", playable = true, uses = listOf(SoundUse("menu_greeting", "Support")), sync = SyncState.OK),
        Sound("s-1", "buiten kantoortijd", playable = true, sync = SyncState.OK),
    )
    var nextId = "s-new"
    var audioBytes = byteArrayOf(1, 2, 3)

    private fun enter(name: String) {
        calls += name
        failures[name]?.removeFirstOrNull()?.let { throw it }
    }

    fun count(name: String) = calls.count { it == name }

    override suspend fun sounds(): List<Sound> {
        enter("sounds")

        return items.toList()
    }

    override suspend fun upload(name: String, fileName: String, data: ByteArray, progress: ((Double) -> Unit)?): String {
        enter("upload")
        uploads += Triple(name, fileName, data.size)
        progress?.invoke(1.0)
        items += Sound(nextId, name, playable = true, sync = SyncState.PENDING)

        return nextId
    }

    override suspend fun rename(id: String, name: String) {
        enter("rename")
        items = items.map { if (it.id == id) it.copy(name = name) else it }.toMutableList()
    }

    override suspend fun delete(id: String) {
        enter("delete")
        items.removeAll { it.id == id }
    }

    override suspend fun audio(id: String): ByteArray {
        enter("audio")

        return audioBytes
    }
}

private class FakePlayer : SoundPlayer {
    override val state = MutableStateFlow(SoundPlayer.PlayerState())
    var loaded: File? = null

    override fun play(id: String, load: suspend () -> File) {
        state.value = SoundPlayer.PlayerState(currentId = id, status = SoundPlayer.Status.PLAYING)
        kotlinx.coroutines.runBlocking { loaded = load() }
    }

    override fun togglePlayPause() {
        val current = state.value
        state.value = current.copy(status = if (current.status == SoundPlayer.Status.PLAYING) SoundPlayer.Status.PAUSED else SoundPlayer.Status.PLAYING)
    }

    override fun seekTo(positionMs: Int) = Unit

    override fun stop() {
        state.value = SoundPlayer.PlayerState()
    }
}

private class StubRecorder : RecorderBackend {
    override var onInterrupted: (() -> Unit)? = null

    override suspend fun requestPermission() = true

    override fun start(file: File) {
        file.writeBytes(byteArrayOf(1, 2, 3))
    }

    override fun currentTime() = 5.0

    override fun stop() = 5.0
}

class SoundsModelTest {
    private val service = FakeSoundService()
    private val player = FakePlayer()
    private val directory = File(System.getProperty("java.io.tmpdir"), "fsvoip-sounds-test-${System.nanoTime()}")
    private var accessDenied = 0
    private var gateTouches = 0
    private var callActive = false

    @After
    fun cleanUp() {
        directory.deleteRecursively()
    }

    private fun TestScope.model(): SoundsModel {
        val recorder = AudioRecorderSession(StubRecorder(), File(directory, "rec"), backgroundScope, isCallActive = { callActive }, sleep = { awaitCancellation() })
        val hooks = SoundsHooks(touchGate = { gateTouches++ }, isCallActive = { callActive }, onAccessDenied = { accessDenied++ })

        return SoundsModel("admin-1", service, player, recorder, backgroundScope as CoroutineScope, File(directory, "cache"), hooks) { LocalDateTime.of(2026, 10, 9, 14, 32) }
    }

    @Test
    fun theListIsSortedByNameIgnoringCase() = runTest {
        val model = model()

        model.load()

        assertEquals(listOf("buiten kantoortijd", "Welkom"), model.sounds.value.map { it.name })
        assertTrue(model.hasLoaded.value)
    }

    @Test
    fun a403OnTheSoundsRoutesTakesTheRightAway() = runTest {
        service.failures["sounds"] = ArrayDeque(listOf(ApiException.Forbidden()))
        val model = model()

        model.load()

        assertEquals(1, accessDenied)
        assertEquals(SoundFailure.AccessDenied, model.failure.value)
    }

    @Test
    fun applyOnlyWhenTheChoiceChangedAndNothingIsBeingAdded() {
        assertTrue(SoundSelection.canApply("a", "b", allowsNone = false, isUploading = false))
        assertFalse(SoundSelection.canApply("a", "a", allowsNone = false, isUploading = false))
        assertFalse(SoundSelection.canApply("a", "b", allowsNone = false, isUploading = true))
        assertFalse(SoundSelection.canApply(null, "b", allowsNone = false, isUploading = false))
        assertTrue(SoundSelection.canApply(null, "b", allowsNone = true, isUploading = false))
    }

    @Test
    fun aFileIsUploadedAndTheNewSoundIsSelectable() = runTest {
        val model = model()
        model.load()

        val id = model.addFile("bericht.mp3", size = 3) { byteArrayOf(1, 2, 3) }

        assertEquals("s-new", id)
        assertEquals(listOf(Triple("bericht", "bericht.mp3", 3)), service.uploads)
        assertEquals("s-new", model.lastAddedId.value)
        assertNotNull(model.sound("s-new"))
        assertNull(model.upload.value)
        assertTrue(gateTouches > 0)
    }

    @Test
    fun aFileAboveTheLimitIsNeverReadOrSent() = runTest {
        val model = model()
        var read = false

        val id = model.addFile("groot.wav", size = SoundUpload.MAX_BYTES + 1L) { read = true; byteArrayOf() }

        assertNull(id)
        assertFalse(read)
        assertTrue(service.uploads.isEmpty())
        assertEquals(SoundFailure.TooLarge, model.banner.value?.failure)
    }

    @Test
    fun aFileOfUnknownSizeIsRefused() = runTest {
        val model = model()
        var read = false

        assertNull(model.addFile("raar.wav", size = null) { read = true; byteArrayOf() })

        assertFalse(read)
        assertEquals(SoundFailure.Other, model.banner.value?.failure)
    }

    @Test
    fun uploadFailuresShowTheirOwnWords() = runTest {
        val model = model()
        val cases = listOf<Pair<Exception, SoundFailure>>(
            ApiException.InvalidAudio() to SoundFailure.InvalidAudio,
            ApiException.TooLarge() to SoundFailure.TooLarge,
            ApiException.TooMany() to SoundFailure.TooMany,
            ApiException.RateLimited(30) to SoundFailure.RateLimited(30),
        )

        for ((error, expected) in cases) {
            service.failures["upload"] = ArrayDeque(listOf(error))

            assertNull(model.addFile("x.mp3", size = 1) { byteArrayOf(1) })
            assertEquals(expected, model.banner.value?.failure)
            model.dismissBanner()
        }

        assertNull(model.upload.value)
    }

    @Test
    fun aRecordingIsUploadedAsM4aAndTheTemporaryFileIsRemoved() = runTest {
        val model = model()
        model.recorder.start()
        model.recorder.stop()
        val file = model.recorder.file!!
        assertTrue(file.exists())

        val id = model.addRecording("  Welkomstbericht\n")

        assertEquals("s-new", id)
        assertEquals(listOf(Triple("Welkomstbericht", "opname.m4a", 3)), service.uploads)
        assertFalse(file.exists())
        assertEquals(5.0, model.durations.value["s-new"]!!, 0.0)
    }

    @Test
    fun aFailedRecordingUploadKeepsTheRecordingForANewTry() = runTest {
        val model = model()
        model.recorder.start()
        model.recorder.stop()
        val file = model.recorder.file!!
        service.failures["upload"] = ArrayDeque(listOf(ApiException.Transport("offline")))

        assertNull(model.addRecording("Bericht"))
        assertTrue(file.exists())
        assertEquals(SoundFailure.Offline, model.banner.value?.failure)

        assertEquals("s-new", model.addRecording("Bericht"))
        assertFalse(file.exists())
    }

    @Test
    fun deletingASoundThatIsInUseExplainsWhere() = runTest {
        val model = model()
        model.load()
        val places = listOf(ApiErrorPlace("menu_greeting", "Support"))
        service.failures["delete"] = ArrayDeque(listOf(ApiException.InUse(places)))

        model.delete(model.sound("s-2")!!)

        assertEquals(SoundDeleteBlock("Welkom", places), model.deleteBlock.value)
        assertNotNull(model.sound("s-2"))
    }

    @Test
    fun deletingAnUnusedSoundRemovesItAndRenamingReloads() = runTest {
        val model = model()
        model.load()

        model.delete(model.sound("s-1")!!)
        assertNull(model.sound("s-1"))

        assertTrue(model.rename(model.sound("s-2")!!, "  Welkomstbericht  "))
        assertEquals("Welkomstbericht", model.name("s-2"))
        // Same name: nothing sent.
        val before = service.count("rename")
        assertTrue(model.rename(model.sound("s-2")!!, "Welkomstbericht"))
        assertEquals(before, service.count("rename"))
    }

    @Test
    fun listeningDownloadsTheAudioAndASoundWithoutACopyIsNotPlayable() = runTest {
        val model = model()
        model.load()

        model.togglePlay(model.sound("s-2")!!)

        assertEquals(1, service.count("audio"))
        assertEquals(3, player.loaded!!.length().toInt())
        assertTrue(model.isPlaying(model.sound("s-2")!!))

        // A second tap pauses what plays instead of downloading again.
        model.togglePlay(model.sound("s-2")!!)
        assertEquals(1, service.count("audio"))
        assertFalse(model.isPlaying(model.sound("s-2")!!))

        service.items = mutableListOf(Sound("old", "Oud", playable = false))
        model.load()
        model.play(model.sound("old")!!)
        assertEquals(1, service.count("audio"))
    }

    @Test
    fun nothingPlaysDuringACall() = runTest {
        val model = model()
        model.load()
        callActive = true

        model.play(model.sound("s-2")!!)

        assertEquals(0, service.count("audio"))
        assertEquals(SoundFailure.CallInProgress, model.banner.value?.failure)
    }

    @Test
    fun durationsAreRememberedPerSound() = runTest {
        val model = model()
        model.load()

        model.noteDuration("s-2", 12.5)
        model.noteDuration(SoundsModel.LOCAL_PREVIEW_ID, 3.0)

        assertEquals(12.5, model.duration(model.sound("s-2")!!)!!, 0.0)
        assertNull(model.duration(model.sound("s-1")!!))
        assertNull(model.durations.value[SoundsModel.LOCAL_PREVIEW_ID])
    }

    @Test
    fun namesAreOneLineAndAtMost100Characters() {
        assertEquals("Eén twee", SoundsModel.cleanName("  Eén\ntwee  "))
        assertEquals(100, SoundsModel.cleanName("x".repeat(250)).length)
    }

    @Test
    fun theDefaultRecordingNameCarriesTheDate() = runTest {
        val model = model()

        assertEquals("Opname 9 okt 14:32", model.defaultRecordingName("Opname") { "9 okt ${"%02d:%02d".format(it.hour, it.minute)}" })
    }
}
