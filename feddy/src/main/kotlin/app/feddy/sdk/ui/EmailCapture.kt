package app.feddy.sdk.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.feddy.sdk.R
import app.feddy.sdk.core.EmailValidation
import app.feddy.sdk.core.FeddyCore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * The one path that turns a typed address into a saved contact email. Three
 * places ask — after the first message, the in-thread banner, the envelope
 * button — and all of them go through this. Null once saved, otherwise the
 * sentence to show.
 */
internal object EmailCapture {
    suspend fun save(context: Context, raw: String): String? {
        val trimmed = raw.trim()
        if (!EmailValidation.isValid(trimmed)) return context.getString(R.string.feddy_email_invalid)
        val client = FeddyCore.client ?: return context.getString(R.string.feddy_error_generic)
        return try {
            client.setEmail(trimmed)
            FeddyCore.prefs.emailKnown = true
            null
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            context.getString(R.string.feddy_error_generic)
        }
    }
}

private val emailKeyboard = KeyboardOptions(
    capitalization = KeyboardCapitalization.None,
    autoCorrectEnabled = false,
    keyboardType = KeyboardType.Email,
    imeAction = ImeAction.Done,
)

/** The address field every ask uses. */
@Composable
private fun EmailField(value: String, onChange: (String) -> Unit, ground: Color) {
    FilledField(
        value = value,
        onValueChange = onChange,
        placeholder = stringResource(R.string.feddy_email_placeholder),
        ground = ground,
        singleLine = true,
        keyboardOptions = emailKeyboard,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * After the first message: confirm it went out, then offer email
 * notifications, always skippable. Skipping is honoured for good.
 */
@Composable
internal fun SubmittedView(onContinue: () -> Unit) {
    val colors = FeddyTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val config by FeddyCore.config.collectAsState()
    val sla = config?.replySlaText
    Column(
        Modifier.fillMaxSize().background(colors.page).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
    ) {
        Icon(painterResource(R.drawable.feddy_ic_check_circle), null, tint = colors.accent, modifier = Modifier.size(52.dp))
        Text(
            if (sla.isNullOrEmpty()) stringResource(R.string.feddy_submitted_fallback) else sla,
            color = colors.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
        )
        Text(stringResource(R.string.feddy_email_prompt_title), color = colors.secondaryText, fontSize = 14.sp, textAlign = TextAlign.Center)
        EmailField(email, { email = it; error = null }, colors.surface)
        error?.let { Text(it, color = colors.error, fontSize = 12.sp) }
        Button(
            onClick = {
                saving = true
                scope.launch {
                    val message = EmailCapture.save(context, email)
                    saving = false
                    if (message == null) onContinue() else error = message
                }
            },
            enabled = !saving && email.isNotBlank(),
            colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = colors.onAccent),
        ) { Text(stringResource(R.string.feddy_email_save)) }
        TextButton(onClick = {
            FeddyCore.prefs.emailAskDismissed = true
            onContinue()
        }) { Text(stringResource(R.string.feddy_email_skip), color = colors.secondaryText) }
    }
}

/**
 * Second-chance ask inside a thread: a teammate replied but there is no
 * email for this contact yet. Sits on the teammate's side.
 */
@Composable
internal fun EmailCaptureBanner(onSkip: () -> Unit, onSaved: () -> Unit) {
    val colors = FeddyTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(end = 24.dp, top = 2.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surface)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(R.string.feddy_email_prompt_title), color = colors.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        EmailField(email, { email = it; error = null }, colors.page)
        error?.let { Text(it, color = colors.error, fontSize = 12.sp) }
        // Trailing, with the primary action last.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            OutlinedButton(onClick = onSkip) { Text(stringResource(R.string.feddy_email_skip), color = colors.secondaryText) }
            Button(
                onClick = {
                    scope.launch {
                        val message = EmailCapture.save(context, email)
                        if (message == null) onSaved() else error = message
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = colors.onAccent),
            ) { Text(stringResource(R.string.feddy_email_save)) }
        }
    }
}

/** The way back to the ask once declined: the thread's envelope button opens this. */
@Composable
internal fun EmailAskDialog(onDismiss: () -> Unit, onSaved: () -> Unit) {
    val colors = FeddyTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.page,
        title = { Text(stringResource(R.string.feddy_email_prompt_title), fontSize = 18.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                EmailField(email, { email = it; error = null }, colors.surface)
                error?.let { Text(it, color = colors.error, fontSize = 12.sp) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !saving && email.isNotBlank(),
                onClick = {
                    saving = true
                    scope.launch {
                        val message = EmailCapture.save(context, email)
                        saving = false
                        if (message == null) onSaved() else error = message
                    }
                },
            ) { Text(stringResource(R.string.feddy_email_save), color = colors.accent) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.feddy_cancel), color = colors.secondaryText) }
        },
    )
}
