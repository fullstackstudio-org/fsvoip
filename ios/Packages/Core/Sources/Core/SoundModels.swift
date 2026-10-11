// SPDX-License-Identifier: AGPL-3.0-or-later
//
// Swift mirror of `/pbx/sounds` and `/pbx/devices/{id}/app-pairing` in `shared/openapi.yaml` (admin pairings only): the sounds of
// the PBX (welcome messages, music, greetings) and the invitation link for a colleague.

import Foundation

/// Where a sound is used. The kinds are the server's: `voicemail_greeting`, `extension_voicemail_greeting`, `queue_music`,
/// `queue_welcome`, `menu_greeting`, `closed_message`; others may follow, so it stays text.
public struct SoundUse: Decodable, Equatable, Sendable {
    public var kind: String
    /// The name of the object that uses it (a name the customer chose).
    public var name: String

    public init(kind: String, name: String) {
        self.kind = kind
        self.name = name
    }
}

public struct Sound: Decodable, Equatable, Sendable, Identifiable {
    public var id: String
    public var name: String
    /// Always `nil` for now: the app reads the duration from the audio.
    public var durationSeconds: Double?
    /// `false` = there is no copy to play (a sound from before this feature); the PBX still uses it.
    public var playable: Bool
    public var uses: [SoundUse]
    /// `audio/wav`, `audio/mpeg` or `audio/mp4`; `nil` without a copy.
    public var contentType: String?
    public var byteSize: Int?
    public var sync: SyncState

    private enum CodingKeys: String, CodingKey {
        case id, name, durationSeconds, playable, uses, contentType, byteSize, sync
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(String.self, forKey: .id)
        name = try container.decode(String.self, forKey: .name)
        durationSeconds = try container.decodeIfPresent(Double.self, forKey: .durationSeconds)
        playable = try container.decodeIfPresent(Bool.self, forKey: .playable) ?? false
        uses = try container.decodeIfPresent([SoundUse].self, forKey: .uses) ?? []
        contentType = try container.decodeIfPresent(String.self, forKey: .contentType)
        byteSize = try container.decodeIfPresent(Int.self, forKey: .byteSize)
        sync = try container.decodeIfPresent(SyncState.self, forKey: .sync) ?? .unknown
    }
}

/// `GET /pbx/sounds`.
public struct SoundsPage: Decodable, Equatable, Sendable {
    public var sounds: [Sound]

    private enum CodingKeys: String, CodingKey {
        case sounds
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        sounds = try container.decodeIfPresent([Sound].self, forKey: .sounds) ?? []
    }
}

/// `POST /pbx/sounds`: the id of the new sound.
public struct SoundUploadResponse: Decodable, Equatable, Sendable {
    public var soundId: String
}

/// `PATCH /pbx/sounds/{id}`.
public struct SoundRenameRequest: Encodable, Equatable, Sendable {
    public var name: String

    public init(name: String) {
        self.name = name
    }
}

/// `POST /pbx/devices/{id}/app-pairing`: a universal link that pairs FSVoip on a colleague's phone with that extension (role `user`).
/// 🚨 The link is a credential: show it once, never log it (it is a `Secret`).
public struct AppPairingResponse: Decodable, Equatable, Sendable {
    public var url: Secret
    public var expiresAt: Date
}

/// The sound formats the server accepts (it reads the type from the first bytes; this is only for the picker and the part header).
public enum SoundUpload {
    /// Up to 20 MB; a bigger file is `413 too_large` without being read.
    public static let maxBytes = 20 * 1024 * 1024

    /// The content type for the multipart part, from the file extension. The server ignores it (it reads the magic bytes).
    public static func contentType(forFileName name: String) -> String {
        switch (name as NSString).pathExtension.lowercased() {
        case "wav": return "audio/wav"
        case "mp3": return "audio/mpeg"
        case "m4a", "mp4": return "audio/mp4"
        default: return "application/octet-stream"
        }
    }
}
