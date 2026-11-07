package app.feddy.sdk.ui

import android.content.Intent
import android.text.format.DateUtils
import androidx.compose.animation.core.animateFloatAsState
import androidx.core.net.toUri
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.feddy.sdk.core.FeddyCore

/** The project's logo, or its initial on the brand colour when there is none. */
@Composable
internal fun BrandMark(size: Dp) {
    val config by FeddyCore.config.collectAsState()
    val colors = FeddyTheme.colors
    val shape = RoundedCornerShape(size * 0.25f)
    val logo = config?.brand?.logoUrl
    Box(
        modifier = Modifier
            .size(size)
            .clip(shape)
            .background(if (logo != null) colors.surface else colors.accent)
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        if (logo != null) {
            val phase = rememberImage(ImageSource.Url(logo), maxEdge = 512)
            if (phase is ImagePhase.Loaded) {
                Image(phase.image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(size))
            }
        } else {
            Text(
                text = brandInitial(config?.brand?.name),
                color = colors.onAccent,
                fontWeight = FontWeight.SemiBold,
                fontSize = (size.value * 0.45f).sp,
            )
        }
    }
}

internal fun brandInitial(name: String?): String =
    name?.trim()?.firstOrNull()?.uppercase() ?: "F"

/**
 * "Powered by Feddy" in the page's own text colour so it reads as a
 * signature, with the name in Feddy's blue.
 */
@Composable
internal fun PoweredByFeddy(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val colors = FeddyTheme.colors
    val label = buildAnnotatedString {
        withStyle(SpanStyle(fontWeight = FontWeight.Medium)) { append("Powered by ") }
        withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = FEDDY_BLUE)) { append("Feddy") }
    }
    Text(
        text = label,
        color = colors.text,
        fontSize = 14.sp,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Button) {
                val intent = Intent(Intent.ACTION_VIEW, POWERED_BY_URL.toUri())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(intent) }
            }
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

private val FEDDY_BLUE = Color(0xFF4F5AFA)
private const val POWERED_BY_URL = "https://feddy.app/?utm_source=android-sdk&utm_medium=powered-by"

/** A filled card dims a little under the finger, the way system rows do. */
@Composable
internal fun Modifier.pressable(
    enabled: Boolean = true,
    onClickLabel: String? = null,
    onClick: () -> Unit,
): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val alpha by animateFloatAsState(if (pressed) 0.6f else 1f, tween(150), label = "press")
    return this
        .alpha(alpha)
        .clickable(
            interactionSource = interaction,
            indication = null,
            enabled = enabled,
            onClickLabel = onClickLabel,
            role = Role.Button,
            onClick = onClick,
        )
}

/** The bar every screen has: one action on the leading side, optional ones trailing. */
@Composable
internal fun FeddyTopBar(
    title: String?,
    navigationIcon: Int,
    navigationLabel: String,
    onNavigate: () -> Unit,
    actions: @Composable () -> Unit = {},
) {
    val colors = FeddyTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        IconButton(onClick = onNavigate) {
            Icon(painterResource(navigationIcon), contentDescription = navigationLabel, tint = colors.text)
        }
        if (title != null) {
            Text(
                text = title,
                color = colors.text,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp),
            )
        } else {
            Box(Modifier.weight(1f))
        }
        Row(verticalAlignment = Alignment.CenterVertically) { actions() }
    }
}

/** "5 min. ago", "Yesterday": the row's when, in the device's language. */
internal fun relativeTime(epochMs: Long, now: Long = System.currentTimeMillis()): String =
    DateUtils.getRelativeTimeSpanString(
        epochMs, now, DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE,
    ).toString()

/** A message's time: today's just the time, older ones the date too. */
internal fun messageTime(context: android.content.Context, epochMs: Long): String {
    val flags = if (DateUtils.isToday(epochMs)) {
        DateUtils.FORMAT_SHOW_TIME
    } else {
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH
    }
    return DateUtils.formatDateTime(context, epochMs, flags)
}

/** Screen-reader text for something drawn without words. */
internal fun Modifier.described(label: String): Modifier = semantics { contentDescription = label }

internal fun Modifier.roleButton(): Modifier = semantics { role = Role.Button }
