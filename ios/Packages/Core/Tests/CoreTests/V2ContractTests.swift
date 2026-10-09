// SPDX-License-Identifier: AGPL-3.0-or-later
import XCTest
@testable import Core

/// Decodes (and, for requests, re-encodes) the fixtures of the v2 API: the own extension, the team history, numbers as a chain,
/// sounds, the invitation link, parking and the new error answers. The fixtures are the JSON the FSS tests produce; they are validated
/// against `shared/openapi.yaml` by `scripts/validate-contract.ts`.
final class V2ContractTests: XCTestCase {
    static let coveredFixtures: Set<String> = [
        "me-response-user-v2", "me-response-admin-v2",
        "self-extension", "self-extension-patch-request", "self-extension-patch-response",
        "calls-page-team",
        "numbers-page", "number-chain-simple", "number-chain-menu", "number-chain-advanced",
        "chain-step-name", "chain-step-hours", "chain-step-welcome", "chain-step-forwarding-standard", "chain-step-forwarding-menu", "chain-step-closed",
        "number-recording-patch",
        "sounds-page", "sound-upload-response", "sound-rename-request", "app-pairing-response",
        "park-request", "park-response", "parked-calls", "parked-calls-unavailable",
        "error-advanced", "error-greeting-required", "error-cost-not-accepted", "error-stale-chain", "error-forbidden-field",
        "error-invalid-audio", "error-too-large", "error-too-many", "error-call-not-found", "error-no-free-slot", "error-park-unavailable", "error-park-uncertain",
    ]

    private let decoder = FSVoipJSON.decoder()

    private func decode<T: Decodable>(_ type: T.Type, _ fixture: String) throws -> T {
        try decoder.decode(type, from: Fixtures.data(fixture))
    }

    private func assertJSONEqual<T: Encodable>(_ value: T, _ fixture: String, file: StaticString = #filePath, line: UInt = #line) throws {
        let encoded = try JSONSerialization.jsonObject(with: FSVoipJSON.encoder().encode(value)) as? NSDictionary
        let expected = try JSONSerialization.jsonObject(with: Fixtures.data(fixture)) as? NSDictionary
        XCTAssertEqual(encoded, expected, "\(fixture)", file: file, line: line)
    }

    private let receptie = "5c0a8e1f-2d7b-4a39-8f46-0b1c2d3e4f50"
    private let pieter = "1b2c3d4e-0a1b-4c2d-8e3f-5a6b7c8d9e01"
    private let box = "2f4a6c8e-1b3d-4f5a-8c7e-9a0b1c2d3e4f"
    private let hours = "c3b2a1f0-e9d8-4c7b-a6f5-e4d3c2b1a0f9"
    private let sound1 = "a1b2c3d4-1111-4a2b-8c3d-4e5f6a7b8c9d"
    private let sound2 = "a1b2c3d4-2222-4a2b-8c3d-4e5f6a7b8c9d"

    // MARK: /me capabilities

    func testCapabilitiesOfTheTwoRoles() throws {
        let user = try decode(MeResponse.self, "me-response-user-v2")
        let admin = try decode(MeResponse.self, "me-response-admin-v2")

        XCTAssertEqual(user.capabilities?.calls, .team)
        XCTAssertEqual(user.capabilities?.sounds, .noAccess)
        XCTAssertTrue(user.canEditOwnExtension)
        XCTAssertFalse(user.canPark)
        XCTAssertEqual(user.capabilities?.invite, false)
        XCTAssertEqual(user.capabilities?.callerChoice, false)

        XCTAssertEqual(admin.capabilities?.calls, .all)
        XCTAssertEqual(admin.capabilities?.sounds, .manage)
        XCTAssertTrue(admin.canPark)
        XCTAssertEqual(admin.capabilities?.invite, true)
        XCTAssertEqual(admin.capabilities?.callerChoice, true)
    }

    func testOldCapabilitiesFixturesStillDecodeWithTheRestrictiveDefaults() throws {
        let user = try decode(MeResponse.self, "me-response-user")
        let capabilities = try XCTUnwrap(user.capabilities)

        XCTAssertEqual(capabilities.calls, .own)
        XCTAssertFalse(capabilities.selfExtension)
        XCTAssertEqual(capabilities.sounds, .noAccess)
        XCTAssertFalse(capabilities.invite)
        XCTAssertFalse(capabilities.park)
        XCTAssertFalse(capabilities.callerChoice)
        XCTAssertFalse(user.canEditOwnExtension)
    }

    func testAnUnknownCallsOrSoundsValueFallsBackToTheRestrictiveChoice() throws {
        let json = Data(#"{"pbxManage":true,"recordings":true,"voicemail":"all","calls":"everyone","sounds":"sing","park":true}"#.utf8)
        let capabilities = try decoder.decode(AppCapabilities.self, from: json)

        XCTAssertEqual(capabilities.calls, .own)
        XCTAssertEqual(capabilities.sounds, .noAccess)
        XCTAssertTrue(capabilities.park)
    }

    // MARK: The own extension

    func testSelfExtension() throws {
        let value = try decode(SelfExtension.self, "self-extension")

        XCTAssertEqual(value.extensionNumber, "102")
        XCTAssertEqual(value.version, 4)
        XCTAssertEqual(value.noAnswerTarget, PbxTarget(type: .voicemail, id: "7a1d9c3e-5b2f-4e8a-9c01-6d3e8f2a4b57"))
        XCTAssertEqual(value.numbers.map(\.number), ["0850607848", "0850607849"])
        XCTAssertEqual(value.numbers.map(\.isDefault), [true, false])
        XCTAssertEqual(value.defaultNumber, "0850607848")
        XCTAssertEqual(value.targets.map(\.type), [.device, .device, .ringGroup, .voicemail, .external])
        XCTAssertNil(value.forwardAlways)
    }

    func testSelfExtensionPatchRoundTrips() throws {
        try assertJSONEqual(SelfExtensionPatch(version: 4, dnd: true, forwardAlways: .set(.external("+31612345678"))), "self-extension-patch-request")

        let response = try decode(SelfExtensionPatchResponse.self, "self-extension-patch-response")

        XCTAssertTrue(response.ok)
        XCTAssertEqual(response.extensionState?.version, 5)
        XCTAssertEqual(response.extensionState?.dnd, true)
        XCTAssertEqual(response.extensionState?.forwardAlways, .external("+31612345678"))
    }

    func testSelfExtensionPatchOnlyKnowsTheSelfKeys() throws {
        let everything = SelfExtensionPatch(version: 1, dnd: false, forwardAlways: .clear, noAnswerSeconds: 20, noAnswerTarget: .clear, voicemailEnabled: true, voicemailToEmail: false, email: .clear)
        let object = try XCTUnwrap(JSONSerialization.jsonObject(with: FSVoipJSON.encoder().encode(everything)) as? [String: Any])

        XCTAssertEqual(Set(object.keys), ["version", "dnd", "forwardAlways", "noAnswerSeconds", "noAnswerTarget", "voicemailEnabled", "voicemailToEmail", "email"])
        XCTAssertTrue(object["forwardAlways"] is NSNull)
        XCTAssertNil(object["outboundNumberId"], "the number to call out with is not a setting")
    }

    func testAnUnreadableFreshExtensionDoesNotHideTheSuccess() throws {
        let response = try decoder.decode(SelfExtensionPatchResponse.self, from: Data(#"{"ok":true,"extension":{"nonsense":1}}"#.utf8))

        XCTAssertTrue(response.ok)
        XCTAssertNil(response.extensionState)
        XCTAssertNil(try decoder.decode(SelfExtensionPatchResponse.self, from: Data(#"{"ok":true}"#.utf8)).extensionState)
    }

    // MARK: Team history

    func testTeamCallsHaveNoRecordingsAndTellWhoAnswered() throws {
        let page = try decode(CallsPage.self, "calls-page-team")

        XCTAssertEqual(page.calls.count, 3)
        XCTAssertTrue(page.calls.allSatisfy { !$0.hasRecording && !$0.recordingExpired })
        XCTAssertEqual(page.calls.map(\.extensionName), ["Pieter Jansen", "Receptie", "Jan de Vries"])
        XCTAssertNil(page.calls[2].number, "an anonymous caller has no number")
    }

    // MARK: Numbers as a chain

    func testNumbersPage() throws {
        let page = try decode(PbxNumbersPage.self, "numbers-page")

        XCTAssertEqual(page.numbers.count, 2)
        XCTAssertEqual(page.numbers[0].name, "Hoofdnummer")
        XCTAssertEqual(page.numbers[0].mode, .simple)
        XCTAssertEqual(page.numbers[0].summary, PbxNumberEntry.Summary(hours: "ma-vr 9:00-17:00", welcome: true, forwarding: .standard, recording: false))
        XCTAssertEqual(page.numbers[1].mode, .advanced)
        XCTAssertEqual(page.numbers[1].summary.forwarding, .advanced)
        XCTAssertEqual(page.numbers[1].sync, .pending)
        XCTAssertNil(page.numbers[1].name)
    }

    func testSimpleChain() throws {
        let chain = try decode(NumberChain.self, "number-chain-simple")

        XCTAssertTrue(chain.isEditable)
        XCTAssertEqual(chain.version, 4)

        let hours = try XCTUnwrap(chain.hours)
        XCTAssertEqual(hours.id, self.hours)
        XCTAssertEqual(hours.week.count, 5)
        XCTAssertTrue(hours.holidays.national)
        XCTAssertEqual(hours.holidays.dates, [ChainHolidayDate(name: "Bouwvak", date: "2026-08-03")])
        XCTAssertEqual(hours.closed, .voicemail(boxId: box, ofDevice: false))
        XCTAssertNil(hours.holiday)

        XCTAssertEqual(chain.welcome?.soundId, sound1)

        guard case let .standard(forwarding) = chain.forwarding else {
            return XCTFail("expected standard forwarding")
        }

        XCTAssertEqual(forwarding.groupId, "9e8d7c6b-5a49-4382-b1a0-f9e8d7c6b5a4")
        XCTAssertEqual(forwarding.strategy, .all)
        XCTAssertEqual(forwarding.members.map(\.deviceId), [receptie, pieter, "7a1d9c3e-5b2f-4e8a-9c01-6d3e8f2a4b57"])
        XCTAssertEqual(forwarding.members.last, ChainMember(deviceId: "7a1d9c3e-5b2f-4e8a-9c01-6d3e8f2a4b57", delaySeconds: 5, timeoutSeconds: 20))
        XCTAssertEqual(forwarding.sharedWith, ["Servicenummer"])

        XCTAssertFalse(chain.recording.enabled)
        XCTAssertEqual(chain.recording.cost, RecordingCost(priceE4: 20000, vatIncluded: false))
        XCTAssertEqual(chain.options.devices.map(\.extensionNumber), ["100", "101", "102"])
        XCTAssertEqual(chain.options.sounds.map(\.name), ["Welkom", "Buiten kantoortijd"])
        XCTAssertEqual(chain.options.groups.map(\.name), ["Iedereen"])
        XCTAssertNil(chain.advancedSummary)
    }

    func testMenuChainAndTheFallbacksTheChainCannotChoose() throws {
        let chain = try decode(NumberChain.self, "number-chain-menu")

        XCTAssertEqual(chain.sync, .pending)
        XCTAssertNil(chain.welcome)

        let hours = try XCTUnwrap(chain.hours)
        XCTAssertEqual(hours.week.map(\.day), [1, 1], "two periods on one day")
        XCTAssertEqual(hours.closed, .message(soundId: sound2))
        XCTAssertEqual(hours.holiday, .other(target: PbxTarget(type: .queue, id: "8c9d0e1f-2a3b-4c4d-9e5f-6a7b8c9d0e1f")))
        XCTAssertEqual(hours.holiday?.isSelectable, false)
        XCTAssertTrue(hours.closed.isSelectable)

        guard case let .menu(menu) = chain.forwarding else {
            return XCTFail("expected a menu")
        }

        XCTAssertEqual(menu.repeats, 2)
        XCTAssertEqual(menu.defaultKey, "1")
        XCTAssertEqual(menu.noChoice, .hangup)
        XCTAssertEqual(menu.keys.map(\.digit), ["1", "2", "3"])
        XCTAssertEqual(menu.keys.map(\.editable), [true, true, false])
        XCTAssertEqual(menu.keys[1].target, .external("0612345678"))

        XCTAssertTrue(chain.recording.enabled)
        XCTAssertTrue(chain.recording.billingActive)
        XCTAssertNil(chain.recording.cost, "free for this customer")
    }

    func testAdvancedChainIsReadOnly() throws {
        let chain = try decode(NumberChain.self, "number-chain-advanced")

        XCTAssertEqual(chain.mode, .advanced)
        XCTAssertFalse(chain.isEditable)
        XCTAssertNil(chain.hours)
        XCTAssertNil(chain.welcome)
        XCTAssertNil(chain.forwarding)
        XCTAssertEqual(chain.advancedSummary?.count, 3)
        XCTAssertEqual(chain.name, "Support")
    }

    func testFallbackLeniencyAndStrictness() throws {
        func fallback(_ json: String) throws -> Fallback {
            try decoder.decode(Fallback.self, from: Data(json.utf8))
        }

        XCTAssertEqual(try fallback(#"{"mode":"voicemail","boxId":"b","ofDevice":true}"#), .voicemail(boxId: "b", ofDevice: true))
        XCTAssertEqual(try fallback(#"{"mode":"voicemail"}"#), .voicemail(boxId: nil, ofDevice: false))
        XCTAssertEqual(try fallback(#"{"mode":"device","deviceId":"d"}"#), .device(deviceId: "d"))
        XCTAssertEqual(try fallback(#"{"mode":"teleport"}"#), .unknown(mode: "teleport"))
        XCTAssertEqual(try fallback(#"{"mode":"other"}"#), .other(target: nil))
        XCTAssertFalse(try fallback(#"{"mode":"teleport"}"#).isSelectable)

        // What the app may send: voicemail without a box = the shared box; `ofDevice` is display only.
        let sent = try JSONSerialization.jsonObject(with: FSVoipJSON.encoder().encode(Fallback.voicemail(boxId: nil, ofDevice: true))) as? NSDictionary
        XCTAssertEqual(sent, ["mode": "voicemail"])

        // `other` and an unknown mode can never be sent back.
        XCTAssertThrowsError(try FSVoipJSON.encoder().encode(Fallback.other(target: nil)))
        XCTAssertThrowsError(try FSVoipJSON.encoder().encode(Fallback.unknown(mode: "teleport")))
        XCTAssertThrowsError(try FSVoipJSON.encoder().encode(ChainClosedStep(closed: .other(target: nil))))
    }

    func testAnUnknownForwardingKindMakesTheChainReadOnly() throws {
        var object = try XCTUnwrap(JSONSerialization.jsonObject(with: Fixtures.data("number-chain-simple")) as? [String: Any])
        object["forwarding"] = ["kind": "teleport", "futureField": 1]
        let chain = try decoder.decode(NumberChain.self, from: JSONSerialization.data(withJSONObject: object))

        XCTAssertEqual(chain.forwarding, .unknown(kind: "teleport"))
        XCTAssertFalse(chain.isEditable)
    }

    func testAnUnknownModeAndFutureFieldsDoNotBreakTheChain() throws {
        var object = try XCTUnwrap(JSONSerialization.jsonObject(with: Fixtures.data("number-chain-simple")) as? [String: Any])
        object["mode"] = "quantum"
        object["futureField"] = ["a": 1]
        object["options"] = "garbage"
        let chain = try decoder.decode(NumberChain.self, from: JSONSerialization.data(withJSONObject: object))

        XCTAssertEqual(chain.mode, .unknown)
        XCTAssertFalse(chain.isEditable)
        XCTAssertEqual(chain.options, ChainOptions())
    }

    // MARK: Chain steps (requests)

    func testEveryStepEncodesToItsFixture() throws {
        let versions = ChainVersions(objects: [hours: 6], number: 4)

        try assertJSONEqual(ChainNameStep(name: "Hoofdnummer", versions: ChainVersions(number: 4)), "chain-step-name")
        try assertJSONEqual(
            ChainHoursStep(
                enabled: true,
                week: [HoursWeekEntry(day: 1, from: "09:00", to: "17:00"), HoursWeekEntry(day: 2, from: "09:00", to: "17:00")],
                holidays: ChainHolidaysInput(national: true, dates: [ChainHolidayDate(name: "Bouwvak", date: "2026-08-03")]),
                closed: .voicemail(boxId: box, ofDevice: false),
                holiday: .clear,
                versions: versions
            ),
            "chain-step-hours"
        )
        try assertJSONEqual(ChainWelcomeStep(enabled: true, soundId: .set(sound1), versions: ChainVersions(objects: ["4a5b6c7d-8e9f-4a0b-9c1d-2e3f4a5b6c7d": 3], number: 4)), "chain-step-welcome")
        try assertJSONEqual(
            ChainStandardForwardingStep(
                strategy: .sequence,
                members: [ChainMember(deviceId: receptie, delaySeconds: 0, timeoutSeconds: 20), ChainMember(deviceId: pieter, delaySeconds: 20, timeoutSeconds: 20)],
                unanswered: .message(soundId: sound2),
                versions: ChainVersions(objects: ["9e8d7c6b-5a49-4382-b1a0-f9e8d7c6b5a4": 3], number: 4)
            ),
            "chain-step-forwarding-standard"
        )
        try assertJSONEqual(
            ChainMenuForwardingStep(
                greetingSoundId: .set(sound1),
                repeats: 2,
                timeoutSeconds: 8,
                defaultKey: .set("1"),
                noChoice: .hangup,
                keys: [ChainKey(digit: "1", target: .object(.device, id: receptie)), ChainKey(digit: "2", target: .external("0612345678"))],
                versions: ChainVersions(objects: ["6b7c8d9e-0f1a-4b2c-8d3e-4f5a6b7c8d9e": 2], number: 2)
            ),
            "chain-step-forwarding-menu"
        )
        try assertJSONEqual(ChainClosedStep(closed: .forward(number: "0612345678"), holiday: .clear, versions: versions), "chain-step-closed")
        try assertJSONEqual(NumberRecordingPatch(enabled: true, announcementSoundId: .set(sound2), costAccepted: true, version: 4), "number-recording-patch")
    }

    func testStepsKnowTheirPathSegment() {
        XCTAssertEqual(ChainNameStep(name: nil).step.rawValue, "name")
        XCTAssertEqual(ChainHoursStep(enabled: false).step.rawValue, "hours")
        XCTAssertEqual(ChainWelcomeStep(enabled: false).step.rawValue, "welcome")
        XCTAssertEqual(ChainStandardForwardingStep().step.rawValue, "forwarding")
        XCTAssertEqual(ChainMenuForwardingStep().step.rawValue, "forwarding")
        XCTAssertEqual(ChainClosedStep().step.rawValue, "closed")
    }

    func testLeftOutPartsStayOutOfTheBody() throws {
        func keys<T: Encodable>(_ value: T) throws -> Set<String> {
            Set(try XCTUnwrap(JSONSerialization.jsonObject(with: FSVoipJSON.encoder().encode(value)) as? [String: Any]).keys)
        }

        XCTAssertEqual(try keys(ChainHoursStep(enabled: false)), ["enabled"])
        XCTAssertEqual(try keys(ChainWelcomeStep(enabled: false)), ["enabled"])
        XCTAssertEqual(try keys(ChainClosedStep()), [])
        XCTAssertEqual(try keys(ChainStandardForwardingStep()), ["kind"])
        XCTAssertEqual(try keys(ChainMenuForwardingStep()), ["kind"])
        XCTAssertEqual(try keys(NumberRecordingPatch(enabled: false, version: 2)), ["enabled", "version"])
    }

    func testABlankNameIsSentAsNull() throws {
        let object = try XCTUnwrap(JSONSerialization.jsonObject(with: FSVoipJSON.encoder().encode(ChainNameStep(name: "   "))) as? [String: Any])

        XCTAssertTrue(object["name"] is NSNull)
    }

    // MARK: Sounds, invitation

    func testSoundsPage() throws {
        let page = try decode(SoundsPage.self, "sounds-page")

        XCTAssertEqual(page.sounds.count, 3)
        XCTAssertEqual(page.sounds[0].uses, [SoundUse(kind: "menu_greeting", name: "Keuzemenu Support")])
        XCTAssertEqual(page.sounds[0].contentType, "audio/mpeg")
        XCTAssertEqual(page.sounds[0].byteSize, 184_320)
        XCTAssertNil(page.sounds[0].durationSeconds)
        XCTAssertEqual(page.sounds[1].sync, .pending)
        XCTAssertFalse(page.sounds[2].playable)
        XCTAssertNil(page.sounds[2].contentType)
    }

    func testSoundUploadAndRename() throws {
        XCTAssertEqual(try decode(SoundUploadResponse.self, "sound-upload-response").soundId, "a1b2c3d4-4444-4a2b-8c3d-4e5f6a7b8c9d")
        try assertJSONEqual(SoundRenameRequest(name: "Welkomstbericht"), "sound-rename-request")
    }

    func testAppPairingLinkIsASecret() throws {
        let response = try decode(AppPairingResponse.self, "app-pairing-response")

        XCTAssertTrue(response.url.reveal().hasPrefix("https://fullstackstudio.nl/fsvoip/pair?t=fss_vpair_"))
        XCTAssertEqual(response.expiresAt, FSVoipJSON.parseTimestamp("2026-10-09T08:25:30.123Z"))

        for text in ["\(response)", String(reflecting: response), String(describing: response.url)] {
            XCTAssertFalse(text.contains("fss_vpair_"), "the link must not show up in a print-out")
        }
    }

    // MARK: Parking

    func testParkRequestAndResponse() throws {
        try assertJSONEqual(ParkRequest(callId: "3b9c1f0e2a7d4c58@192.0.2.10"), "park-request")

        let call = try decode(ParkedCall.self, "park-response")

        XCTAssertEqual(call.slot, 1)
        XCTAssertEqual(call.retrieveNumber, "*5901")
        XCTAssertEqual(call.callerName, "Bakkerij Smit")
        XCTAssertEqual(call.parkedBy, ParkedBy(deviceId: "3f0c2b1e-8a4d-4d6f-9b7a-1c2d3e4f5a6b", name: "Jan (balie)"))
        XCTAssertTrue(call.mine)
        XCTAssertEqual(call.expiresAt, FSVoipJSON.parseTimestamp("2026-10-09T08:20:30.000Z"))
    }

    func testParkedCallsOfOthersAndOutsideFullStackStudio() throws {
        let page = try decode(ParkedCallsPage.self, "parked-calls")

        XCTAssertTrue(page.available)
        XCTAssertEqual(page.calls.map(\.slot), [1, 2, 3])
        XCTAssertEqual(page.calls.map(\.mine), [true, false, false])
        XCTAssertNil(page.calls[1].callerNumber, "an anonymous caller")
        XCTAssertNil(page.calls[1].parkedBy?.deviceId, "parked from another phone")
        XCTAssertEqual(page.calls[1].parkedBy?.name, "Receptie")
        XCTAssertNil(page.calls[2].parkedBy, "parked outside FullStack Studio")
        XCTAssertNil(page.calls[2].retrieveNumber)
        XCTAssertNil(page.calls[2].parkedAt)

        let unavailable = try decode(ParkedCallsPage.self, "parked-calls-unavailable")
        XCTAssertFalse(unavailable.available)
        XCTAssertTrue(unavailable.calls.isEmpty)
    }

    func testAnUnreadableParkTimeDoesNotLoseTheList() throws {
        let json = Data(#"{"available":true,"calls":[{"id":"x","slot":4,"parkedAt":"gisteren","expiresAt":12,"mine":false}]}"#.utf8)
        let page = try decoder.decode(ParkedCallsPage.self, from: json)

        XCTAssertEqual(page.calls.count, 1)
        XCTAssertNil(page.calls[0].parkedAt)
        XCTAssertNil(page.calls[0].expiresAt)
    }

    // MARK: Error bodies

    func testErrorBodies() throws {
        XCTAssertEqual(try decode(APIErrorBody.self, "error-advanced").error, "advanced")
        XCTAssertEqual(try decode(APIErrorBody.self, "error-greeting-required").code, "greeting_required")
        XCTAssertEqual(try decode(APIErrorBody.self, "error-cost-not-accepted").cost, RecordingCost(priceE4: 20000, vatIncluded: false))
        XCTAssertEqual(try decode(APIErrorBody.self, "error-forbidden-field").field, "suspended")
        XCTAssertEqual(try decode(APIErrorBody.self, "error-stale-chain").chain?.version, 5)
        XCTAssertEqual(try decode(APIErrorBody.self, "error-invalid-audio").error, "invalid_audio")
        XCTAssertEqual(try decode(APIErrorBody.self, "error-too-large").error, "too_large")
        XCTAssertEqual(try decode(APIErrorBody.self, "error-too-many").error, "too_many")
        XCTAssertEqual(try decode(APIErrorBody.self, "error-call-not-found").error, "call_not_found")
        XCTAssertEqual(try decode(APIErrorBody.self, "error-no-free-slot").error, "no_free_slot")
        XCTAssertEqual(try decode(APIErrorBody.self, "error-park-unavailable").error, "park_unavailable")
        XCTAssertEqual(try decode(APIErrorBody.self, "error-park-uncertain").error, "park_uncertain")
    }

    func testAnUnreadableChainDoesNotHideTheStaleError() throws {
        let body = try decoder.decode(APIErrorBody.self, from: Data(#"{"error":"stale","chain":{"broken":true},"cost":"free"}"#.utf8))

        XCTAssertEqual(body.error, "stale")
        XCTAssertNil(body.chain)
        XCTAssertNil(body.cost)
    }

    // MARK: Caller choice

    func testCallerChoiceHeader() throws {
        let admin = try decode(MeResponse.self, "me-response-admin-v2")
        let user = try decode(MeResponse.self, "me-response-user-v2")
        let numbers = try decode(SelfExtension.self, "self-extension").numbers

        XCTAssertEqual(CallerChoice.headerName, "X-FSS-From")
        XCTAssertEqual(CallerChoice.headers(choosing: "0850607849", capabilities: admin.capabilities, numbers: numbers), ["X-FSS-From": "0850607849"])

        // Never when the PBX does not support it: the header would leak to the provider.
        XCTAssertEqual(CallerChoice.headers(choosing: "0850607849", capabilities: user.capabilities, numbers: numbers), [:])
        XCTAssertEqual(CallerChoice.headers(choosing: "0850607849", capabilities: nil, numbers: numbers), [:])
        // Not a number of this PBX, or not a national number.
        XCTAssertEqual(CallerChoice.headers(choosing: "0851111111", capabilities: admin.capabilities, numbers: numbers), [:])
        XCTAssertEqual(CallerChoice.headers(choosing: "+31850607849", capabilities: admin.capabilities, numbers: numbers), [:])
        XCTAssertEqual(CallerChoice.headers(choosing: nil, capabilities: admin.capabilities, numbers: numbers), [:])
    }

    func testCallerChoiceNumberFormat() {
        XCTAssertTrue(CallerChoice.isValidNumber("0850607848"))
        XCTAssertTrue(CallerChoice.isValidNumber("0201234567"))
        XCTAssertFalse(CallerChoice.isValidNumber("085060784"))
        XCTAssertFalse(CallerChoice.isValidNumber("08506078489"))
        XCTAssertFalse(CallerChoice.isValidNumber("0050607848"))
        XCTAssertFalse(CallerChoice.isValidNumber("1850607848"))
        XCTAssertFalse(CallerChoice.isValidNumber("085060784x"))
    }
}
