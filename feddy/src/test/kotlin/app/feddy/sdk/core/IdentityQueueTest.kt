package app.feddy.sdk.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.Collections

class IdentityQueueTest {
    /** Records every attempt; fails the first `failures` attempts with `error`. */
    private class Server(private var failures: Int = 0, private val error: Throwable = IOException("offline")) {
        val attempts: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val delivered: MutableList<String> = Collections.synchronizedList(mutableListOf())

        suspend fun receive(payload: IdentityPayload) {
            attempts.add(payload.externalId)
            // Give later submits a chance to arrive while this one is in flight.
            delay(1)
            synchronized(this) {
                if (failures > 0) {
                    failures -= 1
                    throw error
                }
            }
            delivered.add(payload.externalId)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun queue(server: Server) = IdentityQueue(scope) { server.receive(it) }

    private fun waitUntilIdle(queue: IdentityQueue) = runBlocking {
        repeat(2_000) {
            if (!queue.isSending) return@runBlocking
            delay(1)
        }
        fail("queue never went idle")
    }

    @Test
    fun callsMadeBackToBackLandInOrderAndLastWins() {
        val server = Server()
        val queue = queue(server)
        repeat(20) { queue.submit(IdentityPayload(externalId = "$it")) }
        waitUntilIdle(queue)
        val delivered = server.delivered.map { it.toInt() }
        assertEquals(19, delivered.last())
        assertEquals("an older call landed after a newer one", delivered.sorted(), delivered)
        assertTrue("calls made while one was in flight should collapse", delivered.size < 20)
    }

    @Test
    fun transientFailureIsResentOnFlush() {
        val server = Server(failures = 1)
        val queue = queue(server)
        queue.submit(IdentityPayload(externalId = "pro"))
        waitUntilIdle(queue)
        assertEquals(emptyList<String>(), server.delivered.toList())
        queue.flush()
        waitUntilIdle(queue)
        assertEquals(listOf("pro", "pro"), server.attempts.toList())
        assertEquals(listOf("pro"), server.delivered.toList())
    }

    @Test
    fun newerCallReplacesAFailedOne() {
        val server = Server(failures = 1)
        val queue = queue(server)
        queue.submit(IdentityPayload(externalId = "old"))
        waitUntilIdle(queue)
        queue.submit(IdentityPayload(externalId = "new"))
        waitUntilIdle(queue)
        queue.flush()
        waitUntilIdle(queue)
        assertEquals(listOf("new"), server.delivered.toList())
    }

    @Test
    fun clientErrorIsNotRetried() {
        val server = Server(failures = 1, error = ApiError(400, "bad external_id"))
        val queue = queue(server)
        queue.submit(IdentityPayload(externalId = "bad"))
        waitUntilIdle(queue)
        queue.flush()
        waitUntilIdle(queue)
        assertEquals(listOf("bad"), server.attempts.toList())
    }
}
