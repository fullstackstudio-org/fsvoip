// SPDX-License-Identifier: AGPL-3.0-or-later
import XCTest
@testable import Core

final class LoggingTests: XCTestCase {
    func testTokensAreRedacted() {
        let pairing = "fss_vpair_" + String(repeating: "A", count: 43)
        let device = "fss_vapp_" + String(repeating: "B", count: 43)
        let text = LogRedactor.redact("link https://fullstackstudio.nl/fsvoip/pair?t=\(pairing) and \(device)")

        XCTAssertFalse(text.contains(String(repeating: "A", count: 20)))
        XCTAssertFalse(text.contains(String(repeating: "B", count: 20)))
        XCTAssertTrue(text.contains("fss_vpair_[redacted]"))
        XCTAssertTrue(text.contains("fss_vapp_[redacted]"))
    }

    func testBearerAndAuthorizationAreRedacted() {
        XCTAssertEqual(LogRedactor.redact("Authorization: Bearer abcdef123456"), "Authorization: [redacted]")
        XCTAssertEqual(LogRedactor.redact("sent Bearer abcdef123456 ok"), "sent Bearer [redacted] ok")
        XCTAssertEqual(
            LogRedactor.redact("Proxy-Authorization: Digest username=\"102\", realm=\"x\", response=\"deadbeef\""),
            "Proxy-Authorization: [redacted]"
        )
    }

    func testSecretLookingKeysAreRedacted() {
        XCTAssertEqual(LogRedactor.redact(#"{"password":"hunter2","user":"102"}"#), #"{"password":"[redacted]","user":"102"}"#)
        XCTAssertEqual(LogRedactor.redact("password=hunter2 user=102"), #"password="[redacted]" user=102"#)
        XCTAssertEqual(LogRedactor.redact(#"{"pushToken": "abc", "deviceToken": "def"}"#), #"{"pushToken": "[redacted]", "deviceToken": "[redacted]"}"#)
        XCTAssertEqual(LogRedactor.redact(#"secret: "a \" b""#), #"secret: "[redacted]""#)
    }

    func testPushTokensAndLongHexAreRedacted() {
        let token = String(repeating: "ab", count: 32)

        XCTAssertEqual(LogRedactor.redact("voip token \(token) registered"), "voip token [redacted-hex] registered")
    }

    func testOrdinaryTextSurvives() {
        let text = "Registered 102@voorbeeld-bouw.powervoip.nl via sip.powervoip.nl:5060 (tcp); pass through; bypass=1 encoder"

        XCTAssertEqual(LogRedactor.redact(text), text)
    }

    func testLoggerRedactsBeforeTheSinkSeesTheMessage() {
        let sink = MemoryLogSink()
        let logger = FSLogger(category: "test", sink: sink)
        let secret = "Secret(password)"

        logger.info("login password=\(secret) token=fss_vapp_\(String(repeating: "Z", count: 43))")

        XCTAssertEqual(sink.messages.count, 1)
        XCTAssertFalse(sink.messages[0].contains("Secret(password)"))
        XCTAssertFalse(sink.messages[0].contains("ZZZZZZZZ"))
    }

    func testMinimumLevelFilters() {
        let sink = MemoryLogSink()
        let logger = FSLogger(category: "test", sink: sink, minimumLevel: .notice)

        logger.debug("quiet")
        logger.info("quiet")
        logger.notice("loud")
        logger.error("louder")

        XCTAssertEqual(sink.messages, ["loud", "louder"])
    }

    func testSecretNeverPrints() {
        let secret = Secret("super-secret-value")

        XCTAssertEqual("\(secret)", "•••")
        XCTAssertEqual(String(reflecting: secret), "Secret(•••)")
        var dumped = ""
        dump(secret, to: &dumped)
        XCTAssertFalse(dumped.contains("super-secret-value"))
        XCTAssertEqual(secret.reveal(), "super-secret-value")
    }
}
