// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.ui.media

import android.content.Context
import java.io.File
import java.time.Instant
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import nl.fullstackstudio.fsvoip.AppModel
import nl.fullstackstudio.fsvoip.R
import nl.fullstackstudio.fsvoip.core.LiveMediaService
import nl.fullstackstudio.fsvoip.core.MediaCache
import nl.fullstackstudio.fsvoip.ui.RecentFormat

/** Where the app plugs in what this slice only describes. */
object MediaWiring {
    /**
     * The local access check in front of a colleague's voicemail and the recordings (screen lock). Until the local-access slice is
     * merged and plugged in here, everything is open (the server still decides what a pairing may hear).
     */
    var gateFactory: (AppModel) -> MediaAccessGate = { OpenAccessGate() }
}

/** The one media hub of the app: rights per account, the player, the download cache. Created on first use. */
fun AppModel.mediaHub(context: Context): MediaHub = feature {
    val app = context.applicationContext
    val scope = MainScope()
    val model = this
    val hub = MediaHub(
        service = LiveMediaService(clientFor = { id -> model.client(id) }),
        gate = MediaWiring.gateFactory(model),
        backend = MediaPlayerBackend(app),
        cache = MediaCache(File(app.cacheDir, "fsvoip-media")),
        scope = scope,
        callActive = { model.phone.sessions.value.isNotEmpty() },
    )

    meByAccount.value.forEach { (id, me) -> hub.apply(me, id) }
    meListeners += { id, me -> hub.apply(me, id) }
    accountRemovedListeners += { id -> hub.forget(id) }
    hub.onRevoked = { scope.launch { model.refreshAccounts() } }
    hub.nameLookup = { number -> model.name(number) }

    scope.launch { model.phone.sessions.collect { hub.callStateChanged() } }
    scope.launch {
        hub.lostAccessFor.collect { id ->
            if (id != null) {
                model.showNotice(app.getString(R.string.calls_media_notice_lostAccess, model.account(id)?.displayLabel ?: ""), isError = true)
                hub.acknowledgeLostAccess()
            }
        }
    }

    hub
}

/** The model behind "Recent" (the team history), kept for the life of the app. */
fun AppModel.historyModel(context: Context): HistoryModel = feature {
    val app = context.applicationContext
    val hub = mediaHub(app)
    val model = this
    val history = HistoryModel(
        media = hub,
        localWhen = { instant: Instant -> RecentFormat.`when`(instant, app.getString(R.string.recents_yesterday)) },
        anonymousTitle = app.getString(R.string.call_anonymous),
    )
    history.nameLookup = { number -> model.name(number) }
    accountRemovedListeners += { id -> history.forget(id) }

    history
}
