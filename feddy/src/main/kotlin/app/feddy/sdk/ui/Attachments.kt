package app.feddy.sdk.ui

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.feddy.sdk.R
import app.feddy.sdk.core.Attachment
import app.feddy.sdk.core.AttachmentLimits
import app.feddy.sdk.core.readAllBytesCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val TILE = 64.dp

/**
 * The image area: what has been picked, plus the tile that picks more.
 * Deliberately an area rather than a paperclip beside Send: this only ever
 * takes pictures, and an icon crowded in with the buttons is not where
 * anyone looks for it.
 *
 * The system photo picker needs no storage permission (Android 13+ natively,
 * older versions through Google Play services), so the host app declares
 * nothing.
 */
@Composable
internal fun AttachmentArea(tray: AttachmentTrayModel, disabled: Boolean) {
    val colors = FeddyTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val onPicked: (List<Uri>) -> Unit = { uris ->
        scope.launch {
            for (uri in uris) {
                val bytes = withContext(Dispatchers.IO) {
                    runCatching { context.contentResolver.openInputStream(uri)?.use { it.readAllBytesCompat() } }.getOrNull()
                }
                tray.add(bytes)
            }
        }
    }
    val pickOne = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) onPicked(listOf(uri))
    }
    // What is left, not the ceiling: offering five when three are already in
    // the tray let someone pick five and then lose two. Never under 2, which
    // the contract refuses; the single picker covers the last slot.
    val pickMany = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxOf(2, tray.remaining)),
    ) { uris -> onPicked(uris) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 1.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            tray.items.forEach { item -> TrayThumbnail(item, disabled) { tray.remove(item.id) } }
            // Stays put when full rather than disappearing: a control that
            // vanishes explains nothing.
            val enabled = !disabled && !tray.isFull
            val label = stringResource(R.string.feddy_attach)
            Box(
                modifier = Modifier
                    .size(TILE)
                    .clip(RoundedCornerShape(10.dp))
                    .border(1.dp, colors.hairline, RoundedCornerShape(10.dp))
                    .clickable(enabled = enabled, onClickLabel = label) {
                        val request = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        if (tray.remaining >= 2) pickMany.launch(request) else pickOne.launch(request)
                    }
                    .semantics { contentDescription = label },
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.alpha(if (tray.isFull) 0.45f else 1f)) {
                    Icon(painterResource(R.drawable.feddy_ic_photo), contentDescription = null, tint = colors.secondaryText, modifier = Modifier.size(20.dp))
                    // Digits only: "2/5" needs no translation, and it answers
                    // "how many more?" right where that question gets asked.
                    Text("${tray.items.size}/${AttachmentLimits.MAX_PER_MESSAGE}", color = colors.secondaryText, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
        tray.error?.let { Text(it, color = colors.error, fontSize = 13.sp) }
    }
}

@Composable
private fun TrayThumbnail(item: AttachmentTrayModel.Item, disabled: Boolean, onRemove: () -> Unit) {
    val preview: ImageBitmap? = remember(item.id) { decodePreview(item.data) }
    val close = stringResource(R.string.feddy_close)
    Box(Modifier.size(TILE)) {
        if (preview != null) {
            Image(preview, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(TILE).clip(RoundedCornerShape(10.dp)))
        }
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(32.dp)
                .clickable(enabled = !disabled, onClickLabel = close, onClick = onRemove)
                .semantics { contentDescription = close },
            contentAlignment = Alignment.TopEnd,
        ) {
            Box(Modifier.padding(3.dp).size(20.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.feddy_ic_close), contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
            }
        }
    }
}

private fun decodePreview(data: ByteArray): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 256) sample *= 2
    return BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
}

/**
 * However many pictures came with one message, laid out so the bubble can
 * never grow wider than the screen. A single image keeps its shape; several
 * become a grid of equal tiles, and tapping any opens it whole.
 */
@Composable
internal fun AttachmentGrid(attachments: List<Attachment>, alignEnd: Boolean, onOpen: (Attachment) -> Unit) {
    if (attachments.size == 1) {
        AttachmentThumbnail(attachments[0], tile = null, onOpen = onOpen)
        return
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start,
    ) {
        attachments.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { AttachmentThumbnail(it, tile = 84.dp, onOpen = onOpen) }
            }
        }
    }
}

@Composable
private fun AttachmentThumbnail(attachment: Attachment, tile: Dp?, onOpen: (Attachment) -> Unit) {
    val colors = FeddyTheme.colors
    val shape = RoundedCornerShape(12.dp)
    when (val phase = rememberImage(ImageSource.Attachment(attachment.id))) {
        is ImagePhase.Loaded -> {
            val modifier = Modifier
                .clip(shape)
                .clickable(onClickLabel = attachment.filename) { onOpen(attachment) }
            if (tile != null) {
                Image(phase.image, attachment.filename, contentScale = ContentScale.Crop, modifier = modifier.size(tile))
            } else {
                // Capped, not framed square: a lone screenshot cropped to a
                // tile loses the very thing it was sent to show.
                Image(phase.image, attachment.filename, contentScale = ContentScale.Fit, modifier = modifier.sizeIn(maxWidth = 200.dp, maxHeight = 260.dp))
            }
        }
        // Exactly the size the picture will take, so nothing reflows when it lands.
        ImagePhase.Loading -> Box(Modifier.size(tile ?: 200.dp).clip(shape).background(colors.surface), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = colors.secondaryText)
        }
        // A picture that will not load is not worth a broken-image icon mid-conversation.
        ImagePhase.Failed -> Unit
    }
}

/**
 * The picture on its own, over black: whatever the person is trying to read
 * in the screenshot is the only thing that matters here. A tap anywhere
 * closes it, which is the gesture people try first.
 */
@Composable
internal fun AttachmentViewer(attachment: Attachment, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable(onClickLabel = stringResource(R.string.feddy_close)) { onDismiss() },
            contentAlignment = Alignment.Center,
        ) {
            when (val phase = rememberImage(ImageSource.Attachment(attachment.id))) {
                is ImagePhase.Loaded ->
                    Image(phase.image, attachment.filename, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                ImagePhase.Loading -> CircularProgressIndicator(color = Color.White)
                // Said out loud rather than spun forever.
                ImagePhase.Failed -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(painterResource(R.drawable.feddy_ic_photo), null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(30.dp))
                    Text(stringResource(R.string.feddy_error_generic), color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
                }
            }
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(top = 8.dp, start = 12.dp)
                    .size(44.dp)
                    .clip(CircleShape)
                    .clickable(onClickLabel = stringResource(R.string.feddy_close)) { onDismiss() },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(34.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.feddy_ic_close), stringResource(R.string.feddy_close), tint = Color.White, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}
