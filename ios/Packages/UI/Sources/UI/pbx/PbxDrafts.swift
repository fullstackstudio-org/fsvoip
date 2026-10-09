// SPDX-License-Identifier: AGPL-3.0-or-later
//
// The state of a form while it is open, and the translation to a PATCH body that holds only what changed. The server decides
// what is valid; these types only keep "what did the user touch" so an untouched field is never sent (and never overwritten).

import Core
import Foundation

// MARK: - Device

struct DeviceDraft: Equatable {
    var voicemailEnabled: Bool
    var dnd: Bool
    var noAnswerSeconds: Int
    var noAnswerTarget: PbxTarget?
    var busyTarget: PbxTarget?
    var notRegisteredTarget: PbxTarget?
    var forwardAlways: PbxTarget?
    var followMe: [FollowMeStep]

    static let noAnswerRange = 5 ... 120
    static let followMeLimit = 10

    init(_ device: PbxDevice) {
        voicemailEnabled = device.voicemailEnabled
        dnd = device.dnd
        noAnswerSeconds = device.noAnswerSeconds
        noAnswerTarget = device.noAnswerTarget
        busyTarget = device.busyTarget
        notRegisteredTarget = device.notRegisteredTarget
        forwardAlways = device.forwardAlways
        followMe = device.followMe
    }

    /// Only the changed keys, always with the `version` the form was opened with. `nil` = nothing changed.
    func patch(from original: PbxDevice) -> PbxDevicePatch? {
        let initial = DeviceDraft(original)

        guard self != initial else {
            return nil
        }

        return PbxDevicePatch(
            version: original.version,
            voicemailEnabled: voicemailEnabled == initial.voicemailEnabled ? nil : voicemailEnabled,
            dnd: dnd == initial.dnd ? nil : dnd,
            noAnswerSeconds: noAnswerSeconds == initial.noAnswerSeconds ? nil : noAnswerSeconds,
            noAnswerTarget: Self.change(noAnswerTarget, from: initial.noAnswerTarget),
            busyTarget: Self.change(busyTarget, from: initial.busyTarget),
            notRegisteredTarget: Self.change(notRegisteredTarget, from: initial.notRegisteredTarget),
            forwardAlways: Self.change(forwardAlways, from: initial.forwardAlways),
            followMe: followMe == initial.followMe ? nil : followMe
        )
    }

    static func change(_ value: PbxTarget?, from initial: PbxTarget?) -> Change<PbxTarget> {
        value == initial ? .keep : Change(value)
    }
}

// MARK: - Ring group

struct RingGroupDraft: Equatable {
    var name: String
    var strategy: RingStrategy
    var members: [RingGroupMember]
    var timeoutTarget: PbxTarget?

    static let memberLimit = 50

    /// A new group: everybody at once, nobody in it yet.
    init() {
        name = ""
        strategy = .all
        members = []
        timeoutTarget = nil
    }

    init(_ group: PbxRingGroup) {
        name = group.name
        strategy = group.strategy == .unknown ? .all : group.strategy
        members = group.members
        timeoutTarget = group.timeoutTarget
    }

    var cleanName: String {
        name.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// Form check only: a name and at least one member. The server holds the other rules.
    var isFilledIn: Bool {
        !cleanName.isEmpty && !members.isEmpty
    }

    func creation() -> PbxRingGroupCreate {
        PbxRingGroupCreate(name: cleanName, strategy: strategy, members: members, timeoutTarget: Change(timeoutTarget))
    }

    func patch(from original: PbxRingGroup) -> PbxRingGroupPatch? {
        let initial = RingGroupDraft(original)
        var current = self
        current.name = cleanName

        guard current != initial else {
            return nil
        }

        return PbxRingGroupPatch(
            version: original.version,
            name: current.name == initial.name ? nil : current.name,
            strategy: strategy == initial.strategy ? nil : strategy,
            members: members == initial.members ? nil : members,
            timeoutTarget: DeviceDraft.change(timeoutTarget, from: initial.timeoutTarget)
        )
    }
}

// MARK: - Opening hours

/// One opening interval of a day, edited as text (`HH:MM`; `24:00` = until midnight).
struct HoursInterval: Equatable, Identifiable {
    var id = UUID()
    var from: String
    var to: String

    static func == (lhs: HoursInterval, rhs: HoursInterval) -> Bool {
        lhs.from == rhs.from && lhs.to == rhs.to
    }
}

struct HoursDraft: Equatable {
    /// Monday (1) to Sunday (7).
    var days: [Int: [HoursInterval]]
    var openTarget: PbxTarget?
    var closedTarget: PbxTarget?

    static let weekdays = Array(1 ... 7)
    static let intervalsPerDay = 4
    static let defaultInterval = HoursInterval(from: "09:00", to: "17:00")

    init(_ hours: PbxHours) {
        var days: [Int: [HoursInterval]] = [:]

        for entry in hours.week {
            days[entry.day, default: []].append(HoursInterval(from: entry.from, to: entry.to))
        }

        self.days = days
        openTarget = hours.openTarget
        closedTarget = hours.closedTarget
    }

    /// Equal when the week reads the same (a day switched off and a day that never had hours are the same).
    static func == (lhs: HoursDraft, rhs: HoursDraft) -> Bool {
        lhs.week == rhs.week && lhs.openTarget == rhs.openTarget && lhs.closedTarget == rhs.closedTarget
    }

    var week: [HoursWeekEntry] {
        Self.weekdays.flatMap { day in
            (days[day] ?? []).map { HoursWeekEntry(day: day, from: $0.from, to: $0.to) }
        }
    }

    func patch(from original: PbxHours) -> PbxHoursPatch? {
        let initial = HoursDraft(original)

        guard self != initial else {
            return nil
        }

        return PbxHoursPatch(
            version: original.version,
            week: week == initial.week ? nil : week,
            openTarget: DeviceDraft.change(openTarget, from: initial.openTarget),
            closedTarget: DeviceDraft.change(closedTarget, from: initial.closedTarget)
        )
    }
}

// MARK: - Temporarily closed

/// "Tijdelijk dicht": own dates in the holiday list of the opening hours. The list in a PATCH REPLACES the old one, so every
/// existing holiday (fixed rule or own date) goes along; only the own dates are added or removed.
enum TemporaryClosure {
    static let customDateLimit = 20

    enum PlanError: Error, Equatable {
        case emptyRange
        /// More own dates than the centrale takes; `room` = how many more fit.
        case tooMany(room: Int)
    }

    /// Every day from `from` to `to` (inclusive) as `YYYY-MM-DD`, in the calendar of the opening hours.
    static func dates(from: Date, to: Date, calendar: Calendar) -> [String] {
        let start = calendar.startOfDay(for: from)
        let end = calendar.startOfDay(for: to)

        guard start <= end else {
            return []
        }

        var result: [String] = []
        var day = start

        while day <= end, result.count <= customDateLimit + 1 {
            result.append(dateString(day, calendar: calendar))

            guard let next = calendar.date(byAdding: .day, value: 1, to: day) else {
                break
            }

            day = next
        }

        return result
    }

    static func dateString(_ date: Date, calendar: Calendar) -> String {
        let parts = calendar.dateComponents([.year, .month, .day], from: date)

        return String(format: "%04d-%02d-%02d", parts.year ?? 0, parts.month ?? 0, parts.day ?? 0)
    }

    /// The holiday list to send: all existing ones plus one own date per day of the range (a date that is already there stays
    /// as it is).
    static func closing(_ hours: PbxHours, name: String, dates: [String]) -> Result<[PbxHolidayInput], PlanError> {
        guard !dates.isEmpty else {
            return .failure(.emptyRange)
        }

        let existing = preserved(hours.holidays)
        let have = Set(hours.holidays.compactMap(\.date))
        let fresh = dates.filter { !have.contains($0) }
        let customNow = hours.holidays.filter { $0.date != nil }.count
        let room = max(0, customDateLimit - customNow)

        guard fresh.count <= room else {
            return .failure(.tooMany(room: room))
        }

        let label = name.trimmingCharacters(in: .whitespacesAndNewlines)

        return .success(existing + fresh.map { PbxHolidayInput.custom(name: label.isEmpty ? L10n.string("pbx.closed.defaultName") : label, date: $0) })
    }

    /// The list without one own date.
    static func reopening(_ hours: PbxHours, date: String) -> [PbxHolidayInput] {
        preserved(hours.holidays.filter { $0.date != date })
    }

    /// Every holiday of the opening hours as a PATCH item.
    static func preserved(_ holidays: [PbxHoliday]) -> [PbxHolidayInput] {
        holidays.compactMap { holiday in
            if let rule = holiday.rule {
                return .rule(rule)
            }

            if let date = holiday.date {
                return .custom(name: holiday.name, date: date)
            }

            return nil
        }
    }
}

// MARK: - Targets

/// Turns the choices of the server into what a picker shows and the other way round.
enum TargetChoice {
    /// The option the target belongs to (`external` has no id; `hangup` is not an option of the server).
    static func option(for target: PbxTarget?, in options: [PbxTargetOption]) -> PbxTargetOption? {
        guard let target else {
            return nil
        }

        return options.first { $0.type == target.type && $0.id == target.id }
    }

    static func target(for option: PbxTargetOption, externalNumber: String = "") -> PbxTarget? {
        switch option.type {
        case .external:
            let number = externalNumber.trimmingCharacters(in: .whitespacesAndNewlines)

            return number.isEmpty ? nil : .external(number)
        case .hangup:
            return .hangup
        case .unknown:
            return nil
        default:
            return option.id.map { PbxTarget.object(option.type, id: $0) }
        }
    }

    /// The choices without the object that is being edited (a belgroep cannot send its callers to itself).
    static func excluding(_ id: String?, from options: [PbxTargetOption]) -> [PbxTargetOption] {
        guard let id else {
            return options
        }

        return options.filter { $0.id != id }
    }
}
