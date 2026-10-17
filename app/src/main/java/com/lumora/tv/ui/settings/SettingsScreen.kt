package com.lumora.tv.ui.settings

import android.content.ActivityNotFoundException
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lumora.tv.data.update.UpdateState
import com.lumora.tv.ui.common.Button
import com.lumora.tv.ui.common.FormField
import com.lumora.tv.ui.common.ListItem
import com.lumora.tv.ui.common.OutlinedButton
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.lumora.tv.ui.common.LocalIsTv
import com.lumora.tv.ui.common.NewPinDialog
import com.lumora.tv.ui.common.PinDialog
import com.lumora.tv.ui.common.appContainer
import com.lumora.tv.ui.common.requestFocusAfterLayout

@Composable
fun SettingsScreen() {
    val container = appContainer()
    val vm: SettingsViewModel = viewModel {
        SettingsViewModel(container.settings, container.autoRefresher, container.parental, container.updater)
    }
    val settings by vm.settings.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val refreshMessage by vm.refreshMessage.collectAsStateWithLifecycle()
    val pinSet by vm.pinSet.collectAsStateWithLifecycle()
    val unlocked by vm.unlocked.collectAsStateWithLifecycle()
    val update by vm.update.collectAsStateWithLifecycle()
    var editingUpdateSource by remember { mutableStateOf(false) }
    // Back from Android's "Install unknown apps" screen: the update carries on if it was allowed.
    LifecycleResumeEffect(vm) {
        vm.onResume()
        onPauseOrDispose { }
    }
    var pinFlow by remember { mutableStateOf<PinFlow?>(null) }
    // Removing the PIN also removes the focused row: move focus to the PIN row instead.
    val pinRowFocus = remember { FocusRequester() }
    var refocusPinRow by remember { mutableStateOf(false) }
    LaunchedEffect(refocusPinRow) {
        if (refocusPinRow) {
            pinRowFocus.requestFocusAfterLayout()
            refocusPinRow = false
        }
    }
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
            if (LocalIsTv.current) "Press OK to change a setting." else "Tap a setting to change it.",
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
                        "After a stall, live TV keeps a bigger buffer for the rest of the channel. " +
                        "Applies the next time the player opens.",
                    value = settings.bufferProfile.label,
                    onClick = vm::cycleBufferProfile,
                )
            }
            item {
                SettingRow(
                    title = "Video output",
                    description = "If you hear sound but the picture stays black, try Compatibility. " +
                        "Automatic uses Compatibility on phones and tablets and Standard on TVs.",
                    value = settings.videoOutput.label,
                    onClick = vm::cycleVideoOutput,
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

            item { SectionHeader("Parental control") }
            item {
                SettingRow(
                    title = "Parental PIN",
                    description = if (LocalIsTv.current) {
                        "Hold OK on a group in the channel list to lock it. Locked groups are hidden until the PIN is entered."
                    } else {
                        "Long-press a group in the channel list to lock it. Locked groups are hidden until the PIN is entered."
                    },
                    value = if (pinSet) "Change" else "Set PIN",
                    onClick = { pinFlow = if (pinSet) PinFlow.CHANGE else PinFlow.NEW },
                    modifier = Modifier.focusRequester(pinRowFocus),
                )
            }
            if (pinSet) {
                item {
                    SettingRow(
                        title = "Remove PIN",
                        description = "Removes the PIN and every lock.",
                        value = "",
                        onClick = { pinFlow = PinFlow.REMOVE },
                    )
                }
            }
            if (pinSet) {
                item {
                    SettingRow(
                        title = "Locked groups",
                        description = if (unlocked) {
                            "Visible until you hide them again or close the app."
                        } else {
                            "Hidden. Enter the PIN to show them until the app is closed."
                        },
                        value = if (unlocked) "Visible" else "Hidden",
                        onClick = { if (unlocked) vm.lockAgain() else pinFlow = PinFlow.UNLOCK },
                    )
                }
            }

            item { SectionHeader("Updates") }
            item {
                SettingRow(
                    title = "Update address",
                    description = settings.updateUrl.ifBlank {
                        "The address of the update.json file published with each version (see the README). " +
                            "Lumora TV then updates itself without a cable."
                    },
                    value = if (settings.updateUrl.isBlank()) "Not set" else "Change",
                    onClick = { editingUpdateSource = true },
                )
            }
            item {
                SettingRow(
                    title = "Check for updates",
                    description = when (val u = update) {
                        UpdateState.Checking -> "Checking…"
                        is UpdateState.UpToDate -> "You have the newest version (${u.versionName})."
                        is UpdateState.Failed -> if (u.info == null) u.message else "You have version ${vm.installedVersionName}."
                        else -> "You have version ${vm.installedVersionName}."
                    },
                    value = if (update == UpdateState.Checking) "Checking…" else "Check now",
                    onClick = vm::checkForUpdates,
                )
            }
            val found = when (val u = update) {
                is UpdateState.Available -> u.info
                is UpdateState.Downloading -> u.info
                is UpdateState.Installing -> u.info
                is UpdateState.NeedsPermission -> u.info
                is UpdateState.Failed -> u.info
                else -> null
            }
            if (found != null) {
                item {
                    SettingRow(
                        title = "Update available: ${found.versionName}",
                        description = when (val u = update) {
                            is UpdateState.Downloading -> "Downloading…"
                            is UpdateState.Installing -> "Android asks you to confirm, then Lumora TV restarts as the new version."
                            is UpdateState.Failed -> u.message
                            else -> found.notes.ifBlank { "Download and install it now. Your playlists and settings are kept." }
                        },
                        value = when (val u = update) {
                            is UpdateState.Downloading -> u.progress?.let { "${(it * 100).toInt()} %" } ?: "Downloading…"
                            is UpdateState.Installing -> "Installing…"
                            is UpdateState.Failed -> "Try again"
                            else -> "Install"
                        },
                        onClick = { if (update !is UpdateState.Downloading) vm.installUpdate(found) },
                    )
                }
            }
            item {
                SettingRow(
                    title = "Check automatically",
                    description = "Once a day while the app is open, when an update address is set.",
                    value = if (settings.autoUpdateCheck) "On" else "Off",
                    onClick = vm::toggleAutoUpdateCheck,
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

    if (editingUpdateSource) {
        UpdateSourceDialog(
            url = settings.updateUrl,
            token = settings.updateToken,
            onDismiss = { editingUpdateSource = false },
        ) { url, token ->
            vm.setUpdateSource(url, token)
            editingUpdateSource = false
        }
    }
    (update as? UpdateState.NeedsPermission)?.let { waiting ->
        InstallPermissionDialog(
            versionName = waiting.info.versionName,
            onDismiss = vm::dismissPermissionRequest,
            onOpenSettings = {
                try {
                    context.startActivity(vm.permissionScreenIntent())
                } catch (e: ActivityNotFoundException) {
                    Toast.makeText(context, "Open Android Settings and allow Lumora TV to install unknown apps.", Toast.LENGTH_LONG).show()
                }
            },
        )
    }

    // Changing or removing the PIN asks for the current one first.
    var verified by remember(pinFlow) { mutableStateOf(false) }
    when (pinFlow) {
        null -> Unit
        PinFlow.NEW -> NewPinDialog(onDismiss = { pinFlow = null }) { pin ->
            vm.setPin(pin)
            pinFlow = null
        }
        PinFlow.UNLOCK -> PinDialog(title = "Enter parental PIN", message = null, onDismiss = { pinFlow = null }) { pin ->
            if (vm.unlock(pin)) {
                pinFlow = null
                null
            } else {
                "Wrong PIN"
            }
        }
        PinFlow.CHANGE, PinFlow.REMOVE -> if (!verified) {
            PinDialog(title = "Enter current PIN", message = null, onDismiss = { pinFlow = null }) { pin ->
                if (!vm.checkPin(pin)) return@PinDialog "Wrong PIN"
                if (pinFlow == PinFlow.REMOVE) {
                    vm.removePin()
                    pinFlow = null
                    refocusPinRow = true
                } else {
                    verified = true
                }
                null
            }
        } else {
            NewPinDialog(onDismiss = { pinFlow = null }) { pin ->
                vm.setPin(pin)
                pinFlow = null
            }
        }
    }
}

private enum class PinFlow { NEW, CHANGE, REMOVE, UNLOCK }

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

@Composable
private fun UpdateSourceDialog(
    url: String,
    token: String,
    onDismiss: () -> Unit,
    onSave: (url: String, token: String) -> Unit,
) {
    var newUrl by remember { mutableStateOf(url) }
    var newToken by remember { mutableStateOf(token) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.requestFocusAfterLayout() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier = Modifier
                .width(600.dp)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
                .padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Update address", style = MaterialTheme.typography.titleLarge)
            Text(
                "Where the update.json file of each new version is published, for example " +
                    "https://github.com/<you>/<repo>/releases/latest/download/update.json. Leave it empty to turn updates off.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FormField(
                label = "Update file address",
                value = newUrl,
                onValueChange = { newUrl = it },
                keyboardType = KeyboardType.Uri,
                modifier = Modifier.focusRequester(first),
            )
            FormField(
                label = "GitHub access token (private repositories only)",
                value = newToken,
                onValueChange = { newToken = it },
                isPassword = true,
                imeAction = ImeAction.Done,
                keyboardActions = KeyboardActions(onDone = { onSave(newUrl, newToken) }),
            )
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { onSave(newUrl, newToken) }) { Text("Save") }
                OutlinedButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    }
}

@Composable
private fun InstallPermissionDialog(versionName: String, onDismiss: () -> Unit, onOpenSettings: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.requestFocusAfterLayout() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier = Modifier
                .width(560.dp)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
                .padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Allow Lumora TV to install updates", style = MaterialTheme.typography.titleLarge)
            Text(
                "Android only lets an app install $versionName after you allow it once. On the next screen, turn on " +
                    "“Allow from this source” for Lumora TV (on some TVs: Security → Unknown sources → Lumora TV), " +
                    "then press Back. The update then continues here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onOpenSettings, modifier = Modifier.focusRequester(first)) { Text("Open settings") }
                OutlinedButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    }
}
