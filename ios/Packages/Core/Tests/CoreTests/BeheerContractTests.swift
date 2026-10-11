// SPDX-License-Identifier: AGPL-3.0-or-later
import XCTest
@testable import Core

/// Decodes the fixtures of the "beheer" API (roles, `/pbx/*`, calls, voicemail, contacts). They are validated against
/// `shared/openapi.yaml` by `scripts/validate-contract.ts`; these tests prove the Swift models read (and, for requests, write) them.
final class BeheerContractTests: XCTestCase {
    static let coveredFixtures: Set<String> = [
        "me-response-admin", "me-response-admin-frozen", "me-response-user",
        "pbx-overview", "pbx-devices", "pbx-device-patch", "pbx-ring-groups", "pbx-ring-group-create", "pbx-ring-group-created",
        "pbx-ring-group-patch", "pbx-hours", "pbx-hours-patch", "pbx-routing-patch", "pbx-routing-patch-entry",
        "calls-page", "calls-page-user", "voicemail-page", "voicemail-page-unavailable",
        "contacts-page", "contacts-since", "contact-detail", "contact-create-request", "contact-update-request", "contact-update-response",
        "contact-delete-response", "contact-lists", "contact-list-snapshot",
        "error-forbidden", "error-stale", "error-stale-contact", "error-blocked-destination", "error-read-only", "error-gone", "error-resync",
        "error-invalid-field", "error-in-use", "error-conflict-limit",
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

    /// Re-encodes a fixture with extra keys on every object (additive server change) and decodes it again.
    private func withFutureFields(_ fixture: String) throws -> Data {
        func decorate(_ value: Any) -> Any {
            if let object = value as? [String: Any] {
                var copy = object.mapValues(decorate)
                copy["futureField"] = ["a": 1]

                return copy
            }

            if let array = value as? [Any] {
                return array.map(decorate)
            }

            return value
        }

        return try JSONSerialization.data(withJSONObject: decorate(JSONSerialization.jsonObject(with: Fixtures.data(fixture))))
    }

    // MARK: /me

    func testMeAdminRoleAndCapabilities() throws {
        let me = try decode(MeResponse.self, "me-response-admin")

        XCTAssertEqual(me.role, .admin)
        XCTAssertEqual(me.effectiveRole, .admin)
        XCTAssertTrue(me.canManagePbx)
        XCTAssertEqual(me.capabilities, AppCapabilities(pbxManage: true, recordings: true, voicemail: .all, calls: .all, contacts: ContactCapabilities(read: true, write: true, delete: true)))
        XCTAssertEqual(me.pbx, PbxStatus(name: "Voorbeeld Bouw", state: .active, readOnly: false))
        XCTAssertEqual(me.contacts.total, 148)
        XCTAssertEqual(me.contacts.listsAvailable, 2)
    }

    func testMeUserHasNoPbxSection() throws {
        let me = try decode(MeResponse.self, "me-response-user")

        XCTAssertEqual(me.role, .user)
        XCTAssertFalse(me.canManagePbx)
        XCTAssertEqual(me.capabilities?.voicemail, .own)
        XCTAssertEqual(me.capabilities?.calls, .own)
        XCTAssertFalse(me.capabilities?.recordings ?? true)
        XCTAssertFalse(me.capabilities?.contacts.delete ?? true)
    }

    func testFrozenPbxIsReadOnly() throws {
        let me = try decode(MeResponse.self, "me-response-admin-frozen")

        XCTAssertEqual(me.pbx?.state, .frozen)
        XCTAssertEqual(me.pbx?.readOnly, true)
    }

    func testMeFromBeforeRolesStillDecodesAndCountsAsUser() throws {
        let me = try decode(MeResponse.self, "me-response")

        XCTAssertNil(me.role)
        XCTAssertNil(me.capabilities)
        XCTAssertNil(me.pbx)
        XCTAssertNil(me.contacts.total)
        XCTAssertEqual(me.effectiveRole, .user)
        XCTAssertFalse(me.canManagePbx)
    }

    func testUnknownRoleAndStateFallBackSafely() throws {
        var object = try XCTUnwrap(JSONSerialization.jsonObject(with: Fixtures.data("me-response-admin")) as? [String: Any])
        object["role"] = "owner"
        object["pbx"] = ["name": "X", "state": "migrating", "readOnly": false]
        object["capabilities"] = ["pbxManage": true, "voicemail": "everything", "calls": "some"]

        let me = try decoder.decode(MeResponse.self, from: JSONSerialization.data(withJSONObject: object))

        XCTAssertEqual(me.role, .unknown)
        XCTAssertEqual(me.effectiveRole, .user, "an unknown role never gets admin rights")
        XCTAssertFalse(me.canManagePbx)
        XCTAssertEqual(me.pbx?.state, .unknown)
        XCTAssertEqual(me.capabilities?.voicemail, VoicemailAccess.noAccess)
        XCTAssertEqual(me.capabilities?.calls, .own)
        XCTAssertFalse(me.capabilities?.recordings ?? true, "a missing capability means no")
    }

    // MARK: /pbx

    func testPbxOverview() throws {
        let overview = try decode(PbxOverview.self, "pbx-overview")

        XCTAssertEqual(overview.pbx.state, .active)
        XCTAssertEqual(overview.numbers.count, 2)
        XCTAssertEqual(overview.deviceCount, 3)
        XCTAssertEqual(overview.connectedCount, 2)
        XCTAssertFalse(overview.outboundBlocked)

        let first = overview.numbers[0]
        XCTAssertEqual(first.number, "0850607848")
        XCTAssertEqual(first.routing, PbxTarget(type: .businessHours, id: "c3b2a1f0-e9d8-4c7b-a6f5-e4d3c2b1a0f9"))
        XCTAssertEqual(first.version, 3)
        XCTAssertEqual(first.sync, .ok)

        let flow = try XCTUnwrap(first.flow)
        XCTAssertEqual(flow.kind, .businessHours)
        XCTAssertEqual(flow.extensionNumber, "500")
        XCTAssertEqual(flow.branches.map(\.when.kind), [.open, .closed])
        XCTAssertEqual(flow.branches[0].node.kind, .ringGroup)
        XCTAssertEqual(flow.branches[0].node.branches.first?.when, FlowWhen(kind: .noAnswer, seconds: 25, digit: nil))
        XCTAssertEqual(flow.branches[0].node.branches.first?.node.kind, .voicemail)

        let second = overview.numbers[1]
        XCTAssertNil(second.routing)
        XCTAssertEqual(second.sync, .pending)
        XCTAssertEqual(second.flow?.kind, FlowKind.noDestination)
        XCTAssertNil(overview.entryFlow)
    }

    func testPbxDevices() throws {
        let page = try decode(PbxDevicesResponse.self, "pbx-devices")

        XCTAssertEqual(page.devices.map(\.name), ["Receptie", "Pieter Jansen", "Jan de Vries"])
        XCTAssertEqual(page.numbers.map(\.number), ["0850607848", "0850607849"])

        let pieter = page.devices[1]
        XCTAssertTrue(pieter.dnd)
        XCTAssertEqual(pieter.version, 5)
        XCTAssertEqual(pieter.forwardAlways, .external("+31612345678"))
        XCTAssertEqual(pieter.registration, DeviceRegistration(connected: false, count: 0, agent: nil))

        let jan = page.devices[2]
        XCTAssertNil(jan.registration, "null = the PBX did not answer")
        XCTAssertEqual(jan.sync, .pending)
        XCTAssertEqual(jan.followMe, [FollowMeStep(target: .external("+31687654321"), delaySeconds: 10, timeoutSeconds: 20)])

        XCTAssertEqual(page.targets.count, 7)
        XCTAssertTrue(page.targets[5].ofDevice)
        XCTAssertFalse(page.targets[0].ofDevice)
        XCTAssertEqual(page.targets[6].type, .external)
        XCTAssertNil(page.targets[6].id)
    }

    func testDevicePatchEncodesExplicitNullsAndLeavesKeptKeysOut() throws {
        let patch = PbxDevicePatch(
            version: 4,
            outboundNumberId: .clear,
            dnd: true,
            noAnswerSeconds: 20,
            noAnswerTarget: .set(.object(.voicemail, id: "7a1d9c3e-5b2f-4e8a-9c01-6d3e8f2a4b57")),
            busyTarget: .clear,
            forwardAlways: .set(.external("+31612345678")),
            followMe: [FollowMeStep(target: .object(.ringGroup, id: "9e8d7c6b-5a49-4382-b1a0-f9e8d7c6b5a4"), delaySeconds: 0, timeoutSeconds: 25)]
        )

        let object = try XCTUnwrap(JSONSerialization.jsonObject(with: FSVoipJSON.encoder().encode(patch)) as? [String: Any])
        XCTAssertNil(object["notRegisteredTarget"], "an untouched field is left out")
        XCTAssertNil(object["voicemailEnabled"])
        XCTAssertTrue(object["busyTarget"] is NSNull, "clear is an explicit null")
        XCTAssertTrue(object["outboundNumberId"] is NSNull)
        XCTAssertEqual(object["version"] as? Int, 4)
    }

    func testDevicePatchMatchesTheFixture() throws {
        let patch = PbxDevicePatch(
            version: 4,
            outboundNumberId: .clear,
            dnd: true,
            noAnswerSeconds: 20,
            noAnswerTarget: .set(.object(.voicemail, id: "7a1d9c3e-5b2f-4e8a-9c01-6d3e8f2a4b57")),
            busyTarget: .clear,
            forwardAlways: .set(.external("+31612345678")),
            followMe: [FollowMeStep(target: .object(.ringGroup, id: "9e8d7c6b-5a49-4382-b1a0-f9e8d7c6b5a4"), delaySeconds: 0, timeoutSeconds: 25)]
        )

        try assertJSONEqual(patch, "pbx-device-patch")
    }

    func testRingGroups() throws {
        let page = try decode(PbxRingGroupsResponse.self, "pbx-ring-groups")

        XCTAssertEqual(page.ringGroups.count, 1)
        XCTAssertEqual(page.ringGroups[0].strategy, .all)
        XCTAssertEqual(page.ringGroups[0].members.count, 2)
        XCTAssertEqual(page.ringGroups[0].timeoutTarget?.type, .voicemail)
        XCTAssertEqual(page.devices.map(\.extensionNumber), ["100", "101", "102"])
        XCTAssertEqual(try decode(PbxRingGroupCreated.self, "pbx-ring-group-created").ringGroupId, "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d")
    }

    func testRingGroupRequests() throws {
        let create = PbxRingGroupCreate(
            name: "Support",
            strategy: .sequence,
            members: [
                RingGroupMember(extensionId: "1b2c3d4e-0a1b-4c2d-8e3f-5a6b7c8d9e01", delaySeconds: 0, timeoutSeconds: 20),
                RingGroupMember(extensionId: "7a1d9c3e-5b2f-4e8a-9c01-6d3e8f2a4b57", delaySeconds: 20, timeoutSeconds: 20),
            ],
            timeoutTarget: .set(.object(.voicemail, id: "2f4a6c8e-1b3d-4f5a-8c7e-9a0b1c2d3e4f"))
        )
        try assertJSONEqual(create, "pbx-ring-group-create")
        try assertJSONEqual(PbxRingGroupPatch(version: 2, name: "Alle collega's", strategy: .round, timeoutTarget: .clear), "pbx-ring-group-patch")
    }

    func testHours() throws {
        let page = try decode(PbxHoursResponse.self, "pbx-hours")
        let hours = try XCTUnwrap(page.hours.first)

        XCTAssertEqual(hours.week.count, 5)
        XCTAssertEqual(hours.week[0], HoursWeekEntry(day: 1, from: "09:00", to: "17:00"))
        XCTAssertEqual(hours.openTarget?.type, .ringGroup)
        XCTAssertEqual(hours.holidays.map(\.rule), ["christmas_day", nil])
        XCTAssertEqual(hours.holidays[1].date, "2026-08-03")
        XCTAssertEqual(hours.holidays[1].target?.type, .voicemail)
        XCTAssertEqual(hours.version, 6)
        XCTAssertEqual(page.holidayRules.map(\.rule), ["new_year", "kings_day", "christmas_day", "boxing_day"])
    }

    func testHoursPatchAndRoutingPatch() throws {
        let patch = PbxHoursPatch(
            version: 6,
            week: [HoursWeekEntry(day: 1, from: "08:30", to: "12:00"), HoursWeekEntry(day: 1, from: "13:00", to: "17:30"), HoursWeekEntry(day: 5, from: "09:00", to: "24:00")],
            openTarget: .set(.object(.ringGroup, id: "9e8d7c6b-5a49-4382-b1a0-f9e8d7c6b5a4")),
            closedTarget: .clear,
            holidays: [.rule("christmas_day"), .custom(name: "Bedrijfsuitje", date: "2026-11-13")],
            holidayTarget: .set(.object(.voicemail, id: "2f4a6c8e-1b3d-4f5a-8c7e-9a0b1c2d3e4f"))
        )
        try assertJSONEqual(patch, "pbx-hours-patch")
        try assertJSONEqual(PbxRoutingPatch(target: .object(.ringGroup, id: "9e8d7c6b-5a49-4382-b1a0-f9e8d7c6b5a4"), version: 3), "pbx-routing-patch")
        try assertJSONEqual(PbxRoutingPatch(target: nil, version: 3), "pbx-routing-patch-entry")
    }

    func testUnknownEnumValuesDecodeAndCanNotBeSent() throws {
        var object = try XCTUnwrap(JSONSerialization.jsonObject(with: Fixtures.data("pbx-ring-groups")) as? [String: Any])
        var groups = try XCTUnwrap(object["ringGroups"] as? [[String: Any]])
        groups[0]["strategy"] = "random"
        groups[0]["sync"] = "rebooting"
        groups[0]["timeoutTarget"] = ["type": "teleport", "id": "x"]
        object["ringGroups"] = groups

        let page = try decoder.decode(PbxRingGroupsResponse.self, from: JSONSerialization.data(withJSONObject: object))
        XCTAssertEqual(page.ringGroups[0].strategy, .unknown)
        XCTAssertEqual(page.ringGroups[0].sync, .unknown)
        XCTAssertEqual(page.ringGroups[0].timeoutTarget?.type, .unknown)

        // `.unknown` is a decoding fallback: sending it back is an app bug, so encoding refuses.
        XCTAssertThrowsError(try FSVoipJSON.encoder().encode(PbxRingGroupPatch(version: 1, strategy: .unknown)))
        XCTAssertThrowsError(try FSVoipJSON.encoder().encode(PbxRoutingPatch(target: PbxTarget(type: .unknown, id: "x"), version: 1)))
    }

    func testPbxResponsesIgnoreUnknownFields() throws {
        XCTAssertNoThrow(try decoder.decode(PbxOverview.self, from: withFutureFields("pbx-overview")))
        XCTAssertNoThrow(try decoder.decode(PbxDevicesResponse.self, from: withFutureFields("pbx-devices")))
        XCTAssertNoThrow(try decoder.decode(PbxRingGroupsResponse.self, from: withFutureFields("pbx-ring-groups")))
        XCTAssertNoThrow(try decoder.decode(PbxHoursResponse.self, from: withFutureFields("pbx-hours")))
        XCTAssertNoThrow(try decoder.decode(MeResponse.self, from: withFutureFields("me-response-admin")))
    }

    // MARK: Calls and voicemail

    func testCallsPage() throws {
        let page = try decode(CallsPage.self, "calls-page")

        XCTAssertEqual(page.month, "2026-10")
        XCTAssertEqual(page.months, ["2026-10", "2026-09"])
        XCTAssertFalse(page.truncated)
        XCTAssertEqual(page.calls.count, 4)

        let inbound = page.calls[0]
        XCTAssertEqual(inbound.direction, .inbound)
        XCTAssertEqual(inbound.outcome, .answered)
        XCTAssertEqual(inbound.number, "070 123 45 67")
        XCTAssertEqual(inbound.extensionNumber, "102")
        XCTAssertEqual(inbound.lineType, .fixed)
        XCTAssertEqual(inbound.category, .fixed)
        XCTAssertTrue(inbound.hasRecording)
        XCTAssertFalse(inbound.recordingExpired)
        XCTAssertEqual(page.calls[1].lineType, .mobile)

        let expired = page.calls[2]
        XCTAssertEqual(expired.outcome, .voicemail)
        XCTAssertNil(expired.number)
        XCTAssertTrue(expired.recordingExpired)
        XCTAssertFalse(expired.hasRecording)
        XCTAssertEqual(page.calls[3].direction, .internal)
    }

    func testCallsPageOfAUserHasNoRecordings() throws {
        let page = try decode(CallsPage.self, "calls-page-user")

        XCTAssertTrue(page.truncated)
        XCTAssertEqual(page.calls.count, 1)
        XCTAssertFalse(page.calls[0].hasRecording)
        XCTAssertFalse(page.calls[0].recordingExpired, "absent for a user pairing")
    }

    func testUnknownCallValues() throws {
        var object = try XCTUnwrap(JSONSerialization.jsonObject(with: Fixtures.data("calls-page")) as? [String: Any])
        var calls = try XCTUnwrap(object["calls"] as? [[String: Any]])
        calls[0]["direction"] = "sidewards"
        calls[0]["outcome"] = "teleported"
        calls[0]["category"] = "premium_plus"
        calls[0]["lineType"] = "voip"
        object["calls"] = calls

        let page = try decoder.decode(CallsPage.self, from: JSONSerialization.data(withJSONObject: object))
        XCTAssertEqual(page.calls[0].direction, .unknown)
        XCTAssertEqual(page.calls[0].outcome, .unknown)
        XCTAssertEqual(page.calls[0].category, .other)
        XCTAssertEqual(page.calls[0].lineType, .unknown)
    }

    func testVoicemailPages() throws {
        let page = try decode(VoicemailPage.self, "voicemail-page")

        XCTAssertTrue(page.available)
        XCTAssertNil(page.boxId)
        XCTAssertEqual(page.boxes.map(\.shared), [true, false])
        XCTAssertEqual(page.messages.count, 2)
        XCTAssertEqual(page.messages[0].caller, "070 123 45 67")
        XCTAssertEqual(page.messages[0].callerName, "Bakkerij Smit")
        XCTAssertTrue(page.messages[0].isNew)
        XCTAssertEqual(page.messages[0].daysLeft, 29)
        XCTAssertNil(page.messages[1].caller)
        XCTAssertEqual(page.messages[1].transcription, "Bel mij even terug.")

        let unavailable = try decode(VoicemailPage.self, "voicemail-page-unavailable")
        XCTAssertFalse(unavailable.available)
        XCTAssertTrue(unavailable.messages.isEmpty)
        XCTAssertEqual(unavailable.boxId, "7a1d9c3e-5b2f-4e8a-9c01-6d3e8f2a4b57")
    }

    // MARK: Contacts

    func testContactsPage() throws {
        let page = try decode(ContactsPage.self, "contacts-page")

        XCTAssertEqual(page.contacts.count, 2)
        XCTAssertTrue(page.deleted.isEmpty)
        XCTAssertNil(page.nextCursor)
        XCTAssertEqual(page.serverTime, "2026-10-09T08:15:30.123Z")

        let smit = page.contacts[0]
        XCTAssertEqual(smit.name, "Bakkerij Smit")
        XCTAssertEqual(smit.company, "Bakkerij Smit")
        XCTAssertNil(smit.firstName)
        XCTAssertEqual(smit.phones, [ContactPhone(number: "+31701234567", label: .work, isPrimary: true)])
        XCTAssertEqual(smit.tags, ["klant"])
        XCTAssertEqual(smit.updatedAt, "2026-10-07T09:30:12.345Z", "kept as the exact string the server sent")
        XCTAssertNotNil(smit.updatedAtDate)
    }

    func testContactsSince() throws {
        let page = try decode(ContactsPage.self, "contacts-since")

        XCTAssertEqual(page.contacts.map(\.id), ["7a8b9c0d-1e2f-4a3b-9c4d-5e6f7a8b9c0d"])
        XCTAssertEqual(page.deleted, ["8b9c0d1e-2f3a-4b4c-8d5e-6f7a8b9c0d1e"])
        XCTAssertNotNil(page.nextCursor)
        XCTAssertEqual(page.contacts[0].phones.map(\.label), [.mobile, .work])
    }

    func testContactDetailAndUpdateResponse() throws {
        let detail = try decode(ContactSingleResponse.self, "contact-detail").contact

        XCTAssertEqual(detail.id, "6f7a8b9c-0d1e-4f2a-8b3c-4d5e6f7a8b9c")
        XCTAssertEqual(detail.contact.name, "Bakkerij Smit")
        XCTAssertEqual(detail.notes, "Levert brood op maandag.")
        XCTAssertEqual(detail.listIds, ["0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"])

        let updated = try decode(ContactUpdateResponse.self, "contact-update-response")
        XCTAssertTrue(updated.changed)
        XCTAssertEqual(updated.contact.contact.phones.count, 2)
        XCTAssertEqual(updated.contact.contact.updatedAt, "2026-10-09T08:20:00.000Z")
        XCTAssertEqual(try decode(ContactDeleteResponse.self, "contact-delete-response").deleted, 1)
    }

    func testContactRequests() throws {
        let create = ContactCreate(
            name: "Bakkerij Smit",
            company: .set("Bakkerij Smit"),
            email: .set("info@bakkerijsmit.nl"),
            phones: [ContactPhone(number: "+31701234567", label: .work, isPrimary: true)],
            tags: ["klant"],
            listIds: ["0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"]
        )
        try assertJSONEqual(create, "contact-create-request")

        let update = ContactUpdate(
            expectedUpdatedAt: "2026-10-07T09:30:12.345Z",
            firstName: .clear,
            notes: .set("Nieuw nummer van de eigenaar."),
            phones: [ContactPhone(number: "+31701234567", label: .work, isPrimary: true), ContactPhone(number: "+31612345678", label: .mobile, isPrimary: false)]
        )
        try assertJSONEqual(update, "contact-update-request")
    }

    func testAnUpdateAlwaysCarriesExpectedUpdatedAt() throws {
        // The type has no way to leave it out.
        let object = try XCTUnwrap(JSONSerialization.jsonObject(with: FSVoipJSON.encoder().encode(ContactUpdate(expectedUpdatedAt: "2026-10-07T09:30:12.345Z", company: .clear))) as? [String: Any])

        XCTAssertEqual(object["expectedUpdatedAt"] as? String, "2026-10-07T09:30:12.345Z")
        XCTAssertTrue(object["company"] is NSNull)
        XCTAssertNil(object["email"])
    }

    func testContactLists() throws {
        let lists = try decode(ContactListsResponse.self, "contact-lists").lists

        XCTAssertEqual(lists.map(\.name), ["Klanten", "Leveranciers"])
        XCTAssertEqual(lists[0].version, 3)
        XCTAssertEqual(lists[1].contactCount, 0)
        XCTAssertEqual(ContactListETag.make(version: lists[0].version), "\"3\"")

        let snapshot = try decode(ContactListSnapshot.self, "contact-list-snapshot")
        XCTAssertEqual(snapshot.list.version, 3)
        XCTAssertEqual(snapshot.contacts.count, 2)
        XCTAssertEqual(snapshot.contacts[1].phones.map(\.label), [.mobile, .work])
        XCTAssertNil(snapshot.contacts[1].company)
        XCTAssertNil(snapshot.nextCursor)
    }

    func testUnknownPhoneLabelBecomesUnknownButCanNotBeSent() throws {
        let phone = try decoder.decode(ContactPhone.self, from: Data(#"{"number":"+31701234567","label":"pager"}"#.utf8))

        XCTAssertEqual(phone.label, .unknown)
        XCTAssertFalse(phone.isPrimary)
        XCTAssertThrowsError(try FSVoipJSON.encoder().encode(phone))
    }

    func testContactResponsesIgnoreUnknownFields() throws {
        XCTAssertNoThrow(try decoder.decode(ContactsPage.self, from: withFutureFields("contacts-since")))
        XCTAssertNoThrow(try decoder.decode(ContactSingleResponse.self, from: withFutureFields("contact-detail")))
        XCTAssertNoThrow(try decoder.decode(ContactListSnapshot.self, from: withFutureFields("contact-list-snapshot")))
        XCTAssertNoThrow(try decoder.decode(CallsPage.self, from: withFutureFields("calls-page")))
        XCTAssertNoThrow(try decoder.decode(VoicemailPage.self, from: withFutureFields("voicemail-page")))
    }

    // MARK: Errors

    func testErrorBodiesOfTheBeheerRoutes() throws {
        XCTAssertEqual(try decode(APIErrorBody.self, "error-forbidden").requiredRole, "admin")
        XCTAssertEqual(try decode(APIErrorBody.self, "error-stale").version, 5)
        XCTAssertNil(try decode(APIErrorBody.self, "error-stale-contact").version)
        XCTAssertEqual(try decode(APIErrorBody.self, "error-blocked-destination").blocked, ["+449000000000"])
        XCTAssertEqual(try decode(APIErrorBody.self, "error-resync").code, "resync")
        XCTAssertEqual(try decode(APIErrorBody.self, "error-invalid-field").field, "phones")
        XCTAssertEqual(try decode(APIErrorBody.self, "error-in-use").places, [APIErrorPlace(kind: "object", name: "Kantoortijden")])
        XCTAssertEqual(try decode(APIErrorBody.self, "error-conflict-limit").code, "limit_reached")
    }
}
