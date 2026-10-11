// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.core

import java.io.File
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeRecorderBackend : RecorderBackend {
    override var onInterrupted: (() -> Unit)? = null
    var permission = true
    var failToStart = false
    var time = 0.0
    val started = mutableListOf<File>()
    var stops = 0

    override suspend fun requestPermission() = permission

    override fun start(file: File) {
        if (failToStart) throw java.io.IOException("no")

        file.writeBytes(byteArrayOf(1, 2, 3))
        started += file
    }

    override fun currentTime() = time

    override fun stop(): Double {
        stops++
        return time
    }
}

class AudioRecorderSessionTest {
    private val backend = FakeRecorderBackend()
    private val directory = File(System.getProperty("java.io.tmpdir"), "fsvoip-rec-test-${System.nanoTime()}")
    private var callActive = false

    @After
    fun cleanUp() {
        directory.deleteRecursively()
    }

    // A clock that never ticks by itself: the tests drive tick().
    private fun session(scope: kotlinx.coroutines.CoroutineScope) =
        AudioRecorderSession(backend, directory, scope, isCallActive = { callActive }, sleep = { awaitCancellation() })

    @Test
    fun startRecordStopKeepsTheRecording() = runTest {
        val recorder = session(backgroundScope)

        recorder.start()
        assertEquals(AudioRecorderSession.State.Recording, recorder.state.value)
        val file = recorder.file!!
        assertEquals("m4a", file.extension)
        assertTrue(file.exists())

        backend.time = 12.5
        recorder.tick()
        assertEquals(12.5, recorder.elapsed.value, 0.0)

        recorder.stop()
        assertEquals(AudioRecorderSession.State.Recorded(12.5), recorder.state.value)
        assertEquals(1, backend.stops)
        assertTrue(file.exists())

        assertEquals(file, recorder.takeFile())
        assertEquals(AudioRecorderSession.State.Idle, recorder.state.value)
    }

    @Test
    fun recordingDuringACallIsRefusedAndNeverStartsTheMicrophone() = runTest {
        callActive = true
        val recorder = session(backgroundScope)

        recorder.start()

        assertEquals(AudioRecorderSession.State.Failed(RecorderFailure.CALL_IN_PROGRESS), recorder.state.value)
        assertTrue(backend.started.isEmpty())
    }

    @Test
    fun aCallThatStartsWhileRecordingStopsTheRecording() = runTest {
        val recorder = session(backgroundScope)
        recorder.start()
        backend.time = 4.0

        callActive = true
        recorder.callActivityChanged()

        assertEquals(AudioRecorderSession.State.Recorded(4.0), recorder.state.value)
        assertEquals(1, backend.stops)
    }

    @Test
    fun aMicrophoneThatIsNotAllowedFailsCleanly() = runTest {
        backend.permission = false
        val recorder = session(backgroundScope)

        recorder.start()

        assertEquals(AudioRecorderSession.State.Failed(RecorderFailure.PERMISSION_DENIED), recorder.state.value)
        assertTrue(backend.started.isEmpty())
        assertNull(recorder.file)
    }

    @Test
    fun theLimitOfFiveMinutesStopsTheRecording() = runTest {
        val recorder = session(backgroundScope)
        recorder.start()

        backend.time = AudioRecorderSession.MAX_DURATION + 7
        recorder.tick()

        assertEquals(AudioRecorderSession.State.Recorded(AudioRecorderSession.MAX_DURATION), recorder.state.value)
        assertEquals(300.0, AudioRecorderSession.MAX_DURATION, 0.0)
    }

    @Test
    fun aRecordingUnderASecondIsThrownAway() = runTest {
        val recorder = session(backgroundScope)
        recorder.start()
        val file = recorder.file!!

        backend.time = 0.4
        recorder.stop()

        assertEquals(AudioRecorderSession.State.Failed(RecorderFailure.TOO_SHORT), recorder.state.value)
        assertFalse(file.exists())
    }

    @Test
    fun cancelAndDiscardRemoveTheTemporaryFile() = runTest {
        val recorder = session(backgroundScope)
        recorder.start()
        val file = recorder.file!!

        recorder.cancel()

        assertEquals(AudioRecorderSession.State.Idle, recorder.state.value)
        assertFalse(file.exists())

        recorder.start()
        backend.time = 3.0
        recorder.stop()
        val second = recorder.file!!
        recorder.discard()
        assertFalse(second.exists())
        assertEquals(AudioRecorderSession.State.Idle, recorder.state.value)
    }

    @Test
    fun aStartThatFailsLeavesNothingBehind() = runTest {
        backend.failToStart = true
        val recorder = session(backgroundScope)

        recorder.start()

        assertEquals(AudioRecorderSession.State.Failed(RecorderFailure.COULD_NOT_START), recorder.state.value)
        assertNull(recorder.file)
    }

    @Test
    fun theSystemTakingTheMicrophoneStopsAndKeepsWhatWasRecorded() = runTest {
        val recorder = session(backgroundScope)
        recorder.start()
        backend.time = 6.0

        backend.onInterrupted!!.invoke()

        assertEquals(AudioRecorderSession.State.Recorded(6.0), recorder.state.value)
    }

    @Test
    fun anEarlierRecordingIsRemovedWhenANewOneStarts() = runTest {
        val recorder = session(backgroundScope)
        recorder.start()
        backend.time = 3.0
        recorder.stop()
        val first = recorder.file!!

        recorder.start()

        assertFalse(first.exists())
        assertNotNull(recorder.file)
        assertNotEquals(first, recorder.file)
    }

    @Test
    fun staleFilesOfAnEarlierRunArePurged() {
        directory.mkdirs()
        File(directory, "rec-old.m4a").writeBytes(byteArrayOf(1))

        AudioRecorderSession.purgeStaleFiles(directory)

        assertFalse(directory.exists())
    }
}
