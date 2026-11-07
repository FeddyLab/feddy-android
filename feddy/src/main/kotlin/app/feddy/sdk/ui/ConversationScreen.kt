package app.feddy.sdk.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.feddy.sdk.R
import app.feddy.sdk.core.Attachment
import app.feddy.sdk.core.FeddyCore
import app.feddy.sdk.core.Part
import kotlinx.coroutines.launch

/** The speaker's avatar, and the column everything under it lines up on. */
private val AVATAR = 32.dp
private val AVATAR_GAP = 8.dp
private val AVATAR_INSET = AVATAR + AVATAR_GAP
/** Room kept free on the far side of every bubble. */
private val FAR_SIDE = 48.dp

@Composable
internal fun ConversationScreen(conversationId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val colors = FeddyTheme.colors
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val model = remember(conversationId) { ConversationDetailModel(conversationId, context) }
    val tray = remember { AttachmentTrayModel(context) }
    val config by FeddyCore.config.collectAsState()
    var draft by remember { mutableStateOf("") }
    // Mirrors of state kept in preferences, which publish nothing.
    var emailKnown by remember { mutableStateOf(FeddyCore.prefs.emailKnown) }
    var bannerDismissed by remember { mutableStateOf(false) }
    var showEmailDialog by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<Attachment?>(null) }
    val emailEnabled = config?.emailCaptureEnabled ?: true

    LaunchedEffect(conversationId) {
        model.loadInitial()
        model.pollLoop()
    }

    val showBanner = emailEnabled && !emailKnown && !bannerDismissed &&
        !FeddyCore.prefs.emailAskDismissed && model.hasTeammateReply

    // The thank-you belongs to the message it answered: it stays where the
    // buttons were and goes with the next message, whoever sends it.
    val feedback: FeedbackState? = model.feedbackTarget?.let { FeedbackState.Ask(it.seq, model.isRating) }
        ?: model.thanksSeq?.takeIf { it == model.parts.lastOrNull()?.seq }?.let { FeedbackState.Thanks }

    val listState = rememberLazyListState()
    val lastIndex = model.parts.size + (if (feedback != null) 1 else 0) + (if (showBanner) 1 else 0) - 1
    var scrolledOnce by remember { mutableStateOf(false) }
    LaunchedEffect(model.parts.size, showBanner, feedback != null) {
        if (lastIndex < 0) return@LaunchedEffect
        if (scrolledOnce) listState.animateScrollToItem(lastIndex) else listState.scrollToItem(lastIndex)
        scrolledOnce = true
    }
    // Dragging the thread puts the keyboard away, as Messages does.
    val dismissKeyboardOnDrag = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && kotlin.math.abs(available.y) > 4f) focus.clearFocus()
                return Offset.Zero
            }
        }
    }

    Column(Modifier.fillMaxSize().background(colors.page).imePadding()) {
        FeddyTopBar(
            title = config?.brand?.name ?: stringResource(R.string.feddy_messages),
            navigationIcon = R.drawable.feddy_ic_arrow_back,
            navigationLabel = stringResource(R.string.feddy_close),
            onNavigate = onBack,
            actions = {
                // Stays for as long as there is no email, whether the ask was
                // skipped or never shown: skipping is honoured for good, so
                // there has to be a way back to it.
                if (emailEnabled && !emailKnown) {
                    IconButton(onClick = { showEmailDialog = true }) {
                        Icon(painterResource(R.drawable.feddy_ic_mail), stringResource(R.string.feddy_email_add), tint = colors.text)
                    }
                }
            },
        )
        Box(Modifier.weight(1f)) {
            if (!model.loaded) {
                CircularProgressIndicator(Modifier.align(Alignment.Center).size(28.dp), color = colors.secondaryText, strokeWidth = 2.5.dp)
            }
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(dismissKeyboardOnDrag)
                    .pointerInput(Unit) { detectTapGestures { focus.clearFocus() } },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                itemsIndexed(model.parts, key = { _, part -> "part-${part.seq}" }) { index, part ->
                    val startsRun = index == 0 || model.parts[index - 1].authorRunKey != part.authorRunKey
                    MessageBubble(part, startsRun, onOpenAttachment = { viewing = it })
                }
                if (feedback != null) {
                    item(key = "feedback") {
                        FeedbackRow(feedback) { seq, helpful -> scope.launch { model.rate(seq, helpful) } }
                    }
                }
                if (showBanner) {
                    item(key = "email-ask") {
                        EmailCaptureBanner(
                            onSkip = {
                                FeddyCore.prefs.emailAskDismissed = true
                                bannerDismissed = true
                            },
                            onSaved = {
                                emailKnown = true
                                bannerDismissed = true
                            },
                        )
                    }
                }
            }
        }
        BottomBar(
            model = model,
            tray = tray,
            draft = draft,
            onDraftChange = { draft = it },
            attachmentsEnabled = config?.attachmentsEnabled ?: true,
            onSend = {
                val text = draft
                scope.launch {
                    // Clearing unconditionally would swallow anything typed while
                    // the request was in flight; a failure leaves the draft alone.
                    if (model.send(text, tray) && draft == text) draft = ""
                }
            },
        )
    }

    if (showEmailDialog) {
        EmailAskDialog(onDismiss = { showEmailDialog = false }, onSaved = {
            emailKnown = true
            showEmailDialog = false
        })
    }
    viewing?.let { AttachmentViewer(it) { viewing = null } }
}

@Composable
private fun BottomBar(
    model: ConversationDetailModel,
    tray: AttachmentTrayModel,
    draft: String,
    onDraftChange: (String) -> Unit,
    attachmentsEnabled: Boolean,
    onSend: () -> Unit,
) {
    val colors = FeddyTheme.colors
    Column(Modifier.fillMaxWidth().background(colors.surface.copy(alpha = 0.35f)).navigationBarsPadding()) {
        AnimatedVisibility(model.status == "closed", enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Text(
                stringResource(if (model.resolvedByUser) R.string.feddy_resolved_notice else R.string.feddy_closed_notice),
                color = colors.secondaryText,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        model.sendError?.let {
            Text(it, color = colors.error, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
        }
        if (attachmentsEnabled) {
            Box(Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp)) { AttachmentArea(tray, disabled = model.isSending) }
        }
        val canSend = !model.isSending && draft.isNotBlank()
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // Grows with the reply instead of scrolling one line sideways.
            FilledField(
                value = draft,
                onValueChange = onDraftChange,
                placeholder = stringResource(R.string.feddy_reply_placeholder),
                ground = colors.surface,
                maxLines = 5,
                cornerRadius = 20.dp,
                horizontalPadding = 14.dp,
                verticalPadding = 10.dp,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.weight(1f).heightIn(min = 44.dp).padding(vertical = 2.dp),
            )
            val sendAlpha by animateFloatAsState(if (canSend) 1f else 0.4f, label = "send")
            Box(
                Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable(enabled = canSend, onClickLabel = stringResource(R.string.feddy_send), onClick = onSend),
                contentAlignment = Alignment.Center,
            ) {
                if (model.isSending) {
                    CircularProgressIndicator(Modifier.size(22.dp), color = colors.accent, strokeWidth = 2.dp)
                } else {
                    Box(
                        Modifier.size(38.dp).clip(CircleShape).background(if (canSend) colors.accent else colors.surface),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painterResource(R.drawable.feddy_ic_send),
                            contentDescription = stringResource(R.string.feddy_send),
                            tint = if (canSend) colors.onAccent else colors.tertiaryText.copy(alpha = sendAlpha),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(part: Part, startsRun: Boolean, onOpenAttachment: (Attachment) -> Unit) {
    val colors = FeddyTheme.colors
    val context = LocalContext.current
    val config by FeddyCore.config.collectAsState()
    val mine = part.isFromContact
    val authorLabel = if (!mine && startsRun) part.authorName ?: config?.brand?.name else null
    val sideInset = if (mine) 0.dp else AVATAR_INSET
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = if (mine) FAR_SIDE else 0.dp, end = if (mine) 0.dp else FAR_SIDE),
        horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        authorLabel?.let {
            Text(it, color = colors.secondaryText, fontSize = 11.sp, modifier = Modifier.padding(start = AVATAR_INSET))
        }
        Row(verticalAlignment = Alignment.Top) {
            if (!mine) {
                if (startsRun) Avatar(part, config?.brand?.name) else Spacer(Modifier.size(AVATAR))
                Spacer(Modifier.width(AVATAR_GAP))
            }
            Text(
                part.body,
                color = colors.text,
                fontSize = 15.sp,
                lineHeight = 21.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (mine) colors.ownBubble else colors.surface)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
        if (part.attachments.isNotEmpty()) {
            Box(Modifier.padding(start = sideInset)) {
                // Follows the message, not a fixed side.
                AttachmentGrid(part.attachments, alignEnd = mine, onOpen = onOpenAttachment)
            }
        }
        Text(messageTime(context, part.createdAt), color = colors.secondaryText, fontSize = 11.sp, modifier = Modifier.padding(start = sideInset))
    }
}

/** A picture, or an initial on the brand colour. A bot gets a spark: an automatic answer must never read as a person called "A". */
@Composable
private fun Avatar(part: Part, brandName: String?) {
    val colors = FeddyTheme.colors
    val url = part.authorAvatarUrl
    val phase = if (url != null) rememberImage(ImageSource.Url(url), maxEdge = 256) else null
    Box(
        Modifier.size(AVATAR).clip(CircleShape).background(colors.accent).clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        if (phase is ImagePhase.Loaded) {
            Image(phase.image, null, contentScale = ContentScale.Crop, modifier = Modifier.size(AVATAR))
        } else if (part.isFromBot) {
            Icon(painterResource(R.drawable.feddy_ic_sparkles), null, tint = colors.onAccent, modifier = Modifier.size(AVATAR * 0.55f))
        } else {
            Text(brandInitial(part.authorName ?: brandName ?: "?"), color = colors.onAccent, fontSize = (AVATAR.value * 0.45f).sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

private sealed class FeedbackState {
    data class Ask(val seq: Int, val disabled: Boolean) : FeedbackState()
    data object Thanks : FeedbackState()
}

/** "Was this helpful?" under the latest auto-reply, then a thank-you once answered. */
@Composable
private fun FeedbackRow(state: FeedbackState, onAnswer: (Int, Boolean) -> Unit) {
    val colors = FeddyTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(start = AVATAR_INSET, end = FAR_SIDE),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (state) {
            is FeedbackState.Ask -> {
                Text(stringResource(R.string.feddy_feedback_prompt), color = colors.secondaryText, fontSize = 12.sp)
                SmallButton(stringResource(R.string.feddy_feedback_yes), enabled = !state.disabled) { onAnswer(state.seq, true) }
                SmallButton(stringResource(R.string.feddy_feedback_no), enabled = !state.disabled) { onAnswer(state.seq, false) }
            }
            FeedbackState.Thanks -> Text(stringResource(R.string.feddy_feedback_thanks), color = colors.secondaryText, fontSize = 12.sp)
        }
    }
}

@Composable
private fun SmallButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = FeddyTheme.colors
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 0.dp),
        modifier = Modifier.heightIn(min = 32.dp),
        interactionSource = remember { MutableInteractionSource() },
    ) { Text(label, color = colors.accent, fontSize = 13.sp) }
}
