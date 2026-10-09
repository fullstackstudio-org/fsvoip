// SPDX-License-Identifier: AGPL-3.0-or-later
import Core
import XCTest
@testable import FSContacts

final class ContactDraftTests: XCTestCase {
    private func body<T: Encodable>(_ value: T) throws -> [String: Any] {
        try XCTUnwrap(JSONSerialization.jsonObject(with: FSVoipJSON.encoder().encode(value)) as? [String: Any])
    }

    func testANewContactNeedsANameAndAWayToReachIt() {
        var draft = ContactDraft()
        XCTAssertFalse(draft.canSave)

        draft.firstName = "Pieter"
        XCTAssertFalse(draft.canSave, "a name alone is not reachable")

        draft.phones[0].number = "06 12 34 56 78"
        XCTAssertTrue(draft.canSave)

        draft.phones[0].number = ""
        draft.email = "p@example.nl"
        XCTAssertTrue(draft.canSave)

        draft.firstName = ""
        XCTAssertFalse(draft.canSave)
        draft.company = "Smit"
        XCTAssertTrue(draft.canSave, "a company is a name")
    }

    func testCreateSendsNumbersInInternationalFormAndLeavesEmptyFieldsOut() throws {
        var draft = ContactDraft()
        draft.firstName = " Pieter "
        draft.lastName = "de Groot"
        draft.phones = [.init(number: "06 12 34 56 78", label: .mobile), .init(number: "", label: .work), .init(number: "070-1234567", label: .work)]
        draft.listIds = ["l2", "l1"]

        let json = try body(draft.createRequest())

        XCTAssertEqual(json["name"] as? String, "Pieter de Groot")
        XCTAssertEqual(json["firstName"] as? String, "Pieter")
        XCTAssertNil(json["company"], "an empty field is left out on create")
        XCTAssertNil(json["notes"])
        XCTAssertEqual(json["listIds"] as? [String], ["l1", "l2"])

        let phones = try XCTUnwrap(json["phones"] as? [[String: Any]])
        XCTAssertEqual(phones.map { $0["number"] as? String }, ["+31612345678", "+31701234567"])
        XCTAssertEqual(phones.map { $0["isPrimary"] as? Bool }, [true, false])
    }

    func testUpdateAlwaysCarriesExpectedUpdatedAtAndClearsEmptiedFields() throws {
        var draft = ContactDraft(detail: Make.detail(Make.contact("c1", "Pieter de Groot", phones: ["+31612345678"], company: "Smit", email: "p@example.nl"), notes: "n", listIds: ["l1"]))
        draft.company = ""
        draft.notes = ""

        let json = try body(draft.updateRequest(expectedUpdatedAt: "2026-10-09T10:00:00.000Z"))

        XCTAssertEqual(json["expectedUpdatedAt"] as? String, "2026-10-09T10:00:00.000Z")
        XCTAssertTrue(json["company"] is NSNull, "an emptied field is cleared with null")
        XCTAssertTrue(json["notes"] is NSNull)
        XCTAssertEqual(json["email"] as? String, "p@example.nl")
        XCTAssertEqual(json["listIds"] as? [String], ["l1"])
        XCTAssertNil(json["tags"], "tags are not edited on the phone: left unchanged")
    }

    func testAContactThatOnlyHasADisplayNameKeepsIt() {
        let draft = ContactDraft(detail: Make.detail(Make.contact("c1", "Pieter de Groot", phones: ["+31612345678"])))

        XCTAssertEqual(draft.displayName, "Pieter de Groot")
        XCTAssertEqual(draft.listIds, [])
    }

    func testACompanyContactStaysACompany() {
        let draft = ContactDraft(detail: Make.detail(Make.contact("c1", "Bakkerij Smit", phones: ["+31701234567"], company: "Bakkerij Smit")))

        XCTAssertEqual(draft.company, "Bakkerij Smit")
        XCTAssertEqual(draft.firstName, "")
        XCTAssertEqual(draft.displayName, "Bakkerij Smit")
    }
}
