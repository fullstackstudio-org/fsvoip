// SPDX-License-Identifier: AGPL-3.0-or-later
import SwiftUI

/// The "Appropriate Legal Notices" of the AGPL (section 0 and 5d): copyright, the licence, no warranty, and where the
/// source is. Reached from Settings > About; deliberately not on the main settings screen.
struct LicensesView: View {
    var body: some View {
        List {
            Section {
                L10n.text("licenses.fsvoip.body")
                Link(destination: URL(string: "https://www.gnu.org/licenses/agpl-3.0.html")!) {
                    Label(L10n.string("licenses.licenseText"), systemImage: "doc.text")
                }
                Link(destination: URL(string: "https://github.com/fullstackstudio-org/fsvoip")!) {
                    Label(L10n.string("licenses.source"), systemImage: "chevron.left.forwardslash.chevron.right")
                }
            } header: {
                Text(verbatim: "FSVoip")
            }

            Section {
                L10n.text("licenses.linphone.body")
                Link(destination: URL(string: "https://linphone.org")!) {
                    Label(L10n.string("licenses.linphone.site"), systemImage: "globe")
                }
            } header: {
                Text(verbatim: "Linphone SDK")
            }
        }
        .navigationTitle(L10n.string("licenses.title"))
    }
}
