package io.github.artcraftandroid.rushcraft.engine

import android.content.Context

/** The app's own settings (not a project's): kept in shared preferences. */
class AppPrefs(context: Context) {
    // still named for FilmCraft, whose settings RushCraft keeps
    private val prefs = context.getSharedPreferences("filmcraft", Context.MODE_PRIVATE)

    /** Make proxies of HD and 4K videos in the background (Settings › Proxies). */
    var autoProxies: Boolean
        get() = prefs.getBoolean(AUTO_PROXIES, true)
        set(v) = prefs.edit().putBoolean(AUTO_PROXIES, v).apply()

    /** The monitors play proxies where there are some (Toggle Proxies). */
    var useProxies: Boolean
        get() = prefs.getBoolean(USE_PROXIES, true)
        set(v) = prefs.edit().putBoolean(USE_PROXIES, v).apply()

    /** The phone's own video decoders play H.264 and HEVC (Settings › Hardware decoding). */
    var hardwareDecoding: Boolean
        get() = prefs.getBoolean(HARDWARE_DECODING, true)
        set(v) = prefs.edit().putBoolean(HARDWARE_DECODING, v).apply()

    /** Render effects in the background after edits (Settings › Render effects). */
    var autoRender: Boolean
        get() = prefs.getBoolean(AUTO_RENDER, true)
        set(v) = prefs.edit().putBoolean(AUTO_RENDER, v).apply()

    /** Photos put on the timeline get a gentle Ken Burns (Settings › Ken Burns on photos). */
    var autoKenBurns: Boolean
        get() = prefs.getBoolean(AUTO_KEN_BURNS, true)
        set(v) = prefs.edit().putBoolean(AUTO_KEN_BURNS, v).apply()

    /** Edit by Text stays open under the timeline (closed with its X). */
    var textDock: Boolean
        get() = prefs.getBoolean(TEXT_DOCK, false)
        set(v) = prefs.edit().putBoolean(TEXT_DOCK, v).apply()

    /** A tool bar's buttons in order (Customise Toolbars), or null for the built-in set. */
    fun toolbar(name: String): List<String>? = prefs.getString(TOOLBAR + name, null)?.split(',')?.filter { it.isNotEmpty() }

    fun setToolbar(name: String, ids: List<String>?) {
        prefs.edit().apply { if (ids == null) remove(TOOLBAR + name) else putString(TOOLBAR + name, ids.joinToString(",")) }.apply()
    }

    private companion object {
        const val AUTO_PROXIES = "autoProxies"
        const val USE_PROXIES = "useProxies"
        const val HARDWARE_DECODING = "hardwareDecoding"
        const val AUTO_RENDER = "autoRender"
        const val TOOLBAR = "toolbar."
        const val TEXT_DOCK = "textDock"
        const val AUTO_KEN_BURNS = "autoKenBurns"
    }
}
