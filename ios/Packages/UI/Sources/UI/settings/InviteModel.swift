// SPDX-License-Identifier: AGPL-3.0-or-later
import Core
import CoreImage
import CoreImage.CIFilterBuiltins
import Foundation
import UIKit

/// The state of "Gebruiker uitnodigen": pick a colleague's extension, ask for a link, show it once until it expires.
///
/// 🚨 The link is a credential (it pairs a phone with the extension). It lives only in `phase` while the page is open: never in a log,
/// a preference, a file or a restored scene. `reset()` drops it.
@MainActor
final class InviteModel: ObservableObject {
    struct Invitation: Equatable {
        let deviceId: String
        let deviceName: String
        let url: Secret
        let expiresAt: Date
    }

    enum Phase: Equatable {
        case choosing
        case creating
        case shown(Invitation)
        case failed(SelfExtensionFailure)
    }

    @Published private(set) var phase = Phase.choosing
    @Published var selectedId: String?

    private let hub: SelfExtensionHub
    private let account: StoredAccount
    private let now: () -> Date

    init(hub: SelfExtensionHub, account: StoredAccount, now: @escaping () -> Date = { Date() }) {
        self.hub = hub
        self.account = account
        self.now = now
    }

    /// The extensions that can be invited: all except the one of this very app (the server refuses it: it would replace this app).
    func candidates(_ devices: [PbxDevice]) -> [PbxDevice] {
        let ownId = hub.state(for: account.id)?.id

        return devices.filter { device in
            if let ownId { return device.id != ownId }

            return account.extensionNumber == nil || device.extensionNumber != account.extensionNumber
        }
    }

    func create(deviceName: (String) -> String) async {
        guard let id = selectedId, phase != .creating else { return }

        phase = .creating

        switch await hub.invite(deviceId: id, account: account) {
        case let .created(response):
            phase = .shown(Invitation(deviceId: id, deviceName: deviceName(id), url: response.url, expiresAt: response.expiresAt))
        case let .failed(failure):
            phase = .failed(failure)
        }
    }

    /// Back to the list; the link is gone.
    func reset() {
        phase = .choosing
    }

    func remaining(of invitation: Invitation, at date: Date? = nil) -> TimeInterval {
        max(0, invitation.expiresAt.timeIntervalSince(date ?? now()))
    }

    func isExpired(_ invitation: Invitation, at date: Date? = nil) -> Bool {
        remaining(of: invitation, at: date) <= 0
    }
}

enum InviteCountdown {
    /// `9:42`, never negative.
    static func format(_ seconds: TimeInterval) -> String {
        let total = max(0, Int(seconds.rounded(.up)))

        return String(format: "%d:%02d", total / 60, total % 60)
    }
}

enum QRCodeImage {
    /// A crisp QR code of `text` (no smoothing when scaled), or `nil` for an empty text.
    static func make(_ text: String, scale: CGFloat = 10) -> UIImage? {
        guard !text.isEmpty else { return nil }

        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(text.utf8)
        filter.correctionLevel = "M"

        guard let output = filter.outputImage?.transformed(by: CGAffineTransform(scaleX: scale, y: scale)),
              let image = CIContext().createCGImage(output, from: output.extent)
        else {
            return nil
        }

        return UIImage(cgImage: image)
    }
}
