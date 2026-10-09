// SPDX-License-Identifier: AGPL-3.0-or-later
import Core
import SwiftUI

/// The call flow of a number as a line: where a caller starts and what happens next, step by step ("openingstijden, binnen
/// kantooruren belgroep Iedereen, geen antwoord na 25 sec voicemail"). The line runs down the left; every branch says why it
/// is taken.
struct FlowView: View {
    let node: FlowNode

    var body: some View {
        FlowStepView(node: node, depth: 0)
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier("pbx-flow")
    }
}

private struct FlowStepView: View {
    let node: FlowNode
    let depth: Int

    private static let maxDepth = 8

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            StepRow(node: node)

            if depth < Self.maxDepth {
                ForEach(Array(node.branches.enumerated()), id: \.offset) { _, branch in
                    HStack(alignment: .top, spacing: 0) {
                        // The line of the flow.
                        Capsule()
                            .fill(Brand.ink.opacity(0.22))
                            .frame(width: 2)
                            .padding(.leading, 17)
                            .padding(.vertical, 2)
                            .accessibilityHidden(true)

                        VStack(alignment: .leading, spacing: 6) {
                            Text(PbxVocabulary.when(branch.when))
                                .font(.caption.weight(.semibold))
                                .foregroundStyle(.secondary)
                                .padding(.top, 8)

                            FlowStepView(node: branch.node, depth: depth + 1)
                        }
                        .padding(.leading, 12)
                    }
                }
            } else if !node.branches.isEmpty {
                Text(L10n.string("pbx.flow.more"))
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .padding(.leading, 46)
            }
        }
    }
}

private struct StepRow: View {
    let node: FlowNode

    private var isProblem: Bool {
        switch node.kind {
        case .missing, .noDestination, .loop: return true
        default: return false
        }
    }

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: PbxVocabulary.symbol(node.kind))
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(isProblem ? Brand.hangUp : Brand.ink)
                .frame(width: 36, height: 36)
                .background(Circle().fill(Color(.secondarySystemBackground)))
                .overlay(Circle().strokeBorder(Brand.ink.opacity(0.15), lineWidth: 1))
                .accessibilityHidden(true)

            VStack(alignment: .leading, spacing: 2) {
                Text(PbxVocabulary.title(node))
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(isProblem ? Brand.hangUp : Color.primary)
                    .fixedSize(horizontal: false, vertical: true)

                if let detail = PbxVocabulary.detail(node) {
                    Text(detail)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }

                if node.dnd {
                    Label(L10n.string("pbx.flow.dnd"), systemImage: "moon.fill")
                        .font(.caption)
                        .foregroundStyle(Brand.amber)
                }
            }

            Spacer(minLength: 0)
        }
        .accessibilityElement(children: .combine)
    }
}
