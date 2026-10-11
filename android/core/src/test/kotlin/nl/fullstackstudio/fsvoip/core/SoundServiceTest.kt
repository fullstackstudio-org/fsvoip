// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.core

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The sounds service (upload fields, failure mapping, the audio route). */
class SoundServiceTest {
    private val logger = FsLogger("test", MemoryLogSink())

    private fun service(transport: FakeTransport) =
        LiveSoundService(FsVoipApiClient("https://example.test/api/voip-app/v1", transport = transport, userAgent = "FSVoip/2.0 (test)", logger = logger).authenticated(Secret("fss_vapp_t")))

    @Test
    fun uploadSendsExactlyTheNameAndFilePartsAndReturnsTheId() = runTest {
        val transport = FakeTransport { response(200, Fixtures.text("sound-upload-response.json")) }
        val progress = mutableListOf<Double>()

        val id = service(transport).upload("Welkom", "opname.m4a", byteArrayOf(0, 0, 0, 0x20, 0x66, 0x74, 0x79, 0x70)) { progress += it }

        assertEquals("a1b2c3d4-4444-4a2b-8c3d-4e5f6a7b8c9d", id)

        val request = transport.requests.single()
        assertEquals("POST", request.method)
        assertTrue(request.url.endsWith("/pbx/sounds"))
        assertEquals("Bearer fss_vapp_t", request.headers["Authorization"])

        val text = request.body!!.toString(Charsets.ISO_8859_1)
        val boundary = request.contentType.removePrefix("multipart/form-data; boundary=")
        assertTrue(request.contentType.startsWith("multipart/form-data; boundary="))
        assertEquals(2, text.split("--$boundary\r\n").size - 1)
        assertTrue(text.contains("name=\"name\"\r\n\r\nWelkom\r\n"))
        assertTrue(text.contains("name=\"file\"; filename=\"opname.m4a\"\r\nContent-Type: audio/mp4\r\n\r\n"))
        assertTrue(text.endsWith("--$boundary--\r\n"))
        // The progress callback travels with the request to the transport (the fake does not call it).
        assertTrue(request.progress != null)
    }

    @Test
    fun theFileNameCannotBreakOutOfTheHeader() = runTest {
        val transport = FakeTransport { response(200, Fixtures.text("sound-upload-response.json")) }

        service(transport).upload("x", "a\"b\r\nX-Evil: 1.wav", byteArrayOf(1), null)

        val text = transport.requests.single().body!!.toString(Charsets.ISO_8859_1)
        assertFalse(text.contains("\r\nX-Evil"))
        assertTrue(text.contains("filename=\"abX-Evil: 1.wav\""))
    }

    @Test
    fun uploadFailuresBecomeTheirSoundFailure() = runTest {
        val cases = listOf(
            Triple(400, "error-invalid-audio.json", SoundFailure.InvalidAudio),
            Triple(413, "error-too-large.json", SoundFailure.TooLarge),
            Triple(409, "error-too-many.json", SoundFailure.TooMany),
        )

        for ((status, name, expected) in cases) {
            val transport = FakeTransport { response(status, Fixtures.text(name)) }

            try {
                service(transport).upload("x", "x.mp3", byteArrayOf(1), null)
                fail("expected $expected")
            } catch (error: ApiException) {
                assertEquals(name, expected, SoundFailure.classify(error))
            }
        }
    }

    @Test
    fun aFileAboveTheLimitIsTooLargeWithoutARequest() = runTest {
        val transport = FakeTransport { response(200) }

        try {
            service(transport).upload("x", "x.wav", ByteArray(SoundUpload.MAX_BYTES + 1), null)
            fail("expected TooLarge")
        } catch (error: ApiException) {
            assertEquals(SoundFailure.TooLarge, SoundFailure.classify(error))
        }

        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun deletingASoundInUseCarriesThePlaces() = runTest {
        val transport = FakeTransport { response(409, Fixtures.text("error-in-use.json")) }

        try {
            service(transport).delete("a1b2c3d4-1111-4a2b-8c3d-4e5f6a7b8c9d")
            fail("expected InUse")
        } catch (error: ApiException) {
            val failure = SoundFailure.classify(error) as SoundFailure.InUse
            assertTrue(failure.places.isNotEmpty())
        }
    }

    @Test
    fun otherFailuresAreSortedIntoWhatTheUserCanDo() {
        assertEquals(SoundFailure.AccessDenied, SoundFailure.classify(ApiException.Forbidden()))
        assertEquals(SoundFailure.Revoked, SoundFailure.classify(ApiException.Unauthorized()))
        assertEquals(SoundFailure.ReadOnly, SoundFailure.classify(ApiException.ReadOnly()))
        assertEquals(SoundFailure.ReadOnly, SoundFailure.classify(ApiException.Conflict("read_only")))
        assertEquals(SoundFailure.NotFound, SoundFailure.classify(ApiException.NotFound()))
        assertEquals(SoundFailure.RateLimited(30), SoundFailure.classify(ApiException.RateLimited(30)))
        assertEquals(SoundFailure.Offline, SoundFailure.classify(ApiException.Transport("offline")))
        assertEquals(SoundFailure.TooLarge, SoundFailure.classify(ApiException.PayloadTooLarge()))
        assertEquals(SoundFailure.Other, SoundFailure.classify(java.io.IOException("disk")))
        assertTrue(SoundFailure.Offline.isRetryable)
        assertFalse(SoundFailure.InvalidAudio.isRetryable)
    }

    @Test
    fun theAudioRouteSendsTheBearerInTheHeaderNeverTheUrl() = runTest {
        val transport = FakeTransport { HttpResponse(200, mapOf("Content-Type" to "audio/wav"), byteArrayOf(1, 2, 3)) }

        val bytes = service(transport).audio("a1b2c3d4-1111-4a2b-8c3d-4e5f6a7b8c9d")

        assertEquals(3, bytes.size)
        val request = transport.requests.single()
        assertTrue(request.url.endsWith("/pbx/sounds/a1b2c3d4-1111-4a2b-8c3d-4e5f6a7b8c9d/audio"))
        assertFalse(request.url.contains("fss_vapp_"))
        assertEquals("Bearer fss_vapp_t", request.headers["Authorization"])
        assertEquals("audio/*", request.headers["Accept"])
    }

    @Test
    fun contentTypeFollowsTheExtension() {
        assertEquals("audio/wav", SoundUpload.contentType("a.WAV"))
        assertEquals("audio/mpeg", SoundUpload.contentType("a.mp3"))
        assertEquals("audio/mp4", SoundUpload.contentType("a.m4a"))
        assertEquals("application/octet-stream", SoundUpload.contentType("a.txt"))
        assertEquals("application/octet-stream", SoundUpload.contentType("noextension"))
    }
}
