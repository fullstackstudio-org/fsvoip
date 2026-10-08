// SPDX-License-Identifier: AGPL-3.0-or-later
import Foundation

/// What the system call screen shows (plan D10). CallKit only displays `localizedCallerName` and a handle, so the
/// text is built here.
public enum CallDisplay {
    /// Standard: `<caller>`. With "show dialled account" (always on when several accounts are paired):
    /// `<caller> → <account label>`.
    public static func callerText(callerName: String?, callerNumber: String?, accountLabel: String, showAccount: Bool, anonymous: String = "Onbekend") -> String {
        let name = callerName?.trimmingCharacters(in: .whitespacesAndNewlines)
        let number = callerNumber?.trimmingCharacters(in: .whitespacesAndNewlines)
        let caller: String

        if let name, !name.isEmpty {
            caller = name
        } else if let number, !number.isEmpty {
            caller = number
        } else {
            caller = anonymous
        }

        guard showAccount, !accountLabel.isEmpty else {
            return caller
        }

        return "\(caller) → \(accountLabel)"
    }

    /// With several accounts the account is always shown, otherwise the user's setting decides.
    public static func shouldShowAccount(setting: Bool, accountCount: Int) -> Bool {
        setting || accountCount > 1
    }
}
