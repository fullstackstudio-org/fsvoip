// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.core

import org.junit.Assert.assertEquals
import org.junit.Test

class NoticeRouteTest {
    private fun route(href: String) = NoticeRoute.destination(href)

    @Test
    fun knownPortalPaths() {
        assertEquals(NoticeDestination.RECENTS, route("https://fullstackstudio.nl/portal/voip/11111111-2222-4333-8444-555555555555/calls"))
        assertEquals(NoticeDestination.VOICEMAIL, route("https://fullstackstudio.nl/portal/voip/abc/voicemail?box=1#x"))
        assertEquals(NoticeDestination.CONTACTS, route("/portal/contacts"))
        assertEquals(NoticeDestination.CONTACTS, route("https://fullstackstudio.nl/portal/contacts/duplicates"))
        // Case does not matter, a trailing slash neither.
        assertEquals(NoticeDestination.RECENTS, route("https://FullStackStudio.nl/Portal/VOIP/x/Calls/"))
    }

    @Test
    fun everythingElseIsHome() {
        assertEquals(NoticeDestination.HOME, route("https://fullstackstudio.nl/portal/content/p/tickets/1"))
        assertEquals(NoticeDestination.HOME, route("https://fullstackstudio.nl/portal/voip/abc"))
        assertEquals(NoticeDestination.HOME, route("https://fullstackstudio.nl/admin/customers"))
        assertEquals(NoticeDestination.HOME, route(""))
        assertEquals(NoticeDestination.HOME, route("javascript:alert(1)"))
        assertEquals(NoticeDestination.HOME, route("fsvoip://portal/contacts"))
        assertEquals(NoticeDestination.HOME, route("/portal/../portal/contacts"))
        assertEquals(NoticeDestination.HOME, route("/portal/voip/x%2Fy/calls"))
        assertEquals(NoticeDestination.HOME, route("/portal/contacts\u0000"))
        assertEquals(NoticeDestination.HOME, route("/" + "a".repeat(600)))
    }
}
