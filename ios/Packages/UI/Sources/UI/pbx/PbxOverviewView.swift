// SPDX-License-Identifier: AGPL-3.0-or-later
import Core
import SwiftUI

/// The first screen of the section: every number of the centrale with where its callers go, and the way to the rest.
struct PbxOverviewView: View {
    @ObservedObject var model: PbxSectionModel

    var body: some View {
        List {
            PbxNoticesSection(model: model)

            if let overview = model.overview {
                summary(overview)

                if overview.numbers.isEmpty {
                    if let flow = overview.entryFlow {
                        Section {
                            FlowView(node: flow)
                                .padding(.vertical, 6)
                        } header: {
                            Text(L10n.string("pbx.overview.entry"))
                        }
                    }
                } else {
                    ForEach(overview.numbers) { number in
                        numberSection(number)
                    }
                }

                Section {
                    NavigationLink {
                        DevicesView(model: model)
                    } label: {
                        row(L10n.string("pbx.devices.title"), symbol: "phone.fill", detail: devicesRowDetail(overview))
                    }
                    .accessibilityIdentifier("pbx-devices-link")

                    NavigationLink {
                        RingGroupsView(model: model)
                    } label: {
                        row(L10n.string("pbx.ringGroups.title"), symbol: "person.3.fill", detail: "\(overview.ringGroupCount)")
                    }
                    .accessibilityIdentifier("pbx-ringgroups-link")

                    NavigationLink {
                        HoursView(model: model)
                    } label: {
                        row(L10n.string("pbx.hours.title"), symbol: "clock.fill", detail: nil)
                    }
                    .accessibilityIdentifier("pbx-hours-link")
                } header: {
                    Text(L10n.string("pbx.overview.settings"))
                }
            } else if model.isLoading {
                Section {
                    HStack {
                        Spacer()
                        ProgressView()
                        Spacer()
                    }
                }
            } else if model.loadFailure != nil || model.isOutdated {
                Section {
                    Button(L10n.string("action.retry")) {
                        Task { await model.refresh(.overview) }
                    }
                }
            }
        }
        .navigationTitle(L10n.string("pbx.title"))
        .refreshable { await model.refresh(.overview) }
        .task { await model.loadIfNeeded(.overview) }
        .onDisappear { model.stopPolling() }
        .accessibilityIdentifier("pbx-overview")
    }

    private func summary(_ overview: PbxOverview) -> some View {
        Section {
            VStack(alignment: .leading, spacing: 4) {
                Text(overview.pbx.name)
                    .font(.title3.weight(.bold))
                Text(summaryLine(overview))
                    .font(.subheadline)
                    .foregroundStyle(.secondary)

                if overview.outboundBlocked {
                    Label(L10n.string("pbx.overview.outboundBlocked"), systemImage: "phone.down.fill")
                        .font(.footnote)
                        .foregroundStyle(Brand.amber)
                        .padding(.top, 4)
                }
            }
            .padding(.vertical, 4)
            .accessibilityElement(children: .combine)
        }
    }

    private func summaryLine(_ overview: PbxOverview) -> String {
        let devices = devicesDetail(overview)
        let groups = String(format: L10n.string("pbx.count.ringGroups"), overview.ringGroupCount)

        return "\(devices) · \(groups)"
    }

    private func devicesDetail(_ overview: PbxOverview) -> String {
        if let connected = overview.connectedCount {
            return String(format: L10n.string("pbx.count.devices.connected"), overview.deviceCount, connected)
        }

        return String(format: L10n.string("pbx.count.devices"), overview.deviceCount)
    }

    private func devicesRowDetail(_ overview: PbxOverview) -> String {
        if let connected = overview.connectedCount {
            return String(format: L10n.string("pbx.row.devices.connected"), overview.deviceCount, connected)
        }

        return "\(overview.deviceCount)"
    }

    private func numberSection(_ number: PbxNumber) -> some View {
        Section {
            if let flow = number.flow {
                FlowView(node: flow)
                    .padding(.vertical, 6)
            } else {
                Text(L10n.string("pbx.flow.none"))
                    .foregroundStyle(.secondary)
            }

            NavigationLink {
                NumberRoutingView(model: model, number: number)
            } label: {
                Label(L10n.string("pbx.routing.change"), systemImage: "arrow.triangle.branch")
            }
            .accessibilityIdentifier("pbx-routing-link")
        } header: {
            HStack {
                Text(PbxVocabulary.formatNumber(number.number))
                    .font(.headline)
                    .foregroundStyle(.primary)
                    .textCase(nil)
                Spacer()
                PbxSyncBadge(sync: number.sync)
                    .textCase(nil)
            }
            .accessibilityElement(children: .combine)
        }
    }

    private func row(_ title: String, symbol: String, detail: String?) -> some View {
        HStack(spacing: 12) {
            Image(systemName: symbol)
                .frame(width: 28)
                .accessibilityHidden(true)
            Text(title)
            Spacer()
            if let detail {
                Text(detail)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
        .accessibilityElement(children: .combine)
    }
}
