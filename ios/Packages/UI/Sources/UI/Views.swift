// SPDX-License-Identifier: AGPL-3.0-or-later
import Core
import SwiftUI

public struct RootView: View {
    @ObservedObject private var model: FSVoipAppModel

    public init(model: FSVoipAppModel) {
        self.model = model
    }

    public var body: some View {
        NavigationStack {
            Group {
                switch model.screen {
                case .onboarding:
                    if model.accounts.isEmpty {
                        OnboardingView(model: model)
                    } else {
                        AccountsListView(model: model)
                    }
                case .pairingLink:
                    PairingReceivedView(model: model)
                }
            }
            .animation(.default, value: model.screen)
        }
        .tint(Brand.ink)
        .sheet(isPresented: $model.isScannerPresented) {
            ScannerPlaceholderView(model: model)
        }
    }
}

struct OnboardingView: View {
    @ObservedObject var model: FSVoipAppModel

    var body: some View {
        VStack(alignment: .leading, spacing: 24) {
            Spacer()

            Image(systemName: "phone.connection.fill")
                .font(.system(size: 44, weight: .semibold))
                .foregroundStyle(Brand.ink)
                .padding(18)
                .background(Brand.lime, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
                .accessibilityHidden(true)

            VStack(alignment: .leading, spacing: 10) {
                L10n.text("onboarding.title")
                    .font(.largeTitle.bold())
                L10n.text("onboarding.subtitle")
                    .font(.body)
                    .foregroundStyle(.secondary)
            }

            if let message = model.errorMessage {
                ErrorBanner(message: message) { model.dismissError() }
            }

            Button {
                model.isScannerPresented = true
            } label: {
                Label {
                    L10n.text("onboarding.scan")
                } icon: {
                    Image(systemName: "qrcode.viewfinder")
                }
            }
            .buttonStyle(PrimaryButtonStyle())
            .accessibilityIdentifier("scan-button")

            L10n.text("onboarding.hint")
                .font(.footnote)
                .foregroundStyle(.secondary)

            Spacer()
        }
        .padding(24)
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

struct ScannerPlaceholderView: View {
    @ObservedObject var model: FSVoipAppModel
    @State private var text = ""

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 20) {
                Image(systemName: "camera.viewfinder")
                    .font(.system(size: 56))
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 24)
                    .accessibilityHidden(true)

                L10n.text("scanner.placeholder")
                    .font(.body)
                    .foregroundStyle(.secondary)

                VStack(alignment: .leading, spacing: 8) {
                    L10n.text("scanner.field")
                        .font(.footnote.weight(.semibold))
                    TextField("https://fullstackstudio.nl/fsvoip/pair?t=…", text: $text, axis: .vertical)
                        .textFieldStyle(.roundedBorder)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .keyboardType(.URL)
                        .accessibilityIdentifier("link-field")
                }

                if let message = model.errorMessage {
                    ErrorBanner(message: message) { model.dismissError() }
                }

                Button {
                    model.handleScanned(text)
                } label: {
                    L10n.text("scanner.use")
                }
                .buttonStyle(PrimaryButtonStyle())
                .disabled(text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                .accessibilityIdentifier("use-link-button")

                Spacer()
            }
            .padding(24)
            .navigationTitle(L10n.string("scanner.title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L10n.string("scanner.cancel")) {
                        model.isScannerPresented = false
                    }
                }
            }
        }
        .presentationDetents([.large])
    }
}

struct PairingReceivedView: View {
    @ObservedObject var model: FSVoipAppModel
    @State private var isWorking = false

    var body: some View {
        VStack(alignment: .leading, spacing: 24) {
            Spacer()

            Image(systemName: "checkmark.seal.fill")
                .font(.system(size: 44, weight: .semibold))
                .foregroundStyle(Brand.ink)
                .padding(18)
                .background(Brand.lime, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
                .accessibilityHidden(true)

            VStack(alignment: .leading, spacing: 10) {
                L10n.text("link.title")
                    .font(.largeTitle.bold())
                L10n.text("link.body")
                    .font(.body)
                    .foregroundStyle(.secondary)
            }

            if let message = model.errorMessage {
                ErrorBanner(message: message) { model.dismissError() }
            }

            if model.canPair {
                Button {
                    isWorking = true
                    Task {
                        await model.confirmPairing()
                        isWorking = false
                    }
                } label: {
                    L10n.text("link.pair")
                }
                .buttonStyle(PrimaryButtonStyle())
                .disabled(isWorking)
                .accessibilityIdentifier("pair-button")
            }

            Button(role: .cancel) {
                model.discardLink()
            } label: {
                L10n.text("link.discard")
                    .frame(maxWidth: .infinity, minHeight: 44)
            }
            .accessibilityIdentifier("discard-button")

            Spacer()
        }
        .padding(24)
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

struct AccountsListView: View {
    @ObservedObject var model: FSVoipAppModel

    var body: some View {
        List {
            Section {
                ForEach(model.accounts) { account in
                    VStack(alignment: .leading, spacing: 2) {
                        Text(account.labelOverride ?? account.label)
                            .font(.headline)
                        Text(account.customerName)
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                    .padding(.vertical, 2)
                }
            }

            Section {
                Button {
                    model.isScannerPresented = true
                } label: {
                    Label(L10n.string("accounts.add"), systemImage: "qrcode.viewfinder")
                }
            }
        }
        .navigationTitle(L10n.string("accounts.title"))
    }
}

struct ErrorBanner: View {
    let message: String
    let dismiss: () -> Void

    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: "exclamationmark.triangle.fill")
                .foregroundStyle(.orange)
                .accessibilityHidden(true)
            Text(message)
                .font(.subheadline)
            Spacer(minLength: 0)
            Button(action: dismiss) {
                Image(systemName: "xmark")
                    .font(.footnote.weight(.bold))
            }
            .accessibilityLabel("Sluiten")
        }
        .padding(12)
        .background(Color.orange.opacity(0.12), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
        .accessibilityIdentifier("error-banner")
    }
}
