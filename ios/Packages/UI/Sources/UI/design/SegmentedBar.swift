// SPDX-License-Identifier: AGPL-3.0-or-later
import SwiftUI

/// Two to four options in one row (Alle oproepen | Mijn oproepen): the selected one in lime text on a raised surface.
struct SegmentedBar<Value: Hashable>: View {
    struct Option: Identifiable {
        let value: Value
        let title: String

        var id: Value { value }
    }

    let options: [Option]
    @Binding var selection: Value

    var body: some View {
        HStack(spacing: Theme.Spacing.xs) {
            ForEach(options) { option in
                let isSelected = option.value == selection

                Button {
                    selection = option.value
                } label: {
                    Text(option.title)
                        .font(.subheadline.weight(isSelected ? .semibold : .regular))
                        .foregroundStyle(isSelected ? Theme.accent : Theme.textSecondary)
                        .multilineTextAlignment(.center)
                        .fixedSize(horizontal: false, vertical: true)
                        .frame(maxWidth: .infinity, minHeight: 36)
                        .padding(.horizontal, Theme.Spacing.s)
                        .background(isSelected ? Theme.segmentSelected : Color.clear, in: Theme.card(Theme.Radius.s - 2))
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(isSelected ? .isSelected : [])
            }
        }
        .padding(Theme.Spacing.xs)
        .background(Theme.segmentTrack, in: Theme.card(Theme.Radius.s))
    }
}
