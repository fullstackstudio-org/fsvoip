// SPDX-License-Identifier: AGPL-3.0-or-later
import Foundation
import SwiftUI

enum L10n {
    static var bundle: Bundle {
        .module
    }

    static func string(_ key: String) -> String {
        NSLocalizedString(key, bundle: .module, comment: "")
    }

    static func text(_ key: LocalizedStringKey) -> Text {
        Text(key, bundle: .module)
    }
}
