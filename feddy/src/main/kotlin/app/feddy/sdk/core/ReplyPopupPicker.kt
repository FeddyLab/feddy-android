package app.feddy.sdk.core

/** What the reply popup shows: the newest reply the user has not seen. */
internal data class ReplyPopupPick(
    val conversationId: String,
    val authorName: String?,
    val message: String?,
    val sentAt: Long,
    /**
     * Every eligible thread, with the seq it was surfaced at. One card stands
     * for all of them, so all are recorded as shown together: otherwise a
     * backlog would drip out one card per launch.
     */
    val surfaced: Map<String, Int>,
)

/**
 * Chooses what, if anything, the reply popup shows. Pure so the rules are
 * testable: each reply pops at most once, only replies from the last 14 days
 * pop, and one card is shown at a time.
 */
internal object ReplyPopupPicker {
    /** Older replies keep only the badge: one surfacing months late reads as noise. */
    const val MAX_AGE_MS: Long = 14L * 24 * 60 * 60 * 1000

    fun pick(
        conversations: List<ConversationSummary>,
        shown: Map<String, Int>,
        now: Long = System.currentTimeMillis(),
    ): ReplyPopupPick? {
        val cutoff = now - MAX_AGE_MS
        // Closed threads count: a reply followed by a close is the common
        // case, and closing says nothing about whether it was read.
        val eligible = conversations.filter {
            it.hasUnread &&
                it.lastAuthorType != null &&
                it.lastAuthorType != "contact" &&
                it.lastMessageAt > cutoff
        }
        val newest = eligible
            .filter { (shown[it.id] ?: 0) < it.lastSeq }
            .maxByOrNull { it.lastMessageAt }
            ?: return null
        return ReplyPopupPick(
            conversationId = newest.id,
            authorName = newest.lastAuthorName,
            message = newest.lastMessage,
            sentAt = newest.lastMessageAt,
            surfaced = eligible.associate { it.id to it.lastSeq },
        )
    }

    /**
     * The shown record after a pick, trimmed to threads still in the list so
     * it cannot grow without bound.
     */
    fun record(
        pick: ReplyPopupPick?,
        shown: Map<String, Int>,
        listed: List<ConversationSummary>,
    ): Map<String, Int> {
        val ids = listed.mapTo(HashSet()) { it.id }
        val next = shown.filterKeys { it in ids }.toMutableMap()
        pick?.surfaced?.forEach { (id, seq) -> next[id] = maxOf(next[id] ?: 0, seq) }
        return next
    }
}
