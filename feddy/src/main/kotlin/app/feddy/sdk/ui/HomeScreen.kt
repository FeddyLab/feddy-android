package app.feddy.sdk.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.feddy.sdk.R
import app.feddy.sdk.core.ConversationSummary
import app.feddy.sdk.core.FeddyCore
import kotlinx.coroutines.launch

/** Enough to pick a thread back up without pushing the rest off the first screen. */
private const val RECENT_LIMIT = 3

/**
 * What `Feddy.present()` opens on: who you are writing to, the one way to
 * start, and the last few threads. The blocks stack in a fixed order today;
 * kept as separate pieces so the server can one day choose and order them.
 */
@Composable
internal fun HomeScreen(
    model: ConversationListModel,
    onClose: () -> Unit,
    onNewMessage: () -> Unit,
    onOpen: (String) -> Unit,
    onSeeAll: () -> Unit,
) {
    val colors = FeddyTheme.colors
    val config by FeddyCore.config.collectAsState()
    Column(Modifier.fillMaxSize().background(colors.page)) {
        FeddyTopBar(
            // The greeting is the title; a bar title would say it twice.
            title = null,
            navigationIcon = R.drawable.feddy_ic_close,
            navigationLabel = stringResource(R.string.feddy_close),
            onNavigate = onClose,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(36.dp),
        ) {
            Greeting(config?.brand?.name)
            NewMessageCard(config?.replySlaText, onNewMessage)
            RecentConversations(model, onOpen, onSeeAll)
        }
        if (config?.brandingEnabled ?: true) {
            Box(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(top = 8.dp, bottom = 12.dp),
                contentAlignment = Alignment.Center,
            ) { PoweredByFeddy() }
        } else {
            Spacer(Modifier.navigationBarsPadding())
        }
    }
}

/** The mark and the name say who you are writing to; the question is the headline. */
@Composable
private fun Greeting(brandName: String?) {
    val colors = FeddyTheme.colors
    Column(
        verticalArrangement = Arrangement.spacedBy(20.dp),
        modifier = Modifier.semantics(mergeDescendants = true) { heading() },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BrandMark(44.dp)
            if (brandName != null) {
                Text(brandName, color = colors.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Text(
            stringResource(R.string.feddy_home_greeting),
            color = colors.text,
            fontSize = 32.sp,
            lineHeight = 38.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** The one action on the screen, so it carries the brand colour; the reply time rides underneath. */
@Composable
private fun NewMessageCard(sla: String?, onClick: () -> Unit) {
    val colors = FeddyTheme.colors
    val title = stringResource(R.string.feddy_send_us_message)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(colors.accent)
            .pressable(onClick = onClick)
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = colors.onAccent, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            if (!sla.isNullOrEmpty()) {
                Text(sla, color = colors.onAccent.copy(alpha = 0.85f), fontSize = 14.sp)
            }
        }
        Spacer(Modifier.width(8.dp))
        Icon(painterResource(R.drawable.feddy_ic_send), contentDescription = null, tint = colors.onAccent, modifier = Modifier.size(22.dp))
    }
}

/** Absent until there is something to show: the card above already says what to do. */
@Composable
private fun RecentConversations(model: ConversationListModel, onOpen: (String) -> Unit, onSeeAll: () -> Unit) {
    val colors = FeddyTheme.colors
    when {
        model.isLoading -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(28.dp), color = colors.secondaryText, strokeWidth = 2.5.dp)
        }
        model.conversations.isNotEmpty() -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    stringResource(R.string.feddy_your_messages),
                    color = colors.text,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                if (model.conversations.size > RECENT_LIMIT) {
                    Text(
                        stringResource(R.string.feddy_see_all),
                        color = colors.accent,
                        fontSize = 14.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .pressable(onClick = onSeeAll)
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                }
            }
            val recent = model.conversations.take(RECENT_LIMIT)
            Column(Modifier.clip(RoundedCornerShape(16.dp)).background(colors.ownBubble)) {
                recent.forEachIndexed { index, conversation ->
                    ConversationRow(
                        conversation,
                        modifier = Modifier
                            .pressable { onOpen(conversation.id) }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                    if (index < recent.lastIndex) {
                        HorizontalDivider(Modifier.padding(start = 64.dp), color = colors.hairline, thickness = 0.5.dp)
                    }
                }
            }
        }
        model.loadFailed -> Text(stringResource(R.string.feddy_error_generic), color = colors.secondaryText, fontSize = 13.sp)
    }
}

/** One conversation: the project's face, the latest thing said, who said it and when. */
@Composable
internal fun ConversationRow(conversation: ConversationSummary, modifier: Modifier = Modifier) {
    val colors = FeddyTheme.colors
    val config by FeddyCore.config.collectAsState()
    val author = when (conversation.lastAuthorType) {
        null -> null
        "contact" -> stringResource(R.string.feddy_you)
        else -> conversation.lastAuthorName ?: config?.brand?.name
    }
    val newReply = stringResource(R.string.feddy_new_reply)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BrandMark(36.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            // An inbox row is the latest thing said, not the opener.
            Text(
                conversation.lastMessage ?: conversation.subject ?: "…",
                color = colors.text,
                fontSize = 14.sp,
                fontWeight = if (conversation.hasUnread) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                val meta = listOfNotNull(author, relativeTime(conversation.lastMessageAt)).joinToString(" · ")
                Text(meta, color = colors.secondaryText, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (conversation.status == "closed") {
                    Text(
                        stringResource(R.string.feddy_status_closed),
                        color = colors.secondaryText,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .padding(start = 2.dp)
                            .clip(CircleShape)
                            .background(colors.surface)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (conversation.hasUnread) colors.accent else Color.Transparent)
                .then(if (conversation.hasUnread) Modifier.semantics { contentDescription = newReply } else Modifier.clearAndSetSemantics {}),
        )
    }
}

/** Every conversation, for when there are more than the home screen shows. */
@Composable
internal fun ConversationListScreen(model: ConversationListModel, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val colors = FeddyTheme.colors
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(colors.page)) {
        FeddyTopBar(
            title = stringResource(R.string.feddy_your_messages),
            navigationIcon = R.drawable.feddy_ic_arrow_back,
            navigationLabel = stringResource(R.string.feddy_close),
            onNavigate = onBack,
        )
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                scope.launch {
                    refreshing = true
                    model.load()
                    refreshing = false
                }
            },
            modifier = Modifier.weight(1f),
        ) {
            LazyColumn(Modifier.fillMaxSize().navigationBarsPadding()) {
                items(model.conversations, key = { it.id }) { conversation ->
                    ConversationRow(
                        conversation,
                        modifier = Modifier
                            .pressable { onOpen(conversation.id) }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    HorizontalDivider(Modifier.padding(start = 64.dp), color = colors.hairline, thickness = 0.5.dp)
                }
            }
        }
    }
}
