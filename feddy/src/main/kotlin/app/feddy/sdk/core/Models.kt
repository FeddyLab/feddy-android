package app.feddy.sdk.core

import org.json.JSONArray
import org.json.JSONObject

internal data class FeddyConfig(
    val brand: Brand,
    val replySlaText: String?,
    val categories: List<Category>,
    /** Whether the project can email replies. Absent (an older server) means yes. */
    val emailCapture: Boolean?,
    /** Whether "Powered by Feddy" is shown. Absent means shown. */
    val branding: Boolean?,
    /** Whether images can be attached. Absent means yes; the server refuses otherwise. */
    val attachments: Boolean?,
) {
    data class Brand(val name: String, val color: String?, val logoUrl: String?)

    data class Category(val code: String, val label: String)

    val emailCaptureEnabled: Boolean get() = emailCapture ?: true
    val brandingEnabled: Boolean get() = branding ?: true
    val attachmentsEnabled: Boolean get() = attachments ?: true

    companion object {
        /**
         * Mirrors the topics every project is seeded with. A topic is
         * required, so the form needs something to offer even when the
         * config fetch failed.
         */
        val fallbackCategories: List<Category> = listOf(
            Category("bug", "Bug"),
            Category("feature", "Feature"),
            Category("question", "Question"),
            Category("billing", "Billing"),
            Category("other", "Other"),
        )

        fun parse(json: JSONObject): FeddyConfig {
            val brand = json.getJSONObject("brand")
            return FeddyConfig(
                brand = Brand(
                    name = brand.getString("name"),
                    color = brand.optStringOrNull("color"),
                    logoUrl = brand.optStringOrNull("logo_url"),
                ),
                replySlaText = json.optStringOrNull("reply_sla_text"),
                categories = json.optJSONArray("categories").mapObjects {
                    Category(it.getString("code"), it.getString("label"))
                },
                emailCapture = json.optBooleanOrNull("email_capture"),
                branding = json.optBooleanOrNull("branding"),
                attachments = json.optBooleanOrNull("attachments"),
            )
        }
    }
}

internal data class ConversationSummary(
    val id: String,
    val subject: String?,
    val status: String,
    val lastSeq: Int,
    /** Epoch milliseconds. */
    val lastMessageAt: Long,
    val seenSeq: Int,
    /** The latest thing said and who said it. Absent on an older server. */
    val lastMessage: String?,
    val lastAuthorType: String?,
    val lastAuthorName: String?,
) {
    val hasUnread: Boolean get() = lastSeq > seenSeq

    companion object {
        fun parse(json: JSONObject) = ConversationSummary(
            id = json.getString("id"),
            subject = json.optStringOrNull("subject"),
            status = json.getString("status"),
            lastSeq = json.getInt("last_seq"),
            lastMessageAt = IsoDates.parse(json.getString("last_message_at")),
            seenSeq = json.getInt("seen_seq"),
            lastMessage = json.optStringOrNull("last_message"),
            lastAuthorType = json.optStringOrNull("last_author_type"),
            lastAuthorName = json.optStringOrNull("last_author_name"),
        )

        fun parseList(json: JSONObject): List<ConversationSummary> =
            json.getJSONArray("conversations").mapObjects(::parse)
    }
}

/**
 * An image on a message. `url` is an API path rather than a picture:
 * fetching it proves this contact owns the attachment and returns a URL
 * that is good for a minute.
 */
internal data class Attachment(
    val id: String,
    val filename: String,
    val mimeType: String,
    val sizeBytes: Int,
    val url: String,
) {
    companion object {
        fun parse(json: JSONObject) = Attachment(
            id = json.getString("id"),
            filename = json.getString("filename"),
            mimeType = json.getString("mime_type"),
            sizeBytes = json.getInt("size_bytes"),
            url = json.getString("url"),
        )
    }
}

/** What the client reports about a file it has already put in the bucket. */
internal data class UploadedAttachment(
    val key: String,
    val filename: String,
    val mimeType: String,
    val sizeBytes: Int,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("key", key)
        .put("filename", filename)
        .put("mime_type", mimeType)
        .put("size_bytes", sizeBytes)
}

internal data class UploadSlot(val key: String, val uploadUrl: String)

internal data class AttachmentUrl(val url: String, val expiresIn: Int)

internal data class Part(
    val seq: Int,
    val authorType: String,
    val body: String,
    val createdAt: Long,
    val authorName: String?,
    val authorAvatarUrl: String?,
    val attachments: List<Attachment>,
    /** Auto-replies only: "helpful", "not_helpful", or null until answered. */
    val botFeedback: String?,
) {
    val isFromContact: Boolean get() = authorType == "contact"
    val isFromBot: Boolean get() = authorType == "bot"

    /** Consecutive messages by the same author render as one visual run. */
    val authorRunKey: String get() = "$authorType:${authorName.orEmpty()}"

    companion object {
        fun parse(json: JSONObject) = Part(
            seq = json.getInt("seq"),
            authorType = json.getString("author_type"),
            body = json.getString("body"),
            createdAt = IsoDates.parse(json.getString("created_at")),
            authorName = json.optStringOrNull("author_name"),
            authorAvatarUrl = json.optStringOrNull("author_avatar_url"),
            attachments = json.optJSONArray("attachments").mapObjects(Attachment::parse),
            botFeedback = json.optStringOrNull("bot_feedback"),
        )
    }
}

internal data class ConversationDetail(
    val id: String,
    val status: String,
    val subject: String?,
    val lastSeq: Int,
    val parts: List<Part>,
) {
    companion object {
        fun parse(json: JSONObject) = ConversationDetail(
            id = json.getString("id"),
            status = json.getString("status"),
            subject = json.optStringOrNull("subject"),
            lastSeq = json.getInt("last_seq"),
            parts = json.getJSONArray("parts").mapObjects(Part::parse),
        )
    }
}

internal data class CreatedConversation(val id: String, val status: String, val lastSeq: Int)

internal data class AppendedPart(
    val conversationId: String,
    val seq: Int,
    val status: String,
    val renewedFrom: String?,
)

internal data class BotFeedbackResponse(
    val ok: Boolean,
    /** "closed" after a helpful vote: the user resolved the thread. */
    val status: String,
    /** Set when a "no" was answered with the fallback message. */
    val replySeq: Int?,
)

// MARK: - org.json helpers

/** `optString` returns "" for both a missing key and JSON null; neither is a value. */
internal fun JSONObject.optStringOrNull(key: String): String? =
    if (isNull(key)) null else optString(key)

internal fun JSONObject.optBooleanOrNull(key: String): Boolean? =
    if (isNull(key)) null else optBoolean(key)

internal fun JSONObject.optIntOrNull(key: String): Int? =
    if (isNull(key)) null else optInt(key)

internal inline fun <T> JSONArray?.mapObjects(transform: (JSONObject) -> T): List<T> {
    if (this == null) return emptyList()
    val out = ArrayList<T>(length())
    for (i in 0 until length()) out.add(transform(getJSONObject(i)))
    return out
}
