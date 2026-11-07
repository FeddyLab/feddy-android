package app.feddy.sdk.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.feddy.sdk.core.FeddyCore
import app.feddy.sdk.core.Foreground

/**
 * The support screens, in an activity of the SDK's own. The host hands over
 * nothing but a Context, so this works the same from a Compose app, a Views
 * app, or a Flutter or React Native app whose single activity is a plain
 * `Activity` with no Compose in it.
 */
internal class FeddyActivity : ComponentActivity() {
    enum class Start { Home, Compose }

    private val pendingConversation = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // After process death the system can restore this screen before the
        // host has configured the SDK again; there is nothing to show then.
        if (!FeddyCore.isConfigured) {
            finish()
            return
        }
        val start = intent.getStringExtra(EXTRA_START)?.let { runCatching { Start.valueOf(it) }.getOrNull() } ?: Start.Home
        val conversationId = intent.getStringExtra(EXTRA_CONVERSATION)
        setContent {
            FeddyTheme {
                FeddyApp(start, conversationId, pendingConversation, onClose = { finish() })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_CONVERSATION)?.let { pendingConversation.value = it }
    }

    override fun onResume() {
        super.onResume()
        visible += 1
        // The panel shows the same replies; a card on top of it says nothing.
        ReplyPopupCoordinator.hide()
    }

    override fun onPause() {
        super.onPause()
        visible = maxOf(0, visible - 1)
    }

    override fun onStop() {
        super.onStop()
        // Pick up read state for the host's badge.
        FeddyCore.refresh()
    }

    companion object {
        private const val EXTRA_START = "app.feddy.sdk.start"
        private const val EXTRA_CONVERSATION = "app.feddy.sdk.conversation"

        /** Resumed instances; the reply popup stays away while one is up. */
        var visible = 0
            private set

        fun open(context: Context, start: Start, conversationId: String? = null) {
            if (!FeddyCore.isConfigured) {
                FeddyCore.warn("present() called before configure()")
                return
            }
            Foreground.noteActivity(context)
            ReplyPopupCoordinator.hide()
            val intent = Intent(context, FeddyActivity::class.java)
                .putExtra(EXTRA_START, start.name)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            conversationId?.let { intent.putExtra(EXTRA_CONVERSATION, it) }
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }
}

private sealed class Screen {
    data object Home : Screen()
    data object All : Screen()
    data object Compose : Screen()
    data class Conversation(val id: String) : Screen()

    val key: String
        get() = when (this) {
            Home -> "home"
            All -> "all"
            Compose -> "compose"
            is Conversation -> "conversation:$id"
        }
}

@Composable
private fun FeddyApp(
    start: FeddyActivity.Start,
    initialConversation: String?,
    pendingConversation: androidx.compose.runtime.MutableState<String?>,
    onClose: () -> Unit,
) {
    val listModel = remember { ConversationListModel() }
    val stack = remember {
        mutableStateListOf<Screen>(Screen.Home).apply {
            if (start == FeddyActivity.Start.Compose) add(Screen.Compose)
            initialConversation?.let { add(Screen.Conversation(it)) }
        }
    }
    var forward by remember { mutableStateOf(true) }
    val saveable = rememberSaveableStateHolder()

    fun push(screen: Screen) {
        forward = true
        stack.add(screen)
    }
    fun pop() {
        if (stack.size <= 1) {
            onClose()
            return
        }
        forward = false
        val leaving = stack.removeAt(stack.lastIndex)
        saveable.removeState(leaving.key)
    }

    LaunchedEffect(Unit) {
        FeddyCore.loadConfig()
        listModel.load()
        listModel.pollLoop()
    }
    // The popup was tapped while this was already open.
    LaunchedEffect(pendingConversation.value) {
        val id = pendingConversation.value ?: return@LaunchedEffect
        pendingConversation.value = null
        if ((stack.lastOrNull() as? Screen.Conversation)?.id != id) push(Screen.Conversation(id))
    }

    BackHandler(enabled = true) { pop() }

    val current = stack.last()
    LaunchedEffect(current) {
        when (current) {
            // Clear the row's dot at once; the thread reports the read itself.
            is Screen.Conversation -> listModel.markReadLocally(current.id)
            // Back on a list: pick up read state and any new replies.
            Screen.Home, Screen.All -> listModel.load()
            Screen.Compose -> Unit
        }
    }

    Box(Modifier.fillMaxSize().background(FeddyTheme.colors.page).statusBarsPadding()) {
        AnimatedContent(
            targetState = current,
            transitionSpec = {
                val duration = 280
                if (forward) {
                    (slideInHorizontally(tween(duration)) { it / 3 } + fadeIn(tween(duration)))
                        .togetherWith(slideOutHorizontally(tween(duration)) { -it / 6 } + fadeOut(tween(duration / 2)))
                } else {
                    (slideInHorizontally(tween(duration)) { -it / 6 } + fadeIn(tween(duration)))
                        .togetherWith(slideOutHorizontally(tween(duration)) { it / 3 } + fadeOut(tween(duration / 2)))
                }
            },
            label = "screens",
        ) { screen ->
            saveable.SaveableStateProvider(screen.key) {
                when (screen) {
                    Screen.Home -> HomeScreen(
                        model = listModel,
                        onClose = onClose,
                        onNewMessage = { push(Screen.Compose) },
                        onOpen = { push(Screen.Conversation(it)) },
                        onSeeAll = { push(Screen.All) },
                    )
                    Screen.All -> ConversationListScreen(listModel, onBack = { pop() }, onOpen = { push(Screen.Conversation(it)) })
                    Screen.Compose -> ComposeScreen(
                        onCancel = { pop() },
                        onCreated = { id ->
                            // The form gives way to the new thread: back goes home, not to an empty form.
                            forward = true
                            val composeIndex = stack.indexOfLast { it == Screen.Compose }
                            if (composeIndex >= 0) stack[composeIndex] = Screen.Conversation(id) else stack.add(Screen.Conversation(id))
                            saveable.removeState(Screen.Compose.key)
                        },
                    )
                    is Screen.Conversation -> ConversationScreen(screen.id, onBack = { pop() })
                }
            }
        }
    }
}
