// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.ui.settings

import android.content.Context
import java.io.File
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import nl.fullstackstudio.fsvoip.AppModel
import nl.fullstackstudio.fsvoip.core.AudioRecorderSession
import nl.fullstackstudio.fsvoip.core.AvailabilityHub
import nl.fullstackstudio.fsvoip.core.LiveSelfExtensionService
import nl.fullstackstudio.fsvoip.core.LiveSoundService
import nl.fullstackstudio.fsvoip.core.SelfExtensionHub
import nl.fullstackstudio.fsvoip.core.SoundsHooks
import nl.fullstackstudio.fsvoip.core.SoundsModel
import nl.fullstackstudio.fsvoip.ui.LocalAccess
import nl.fullstackstudio.fsvoip.ui.sounds.MediaRecorderBackend
import nl.fullstackstudio.fsvoip.ui.sounds.MediaSoundPlayer

/**
 * The long-lived state of this slice, one per process (`model.settingsHubs(context)`): the own extension, "Beschikbaar" and the
 * sounds per account. They load when `GET /me` says the account may, and let go of an account when it goes away.
 */
class SettingsHubs(private val model: AppModel, context: Context) {
    private val context = context.applicationContext
    private val scope = MainScope()
    private val service = LiveSelfExtensionService { model.client(it) }

    val selfExtension = SelfExtensionHub(service, service)
    val availability = AvailabilityHub(service)
    private val soundModels = mutableMapOf<String, SoundsModel>()
    private val denied = MutableStateFlow<Set<String>>(emptySet())

    /** Accounts whose sounds routes answered 403 since the last `GET /me` (the right is gone until `/me` says otherwise). */
    val soundsDenied: StateFlow<Set<String>> = denied.asStateFlow()

    init {
        selfExtension.onRevoked = { scope.launch { model.refreshAccounts() } }

        model.meListeners += { accountId, me ->
            denied.value = denied.value - accountId

            if (me.canEditOwnExtension) {
                scope.launch { availability.load(accountId) }
            } else {
                availability.forget(accountId)
                selfExtension.forget(accountId)
            }

            if (!model.canManageSounds(accountId)) dropSounds(accountId)
        }

        model.accountRemovedListeners += { accountId ->
            availability.forget(accountId)
            selfExtension.forget(accountId)
            dropSounds(accountId)
        }

        // What `GET /me` already said before this was created.
        for ((accountId, me) in model.meByAccount.value) {
            if (me.canEditOwnExtension) scope.launch { availability.load(accountId) }
        }

        // A call starts: what records stops and what plays pauses.
        scope.launch {
            model.phone.sessions.collect { sessions ->
                if (sessions.isNotEmpty()) soundModels.values.toList().forEach { it.callStarted() }
            }
        }
    }

    fun canManageSounds(accountId: String): Boolean = model.canManageSounds(accountId) && accountId !in denied.value

    /** The sounds of one account for "Geluiden" and the audio picker; null for anyone who may not manage sounds. */
    fun sounds(accountId: String): SoundsModel? {
        if (!canManageSounds(accountId)) {
            dropSounds(accountId)
            return null
        }

        soundModels[accountId]?.let { return it }

        val client = model.client(accountId) ?: return null
        val gate = LocalAccess.gate(context, model)
        val player = MediaSoundPlayer(scope)
        val recorder = AudioRecorderSession(
            MediaRecorderBackend(context, model),
            File(context.cacheDir, "FSVoipRecordings"),
            scope,
            isCallActive = { model.phone.sessions.value.isNotEmpty() },
        )
        val created = SoundsModel(
            accountId,
            LiveSoundService(client),
            player,
            recorder,
            scope,
            File(context.cacheDir, "sounds"),
            SoundsHooks(
                touchGate = { gate.touch() },
                isCallActive = { model.phone.sessions.value.isNotEmpty() },
                onAccessDenied = { denied.value = denied.value + accountId; dropSounds(accountId) },
                onRevoked = { scope.launch { model.refreshAccounts() } },
            ),
        )

        // The player reads the duration from the audio; the model keeps it per sound.
        scope.launch {
            player.state.collect { state ->
                val id = state.currentId
                val duration = state.durationMs

                if (id != null && duration != null) created.noteDuration(id, duration / 1000.0)
            }
        }

        soundModels[accountId] = created

        return created
    }

    private fun dropSounds(accountId: String) {
        soundModels.remove(accountId)?.stopAll()
    }

    init {
        // Leftovers of an earlier run (a crash while recording) are removed once per process, before the first recorder exists.
        AudioRecorderSession.purgeStaleFiles(File(context.cacheDir, "FSVoipRecordings"))
    }
}

/** The settings hubs of the process. */
fun AppModel.settingsHubs(context: Context): SettingsHubs = feature { SettingsHubs(this, context) }
