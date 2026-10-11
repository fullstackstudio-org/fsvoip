// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip

import nl.fullstackstudio.fsvoip.core.NoticeDestination

/** The tab a tap on a portal notification opens (missed calls to Recent, voicemail, contacts; everything else the dialler). */
fun NoticeDestination.tab(): AppModel.Tab = when (this) {
    NoticeDestination.HOME -> AppModel.Tab.DIALER
    NoticeDestination.RECENTS -> AppModel.Tab.RECENTS
    NoticeDestination.VOICEMAIL -> AppModel.Tab.VOICEMAIL
    NoticeDestination.CONTACTS -> AppModel.Tab.CONTACTS
}
