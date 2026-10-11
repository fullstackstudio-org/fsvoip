// SPDX-License-Identifier: AGPL-3.0-or-later
import Core
import XCTest
@testable import UI

@MainActor
final class PbxSectionModelTests: XCTestCase {
    private var service: FakePbxService!
    private var auth: FakeLocalAuth!
    private var gate: LocalAccessGate!
    private var sleeps: [TimeInterval] = []

    override func setUp() async throws {
        service = FakePbxService()
        auth = FakeLocalAuth()
        gate = LocalAccessGate(authenticator: auth)
        sleeps = []
    }

    private func account(_ id: String = "acc-1") -> StoredAccount {
        StoredAccount(id: id, label: "Jan", pbxName: "Voorbeeld Bouw", extensionName: "Jan", extensionNumber: "102", customerName: "Voorbeeld", deviceToken: Secret("fss_vapp_x"), installId: "a1b2c3d4e5f60718", sip: SIPCredentials(username: "102", password: Secret("pw"), domain: "x.powervoip.nl", proxy: "sip.powervoip.nl", port: 5061, transport: .tls, srv: true), pairedAt: Date(timeIntervalSince1970: 1_790_000_000))
    }

    private func makeModel(readOnly: Bool = false) -> PbxSectionModel {
        let box = SleepBox()

        return PbxSectionModel(account: account(), readOnly: readOnly, service: service, gate: gate, authReason: { "test" }, sleep: { seconds in await box.record(seconds) })
    }

    private actor SleepBox {
        private(set) var values: [TimeInterval] = []
        func record(_ seconds: TimeInterval) { values.append(seconds) }
    }

    private func firstDevice(_ model: PbxSectionModel) -> PbxDevice {
        model.devices!.devices[0]
    }

    // MARK: Version and changed keys only

    func testDeviceSaveSendsVersionAndOnlyChangedKeys() async throws {
        let model = makeModel()
        await model.load(.devices)
        let device = firstDevice(model)

        var draft = DeviceDraft(device)
        draft.dnd = true
        draft.forwardAlways = .external("+31612345678")

        let outcome = await model.saveDevice(draft, original: device)
        XCTAssertEqual(outcome, .saved)

        let patch = try XCTUnwrap(service.devicePatches.first?.patch)
        XCTAssertEqual(patch.version, device.version)

        let json = try JSONSerialization.jsonObject(with: FSVoipJSON.encoder().encode(patch)) as! [String: Any]
        XCTAssertEqual(Set(json.keys), ["version", "dnd", "forwardAlways"])
        XCTAssertEqual(json["version"] as? Int, device.version)
        XCTAssertEqual(json["dnd"] as? Bool, true)
    }

    func testClearingAForwardSendsAnExplicitNull() async throws {
        let model = makeModel()
        await model.load(.devices)
        let device = model.devices!.devices[1]
        XCTAssertNotNil(device.forwardAlways)

        var draft = DeviceDraft(device)
        draft.forwardAlways = nil

        let patch = try XCTUnwrap(draft.patch(from: device))
        let json = try JSONSerialization.jsonObject(with: FSVoipJSON.encoder().encode(patch)) as! [String: Any]
        XCTAssertEqual(Set(json.keys), ["version", "forwardAlways"])
        XCTAssertTrue(json["forwardAlways"] is NSNull)
    }

    func testUnchangedFormSendsNothing() async {
        let model = makeModel()
        await model.load(.devices)
        let device = firstDevice(model)

        let outcome = await model.saveDevice(DeviceDraft(device), original: device)
        XCTAssertEqual(outcome, .unchanged)
        XCTAssertEqual(service.count("updateDevice"), 0)
        XCTAssertEqual(auth.evaluations, 0)
    }

    func testRingGroupPatchAndCreationCarryTheRightKeys() async throws {
        let model = makeModel()
        await model.load(.ringGroups)
        let group = model.ringGroups!.ringGroups[0]

        var draft = RingGroupDraft(group)
        draft.strategy = .sequence
        let outcome1 = await model.saveRingGroup(draft, original: group)
        XCTAssertEqual(outcome1, .saved)

        let patch = try XCTUnwrap(service.ringGroupPatches.first?.patch)
        let json = try JSONSerialization.jsonObject(with: FSVoipJSON.encoder().encode(patch)) as! [String: Any]
        XCTAssertEqual(Set(json.keys), ["version", "strategy"])
        XCTAssertEqual(json["version"] as? Int, group.version)

        var fresh = RingGroupDraft()
        XCTAssertFalse(fresh.isFilledIn)
        fresh.name = "  Werkplaats  "
        fresh.members = [RingGroupMember(extensionId: model.ringGroups!.devices[0].id)]
        XCTAssertTrue(fresh.isFilledIn)
        let outcome2 = await model.createRingGroup(fresh)
        XCTAssertEqual(outcome2, .saved)
        XCTAssertEqual(service.created.first?.name, "Werkplaats")
    }

    func testRoutingSendsTheNumberVersionAndNullForTheStandard() async throws {
        let model = makeModel()
        await model.load(.overview)
        let number = model.overview!.numbers[0]

        let outcome3 = await model.saveRouting(of: number, to: nil)

        XCTAssertEqual(outcome3, .saved)

        let patch = try XCTUnwrap(service.routingPatches.first?.patch)
        let json = try JSONSerialization.jsonObject(with: FSVoipJSON.encoder().encode(patch)) as! [String: Any]
        XCTAssertEqual(json["version"] as? Int, number.version)
        XCTAssertTrue(json["target"] is NSNull)
    }

    // MARK: stale

    func testStaleReloadsAndAsksTheFormToClose() async {
        let model = makeModel()
        await model.load(.devices)
        let device = firstDevice(model)
        service.failures["updateDevice"] = [APIError.stale(version: 9)]

        var changed = service.devicesResult
        changed.devices[0].version = 9
        service.devicesResult = changed

        var draft = DeviceDraft(device)
        draft.dnd = true
        let outcome = await model.saveDevice(draft, original: device)

        XCTAssertEqual(outcome, .stale)
        XCTAssertTrue(outcome.closesForm)
        XCTAssertEqual(model.devices?.devices[0].version, 9, "the newest data is loaded")
        XCTAssertEqual(model.banner?.text, PbxFailure.stale.message)
        XCTAssertTrue(model.banner?.text.contains("Intussen gewijzigd") == true || model.banner?.text.contains("Changed in the meantime") == true)
    }

    // MARK: Read only

    func testReadOnlyBlocksEverySaveWithoutAskingOrSending() async {
        let model = makeModel(readOnly: true)
        await model.load(.devices)
        let device = firstDevice(model)

        var draft = DeviceDraft(device)
        draft.dnd = true
        // The overview was not loaded, so the flag from `/me` still holds.
        let outcome = await model.saveDevice(draft, original: device)

        XCTAssertEqual(outcome, .failed(.readOnly))
        XCTAssertEqual(service.count("updateDevice"), 0)
        XCTAssertEqual(auth.evaluations, 0)
    }

    func testFrozenOverviewSetsReadOnly() async {
        var overview = service.overviewResult
        overview.pbx.readOnly = true
        overview.pbx.state = .frozen
        service.overviewResult = overview

        let model = makeModel()
        XCTAssertFalse(model.isReadOnly)
        await model.load(.overview)
        XCTAssertTrue(model.isReadOnly)
    }

    func testServerReadOnlyAnswerLocksTheModel() async {
        let model = makeModel()
        await model.load(.devices)
        let device = firstDevice(model)
        service.failures["updateDevice"] = [APIError.readOnly]
        // What the server says about itself is frozen too, so the reload agrees.
        service.overviewResult.pbx.readOnly = true

        var draft = DeviceDraft(device)
        draft.dnd = true

        let outcome4 = await model.saveDevice(draft, original: device)

        XCTAssertEqual(outcome4, .failed(.readOnly))
        XCTAssertTrue(model.isReadOnly)
    }

    // MARK: Forbidden, revoked, offline

    func testForbiddenHidesTheSectionThroughTheHub() async {
        let hub = PbxHub(service: service, gate: gate)
        let acc = account()
        hub.apply(me: PbxFixtures.me, accountId: acc.id)
        XCTAssertTrue(hub.isAvailable(acc.id))

        let model = hub.section(for: acc)
        service.failures["devices"] = [APIError.forbidden]
        await model.load(.devices)

        XCTAssertFalse(hub.isAvailable(acc.id))
        XCTAssertEqual(hub.lostAccessFor, acc.id)
        XCTAssertFalse(gate.isUnlocked)
    }

    func testUnauthorizedAsksTheAppToRefreshItsAccounts() async {
        let model = makeModel()
        var revoked = 0
        model.onRevoked = { revoked += 1 }
        service.failures["overview"] = [APIError.unauthorized]

        await model.load(.overview)
        XCTAssertEqual(revoked, 1)
    }

    func testOfflineKeepsTheLastStateAndSaysItIsOutdated() async {
        let model = makeModel()
        await model.load(.devices)
        XCTAssertNotNil(model.devices)

        service.failures["devices"] = [APIError.transport("offline")]
        await model.load(.devices)

        XCTAssertNotNil(model.devices)
        XCTAssertTrue(model.isOutdated)

        await model.load(.devices)
        XCTAssertFalse(model.isOutdated)
    }

    // MARK: Messages

    func testBlockedDestinationNamesTheNumbers() async {
        let model = makeModel()
        await model.load(.devices)
        let device = firstDevice(model)
        service.failures["updateDevice"] = [APIError.blockedDestination(["+449001234", "+449005678"])]

        var draft = DeviceDraft(device)
        draft.forwardAlways = .external("+449001234")
        let outcome = await model.saveDevice(draft, original: device)

        guard case let .failed(failure) = outcome else {
            return XCTFail("expected a failure")
        }

        XCTAssertEqual(failure, .blockedDestination(["+449001234", "+449005678"]))
        XCTAssertTrue(failure.message.contains("+449001234"))
        XCTAssertTrue(failure.message.contains("+449005678"))
        XCTAssertFalse(outcome.closesForm)
    }

    func testInUseShowsTheNamesTheCustomerChose() {
        let places = try! JSONDecoder().decode([APIErrorPlace].self, from: Data(#"[{"kind":"ring_group","name":"Iedereen"},{"kind":"number","name":"Het begin van je belstroom"}]"#.utf8))
        let failure = PbxFailure.classify(APIError.inUse(places: places))

        XCTAssertEqual(failure, .inUse(["Iedereen", "Het begin van je belstroom"]))
        XCTAssertTrue(failure.message.contains("Iedereen"))
    }

    func testRateLimitRespectsRetryAfter() {
        let failure = PbxFailure.classify(APIError.rateLimited(retryAfterSeconds: 42))

        XCTAssertEqual(failure, .rateLimited(retryAfterSeconds: 42))
        XCTAssertTrue(failure.message.contains("42"))
        XCTAssertFalse(PbxFailure.rateLimited(retryAfterSeconds: nil).message.contains("nil"))
    }

    func testEveryApiErrorHasASentenceWithoutServerText() {
        let errors: [APIError] = [.forbidden, .unauthorized, .readOnly, .stale(version: 3), .inUse(places: []), .blockedDestination(["1"]), .rateLimited(retryAfterSeconds: nil), .unavailable(retryable: true, retryAfterSeconds: nil), .transport("x"), .invalid(code: "x", field: nil), .invalidRequest(message: "Server says no"), .conflict(code: "busy"), .notFound, .gone, .payloadTooLarge, .decoding("x"), .unexpectedStatus(500), .missingDeviceToken]

        for error in errors {
            let message = PbxFailure.classify(error).message
            XCTAssertFalse(message.isEmpty, "\(error)")
            XCTAssertFalse(message.contains("Server says no"))
        }
    }

    // MARK: Local access

    func testCancelledFaceIdSendsNothing() async {
        let model = makeModel()
        await model.load(.devices)
        let device = firstDevice(model)
        auth.results = [.cancelled]

        var draft = DeviceDraft(device)
        draft.dnd = true

        let outcome5 = await model.saveDevice(draft, original: device)

        XCTAssertEqual(outcome5, .failed(.authentication))
        XCTAssertEqual(service.count("updateDevice"), 0)
    }

    func testNoPasscodeMeansNothingIsSaved() async {
        auth.availabilityValue = .noPasscode
        let model = makeModel()
        await model.load(.devices)
        let device = firstDevice(model)

        var draft = DeviceDraft(device)
        draft.dnd = true

        let outcome6 = await model.saveDevice(draft, original: device)

        XCTAssertEqual(outcome6, .failed(.notAvailable))
        XCTAssertEqual(service.count("updateDevice"), 0)
    }

    func testAnOpenGateIsNotAskedAgain() async {
        let model = makeModel()
        await model.load(.devices)
        let device = firstDevice(model)

        var first = DeviceDraft(device)
        first.dnd = true
        let outcome7 = await model.saveDevice(first, original: device)
        XCTAssertEqual(outcome7, .saved)

        var second = DeviceDraft(model.devices!.devices[0])
        second.voicemailEnabled.toggle()
        let outcome8 = await model.saveDevice(second, original: model.devices!.devices[0])
        XCTAssertEqual(outcome8, .saved)
        XCTAssertEqual(auth.evaluations, 1)
    }

    // MARK: Pending sync

    func testPendingSyncIsFollowedEveryFiveSecondsUntilItIsDone() async {
        var overview = service.overviewResult
        for index in overview.numbers.indices { overview.numbers[index].sync = .pending }
        service.overviewResult = overview

        let box = SleepBox()
        var reads = 0
        service.onRead = { [unowned self] name in
            guard name == "overview" else { return }
            reads += 1

            if reads >= 3 {
                var done = self.service.overviewResult
                for index in done.numbers.indices { done.numbers[index].sync = .ok }
                self.service.overviewResult = done
            }
        }

        let model = PbxSectionModel(account: account(), readOnly: false, service: service, gate: gate, authReason: { "test" }, sleep: { await box.record($0) })
        await model.loadIfNeeded(.overview)
        XCTAssertTrue(model.hasPendingSync)

        await model.pollTaskForTests()

        XCTAssertFalse(model.hasPendingSync)
        XCTAssertFalse(model.syncTimedOut)
        let values = await box.values
        XCTAssertEqual(values, [5, 5])
    }

    func testPendingSyncGivesUpAfterThreeMinutes() async {
        var overview = service.overviewResult
        overview.numbers[0].sync = .pending
        service.overviewResult = overview

        let box = SleepBox()
        let model = PbxSectionModel(account: account(), readOnly: false, service: service, gate: gate, authReason: { "test" }, sleep: { await box.record($0) })
        await model.loadIfNeeded(.overview)
        await model.pollTaskForTests()

        XCTAssertTrue(model.syncTimedOut)
        XCTAssertTrue(model.hasPendingSync)
        let values = await box.values
        XCTAssertEqual(values.count, PbxSectionModel.maxPollRounds)
        XCTAssertEqual(Double(values.count) * PbxSectionModel.pollInterval, 180)
    }

    // MARK: Temporarily closed

    func testTemporaryClosureKeepsExistingHolidaysAndAddsOwnDates() async throws {
        let model = makeModel()
        await model.load(.hours)
        let hours = model.hours!.hours[0]
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "Europe/Amsterdam")!
        let from = calendar.date(from: DateComponents(year: 2026, month: 12, day: 28))!
        let to = calendar.date(from: DateComponents(year: 2026, month: 12, day: 30))!
        let dates = TemporaryClosure.dates(from: from, to: to, calendar: calendar)
        XCTAssertEqual(dates, ["2026-12-28", "2026-12-29", "2026-12-30"])

        let list = try TemporaryClosure.closing(hours, name: "Verbouwing", dates: dates).get()
        XCTAssertEqual(list.count, hours.holidays.count + 3)
        XCTAssertEqual(list.first, .rule("christmas_day"))
        XCTAssertTrue(list.contains(.custom(name: "Bouwvak", date: "2026-08-03")))
        XCTAssertTrue(list.contains(.custom(name: "Verbouwing", date: "2026-12-29")))

        let outcome9 = await model.saveHolidays(list, original: hours)

        XCTAssertEqual(outcome9, .saved)
        let patch = try XCTUnwrap(service.hoursPatches.first?.patch)
        let json = try JSONSerialization.jsonObject(with: FSVoipJSON.encoder().encode(patch)) as! [String: Any]
        XCTAssertEqual(Set(json.keys), ["version", "holidays"])
        XCTAssertEqual(json["version"] as? Int, hours.version)
    }

    func testTemporaryClosureRefusesMoreThanTwentyOwnDates() throws {
        let hours = PbxFixtures.decode("pbx-hours", as: PbxHoursResponse.self).hours[0]
        let many = (1 ... 25).map { String(format: "2027-01-%02d", $0) }

        guard case let .failure(error) = TemporaryClosure.closing(hours, name: "", dates: many) else {
            return XCTFail("expected a failure")
        }

        XCTAssertEqual(error, .tooMany(room: 19))
        XCTAssertEqual(TemporaryClosure.closing(hours, name: "", dates: []), .failure(.emptyRange))
    }

    func testReopeningRemovesOnlyThatDate() {
        let hours = PbxFixtures.decode("pbx-hours", as: PbxHoursResponse.self).hours[0]
        let list = TemporaryClosure.reopening(hours, date: "2026-08-03")

        XCTAssertEqual(list, [.rule("christmas_day")])
    }

    func testHoursPatchOnlyHasTheTouchedParts() throws {
        let hours = PbxFixtures.decode("pbx-hours", as: PbxHoursResponse.self).hours[0]
        var draft = HoursDraft(hours)
        XCTAssertNil(draft.patch(from: hours))

        draft.days[6] = [HoursDraft.defaultInterval]
        let patch = try XCTUnwrap(draft.patch(from: hours))
        let json = try JSONSerialization.jsonObject(with: FSVoipJSON.encoder().encode(patch)) as! [String: Any]
        XCTAssertEqual(Set(json.keys), ["version", "week"])

        // A day switched on and off again is not a change.
        var same = HoursDraft(hours)
        same.days[6] = []
        XCTAssertNil(same.patch(from: hours))
    }
}

extension PbxSectionModel {
    /// Waits for the sync polling that `loadIfNeeded` started.
    func pollTaskForTests() async {
        while hasPendingSync, !syncTimedOut {
            await Task.yield()
        }

        // The timeout path ends the task right after it sets the flag.
        await Task.yield()
    }
}
