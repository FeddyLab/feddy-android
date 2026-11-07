package app.feddy.sdk.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Date

internal class ApiError(val status: Int, message: String) : Exception(message) {
    val isRateLimited: Boolean get() = status == 429
}

/** Which platform the messages say they came from, and what device. */
internal interface ContextSource {
    val platform: String
    fun context(): Map<String, String>
    fun locale(): String
}

/**
 * Thin wrapper over the Feddy v1 HTTP API on `HttpURLConnection` and
 * `org.json`, both part of the platform: no HTTP or JSON library of ours
 * can then clash with the host app's version of it.
 */
internal class ApiClient(
    private val projectId: String,
    baseUrl: String,
    private val anonId: String,
    private val device: ContextSource,
) {
    private val base = baseUrl.trimEnd('/')

    /**
     * Signed attachment URLs, kept until just before they expire. Scrolling a
     * thread back and forth otherwise asks again every time a bubble is
     * rebuilt, and each answer is a different URL — one billed read per look.
     */
    private val signedUrls = HashMap<String, Pair<String, Long>>()

    // MARK: - Endpoints

    suspend fun config(): FeddyConfig = FeddyConfig.parse(request("v1/config"))

    suspend fun identify(
        externalId: String,
        email: String?,
        name: String?,
        avatarUrl: String?,
        attributes: Map<String, Any?>,
    ): String {
        val body = JSONObject()
            .put("external_id", externalId)
            .put("locale", device.locale())
            .put("attributes", JSONObject(sanitizeAttributes(attributes)))
        email?.let { body.put("email", it) }
        name?.let { body.put("name", it) }
        avatarUrl?.let { body.put("avatar_url", it) }
        return request("v1/contacts/identify", "POST", body).getString("id")
    }

    suspend fun setEmail(email: String) {
        request("v1/contacts/email", "POST", JSONObject().put("email", email))
    }

    suspend fun conversations(): List<ConversationSummary> =
        ConversationSummary.parseList(request("v1/conversations"))

    suspend fun conversation(id: String, sinceSeq: Int = 0): ConversationDetail {
        val suffix = if (sinceSeq > 0) "?since_seq=$sinceSeq" else ""
        return ConversationDetail.parse(request("v1/conversations/$id$suffix"))
    }

    suspend fun createConversation(
        text: String,
        categoryCode: String?,
        attachments: List<UploadedAttachment>,
        idempotencyKey: String,
    ): CreatedConversation {
        val body = messageBody(text, attachments)
        categoryCode?.let { body.put("category_code", it) }
        val json = request("v1/conversations", "POST", body, idempotencyKey)
        return CreatedConversation(json.getString("id"), json.getString("status"), json.getInt("last_seq"))
    }

    suspend fun appendPart(
        conversationId: String,
        text: String,
        attachments: List<UploadedAttachment>,
        idempotencyKey: String,
    ): AppendedPart {
        val json = request(
            "v1/conversations/$conversationId/parts", "POST",
            messageBody(text, attachments), idempotencyKey,
        )
        return AppendedPart(
            conversationId = json.getString("conversation_id"),
            seq = json.getInt("seq"),
            status = json.getString("status"),
            renewedFrom = json.optStringOrNull("renewed_from"),
        )
    }

    /** "Was this helpful?" on an auto-reply. A yes closes the conversation server-side. */
    suspend fun sendBotFeedback(conversationId: String, seq: Int, helpful: Boolean): BotFeedbackResponse {
        val json = request(
            "v1/conversations/$conversationId/bot_feedback", "POST",
            JSONObject().put("seq", seq).put("helpful", helpful),
        )
        return BotFeedbackResponse(
            ok = json.optBoolean("ok"),
            status = json.getString("status"),
            replySeq = json.optIntOrNull("reply_seq"),
        )
    }

    suspend fun markRead(conversationId: String, seenSeq: Int) {
        request("v1/conversations/$conversationId/read", "POST", JSONObject().put("seen_seq", seenSeq))
    }

    suspend fun unreadCount(): Int = request("v1/unread_count").getInt("unread_count")

    /** Permission to write one object, of one type, for one hour. */
    suspend fun createUpload(filename: String, mimeType: String, sizeBytes: Int): UploadSlot {
        val json = request(
            "v1/uploads", "POST",
            JSONObject().put("filename", filename).put("mime_type", mimeType).put("size_bytes", sizeBytes),
        )
        return UploadSlot(json.getString("key"), json.getString("upload_url"))
    }

    /** A signed URL good for a minute, cached until shortly before it expires. */
    suspend fun attachmentUrl(id: String): String {
        synchronized(signedUrls) {
            signedUrls[id]?.let { (url, until) -> if (until > System.currentTimeMillis()) return url }
        }
        val json = request("v1/attachments/$id")
        val url = json.getString("url")
        // A margin off the end so a URL handed out here never expires mid-load.
        val ttl = maxOf(0, json.optInt("expires_in") - 15) * 1000L
        synchronized(signedUrls) { signedUrls[id] = url to (System.currentTimeMillis() + ttl) }
        return url
    }

    /** Drops a cached signature so the next ask re-signs. */
    fun forgetAttachmentUrl(id: String) {
        synchronized(signedUrls) { signedUrls.remove(id) }
    }

    /**
     * Fetches bytes from a signed or public URL. Talks to the bucket, not to
     * Feddy, so it carries none of our headers; a 403 there means the
     * signature aged out, which the caller retries.
     */
    suspend fun download(url: String): ByteArray = withContext(Dispatchers.IO) {
        val connection = open(url, "GET")
        try {
            val status = connection.responseCode
            if (status !in 200..299) throw ApiError(status, "download failed")
            connection.inputStream.use { it.readAllBytesCompat() }
        } finally {
            connection.disconnect()
        }
    }

    /** Puts the bytes straight into the bucket with the signed URL. */
    suspend fun upload(data: ByteArray, url: String, mimeType: String): Unit = withContext(Dispatchers.IO) {
        val connection = open(url, "PUT")
        try {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", mimeType)
            connection.setFixedLengthStreamingMode(data.size)
            connection.outputStream.use { it.write(data) }
            val status = connection.responseCode
            if (status !in 200..299) throw ApiError(status, "upload failed")
        } finally {
            connection.disconnect()
        }
    }

    // MARK: - Transport

    private fun messageBody(text: String, attachments: List<UploadedAttachment>): JSONObject =
        JSONObject()
            .put("body", text)
            .put("platform", device.platform)
            .put("context", JSONObject(device.context()))
            .put("attachments", JSONArray(attachments.map { it.toJson() }))

    private suspend fun request(
        path: String,
        method: String = "GET",
        body: JSONObject? = null,
        idempotencyKey: String? = null,
    ): JSONObject = withContext(Dispatchers.IO) {
        val connection = open("$base/$path", method)
        try {
            connection.setRequestProperty("Authorization", "Bearer $projectId")
            connection.setRequestProperty("X-Feddy-Anon-Id", anonId)
            // The language developer-written text comes back in; the API
            // translates server-side and falls back to the default text.
            connection.setRequestProperty("X-Feddy-Locale", device.locale())
            connection.setRequestProperty("Accept", "application/json")
            idempotencyKey?.let { connection.setRequestProperty("Idempotency-Key", it) }
            if (body != null) {
                val bytes = body.toString().toByteArray(Charsets.UTF_8)
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.use { it.readAllBytesCompat().toString(Charsets.UTF_8) }.orEmpty()
            if (status !in 200..299) {
                val message = runCatching { JSONObject(text).optString("error") }.getOrNull()
                    ?.takeIf { it.isNotEmpty() } ?: "request failed ($status)"
                throw ApiError(status, message)
            }
            if (text.isBlank()) JSONObject() else JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String, method: String): HttpURLConnection {
        val connection = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (error: Exception) {
            throw ApiError(0, "invalid URL")
        }
        connection.requestMethod = method
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.useCaches = false
        return connection
    }

    companion object {
        /**
         * Keeps only JSON scalars; a Date becomes an ISO 8601 string. Anything
         * else is dropped here, as the server drops out-of-contract values.
         */
        fun sanitizeAttributes(attributes: Map<String, Any?>): Map<String, Any> {
            val out = LinkedHashMap<String, Any>()
            for ((key, value) in attributes) {
                when (value) {
                    is String, is Boolean -> out[key] = value
                    is Int, is Long, is Short, is Byte -> out[key] = (value as Number).toLong()
                    is Double -> if (value.isFinite()) out[key] = value
                    is Float -> if (value.isFinite()) out[key] = value.toDouble()
                    is Date -> out[key] = IsoDates.format(value)
                    else -> Unit
                }
            }
            return out
        }
    }
}

/** `InputStream.readAllBytes` is API 33. */
internal fun InputStream.readAllBytesCompat(): ByteArray {
    val buffer = ByteArrayOutputStream()
    val chunk = ByteArray(16 * 1024)
    while (true) {
        val read = read(chunk)
        if (read < 0) break
        buffer.write(chunk, 0, read)
    }
    return buffer.toByteArray()
}

/**
 * Network failures (IOException and anything else without a status) are
 * worth another try; of the API's answers only 429 and 5xx are.
 */
internal fun Throwable.isRetryable(): Boolean = when (this) {
    is ApiError -> isRateLimited || status >= 500
    else -> true
}
