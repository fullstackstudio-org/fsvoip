// SPDX-License-Identifier: AGPL-3.0-or-later
import XCTest
@testable import Core

/// The routes, verbs, bodies and error mapping of the v2 client: the own extension, numbers as a chain, sounds (multipart),
/// the invitation link and parking.
final class V2APIClientTests: XCTestCase {
    private let deviceToken = Secret("fss_vapp_" + String(repeating: "x", count: 43))
    private let base = "https://fullstackstudio.nl/api/voip-app/v1"
    private let number = "d4e5f6a7-b8c9-4d0e-a1f2-b3c4d5e6f7a8"

    private func client(_ replies: [MockTransport.Reply]) -> (FSVoipAPIClient, MockTransport) {
        let transport = MockTransport(replies)
        let api = FSVoipAPIClient(deviceToken: deviceToken, transport: transport, userAgent: "FSVoip/2.0 (test)", logger: FSLogger(category: "test", sink: MemoryLogSink()))

        return (api, transport)
    }

    private func reply(_ status: Int, _ fixture: String, headers: [String: String] = [:]) throws -> MockTransport.Reply {
        .init(status: status, body: try Fixtures.data(fixture), headers: headers)
    }

    private func bodyText(_ request: URLRequest) throws -> String {
        String(decoding: try XCTUnwrap(request.httpBody), as: UTF8.self)
    }

    // MARK: Own extension

    func testSelfExtensionRoutes() async throws {
        let (api, transport) = client([try reply(200, "self-extension"), try reply(200, "self-extension-patch-response")])

        let value = try await api.selfExtension()
        let patched = try await api.updateSelfExtension(SelfExtensionPatch(version: value.version, dnd: true, forwardAlways: .set(.external("+31612345678"))))

        XCTAssertEqual(patched.extensionState?.version, 5)
        XCTAssertEqual(transport.requests.map(\.httpMethod), ["GET", "PATCH"])
        XCTAssertEqual(transport.requests.map { $0.url?.absoluteString }, ["\(base)/me/extension", "\(base)/me/extension"])
        XCTAssertEqual(try bodyText(transport.requests[1]), #"{"dnd":true,"forwardAlways":{"number":"+31612345678","type":"external"},"version":4}"#)
        XCTAssertTrue(transport.requests.allSatisfy { $0.value(forHTTPHeaderField: "Authorization") == "Bearer \(deviceToken.reveal())" })
    }

    // MARK: Numbers as a chain

    func testNumberRoutes() async throws {
        let (api, transport) = client([
            try reply(200, "numbers-page"), try reply(200, "number-chain-simple"), try reply(200, "number-chain-simple"), try reply(200, "number-chain-simple"),
            try reply(200, "number-chain-simple"), try reply(200, "number-chain-simple"), try reply(200, "number-chain-simple"), try reply(200, "number-chain-simple"),
        ])

        _ = try await api.pbxNumbers()
        _ = try await api.numberChain(numberId: number)
        _ = try await api.saveChainStep(numberId: number, ChainNameStep(name: "Hoofdnummer"))
        _ = try await api.saveChainStep(numberId: number, ChainHoursStep(enabled: false))
        _ = try await api.saveChainStep(numberId: number, ChainWelcomeStep(enabled: false))
        _ = try await api.saveChainStep(numberId: number, ChainMenuForwardingStep(repeats: 1))
        _ = try await api.saveChainStep(numberId: number, ChainClosedStep(closed: .hangup))
        _ = try await api.setNumberRecording(numberId: number, NumberRecordingPatch(enabled: false, version: 4))

        XCTAssertEqual(transport.requests.map(\.httpMethod), ["GET", "GET", "PUT", "PUT", "PUT", "PUT", "PUT", "PATCH"])
        XCTAssertEqual(
            transport.requests.map { $0.url?.absoluteString },
            [
                "\(base)/pbx/numbers", "\(base)/pbx/numbers/\(number)/chain", "\(base)/pbx/numbers/\(number)/chain/name", "\(base)/pbx/numbers/\(number)/chain/hours",
                "\(base)/pbx/numbers/\(number)/chain/welcome", "\(base)/pbx/numbers/\(number)/chain/forwarding", "\(base)/pbx/numbers/\(number)/chain/closed",
                "\(base)/pbx/numbers/\(number)/recording",
            ]
        )
        XCTAssertEqual(try bodyText(transport.requests[5]), #"{"kind":"menu","repeats":1}"#)
        XCTAssertEqual(try bodyText(transport.requests[6]), #"{"closed":{"mode":"hangup"}}"#)
        XCTAssertEqual(try bodyText(transport.requests[7]), #"{"enabled":false,"version":4}"#)
    }

    func testAStepWithAReadOnlyFallbackNeverLeavesTheApp() async throws {
        let (api, transport) = client([])

        do {
            _ = try await api.saveChainStep(numberId: number, ChainClosedStep(closed: .other(target: nil)))
            XCTFail("expected an encoding error")
        } catch {
            XCTAssertTrue(error is EncodingError, "\(error)")
        }

        XCTAssertTrue(transport.requests.isEmpty)
    }

    // MARK: Sounds

    func testSoundRoutes() async throws {
        let id = "a1b2c3d4-1111-4a2b-8c3d-4e5f6a7b8c9d"
        let (api, transport) = client([try reply(200, "sounds-page"), try reply(200, "ok-response"), try reply(200, "ok-response")])

        let page = try await api.sounds()
        try await api.renameSound(id: id, name: "Welkomstbericht")
        try await api.deleteSound(id: id)

        XCTAssertEqual(page.sounds.count, 3)
        XCTAssertEqual(transport.requests.map(\.httpMethod), ["GET", "PATCH", "DELETE"])
        XCTAssertEqual(transport.requests.map { $0.url?.absoluteString }, ["\(base)/pbx/sounds", "\(base)/pbx/sounds/\(id)", "\(base)/pbx/sounds/\(id)"])
        XCTAssertEqual(try bodyText(transport.requests[1]), #"{"name":"Welkomstbericht"}"#)
        XCTAssertNil(transport.requests[2].httpBody)

        let media = try api.soundMedia(id: id)
        XCTAssertEqual(media.url.absoluteString, "\(base)/pbx/sounds/\(id)/audio")
        XCTAssertEqual(media.headers()["Authorization"], "Bearer \(deviceToken.reveal())")
        XCTAssertFalse(media.url.absoluteString.contains("fss_vapp_"))
    }

    func testUploadIsMultipartWithExactlyTwoParts() async throws {
        let (api, transport) = client([try reply(200, "sound-upload-response")])
        let audio = Data([0x49, 0x44, 0x33, 0x04, 0x00, 0x00, 0xFF, 0xFB])
        let progress = ProgressLog()

        let response = try await api.uploadSound(name: "Welkom", fileName: "welkom.mp3", data: audio, progress: { progress.add($0) })

        XCTAssertEqual(response.soundId, "a1b2c3d4-4444-4a2b-8c3d-4e5f6a7b8c9d")

        let request = try XCTUnwrap(transport.requests.first)
        XCTAssertEqual(request.httpMethod, "POST")
        XCTAssertEqual(request.url?.absoluteString, "\(base)/pbx/sounds")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer \(deviceToken.reveal())")

        let contentType = try XCTUnwrap(request.value(forHTTPHeaderField: "Content-Type"))
        XCTAssertTrue(contentType.hasPrefix("multipart/form-data; boundary=fsvoip-"), contentType)

        let boundary = String(contentType.dropFirst("multipart/form-data; boundary=".count))
        let body = try XCTUnwrap(request.httpBody)
        let text = String(decoding: body, as: UTF8.self)

        XCTAssertEqual(text.components(separatedBy: "--\(boundary)\r\n").count - 1, 2, "exactly two parts")
        XCTAssertTrue(text.hasSuffix("--\(boundary)--\r\n"))
        XCTAssertTrue(text.contains("Content-Disposition: form-data; name=\"name\"\r\n\r\nWelkom\r\n"))
        XCTAssertTrue(text.contains("Content-Disposition: form-data; name=\"file\"; filename=\"welkom.mp3\"\r\nContent-Type: audio/mpeg\r\n\r\n"))
        XCTAssertNotNil(body.range(of: audio), "the file bytes go in unchanged")
        XCTAssertEqual(progress.values.last, 1)
    }

    func testAFileAboveTheLimitIsRefusedBeforeAnyRequest() async throws {
        let (api, transport) = client([])

        do {
            _ = try await api.uploadSound(name: "Groot", fileName: "groot.wav", data: Data(count: SoundUpload.maxBytes + 1))
            XCTFail("expected tooLarge")
        } catch {
            XCTAssertEqual(error as? APIError, .tooLarge)
        }

        XCTAssertTrue(transport.requests.isEmpty)
    }

    func testUploadFailuresMapToTheirErrors() async throws {
        let cases: [(Int, String, APIError)] = [(400, "error-invalid-audio", .invalidAudio), (413, "error-too-large", .tooLarge), (409, "error-too-many", .tooMany)]

        for (status, fixture, expected) in cases {
            let (api, _) = client([try reply(status, fixture)])

            do {
                _ = try await api.uploadSound(name: "x", fileName: "x.mp3", data: Data([1]))
                XCTFail("expected \(expected)")
            } catch {
                XCTAssertEqual(error as? APIError, expected, fixture)
            }
        }
    }

    func testMultipartKeepsNamesOutOfTroubleAndTheBoundaryIsRandom() {
        XCTAssertEqual(MultipartForm.header("we\"ird\r\nna\\me.mp3"), "weirdname.mp3")
        XCTAssertNotEqual(MultipartForm().boundary, MultipartForm().boundary)
        XCTAssertEqual(SoundUpload.contentType(forFileName: "a.WAV"), "audio/wav")
        XCTAssertEqual(SoundUpload.contentType(forFileName: "a.m4a"), "audio/mp4")
        XCTAssertEqual(SoundUpload.contentType(forFileName: "a.ogg"), "application/octet-stream")
    }

    func testInvitationLink() async throws {
        let device = "7a1d9c3e-5b2f-4e8a-9c01-6d3e8f2a4b57"
        let (api, transport) = client([try reply(200, "app-pairing-response")])

        let response = try await api.createAppPairing(deviceId: device)

        XCTAssertTrue(response.url.reveal().contains("fss_vpair_"))
        XCTAssertEqual(transport.requests[0].httpMethod, "POST")
        XCTAssertEqual(transport.requests[0].url?.absoluteString, "\(base)/pbx/devices/\(device)/app-pairing")
        XCTAssertNil(transport.requests[0].httpBody, "the role never comes from the request")
    }

    // MARK: Parking

    func testParkRoutes() async throws {
        let (api, transport) = client([try reply(201, "park-response"), try reply(200, "parked-calls"), try reply(200, "ok-response")])
        let id = "Zm9vYmFyLWZpeHR1cmUtcGFya2VkLWNhbGwtcmVmZXJlbmNlLTAwMQ"

        let parked = try await api.parkCall(callId: "3b9c1f0e2a7d4c58@192.0.2.10")
        let page = try await api.parkedCalls()
        try await api.hangupParkedCall(id: id)

        XCTAssertEqual(parked.retrieveNumber, "*5901")
        XCTAssertEqual(page.calls.count, 3)
        XCTAssertEqual(transport.requests.map(\.httpMethod), ["POST", "GET", "DELETE"])
        XCTAssertEqual(transport.requests.map { $0.url?.absoluteString }, ["\(base)/calls/park", "\(base)/parked", "\(base)/parked/\(id)"])
        XCTAssertEqual(try bodyText(transport.requests[0]), #"{"callId":"3b9c1f0e2a7d4c58@192.0.2.10"}"#)
    }

    // MARK: Error mapping

    func testTheNewAnswersMapToTheirErrors() async throws {
        let chain = try FSVoipJSON.decoder().decode(APIErrorBody.self, from: Fixtures.data("error-stale-chain")).chain
        let cases: [(Int, String, APIError)] = [
            (409, "error-advanced", .advanced),
            (400, "error-greeting-required", .greetingRequired),
            (422, "error-cost-not-accepted", .costNotAccepted(cost: RecordingCost(priceE4: 20000, vatIncluded: false))),
            (409, "error-stale-chain", .staleChain(try XCTUnwrap(chain))),
            (403, "error-forbidden-field", .forbidden),
            (404, "error-call-not-found", .callNotFound),
            (409, "error-no-free-slot", .noFreeSlot),
            (409, "error-park-unavailable", .parkUnavailable),
            (503, "error-park-uncertain", .parkUncertain),
            // The existing answers keep their meaning.
            (409, "error-stale", .stale(version: 5)),
            (404, "error-not-found", .notFound),
            (400, "error-invalid-field", .invalid(code: "invalid_phone", field: "phones")),
            (413, "error-too-large", .tooLarge),
        ]

        for (status, fixture, expected) in cases {
            let (api, _) = client([.init(status: status, body: try Fixtures.data(fixture))])

            do {
                _ = try await api.numberChain(numberId: number)
                XCTFail("expected \(expected)")
            } catch {
                XCTAssertEqual(error as? APIError, expected, fixture)
            }
        }
    }

    func testCostNotAcceptedAlsoMapsOnA409() async throws {
        // `cost_not_accepted` is a 422 on a recording but a 409 when a new extension costs money: both are recognised on the name.
        let (api, _) = client([.init(status: 409, body: Data(#"{"error":"cost_not_accepted"}"#.utf8))])

        do {
            _ = try await api.numberChain(numberId: number)
            XCTFail("expected an error")
        } catch {
            XCTAssertEqual(error as? APIError, .costNotAccepted(cost: nil))
        }
    }

    func testAParkBusyConflictKeepsItsCode() async throws {
        let (api, _) = client([.init(status: 409, body: Data(#"{"error":"park_busy"}"#.utf8))])

        do {
            _ = try await api.parkCall(callId: "x")
            XCTFail("expected a conflict")
        } catch {
            XCTAssertEqual(error as? APIError, .conflict(code: "park_busy"))
        }
    }

    func testV2ClientNeverLogsTokensOrLinks() async throws {
        let sink = MemoryLogSink()
        let transport = MockTransport([.init(status: 200, body: try Fixtures.data("app-pairing-response")), .init(status: 201, body: try Fixtures.data("park-response"))])
        let api = FSVoipAPIClient(deviceToken: deviceToken, transport: transport, logger: FSLogger(category: "api", sink: sink))

        _ = try await api.createAppPairing(deviceId: "7a1d9c3e-5b2f-4e8a-9c01-6d3e8f2a4b57")
        _ = try await api.parkCall(callId: "3b9c1f0e2a7d4c58@192.0.2.10")

        let log = sink.messages.joined(separator: "\n")
        XCTAssertFalse(log.isEmpty)
        XCTAssertFalse(log.contains("fss_vapp_"))
        XCTAssertFalse(log.contains("fss_vpair_"))
        XCTAssertFalse(log.contains("3b9c1f0e2a7d4c58"), "the Call-ID is not logged")
    }
}

private final class ProgressLog: @unchecked Sendable {
    private let lock = NSLock()
    private var storage: [Double] = []

    func add(_ value: Double) {
        lock.withLock { storage.append(value) }
    }

    var values: [Double] {
        lock.withLock { storage }
    }
}
