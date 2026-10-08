// SPDX-License-Identifier: AGPL-3.0-or-later
import Core
import XCTest
@testable import Contacts

final class ContactsTests: XCTestCase {
    func testInternalContactsProviderMatchesByExtension() async throws {
        let provider = InternalContactsProvider([InternalContact(number: "100", name: "Receptie"), InternalContact(number: "101", name: "Pieter Jansen")])

        let all = try await provider.contacts()
        XCTAssertEqual(all.map(\.name), ["Receptie", "Pieter Jansen"])
        XCTAssertTrue(all.allSatisfy { $0.source == .internalExtensions })
        let name = await provider.name(forNumber: "101")
        XCTAssertEqual(name, "Pieter Jansen")
        let unknown = await provider.name(forNumber: "999")
        XCTAssertNil(unknown)
    }

    func testEmptyProvider() async throws {
        let contacts = try await EmptyContactsProvider().contacts()
        XCTAssertTrue(contacts.isEmpty)
    }
}
