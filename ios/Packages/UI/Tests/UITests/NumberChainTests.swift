// SPDX-License-Identifier: AGPL-3.0-or-later
import Core
import SwiftUI
import UIKit
import XCTest
@testable import UI

/// The number settings (plan `fsvoip-app-v2`, Task 9): loading chains, the body of every step (only what changed, plus the
/// versions), the stale recovery that keeps the input, and the rules around recording consent.
@MainActor
final class NumberChainTests: XCTestCase {
    private var service: FakePbxService!
    private var auth: FakeLocalAuth!
    private var gate: LocalAccessGate!

    override func setUp() async throws {
        service = FakePbxService()
        auth = FakeLocalAuth()
        gate = LocalAccessGate(authenticator: auth)
    }

    private func account() -> StoredAccount {
        StoredAccount(id: "acc-1", label: "Jan", pbxName: "Voorbeeld Bouw", extensionName: "Jan", extensionNumber: "102", customerName: "Voorbeeld", deviceToken: Secret("fss_vapp_x"), installId: "a1b2c3d4e5f60718", sip: SIPCredentials(username: "102", password: Secret("pw"), domain: "x.powervoip.nl", proxy: "sip.powervoip.nl", port: 5061, transport: .tls, srv: true), pairedAt: Date(timeIntervalSince1970: 1_790_000_000))
    }

    private func makeModel(readOnly: Bool = false) -> PbxSectionModel {
        PbxSectionModel(account: account(), readOnly: readOnly, service: service, gate: gate, authReason: { "test" }, sleep: { _ in })
    }

    private var simple: NumberChain { PbxFixtures.simpleChain }

    private func versionsOf(_ body: [String: Any]) -> [String: Int] {
        body["versions"] as? [String: Int] ?? [:]
    }

    // MARK: Loading

    func testLoadsTheNumbersAndASimpleChain() async throws {
        let model = makeModel()
        await model.load(.numbers)
        await model.loadChain(simple.id)

        XCTAssertEqual(model.numbers?.numbers.count, 2)
        let chain = try XCTUnwrap(model.chains[simple.id])
        XCTAssertTrue(chain.isEditable)
        XCTAssertEqual(chain.mode, .simple)
        XCTAssertNotNil(chain.hours)
    }

    func testAnAdvancedChainIsReadOnlyWithASummary() async throws {
        let model = makeModel()
        let advanced = PbxFixtures.advancedChain
        await model.loadChain(advanced.id)

        let chain = try XCTUnwrap(model.chains[advanced.id])
        XCTAssertFalse(chain.isEditable)
        XCTAssertEqual(chain.advancedSummary?.count, 3)
        XCTAssertNil(chain.forwarding)
    }

    func testAChainThatCannotBeLoadedSaysSo() async {
        let model = makeModel()
        await model.loadChain("unknown")

        XCTAssertNil(model.chains["unknown"])
        XCTAssertNotNil(model.chainFailures["unknown"])
    }

    // MARK: Name

    func testNameSendsOnlyTheNameAndTheVersions() async throws {
        let model = makeModel()
        await model.loadChain(simple.id)
        var draft = NumberNameDraft(simple)
        let baseline = draft
        draft.name = "  Receptie  "

        let outcome = await model.saveChainStep(numberId: simple.id, draft.step(baseline: baseline, chain: simple))

        XCTAssertEqual(outcome, .saved)
        let sent = try XCTUnwrap(service.chainSteps.last)
        XCTAssertEqual(sent.step, .name)
        XCTAssertEqual(Set(sent.body.keys), ["name", "versions", "numberVersion"])
        XCTAssertEqual(sent.body["name"] as? String, "Receptie")
        XCTAssertEqual(sent.body["numberVersion"] as? Int, simple.version)
        XCTAssertEqual(versionsOf(sent.body)[simple.hours!.id], simple.hours!.version)
    }

    func testABlankNameIsSentAsNull() throws {
        var draft = NumberNameDraft(simple)
        let baseline = draft
        draft.name = "   "

        let body = try jsonBody(XCTUnwrap(draft.step(baseline: baseline, chain: simple)))
        XCTAssertTrue(body["name"] is NSNull)
    }

    func testNothingChangedSendsNothing() async {
        let model = makeModel()
        await model.loadChain(simple.id)
        let draft = NumberForwardingDraft(simple)

        let outcome = await model.saveChainStep(numberId: simple.id, draft.step(baseline: draft, chain: simple))

        XCTAssertEqual(outcome, .unchanged)
        XCTAssertTrue(service.chainSteps.isEmpty)
        XCTAssertEqual(auth.evaluations, 0)
    }

    // MARK: Opening hours

    func testOnlyTheClosedFallbackUsesTheClosedStep() throws {
        var draft = NumberHoursDraft(simple)
        let baseline = draft
        draft.closed = .hangup

        let step = try XCTUnwrap(draft.step(baseline: baseline, chain: simple))
        XCTAssertEqual(step.step, .closed)

        let body = try jsonBody(step)
        XCTAssertEqual(Set(body.keys), ["closed", "versions", "numberVersion"])
        XCTAssertEqual((body["closed"] as? [String: Any])?["mode"] as? String, "hangup")
    }

    func testAChangedWeekSendsOnlyTheWeek() throws {
        var draft = NumberHoursDraft(simple)
        let baseline = draft
        draft.week.days[6] = [HoursInterval(from: "10:00", to: "14:00")]

        let step = try XCTUnwrap(draft.step(baseline: baseline, chain: simple))
        XCTAssertEqual(step.step, .hours)

        let body = try jsonBody(step)
        XCTAssertEqual(Set(body.keys), ["enabled", "week", "versions", "numberVersion"])
        XCTAssertEqual((body["week"] as? [[String: Any]])?.count, 6)
    }

    func testOwnDatesAndNationalHolidaysGoInHolidaysOnly() throws {
        var draft = NumberHoursDraft(simple)
        let baseline = draft
        draft.national = false

        let body = try jsonBody(XCTUnwrap(draft.step(baseline: baseline, chain: simple)))
        XCTAssertEqual(Set(body.keys), ["enabled", "holidays", "versions", "numberVersion"])
        XCTAssertEqual(Set((body["holidays"] as? [String: Any] ?? [:]).keys), ["national"])
    }

    func testSwitchingOffSendsOnlyEnabled() throws {
        var draft = NumberHoursDraft(simple)
        let baseline = draft
        draft.enabled = false
        draft.closed = .hangup

        let body = try jsonBody(XCTUnwrap(draft.step(baseline: baseline, chain: simple)))
        XCTAssertEqual(Set(body.keys), ["enabled", "versions", "numberVersion"])
        XCTAssertEqual(body["enabled"] as? Bool, false)
    }

    func testSwitchingOnSendsWhatTheNewHoursNeed() throws {
        let chain = PbxFixtures.chain("number-chain-simple") { $0["hours"] = NSNull() }
        var draft = NumberHoursDraft(chain)
        let baseline = draft
        XCTAssertFalse(draft.enabled)
        draft.enabled = true

        let body = try jsonBody(XCTUnwrap(draft.step(baseline: baseline, chain: chain)))
        XCTAssertEqual(Set(body.keys), ["enabled", "week", "holidays", "closed", "versions", "numberVersion"])
        XCTAssertEqual((body["week"] as? [[String: Any]])?.count, 5, "office hours: Monday to Friday")
        XCTAssertEqual((body["closed"] as? [String: Any])?["mode"] as? String, "voicemail")
        XCTAssertNil((body["closed"] as? [String: Any])?["boxId"], "the shared box: the server picks it")
    }

    func testHolidaySameAsClosedClearsWithNull() throws {
        let chain = PbxFixtures.chain("number-chain-simple") { json in
            var hours = json["hours"] as! [String: Any]
            hours["holiday"] = ["mode": "hangup"]
            json["hours"] = hours
        }
        var draft = NumberHoursDraft(chain)
        let baseline = draft
        draft.holiday = nil

        let body = try jsonBody(XCTUnwrap(draft.step(baseline: baseline, chain: chain)))
        XCTAssertEqual(Set(body.keys), ["holiday", "versions", "numberVersion"])
        XCTAssertTrue(body["holiday"] is NSNull)
    }

    func testAReadOnlyFallbackIsNeverSentBack() throws {
        let chain = PbxFixtures.chain("number-chain-simple") { json in
            var hours = json["hours"] as! [String: Any]
            hours["closed"] = ["mode": "other", "target": ["type": "queue", "id": "8c9d0e1f-2a3b-4c4d-9e5f-6a7b8c9d0e1f"]]
            json["hours"] = hours
        }
        var draft = NumberHoursDraft(chain)
        let baseline = draft
        draft.week = WeekPlan.preset(.always)

        let body = try jsonBody(XCTUnwrap(draft.step(baseline: baseline, chain: chain)))
        XCTAssertNil(body["closed"])
    }

    func testOwnDatesStopAtTheLimit() {
        let draft = NumberHoursDraft(simple)
        let many = (1 ... 25).map { String(format: "2027-01-%02d", $0) }

        XCTAssertNil(draft.adding(dates: many, name: "Vakantie"))
        XCTAssertEqual(draft.adding(dates: ["2026-08-03", "2026-12-24"], name: "")?.count, 2, "an existing date stays once")
    }

    // MARK: Welcome

    func testWelcomeOffSendsNoSound() throws {
        var draft = NumberWelcomeDraft(simple)
        let baseline = draft
        draft.enabled = false

        let body = try jsonBody(XCTUnwrap(draft.step(baseline: baseline, chain: simple)))
        XCTAssertEqual(Set(body.keys), ["enabled", "versions", "numberVersion"])
    }

    func testWelcomeNeedsASound() {
        let chain = PbxFixtures.chain("number-chain-simple") { $0["welcome"] = NSNull() }
        var draft = NumberWelcomeDraft(chain)
        draft.enabled = true

        XCTAssertFalse(draft.canSave)
        draft.soundId = "a1b2c3d4-2222-4a2b-8c3d-4e5f6a7b8c9d"
        XCTAssertTrue(draft.canSave)
    }

    // MARK: Forwarding

    func testAMemberOffSendsOnlyTheMembers() throws {
        var draft = NumberForwardingDraft(simple)
        let baseline = draft
        draft.setMember("7a1d9c3e-5b2f-4e8a-9c01-6d3e8f2a4b57", on: false)

        let body = try jsonBody(XCTUnwrap(draft.step(baseline: baseline, chain: simple)))
        XCTAssertEqual(Set(body.keys), ["kind", "members", "versions", "numberVersion"])
        XCTAssertEqual((body["members"] as? [[String: Any]])?.count, 2)
        XCTAssertEqual(versionsOf(body)["9e8d7c6b-5a49-4382-b1a0-f9e8d7c6b5a4"], 3)
    }

    func testTheUnansweredFallbackGoesOnlyWhenChanged() throws {
        var draft = NumberForwardingDraft(simple)
        let baseline = draft
        draft.unanswered = .forward(number: "0612345678")

        let body = try jsonBody(XCTUnwrap(draft.step(baseline: baseline, chain: simple)))
        XCTAssertEqual(Set(body.keys), ["kind", "unanswered", "versions", "numberVersion"])
        XCTAssertEqual((body["unanswered"] as? [String: Any])?["number"] as? String, "0612345678")
    }

    func testRingTimeIsTheSameForEveryMember() {
        var draft = NumberForwardingDraft(simple)
        XCTAssertNil(draft.ringSeconds, "the fixture has 25, 25 and 20")

        draft.setRingSeconds(30)
        XCTAssertEqual(draft.ringSeconds, 30)
    }

    func testSwitchingToAMenuSendsTheWholeMenu() throws {
        var draft = NumberForwardingDraft(simple)
        let baseline = draft
        draft.kind = .menu
        draft.greetingSoundId = "a1b2c3d4-1111-4a2b-8c3d-4e5f6a7b8c9d"
        draft.setKey("1", target: .object(.device, id: "5c0a8e1f-2d7b-4a39-8f46-0b1c2d3e4f50"))

        XCTAssertTrue(draft.canSave)
        let body = try jsonBody(XCTUnwrap(draft.step(baseline: baseline, chain: simple)))
        XCTAssertEqual(body["kind"] as? String, "menu")
        XCTAssertEqual(Set(body.keys), ["kind", "greetingSoundId", "repeats", "timeoutSeconds", "noChoice", "keys", "versions", "numberVersion"])
        XCTAssertEqual((body["keys"] as? [[String: Any]])?.first?["digit"] as? String, "1")
    }

    func testAMenuNeedsAKey() {
        var draft = NumberForwardingDraft(simple)
        draft.kind = .menu

        XCTAssertFalse(draft.canSave)
    }

    func testAKeyFromThePortalIsShownButNeverChangedOrSent() throws {
        let chain = PbxFixtures.menuChain
        var draft = NumberForwardingDraft(chain)
        let baseline = draft

        draft.setKey("3", target: nil)
        XCTAssertNotNil(draft.key("3"), "a key the portal set up cannot be switched off here")

        draft.setKey("2", target: nil)
        let body = try jsonBody(XCTUnwrap(draft.step(baseline: baseline, chain: chain)))
        XCTAssertEqual(Set(body.keys), ["kind", "keys", "versions", "numberVersion"])
        XCTAssertEqual((body["keys"] as? [[String: Any]])?.compactMap { $0["digit"] as? String }, ["1"])
    }

    func testRemovingTheDefaultKeyClearsIt() throws {
        let chain = PbxFixtures.menuChain
        var draft = NumberForwardingDraft(chain)
        let baseline = draft
        draft.setKey("1", target: nil)

        XCTAssertNil(draft.defaultKey)
        let body = try jsonBody(XCTUnwrap(draft.step(baseline: baseline, chain: chain)))
        XCTAssertTrue(body["defaultKey"] is NSNull)
    }

    // MARK: Recording

    func testRecordingAsksConsentOnlyWhenItStartsTheBilling() throws {
        var draft = NumberRecordingDraft(simple)
        let baseline = draft
        draft.enabled = true

        XCTAssertTrue(draft.needsConsent(simple.recording))
        draft.costAccepted = true
        let body = try jsonBody(XCTUnwrap(draft.patch(baseline: baseline, chain: simple)))
        XCTAssertEqual(body["costAccepted"] as? Bool, true)
        XCTAssertEqual(body["version"] as? Int, simple.version)

        let billed = PbxFixtures.advancedChain
        var announcement = NumberRecordingDraft(billed)
        let billedBase = announcement
        announcement.announcementSoundId = "a1b2c3d4-2222-4a2b-8c3d-4e5f6a7b8c9d"
        XCTAssertFalse(announcement.needsConsent(billed.recording), "already billed: no new consent")
        let changed = try jsonBody(XCTUnwrap(announcement.patch(baseline: billedBase, chain: billed)))
        XCTAssertEqual(Set(changed.keys), ["enabled", "announcementSoundId", "version"])
    }

    func testCostNotAcceptedAsksForConsent() async {
        let model = makeModel()
        await model.loadChain(simple.id)
        service.failures["setNumberRecording"] = [APIError.costNotAccepted(cost: RecordingCost(priceE4: 25000, vatIncluded: false))]
        var draft = NumberRecordingDraft(simple)
        let baseline = draft
        draft.enabled = true

        let outcome = await model.saveRecording(numberId: simple.id, draft.patch(baseline: baseline, chain: simple))

        XCTAssertEqual(outcome, .costRequired(RecordingCost(priceE4: 25000, vatIncluded: false)))
        XCTAssertFalse(outcome.closesForm)
    }

    func testThePriceReadsAsMoney() {
        let text = RecordingPrice.text(RecordingCost(priceE4: 20000, vatIncluded: false), locale: Locale(identifier: "nl_NL"))
        XCTAssertTrue(text.contains("2,00"), text)
    }

    // MARK: Changed in the meantime

    func testStaleShowsTheFreshChainAndKeepsTheInput() async throws {
        let model = makeModel()
        await model.loadChain(simple.id)

        // Someone else renamed the number and moved Saturday into the week; the hours are now version 7.
        let fresh = PbxFixtures.chain("number-chain-simple") { json in
            json["name"] = "Nieuwe naam"
            json["version"] = 5
            var hours = json["hours"] as! [String: Any]
            hours["version"] = 7
            var week = hours["week"] as! [[String: Any]]
            week.append(["day": 6, "from": "10:00", "to": "12:00"])
            hours["week"] = week
            json["hours"] = hours
        }
        service.failures["saveChainStep"] = [APIError.staleChain(fresh)]

        var draft = NumberHoursDraft(simple)
        var baseline = draft
        draft.closed = .hangup

        let outcome = await model.saveChainStep(numberId: simple.id, draft.step(baseline: baseline, chain: simple))
        XCTAssertEqual(outcome, .stale)
        XCTAssertFalse(outcome.closesForm)
        XCTAssertEqual(model.chains[simple.id]?.name, "Nieuwe naam")

        var message: ChainEditorMessage?
        _ = ChainOutcomeHandler.apply(outcome, fresh: model.chains[simple.id], draft: &draft, baseline: &baseline, message: &message)

        XCTAssertEqual(message, .stale)
        XCTAssertEqual(draft.closed, .hangup, "what the user chose stays")
        XCTAssertEqual(draft.week.slots(6).count, 1, "what the user did not touch follows the fresh chain")

        // The second save sends the user's change with the fresh versions, and nothing the other person changed.
        let latest = try XCTUnwrap(model.chains[simple.id])
        let retry = await model.saveChainStep(numberId: simple.id, draft.step(baseline: baseline, chain: latest))
        XCTAssertEqual(retry, .saved)

        let sent = try XCTUnwrap(service.chainSteps.last)
        XCTAssertEqual(Set(sent.body.keys), ["closed", "versions", "numberVersion"])
        XCTAssertEqual(sent.body["numberVersion"] as? Int, 5)
        XCTAssertEqual(versionsOf(sent.body)[simple.hours!.id], 7)
    }

    func testStaleWhileSwitchingStandardToMenuKeepsTheSwitchAndFollowsTheFreshMembers() throws {
        var draft = NumberForwardingDraft(simple)
        var baseline = draft
        draft.kind = .menu
        draft.greetingSoundId = "a1b2c3d4-1111-4a2b-8c3d-4e5f6a7b8c9d"
        draft.setKey("1", target: .object(.device, id: "5c0a8e1f-2d7b-4a39-8f46-0b1c2d3e4f50"))

        // Someone else took one phone out of the standard forwarding, and the number is now version 9.
        let fresh = PbxFixtures.chain("number-chain-simple") { json in
            json["version"] = 9
            var forwarding = json["forwarding"] as! [String: Any]
            var members = forwarding["members"] as! [[String: Any]]
            members.removeLast()
            forwarding["members"] = members
            json["forwarding"] = forwarding
        }

        draft.rebase(onto: fresh, baseline: &baseline)

        XCTAssertEqual(draft.kind, .menu, "the switch the user made stays")
        XCTAssertEqual(draft.keys.count, 1)
        XCTAssertEqual(baseline, NumberForwardingDraft(fresh), "the fresh chain is the new baseline")
        XCTAssertEqual(draft.members, NumberForwardingDraft(fresh).members, "untouched fields follow the fresh chain")

        // The save is still the whole menu (the kind differs from the baseline), now with the fresh versions.
        let body = try jsonBody(XCTUnwrap(draft.step(baseline: baseline, chain: fresh)))
        XCTAssertEqual(body["kind"] as? String, "menu")
        XCTAssertEqual(body["numberVersion"] as? Int, 9)
        XCTAssertNotNil(body["keys"])
    }

    func testStaleWhileSwitchingMenuToStandardKeepsTheSwitchAndTakesTheFreshMenuValues() throws {
        let chain = PbxFixtures.menuChain
        var draft = NumberForwardingDraft(chain)
        var baseline = draft
        draft.kind = .standard
        draft.members = NumberForwardingDraft(simple).members

        // Someone else changed the wait time of the menu.
        let fresh = PbxFixtures.chain("number-chain-menu") { json in
            json["version"] = 12
            var forwarding = json["forwarding"] as! [String: Any]
            forwarding["timeoutSeconds"] = 15
            json["forwarding"] = forwarding
        }

        draft.rebase(onto: fresh, baseline: &baseline)

        XCTAssertEqual(draft.kind, .standard)
        XCTAssertEqual(draft.members, NumberForwardingDraft(simple).members, "what the user chose stays")
        XCTAssertEqual(draft.timeoutSeconds, 15, "untouched menu values follow the fresh chain")

        let body = try jsonBody(XCTUnwrap(draft.step(baseline: baseline, chain: fresh)))
        XCTAssertEqual(body["kind"] as? String, "standard")
        XCTAssertEqual(body["numberVersion"] as? Int, 12)
        XCTAssertNotNil(body["members"])
    }

    func testStaleHoursOffToOnWhenSomeoneElseSwitchedThemOnAlreadySendsNothing() throws {
        let off = PbxFixtures.chain("number-chain-simple") { $0["hours"] = NSNull() }
        var draft = NumberHoursDraft(off)
        var baseline = draft
        draft.enabled = true

        let fresh = PbxFixtures.chain("number-chain-simple") { json in
            json["version"] = 6
            var hours = json["hours"] as! [String: Any]
            hours["version"] = 4
            json["hours"] = hours
        }

        draft.rebase(onto: fresh, baseline: &baseline)

        XCTAssertTrue(draft.enabled)
        XCTAssertTrue(baseline.enabled, "the fresh chain has hours")
        XCTAssertEqual(draft.week, baseline.week, "the week of the other person is kept, not the default office week")
        XCTAssertNil(draft.step(baseline: baseline, chain: fresh), "already on: nothing left to send")
    }

    func testStaleHoursOffToOnThatStaysOffOnTheServerStillSwitchesOn() throws {
        let off = PbxFixtures.chain("number-chain-simple") { $0["hours"] = NSNull() }
        var draft = NumberHoursDraft(off)
        var baseline = draft
        draft.enabled = true
        draft.closed = .hangup

        // Someone else only renamed the number.
        let fresh = PbxFixtures.chain("number-chain-simple") { json in
            json["hours"] = NSNull()
            json["name"] = "Andere naam"
            json["version"] = 8
        }

        draft.rebase(onto: fresh, baseline: &baseline)

        XCTAssertTrue(draft.enabled)
        XCTAssertFalse(baseline.enabled)
        let body = try jsonBody(XCTUnwrap(draft.step(baseline: baseline, chain: fresh)))
        XCTAssertEqual(Set(body.keys), ["enabled", "week", "holidays", "closed", "versions", "numberVersion"])
        XCTAssertEqual(body["numberVersion"] as? Int, 8)
        XCTAssertEqual((body["closed"] as? [String: Any])?["mode"] as? String, "hangup")
    }

    func testAPlainStaleReloadsTheChain() async {
        let model = makeModel()
        await model.loadChain(simple.id)
        let reads = service.count("chain")
        service.failures["saveChainStep"] = [APIError.stale(version: 9)]

        var draft = NumberNameDraft(simple)
        let baseline = draft
        draft.name = "X"
        let outcome = await model.saveChainStep(numberId: simple.id, draft.step(baseline: baseline, chain: simple))

        XCTAssertEqual(outcome, .stale)
        XCTAssertEqual(service.count("chain"), reads + 1)
    }

    func testAdvancedAnswerShowsTheChainReadOnly() async {
        let model = makeModel()
        await model.loadChain(simple.id)
        service.failures["saveChainStep"] = [APIError.advanced]
        service.chainResults[simple.id] = PbxFixtures.chain("number-chain-advanced") { $0["id"] = PbxFixtures.simpleChain.id }

        var draft = NumberNameDraft(simple)
        let baseline = draft
        draft.name = "X"
        let outcome = await model.saveChainStep(numberId: simple.id, draft.step(baseline: baseline, chain: simple))

        XCTAssertEqual(outcome, .failed(.advanced))
        XCTAssertEqual(model.chains[simple.id]?.isEditable, false)
    }

    func testAFrozenCentraleSendsNothing() async {
        let model = makeModel(readOnly: true)
        await model.loadChain(simple.id)
        var draft = NumberNameDraft(simple)
        let baseline = draft
        draft.name = "X"

        let outcome = await model.saveChainStep(numberId: simple.id, draft.step(baseline: baseline, chain: simple))

        XCTAssertEqual(outcome, .failed(.readOnly))
        XCTAssertTrue(service.chainSteps.isEmpty)
        XCTAssertEqual(auth.evaluations, 0)
    }

    func testASavedStepRefreshesTheList() async {
        let model = makeModel()
        await model.load(.numbers)
        await model.loadChain(simple.id)
        let reads = service.count("numbers")
        var draft = NumberNameDraft(simple)
        let baseline = draft
        draft.name = "X"

        _ = await model.saveChainStep(numberId: simple.id, draft.step(baseline: baseline, chain: simple))

        XCTAssertEqual(service.count("numbers"), reads + 1)
    }

    // MARK: Words

    func testListLineSaysWhatTheNumberDoes() {
        let page = PbxFixtures.decode("numbers-page", as: PbxNumbersPage.self)
        let line = NumberSummary.listLine(page.numbers[0])

        XCTAssertTrue(line.contains("9:00"), line)
        XCTAssertTrue(line.contains(L10n.string("numbers.forwarding.standard")), line)
        XCTAssertTrue(NumberSummary.listLine(page.numbers[1]).contains(L10n.string("pbx.sync.pending")))
    }

    func testFallbacksReadInWords() {
        let options = simple.options

        XCTAssertEqual(ChainWords.fallback(.voicemail(boxId: "7a1d9c3e-5b2f-4e8a-9c01-6d3e8f2a4b57", ofDevice: true), options), String(format: L10n.string("numbers.fallback.voicemailOf"), "Jan de Vries"))
        XCTAssertEqual(ChainWords.fallback(.other(target: nil), options), L10n.string("numbers.portalOnly"))
        XCTAssertEqual(ChainWords.target(PbxTarget(type: .queue, id: "x"), options), L10n.string("numbers.portalOnly"))
    }
}

/// The day bars: minutes, shapes, presets.
final class DayBarsLogicTests: XCTestCase {
    func testMinutes() {
        XCTAssertEqual(DayBarsLogic.minutes("00:00"), 0)
        XCTAssertEqual(DayBarsLogic.minutes("09:30"), 570)
        XCTAssertEqual(DayBarsLogic.minutes("24:00"), 1440)
        XCTAssertNil(DayBarsLogic.minutes("24:30"))
        XCTAssertNil(DayBarsLogic.minutes("9:30"))
        XCTAssertNil(DayBarsLogic.minutes("12:60"))
        XCTAssertNil(DayBarsLogic.minutes("ab:cd"))
    }

    func testAValidSlotEndsAfterItStarts() {
        XCTAssertTrue(DayBarsLogic.isValid(HoursInterval(from: "09:00", to: "17:00")))
        XCTAssertTrue(DayBarsLogic.isValid(HoursInterval(from: "18:00", to: "24:00")))
        XCTAssertFalse(DayBarsLogic.isValid(HoursInterval(from: "17:00", to: "09:00")))
        XCTAssertFalse(DayBarsLogic.isValid(HoursInterval(from: "09:00", to: "09:00")))
        XCTAssertFalse(DayBarsLogic.isValid(HoursInterval(from: "24:00", to: "24:00")))
    }

    func testSegmentsAreFractionsOfTheDaySorted() {
        let segments = DayBarsLogic.segments([
            HoursInterval(from: "13:00", to: "18:00"),
            HoursInterval(from: "06:00", to: "12:00"),
            HoursInterval(from: "20:00", to: "19:00"),
        ])

        XCTAssertEqual(segments.count, 2, "the invalid slot is left out")
        XCTAssertEqual(segments[0].lowerBound, 0.25, accuracy: 0.0001)
        XCTAssertEqual(segments[0].upperBound, 0.5, accuracy: 0.0001)
        XCTAssertEqual(segments[1].upperBound, 0.75, accuracy: 0.0001)
    }

    func testPresets() {
        XCTAssertEqual(WeekPlan.preset(.office).entries.count, 5)
        XCTAssertEqual(WeekPlan.preset(.always).entries.map(\.to), Array(repeating: "24:00", count: 7))
        XCTAssertTrue(WeekPlan.preset(.closed).entries.isEmpty)
        XCTAssertEqual(WeekPlan.preset(.office).matchingPreset, .office)
        XCTAssertNil(WeekPlan([HoursWeekEntry(day: 1, from: "08:00", to: "12:00")]).matchingPreset)
    }

    func testAnEmptyDayEqualsAMissingDay() {
        XCTAssertEqual(WeekPlan(days: [3: []]), WeekPlan())
    }

    func testANewSlotFollowsTheLastOne() {
        XCTAssertEqual(DayBarsLogic.nextSlot(after: [HoursInterval(from: "08:00", to: "12:00")]), HoursInterval(from: "12:00", to: "16:00"))
        XCTAssertEqual(DayBarsLogic.nextSlot(after: [HoursInterval(from: "21:00", to: "23:30")]), WeekPlan.defaultSlot)
        XCTAssertEqual(DayBarsLogic.nextSlot(after: []), WeekPlan.defaultSlot)
    }
}

/// The number screens draw in dark and light at the largest type without growing wider than the phone.
@MainActor
final class NumberLayoutTests: XCTestCase {
    private func fittingSize<V: View>(_ view: V, style: UIUserInterfaceStyle, category: UIContentSizeCategory, width: CGFloat = 390) -> CGSize {
        let host = UIHostingController(rootView: view.environment(\.sizeCategory, ContentSizeCategory(category) ?? .large))
        host.overrideUserInterfaceStyle = style
        host.view.frame = CGRect(x: 0, y: 0, width: width, height: 2000)
        host.view.layoutIfNeeded()

        return host.sizeThatFits(in: CGSize(width: width, height: .greatestFiniteMagnitude))
    }

    private func model() async -> PbxSectionModel {
        let model = PbxSectionModel(account: StoredAccount(id: "a", label: "Jan", pbxName: "X", extensionName: "Jan", extensionNumber: "102", customerName: "X", deviceToken: Secret("t"), installId: "a1b2c3d4e5f60718", sip: SIPCredentials(username: "102", password: Secret("pw"), domain: "x", proxy: "x", port: 5061, transport: .tls, srv: true), pairedAt: Date()), readOnly: false, service: FakePbxService(), gate: LocalAccessGate(authenticator: FakeLocalAuth()), authReason: { "t" }, sleep: { _ in })
        await model.loadChain(PbxFixtures.simpleChain.id)
        await model.loadChain(PbxFixtures.advancedChain.id)

        return model
    }

    func testDayBarsGrowAtTheLargestTypeAndStayInsideThePhone() {
        let week = WeekPlan.preset(.office)
        let normal = fittingSize(DayBars(week: week, onSelect: { _ in }), style: .dark, category: .large)
        let huge = fittingSize(DayBars(week: week, onSelect: { _ in }), style: .dark, category: .accessibilityExtraExtraExtraLarge)

        XCTAssertGreaterThanOrEqual(normal.height, 7 * 44)
        XCTAssertGreaterThan(huge.height, normal.height)
        XCTAssertLessThanOrEqual(huge.width, 390.5)
    }

    func testEveryStepDrawsInBothModesAtTheLargestType() async {
        let model = await model()
        let chain = PbxFixtures.simpleChain
        let screens: [AnyView] = [
            AnyView(NumberNameView(model: model, chain: chain)),
            AnyView(NumberHoursView(model: model, chain: chain)),
            AnyView(NumberWelcomeView(model: model, chain: chain)),
            AnyView(NumberForwardingView(model: model, chain: chain)),
            AnyView(NumberRecordingView(model: model, chain: chain)),
            AnyView(NumberView(model: model, numberId: chain.id)),
            AnyView(NumberView(model: model, numberId: PbxFixtures.advancedChain.id)),
        ]

        for style in [UIUserInterfaceStyle.dark, .light] {
            for screen in screens {
                let size = fittingSize(screen, style: style, category: .accessibilityExtraExtraExtraLarge)

                XCTAssertGreaterThan(size.height, 100)
                XCTAssertLessThanOrEqual(size.width, 390.5)
            }
        }
    }
}

/// `numbers.*` in both languages, the same placeholders, and no telecom words.
final class NumberLocalizationTests: XCTestCase {
    private static func strings(_ language: String) -> [String: String] {
        let path = Bundle.module.path(forResource: "Localizable", ofType: "strings", inDirectory: nil, forLocalization: language)!

        return (NSDictionary(contentsOfFile: path) as! [String: String]).filter { $0.key.hasPrefix("numbers.") }
    }

    private func placeholders(_ text: String) -> [String] {
        let regex = try! NSRegularExpression(pattern: "%(?:\\d+\\$)?(?:ld|@|d|f)")

        return regex.matches(in: text, range: NSRange(text.startIndex..., in: text)).map { String(text[Range($0.range, in: text)!]) }.sorted()
    }

    func testBothLanguagesHaveTheSameKeysAndPlaceholders() {
        let nl = Self.strings("nl")
        let en = Self.strings("en")

        XCTAssertGreaterThan(nl.count, 100)
        XCTAssertEqual(Set(nl.keys), Set(en.keys))

        for (key, text) in nl {
            XCTAssertEqual(placeholders(text), placeholders(en[key] ?? ""), key)
        }
    }

    func testNoTelecomJargonAndNoRinkel() {
        let banned = ["extensie", "ivr", "trunk", "gateway", "sip", "dialplan", "pbx", "rinkel", "expert"]

        for language in ["nl", "en"] {
            for (key, text) in Self.strings(language) {
                for word in banned {
                    XCTAssertNil(text.lowercased().range(of: "\\b\(word)\\b", options: .regularExpression), "\(language) \(key): \(text)")
                }
            }
        }
    }

    func testEveryKeyTheNumberScreensAskForIsTranslated() throws {
        let sources = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("Sources/UI", isDirectory: true)
        let files = try FileManager.default.contentsOfDirectory(at: sources.appendingPathComponent("numbers"), includingPropertiesForKeys: nil)
            + [sources.appendingPathComponent("design/DayBars.swift")]
        let regex = try NSRegularExpression(pattern: "\"((?:numbers|pbx|account|action)\\.[A-Za-z0-9_.]+)\"")
        let path = Bundle.module.path(forResource: "Localizable", ofType: "strings", inDirectory: nil, forLocalization: "en")!
        let en = NSDictionary(contentsOfFile: path) as! [String: String]

        for file in files where file.pathExtension == "swift" {
            let text = try String(contentsOf: file)

            for match in regex.matches(in: text, range: NSRange(text.startIndex..., in: text)) {
                let key = String(text[Range(match.range(at: 1), in: text)!])
                XCTAssertNotEqual(L10n.string(key), key, "no Dutch text for \(key)")
                XCTAssertNotNil(en[key], "no English text for \(key)")
            }
        }
    }
}
