// SPDX-License-Identifier: AGPL-3.0-or-later
import SwiftUI

/// The inside of a choice row (title, subtitle, the check), without the row frame: for rows that put something in front of it,
/// like an avatar.
struct ChoiceRowLabel: View {
    let title: String
    var subtitle: String?
    let isSelected: Bool

    var body: some View {
        HStack(spacing: Theme.Spacing.m) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.body)
                    .foregroundStyle(Theme.textPrimary)
                    .fixedSize(horizontal: false, vertical: true)

                if let subtitle {
                    Text(subtitle)
                        .font(.footnote)
                        .foregroundStyle(Theme.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }

            Spacer(minLength: Theme.Spacing.s)

            if isSelected {
                Image(systemName: "checkmark")
                    .font(.body.weight(.semibold))
                    .foregroundStyle(Theme.accentText)
                    .accessibilityHidden(true)
            }
        }
    }
}

/// One choice out of a list (an account, a number, a theme): a check on the chosen one.
struct ChoiceRow: View {
    let title: String
    var subtitle: String?
    let isSelected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            ChoiceRowLabel(title: title, subtitle: subtitle, isSelected: isSelected)
                .settingsRowChrome()
        }
        .buttonStyle(RowButtonStyle())
        .accessibilityAddTraits(isSelected ? .isSelected : [])
    }
}
