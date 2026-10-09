// SPDX-License-Identifier: AGPL-3.0-or-later
import SwiftUI

extension View {
    /// Pull to refresh whose work SwiftUI cannot cancel halfway.
    ///
    /// SwiftUI cancels the task of `.refreshable` when the view updates while it runs (a published value that changes during the load
    /// is enough). The cancelled `URLSession` request then surfaced as "no connection" although the internet worked (TestFlight,
    /// Opnames). The work runs in its own task; the spinner still waits for it.
    func detachedRefreshable(_ action: @escaping @MainActor () async -> Void) -> some View {
        refreshable {
            await Task { @MainActor in await action() }.value
        }
    }
}
