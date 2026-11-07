package app.feddy.sdk.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReplyPopupPickerTest {
    private val now = 1_800_000_000_000L
    private val day = 86_400_000L

    private fun conversation(
        id: String,
        lastSeq: Int = 4,
        seenSeq: Int = 2,
        daysAgo: Double = 1.0,
        author: String? = "teammate",
        status: String = "open",
    ) = ConversationSummary(
        id = id,
        subject = null,
        status = status,
        lastSeq = lastSeq,
        lastMessageAt = now - (daysAgo * day).toLong(),
        seenSeq = seenSeq,
        lastMessage = "Reply in $id",
        lastAuthorType = author,
        lastAuthorName = "Yu",
    )

    @Test
    fun picksNewestUnreadReplyAndSurfacesTheRest() {
        val list = listOf(
            conversation("older", daysAgo = 3.0),
            conversation("newest", daysAgo = 0.5),
            conversation("read", lastSeq = 4, seenSeq = 4, daysAgo = 0.1),
        )
        val pick = ReplyPopupPicker.pick(list, emptyMap(), now)
        assertEquals("newest", pick?.conversationId)
        assertEquals("Reply in newest", pick?.message)
        assertEquals(now - day / 2, pick?.sentAt)
        assertEquals(mapOf("older" to 4, "newest" to 4), pick?.surfaced)
    }

    @Test
    fun eachReplyPopsOnce() {
        assertNull(ReplyPopupPicker.pick(listOf(conversation("a", lastSeq = 4)), mapOf("a" to 4), now))
        // A later reply in the same thread is new again.
        assertEquals("a", ReplyPopupPicker.pick(listOf(conversation("a", lastSeq = 6)), mapOf("a" to 4), now)?.conversationId)
    }

    @Test
    fun onlyRepliesFromTheLastFourteenDays() {
        val list = listOf(conversation("stale", daysAgo = 14.5), conversation("recent", daysAgo = 13.5))
        val pick = ReplyPopupPicker.pick(list, emptyMap(), now)
        assertEquals("recent", pick?.conversationId)
        assertEquals(mapOf("recent" to 4), pick?.surfaced)
        assertNull(ReplyPopupPicker.pick(listOf(list[0]), emptyMap(), now))
    }

    @Test
    fun ignoresThreadsWhereTheUserSpokeLast() {
        val list = listOf(conversation("mine", author = "contact"), conversation("unknown", author = null))
        assertNull(ReplyPopupPicker.pick(list, emptyMap(), now))
    }

    @Test
    fun closedThreadsAndBotRepliesStillPop() {
        assertEquals("closed", ReplyPopupPicker.pick(listOf(conversation("closed", status = "closed")), emptyMap(), now)?.conversationId)
        assertEquals("bot", ReplyPopupPicker.pick(listOf(conversation("bot", author = "bot")), emptyMap(), now)?.conversationId)
    }

    @Test
    fun skipsThreadsAlreadyShown() {
        val list = listOf(conversation("seen-card", daysAgo = 2.0), conversation("fresh", daysAgo = 1.0))
        assertEquals("fresh", ReplyPopupPicker.pick(list, mapOf("seen-card" to 4), now)?.conversationId)
    }

    @Test
    fun recordMarksEverySurfacedThreadAndDropsUnlisted() {
        val list = listOf(conversation("a", lastSeq = 5), conversation("b", lastSeq = 3))
        val pick = ReplyPopupPicker.pick(list, emptyMap(), now)
        val next = ReplyPopupPicker.record(pick, mapOf("gone" to 9, "a" to 2), list)
        assertEquals(mapOf("a" to 5, "b" to 3), next)
        assertNull(ReplyPopupPicker.pick(list, next, now))
    }
}
