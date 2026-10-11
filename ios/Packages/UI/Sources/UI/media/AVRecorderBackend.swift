// SPDX-License-Identifier: AGPL-3.0-or-later
import AVFoundation
import Core
import Foundation

/// Records with `AVAudioRecorder`: AAC, 44,1 kHz, mono, 64 kbps, in an `.m4a` file. The audio session is `.playAndRecord` only while
/// recording and is released again at the end (the session is never touched during a call: `AudioRecorderSession` refuses to
/// start then).
@MainActor
final class AVRecorderBackend: RecorderBackend {
    var onInterrupted: (() -> Void)?

    private var recorder: AVAudioRecorder?
    private var observer: NSObjectProtocol?

    init() {}

    func requestPermission() async -> Bool {
        if #available(iOS 17.0, *) {
            return await AVAudioApplication.requestRecordPermission()
        }

        return await withCheckedContinuation { continuation in
            AVAudioSession.sharedInstance().requestRecordPermission { continuation.resume(returning: $0) }
        }
    }

    func start(to url: URL) throws {
        let session = AVAudioSession.sharedInstance()
        try session.setCategory(.playAndRecord, mode: .default, options: [.defaultToSpeaker, .allowBluetooth])
        try session.setActive(true)

        let settings: [String: Any] = [
            AVFormatIDKey: kAudioFormatMPEG4AAC,
            AVSampleRateKey: 44_100,
            AVNumberOfChannelsKey: 1,
            AVEncoderBitRateKey: 64_000,
            AVEncoderAudioQualityKey: AVAudioQuality.high.rawValue,
        ]
        let recorder = try AVAudioRecorder(url: url, settings: settings)
        recorder.isMeteringEnabled = false

        guard recorder.record() else {
            release()

            throw AVError(.unknown)
        }

        self.recorder = recorder
        observer = NotificationCenter.default.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: .main) { [weak self] note in
            let began = (note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt).flatMap(AVAudioSession.InterruptionType.init(rawValue:)) == .began

            MainActor.assumeIsolated {
                if began { self?.onInterrupted?() }
            }
        }
    }

    func currentTime() -> TimeInterval {
        recorder?.currentTime ?? 0
    }

    func stop() -> TimeInterval {
        let seconds = recorder?.currentTime ?? 0
        recorder?.stop()
        recorder = nil
        release()

        return seconds
    }

    private func release() {
        if let observer {
            NotificationCenter.default.removeObserver(observer)
        }

        observer = nil
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }
}
