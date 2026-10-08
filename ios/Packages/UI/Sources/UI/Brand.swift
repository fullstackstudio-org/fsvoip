// SPDX-License-Identifier: AGPL-3.0-or-later
import SwiftUI

/// FullStack Studio house style: a lime accent, used sparingly.
enum Brand {
    /// `#c7ff4a`
    static let lime = Color(red: 199 / 255, green: 255 / 255, blue: 74 / 255)
    /// Text on lime.
    static let ink = Color(red: 16 / 255, green: 19 / 255, blue: 23 / 255)
}

struct PrimaryButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.headline)
            .foregroundStyle(Brand.ink)
            .frame(maxWidth: .infinity, minHeight: 52)
            .background(Brand.lime.opacity(configuration.isPressed ? 0.8 : 1), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
    }
}
