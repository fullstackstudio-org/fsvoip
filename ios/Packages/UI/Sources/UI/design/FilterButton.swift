// SPDX-License-Identifier: AGPL-3.0-or-later
import SwiftUI

/// The small pill that opens a filter (Geschiedenis: alle / gemist). Lime outline while a filter is on.
struct FilterButton: View {
    let title: String
    var isActive = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: Theme.Spacing.xs) {
                Image(systemName: "line.3.horizontal.decrease")
                    .font(.footnote.weight(.semibold))
                    .accessibilityHidden(true)
                Text(title)
                    .font(.subheadline.weight(.medium))
                    .fixedSize(horizontal: false, vertical: true)
            }
            .foregroundStyle(isActive ? Theme.accentText : Theme.textPrimary)
            .padding(.horizontal, Theme.Spacing.m)
            .frame(minHeight: 36)
            .background(Theme.raised, in: Capsule())
            .overlay(Capsule().strokeBorder(isActive ? Theme.accentText : Theme.separator, lineWidth: 1))
            .frame(minHeight: Theme.minimumTarget)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(isActive ? .isSelected : [])
    }
}
