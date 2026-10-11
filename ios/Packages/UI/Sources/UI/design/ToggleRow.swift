// SPDX-License-Identifier: AGPL-3.0-or-later
import SwiftUI

/// A switch with the explanation under it. The explanation says what happens, in the words of the person using the app.
struct ToggleRow: View {
    let title: String
    var explanation: String?
    @Binding var isOn: Bool
    var isEnabled = true

    var body: some View {
        Toggle(isOn: $isOn) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.body)
                    .foregroundStyle(Theme.textPrimary)
                    .fixedSize(horizontal: false, vertical: true)

                if let explanation {
                    Text(explanation)
                        .font(.footnote)
                        .foregroundStyle(Theme.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
        }
        .tint(Theme.accent)
        .disabled(!isEnabled)
        .settingsRowChrome()
    }
}
