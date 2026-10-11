// SPDX-License-Identifier: AGPL-3.0-or-later
import SwiftUI
import UIKit

/// Accessibility helpers shared by every screen: motion that respects "Reduce Motion" and text that is never cut off
/// at the accessibility text sizes.
enum Motion {
    /// `withAnimation`, but without animation when the user asked for reduced motion.
    @MainActor
    static func run(_ animation: Animation = .easeOut(duration: 0.15), _ body: () -> Void) {
        if UIAccessibility.isReduceMotionEnabled {
            body()
        } else {
            withAnimation(animation, body)
        }
    }
}

private struct MotionAnimation<Value: Equatable>: ViewModifier {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    let animation: Animation
    let value: Value

    func body(content: Content) -> some View {
        content.animation(reduceMotion ? nil : animation, value: value)
    }
}

private struct AdaptiveLineLimit: ViewModifier {
    @Environment(\.dynamicTypeSize) private var size
    let limit: Int

    func body(content: Content) -> some View {
        // From the accessibility sizes on a name or a subtitle wraps to as many lines as it needs.
        content.lineLimit(size.isAccessibilitySize ? nil : limit)
    }
}

extension View {
    /// `.animation(_:value:)` that is switched off by "Reduce Motion".
    func motionAnimation<Value: Equatable>(_ animation: Animation = .easeOut(duration: 0.22), value: Value) -> some View {
        modifier(MotionAnimation(animation: animation, value: value))
    }

    /// `.lineLimit(n)` that is lifted at the accessibility text sizes, so nothing is truncated.
    func adaptiveLineLimit(_ limit: Int) -> some View {
        modifier(AdaptiveLineLimit(limit: limit))
    }
}
