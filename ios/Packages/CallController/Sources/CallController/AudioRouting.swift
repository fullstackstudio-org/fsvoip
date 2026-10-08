// SPDX-License-Identifier: AGPL-3.0-or-later
import AVFoundation
import Foundation

/// Speaker on/off. The audio session belongs to the app (CallKit activates it), so the route is switched here and
/// not inside the SIP engine.
public protocol AudioRouting: AnyObject {
    func setSpeaker(_ on: Bool) throws
    /// Whether the speaker is the current output (it can change underneath us, e.g. Bluetooth connects).
    var isSpeakerActive: Bool { get }
}

public final class SystemAudioRouting: AudioRouting {
    public init() {}

    public func setSpeaker(_ on: Bool) throws {
        try AVAudioSession.sharedInstance().overrideOutputAudioPort(on ? .speaker : .none)
    }

    public var isSpeakerActive: Bool {
        AVAudioSession.sharedInstance().currentRoute.outputs.contains { $0.portType == .builtInSpeaker }
    }
}

/// Remembers the choice only (tests, previews, demo mode).
public final class MemoryAudioRouting: AudioRouting {
    public private(set) var isSpeakerActive = false

    public init() {}

    public func setSpeaker(_ on: Bool) throws {
        isSpeakerActive = on
    }
}
