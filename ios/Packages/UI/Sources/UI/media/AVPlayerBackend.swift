// SPDX-License-Identifier: AGPL-3.0-or-later
import AVFoundation
import Core
import Foundation

/// Plays through `AVPlayer`. A streamed item is an `AVURLAsset` whose options carry the bearer token in the HTTP headers
/// (`MediaRequest.assetOptions()`); AVFoundation then issues the `Range` requests itself, which is what makes scrubbing work
/// without downloading everything. The URL never holds the token and nothing here logs a URL or a header.
///
/// The audio session is `.playback` / `.spokenAudio`, activated only when playing starts and released again when the item is
/// stopped, and never touched while a call is going on (CallKit owns the session then).
@MainActor
public final class AVPlayerBackend: AudioBackend {
    public var onEvent: ((AudioBackendEvent) -> Void)?
    /// Set by the app: a call is in progress, so the session is not ours to change.
    public var isCallActive: () -> Bool = { false }

    private var player: AVPlayer?
    private var item: AVPlayerItem?
    private var statusObservation: NSKeyValueObservation?
    private var timeObserver: Any?
    private var observers: [NSObjectProtocol] = []
    private var sessionActive = false

    public init() {}

    deinit {
        // Observers are removed in `stop()`; nothing else to release here.
    }

    public func load(_ source: AudioSource) {
        teardown()

        let item = AVPlayerItem(asset: Self.makeAsset(for: source))
        item.audioTimePitchAlgorithm = .timeDomain
        let player = AVPlayer(playerItem: item)
        player.automaticallyWaitsToMinimizeStalling = true
        self.item = item
        self.player = player

        statusObservation = item.observe(\.status, options: [.initial, .new]) { [weak self] item, _ in
            let status = item.status
            let seconds = item.duration.seconds

            Task { @MainActor [weak self] in
                guard let self, self.item === item else { return }

                switch status {
                case .readyToPlay:
                    self.onEvent?(.ready(duration: seconds.isFinite && seconds > 0 ? seconds : nil))
                case .failed:
                    self.onEvent?(.failed)
                default:
                    break
                }
            }
        }

        timeObserver = player.addPeriodicTimeObserver(forInterval: CMTime(seconds: 0.25, preferredTimescale: 600), queue: .main) { [weak self] time in
            let seconds = time.seconds

            MainActor.assumeIsolated {
                guard let self, seconds.isFinite else { return }

                self.onEvent?(.progress(max(0, seconds)))
            }
        }

        let center = NotificationCenter.default
        observers = [
            center.addObserver(forName: AVPlayerItem.didPlayToEndTimeNotification, object: item, queue: .main) { [weak self] _ in
                MainActor.assumeIsolated { self?.onEvent?(.ended) }
            },
            center.addObserver(forName: AVPlayerItem.failedToPlayToEndTimeNotification, object: item, queue: .main) { [weak self] _ in
                MainActor.assumeIsolated { self?.onEvent?(.failed) }
            },
            center.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: .main) { [weak self] note in
                let began = (note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt).flatMap(AVAudioSession.InterruptionType.init(rawValue:)) == .began

                MainActor.assumeIsolated {
                    if began {
                        self?.player?.pause()
                        self?.onEvent?(.interrupted)
                    }
                }
            },
        ]
    }

    /// The `options` of the asset: for a stream the bearer token in the HTTP headers, for a local file nothing.
    static func assetOptions(for source: AudioSource) -> [String: Any]? {
        switch source {
        case let .stream(media): return media.assetOptions()
        case .file: return nil
        }
    }

    static func makeAsset(for source: AudioSource) -> AVURLAsset {
        switch source {
        case let .stream(media): return AVURLAsset(url: media.url, options: assetOptions(for: source))
        case let .file(url): return AVURLAsset(url: url)
        }
    }

    public func play(rate: Double) {
        activateSession()
        player?.defaultRate = Float(rate)
        player?.playImmediately(atRate: Float(rate))
    }

    public func pause() {
        player?.pause()
    }

    public func seek(to seconds: TimeInterval) {
        player?.seek(to: CMTime(seconds: seconds, preferredTimescale: 600), toleranceBefore: .zero, toleranceAfter: .zero)
    }

    public func setRate(_ rate: Double) {
        player?.defaultRate = Float(rate)

        if player?.timeControlStatus != .paused {
            player?.rate = Float(rate)
        }
    }

    public func stop() {
        teardown()
        deactivateSession()
    }

    private func teardown() {
        if let timeObserver {
            player?.removeTimeObserver(timeObserver)
        }

        timeObserver = nil
        statusObservation = nil
        observers.forEach(NotificationCenter.default.removeObserver)
        observers = []
        player?.pause()
        player?.replaceCurrentItem(with: nil)
        player = nil
        item = nil
    }

    private func activateSession() {
        guard !sessionActive, !isCallActive() else { return }

        let session = AVAudioSession.sharedInstance()

        do {
            try session.setCategory(.playback, mode: .spokenAudio)
            try session.setActive(true)
            sessionActive = true
        } catch {
            // Playing without our own session still works; the system picks a default.
        }
    }

    private func deactivateSession() {
        guard sessionActive else { return }

        sessionActive = false

        // During a call the session belongs to the call.
        guard !isCallActive() else { return }

        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }
}
