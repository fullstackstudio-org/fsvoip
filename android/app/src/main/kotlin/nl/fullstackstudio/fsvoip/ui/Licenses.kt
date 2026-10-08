// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Language
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import nl.fullstackstudio.fsvoip.R

/**
 * The "Appropriate Legal Notices" of the AGPL (section 0 and 5d): copyright, the licence, no warranty, and where the
 * source is. Reached from Settings > About; deliberately not on the main settings screen (same as iOS).
 */
@Composable
fun LicensesScreen(back: () -> Unit) {
    val context = LocalContext.current
    val open = { url: String -> context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }

    SubpageScaffold(stringResource(R.string.licenses_title), back) {
        section("FSVoip", null) {
            InfoRow(stringResource(R.string.licenses_fsvoip_body))
            ActionRow(stringResource(R.string.licenses_licenseText), Icons.Filled.Description) { open("https://www.gnu.org/licenses/agpl-3.0.html") }
            ActionRow(stringResource(R.string.licenses_source), Icons.Filled.Code) { open("https://github.com/fullstackstudio-org/fsvoip") }
        }

        section("Linphone SDK", null) {
            InfoRow(stringResource(R.string.licenses_linphone_body))
            ActionRow(stringResource(R.string.licenses_linphone_site), Icons.Filled.Language) { open("https://linphone.org") }
        }

        section(stringResource(R.string.licenses_others_title), null) {
            InfoRow(stringResource(R.string.licenses_others_body))
        }
    }
}
