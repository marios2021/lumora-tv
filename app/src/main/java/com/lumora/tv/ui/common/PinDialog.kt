package com.lumora.tv.ui.common

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.launch

const val PIN_LENGTH = 4

/**
 * A 4-digit PIN pad. Works with the on-screen keys, a remote's number keys, or touch.
 * [onPin] receives the complete PIN and returns null to accept it, or an error to show.
 */
@Composable
fun PinDialog(
    title: String,
    message: String?,
    onDismiss: () -> Unit,
    onPin: suspend (String) -> String?,
) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun type(digit: Int) {
        if (checking || pin.length >= PIN_LENGTH) return
        pin += digit
        error = null
        if (pin.length == PIN_LENGTH) {
            checking = true
            val entered = pin
            scope.launch {
                val problem = onPin(entered)
                if (problem != null) {
                    error = problem
                    pin = ""
                }
                checking = false
            }
        }
    }

    fun erase() {
        if (!checking) pin = pin.dropLast(1)
    }

    val firstKey = remember { FocusRequester() }
    LaunchedEffect(Unit) { firstKey.requestFocusAfterLayout() }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
                .padding(horizontal = 40.dp, vertical = 28.dp)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val code = event.key.nativeKeyCode
                    when (code) {
                        in AndroidKeyEvent.KEYCODE_0..AndroidKeyEvent.KEYCODE_9 -> type(code - AndroidKeyEvent.KEYCODE_0)
                        in AndroidKeyEvent.KEYCODE_NUMPAD_0..AndroidKeyEvent.KEYCODE_NUMPAD_9 ->
                            type(code - AndroidKeyEvent.KEYCODE_NUMPAD_0)
                        AndroidKeyEvent.KEYCODE_DEL -> erase()
                        else -> return@onPreviewKeyEvent false
                    }
                    true
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            if (message != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(320.dp),
                )
            }
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                repeat(PIN_LENGTH) { i ->
                    val filled = i < pin.length
                    Box(
                        Modifier
                            .size(16.dp)
                            .then(
                                if (filled) {
                                    Modifier.background(MaterialTheme.colorScheme.primary, CircleShape)
                                } else {
                                    Modifier.border(2.dp, MaterialTheme.colorScheme.onSurfaceVariant, CircleShape)
                                },
                            ),
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                error ?: " ",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(10.dp))
            val rows = listOf(listOf(1, 2, 3), listOf(4, 5, 6), listOf(7, 8, 9))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                rows.forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { digit ->
                            PadKey(
                                onClick = { type(digit) },
                                modifier = if (digit == 1) Modifier.focusRequester(firstKey) else Modifier,
                            ) { Text(digit.toString(), style = MaterialTheme.typography.titleLarge) }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PadKey(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Cancel") }
                    PadKey(onClick = { type(0) }) { Text("0", style = MaterialTheme.typography.titleLarge) }
                    PadKey(onClick = ::erase) { Icon(Icons.AutoMirrored.Filled.Backspace, contentDescription = "Delete") }
                }
            }
        }
    }
}

@Composable
private fun PadKey(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Button(onClick = onClick, modifier = modifier.size(width = 72.dp, height = 52.dp)) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { content() }
    }
}

/** Choosing a new PIN: enter it, then again to confirm. [onSet] gets the confirmed PIN. */
@Composable
fun NewPinDialog(onDismiss: () -> Unit, onSet: suspend (String) -> Unit) {
    var first by remember { mutableStateOf<String?>(null) }
    var mismatch by remember { mutableStateOf(false) }
    val chosen = first
    if (chosen == null) {
        PinDialog(
            title = "Choose a parental PIN",
            message = if (mismatch) "The PINs didn't match. Choose a PIN again." else "4 digits. You'll need it to unlock locked groups.",
            onDismiss = onDismiss,
        ) { pin ->
            first = pin
            null
        }
    } else {
        // A new key so the second pad starts empty.
        androidx.compose.runtime.key("confirm") {
            PinDialog(title = "Enter the PIN again", message = null, onDismiss = onDismiss) { pin ->
                if (pin == chosen) {
                    onSet(pin)
                } else {
                    mismatch = true
                    first = null
                }
                null
            }
        }
    }
}
