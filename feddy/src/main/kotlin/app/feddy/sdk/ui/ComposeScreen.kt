package app.feddy.sdk.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.feddy.sdk.R
import app.feddy.sdk.core.FeddyConfig
import app.feddy.sdk.core.FeddyCore
import kotlinx.coroutines.launch

/**
 * The new-message form (topic + body + images), then the optional email ask.
 * Email is never a required field before sending.
 */
@Composable
internal fun ComposeScreen(onCancel: () -> Unit, onCreated: (String) -> Unit) {
    val context = LocalContext.current
    val model = remember { ComposeModel(context) }
    val tray = remember { AttachmentTrayModel(context) }
    val created = model.createdConversationId
    if (created != null) {
        // Someone who declined once is not asked again; a project that
        // cannot email replies never asks at all.
        val prefs = FeddyCore.prefs
        if (!FeddyCore.emailCaptureEnabled || prefs.emailKnown || prefs.emailAskDismissed) {
            LaunchedEffect(created) { onCreated(created) }
        } else {
            Column(Modifier.fillMaxSize().background(FeddyTheme.colors.page)) {
                FeddyTopBar(null, R.drawable.feddy_ic_close, stringResource(R.string.feddy_close), { onCreated(created) })
                Box(Modifier.weight(1f).imePadding()) { SubmittedView { onCreated(created) } }
            }
        }
        return
    }
    ComposeForm(model, tray, onCancel)
}

@Composable
private fun ComposeForm(model: ComposeModel, tray: AttachmentTrayModel, onCancel: () -> Unit) {
    val colors = FeddyTheme.colors
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val config by FeddyCore.config.collectAsState()
    // Never empty: a topic is required, so a failed config fetch falls back
    // to the seeded set rather than leaving nothing to pick.
    val categories = config?.categories?.takeIf { it.isNotEmpty() } ?: FeddyConfig.fallbackCategories
    val hasText = model.text.isNotBlank()
    // Something is written and the topic is the only thing left in the way:
    // the one moment a disabled send button needs explaining.
    val topicIsBlocking = hasText && model.categoryCode == null

    Column(Modifier.fillMaxSize().background(colors.page).imePadding()) {
        // A form cancels; only the root panel closes.
        FeddyTopBar(
            title = stringResource(R.string.feddy_new_message),
            navigationIcon = R.drawable.feddy_ic_close,
            navigationLabel = stringResource(R.string.feddy_cancel),
            onNavigate = onCancel,
        )
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { focus.clearFocus() }
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val hintColor by animateColorAsState(if (topicIsBlocking) colors.accent else colors.secondaryText, label = "topicHint")
                // The label doubles as the empty-state prompt.
                Text(
                    stringResource(if (model.categoryCode == null) R.string.feddy_topic_choose else R.string.feddy_topic),
                    color = hintColor,
                    fontSize = 13.sp,
                )
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    categories.forEach { category ->
                        TopicChip(
                            label = categoryLabel(category),
                            selected = model.categoryCode == category.code,
                            onClick = {
                                focus.clearFocus()
                                model.categoryCode = category.code
                            },
                        )
                    }
                }
            }
            FilledField(
                value = model.text,
                onValueChange = { model.text = it },
                placeholder = stringResource(R.string.feddy_compose_placeholder),
                ground = colors.surface,
                cornerRadius = 12.dp,
                horizontalPadding = 14.dp,
                verticalPadding = 12.dp,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                enabled = !model.isSubmitting,
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
            if (config?.attachmentsEnabled ?: true) {
                AttachmentArea(tray, disabled = model.isSubmitting)
            }
            model.submitError?.let { Text(it, color = colors.error, fontSize = 13.sp) }
        }
        // Same ground as the page, parted by a hairline.
        HorizontalDivider(color = colors.hairline, thickness = 0.5.dp)
        Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp)) {
            val enabled = model.categoryCode != null && hasText
            Button(
                onClick = { scope.launch { model.submit(tray) } },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.accent,
                    contentColor = colors.onAccent,
                    // Submitting keeps the filled look so the spinner stays legible.
                    disabledContainerColor = if (model.isSubmitting) colors.accent else colors.surface,
                    disabledContentColor = if (model.isSubmitting) colors.onAccent else colors.tertiaryText,
                ),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    // Kept in place, merely hidden: the label gives the button its height.
                    Text(stringResource(R.string.feddy_send), fontSize = 16.sp, modifier = Modifier.alpha(if (model.isSubmitting) 0f else 1f))
                    if (model.isSubmitting) CircularProgressIndicator(Modifier.size(20.dp), color = colors.onAccent, strokeWidth = 2.dp)
                }
            }
        }
    }
}

@Composable
private fun TopicChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = FeddyTheme.colors
    val background by animateColorAsState(if (selected) colors.accent else colors.surface, label = "chip")
    val foreground by animateColorAsState(if (selected) colors.onAccent else colors.text, label = "chipText")
    Text(
        label,
        color = foreground,
        fontSize = 14.sp,
        modifier = Modifier
            .clip(CircleShape)
            .background(background)
            .semantics { this.selected = selected }
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

/**
 * The topics every project is seeded with have English labels in the
 * project row, which cannot follow the user's language, so the SDK supplies
 * its own translation for the seeded codes. Topics the developer added keep
 * the label they typed.
 */
@Composable
internal fun categoryLabel(category: FeddyConfig.Category): String = when (category.code) {
    "bug" -> stringResource(R.string.feddy_category_bug)
    "feature" -> stringResource(R.string.feddy_category_feature)
    "question" -> stringResource(R.string.feddy_category_question)
    "billing" -> stringResource(R.string.feddy_category_billing)
    "other" -> stringResource(R.string.feddy_category_other)
    else -> category.label
}
