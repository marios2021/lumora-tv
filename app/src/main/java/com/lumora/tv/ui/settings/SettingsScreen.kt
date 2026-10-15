package com.lumora.tv.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.lumora.tv.ui.common.appContainer
import com.lumora.tv.ui.common.requestFocusAfterLayout

@Composable
fun SettingsScreen() {
    val container = appContainer()
    val vm: SettingsViewModel = viewModel { SettingsViewModel(container.settings, container.autoRefresher) }
    val settings by vm.settings.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val refreshMessage by vm.refreshMessage.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val version = remember(context) {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
    }

    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { firstFocus.requestFocusAfterLayout() }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 48.dp, vertical = 32.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Press OK to change a setting.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LazyColumn(
            modifier = Modifier.widthIn(max = 900.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(top = 16.dp, bottom = 48.dp),
        ) {
            item { SectionHeader("Playback") }
            item {
                SettingRow(
                    title = "Channel up / down",
                    description = "Which way the ▲ and ▼ buttons change channel in the player.",
                    value = if (settings.invertZapping) "▲ = previous channel" else "▲ = next channel",
                    onClick = vm::toggleInvertZapping,
                    modifier = Modifier.focusRequester(firstFocus),
                )
            }
            item {
                SettingRow(
                    title = "Buffering",
                    description = "Fast start changes channel quicker; Smooth playback stalls less on slow connections. " +
                        "Applies the next time the player opens.",
                    value = settings.bufferProfile.label,
                    onClick = vm::cycleBufferProfile,
                )
            }
            item {
                SettingRow(
                    title = "Xtream Codes stream format",
                    description = "Try HLS if live channels stutter or won't start.",
                    value = settings.xtreamFormat.label,
                    onClick = vm::cycleXtreamFormat,
                )
            }
            item {
                SettingRow(
                    title = "Start on last channel",
                    description = "Open the player on the channel you last watched when the app starts.",
                    value = if (settings.startOnLastChannel) "On" else "Off",
                    onClick = vm::toggleStartOnLastChannel,
                )
            }

            item { SectionHeader("Playlists and TV guide") }
            item {
                SettingRow(
                    title = "Auto-refresh",
                    description = "Reload playlists and TV guides in the background while the app is open.",
                    value = if (settings.autoRefreshHours == 0) "Off" else "Every ${settings.autoRefreshHours} hours",
                    onClick = vm::cycleAutoRefresh,
                )
            }
            item {
                SettingRow(
                    title = "Refresh everything now",
                    description = refreshMessage ?: "Reload every playlist and its TV guide.",
                    value = if (refreshing) "Working…" else "",
                    onClick = vm::refreshAllNow,
                )
            }

            item { SectionHeader("About") }
            item {
                SettingRow(
                    title = "Lumora TV",
                    description = "Plays playlists you provide. It includes no channels of its own.",
                    value = "Version $version",
                    onClick = {},
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 20.dp, bottom = 4.dp, start = 8.dp),
    )
}

@Composable
private fun SettingRow(
    title: String,
    description: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ListItem(
        selected = false,
        onClick = onClick,
        modifier = modifier,
        headlineContent = { Text(title) },
        supportingContent = { Text(description, maxLines = 2) },
        trailingContent = {
            Text(value, style = MaterialTheme.typography.titleSmall)
        },
    )
}
