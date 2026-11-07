package app.feddy.sdk.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import app.feddy.sdk.core.ApiError
import app.feddy.sdk.core.FeddyCore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Where a picture comes from. */
internal sealed class ImageSource {
    abstract val cacheKey: String

    /** A public http(s) URL: the project logo, a teammate's avatar. */
    data class Url(val url: String) : ImageSource() {
        override val cacheKey: String get() = "url:$url"
    }

    /**
     * A message attachment. The thread carries an API path, not a picture;
     * asking for it proves this contact owns the file and yields a URL good
     * for a minute.
     */
    data class Attachment(val id: String) : ImageSource() {
        override val cacheKey: String get() = "att:$id"
    }
}

internal sealed class ImagePhase {
    data object Loading : ImagePhase()
    data class Loaded(val image: ImageBitmap) : ImagePhase()
    data object Failed : ImagePhase()
}

/**
 * Pictures, decoded once and kept while the process lives.
 *
 * Without this a picture scrolled off screen and back was fetched again, and
 * since every attachment answer is a different signature nothing downstream
 * could cache it either. A request that comes back 403 is retried once
 * against a freshly signed URL before it counts as a failure: a URL taken
 * when the bubble appeared is dead by the time someone taps the picture.
 */
internal object ImageStore {
    /**
     * Sized by decoded bytes, not by bytes on the wire — a 1 MB JPEG is
     * fifteen times that unpacked. Three or four full-size screenshots.
     */
    private val cache = object : LruCache<String, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    fun cached(source: ImageSource): Bitmap? = cache.get(source.cacheKey)

    suspend fun load(source: ImageSource, maxEdge: Int = 3000): Bitmap {
        cached(source)?.let { return it }
        val client = FeddyCore.client ?: throw ApiError(0, "not configured")
        val bytes = when (source) {
            is ImageSource.Url -> client.download(source.url)
            is ImageSource.Attachment -> try {
                client.download(client.attachmentUrl(source.id))
            } catch (error: ApiError) {
                if (error.status != 403 && error.status != 401) throw error
                // The signature aged out while the thread sat open. Nothing
                // else is retried: it would fail the same way and cost the
                // user another download.
                client.forgetAttachmentUrl(source.id)
                client.download(client.attachmentUrl(source.id))
            }
        }
        val bitmap = withContext(Dispatchers.Default) { decode(bytes, maxEdge) }
            ?: throw ApiError(0, "undecodable image")
        cache.put(source.cacheKey, bitmap)
        return bitmap
    }

    private fun decode(bytes: ByteArray, maxEdge: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}

/**
 * Draws through [ImageStore]. Starts at `Loaded` when the picture is already
 * cached, so one scrolled back into view does not blink.
 */
@Composable
internal fun rememberImage(source: ImageSource?, maxEdge: Int = 3000): ImagePhase {
    var phase by remember(source) {
        mutableStateOf<ImagePhase>(
            source?.let { ImageStore.cached(it) }?.let { ImagePhase.Loaded(it.asImageBitmap()) }
                ?: if (source == null) ImagePhase.Failed else ImagePhase.Loading,
        )
    }
    LaunchedEffect(source) {
        if (source == null || phase is ImagePhase.Loaded) return@LaunchedEffect
        phase = try {
            ImagePhase.Loaded(ImageStore.load(source, maxEdge).asImageBitmap())
        } catch (error: CancellationException) {
            // Scrolling away cancels this. Not a failure: coming back tries again.
            throw error
        } catch (error: Throwable) {
            ImagePhase.Failed
        }
    }
    return phase
}
