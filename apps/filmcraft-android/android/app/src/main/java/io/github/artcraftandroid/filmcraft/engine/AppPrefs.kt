package io.github.artcraftandroid.filmcraft.engine

import android.content.Context

/** The app's own settings (not a project's): kept in shared preferences. */
class AppPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("filmcraft", Context.MODE_PRIVATE)

    /** Projects that were never switched open in Pro mode. */
    var proModeDefault: Boolean
        get() = prefs.getBoolean(PRO_MODE_DEFAULT, false)
        set(v) = prefs.edit().putBoolean(PRO_MODE_DEFAULT, v).apply()

    /** Make proxies of HD and 4K videos in the background (Settings › Proxies). */
    var autoProxies: Boolean
        get() = prefs.getBoolean(AUTO_PROXIES, true)
        set(v) = prefs.edit().putBoolean(AUTO_PROXIES, v).apply()

    /** The monitors play proxies where there are some (Toggle Proxies). */
    var useProxies: Boolean
        get() = prefs.getBoolean(USE_PROXIES, true)
        set(v) = prefs.edit().putBoolean(USE_PROXIES, v).apply()

    private companion object {
        const val PRO_MODE_DEFAULT = "proModeDefault"
        const val AUTO_PROXIES = "autoProxies"
        const val USE_PROXIES = "useProxies"
    }
}
