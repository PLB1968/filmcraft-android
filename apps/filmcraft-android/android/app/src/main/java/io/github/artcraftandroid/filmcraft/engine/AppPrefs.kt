package io.github.artcraftandroid.filmcraft.engine

import android.content.Context

/** The app's own settings (not a project's): kept in shared preferences. */
class AppPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("filmcraft", Context.MODE_PRIVATE)

    /** Projects that were never switched open in Pro mode. */
    var proModeDefault: Boolean
        get() = prefs.getBoolean(PRO_MODE_DEFAULT, false)
        set(v) = prefs.edit().putBoolean(PRO_MODE_DEFAULT, v).apply()

    private companion object {
        const val PRO_MODE_DEFAULT = "proModeDefault"
    }
}
