// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.ui.pbx

import androidx.compose.runtime.Composable
import nl.fullstackstudio.fsvoip.AppModel
import nl.fullstackstudio.fsvoip.ui.TabPlaceholder

/** The parts of the "Centrale" section an administrator can open (one page each). */
enum class PbxPart { OVERVIEW, DEVICES, RING_GROUPS, HOURS, NUMBERS }

/** STUB: replaced by the pbx slice. Keep the signature: the settings link to it. */
@Composable
fun PbxHubScreen(model: AppModel, accountId: String, part: PbxPart, back: () -> Unit) = TabPlaceholder(model, part.name)
