package app.feddy.sdk.core

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import org.json.JSONObject
import java.util.UUID

/**
 * Everything the SDK keeps on the device, in a preferences file of its own
 * so nothing collides with the host app's keys.
 *
 * The anonymous id is what ties this install to its conversations. iOS keeps
 * it in the Keychain, which survives a reinstall; Android has no equivalent,
 * but Auto Backup (on by default) restores this file, which is the best
 * available.
 */
internal class Prefs(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("app.feddy.sdk", Context.MODE_PRIVATE)

    fun anonId(): String {
        val existing = prefs.getString(ANON_ID, null)
        if (existing != null && existing.length in 8..128) return existing
        val fresh = UUID.randomUUID().toString().lowercase()
        prefs.edit { putString(ANON_ID, fresh) }
        return fresh
    }

    var emailKnown: Boolean
        get() = prefs.getBoolean(EMAIL_KNOWN, false)
        set(value) = prefs.edit { putBoolean(EMAIL_KNOWN, value) }

    /**
     * Skipping is a decision, not per-screen state: once set the SDK never
     * asks unprompted again; the thread's envelope button stays as the way
     * back in.
     */
    var emailAskDismissed: Boolean
        get() = prefs.getBoolean(EMAIL_ASK_DISMISSED, false)
        set(value) = prefs.edit { putBoolean(EMAIL_ASK_DISMISSED, value) }

    /** Conversation id to the seq its reply popup was shown at. */
    var popupShown: Map<String, Int>
        get() {
            val raw = prefs.getString(POPUP_SHOWN, null) ?: return emptyMap()
            return runCatching {
                val json = JSONObject(raw)
                json.keys().asSequence().associateWith { json.getInt(it) }
            }.getOrDefault(emptyMap())
        }
        set(value) = prefs.edit { putString(POPUP_SHOWN, JSONObject(value as Map<*, *>).toString()) }

    private companion object {
        const val ANON_ID = "anon_id"
        const val EMAIL_KNOWN = "email_known"
        const val EMAIL_ASK_DISMISSED = "email_ask_dismissed"
        const val POPUP_SHOWN = "reply_popup_shown"
    }
}
