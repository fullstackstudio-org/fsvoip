// SPDX-License-Identifier: AGPL-3.0-or-later
import SwiftUI

struct OnboardingView: View {
    @ObservedObject var model: FSVoipAppModel

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Spacer(minLength: 24)

            BrandMark(size: 72)
                .padding(.bottom, 28)

            L10n.text("onboarding.title")
                .font(.largeTitle.bold())
                .padding(.bottom, 10)

            L10n.text("onboarding.subtitle")
                .font(.body)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)

            // What pairing gives you, in the order the user will meet it.
            VStack(alignment: .leading, spacing: 16) {
                OnboardingStep(symbol: "laptopcomputer", textKey: "onboarding.step.portal")
                OnboardingStep(symbol: "qrcode.viewfinder", textKey: "onboarding.step.scan")
                OnboardingStep(symbol: "phone.fill", textKey: "onboarding.step.call")
            }
            .padding(.vertical, 32)

            Spacer(minLength: 24)

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
                .frame(maxWidth: .infinity, alignment: .center)
                .multilineTextAlignment(.center)
                .padding(.top, 14)
        }
        .padding(.horizontal, 24)
        .padding(.bottom, 16)
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

private struct OnboardingStep: View {
    let symbol: String
    let textKey: LocalizedStringKey

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 14) {
            Image(systemName: symbol)
                .font(.body.weight(.semibold))
                .frame(width: 24)
                .foregroundStyle(.secondary)
                .accessibilityHidden(true)
            L10n.text(textKey)
                .font(.callout)
                .fixedSize(horizontal: false, vertical: true)
        }
    }
}
