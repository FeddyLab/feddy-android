package app.feddy.sdk

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The unread count as a small marker for your own "Help" row. Draws nothing
 * at all while nothing is unread, so it can sit in a row unconditionally.
 *
 * ```kotlin
 * Row {
 *     Text("Support")
 *     Spacer(Modifier.weight(1f))
 *     FeddyUnreadDot()
 * }
 * ```
 *
 * @param showsCount draw the number inside the marker; a plain dot when
 *   false, which suits a row that only needs to say "new".
 */
@Composable
public fun FeddyUnreadDot(modifier: Modifier = Modifier, showsCount: Boolean = false) {
    val count by Feddy.unreadCountFlow.collectAsState()
    if (count <= 0) return
    val label = stringResource(R.string.feddy_new_reply)
    val color = MaterialTheme.colorScheme.error
    if (showsCount) {
        Box(
            modifier
                .clip(CircleShape)
                .background(color)
                .padding(horizontal = 6.dp, vertical = 2.dp)
                .semantics { contentDescription = "$label: $count" },
        ) {
            Text("$count", color = MaterialTheme.colorScheme.onError, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }
    } else {
        Box(modifier.size(8.dp).clip(CircleShape).background(color).semantics { contentDescription = label })
    }
}
