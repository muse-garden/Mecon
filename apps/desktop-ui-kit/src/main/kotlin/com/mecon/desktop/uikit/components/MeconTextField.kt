package com.mecon.desktop.uikit.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import com.mecon.desktop.uikit.theme.MeconColors

/** Window shortcut gate shared by every canonical Mecon text input. */
object MeconTextInputFocus {
    private val focusedTokens = mutableSetOf<Any>()
    val hasFocus: Boolean get() = focusedTokens.isNotEmpty()

    internal fun update(token: Any, focused: Boolean) {
        if (focused) focusedTokens += token else focusedTokens -= token
    }
}

/** Marks any styled Compose text editor as an input focus owner for window shortcut gating. */
fun Modifier.meconTextInputFocus(): Modifier = composed {
    val focusToken = remember { Any() }
    DisposableEffect(focusToken) {
        onDispose { MeconTextInputFocus.update(focusToken, false) }
    }
    this.onFocusChanged { MeconTextInputFocus.update(focusToken, it.hasFocus) }
}

/** Canonical outlined text input for the desktop UI. */
@Composable
fun MeconTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth(),
    label: String? = null,
    singleLine: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    /** Optional transactional commit, invoked by Enter or when focus leaves the field. */
    onCommit: ((String) -> Unit)? = null,
) {
    var hadFocus by remember { mutableStateOf(false) }
    val currentValue by rememberUpdatedState(value)
    val currentCommit by rememberUpdatedState(onCommit)
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = label?.let { { Text(it, fontSize = 11.sp) } },
        singleLine = singleLine,
        keyboardOptions = keyboardOptions,
        textStyle = TextStyle(fontSize = 12.sp),
        modifier = modifier
            .meconTextInputFocus()
            .onFocusChanged { state ->
                val focused = state.hasFocus
                if (hadFocus && !focused) currentCommit?.invoke(currentValue)
                hadFocus = focused
            }
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Enter && currentCommit != null) {
                    currentCommit?.invoke(currentValue)
                    true
                } else false
            },
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MeconColors.InputBackground,
            unfocusedContainerColor = MeconColors.InputBackground,
            focusedBorderColor = MeconColors.Primary,
            unfocusedBorderColor = MeconColors.BorderLight,
            focusedTextColor = MeconColors.TextPrimary,
            unfocusedTextColor = MeconColors.TextPrimary,
            focusedLabelColor = MeconColors.TextSecondary,
            unfocusedLabelColor = MeconColors.TextMuted,
            cursorColor = MeconColors.Primary,
        ),
    )
}
