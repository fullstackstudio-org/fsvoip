// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.ui.media

import androidx.compose.runtime.Composable
import nl.fullstackstudio.fsvoip.AppModel
import nl.fullstackstudio.fsvoip.ui.TabPlaceholder

/** STUB: replaced by the calls slice (call recordings, administrators only). Keep the signature: the settings link to it. */
@Composable
fun RecordingsScreen(model: AppModel, accountId: String, back: () -> Unit) = TabPlaceholder(model, "Opnames")
