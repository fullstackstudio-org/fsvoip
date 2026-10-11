// SPDX-License-Identifier: AGPL-3.0-or-later
import Core
import Foundation
@testable import UI

/// A scripted `/calls` and `/voicemail`: every call is recorded and errors can be queued per method.
final class FakeMediaService: MediaServicing, @unchecked Sendable {
    private let lock = NSLock()
    private(set) var calls: [String] = []
    private(set) var deleted: [(boxId: String, ref: String)] = []
    private(set) var requestedMonths: [String?] = []
    private(set) var requestedBoxes: [String?] = []
    var failures: [String: [Error]] = [:]
    var voicemailPage: VoicemailPage = MediaFixtures.voicemail
    var callsPages: [String: CallsPage] = ["2026-10": MediaFixtures.callsOctober, "2026-09": MediaFixtures.callsSeptember]
    var tokenForSources = Secret("fss_vapp_" + String(repeating: "y", count: 43))

    private func enter(_ name: String) throws {
        lock.lock()
        defer { lock.unlock() }

        calls.append(name)

        if var queue = failures[name], !queue.isEmpty {
            let error = queue.removeFirst()
            failures[name] = queue

            throw error
        }
    }

    func count(_ name: String) -> Int { calls.filter { $0 == name }.count }

    func calls(for account: StoredAccount, month: String?) async throws -> CallsPage {
        try enter("calls")
        requestedMonths.append(month)

        return callsPages[month ?? "2026-10"] ?? callsPages["2026-10"]!
    }

    func voicemail(for account: StoredAccount, box: String?) async throws -> VoicemailPage {
        try enter("voicemail")
        requestedBoxes.append(box)

        return voicemailPage
    }

    func deleteVoicemail(for account: StoredAccount, boxId: String, ref: String) async throws {
        try enter("deleteVoicemail")
        deleted.append((boxId, ref))
    }

    func recordingSource(for account: StoredAccount, callId: String) throws -> AudioSource {
        try enter("recordingSource")

        return .stream(MediaRequest(url: URL(string: "https://example.invalid/api/voip-app/v1/calls/\(callId)/recording")!, deviceToken: tokenForSources, userAgent: "FSVoip/1 (test)"))
    }

    func voicemailSource(for account: StoredAccount, boxId: String, ref: String) throws -> AudioSource {
        try enter("voicemailSource")

        return .stream(MediaRequest(url: URL(string: "https://example.invalid/api/voip-app/v1/voicemail/\(boxId)/\(ref)/audio")!, deviceToken: tokenForSources, userAgent: "FSVoip/1 (test)"))
    }

    func download(_ media: MediaRequest, for account: StoredAccount) async throws -> MediaDownload {
        try enter("download")

        return MediaDownload(data: Data([0x52, 0x49, 0x46, 0x46, 0, 0, 0, 0, 0x57, 0x41, 0x56, 0x45]), contentType: "audio/wav")
    }
}

enum MediaFixtures {
    static let ownBox = "7a1d9c3e-5b2f-4e8a-9c01-6d3e8f2a4b57"
    static let sharedBox = "2f4a6c8e-1b3d-4f5a-8c7e-9a0b1c2d3e4f"
    static let colleagueBox = "5c3b1a09-8d7e-4f6a-b2c1-0e9d8c7b6a51"

    static var voicemail: VoicemailPage {
        decode("""
        {"boxes": [
          {"id": "\(sharedBox)", "name": "Algemeen", "number": "900", "shared": true},
          {"id": "\(ownBox)", "name": "Jan de Vries", "number": "102", "shared": false},
          {"id": "\(colleagueBox)", "name": "Pieter Jansen", "number": "101", "shared": false}],
         "boxId": null, "available": true,
         "messages": [
          \(message("own-new", box: ownBox, name: "Jan de Vries", number: "102", sort: "2026-10-09T06:55:00.000Z", days: 29)),
          \(message("own-old", box: ownBox, name: "Jan de Vries", number: "102", sort: "2026-10-02T09:03:00.000Z", days: 2)),
          \(message("shared", box: sharedBox, name: "Algemeen", number: "900", sort: "2026-10-07T07:20:00.000Z", days: 27)),
          \(message("colleague", box: colleagueBox, name: "Pieter Jansen", number: "101", sort: "2026-10-06T15:45:00.000Z", days: 26))]}
        """)
    }

    static func message(_ ref: String, box: String, name: String, number: String, sort: String, days: Int) -> String {
        #"{"ref": "v1.\#(ref)", "boxId": "\#(box)", "boxName": "\#(name)", "boxNumber": "\#(number)", "caller": "070 123 45 67", "callerName": null, "receivedLabel": "x", "receivedSort": "\#(sort)", "durationLabel": "0:21", "isNew": false, "transcription": null, "daysLeft": \#(days)}"#
    }

    static var callsOctober: CallsPage {
        decode(#"{"month": "2026-10", "months": ["2026-10", "2026-09"], "truncated": false, "calls": [\#(call(1, true, false)), \#(call(2, false, false)), \#(call(3, true, false))]}"#)
    }

    static var callsSeptember: CallsPage {
        decode(#"{"month": "2026-09", "months": ["2026-10", "2026-09"], "truncated": false, "calls": [\#(call(11, false, true))]}"#)
    }

    static func call(_ n: Int, _ recording: Bool, _ expired: Bool) -> String {
        #"{"id": "11111111-aaaa-4bbb-8ccc-0000000000\#(String(format: "%02d", n))", "direction": "inbound", "outcome": "answered", "number": "070 123 45 67", "ourNumber": "0850607848", "extension": "102", "extensionName": "Jan de Vries", "startedLabel": "9 okt 09:12", "startedSort": "2026-10-0\#(n % 9 + 1)T07:12:41.000Z", "durationLabel": "2:05", "durationSeconds": 125, "hasRecording": \#(recording), "recordingExpired": \#(expired)}"#
    }

    static func decode<T: Decodable>(_ json: String) -> T {
        try! FSVoipJSON.decoder().decode(T.self, from: Data(json.utf8))
    }
}

@MainActor
final class FakeAudioBackend: AudioBackend {
    var onEvent: ((AudioBackendEvent) -> Void)?
    var isCallActive: () -> Bool = { false }
    private(set) var loaded: [AudioSource] = []
    private(set) var pauses = 0
    private(set) var stops = 0

    func load(_ source: AudioSource) { loaded.append(source) }
    func play(rate: Double) {}
    func pause() { pauses += 1 }
    func seek(to seconds: TimeInterval) {}
    func setRate(_ rate: Double) {}
    func stop() { stops += 1 }
    func emit(_ event: AudioBackendEvent) { onEvent?(event) }
}
