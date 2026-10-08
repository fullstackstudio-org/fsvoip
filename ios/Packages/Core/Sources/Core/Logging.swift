// SPDX-License-Identifier: AGPL-3.0-or-later
import Foundation
import os

/// Removes everything that must never reach a log, a crash report or a bug report from a message.
///
/// Redacted: FSVoip tokens (`fss_vapp_...`, `fss_vpair_...`), `Bearer` credentials, values of keys that
/// look like secrets (`password`, `token`, ...), SIP `Authorization` headers and long hex strings (push tokens).
public enum LogRedactor {
    private static let rules: [(NSRegularExpression, String)] = {
        func rule(_ pattern: String, _ template: String) -> (NSRegularExpression, String) {
            // The patterns are constants; a typo is a programming error.
            // swiftlint:disable:next force_try
            (try! NSRegularExpression(pattern: pattern, options: []), template)
        }

        return [
            rule(#"fss_(vapp|vpair)_[A-Za-z0-9_-]{6,}"#, "fss_$1_[redacted]"),
            rule(#"(?i)\bbearer\s+[A-Za-z0-9._~+/=-]{6,}"#, "Bearer [redacted]"),
            rule(#"(?i)\b(proxy-authorization|authorization)\s*:[^\r\n]*"#, "$1: [redacted]"),
            rule(
                #"(?i)(?<![A-Za-z0-9_])("?(?:password|passwd|pass|secret|devicetoken|pushtoken|alertpushtoken|token|apikey|api_key)"?\s*[:=]\s*)("(?:[^"\\]|\\.)*"|[^\s,;&}\])]+)"#,
                "$1\"[redacted]\""
            ),
            rule(#"\b[0-9a-fA-F]{64,}\b"#, "[redacted-hex]"),
        ]
    }()

    public static func redact(_ text: String) -> String {
        var result = text

        for (expression, template) in rules {
            let range = NSRange(result.startIndex ..< result.endIndex, in: result)
            result = expression.stringByReplacingMatches(in: result, options: [], range: range, withTemplate: template)
        }

        return result
    }
}

public enum LogLevel: Int, Comparable, Sendable {
    case debug = 0
    case info
    case notice
    case error

    public static func < (lhs: LogLevel, rhs: LogLevel) -> Bool {
        lhs.rawValue < rhs.rawValue
    }
}

public protocol LogSink: Sendable {
    /// Receives text that is ALREADY redacted.
    func write(level: LogLevel, category: String, message: String)
}

/// The system log. Messages are redacted before they get here, so they are safe to mark `public`.
public struct OSLogSink: LogSink {
    private let subsystem: String

    public init(subsystem: String = "nl.fullstackstudio.fsvoip") {
        self.subsystem = subsystem
    }

    public func write(level: LogLevel, category: String, message: String) {
        let logger = os.Logger(subsystem: subsystem, category: category)

        switch level {
        case .debug:
            logger.debug("\(message, privacy: .public)")
        case .info:
            logger.info("\(message, privacy: .public)")
        case .notice:
            logger.notice("\(message, privacy: .public)")
        case .error:
            logger.error("\(message, privacy: .public)")
        }
    }
}

/// Collects messages in memory (tests).
public final class MemoryLogSink: LogSink, @unchecked Sendable {
    private let lock = NSLock()
    private var storage: [(LogLevel, String, String)] = []

    public init() {}

    public func write(level: LogLevel, category: String, message: String) {
        lock.lock()
        storage.append((level, category, message))
        lock.unlock()
    }

    public var messages: [String] {
        lock.lock()
        defer { lock.unlock() }

        return storage.map(\.2)
    }
}

/// The only logger the app uses. Every message passes the `LogRedactor`.
public struct FSLogger: Sendable {
    public let category: String
    private let sink: LogSink
    private let minimumLevel: LogLevel

    public init(category: String, sink: LogSink = OSLogSink(), minimumLevel: LogLevel = .debug) {
        self.category = category
        self.sink = sink
        self.minimumLevel = minimumLevel
    }

    public func log(_ level: LogLevel, _ message: @autoclosure () -> String) {
        guard level >= minimumLevel else {
            return
        }

        sink.write(level: level, category: category, message: LogRedactor.redact(message()))
    }

    public func debug(_ message: @autoclosure () -> String) {
        log(.debug, message())
    }

    public func info(_ message: @autoclosure () -> String) {
        log(.info, message())
    }

    public func notice(_ message: @autoclosure () -> String) {
        log(.notice, message())
    }

    public func error(_ message: @autoclosure () -> String) {
        log(.error, message())
    }
}
