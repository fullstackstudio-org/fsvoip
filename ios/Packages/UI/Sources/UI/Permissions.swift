// SPDX-License-Identifier: AGPL-3.0-or-later
import AVFoundation
import Foundation

public enum MicrophonePermission {
    /// Ask once (iOS only shows the question the first time); returns whether calls can use the microphone.
    public static func request() async -> Bool {
        await withCheckedContinuation { continuation in
            if #available(iOS 17.0, *) {
                AVAudioApplication.requestRecordPermission { continuation.resume(returning: $0) }
            } else {
                AVAudioSession.sharedInstance().requestRecordPermission { continuation.resume(returning: $0) }
            }
        }
    }
}
