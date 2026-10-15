package com.lumora.tv.ui.common

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.tv.material3.ButtonColors
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.OutlinedButtonDefaults
import androidx.tv.material3.Button as TvButton
import androidx.tv.material3.IconButton as TvIconButton
import androidx.tv.material3.ListItem as TvListItem
import androidx.tv.material3.OutlinedButton as TvOutlinedButton

/*
 * TV Material components react only to a remote's OK button and ignore touch. These wrappers keep
 * the same names and remote behaviour and add tap (= OK) and long-press (= hold OK), so the app
 * also works on phones, tablets and with the mouse in an emulator. Screens import these instead
 * of the androidx.tv.material3 versions.
 */

/** Tap runs [onClick]; long-press runs [onLongClick] when given. */
fun Modifier.touchClick(
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier = composed {
    val click by rememberUpdatedState(onClick)
    val longClick by rememberUpdatedState(onLongClick)
    if (!enabled) {
        Modifier
    } else {
        Modifier.pointerInput(Unit) {
            detectTapGestures(
                onTap = { click() },
                onLongPress = { longClick?.invoke() ?: click() },
            )
        }
    }
}

@Composable
fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.colors(),
    content: @Composable RowScope.() -> Unit,
) = TvButton(
    onClick = onClick,
    modifier = modifier.touchClick(enabled, onClick = onClick),
    enabled = enabled,
    colors = colors,
    content = content,
)

@Composable
fun OutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = OutlinedButtonDefaults.colors(),
    content: @Composable RowScope.() -> Unit,
) = TvOutlinedButton(
    onClick = onClick,
    modifier = modifier.touchClick(enabled, onClick = onClick),
    enabled = enabled,
    colors = colors,
    content = content,
)

@Composable
fun IconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) = TvIconButton(
    onClick = onClick,
    modifier = modifier.touchClick(onClick = onClick),
    content = content,
)

@Composable
fun ListItem(
    selected: Boolean,
    onClick: () -> Unit,
    headlineContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    supportingContent: (@Composable () -> Unit)? = null,
    leadingContent: (@Composable BoxScope.() -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
) = TvListItem(
    selected = selected,
    onClick = onClick,
    headlineContent = headlineContent,
    modifier = modifier.touchClick(onLongClick = onLongClick, onClick = onClick),
    onLongClick = onLongClick,
    supportingContent = supportingContent,
    leadingContent = leadingContent,
    trailingContent = trailingContent,
)
