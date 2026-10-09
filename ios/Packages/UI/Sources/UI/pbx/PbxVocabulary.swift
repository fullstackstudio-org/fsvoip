// SPDX-License-Identifier: AGPL-3.0-or-later
//
// The words of the "Centrale" section. They are the portal's: toestel, belgroep, wachtrij, keuzemenu, openingstijden,
// voicemail. Never extension, ring group, IVR, trunk or gateway.

import Core
import Foundation

enum PbxVocabulary {
    // MARK: Targets

    static func typeName(_ type: TargetType) -> String {
        switch type {
        case .device: return L10n.string("pbx.type.device")
        case .ringGroup: return L10n.string("pbx.type.ringGroup")
        case .queue: return L10n.string("pbx.type.queue")
        case .ivr: return L10n.string("pbx.type.ivr")
        case .businessHours: return L10n.string("pbx.type.hours")
        case .voicemail: return L10n.string("pbx.type.voicemail")
        case .recording: return L10n.string("pbx.type.recording")
        case .hangup: return L10n.string("pbx.type.hangup")
        case .external: return L10n.string("pbx.type.external")
        case .unknown: return L10n.string("pbx.type.unknown")
        }
    }

    /// The plural heading of a group of choices in the picker.
    static func groupName(_ type: TargetType) -> String {
        switch type {
        case .device: return L10n.string("pbx.group.device")
        case .ringGroup: return L10n.string("pbx.group.ringGroup")
        case .queue: return L10n.string("pbx.group.queue")
        case .ivr: return L10n.string("pbx.group.ivr")
        case .businessHours: return L10n.string("pbx.group.hours")
        case .voicemail: return L10n.string("pbx.group.voicemail")
        case .recording: return L10n.string("pbx.group.recording")
        case .hangup, .external, .unknown: return typeName(type)
        }
    }

    static let pickerOrder: [TargetType] = [.device, .ringGroup, .queue, .ivr, .businessHours, .voicemail, .recording]

    /// "Receptie · 100", "Voicemail van Jan de Vries", "+31 6 12345678", "Ophangen".
    static func name(of option: PbxTargetOption) -> String {
        switch option.type {
        case .voicemail:
            return option.ofDevice
                ? String(format: L10n.string("pbx.voicemail.of"), option.name)
                : String(format: L10n.string("pbx.voicemail.shared"), option.name)
        case .device, .ringGroup, .queue, .ivr, .businessHours:
            return [option.name, option.extensionNumber].compactMap { $0 }.joined(separator: " · ")
        default:
            return option.name
        }
    }

    static func describe(_ target: PbxTarget, options: [PbxTargetOption]) -> String {
        switch target.type {
        case .hangup:
            return L10n.string("pbx.type.hangup")
        case .external:
            let number = (target.number ?? "").trimmingCharacters(in: .whitespaces)

            return number.isEmpty ? L10n.string("pbx.target.unset") : number
        default:
            if let option = TargetChoice.option(for: target, in: options) {
                return name(of: option)
            }

            return L10n.string("pbx.target.gone")
        }
    }

    // MARK: Call flow

    static func symbol(_ kind: FlowKind) -> String {
        switch kind {
        case .device: return "phone.fill"
        case .ringGroup: return "person.3.fill"
        case .queue: return "person.2.wave.2.fill"
        case .ivr: return "circle.grid.3x3.fill"
        case .businessHours: return "clock.fill"
        case .voicemail: return "recordingtape"
        case .recording: return "speaker.wave.2.fill"
        case .hangup: return "phone.down.fill"
        case .external: return "arrow.up.forward.app.fill"
        case .missing: return "exclamationmark.triangle.fill"
        case .loop: return "arrow.triangle.2.circlepath"
        case .noDestination: return "questionmark.circle"
        case .more: return "ellipsis"
        case .unknown: return "questionmark.circle"
        }
    }

    /// The step in words: "Belgroep Iedereen · 200".
    static func title(_ node: FlowNode) -> String {
        let name = node.name ?? ""

        switch node.kind {
        case .device: return String(format: L10n.string("pbx.flow.device"), name)
        case .ringGroup: return String(format: L10n.string("pbx.flow.ringGroup"), name)
        case .queue: return String(format: L10n.string("pbx.flow.queue"), name)
        case .ivr: return String(format: L10n.string("pbx.flow.ivr"), name)
        case .businessHours: return String(format: L10n.string("pbx.flow.hours"), name)
        case .voicemail:
            return node.ofDevice
                ? String(format: L10n.string("pbx.voicemail.of"), name)
                : String(format: L10n.string("pbx.voicemail.shared"), name)
        case .recording: return String(format: L10n.string("pbx.flow.recording"), name)
        case .hangup: return L10n.string("pbx.flow.hangup")
        case .external: return String(format: L10n.string("pbx.flow.external"), node.number ?? "")
        case .missing: return L10n.string("pbx.flow.missing")
        case .loop: return L10n.string("pbx.flow.loop")
        case .noDestination: return L10n.string("pbx.flow.none")
        case .more: return L10n.string("pbx.flow.more")
        case .unknown: return L10n.string("pbx.flow.unknown")
        }
    }

    static func detail(_ node: FlowNode) -> String? {
        switch node.kind {
        case .device, .ringGroup, .queue, .ivr, .businessHours:
            return node.extensionNumber.map { String(format: L10n.string("pbx.flow.internalNumber"), $0) }
        default:
            return nil
        }
    }

    /// The line on the rail before a step: why this step follows ("Buiten openingstijden").
    static func when(_ when: FlowWhen) -> String {
        switch when.kind {
        case .open: return L10n.string("pbx.when.open")
        case .closed: return L10n.string("pbx.when.closed")
        case .noAnswer:
            if let seconds = when.seconds {
                return String(format: L10n.string("pbx.when.noAnswer.seconds"), seconds)
            }

            return L10n.string("pbx.when.noAnswer")
        case .busy: return L10n.string("pbx.when.busy")
        case .timeout: return L10n.string("pbx.when.timeout")
        case .key: return String(format: L10n.string("pbx.when.key"), when.digit ?? "?")
        case .noChoice: return L10n.string("pbx.when.noChoice")
        case .forwardAlways: return L10n.string("pbx.when.forwardAlways")
        case .unknown: return L10n.string("pbx.when.unknown")
        }
    }

    // MARK: Other

    static func strategy(_ strategy: RingStrategy) -> String {
        switch strategy {
        case .all: return L10n.string("pbx.strategy.all")
        case .sequence: return L10n.string("pbx.strategy.sequence")
        case .round: return L10n.string("pbx.strategy.round")
        case .unknown: return L10n.string("pbx.strategy.all")
        }
    }

    static func strategyHint(_ strategy: RingStrategy) -> String {
        switch strategy {
        case .all, .unknown: return L10n.string("pbx.strategy.all.hint")
        case .sequence: return L10n.string("pbx.strategy.sequence.hint")
        case .round: return L10n.string("pbx.strategy.round.hint")
        }
    }

    /// "085 060 7848" for a national ten-digit number; anything else as it is.
    static func formatNumber(_ number: String) -> String {
        let digits = number.filter(\.isNumber)

        guard digits.count == 10, digits == number else {
            return number
        }

        return "\(digits.prefix(3)) \(digits.dropFirst(3).prefix(3)) \(digits.dropFirst(6))"
    }

    static func weekday(_ day: Int, short: Bool = false, calendar: Calendar = .current) -> String {
        let symbols = short ? calendar.shortWeekdaySymbols : calendar.weekdaySymbols

        // Calendar symbols start on Sunday; the API counts 1 = Monday ... 7 = Sunday.
        return symbols[day % 7]
    }

    /// "ma–vr 09:00–17:00": days with the same hours are joined when they follow each other.
    static func hoursSummary(_ week: [HoursWeekEntry], calendar: Calendar = .current) -> String {
        guard !week.isEmpty else {
            return L10n.string("pbx.hours.never")
        }

        var byDay: [Int: String] = [:]

        for entry in week.sorted(by: { ($0.day, $0.from) < ($1.day, $1.from) }) {
            let slot = "\(entry.from)–\(entry.to)"
            byDay[entry.day] = byDay[entry.day].map { "\($0), \(slot)" } ?? slot
        }

        var parts: [String] = []
        var day = 1

        while day <= 7 {
            guard let slot = byDay[day] else {
                day += 1
                continue
            }

            var last = day

            while last < 7, byDay[last + 1] == slot {
                last += 1
            }

            let name = last == day
                ? weekday(day, short: true, calendar: calendar)
                : "\(weekday(day, short: true, calendar: calendar))–\(weekday(last, short: true, calendar: calendar))"
            parts.append("\(name) \(slot)")
            day = last + 1
        }

        return parts.joined(separator: " · ")
    }
}
