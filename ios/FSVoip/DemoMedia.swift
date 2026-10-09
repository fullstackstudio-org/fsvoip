// SPDX-License-Identifier: AGPL-3.0-or-later
//
// DEBUG builds only: voicemail and recordings for the demo mode. The audio is a short sound made on the spot (a stand-in for
// speech), written to a temporary file; nothing here talks to a server.

#if DEBUG
import Core
import Foundation

/// Pretends to be `/calls` and `/voicemail`. The first demo account is the admin; the others only have their own box.
final class DemoMediaService: MediaServicing, @unchecked Sendable {
    private let lock = NSLock()
    private var voicemailPage: VoicemailPage
    private let callsByMonth: [String: CallsPage]
    private let adminId: String
    private let soundURL: URL

    init(adminAccountId: String) {
        adminId = adminAccountId
        soundURL = DemoSound.makeFile()
        voicemailPage = Self.decode(Self.voicemailJSON)
        callsByMonth = [
            "2026-10": Self.decode(Self.callsOctoberJSON),
            "2026-09": Self.decode(Self.callsSeptemberJSON),
        ]
    }

    func calls(for account: StoredAccount, month: String?) async throws -> CallsPage {
        guard account.id == adminId else { throw APIError.forbidden }

        try await Task.sleep(nanoseconds: 150_000_000)

        return callsByMonth[month ?? "2026-10"] ?? callsByMonth["2026-10"]!
    }

    func voicemail(for account: StoredAccount, box: String?) async throws -> VoicemailPage {
        try await Task.sleep(nanoseconds: 150_000_000)

        var page = lock.withLock { voicemailPage }

        if account.id != adminId {
            // A plain user only sees the own box.
            page.boxes = page.boxes.filter { $0.id == Self.ownBox }
            page.messages = page.messages.filter { $0.boxId == Self.ownBox }
        }

        return page
    }

    func deleteVoicemail(for account: StoredAccount, boxId: String, ref: String) async throws {
        try await Task.sleep(nanoseconds: 200_000_000)
        lock.withLock { voicemailPage.messages.removeAll { $0.boxId == boxId && $0.ref == ref } }
    }

    func recordingSource(for account: StoredAccount, callId: String) throws -> AudioSource {
        guard account.id == adminId else { throw APIError.forbidden }

        return .file(soundURL)
    }

    func voicemailSource(for account: StoredAccount, boxId: String, ref: String) throws -> AudioSource {
        .file(soundURL)
    }

    func download(_ media: MediaRequest, for account: StoredAccount) async throws -> MediaDownload {
        throw APIError.unavailable(retryable: true, retryAfterSeconds: nil)
    }

    private static func decode<T: Decodable>(_ json: String) -> T {
        try! FSVoipJSON.decoder().decode(T.self, from: Data(json.utf8))
    }

    private static let ownBox = "7a1d9c3e-5b2f-4e8a-9c01-6d3e8f2a4b57"
    private static let sharedBox = "2f4a6c8e-1b3d-4f5a-8c7e-9a0b1c2d3e4f"
    private static let colleagueBox = "5c3b1a09-8d7e-4f6a-b2c1-0e9d8c7b6a51"

    private static let voicemailJSON = """
    {
     "boxes": [
      {"id": "\(sharedBox)", "name": "Algemeen", "number": "900", "shared": true},
      {"id": "\(ownBox)", "name": "Jan de Vries", "number": "102", "shared": false},
      {"id": "\(colleagueBox)", "name": "Pieter Jansen", "number": "101", "shared": false}
     ],
     "boxId": null,
     "available": true,
     "messages": [
      {"ref": "v1.demo-1", "boxId": "\(ownBox)", "boxName": "Jan de Vries", "boxNumber": "102", "caller": "070 123 45 67", "callerName": "Bakkerij Smit", "receivedLabel": "vandaag 08:55", "receivedSort": "2026-10-09T06:55:00.000Z", "durationLabel": "0:21", "isNew": true, "transcription": null, "daysLeft": 29},
      {"ref": "v1.demo-2", "boxId": "\(ownBox)", "boxName": "Jan de Vries", "boxNumber": "102", "caller": "06 12 34 56 78", "callerName": null, "receivedLabel": "gisteren 16:12", "receivedSort": "2026-10-08T14:12:00.000Z", "durationLabel": "0:42", "isNew": false, "transcription": "Hoi Jan, kun je mij terugbellen over de offerte voor de dakkapel?", "daysLeft": 28},
      {"ref": "v1.demo-3", "boxId": "\(ownBox)", "boxName": "Jan de Vries", "boxNumber": "102", "caller": null, "callerName": null, "receivedLabel": "2 okt 11:03", "receivedSort": "2026-10-02T09:03:00.000Z", "durationLabel": "0:09", "isNew": false, "transcription": null, "daysLeft": 2},
      {"ref": "v1.demo-4", "boxId": "\(sharedBox)", "boxName": "Algemeen", "boxNumber": "900", "caller": "020 765 43 21", "callerName": "Gemeente Amsterdam", "receivedLabel": "7 okt 09:20", "receivedSort": "2026-10-07T07:20:00.000Z", "durationLabel": "1:02", "isNew": true, "transcription": null, "daysLeft": 27},
      {"ref": "v1.demo-5", "boxId": "\(colleagueBox)", "boxName": "Pieter Jansen", "boxNumber": "101", "caller": "06 98 76 54 32", "callerName": null, "receivedLabel": "6 okt 17:45", "receivedSort": "2026-10-06T15:45:00.000Z", "durationLabel": "0:31", "isNew": false, "transcription": null, "daysLeft": 26}
     ]
    }
    """

    private static func call(_ n: Int, _ direction: String, _ number: String?, _ ext: String, _ extName: String, _ label: String, _ sort: String, _ duration: String, recording: Bool, expired: Bool = false) -> String {
        """
        {"id": "11111111-aaaa-4bbb-8ccc-0000000000\(String(format: "%02d", n))", "direction": "\(direction)", "outcome": "answered", "number": \(number.map { "\"\($0)\"" } ?? "null"), "ourNumber": "0850607848", "extension": "\(ext)", "extensionName": "\(extName)", "country": "NL", "countryName": "Nederland", "lineType": "fixed", "category": "fixed", "startedLabel": "\(label)", "startedSort": "\(sort)", "durationLabel": "\(duration)", "durationSeconds": 60, "hasRecording": \(recording), "recordingExpired": \(expired)}
        """
    }

    private static var callsOctoberJSON: String {
        let items = [
            call(1, "inbound", "070 123 45 67", "102", "Jan de Vries", "9 okt 09:12", "2026-10-09T07:12:00.000Z", "2:05", recording: true),
            call(2, "outbound", "06 12 34 56 78", "102", "Jan de Vries", "8 okt 16:40", "2026-10-08T14:40:00.000Z", "4:18", recording: true),
            call(3, "inbound", "020 765 43 21", "101", "Pieter Jansen", "7 okt 11:05", "2026-10-07T09:05:00.000Z", "0:48", recording: true),
            call(4, "internal", nil, "103", "Werkplaats", "6 okt 14:30", "2026-10-06T12:30:00.000Z", "1:12", recording: true),
            call(5, "outbound", "010 555 01 02", "102", "Jan de Vries", "2 okt 10:02", "2026-10-02T08:02:00.000Z", "6:40", recording: true),
        ]

        return #"{"month": "2026-10", "months": ["2026-10", "2026-09", "2026-08"], "truncated": false, "calls": [\#(items.joined(separator: ","))]}"#
    }

    private static var callsSeptemberJSON: String {
        let items = [
            call(11, "inbound", "06 12 34 56 78", "102", "Jan de Vries", "29 sep 15:21", "2026-09-29T13:21:00.000Z", "3:09", recording: true),
            call(12, "outbound", "070 123 45 67", "101", "Pieter Jansen", "12 sep 09:45", "2026-09-12T07:45:00.000Z", "0:55", recording: true),
            call(13, "inbound", "085 060 78 48", "102", "Jan de Vries", "3 sep 13:10", "2026-09-03T11:10:00.000Z", "2:30", recording: false, expired: true),
        ]

        return #"{"month": "2026-09", "months": ["2026-10", "2026-09", "2026-08"], "truncated": false, "calls": [\#(items.joined(separator: ","))]}"#
    }
}

/// A 21-second sound that moves like speech (syllable-sized swells on a changing pitch), as a 16-bit mono WAV in the temporary directory.
enum DemoSound {
    static func makeFile() -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("fsvoip-demo-voicemail.wav")

        if let data = try? Data(contentsOf: url), data.count > 1_000 {
            return url
        }

        let rate = 16_000
        let seconds = 21
        let count = rate * seconds
        var samples = [Int16](repeating: 0, count: count)
        var phase = 0.0

        for index in 0 ..< count {
            let t = Double(index) / Double(rate)
            // Syllables: swells about four times a second, with pauses between short phrases.
            let syllable = pow(max(0, sin(t * 2 * .pi * 3.6)), 1.5)
            let phrase = sin(t * 2 * .pi * 0.35) > -0.25 ? 1.0 : 0.0
            let pitch = 130 + 45 * sin(t * 2 * .pi * 0.9) + 25 * sin(t * 2 * .pi * 2.3)
            phase += 2 * .pi * pitch / Double(rate)
            let tone = sin(phase) + 0.5 * sin(2 * phase) + 0.25 * sin(3 * phase)
            samples[index] = Int16(max(-1, min(1, tone * syllable * phrase * 0.28)) * Double(Int16.max))
        }

        var data = Data()

        func append<T: FixedWidthInteger>(_ value: T) {
            withUnsafeBytes(of: value.littleEndian) { data.append(contentsOf: $0) }
        }

        data.append(contentsOf: Array("RIFF".utf8))
        append(UInt32(36 + count * 2))
        data.append(contentsOf: Array("WAVEfmt ".utf8))
        append(UInt32(16))
        append(UInt16(1))
        append(UInt16(1))
        append(UInt32(rate))
        append(UInt32(rate * 2))
        append(UInt16(2))
        append(UInt16(16))
        data.append(contentsOf: Array("data".utf8))
        append(UInt32(count * 2))

        for sample in samples { append(sample) }

        try? data.write(to: url, options: .atomic)

        return url
    }
}
#endif
