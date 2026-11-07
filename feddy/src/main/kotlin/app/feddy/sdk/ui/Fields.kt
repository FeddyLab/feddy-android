package app.feddy.sdk.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A text field on a filled surface, with no outline: filled surfaces carry
 * their own edge, the way the system's own search fields do.
 */
@Composable
internal fun FilledField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    ground: Color,
    modifier: Modifier = Modifier,
    singleLine: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    cornerRadius: Dp = 10.dp,
    horizontalPadding: Dp = 12.dp,
    verticalPadding: Dp = 10.dp,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    enabled: Boolean = true,
) {
    val colors = FeddyTheme.colors
    val style = TextStyle(color = colors.text, fontSize = 16.sp, lineHeight = 22.sp)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        textStyle = style,
        cursorBrush = SolidColor(colors.accent),
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(ground),
        decorationBox = { inner ->
            Box(Modifier.padding(horizontal = horizontalPadding, vertical = verticalPadding)) {
                if (value.isEmpty()) Text(placeholder, style = style.copy(color = colors.secondaryText))
                inner()
            }
        },
    )
}
