// SPDX-License-Identifier: AGPL-3.0-or-later
//
// DEBUG builds only: the sounds of the demo centrale (`-FSVoipDemoScreen sounds`). Uploads, renames and deletes are kept in memory;
// the audio is the same stand-in sound as the voicemail of the demo.

#if DEBUG
import Core
import Foundation

final class DemoSoundService: SoundServicing, @unchecked Sendable {
    private let lock = NSLock()
    private var items: [Sound]
    private let soundURL = DemoSound.makeFile()

    init() {
        items = Self.decode(Self.json).sounds
    }

    func sounds(for account: StoredAccount) async throws -> [Sound] {
        try await Task.sleep(nanoseconds: 120_000_000)

        return lock.withLock { items }
    }

    func upload(for account: StoredAccount, name: String, fileName: String, data: Data, progress: (@Sendable (Double) -> Void)?) async throws -> String {
        guard data.count <= SoundUpload.maxBytes else { throw APIError.tooLarge }

        for step in 1 ... 5 {
            try await Task.sleep(nanoseconds: 120_000_000)
            progress?(Double(step) / 5)
        }

        let id = UUID().uuidString.lowercased()
        let sound: Sound = Self.decode(#"{"sounds":[{"id":"\#(id)","name":"\#(name.replacingOccurrences(of: "\"", with: ""))","playable":true,"uses":[],"contentType":"audio/mp4","byteSize":\#(data.count),"sync":"pending"}]}"#).sounds[0]
        lock.withLock { items.append(sound) }

        return id
    }

    func rename(for account: StoredAccount, id: String, name: String) async throws {
        try await Task.sleep(nanoseconds: 120_000_000)
        let renamed: Sound? = lock.withLock {
            guard let index = items.firstIndex(where: { $0.id == id }) else { return nil }

            items[index].name = name

            return items[index]
        }

        if renamed == nil { throw APIError.notFound }
    }

    func delete(for account: StoredAccount, id: String) async throws {
        try await Task.sleep(nanoseconds: 120_000_000)

        try lock.withLock {
            guard let sound = items.first(where: { $0.id == id }) else { throw APIError.notFound }

            if !sound.uses.isEmpty {
                throw APIError.inUse(places: sound.uses.map { APIErrorPlace(kind: $0.kind, name: $0.name) })
            }

            items.removeAll { $0.id == id }
        }
    }

    func source(for account: StoredAccount, id: String) throws -> AudioSource {
        .file(soundURL)
    }

    private static func decode(_ json: String) -> SoundsPage {
        try! FSVoipJSON.decoder().decode(SoundsPage.self, from: Data(json.utf8))
    }

    private static let json = """
    {"sounds": [
      {"id": "a1b2c3d4-1111-4a2b-8c3d-4e5f6a7b8c9d", "name": "Welkom", "playable": true, "uses": [{"kind": "menu_greeting", "name": "Keuzemenu Support"}], "contentType": "audio/mpeg", "byteSize": 184320, "sync": "ok"},
      {"id": "a1b2c3d4-2222-4a2b-8c3d-4e5f6a7b8c9d", "name": "Buiten kantoortijd", "playable": true, "uses": [{"kind": "closed_message", "name": "Kantoortijden"}], "contentType": "audio/wav", "byteSize": 512044, "sync": "ok"},
      {"id": "a1b2c3d4-3333-4a2b-8c3d-4e5f6a7b8c9d", "name": "Wachtmuziek", "playable": true, "uses": [], "contentType": "audio/mpeg", "byteSize": 902044, "sync": "ok"},
      {"id": "a1b2c3d4-4444-4a2b-8c3d-4e5f6a7b8c9d", "name": "Oud bericht", "playable": false, "uses": [], "contentType": null, "byteSize": null, "sync": "ok"}
    ]}
    """
}
#endif
