package app.screentotext

import android.content.Context
import android.content.SharedPreferences

/** Small settings store. Kept in the app's private storage on the phone. */
class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var useCloud: Boolean
        get() = sp.getBoolean("useCloud", false)
        set(v) = sp.edit().putBoolean("useCloud", v).apply()

    var apiKey: String
        get() = sp.getString("apiKey", "") ?: ""
        set(v) = sp.edit().putString("apiKey", v.trim()).apply()

    var model: String
        get() = sp.getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(v) = sp.edit().putString("model", v.trim().ifEmpty { DEFAULT_MODEL }).apply()

    /** Chosen webcam resolution as "type:width:height", or "" for automatic. */
    var cameraSize: String
        get() = sp.getString("cameraSize", "") ?: ""
        set(v) = sp.edit().putString("cameraSize", v).apply()

    companion object {
        const val DEFAULT_MODEL = "claude-sonnet-5-5"
    }
}
