package app.feddy.sdk

import android.content.Context
import app.feddy.sdk.core.FeddyCore
import app.feddy.sdk.core.Foreground
import app.feddy.sdk.core.IdentityPayload
import app.feddy.sdk.ui.FeddyActivity
import app.feddy.sdk.ui.ReplyPopupCoordinator
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.CopyOnWriteArrayList

/** Receives the number of unread replies, on the main thread. */
public fun interface FeddyUnreadListener {
    public fun onUnreadCountChanged(count: Int)
}

/**
 * In-app support for your users: they write in, you reply from the
 * dashboard, the reply reaches them here.
 *
 * ```kotlin
 * // Application.onCreate
 * Feddy.configure(this, projectId = "fd_...")
 *
 * // A "Help" button
 * Feddy.present(context)
 * ```
 *
 * Every method is safe to call from any thread unless it says otherwise.
 * The support screens run in an activity of the SDK's own, so they work
 * from any host: Compose, Views, or a Flutter or React Native app.
 */
public object Feddy {
    /** The production API. Pass another `apiUrl` to `configure` only to test against your own. */
    public const val DEFAULT_API_URL: String = "https://core.feddy.app"

    private val listeners = CopyOnWriteArrayList<FeddyUnreadListener>()

    init {
        FeddyCore.onUnreadChanged = { previous, count ->
            listeners.forEach { it.onUnreadCountChanged(count) }
            // A reply arrived (or the first count after launch came in): the
            // popup decides whether there is anything new to show.
            if (count > previous) ReplyPopupCoordinator.scheduleCheck()
        }
    }

    /**
     * Call once, as early as you can — `Application.onCreate` is ideal.
     * Later calls are ignored.
     *
     * @param context any context; the SDK keeps only the application context.
     * @param projectId the project's ID, copied from the dashboard. It ships
     *   inside your app, so it is public by design — checking it into source
     *   control is fine.
     */
    @JvmStatic
    @JvmOverloads
    public fun configure(context: Context, projectId: String, apiUrl: String = DEFAULT_API_URL) {
        FeddyCore.runOnMain {
            if (FeddyCore.isConfigured) return@runOnMain
            Foreground.isOwnActivity = { it is FeddyActivity }
            FeddyCore.configure(context, projectId, apiUrl)
            ReplyPopupCoordinator.start()
        }
    }

    /**
     * Optionally binds the logged-in user. `avatarUrl` is an absolute
     * http(s) URL of a picture your app already has of them, shown in the
     * dashboard. Attribute values may be `String`, `Boolean`, numbers, or
     * `java.util.Date`; anything else is dropped.
     *
     * Calls take effect in the order you make them, and the last one wins:
     * each call replaces the whole attribute set. Calling again whenever a
     * value changes is fine; calls made while one is still sending collapse
     * into the newest.
     */
    @JvmStatic
    @JvmOverloads
    public fun identify(
        userId: String,
        email: String? = null,
        name: String? = null,
        avatarUrl: String? = null,
        attributes: Map<String, Any?> = emptyMap(),
    ) {
        val queue = FeddyCore.identityQueue ?: run {
            FeddyCore.warn("identify() called before configure()")
            return
        }
        if (email != null) FeddyCore.prefs.emailKnown = true
        queue.submit(IdentityPayload(userId, email, name, avatarUrl, attributes.toMap()))
    }

    /** Fetches the current unread count; the callback runs on the main thread. */
    @JvmStatic
    public fun unreadCount(callback: FeddyUnreadListener) {
        FeddyCore.unreadCount { callback.onUnreadCountChanged(it) }
    }

    /**
     * Re-pulls the unread count behind your badge. The SDK already does this
     * when the app comes to the foreground and every 30 seconds while it is
     * there; call it after anything that should show a fresh count at once.
     */
    @JvmStatic
    public fun refresh() {
        FeddyCore.runOnMain { FeddyCore.refresh() }
    }

    /**
     * Called on the main thread whenever the unread count changes. Drive
     * your own badge with it; remove the listener when its screen goes away.
     */
    @JvmStatic
    public fun addUnreadCountListener(listener: FeddyUnreadListener) {
        listeners.addIfAbsent(listener)
    }

    @JvmStatic
    public fun removeUnreadCountListener(listener: FeddyUnreadListener) {
        listeners.remove(listener)
    }

    /** The unread count as a flow, for Kotlin callers (`collectAsState()` in Compose). */
    @JvmStatic
    public val unreadCountFlow: StateFlow<Int>
        get() = FeddyCore.unread

    /** Opens the support screen: who you are writing to, a new-message button, recent threads. */
    @JvmStatic
    public fun present(context: Context) {
        FeddyCore.runOnMain { FeddyActivity.open(context, FeddyActivity.Start.Home) }
    }

    /** Opens the support screen straight into the new-message form. */
    @JvmStatic
    public fun presentNewConversation(context: Context) {
        FeddyCore.runOnMain { FeddyActivity.open(context, FeddyActivity.Start.Compose) }
    }

    /**
     * When the user comes back to your app with a reply they have not seen,
     * a card slides in at the top of the screen; tapping it opens the
     * conversation. Each reply shows at most once, and only replies from the
     * last 14 days. No notification permission is involved.
     *
     * On by default. Set it to `false` around screens that must not be
     * interrupted, such as a paywall or a game level, and back to `true`
     * afterwards; a waiting reply then shows. Main thread only.
     */
    @JvmStatic
    public var replyPopupsEnabled: Boolean
        get() = ReplyPopupCoordinator.enabled
        set(value) {
            ReplyPopupCoordinator.enabled = value
        }

    /**
     * For wrapper packages (Flutter, React Native) only: names the wrapper
     * so the dashboard can tell its messages apart. Call right after
     * [configure]. Apps calling the SDK directly never need this.
     */
    @JvmStatic
    public fun setWrapper(name: String, version: String) {
        FeddyCore.setWrapper(name, version)
    }
}
