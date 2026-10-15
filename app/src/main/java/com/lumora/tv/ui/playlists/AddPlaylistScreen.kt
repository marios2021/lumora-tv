package com.lumora.tv.ui.playlists

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import com.lumora.tv.data.db.PlaylistEntity
import com.lumora.tv.data.db.PlaylistType
import com.lumora.tv.data.remote.XtreamClient
import com.lumora.tv.ui.common.appContainer
import com.lumora.tv.ui.common.requestFocusAfterLayout
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import androidx.compose.material3.Text as M3Text

@Composable
fun AddPlaylistScreen(
    /** Null to add a new playlist, otherwise the playlist to edit. */
    editId: Long?,
    onDone: () -> Unit,
) {
    val container = appContainer()
    val vm: AddPlaylistViewModel = viewModel {
        AddPlaylistViewModel(container.playlists, container.settings, container.epg, editId)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val original by vm.original.collectAsStateWithLifecycle()
    val editing = editId != null
    val context = LocalContext.current

    var type by rememberSaveable { mutableStateOf(PlaylistType.M3U_URL) }
    var name by rememberSaveable { mutableStateOf("") }
    var m3uUrl by rememberSaveable { mutableStateOf("") }
    var epgUrl by rememberSaveable { mutableStateOf("") }
    var fileUri by rememberSaveable { mutableStateOf<String?>(null) }
    var fileName by rememberSaveable { mutableStateOf<String?>(null) }
    var server by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(state.doneId) { if (state.doneId != null) onDone() }

    // Editing: fill the form once from the stored playlist.
    var prefilled by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(original) {
        val p = original ?: return@LaunchedEffect
        if (prefilled) return@LaunchedEffect
        type = p.type
        name = p.name
        epgUrl = p.epgUrl.orEmpty()
        when (p.type) {
            PlaylistType.M3U_URL -> m3uUrl = p.url
            PlaylistType.M3U_FILE -> {
                fileUri = p.url
                fileName = displayName(context, Uri.parse(p.url)) ?: "Current file"
            }
            PlaylistType.XTREAM -> {
                server = p.url
                username = p.username.orEmpty()
                password = p.password.orEmpty()
            }
        }
        prefilled = true
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            fileUri = uri.toString()
            fileName = displayName(context, uri)
        }
    }

    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(prefilled) { if (!editing || prefilled) firstFocus.requestFocusAfterLayout() }
    val firstFieldModifier = if (editing) Modifier.focusRequester(firstFocus) else Modifier

    fun submit() {
        val draft = when (type) {
            PlaylistType.M3U_URL -> {
                val url = m3uUrl.trim()
                val host = url.toHttpUrlOrNull()?.host
                    ?: return vm.showError("Enter a valid playlist URL starting with http:// or https://")
                PlaylistEntity(name = name.trim().ifEmpty { host }, type = type, url = url)
            }
            PlaylistType.M3U_FILE -> {
                val uri = fileUri ?: return vm.showError("Choose a playlist file first.")
                PlaylistEntity(name = name.trim().ifEmpty { fileName ?: "Local playlist" }, type = type, url = uri)
            }
            PlaylistType.XTREAM -> {
                if (server.isBlank() || username.isBlank() || password.isBlank()) {
                    return vm.showError("Server, username and password are all required.")
                }
                val base = runCatching { XtreamClient.baseUrl(server) }.getOrNull()
                    ?: return vm.showError("Enter a valid server address, like http://example.com:8080")
                PlaylistEntity(
                    name = name.trim().ifEmpty { base.host },
                    type = type,
                    url = base.toString(),
                    username = username.trim(),
                    password = password.trim(),
                )
            }
        }
        vm.submit(draft.copy(epgUrl = epgUrl.trim().ifEmpty { null }))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 48.dp, vertical = 32.dp),
    ) {
        Text(if (editing) "Edit playlist" else "Add playlist", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(20.dp))

        if (editing) {
            // The type of an existing playlist is fixed; to switch, add a new one.
            Text(
                type.label,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PlaylistType.entries.forEach { option ->
                    TypeButton(
                        label = option.label,
                        selected = option == type,
                        modifier = if (option == PlaylistType.M3U_URL) Modifier.focusRequester(firstFocus) else Modifier,
                        onClick = { type = option },
                    )
                }
            }
        }
        Spacer(Modifier.height(24.dp))

        Column(
            modifier = Modifier.widthIn(max = 640.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (type) {
                PlaylistType.M3U_URL -> {
                    FormField(
                        "Playlist URL",
                        m3uUrl,
                        { m3uUrl = it },
                        keyboardType = KeyboardType.Uri,
                        modifier = firstFieldModifier,
                    )
                }
                PlaylistType.M3U_FILE -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(modifier = firstFieldModifier, onClick = {
                            try {
                                picker.launch(arrayOf("*/*"))
                            } catch (e: ActivityNotFoundException) {
                                vm.showError(
                                    "This device has no file picker. Put the file on a web server " +
                                        "on your network and use M3U URL instead.",
                                )
                            }
                        }) {
                            Icon(Icons.Filled.FolderOpen, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Choose file")
                        }
                        Spacer(Modifier.width(16.dp))
                        Text(
                            fileName ?: "No file chosen",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                PlaylistType.XTREAM -> {
                    FormField(
                        "Server URL (e.g. http://example.com:8080)",
                        server,
                        { server = it },
                        KeyboardType.Uri,
                        modifier = firstFieldModifier,
                    )
                    FormField("Username", username, { username = it })
                    FormField("Password", password, { password = it }, KeyboardType.Password, isPassword = true)
                }
            }
            FormField("Name (optional)", name, { name = it })
            FormField(
                if (type == PlaylistType.XTREAM) "EPG URL (optional, empty uses the provider's guide)" else "EPG URL (optional)",
                epgUrl,
                { epgUrl = it },
                KeyboardType.Uri,
                imeAction = ImeAction.Done,
            )
            Text(
                "Several EPG URLs can be separated with commas.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { submit() }, enabled = !state.saving) {
                    Text(
                        when {
                            state.saving && editing -> "Saving…"
                            state.saving -> "Loading channels…"
                            editing -> "Save"
                            else -> "Add playlist"
                        },
                    )
                }
                if (state.saving) {
                    Spacer(Modifier.width(16.dp))
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
                }
            }
            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun TypeButton(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Button(
        onClick = onClick,
        modifier = modifier,
        colors = if (selected) {
            ButtonDefaults.colors(
                containerColor = colors.primary,
                contentColor = colors.onPrimary,
            )
        } else {
            ButtonDefaults.colors(
                containerColor = colors.surfaceVariant,
                contentColor = colors.onSurfaceVariant,
            )
        },
    ) {
        Text(label)
    }
}

@Composable
private fun FormField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    keyboardType: KeyboardType = KeyboardType.Text,
    isPassword: Boolean = false,
    imeAction: ImeAction = ImeAction.Next,
    modifier: Modifier = Modifier,
) {
    // TV behaviour: the D-pad moves between fields; OK starts editing and opens the keyboard.
    // While not editing the field is read-only, so focusing it doesn't pop the keyboard up.
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var editing by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { M3Text(label) },
        singleLine = true,
        readOnly = !editing,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction, autoCorrectEnabled = false),
        visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { if (!it.isFocused) editing = false }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionUp -> focusManager.moveFocus(FocusDirection.Up)
                    Key.DirectionDown -> focusManager.moveFocus(FocusDirection.Down)
                    Key.DirectionLeft -> !editing && focusManager.moveFocus(FocusDirection.Left)
                    Key.DirectionRight -> !editing && focusManager.moveFocus(FocusDirection.Right)
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        editing = true
                        keyboard?.show()
                        true
                    }
                    else -> false
                }
            },
    )
}

private fun displayName(context: Context, uri: Uri): String? =
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment
