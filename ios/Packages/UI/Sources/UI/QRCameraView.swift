// SPDX-License-Identifier: AGPL-3.0-or-later
import AVFoundation
import SwiftUI
import UIKit

/// Camera preview that reports the first QR code it sees. Stops itself once a code was delivered; `resetToken`
/// lets the parent re-arm it (after a code that was not a pairing link).
struct QRCameraView: UIViewControllerRepresentable {
    let resetToken: Int
    let onCode: (String) -> Void

    func makeUIViewController(context: Context) -> QRCameraController {
        let controller = QRCameraController()
        controller.onCode = onCode
        return controller
    }

    func updateUIViewController(_ controller: QRCameraController, context: Context) {
        controller.onCode = onCode
        controller.rearm(token: resetToken)
    }
}

final class QRCameraController: UIViewController, AVCaptureMetadataOutputObjectsDelegate {
    var onCode: ((String) -> Void)?

    private let session = AVCaptureSession()
    private let sessionQueue = DispatchQueue(label: "nl.fullstackstudio.fsvoip.camera")
    private var previewLayer: AVCaptureVideoPreviewLayer?
    private var delivered = false
    private var token = 0

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black

        guard let device = AVCaptureDevice.default(for: .video), let input = try? AVCaptureDeviceInput(device: device), session.canAddInput(input) else {
            return
        }

        session.addInput(input)

        let output = AVCaptureMetadataOutput()

        guard session.canAddOutput(output) else {
            return
        }

        session.addOutput(output)
        output.setMetadataObjectsDelegate(self, queue: .main)
        output.metadataObjectTypes = [.qr]

        let layer = AVCaptureVideoPreviewLayer(session: session)
        layer.videoGravity = .resizeAspectFill
        view.layer.addSublayer(layer)
        previewLayer = layer
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        previewLayer?.frame = view.bounds
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        let session = session
        sessionQueue.async { if !session.isRunning { session.startRunning() } }
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        let session = session
        sessionQueue.async { if session.isRunning { session.stopRunning() } }
    }

    func rearm(token newToken: Int) {
        guard newToken != token else {
            return
        }

        token = newToken
        delivered = false
    }

    func metadataOutput(_ output: AVCaptureMetadataOutput, didOutput metadataObjects: [AVMetadataObject], from connection: AVCaptureConnection) {
        guard !delivered, let code = metadataObjects.compactMap({ $0 as? AVMetadataMachineReadableCodeObject }).first?.stringValue else {
            return
        }

        delivered = true
        onCode?(code)
    }
}

enum CameraAccess: Equatable {
    case available
    case notDetermined
    case denied
    /// No camera (simulator) or restricted by the device owner.
    case unavailable

    static var current: CameraAccess {
        guard AVCaptureDevice.default(for: .video) != nil else {
            return .unavailable
        }

        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized: return .available
        case .notDetermined: return .notDetermined
        case .denied: return .denied
        case .restricted: return .unavailable
        @unknown default: return .unavailable
        }
    }

    static func request() async -> CameraAccess {
        _ = await AVCaptureDevice.requestAccess(for: .video)
        return current
    }
}
