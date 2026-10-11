// SPDX-License-Identifier: AGPL-3.0-or-later
import SwiftUI

/// An empty screen is an invitation: what is missing, and what to do about it.
struct EmptyState: View {
    let symbol: String
    let title: String
    var message: String?
    var actionTitle: String?
    var action: (() -> Void)?

    var body: some View {
        VStack(spacing: Theme.Spacing.m) {
            Image(systemName: symbol)
                .font(.system(size: 34, weight: .regular))
                .foregroundStyle(Theme.textTertiary)
                .accessibilityHidden(true)

            Text(title)
                .font(.headline)
                .foregroundStyle(Theme.textPrimary)
                .multilineTextAlignment(.center)
                .accessibilityAddTraits(.isHeader)

            if let message {
                Text(message)
                    .font(.callout)
                    .foregroundStyle(Theme.textSecondary)
                    .multilineTextAlignment(.center)
            }

            if let actionTitle, let action {
                Button(actionTitle, action: action)
                    .buttonStyle(SecondaryButtonStyle())
                    .padding(.top, Theme.Spacing.s)
                    .frame(maxWidth: 280)
            }
        }
        .fixedSize(horizontal: false, vertical: true)
        .padding(Theme.Spacing.xl)
        .frame(maxWidth: 420)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}
