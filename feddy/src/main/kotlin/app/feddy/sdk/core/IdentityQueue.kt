package app.feddy.sdk.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** One `identify` call's arguments, captured at call time. */
internal data class IdentityPayload(
    val externalId: String,
    val email: String? = null,
    val name: String? = null,
    val avatarUrl: String? = null,
    val attributes: Map<String, Any?> = emptyMap(),
)

/**
 * Sends `identify` payloads one at a time, in call order.
 *
 * The server replaces the whole attribute set on every identify, so the last
 * request to arrive wins. Firing each call as its own request let two calls
 * made milliseconds apart land in either order, and the stale one could
 * overwrite the real one. Here only one request is in flight, and calls made
 * meanwhile collapse into the newest, which goes out next.
 *
 * `submit` records the payload synchronously under a lock, so call order is
 * fixed before any coroutine is scheduled.
 *
 * A payload that fails on a transient error (network, 429, 5xx) is kept and
 * resent on the next `flush()`, unless a newer call replaced it. Other client
 * errors are dropped: resending the same payload cannot fix them.
 */
internal class IdentityQueue(
    private val scope: CoroutineScope,
    private val send: suspend (IdentityPayload) -> Unit,
) {
    private val lock = Any()
    private var pending: IdentityPayload? = null
    private var sending = false

    val isSending: Boolean get() = synchronized(lock) { sending }

    fun submit(payload: IdentityPayload) {
        val start = synchronized(lock) {
            pending = payload
            claimSender()
        }
        if (start) scope.launch { drain() }
    }

    /** Starts sending whatever is pending. A no-op while a request is in flight. */
    fun flush() {
        val start = synchronized(lock) { claimSender() }
        if (start) scope.launch { drain() }
    }

    /** Caller holds the lock. */
    private fun claimSender(): Boolean {
        if (sending || pending == null) return false
        sending = true
        return true
    }

    private fun takeNext(): IdentityPayload? = synchronized(lock) {
        val payload = pending
        if (payload == null) {
            sending = false
        } else {
            pending = null
        }
        payload
    }

    /** True when the failed payload was kept and sending stopped. */
    private fun keepForRetry(payload: IdentityPayload, error: Throwable): Boolean = synchronized(lock) {
        // A newer call made while this one was in flight supersedes it.
        if (pending != null || !error.isRetryable()) return false
        pending = payload
        sending = false
        true
    }

    private suspend fun drain() {
        while (true) {
            val payload = takeNext() ?: return
            try {
                send(payload)
            } catch (error: kotlinx.coroutines.CancellationException) {
                // Put it back (unless superseded) and release the sender, so a
                // later flush can still send it.
                synchronized(lock) {
                    if (pending == null) pending = payload
                    sending = false
                }
                throw error
            } catch (error: Throwable) {
                if (keepForRetry(payload, error)) return
            }
        }
    }
}
