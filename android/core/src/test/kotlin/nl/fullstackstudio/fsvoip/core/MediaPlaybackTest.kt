// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.core

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.delay
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FakeBackend : AudioBackend {
    override var onEvent: ((AudioBackendEvent) -> Unit)? = null
    val loaded = mutableListOf<AudioSource>()
    val played = mutableListOf<Double>()
    val rates = mutableListOf<Double>()
    val seeks = mutableListOf<Double>()
    var pauses = 0
    var stops = 0

    override fun load(source: AudioSource) { loaded += source }
    override fun play(rate: Double) { played += rate }
    override fun pause() { pauses++ }
    override fun seek(seconds: Double) { seeks += seconds }
    override fun setRate(rate: Double) { rates += rate }
    override fun stop() { stops++ }

    fun emit(event: AudioBackendEvent) = onEvent?.invoke(event)

    val lastFile: File? get() = (loaded.lastOrNull() as? AudioSource.LocalFile)?.file
    val lastWasStream: Boolean get() = loaded.lastOrNull() is AudioSource.Stream
}

private fun wav() = MediaDownload(byteArrayOf(0x52, 0x49, 0x46, 0x46, 0, 0, 0, 0, 0x57, 0x41, 0x56, 0x45), "audio/wav")

private fun request() = MediaRequest("voicemail/b/r/audio", "https://example.invalid/api/voip-app/v1/voicemail/b/r/audio") { mapOf("Authorization" to "Bearer fss_vapp_secret") }

class AudioStreamerTest {
    private lateinit var directory: File
    private lateinit var cache: MediaCache
    private lateinit var backend: FakeBackend
    private var callActive = false
    private val testScope = TestScope()

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("fsvoip-media").toFile()
        cache = MediaCache(directory, maxBytes = 1_000)
        backend = FakeBackend()
        callActive = false
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    private fun streamer(streamFirst: Boolean = true): AudioStreamer {
        val dispatcher = StandardTestDispatcher(testScope.testScheduler)

        return AudioStreamer(backend, cache, CoroutineScope(dispatcher), isCallActive = { callActive }, streamFirst = streamFirst, io = dispatcher)
    }

    private val ok: suspend (MediaRequest) -> MediaDownload = { wav() }

    // MARK: Loading and playing

    @Test
    fun loadsThenPlaysWhenTheBackendIsReady() {
        val player = streamer()
        player.play("a", AudioSource.Stream(request()), ok)

        assertEquals(PlayerState.Loading, player.state.value)
        assertEquals("a", player.currentId.value)
        assertTrue(backend.lastWasStream)

        backend.emit(AudioBackendEvent.Ready(21.0))

        assertEquals(PlayerState.Playing, player.state.value)
        assertEquals(21.0, player.duration.value)
        assertEquals(listOf(1.0), backend.played)

        backend.emit(AudioBackendEvent.Progress(5.5))
        assertEquals(5.5, player.position.value, 0.0)

        backend.emit(AudioBackendEvent.Ended)
        assertEquals(PlayerState.Ended, player.state.value)
        assertEquals(21.0, player.position.value, 0.0)
    }

    @Test
    fun pauseResumeSeekAndSpeed() {
        val player = streamer()
        player.play("a", AudioSource.Stream(request()), ok)
        backend.emit(AudioBackendEvent.Ready(60.0))

        player.cycleSpeed()
        assertEquals(PlaybackSpeed.FAST, player.speed.value)
        assertEquals(listOf(1.5), backend.rates)

        player.pause()
        assertEquals(PlayerState.Paused, player.state.value)
        assertEquals(1, backend.pauses)

        player.seek(100.0)
        assertEquals("clamped to the length", 60.0, player.position.value, 0.0)
        assertEquals(listOf(60.0), backend.seeks)

        player.seek(-5.0)
        assertEquals(0.0, player.position.value, 0.0)

        player.resume()
        assertEquals(PlayerState.Playing, player.state.value)
        assertEquals("a new play uses the chosen speed", 1.5, backend.played.last(), 0.0)

        player.cycleSpeed()
        player.cycleSpeed()
        assertEquals("1x, 1.5x, 2x and around", PlaybackSpeed.NORMAL, player.speed.value)
    }

    @Test
    fun skipMovesRelativeToThePosition() {
        val player = streamer()
        player.play("a", AudioSource.Stream(request()), ok)
        backend.emit(AudioBackendEvent.Ready(60.0))
        backend.emit(AudioBackendEvent.Progress(30.0))

        player.skip(10.0)
        assertEquals(40.0, player.position.value, 0.0)
        player.skip(-100.0)
        assertEquals(0.0, player.position.value, 0.0)
    }

    @Test
    fun togglePlayPauseRestartsAnEndedItem() {
        val player = streamer()
        player.play("a", AudioSource.Stream(request()), ok)
        backend.emit(AudioBackendEvent.Ready(10.0))
        backend.emit(AudioBackendEvent.Ended)

        player.togglePlayPause()

        assertEquals(PlayerState.Playing, player.state.value)
        assertEquals(0.0, backend.seeks.last(), 0.0)
    }

    @Test
    fun stopForgetsEverything() {
        val player = streamer()
        player.play("a", AudioSource.Stream(request()), ok)
        backend.emit(AudioBackendEvent.Ready(10.0))
        player.stop()

        assertEquals(PlayerState.Idle, player.state.value)
        assertNull(player.currentId.value)
        assertTrue(backend.stops > 0)

        backend.emit(AudioBackendEvent.Progress(3.0))
        assertEquals("events of a stopped item are ignored", 0.0, player.position.value, 0.0)
    }

    @Test
    fun anInterruptionPauses() {
        val player = streamer()
        player.play("a", AudioSource.Stream(request()), ok)
        backend.emit(AudioBackendEvent.Ready(10.0))
        backend.emit(AudioBackendEvent.Interrupted)

        assertEquals(PlayerState.Paused, player.state.value)
    }

    // MARK: Calls

    @Test
    fun nothingPlaysDuringACall() {
        callActive = true
        val player = streamer()
        player.play("a", AudioSource.Stream(request()), ok)

        assertEquals(PlayerState.Failed(MediaFailure.CallInProgress), player.state.value)
        assertTrue("the audio is not even requested", backend.loaded.isEmpty())
    }

    @Test
    fun aCallThatStartsPausesWhatPlays() {
        val player = streamer()
        player.play("a", AudioSource.Stream(request()), ok)
        backend.emit(AudioBackendEvent.Ready(10.0))

        callActive = true
        player.callActivityChanged()
        assertEquals(PlayerState.Paused, player.state.value)

        player.resume()
        assertEquals("resuming over a call is refused", PlayerState.Failed(MediaFailure.CallInProgress), player.state.value)
    }

    @Test
    fun aCallThatStartsWhileLoadingStopsTheLoad() {
        val player = streamer()
        player.play("a", AudioSource.Stream(request()), ok)

        callActive = true
        player.callActivityChanged()

        assertEquals(PlayerState.Failed(MediaFailure.CallInProgress), player.state.value)
        backend.emit(AudioBackendEvent.Ready(10.0))
        assertEquals("a late 'ready' does not start the audio", PlayerState.Failed(MediaFailure.CallInProgress), player.state.value)
        assertTrue(backend.played.isEmpty())
    }

    // MARK: Download (the Android way) and the fallback of a stream

    @Test
    fun theAndroidWayDownloadsFirstAndPlaysTheFile() {
        val player = streamer(streamFirst = false)
        player.play("box/ref", AudioSource.Stream(request()), ok)

        assertEquals(PlayerState.Loading, player.state.value)
        assertTrue(player.usesDownload.value)
        assertTrue("nothing is streamed", backend.loaded.isEmpty())

        testScope.advanceUntilIdle()

        val file = backend.lastFile
        assertNotNull(file)
        assertEquals("wav", file!!.extension)
        assertTrue(file.exists())

        backend.emit(AudioBackendEvent.Ready(12.0))
        assertEquals(PlayerState.Playing, player.state.value)
    }

    @Test
    fun aFailedStreamIsRetriedAsADownloadAndThenPlays() {
        val player = streamer(streamFirst = true)
        player.play("box/ref", AudioSource.Stream(request()), ok)
        backend.emit(AudioBackendEvent.Failed)

        assertEquals(PlayerState.Loading, player.state.value)
        assertTrue(player.usesDownload.value)

        testScope.advanceUntilIdle()

        assertEquals("wav", backend.lastFile?.extension)
        backend.emit(AudioBackendEvent.Ready(12.0))
        assertEquals(PlayerState.Playing, player.state.value)
    }

    @Test
    fun theDownloadSaysWhyItFailed() {
        val cases: List<Pair<Exception, MediaFailure>> = listOf(
            ApiException.Gone() to MediaFailure.Gone,
            ApiException.Forbidden() to MediaFailure.AccessDenied,
            ApiException.RateLimited(12) to MediaFailure.RateLimited(12),
            ApiException.Transport("offline") to MediaFailure.Offline,
            ApiException.NotFound() to MediaFailure.NotFound,
            ApiException.Unavailable(true, null) to MediaFailure.Unavailable,
            ApiException.Unauthorized() to MediaFailure.Revoked,
        )

        for ((error, expected) in cases) {
            for (streamFirst in listOf(false, true)) {
                backend = FakeBackend()
                val player = streamer(streamFirst)
                player.play("x-$expected", AudioSource.Stream(request())) { throw error }
                if (streamFirst) backend.emit(AudioBackendEvent.Failed)
                testScope.advanceUntilIdle()

                assertEquals("$error", PlayerState.Failed(expected), player.state.value)
            }
        }
    }

    @Test
    fun aFileThatCannotBePlayedIsUnplayableAndNotRetriedForever() {
        val player = streamer(streamFirst = true)
        player.play("a", AudioSource.Stream(request()), ok)
        backend.emit(AudioBackendEvent.Failed)
        testScope.advanceUntilIdle()
        backend.emit(AudioBackendEvent.Failed)

        assertEquals(PlayerState.Failed(MediaFailure.Unplayable), player.state.value)
        assertEquals("one stream and one file, no loop", 2, backend.loaded.size)
    }

    @Test
    fun aDownloadedFileThatFailsToPlayIsUnplayable() {
        val player = streamer(streamFirst = false)
        player.play("a", AudioSource.Stream(request()), ok)
        testScope.advanceUntilIdle()
        backend.emit(AudioBackendEvent.Failed)

        assertEquals(PlayerState.Failed(MediaFailure.Unplayable), player.state.value)
        assertEquals(1, backend.loaded.size)
    }

    @Test
    fun aLocalFileNeverAsksTheServer() {
        val player = streamer()
        player.play("a", AudioSource.LocalFile(File("/tmp/x.wav"))) { error("no download") }
        backend.emit(AudioBackendEvent.Failed)

        assertEquals(PlayerState.Failed(MediaFailure.Unplayable), player.state.value)
    }

    @Test
    fun anItemInTheCacheIsPlayedWithoutAskingTheServer() {
        val player = streamer(streamFirst = false)
        player.play("k", AudioSource.Stream(request()), ok)
        testScope.advanceUntilIdle()

        player.play("k", AudioSource.Stream(request())) { error("cached") }

        assertNotNull(backend.lastFile)
        assertEquals(2, backend.loaded.size)
        assertEquals(PlayerState.Loading, player.state.value)
    }

    @Test
    fun anOlderLoadDoesNotOverwriteANewerOne() {
        val player = streamer(streamFirst = false)
        player.play("slow", AudioSource.Stream(request())) {
            delay(80)
            wav()
        }
        player.play("next", AudioSource.LocalFile(File("/tmp/next.wav")), ok)
        testScope.advanceUntilIdle()

        assertEquals("next", player.currentId.value)
        assertEquals("the slow download never reaches the backend", 1, backend.loaded.size)
        assertEquals(0, cache.totalBytes().coerceAtMost(1) * 0)
    }

    @Test
    fun stoppingWhileTheDownloadRunsDropsIt() {
        val player = streamer(streamFirst = false)
        player.play("slow", AudioSource.Stream(request())) {
            delay(80)
            wav()
        }
        player.stop()
        testScope.advanceUntilIdle()

        assertTrue(backend.loaded.isEmpty())
        assertEquals(PlayerState.Idle, player.state.value)
    }
}

class MediaCacheTest {
    private lateinit var directory: File

    @Before
    fun setUp() {
        directory = File(Files.createTempDirectory("fsvoip-cache").toFile(), "media")
    }

    @After
    fun tearDown() {
        directory.parentFile.deleteRecursively()
    }

    private fun blob(size: Int, type: String = "audio/mpeg") = MediaDownload(ByteArray(size) { 1 }, type)

    @Test
    fun storesUnderAHashedNameWithTheRightExtension() {
        val cache = MediaCache(directory, 1_000)
        val file = cache.store(blob(10), "caller-box/ref")

        assertEquals("mp3", file.extension)
        assertFalse("nothing readable in the name", file.name.contains("box"))
        assertEquals(file, cache.cachedFile("caller-box/ref"))
        assertNull(cache.cachedFile("other"))
    }

    @Test
    fun theLeastRecentlyUsedGoFirst() {
        val cache = MediaCache(directory, 250)
        val first = cache.store(blob(100), "1")
        first.setLastModified(System.currentTimeMillis() - 300_000)
        val second = cache.store(blob(100), "2")
        second.setLastModified(System.currentTimeMillis() - 200_000)

        // Hearing the first again makes it the freshest.
        assertNotNull(cache.cachedFile("1"))

        cache.store(blob(100), "3")

        assertNotNull(cache.cachedFile("1"))
        assertNull("the least recently used one went", cache.cachedFile("2"))
        assertNotNull(cache.cachedFile("3"))
        assertTrue(cache.totalBytes() <= 250)
    }

    @Test
    fun theFileJustStoredAlwaysStaysEvenWhenItAloneIsTooBig() {
        val cache = MediaCache(directory, 50)
        cache.store(blob(40), "old")
        cache.store(blob(200), "big")

        assertNotNull(cache.cachedFile("big"))
        assertNull(cache.cachedFile("old"))
    }

    @Test
    fun clearRemovesEverything() {
        val cache = MediaCache(directory)
        cache.store(blob(10), "a")
        cache.clear()

        assertEquals(0, cache.totalBytes())
        assertNull(cache.cachedFile("a"))
    }

    @Test
    fun fileTypeFromContentTypeOrFirstBytes() {
        assertEquals(AudioFileType.WAV, AudioFileType.detect("audio/x-wav", ByteArray(0)))
        assertEquals(AudioFileType.MP3, AudioFileType.detect("audio/mpeg", ByteArray(0)))
        assertEquals(AudioFileType.M4A, AudioFileType.detect("audio/mp4", ByteArray(0)))
        assertEquals(AudioFileType.MP3, AudioFileType.detect("application/octet-stream", byteArrayOf(0x49, 0x44, 0x33, 4, 0, 0, 0, 0, 0, 0, 0, 0)))
        assertEquals(AudioFileType.M4A, AudioFileType.detect(null, byteArrayOf(0, 0, 0, 0x20, 0x66, 0x74, 0x79, 0x70, 0, 0, 0, 0)))
        assertEquals(AudioFileType.WAV, AudioFileType.detect(null, byteArrayOf(0x52, 0x49, 0x46, 0x46, 0, 0, 0, 0, 0x57, 0x41, 0x56, 0x45)))
        assertEquals(AudioFileType.MP3, AudioFileType.detect(null, byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte())))
    }
}

class MediaApiTest {
    private val token = Secret("fss_vapp_" + "x".repeat(43))
    private val logger = FsLogger("test", MemoryLogSink())

    private fun api(vararg replies: HttpResponse): Pair<FsVoipApiClient, FakeTransport> {
        val queue = replies.toMutableList()
        val transport = FakeTransport { queue.removeAt(0) }

        return FsVoipApiClient("https://example.test/api/voip-app/v1", transport = transport, userAgent = "FSVoip/1.0 (test)", logger = logger).authenticated(token) to transport
    }

    @Test
    fun callsAndVoicemailQueries() = runTest {
        val (client, transport) = api(
            response(200, Fixtures.text("calls-page.json")),
            response(200, Fixtures.text("calls-page.json")),
            response(200, Fixtures.text("voicemail-page.json")),
            response(200, Fixtures.text("ok-response.json")),
        )
        val base = "https://example.test/api/voip-app/v1"

        client.calls()
        client.calls(month = "2026-09", locale = "en")
        client.voicemail(box = "7a1d9c3e-5b2f-4e8a-9c01-6d3e8f2a4b57", locale = "nl")
        client.deleteVoicemail("7a1d9c3e-5b2f-4e8a-9c01-6d3e8f2a4b57", "v1.Zm9vYmFy")

        assertEquals("$base/calls", transport.requests[0].url)
        assertEquals("$base/calls?month=2026-09&locale=en", transport.requests[1].url)
        assertEquals("$base/voicemail?box=7a1d9c3e-5b2f-4e8a-9c01-6d3e8f2a4b57&locale=nl", transport.requests[2].url)
        assertEquals("DELETE", transport.requests[3].method)
        assertEquals("$base/voicemail/7a1d9c3e-5b2f-4e8a-9c01-6d3e8f2a4b57/v1.Zm9vYmFy", transport.requests[3].url)
        assertTrue(transport.requests.all { it.headers["Authorization"] == "Bearer ${token.reveal()}" })
    }

    @Test
    fun theBearerIsInTheHeadersAndNeverInTheUrl() {
        val (client, _) = api()
        val recording = client.recordingMedia("11111111-aaaa-4bbb-8ccc-000000000001")
        val voicemail = client.voicemailMedia("7a1d9c3e-5b2f-4e8a-9c01-6d3e8f2a4b57", "v1.Zm9vYmFy")

        assertEquals("https://example.test/api/voip-app/v1/calls/11111111-aaaa-4bbb-8ccc-000000000001/recording", recording.url)
        assertEquals("https://example.test/api/voip-app/v1/voicemail/7a1d9c3e-5b2f-4e8a-9c01-6d3e8f2a4b57/v1.Zm9vYmFy/audio", voicemail.url)
        assertEquals("Bearer ${token.reveal()}", voicemail.headers()["Authorization"])
        assertFalse(voicemail.url.contains("fss_vapp_"))
        assertFalse(voicemail.url.contains("?"))

        // Not in a print-out either.
        for (text in listOf(recording.toString(), voicemail.toString())) {
            assertFalse(text.contains("fss_vapp_"))
            assertFalse(text.contains("Bearer"))
        }
    }

    @Test
    fun aSealedReferenceIsOneUrlSegment() {
        val (client, _) = api()
        val media = client.voicemailMedia("box", "v1.a_b-c d")

        assertEquals("https://example.test/api/voip-app/v1/voicemail/box/v1.a_b-c%20d/audio", media.url)
    }

    @Test
    fun withoutADeviceTokenThereIsNoMediaRequest() {
        val client = FsVoipApiClient("https://example.test/v1", logger = logger)

        try {
            client.recordingMedia("x")
            org.junit.Assert.fail()
        } catch (_: ApiException.MissingDeviceToken) {
        }
    }

    @Test
    fun downloadMapsTheStatusAndKeepsTheContentType() = runTest {
        val (client, transport) = api(
            HttpResponse(200, mapOf("Content-Type" to "audio/wav"), byteArrayOf(1, 2, 3)),
            response(410, Fixtures.text("error-gone.json")),
            response(429, """{"error":"rate_limited"}""", mapOf("Retry-After" to "7")),
            response(403, """{"error":"forbidden"}"""),
        )
        val media = client.recordingMedia("11111111-aaaa-4bbb-8ccc-000000000001")

        val download = client.downloadMedia(media)
        assertEquals(listOf<Byte>(1, 2, 3), download.data.toList())
        assertEquals("audio/wav", download.contentType)
        assertEquals("Bearer ${token.reveal()}", transport.requests.first().headers["Authorization"])
        assertEquals("GET", transport.requests.first().method)
        assertTrue(transport.requests.first().headers["Accept"]!!.startsWith("audio/"))

        try { client.downloadMedia(media); org.junit.Assert.fail() } catch (_: ApiException.Gone) {}
        try { client.downloadMedia(media); org.junit.Assert.fail() } catch (error: ApiException.RateLimited) { assertEquals(7, error.retryAfterSeconds) }
        try { client.downloadMedia(media); org.junit.Assert.fail() } catch (_: ApiException.Forbidden) {}
    }

    @Test
    fun failureClassification() {
        assertEquals(MediaFailure.AccessDenied, MediaFailure.classify(ApiException.Forbidden()))
        assertEquals(MediaFailure.Gone, MediaFailure.classify(ApiException.Gone()))
        assertEquals(MediaFailure.ReadOnly, MediaFailure.classify(ApiException.ReadOnly()))
        assertEquals(MediaFailure.ReadOnly, MediaFailure.classify(ApiException.Conflict("read_only")))
        assertEquals(MediaFailure.Other, MediaFailure.classify(ApiException.Conflict("busy")))
        assertEquals(MediaFailure.Offline, MediaFailure.classify(ApiException.Transport("x")))
        assertEquals(MediaFailure.Revoked, MediaFailure.classify(ApiException.Unauthorized()))
        assertEquals(MediaFailure.NotFound, MediaFailure.classify(ApiException.NotFound()))
        assertEquals(MediaFailure.Unavailable, MediaFailure.classify(ApiException.UnexpectedStatus(500)))
        assertEquals(MediaFailure.Other, MediaFailure.classify(IllegalStateException()))
        assertTrue(MediaFailure.RateLimited(5).isRetryable)
        assertTrue(MediaFailure.Offline.isRetryable)
        assertFalse(MediaFailure.Gone.isRetryable)
        assertFalse(MediaFailure.AccessDenied.isRetryable)
        assertFalse(MediaFailure.Unplayable.isRetryable)
    }
}
