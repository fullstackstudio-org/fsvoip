// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.demo

import android.os.Handler
import android.os.Looper
import java.time.Instant
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import nl.fullstackstudio.fsvoip.AppModel
import nl.fullstackstudio.fsvoip.core.HttpResponse
import nl.fullstackstudio.fsvoip.core.RecentCall
import nl.fullstackstudio.fsvoip.core.Secret
import nl.fullstackstudio.fsvoip.core.ServerTransport
import nl.fullstackstudio.fsvoip.core.SipCredentials
import nl.fullstackstudio.fsvoip.core.StoredAccount
import nl.fullstackstudio.fsvoip.ui.contacts.openContactsDemoScreen

/**
 * The demo content: two example accounts (the first an administrator, the second a plain user), example recents and the
 * core of the pretend API. Feature slices add their own [DemoHandler] to [handlers] and their screens to [openScreen].
 */
object DemoMode {
    const val ADMIN_ID = "demo-1"
    const val USER_ID = "demo-2"

    val exampleAccounts: List<StoredAccount> = listOf(
        account(ADMIN_ID, "JEST Bouw · Jan de Vries", "Jan (balie)", "JEST Bouw", "Jan de Vries", "102", "JEST Bouw B.V.", "jest-bouw.powervoip.nl", ServerTransport.TLS, 0),
        account(USER_ID, "Voorbeeld Installatie · Werkplaats", null, "Voorbeeld Installatie", "Werkplaats", "201", "Voorbeeld Installatie", "voorbeeld.powervoip.nl", ServerTransport.TCP, 60),
    )

    /** In order; the first handler that answers wins. Slices append their own (one line each). */
    val handlers: MutableList<DemoHandler> = mutableListOf(
        CoreDemoHandler(),
        // slice handlers are added here
        ContactsDemoHandler(),
    )

    fun exampleRecents(): List<RecentCall> {
        val now = System.currentTimeMillis()
        val day = 86_400_000L

        return listOf(
            RecentCall("0612345678".hashCode().toString() + "a", "0612345678", null, USER_ID, exampleAccounts[1].displayLabel, RecentCall.Direction.OUTGOING, RecentCall.Outcome.NOT_ANSWERED, now - 2 * day, 0),
            RecentCall("b", "103", "Werkplaats", ADMIN_ID, exampleAccounts[0].displayLabel, RecentCall.Direction.OUTGOING, RecentCall.Outcome.ANSWERED, now - day - 3_000_000, 74),
            RecentCall("c", "0201234567", null, ADMIN_ID, exampleAccounts[0].displayLabel, RecentCall.Direction.INCOMING, RecentCall.Outcome.MISSED, now - 9_000_000, 0),
            RecentCall("d", "0701234567", "Bakkerij Smit", ADMIN_ID, exampleAccounts[0].displayLabel, RecentCall.Direction.INCOMING, RecentCall.Outcome.ANSWERED, now - 2_400_000, 312),
        )
    }

    /** Opens the screen the config asks for (`demo-screen`). Slices add their cases. */
    fun openScreen(screen: String?, model: AppModel, engine: DemoSipEngine) {
        val main = Handler(Looper.getMainLooper())
        val link = "https://fullstackstudio.nl/fsvoip/pair?t=fss_vpair_DEMOdemoDEMOdemoDEMOdemoDEMOdemoDEMOdemoDEM"

        when (screen) {
            "recents" -> model.select(AppModel.Tab.RECENTS)
            "contacts" -> model.select(AppModel.Tab.CONTACTS)
            "contactdetail", "contactedit", "contactnew", "contactsources", "contactphone" -> openContactsDemoScreen(model, screen.removePrefix("contact"))
            "onhold" -> model.select(AppModel.Tab.ON_HOLD)
            "voicemail" -> model.select(AppModel.Tab.VOICEMAIL)
            "settings" -> model.openSettings()
            "scanner" -> model.presentScanner()
            "pairing" -> model.handleIncoming(link)
            "incall", "parkcall" -> main.postDelayed({ model.call("0612345678", ADMIN_ID) }, 1200)
            "incoming" -> main.postDelayed({ engine.simulateIncoming("0701234567", "Bakkerij Smit", USER_ID) }, 1200)
            else -> Unit
        }
    }

    private fun account(
        id: String,
        label: String,
        alias: String?,
        pbx: String,
        extensionName: String,
        number: String,
        customer: String,
        domain: String,
        transport: ServerTransport,
        offset: Long,
    ) = StoredAccount(
        id = id,
        label = alias ?: label,
        labelOverride = alias,
        pbxName = pbx,
        extensionName = extensionName,
        extensionNumber = number,
        customerName = customer,
        deviceToken = Secret(id),
        installId = "d3m0d3m0d3m0d3m0",
        sip = SipCredentials(number, Secret("demo"), domain, "sip.powervoip.nl", if (transport == ServerTransport.TLS) 5061 else 5060, transport, true),
        pairedAtMillis = 1_791_400_000_000L + offset * 1000,
    )
}

/** `GET/PATCH /me`, `PUT /push-token`, `POST /unpair`: what the app needs to keep an account alive. */
class CoreDemoHandler : DemoHandler {
    private val aliases = mutableMapOf<String, String?>()

    override fun handle(request: DemoRequest): HttpResponse? {
        val token = request.headers["Authorization"]?.removePrefix("Bearer ")?.trim()
        val account = DemoMode.exampleAccounts.firstOrNull { it.deviceToken.reveal() == token }

        return when {
            request.matches("GET", "me") && account != null -> demoJson(200, me(account))
            request.matches("PATCH", "me") && account != null -> {
                val alias = request.bodyObject()["labelOverride"]?.let { it as? kotlinx.serialization.json.JsonPrimitive }?.contentOrNull
                aliases[account.id] = alias
                demoJson(200, """{"label":${jsonString(alias ?: account.label)}${if (alias != null) ""","labelOverride":${jsonString(alias)}""" else ""}}""")
            }
            request.matches("PUT", "push-token") || request.matches("POST", "unpair") -> demoOk()
            else -> null
        }
    }

    private fun me(account: StoredAccount): String {
        val admin = account.id == DemoMode.ADMIN_ID
        val alias = if (aliases.containsKey(account.id)) aliases[account.id] else account.labelOverride
        val role = if (admin) "admin" else "user"
        val capabilities = if (admin) {
            """{"pbxManage":true,"recordings":true,"voicemail":"all","calls":"all","contacts":{"read":true,"write":true,"delete":true},"selfExtension":true,"sounds":"manage","invite":true,"park":true,"callerChoice":true}"""
        } else {
            """{"pbxManage":false,"recordings":false,"voicemail":"own","calls":"team","contacts":{"read":true,"write":true,"delete":false},"selfExtension":true,"sounds":"none","invite":false,"park":true,"callerChoice":false}"""
        }

        return """
            {"account":{"id":${jsonString(account.id)},"label":${jsonString(alias ?: account.label)},${if (alias != null) """"labelOverride":${jsonString(alias)},""" else ""}"pbxName":${jsonString(account.pbxName)},"extensionName":${jsonString(account.extensionName)},"extensionNumber":${jsonString(account.extensionNumber ?: "")},"customerName":${jsonString(account.customerName)}},
            "sip":{"domain":${jsonString(account.sip.domain)},"proxy":"sip.powervoip.nl","port":${account.sip.port},"transport":"${account.sip.transport.name.lowercase()}","srv":true},
            "internal":[{"number":"100","name":"Receptie"},{"number":"101","name":"Pieter Jansen"},{"number":"103","name":"Werkplaats"}],
            "contacts":{"internal":true,"listsAvailable":2,"total":148},
            "push":{"registered":true,"kind":"fcm","env":"production","invalid":false},
            "serverTime":"${Instant.now()}",
            "role":"$role","capabilities":$capabilities,
            "pbx":{"name":${jsonString(account.pbxName)},"state":"active","readOnly":false}}
        """.trimIndent()
    }
}
