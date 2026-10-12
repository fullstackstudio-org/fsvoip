// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.pairing

import java.io.File
import nl.fullstackstudio.fsvoip.core.FsLogger
import nl.fullstackstudio.fsvoip.core.FsVoipApiClient
import nl.fullstackstudio.fsvoip.core.HttpRequest
import nl.fullstackstudio.fsvoip.core.HttpResponse
import nl.fullstackstudio.fsvoip.core.HttpTransport
import nl.fullstackstudio.fsvoip.core.MemoryLogSink

fun fixture(name: String): String = File(System.getProperty("fsvoip.fixtures") ?: "../../shared/fixtures", name).readText()

class FakeTransport(var respond: (HttpRequest) -> HttpResponse) : HttpTransport {
    val requests = mutableListOf<HttpRequest>()

    override suspend fun send(request: HttpRequest): HttpResponse {
        requests += request
        return respond(request)
    }
}

fun response(status: Int, body: String = "{}") = HttpResponse(status, emptyMap(), body.toByteArray())

val testSink = MemoryLogSink()

fun client(transport: HttpTransport) = FsVoipApiClient("https://example.test/v1", transport = transport, logger = FsLogger("test", testSink))

const val GOOD_TOKEN = "fss_vpair_FIXTUREpairingFIXTUREpairingFIXTUREpairingF"
