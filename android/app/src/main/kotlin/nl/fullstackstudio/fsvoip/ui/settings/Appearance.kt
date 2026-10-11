// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.ui.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** "Weergave": light, dark or follow the system (the iOS `AppearancePreference`). Android follows the system unless the user chooses. */
enum class AppearancePreference(val key: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark"),
    ;

    /** True = dark; [systemDark] is what the system says. */
    fun resolveDark(systemDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemDark
        LIGHT -> false
        DARK -> true
    }

    companion object {
        val default = SYSTEM

        fun fromKey(key: String?): AppearancePreference = entries.firstOrNull { it.key == key } ?: default
    }
}

/** The chosen appearance, kept in a plain preference file (it is not a secret). The theme observes [current]. */
object Appearance {
    private const val FILE = "fsvoip.appearance"
    private const val KEY = "appearance"
    private val _current = MutableStateFlow(AppearancePreference.default)
    private var loaded = false

    val current: StateFlow<AppearancePreference> = _current.asStateFlow()

    /** Reads the saved choice once (call it before the first frame). */
    fun load(context: Context) {
        if (loaded) return
        loaded = true
        _current.value = AppearancePreference.fromKey(context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, null))
    }

    fun set(context: Context, preference: AppearancePreference) {
        _current.value = preference
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY, preference.key).apply()
    }
}

val AppearancePreference.titleRes: Int
    get() = when (this) {
        AppearancePreference.SYSTEM -> nl.fullstackstudio.fsvoip.R.string.snd_appearance_system
        AppearancePreference.LIGHT -> nl.fullstackstudio.fsvoip.R.string.snd_appearance_light
        AppearancePreference.DARK -> nl.fullstackstudio.fsvoip.R.string.snd_appearance_dark
    }
