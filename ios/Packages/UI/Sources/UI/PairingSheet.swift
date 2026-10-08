// SPDX-License-Identifier: AGPL-3.0-or-later
import Core
import Pairing
import SwiftUI
import UIKit

/// The whole "add a line" flow in one sheet: scan (or paste) → confirm → pairing → done / problem.
struct PairingSheet: View {
    @ObservedObject var model: FSVoipAppModel

    var body: some View {
        NavigationStack {
            Group {
                switch model.pairing {
                case .idle:
                    ScannerView(model: model)
                case .linkReceived:
                    ConfirmPairingView(model: model)
                case .pairing:
                    PairingProgressView()
                case let .paired(account):
                    PairedView(model: model, account: account)
                case let .failed(link, failure):
                    PairingFailedView(model: model, canRetry: link != nil && failure.isRetryable, failure: failure)
                }
            }
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    // Only the scanner needs it: every other step has its own cancel or close button.
                    if model.pairing == .idle {
                        Button(L10n.string("action.cancel")) {
                            model.isScannerPresented = false
                            model.closePairing()
                        }
                    }
                }
            }
            .navigationBarTitleDisplayMode(.inline)
        }
        .interactiveDismissDisabled(isBusy)
    }

    private var isBusy: Bool {
        if case .pairing = model.pairing {
            return true
        }

        return false
    }

}

// MARK: - Scanner

struct ScannerView: View {
    @ObservedObject var model: FSVoipAppModel
    @State private var access = CameraAccess.current
    @State private var resetToken = 0
    @State private var pasted = ""
    @State private var showsPasteField = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                viewfinder
                    .frame(maxWidth: .infinity)
                    .aspectRatio(1, contentMode: .fit)
                    .clipShape(RoundedRectangle(cornerRadius: 28, style: .continuous))

                if let error = model.scannerError {
                    Label(error, systemImage: "exclamationmark.triangle.fill")
                        .font(.subheadline)
                        .foregroundStyle(Brand.hangUp)
                        .accessibilityIdentifier("scanner-error")
                }

                L10n.text("scanner.instructions")
                    .font(.callout)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)

                pasteSection
            }
            .padding(20)
        }
        .navigationTitle(L10n.string("scanner.title"))
        .task {
            if access == .notDetermined {
                access = await CameraAccess.request()
            }
        }
    }

    @ViewBuilder
    private var viewfinder: some View {
        switch access {
        case .available:
            ZStack {
                QRCameraView(resetToken: resetToken) { code in
                    if model.handleScanned(code) {
                        Haptics.success()
                    } else {
                        Haptics.warning()
                        // Not ours: listen again after a moment.
                        DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { resetToken += 1 }
                    }
                }
                ViewfinderCorners()
                    .padding(36)
                    .allowsHitTesting(false)
            }
            .accessibilityLabel(L10n.string("scanner.cameraLabel"))
        case .notDetermined:
            cameraPlaceholder(symbol: "camera", textKey: "scanner.camera.asking", action: nil)
        case .denied:
            cameraPlaceholder(symbol: "camera.fill", textKey: "scanner.camera.denied", action: (L10n.string("scanner.camera.openSettings"), {
                if let url = URL(string: UIApplication.openSettingsURLString) {
                    UIApplication.shared.open(url)
                }
            }))
        case .unavailable:
            cameraPlaceholder(symbol: "camera.metering.unknown", textKey: "scanner.camera.unavailable", action: nil)
        }
    }

    private func cameraPlaceholder(symbol: String, textKey: LocalizedStringKey, action: (String, () -> Void)?) -> some View {
        VStack(spacing: 14) {
            Image(systemName: symbol)
                .font(.system(size: 34, weight: .regular))
                .foregroundStyle(.secondary)
                .accessibilityHidden(true)
            L10n.text(textKey)
                .font(.callout)
                .multilineTextAlignment(.center)
                .foregroundStyle(.secondary)
                .padding(.horizontal, 28)

            if let action {
                Button(action.0, action: action.1)
                    .font(.callout.weight(.semibold))
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color(.secondarySystemBackground))
    }

    private var pasteSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            if showsPasteField || access != .available {
                L10n.text("scanner.field")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(.secondary)
                TextField("https://fullstackstudio.nl/fsvoip/pair?t=…", text: $pasted, axis: .vertical)
                    .textFieldStyle(.roundedBorder)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .keyboardType(.URL)
                    .onChange(of: pasted) { _ in model.clearScannerError() }
                    .accessibilityIdentifier("link-field")

                HStack(spacing: 12) {
                    Button {
                        if let text = UIPasteboard.general.string {
                            pasted = text
                        }
                    } label: {
                        Label(L10n.string("scanner.paste"), systemImage: "doc.on.clipboard")
                    }
                    .buttonStyle(SecondaryButtonStyle())

                    Button(L10n.string("scanner.use")) {
                        model.handleScanned(pasted)
                    }
                    .buttonStyle(PrimaryButtonStyle())
                    .disabled(pasted.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    .accessibilityIdentifier("use-link-button")
                }
            } else {
                Button {
                    showsPasteField = true
                } label: {
                    L10n.text("scanner.pasteInstead")
                        .font(.callout.weight(.semibold))
                }
            }
        }
    }
}

/// Four corner brackets: the target for the QR code.
private struct ViewfinderCorners: View {
    var body: some View {
        GeometryReader { geometry in
            let side = min(geometry.size.width, geometry.size.height)
            let arm = side * 0.16

            Path { path in
                let rect = CGRect(x: (geometry.size.width - side) / 2, y: (geometry.size.height - side) / 2, width: side, height: side)

                path.move(to: CGPoint(x: rect.minX, y: rect.minY + arm))
                path.addLine(to: CGPoint(x: rect.minX, y: rect.minY))
                path.addLine(to: CGPoint(x: rect.minX + arm, y: rect.minY))

                path.move(to: CGPoint(x: rect.maxX - arm, y: rect.minY))
                path.addLine(to: CGPoint(x: rect.maxX, y: rect.minY))
                path.addLine(to: CGPoint(x: rect.maxX, y: rect.minY + arm))

                path.move(to: CGPoint(x: rect.maxX, y: rect.maxY - arm))
                path.addLine(to: CGPoint(x: rect.maxX, y: rect.maxY))
                path.addLine(to: CGPoint(x: rect.maxX - arm, y: rect.maxY))

                path.move(to: CGPoint(x: rect.minX + arm, y: rect.maxY))
                path.addLine(to: CGPoint(x: rect.minX, y: rect.maxY))
                path.addLine(to: CGPoint(x: rect.minX, y: rect.maxY - arm))
            }
            .stroke(Brand.lime, style: StrokeStyle(lineWidth: 5, lineCap: .round, lineJoin: .round))
        }
    }
}

// MARK: - Steps

struct ConfirmPairingView: View {
    @ObservedObject var model: FSVoipAppModel

    var body: some View {
        StepLayout(symbol: "link", titleKey: "pairing.confirm.title", bodyKey: "pairing.confirm.body") {
            Button {
                Task { await model.confirmPairing() }
            } label: {
                L10n.text("pairing.confirm.action")
            }
            .buttonStyle(PrimaryButtonStyle())
            .accessibilityIdentifier("pair-button")

            Button {
                model.closePairing()
            } label: {
                L10n.text("action.cancel")
            }
            .buttonStyle(SecondaryButtonStyle())
            .accessibilityIdentifier("discard-button")
        }
    }
}

struct PairingProgressView: View {
    var body: some View {
        VStack(spacing: 18) {
            ProgressView()
                .controlSize(.large)
            L10n.text("pairing.progress")
                .font(.headline)
            L10n.text("pairing.progress.body")
                .font(.callout)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .padding(32)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

struct PairedView: View {
    @ObservedObject var model: FSVoipAppModel
    let account: StoredAccount

    var body: some View {
        StepLayout(symbol: "checkmark", titleKey: "pairing.done.title", bodyKey: "pairing.done.body") {
            VStack(alignment: .leading, spacing: 4) {
                Text(account.displayLabel)
                    .font(.headline)
                Text(account.customerName)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(16)
            .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            .padding(.bottom, 8)

            Button {
                model.closePairing()
            } label: {
                L10n.text("pairing.done.action")
            }
            .buttonStyle(PrimaryButtonStyle())
            .accessibilityIdentifier("pairing-done-button")
        }
        .onAppear { Haptics.success() }
    }
}

struct PairingFailedView: View {
    @ObservedObject var model: FSVoipAppModel
    let canRetry: Bool
    let failure: PairingFailure

    var body: some View {
        StepLayout(symbol: "exclamationmark", titleKey: "pairing.failed.title", bodyText: FSVoipAppModel.message(for: failure), tint: Brand.hangUp.opacity(0.14), symbolColor: Brand.hangUp) {
            if canRetry {
                Button {
                    Task { await model.confirmPairing() }
                } label: {
                    L10n.text("action.retry")
                }
                .buttonStyle(PrimaryButtonStyle())
            } else {
                Button {
                    model.restartScan()
                } label: {
                    L10n.text("pairing.failed.scanAgain")
                }
                .buttonStyle(PrimaryButtonStyle())
            }

            Button {
                model.closePairing()
            } label: {
                L10n.text("action.close")
            }
            .buttonStyle(SecondaryButtonStyle())
        }
        .onAppear { Haptics.warning() }
    }
}

/// Icon, title, text, actions at the bottom: the shape every pairing step shares.
private struct StepLayout<Actions: View>: View {
    let symbol: String
    let titleKey: LocalizedStringKey
    var bodyKey: LocalizedStringKey?
    var bodyText: String?
    var tint: Color = Brand.lime
    var symbolColor: Color = Brand.ink
    @ViewBuilder let actions: () -> Actions

    init(symbol: String, titleKey: LocalizedStringKey, bodyKey: LocalizedStringKey? = nil, bodyText: String? = nil, tint: Color = Brand.lime, symbolColor: Color = Brand.ink, @ViewBuilder actions: @escaping () -> Actions) {
        self.symbol = symbol
        self.titleKey = titleKey
        self.bodyKey = bodyKey
        self.bodyText = bodyText
        self.tint = tint
        self.symbolColor = symbolColor
        self.actions = actions
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Spacer(minLength: 16)

            Image(systemName: symbol)
                .font(.system(size: 28, weight: .bold))
                .foregroundStyle(symbolColor)
                .frame(width: 64, height: 64)
                .background(tint, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
                .padding(.bottom, 24)
                .accessibilityHidden(true)

            L10n.text(titleKey)
                .font(.title.bold())
                .padding(.bottom, 10)

            Group {
                if let bodyKey {
                    L10n.text(bodyKey)
                } else if let bodyText {
                    Text(bodyText)
                }
            }
            .font(.body)
            .foregroundStyle(.secondary)
            .fixedSize(horizontal: false, vertical: true)

            Spacer(minLength: 24)

            VStack(spacing: 12) {
                actions()
            }
        }
        .padding(24)
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}
