// SPDX-License-Identifier: AGPL-3.0-or-later
//
// The pretend API of the call experience: parking (`/calls/park`, `/parked`), the numbers to call out with (`GET /me/extension`) and the
// customer card (`/contacts/lookup`, `/contacts/{id}/timeline`). Stateful, like the real server: a call you park appears in the list
// and our leg of it ends; a parked call you hang up is gone.

package nl.fullstackstudio.fsvoip.demo

import android.os.Handler
import android.os.Looper
import java.time.Instant
import nl.fullstackstudio.fsvoip.core.HttpResponse

class CallExpDemoHandler : DemoHandler {
    enum class Mode { NORMAL, EMPTY, UNAVAILABLE }

    private data class Parked(val id: String, val slot: Int, val callerNumber: String?, val callerName: String?, val parkedAt: Instant, val expiresAt: Instant, val parkedByAccount: String?, val parkerName: String)

    private val main = Handler(Looper.getMainLooper())
    private val parked = mutableMapOf<String, MutableList<Parked>>()
    private var nextId = 1

    override fun handle(request: DemoRequest): HttpResponse? {
        val account = request.headers["Authorization"]?.removePrefix("Bearer ")?.trim()?.takeIf { id -> DemoMode.exampleAccounts.any { it.id == id } }

        return when {
            account == null -> null
            request.matches("POST", "calls", "park") -> park(account, request.bodyString("callId"))
            request.matches("GET", "parked") -> list(account)
            request.matches("DELETE", "parked", "*") -> hangUp(account, request.segments[1])
            request.matches("GET", "me", "extension") -> demoJson(200, extension(account))
            request.matches("GET", "contacts", "lookup") -> lookup(request.query["number"].orEmpty())
            request.matches("GET", "contacts", "*", "timeline") && request.segments[1] == SMIT_ID -> timeline(request.query["cursor"], request.query["limit"]?.toIntOrNull() ?: 30)
            else -> null
        }
    }

    // MARK: Parking

    private fun entries(account: String): MutableList<Parked> {
        val now = Instant.now()

        return parked.getOrPut(account) {
            if (mode == Mode.EMPTY) {
                mutableListOf()
            } else if (account == DemoMode.ADMIN_ID) {
                mutableListOf(
                    Parked(newId(), 1, "0701234567", "Bakkerij Smit", now.minusSeconds(83), now.plusSeconds(217), account, "Jan (balie)"),
                    Parked(newId(), 2, null, null, now.minusSeconds(31), now.plusSeconds(269), null, "Receptie"),
                    Parked(newId(), 3, "0612345678", null, now.minusSeconds(250), now.plusSeconds(50), null, "Pieter Jansen"),
                )
            } else {
                mutableListOf(
                    Parked(newId(), 1, "0201234567", "Gemeente Voorbeeld", now.minusSeconds(120), now.plusSeconds(180), null, "Receptie"),
                    Parked(newId(), 2, "0881234567", "Installatie Noord", now.minusSeconds(45), now.plusSeconds(255), account, "Werkplaats"),
                )
            }
        }.also { list -> list.removeAll { it.expiresAt.isBefore(now) } }
    }

    private fun newId() = "ZGVtby1wYXJrZWQtY2FsbC0${(nextId++).toString().padStart(3, '0')}"

    private fun json(call: Parked, account: String): String {
        val by = """{"deviceId":${if (call.parkedByAccount != null) jsonString(call.parkedByAccount) else "null"},"name":${jsonString(call.parkerName)}}"""

        return """{"id":${jsonString(call.id)},"slot":${call.slot},"retrieveNumber":"*59${call.slot.toString().padStart(2, '0')}","callerNumber":${call.callerNumber?.let(::jsonString) ?: "null"},"callerName":${call.callerName?.let(::jsonString) ?: "null"},"parkedAt":"${call.parkedAt}","expiresAt":"${call.expiresAt}","parkedBy":$by,"mine":${call.parkedByAccount == account}}"""
    }

    private fun list(account: String): HttpResponse = when (mode) {
        Mode.UNAVAILABLE -> demoJson(200, """{"available":false,"calls":[]}""")
        else -> demoJson(200, """{"available":true,"calls":[${entries(account).joinToString(",") { json(it, account) }}]}""")
    }

    private fun park(account: String, callId: String?): HttpResponse {
        val engine = engine

        if (mode == Mode.UNAVAILABLE) return demoError(409, "park_unavailable")
        if (callId == null || engine == null || !engine.hasSipCallId(callId)) return demoError(404, "call_not_found")

        val list = entries(account)
        val slot = (1..99).firstOrNull { free -> list.none { it.slot == free } } ?: return demoError(409, "no_free_slot")
        val now = Instant.now()
        val name = DemoMode.exampleAccounts.first { it.id == account }.extensionName
        val call = Parked(newId(), slot, "0612345678", null, now, now.plusSeconds(120), account, name)
        list += call

        // The PBX ends our leg of the call once it is parked.
        main.postDelayed({ engine.endBecauseParked(callId) }, 600)

        return demoJson(201, json(call, account))
    }

    private fun hangUp(account: String, id: String): HttpResponse {
        val list = entries(account)
        val call = list.firstOrNull { it.id == id } ?: return demoError(404, "not_found")

        // A `user` may only hang up a call that is theirs.
        if (account != DemoMode.ADMIN_ID && call.parkedByAccount != account) return demoError(403, "forbidden", ""","required":"admin"""")

        list.remove(call)

        return demoOk()
    }

    // MARK: Calling out via

    private fun extension(account: String): String {
        val numbers = if (account == DemoMode.ADMIN_ID) {
            """[{"id":"d4e5f6a7-b8c9-4d0e-a1f2-b3c4d5e6f7a8","number":"0850607848","name":"Hoofdnummer","isDefault":true},{"id":"e5f6a7b8-c9d0-4e1f-b2a3-c4d5e6f7a8b9","number":"0850607849","name":"Service","isDefault":false},{"id":"f6a7b8c9-d0e1-4f2a-83b4-d5e6f7a8b9c0","number":"0201234567","name":"Kantoor Amsterdam","isDefault":false}]"""
        } else {
            """[{"id":"d4e5f6a7-b8c9-4d0e-a1f2-b3c4d5e6f7a8","number":"0850607848","name":"Hoofdnummer","isDefault":true}]"""
        }
        val me = DemoMode.exampleAccounts.first { it.id == account }

        return """{"id":"7a1d9c3e-5b2f-4e8a-9c01-6d3e8f2a4b57","name":${jsonString(me.extensionName)},"extension":${jsonString(me.extensionNumber ?: "")},"dnd":false,"forwardAlways":null,"noAnswerSeconds":25,"noAnswerTarget":null,"voicemailEnabled":true,"voicemailToEmail":false,"email":null,"sync":"ok","version":1,"numbers":$numbers,"defaultNumber":"0850607848","targets":[]}"""
    }

    // MARK: Customer card

    private fun lookup(number: String): HttpResponse {
        val digits = number.filter { it.isDigit() }

        // Bakkerij Smit, written any way: 0701234567, +31701234567, 0031701234567.
        if (digits.endsWith("701234567")) {
            return demoJson(200, """{"contact":{"id":"$SMIT_ID","name":"Bakkerij Smit","firstName":null,"lastName":null,"company":"Bakkerij Smit","email":"info@bakkerijsmit.nl","phones":[{"number":"+31701234567","label":"work","isPrimary":true}],"tags":["klant"],"updatedAt":"2026-10-07T09:30:12.345Z"},"ambiguous":false,"doNotContact":false,"counters":{"openRequests":1,"openOrders":2},"timeline":[${TIMELINE.take(3).joinToString(",")}]}""")
        }

        return demoJson(200, """{"contact":null,"ambiguous":false,"doNotContact":false,"counters":{"openRequests":0,"openOrders":0},"timeline":[]}""")
    }

    private fun timeline(cursor: String?, limit: Int): HttpResponse {
        val start = cursor?.removePrefix("c")?.toIntOrNull() ?: 0
        val page = TIMELINE.drop(start).take(limit.coerceIn(1, 100))
        val next = if (start + page.size < TIMELINE.size) "\"c${start + page.size}\"" else "null"

        return demoJson(200, """{"items":[${page.joinToString(",")}],"nextCursor":$next}""")
    }

    companion object {
        const val SMIT_ID = "6f7a8b9c-0d1e-4f2a-8b3c-4d5e6f7a8b9c"

        /** What the "In de wacht" list shows (`onhold-empty` and `onhold-unavailable` set it before the tab opens). */
        @Volatile
        var mode = Mode.NORMAL

        /** Set by [DemoSipEngine]: the demo API checks a Call-ID against its running calls like the PBX does. */
        @Volatile
        var engine: DemoSipEngine? = null

        private val TIMELINE = listOf(
            """{"id":"e1","kind":"order","title":"Bestelling geplaatst","summary":"3 producten, € 84,50","occurredAt":"2026-10-09T14:02:11.000Z","ref":"W-1042"}""",
            """{"id":"e2","kind":"ticket","title":"Verzoek ontvangen","summary":"Vraag over de levertijd","occurredAt":"2026-10-08T09:15:00.000Z","ref":"#12"}""",
            """{"id":"e3","kind":"call","title":"Gesprek","summary":null,"occurredAt":"2026-10-05T11:40:00.000Z","ref":null}""",
            """{"id":"e4","kind":"order","title":"Bestelling verzonden","summary":"Pakket onderweg","occurredAt":"2026-10-02T08:30:00.000Z","ref":"W-1031"}""",
            """{"id":"e5","kind":"appointment","title":"Afspraak ingepland","summary":"Opmeten ter plaatse","occurredAt":"2026-09-28T13:00:00.000Z","ref":null}""",
        )
    }
}
