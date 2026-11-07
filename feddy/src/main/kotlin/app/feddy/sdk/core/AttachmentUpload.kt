package app.feddy.sdk.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import androidx.core.graphics.scale
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** The limits the API enforces, mirrored so a file is refused without a round trip. */
internal object AttachmentLimits {
    const val MAX_BYTES: Int = 10 * 1024 * 1024
    const val MAX_PER_MESSAGE: Int = 5
}

/**
 * Turns a picked image into something worth uploading, then puts it in the
 * bucket. Same rules as iOS: a JPEG whose long edge is capped at 3000px —
 * a screenshot is sent so someone can read the label on a button, so the
 * ceiling sits above every phone screenshot — at quality 90, because JPEG
 * spends its error budget on hard edges, which in a screenshot is the text.
 */
internal object AttachmentUpload {
    const val MAX_EDGE: Int = 3000
    const val QUALITY: Int = 90

    /**
     * Downscales and re-encodes as JPEG. Null when the bytes are not a
     * readable image. Decodes at a power-of-two reduction first so a large
     * photo never sits in memory at full resolution.
     */
    fun prepare(data: ByteArray): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_EDGE) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(
            data, 0, data.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return null
        val oriented = applyOrientation(decoded, data)
        val longEdge = maxOf(oriented.width, oriented.height)
        val scaled = if (longEdge > MAX_EDGE) {
            val factor = MAX_EDGE.toFloat() / longEdge
            oriented.scale(
                (oriented.width * factor).toInt().coerceAtLeast(1),
                (oriented.height * factor).toInt().coerceAtLeast(1),
            )
        } else {
            oriented
        }
        val out = ByteArrayOutputStream()
        if (!scaled.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)) return null
        if (scaled !== oriented) scaled.recycle()
        if (oriented !== decoded) oriented.recycle()
        decoded.recycle()
        return out.toByteArray()
    }

    /**
     * Camera photos are stored sideways with an EXIF note saying so; the
     * re-encoded JPEG drops that note, so the rotation is baked in. Reading
     * EXIF from a stream is API 24; on 23 a photo keeps its stored rotation.
     */
    // The framework class, not androidx.exifinterface: one dependency fewer,
    // and its known bugs are on versions before 24, which never get here.
    @Suppress("ExifInterface")
    private fun applyOrientation(bitmap: Bitmap, data: ByteArray): Bitmap {
        if (Build.VERSION.SDK_INT < 24) return bitmap
        val orientation = runCatching {
            ExifInterface(ByteArrayInputStream(data))
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /** Compress, ask for a slot, PUT the bytes. Returns what the message write needs. */
    suspend fun send(data: ByteArray, filename: String, api: ApiClient): UploadedAttachment {
        val prepared = withContext(Dispatchers.Default) { prepare(data) }
            ?: throw ApiError(0, "unreadable image")
        if (prepared.size > AttachmentLimits.MAX_BYTES) throw ApiError(0, "image too large")
        val name = jpegName(filename)
        val slot = api.createUpload(name, "image/jpeg", prepared.size)
        api.upload(prepared, slot.uploadUrl, "image/jpeg")
        return UploadedAttachment(slot.key, name, "image/jpeg", prepared.size)
    }

    /** Everything leaves as JPEG, so the name says so. */
    fun jpegName(filename: String): String {
        val dot = filename.lastIndexOf('.')
        val stem = if (dot > 0) filename.substring(0, dot) else if (dot == 0) "" else filename
        return stem.ifEmpty { "image" } + ".jpg"
    }
}
