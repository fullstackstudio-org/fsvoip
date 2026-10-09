// SPDX-License-Identifier: AGPL-3.0-or-later
//
// The forms of a number's chain, as values. Each draft is made from a `NumberChain`, remembers nothing else, and turns into the
// request of its step with only what differs from the BASELINE (the chain the form was opened with, or rebased onto after a
// "changed in the meantime"). Pure, so the bodies and the stale recovery can be tested without a screen.

import Core
import Foundation

/// Keep what the user changed, take the rest from the fresh chain: field by field, mine wins only where it differs from the
/// baseline the form started from.
@inline(__always)
func rebased<Value: Equatable>(_ mine: Value, base: Value, fresh: Value) -> Value {
    mine == base ? fresh : mine
}

/// A form of one step: made from a chain, and able to move onto a fresh chain without losing what the user changed.
protocol ChainDraft: Equatable {
    init(_ chain: NumberChain)
    mutating func rebase(from base: Self, to fresh: Self)
}

extension ChainDraft {
    /// After "changed in the meantime": take the fresh chain for everything the user did not touch, and make it the new baseline
    /// (so a second save sends the user's changes with the new versions, and nothing else).
    mutating func rebase(onto fresh: NumberChain, baseline: inout Self) {
        let freshDraft = Self(fresh)
        rebase(from: baseline, to: freshDraft)
        baseline = freshDraft
    }
}

extension NumberChain {
    /// The versions of the chain and its parts, sent with every step so the server can see a lost update.
    var stepVersions: ChainVersions {
        var objects: [String: Int] = [:]

        if let hours {
            objects[hours.id] = hours.version
        }

        if let welcome {
            objects[welcome.menuId] = welcome.version
        }

        switch forwarding {
        case let .standard(standard):
            if let groupId = standard.groupId {
                objects[groupId] = standard.version
            }
        case let .menu(menu):
            objects[menu.menuId] = menu.version
        case .unknown, .none:
            break
        }

        return ChainVersions(objects: objects.isEmpty ? nil : objects, number: version)
    }

    /// The label of a number: its name, else the number itself.
    var displayName: String {
        let trimmed = name?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""

        return trimmed.isEmpty ? PbxVocabulary.formatNumber(number) : trimmed
    }
}

/// What a step does when nothing is chosen yet: the shared voicemail box (the server knows which one).
let defaultChainFallback = Fallback.voicemail(boxId: nil, ofDevice: false)

/// Only a selectable fallback can be sent; `other` stays as it is on the server.
private func sendable(_ fallback: Fallback) -> Fallback? {
    fallback.isSelectable ? fallback : nil
}

// MARK: - Name

struct NumberNameDraft: ChainDraft {
    var name: String

    init(_ chain: NumberChain) {
        name = chain.name ?? ""
    }

    private var cleaned: String {
        name.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    mutating func rebase(from base: NumberNameDraft, to fresh: NumberNameDraft) {
        name = rebased(name, base: base.name, fresh: fresh.name)
    }

    func step(baseline: NumberNameDraft, chain: NumberChain) -> ChainNameStep? {
        guard cleaned != baseline.cleaned else {
            return nil
        }

        return ChainNameStep(name: cleaned.isEmpty ? nil : cleaned, versions: chain.stepVersions)
    }
}

// MARK: - Opening hours

struct NumberHoursDraft: ChainDraft {
    var enabled: Bool
    var week: WeekPlan
    /// All Dutch national holidays use the "closed" behaviour.
    var national: Bool
    /// Own dates (holiday, building holiday, a day off).
    var dates: [ChainHolidayDate]
    var closed: Fallback
    /// `nil` = on a holiday the same as outside opening hours.
    var holiday: Fallback?

    static let ownDateLimit = TemporaryClosure.customDateLimit

    init(_ chain: NumberChain) {
        if let hours = chain.hours {
            enabled = true
            week = WeekPlan(hours.week)
            national = hours.holidays.national
            dates = hours.holidays.dates.sorted { $0.date < $1.date }
            closed = hours.closed
            holiday = hours.holiday
        } else {
            // Switching opening hours on starts from office hours, closed = the shared voicemail.
            enabled = false
            week = WeekPlan.preset(.office)
            national = true
            dates = []
            closed = defaultChainFallback
            holiday = nil
        }
    }

    /// Every slot has the shape the server takes (the rest is the server's call).
    var isWellFormed: Bool {
        !enabled || week.days.values.allSatisfy { $0.allSatisfy(DayBarsLogic.isValid) && $0.count <= WeekPlan.slotsPerDay }
    }

    mutating func rebase(from base: NumberHoursDraft, to fresh: NumberHoursDraft) {
        enabled = rebased(enabled, base: base.enabled, fresh: fresh.enabled)
        week = rebased(week, base: base.week, fresh: fresh.week)
        national = rebased(national, base: base.national, fresh: fresh.national)
        dates = rebased(dates, base: base.dates, fresh: fresh.dates)
        closed = rebased(closed, base: base.closed, fresh: fresh.closed)
        holiday = rebased(holiday, base: base.holiday, fresh: fresh.holiday)
    }

    /// The request: `hours` (on, off or the week/holidays changed) or `closed` (only what happens when it is closed).
    func step(baseline: NumberHoursDraft, chain: NumberChain) -> (any NumberChainStepRequest)? {
        let versions = chain.stepVersions

        guard enabled || baseline.enabled else {
            return nil
        }

        if !enabled {
            return ChainHoursStep(enabled: false, versions: versions)
        }

        if !baseline.enabled {
            // Switching on: everything the new (or re-linked) opening hours need.
            return ChainHoursStep(
                enabled: true,
                week: week.entries,
                holidays: ChainHolidaysInput(national: national, dates: dates),
                closed: sendable(closed),
                holiday: holidayChange(from: baseline, always: true),
                versions: versions
            )
        }

        let weekChanged = week != baseline.week
        let nationalChanged = national != baseline.national
        let datesChanged = dates != baseline.dates
        let closedChanged = closed != baseline.closed
        let holidayChanged = holiday != baseline.holiday

        guard weekChanged || nationalChanged || datesChanged || closedChanged || holidayChanged else {
            return nil
        }

        if !weekChanged, !nationalChanged, !datesChanged {
            return ChainClosedStep(
                closed: closedChanged ? sendable(closed) : nil,
                holiday: holidayChange(from: baseline, always: false),
                versions: versions
            )
        }

        let holidays: ChainHolidaysInput? = nationalChanged || datesChanged
            ? ChainHolidaysInput(national: nationalChanged ? national : nil, dates: datesChanged ? dates : nil)
            : nil

        return ChainHoursStep(
            enabled: true,
            week: weekChanged ? week.entries : nil,
            holidays: holidays,
            closed: closedChanged ? sendable(closed) : nil,
            holiday: holidayChange(from: baseline, always: false),
            versions: versions
        )
    }

    private func holidayChange(from baseline: NumberHoursDraft, always: Bool) -> Change<Fallback> {
        guard always || holiday != baseline.holiday else {
            return .keep
        }

        guard let holiday else {
            return always ? .keep : .clear
        }

        return holiday.isSelectable ? .set(holiday) : .keep
    }

    // MARK: Own dates

    /// Adds every day of a range as an own date (a date that is already there stays as it is). `nil` when the limit is passed.
    func adding(dates newDates: [String], name: String) -> [ChainHolidayDate]? {
        let label = name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? L10n.string("pbx.closed.defaultName") : name.trimmingCharacters(in: .whitespacesAndNewlines)
        var result = dates

        for date in newDates where !result.contains(where: { $0.date == date }) {
            result.append(ChainHolidayDate(name: label, date: date))
        }

        guard result.count <= Self.ownDateLimit else {
            return nil
        }

        return result.sorted { $0.date < $1.date }
    }
}

// MARK: - Welcome message

struct NumberWelcomeDraft: ChainDraft {
    var enabled: Bool
    var soundId: String?

    init(_ chain: NumberChain) {
        enabled = chain.welcome != nil
        soundId = chain.welcome?.soundId
    }

    /// A welcome message needs a sound.
    var canSave: Bool {
        !enabled || soundId != nil
    }

    mutating func rebase(from base: NumberWelcomeDraft, to fresh: NumberWelcomeDraft) {
        enabled = rebased(enabled, base: base.enabled, fresh: fresh.enabled)
        soundId = rebased(soundId, base: base.soundId, fresh: fresh.soundId)
    }

    func step(baseline: NumberWelcomeDraft, chain: NumberChain) -> ChainWelcomeStep? {
        guard self != baseline else {
            return nil
        }

        let sound: Change<String> = enabled && soundId != baseline.soundId ? Change(soundId) : .keep

        return ChainWelcomeStep(enabled: enabled, soundId: sound, versions: chain.stepVersions)
    }
}

// MARK: - Forwarding

struct NumberForwardingDraft: ChainDraft {
    enum Kind: Hashable {
        case standard
        case menu
    }

    static let menuDigits = ["1", "2", "3", "4", "5", "6", "7", "8", "9", "0"]
    static let repeatsRange = 0 ... 5
    static let ringSecondsRange = 5 ... 120

    var kind: Kind

    // Standard
    var strategy: RingStrategy
    var members: [ChainMember]
    var unanswered: Fallback

    // Menu
    var greetingSoundId: String?
    var repeats: Int
    var timeoutSeconds: Int
    var defaultKey: String?
    var noChoice: Fallback
    var keys: [ChainKey]

    init(_ chain: NumberChain) {
        kind = .standard
        strategy = .all
        members = []
        unanswered = defaultChainFallback
        greetingSoundId = nil
        repeats = 1
        timeoutSeconds = 5
        defaultKey = nil
        noChoice = defaultChainFallback
        keys = []

        switch chain.forwarding {
        case let .standard(standard):
            kind = .standard
            strategy = standard.strategy == .unknown ? .all : standard.strategy
            members = standard.members
            unanswered = standard.unanswered
            noChoice = standard.unanswered
        case let .menu(menu):
            kind = .menu
            greetingSoundId = menu.greetingSoundId
            repeats = menu.repeats
            timeoutSeconds = menu.timeoutSeconds
            defaultKey = menu.defaultKey
            noChoice = menu.noChoice
            keys = menu.keys
            unanswered = menu.noChoice
        case .unknown, .none:
            break
        }
    }

    // MARK: Standard helpers

    /// Members the device list does not know (a phone of another kind, or one the app may not list): kept and sent along as they
    /// are, so they are shown read-only instead of travelling invisibly.
    func members(notIn deviceIds: Set<String>) -> [ChainMember] {
        members.filter { !deviceIds.contains($0.deviceId) }
    }

    func isMember(_ deviceId: String) -> Bool {
        members.contains { $0.deviceId == deviceId }
    }

    mutating func setMember(_ deviceId: String, on: Bool) {
        if on {
            guard !isMember(deviceId) else { return }

            members.append(ChainMember(deviceId: deviceId, delaySeconds: 0, timeoutSeconds: ringSeconds ?? 25))
        } else {
            members.removeAll { $0.deviceId == deviceId }
        }
    }

    /// How long the phones ring, when every member rings equally long.
    var ringSeconds: Int? {
        let values = Set(members.map(\.timeoutSeconds))

        return values.count == 1 ? values.first : nil
    }

    mutating func setRingSeconds(_ seconds: Int) {
        for index in members.indices {
            members[index].timeoutSeconds = seconds
        }
    }

    // MARK: Menu helpers

    func key(_ digit: String) -> ChainKey? {
        keys.first { $0.digit == digit }
    }

    /// `nil` switches the key off. A key the portal set up (`editable == false`) is never changed here.
    mutating func setKey(_ digit: String, target: PbxTarget?) {
        if let existing = key(digit), !existing.editable {
            return
        }

        keys.removeAll { $0.digit == digit }

        if let target {
            keys.append(ChainKey(digit: digit, target: target))
            keys.sort { Self.order($0.digit) < Self.order($1.digit) }
        }

        if let defaultKey, key(defaultKey) == nil {
            self.defaultKey = nil
        }
    }

    private static func order(_ digit: String) -> Int {
        menuDigits.firstIndex(of: digit) ?? menuDigits.count
    }

    private var editableKeys: [ChainKey] {
        keys.filter(\.editable)
    }

    // MARK: Saving

    /// The form has what its kind needs: a menu has at least one key; a standard forwarding at least one phone.
    var canSave: Bool {
        switch kind {
        case .standard: return !members.isEmpty
        case .menu: return !keys.isEmpty
        }
    }

    mutating func rebase(from base: NumberForwardingDraft, to fresh: NumberForwardingDraft) {
        kind = rebased(kind, base: base.kind, fresh: fresh.kind)
        strategy = rebased(strategy, base: base.strategy, fresh: fresh.strategy)
        members = rebased(members, base: base.members, fresh: fresh.members)
        unanswered = rebased(unanswered, base: base.unanswered, fresh: fresh.unanswered)
        greetingSoundId = rebased(greetingSoundId, base: base.greetingSoundId, fresh: fresh.greetingSoundId)
        repeats = rebased(repeats, base: base.repeats, fresh: fresh.repeats)
        timeoutSeconds = rebased(timeoutSeconds, base: base.timeoutSeconds, fresh: fresh.timeoutSeconds)
        defaultKey = rebased(defaultKey, base: base.defaultKey, fresh: fresh.defaultKey)
        noChoice = rebased(noChoice, base: base.noChoice, fresh: fresh.noChoice)
        keys = rebased(keys, base: base.keys, fresh: fresh.keys)
    }

    func step(baseline: NumberForwardingDraft, chain: NumberChain) -> (any NumberChainStepRequest)? {
        let versions = chain.stepVersions
        let switched = kind != baseline.kind

        switch kind {
        case .standard:
            let strategyChanged = switched || strategy != baseline.strategy
            let membersChanged = switched || members != baseline.members
            let unansweredChanged = switched || unanswered != baseline.unanswered

            guard strategyChanged || membersChanged || unansweredChanged else {
                return nil
            }

            return ChainStandardForwardingStep(
                strategy: strategyChanged ? strategy : nil,
                members: membersChanged ? members : nil,
                unanswered: unansweredChanged ? sendable(unanswered) : nil,
                versions: versions
            )
        case .menu:
            let greetingChanged = switched || greetingSoundId != baseline.greetingSoundId
            let repeatsChanged = switched || repeats != baseline.repeats
            let timeoutChanged = switched || timeoutSeconds != baseline.timeoutSeconds
            let defaultChanged = switched || defaultKey != baseline.defaultKey
            let noChoiceChanged = switched || noChoice != baseline.noChoice
            let keysChanged = switched || keys != baseline.keys

            guard greetingChanged || repeatsChanged || timeoutChanged || defaultChanged || noChoiceChanged || keysChanged else {
                return nil
            }

            return ChainMenuForwardingStep(
                greetingSoundId: greetingChanged ? (switched && greetingSoundId == nil ? .keep : Change(greetingSoundId)) : .keep,
                repeats: repeatsChanged ? repeats : nil,
                timeoutSeconds: timeoutChanged ? timeoutSeconds : nil,
                defaultKey: defaultChanged ? (switched && defaultKey == nil ? .keep : Change(defaultKey)) : .keep,
                noChoice: noChoiceChanged ? sendable(noChoice) : nil,
                keys: keysChanged ? editableKeys : nil,
                versions: versions
            )
        }
    }
}

// MARK: - Recording

struct NumberRecordingDraft: ChainDraft {
    var enabled: Bool
    var announcementSoundId: String?
    /// The customer accepted the monthly price (only asked when this save starts the billing).
    var costAccepted: Bool

    init(_ chain: NumberChain) {
        enabled = chain.recording.enabled
        announcementSoundId = chain.recording.announcementSoundId
        costAccepted = false
    }

    /// Switching on starts the billing: show the price and ask for consent (plan D11: only when billing is not active yet).
    func needsConsent(_ recording: ChainRecording, cost: RecordingCost? = nil) -> Bool {
        let price = cost ?? recording.cost

        return enabled && !recording.billingActive && (price?.priceE4 ?? 0) > 0
    }

    mutating func rebase(from base: NumberRecordingDraft, to fresh: NumberRecordingDraft) {
        enabled = rebased(enabled, base: base.enabled, fresh: fresh.enabled)
        announcementSoundId = rebased(announcementSoundId, base: base.announcementSoundId, fresh: fresh.announcementSoundId)
    }

    func patch(baseline: NumberRecordingDraft, chain: NumberChain) -> NumberRecordingPatch? {
        guard enabled != baseline.enabled || announcementSoundId != baseline.announcementSoundId else {
            return nil
        }

        let announcement: Change<String> = announcementSoundId != baseline.announcementSoundId ? Change(announcementSoundId) : .keep
        // Consent goes along only when it was asked and given (the toggle exists only when this save starts the billing).
        let consent: Bool? = enabled && costAccepted ? true : nil

        return NumberRecordingPatch(enabled: enabled, announcementSoundId: announcement, costAccepted: consent, version: chain.version)
    }
}

// MARK: - Showing a price

enum RecordingPrice {
    /// "€ 2,00 per maand (excl. btw)".
    static func text(_ cost: RecordingCost, locale: Locale = .current) -> String {
        let amount = Decimal(cost.priceE4) / 10_000
        let formatter = NumberFormatter()
        formatter.numberStyle = .currency
        formatter.currencyCode = "EUR"
        formatter.locale = locale
        let money = formatter.string(from: amount as NSDecimalNumber) ?? "€ \(amount)"

        return String(format: L10n.string(cost.vatIncluded ? "numbers.recording.price.incl" : "numbers.recording.price.excl"), money)
    }
}
