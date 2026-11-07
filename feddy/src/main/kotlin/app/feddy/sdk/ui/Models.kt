package app.feddy.sdk.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.feddy.sdk.R
import app.feddy.sdk.core.ApiClient
import app.feddy.sdk.core.ApiError
import app.feddy.sdk.core.AttachmentLimits
import app.feddy.sdk.core.AttachmentUpload
import app.feddy.sdk.core.ConversationSummary
import app.feddy.sdk.core.FeddyCore
import app.feddy.sdk.core.Part
import app.feddy.sdk.core.UploadedAttachment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.UUID
import kotlin.coroutines.coroutineContext

/** How often an open screen re-asks for new messages. */
internal const val SCREEN_POLL_MS = 5_000L

internal class ConversationListModel {
    var conversations by mutableStateOf<List<ConversationSummary>>(emptyList())
        private set
    var isLoading by mutableStateOf(false)
        private set
    var loadFailed by mutableStateOf(false)
        private set
    private var hasLoaded = false

    suspend fun load() {
        val client = FeddyCore.client ?: return
        // Only the very first load swaps the screen for a spinner; later
        // refreshes update in place.
        if (!hasLoaded) isLoading = true
        try {
            conversations = client.conversations()
            loadFailed = false
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            loadFailed = conversations.isEmpty()
        } finally {
            isLoading = false
            hasLoaded = true
        }
    }

    /** Keeps the list fresh while the panel is open. */
    suspend fun pollLoop() {
        while (coroutineContext.isActive) {
            delay(SCREEN_POLL_MS)
            // A failed config fetch leaves the compose form without topics,
            // so keep retrying it alongside the list.
            FeddyCore.loadConfig()
            load()
        }
    }

    /** Opening a thread clears its dot without waiting for the round trip. */
    fun markReadLocally(id: String) {
        conversations = conversations.map { if (it.id == id) it.copy(seenSeq = it.lastSeq) else it }
    }
}

internal class ConversationDetailModel(initialId: String, private val context: Context) {
    var conversationId by mutableStateOf(initialId)
        private set
    var parts by mutableStateOf<List<Part>>(emptyList())
        private set
    var status by mutableStateOf("open")
        private set
    var isSending by mutableStateOf(false)
        private set
    var sendError by mutableStateOf<String?>(null)
        private set
    /** The user just closed this thread by rating an auto-reply helpful. */
    var resolvedByUser by mutableStateOf(false)
        private set
    var isRating by mutableStateOf(false)
        private set
    /** Shown where the rating buttons were, after an answer. */
    var thanksSeq by mutableStateOf<Int?>(null)
        private set
    var loaded by mutableStateOf(false)
        private set

    private var reportedSeq = 0
    private var pendingIdempotencyKey: String? = null

    val maxSeq: Int get() = parts.lastOrNull()?.seq ?: 0

    /** The email ask follows a person's reply, not the bot's. */
    val hasTeammateReply: Boolean get() = parts.any { it.authorType == "teammate" }

    /**
     * The auto-reply that can still be rated: the latest bot message, as long
     * as nobody has rated it and no teammate has replied since.
     */
    val feedbackTarget: Part?
        get() {
            val bot = parts.lastOrNull { it.isFromBot } ?: return null
            if (bot.botFeedback != null) return null
            if (parts.any { it.seq > bot.seq && it.authorType == "teammate" }) return null
            return bot
        }

    suspend fun rate(seq: Int, helpful: Boolean) {
        val client = FeddyCore.client ?: return
        if (isRating) return
        isRating = true
        try {
            val result = client.sendBotFeedback(conversationId, seq, helpful)
            updatePart(seq) { it.copy(botFeedback = if (helpful) "helpful" else "not_helpful") }
            thanksSeq = seq
            if (result.status == "closed" && status != "closed") {
                status = "closed"
                resolvedByUser = true
            }
            // A "no" is answered with the fallback message; the regular poll brings it in.
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            // Rated elsewhere already, or offline: the next poll draws the truth.
            updatePart(seq) { it.copy(botFeedback = "unknown") }
        } finally {
            isRating = false
        }
    }

    suspend fun loadInitial() {
        val client = FeddyCore.client ?: return
        val detail = runCatching { client.conversation(conversationId) }.getOrNull()
        if (detail == null) {
            loaded = true
            return
        }
        parts = detail.parts
        status = detail.status
        loaded = true
        reportRead()
    }

    suspend fun pollLoop() {
        while (coroutineContext.isActive) {
            delay(SCREEN_POLL_MS)
            pollOnce()
        }
    }

    suspend fun pollOnce() {
        val client = FeddyCore.client ?: return
        val fresh = runCatching { client.conversation(conversationId, maxSeq) }.getOrNull() ?: return
        status = fresh.status
        if (fresh.parts.isEmpty()) return
        merge(fresh.parts)
        reportRead()
    }

    /** True when sent. A failure leaves the caller's draft alone so the message is never lost. */
    suspend fun send(text: String, tray: AttachmentTrayModel?): Boolean {
        val client = FeddyCore.client ?: return false
        if (isSending) return false
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        isSending = true
        sendError = null
        val key = pendingIdempotencyKey ?: UUID.randomUUID().toString()
        pendingIdempotencyKey = key
        try {
            // Uploaded before the message is written, and left in the tray if
            // it fails: the reply can then be retried whole.
            val files = tray?.upload(client) ?: emptyList()
            val result = client.appendPart(conversationId, trimmed, files, key)
            pendingIdempotencyKey = null
            tray?.clear()
            if (result.conversationId != conversationId) {
                // Closed for 7+ days: the server started a fresh conversation.
                conversationId = result.conversationId
                parts = emptyList()
                reportedSeq = 0
                resolvedByUser = false
                loadInitial()
            } else {
                pollOnce()
            }
            return true
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            sendError = errorMessage(context, error)
            return false
        } finally {
            isSending = false
        }
    }

    private fun updatePart(seq: Int, change: (Part) -> Part) {
        parts = parts.map { if (it.seq == seq) change(it) else it }
    }

    private fun merge(incoming: List<Part>) {
        val bySeq = parts.associateBy { it.seq }.toMutableMap()
        incoming.forEach { bySeq[it.seq] = it }
        parts = bySeq.values.sortedBy { it.seq }
    }

    /** Having the thread on screen is what "read" means: report the highest seq shown. */
    private suspend fun reportRead() {
        val client = FeddyCore.client ?: return
        val seq = maxSeq
        if (seq <= reportedSeq) return
        if (runCatching { client.markRead(conversationId, seq) }.isSuccess) {
            reportedSeq = seq
            FeddyCore.refresh()
        }
    }
}

internal class ComposeModel(private val context: Context) {
    var text by mutableStateOf("")
    var categoryCode by mutableStateOf<String?>(null)
    var isSubmitting by mutableStateOf(false)
        private set
    var submitError by mutableStateOf<String?>(null)
        private set
    var createdConversationId by mutableStateOf<String?>(null)
        private set

    private var pendingIdempotencyKey: String? = null

    suspend fun submit(tray: AttachmentTrayModel?) {
        val client = FeddyCore.client ?: return
        if (isSubmitting) return
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        isSubmitting = true
        submitError = null
        val key = pendingIdempotencyKey ?: UUID.randomUUID().toString()
        pendingIdempotencyKey = key
        try {
            val files = tray?.upload(client) ?: emptyList()
            val created = client.createConversation(trimmed, categoryCode, files, key)
            pendingIdempotencyKey = null
            tray?.clear()
            createdConversationId = created.id
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            submitError = errorMessage(context, error)
        } finally {
            isSubmitting = false
        }
    }
}

/**
 * Images picked but not yet sent. The bytes are held rather than uploaded on
 * pick: someone who changes their mind should not have spent their data, and
 * the upload has to finish before the message is written anyway.
 */
internal class AttachmentTrayModel(private val context: Context) {
    class Item(val id: String, val data: ByteArray, val filename: String)

    val items = mutableStateListOf<Item>()
    var error by mutableStateOf<String?>(null)

    /** Names are handed out here, counting up so a removed one is never reused. */
    private var nextIndex = 1

    val isFull: Boolean get() = items.size >= AttachmentLimits.MAX_PER_MESSAGE
    val remaining: Int get() = maxOf(0, AttachmentLimits.MAX_PER_MESSAGE - items.size)

    fun add(data: ByteArray?) {
        when {
            data == null -> error = context.getString(R.string.feddy_attach_type_error)
            isFull -> error = context.getString(R.string.feddy_attach_count_error)
            // What is sent is the re-encoded JPEG, checked against the real
            // limit in AttachmentUpload. A raw camera photo is often past
            // 10 MB and shrinks well below it, so only refuse here what no
            // re-encode will bring under.
            data.size > AttachmentLimits.MAX_BYTES * 4 -> error = context.getString(R.string.feddy_attach_size_error)
            !isImage(data) -> error = context.getString(R.string.feddy_attach_type_error)
            else -> {
                error = null
                items.add(Item(UUID.randomUUID().toString(), data, "image-$nextIndex.jpg"))
                nextIndex += 1
            }
        }
    }

    fun remove(id: String) {
        items.removeAll { it.id == id }
    }

    fun clear() {
        items.clear()
        error = null
        nextIndex = 1
    }

    /**
     * Uploads in order. Throws on the first failure: a half-attached message
     * is worse than a send that can be retried with everything still here.
     */
    suspend fun upload(client: ApiClient): List<UploadedAttachment> =
        items.toList().map { AttachmentUpload.send(it.data, it.filename, client) }

    private fun isImage(data: ByteArray): Boolean {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        return bounds.outWidth > 0
    }
}

internal fun errorMessage(context: Context, error: Throwable): String =
    if (error is ApiError && error.isRateLimited) {
        context.getString(R.string.feddy_rate_limited)
    } else {
        context.getString(R.string.feddy_error_generic)
    }
