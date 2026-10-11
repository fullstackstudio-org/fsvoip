// SPDX-License-Identifier: AGPL-3.0-or-later
import SwiftUI

enum Initials {
    /// One or two letters: first and last word of a name. "Jan de Vries" gives "JV", "Receptie" gives "R". Anything that is not
    /// a letter is skipped. Empty when there is nothing to show.
    static func make(from name: String?) -> String {
        let words = (name ?? "")
            .split(whereSeparator: { !$0.isLetter && !$0.isNumber })
            .map(String.init)
            .filter { word in word.first.map { $0.isLetter || $0.isNumber } ?? false }

        guard let first = words.first?.first else {
            return ""
        }

        guard words.count > 1, let last = words.last?.first else {
            return String(first).uppercased()
        }

        return (String(first) + String(last)).uppercased()
    }
}

/// A person as one or two letters on a raised circle, with an optional status dot. No photos, no uploads (plan D13).
struct InitialsAvatar: View {
    let name: String?
    var size: CGFloat = 36
    /// A dot at the bottom right: lime = available, amber = busy/connecting, `nil` = none.
    var dot: Color?

    @ScaledMetric(relativeTo: .body) private var scale: CGFloat = 1

    var body: some View {
        let side = size * scale
        let initials = Initials.make(from: name)

        ZStack {
            Circle().fill(Theme.raised)

            if initials.isEmpty {
                Image(systemName: "person.fill")
                    .font(.system(size: side * 0.42))
                    .foregroundStyle(Theme.textSecondary)
            } else {
                Text(initials)
                    .font(.system(size: side * 0.40, weight: .semibold, design: .rounded))
                    .foregroundStyle(Theme.textPrimary)
                    .minimumScaleFactor(0.6)
                    .lineLimit(1)
            }
        }
        .frame(width: side, height: side)
        .overlay(Circle().strokeBorder(Theme.separator, lineWidth: 1))
        .overlay(alignment: .bottomTrailing) {
            if let dot {
                Circle()
                    .fill(dot)
                    .frame(width: side * 0.30, height: side * 0.30)
                    .overlay(Circle().strokeBorder(Theme.background, lineWidth: 2))
                    .offset(x: 1, y: 1)
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(name ?? "")
    }
}
