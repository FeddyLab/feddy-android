package app.feddy.sdk.core

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap

/**
 * Internal singleton behind the public `Feddy` facade: configuration, the
 * API client, the cached config, unread state, and which of the host's
 * activities is on screen.
 */
internal object FeddyCore {
    private const val TAG = "Feddy"

    /** Spec 5.1: the badge is fed by a foreground poll as well as by explicit refreshes. */
    private const val POLL_INTERVAL_MS = 30_000L

    /** Main-thread scope for the SDK's whole lifetime; network work hops to IO inside ApiClient. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile var client: ApiClient? = null
        private set
    @Volatile var identityQueue: IdentityQueue? = null
        private set
    lateinit var prefs: Prefs
        private set
    lateinit var device: DeviceContext
        private set

    private val configState = MutableStateFlow<FeddyConfig?>(null)
    val config: StateFlow<FeddyConfig?> = configState.asStateFlow()

    private val unreadState = MutableStateFlow(0)
    val unread: StateFlow<Int> = unreadState.asStateFlow()

    /** Called on the main thread whenever the unread count changes. */
    var onUnreadChanged: ((previous: Int, count: Int) -> Unit)? = null

    /** Called when the app comes to the foreground (and at configure if it already is). */
    var onForeground: (() -> Unit)? = null

    private var refreshJob: Job? = null
    private var pollJob: Job? = null
    private var configJob: Job? = null

    val isConfigured: Boolean get() = client != null

    // MARK: - Lifecycle

    fun configure(context: Context, projectId: String, apiUrl: String) {
        if (client != null) return
        val app = context.applicationContext as Application
        prefs = Prefs(app)
        device = DeviceContext(app)
        val api = ApiClient(projectId, apiUrl, prefs.anonId(), device)
        client = api
        identityQueue = IdentityQueue(scope) { payload ->
            api.identify(payload.externalId, payload.email, payload.name, payload.avatarUrl, payload.attributes)
        }
        Foreground.install(app, context)
        scope.launch { loadConfig() }
        refresh()
        if (Foreground.isForeground) startPolling()
    }

    fun setWrapper(name: String, version: String) {
        if (!::device.isInitialized) return
        device.wrapper = name to version
    }

    internal fun appCameToForeground() {
        refresh()
        startPolling()
        onForeground?.invoke()
    }

    internal fun appWentToBackground() {
        pollJob?.cancel()
        pollJob = null
    }

    private fun startPolling() {
        if (client == null) return
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                refresh()
            }
        }
    }

    /** Loads the config once; a failed fetch is retried by the next caller. */
    suspend fun loadConfig() {
        val api = client ?: return
        if (configState.value != null) return
        configJob?.let { if (it.isActive) { it.join(); return } }
        val job = scope.launch {
            runCatching { api.config() }.onSuccess { configState.value = it }
        }
        configJob = job
        job.join()
    }

    // MARK: - Unread

    /**
     * Pulls the unread count behind the badge. Also resends an identify that
     * failed earlier, so the foreground poll doubles as its retry.
     */
    fun refresh() {
        val api = client ?: return
        identityQueue?.flush()
        refreshJob?.cancel()
        refreshJob = scope.launch {
            runCatching { api.unreadCount() }.onSuccess { setUnread(it) }
        }
    }

    fun unreadCount(callback: (Int) -> Unit) {
        val api = client ?: run {
            mainHandler.post { callback(0) }
            return
        }
        scope.launch {
            val count = runCatching { api.unreadCount() }.getOrNull()
            if (count != null) setUnread(count)
            callback(count ?: unreadState.value)
        }
    }

    fun setUnread(count: Int) {
        val previous = unreadState.value
        if (previous == count) return
        unreadState.value = count
        runOnMain { onUnreadChanged?.invoke(previous, count) }
    }

    fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    fun warn(message: String) {
        Log.w(TAG, message)
    }

    // MARK: - Email capture state

    /** Server-side gate on every email ask. True while the config has not arrived. */
    val emailCaptureEnabled: Boolean get() = configState.value?.emailCaptureEnabled ?: true
    val brandingEnabled: Boolean get() = configState.value?.brandingEnabled ?: true
    val attachmentsEnabled: Boolean get() = configState.value?.attachmentsEnabled ?: true
}

/**
 * Which of the host's activities is in front, and whether the app is in the
 * foreground at all. Tracked from the Application so the host never has to
 * hand anything over — a Flutter app has exactly one plain Activity, and a
 * wrapper plugin should not have to forward its lifecycle.
 */
internal object Foreground : Application.ActivityLifecycleCallbacks {
    private var installed = false
    /** A set, not a counter: seeding at configure must not double-count a later onStart. */
    private val started: MutableSet<Activity> = Collections.newSetFromMap(WeakHashMap())
    private var resumed: WeakReference<Activity>? = null

    /** Activities that must not be counted as the host's (the SDK's own). */
    var isOwnActivity: (Activity) -> Boolean = { false }

    /** Called when the resumed activity changes (null when none is resumed). */
    var onResumedChanged: ((Activity?) -> Unit)? = null

    val isForeground: Boolean get() = started.isNotEmpty()
    val resumedActivity: Activity? get() = resumed?.get()

    /**
     * `hint` is whatever context `configure` was given. Configuring late —
     * a Flutter plugin does, once Dart is running — means the activity's
     * start and resume have already happened, so they are seeded here.
     */
    fun install(app: Application, hint: Context) {
        if (installed) return
        installed = true
        app.registerActivityLifecycleCallbacks(this)
        val activity = hint as? Activity ?: return
        if (activity.isFinishing) return
        val state = (activity as? LifecycleOwner)?.lifecycle?.currentState
        if (state != null) {
            if (state.isAtLeast(Lifecycle.State.STARTED)) started.add(activity)
            if (state.isAtLeast(Lifecycle.State.RESUMED)) resumed = WeakReference(activity)
        } else {
            noteActivity(activity)
        }
    }

    /** Records an activity the host handed us, if nothing better is known. */
    fun noteActivity(context: Context) {
        val activity = context as? Activity ?: return
        if (activity.isFinishing || isOwnActivity(activity)) return
        if (resumed?.get() == null) resumed = WeakReference(activity)
    }

    override fun onActivityStarted(activity: Activity) {
        val wasForeground = isForeground
        started.add(activity)
        if (!wasForeground) FeddyCore.appCameToForeground()
    }

    override fun onActivityStopped(activity: Activity) {
        started.remove(activity)
        if (!isForeground) FeddyCore.appWentToBackground()
    }

    override fun onActivityResumed(activity: Activity) {
        resumed = WeakReference(activity)
        onResumedChanged?.invoke(activity)
    }

    override fun onActivityPaused(activity: Activity) {
        if (resumed?.get() === activity) {
            resumed = null
            onResumedChanged?.invoke(null)
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) {
        started.remove(activity)
    }
}
