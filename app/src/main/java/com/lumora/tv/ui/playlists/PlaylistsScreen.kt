package com.lumora.tv.ui.playlists

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lumora.tv.ui.common.Button
import androidx.tv.material3.OutlinedButtonDefaults
import androidx.tv.material3.Icon
import com.lumora.tv.ui.common.ListItem
import androidx.tv.material3.MaterialTheme
import com.lumora.tv.ui.common.OutlinedButton
import androidx.tv.material3.Text
import com.lumora.tv.data.db.PlaylistEntity
import com.lumora.tv.data.db.PlaylistType
import com.lumora.tv.data.epg.EpgStatus
import com.lumora.tv.ui.common.appContainer
import com.lumora.tv.ui.common.requestFocusAfterLayout

@Composable
fun PlaylistsScreen(
    onAddPlaylist: () -> Unit,
    onEditPlaylist: (Long) -> Unit,
    onDone: () -> Unit,
) {
    val container = appContainer()
    val vm: PlaylistsViewModel = viewModel { PlaylistsViewModel(container.playlists, container.settings, container.epg) }
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val storedActiveId by vm.activeId.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val epgStatus by vm.epgStatus.collectAsStateWithLifecycle()

    val list = playlists ?: return
    val activeId = list.firstOrNull { it.id == storedActiveId }?.id ?: list.firstOrNull()?.id

    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { firstFocus.requestFocusAfterLayout() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 48.dp, vertical = 32.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Playlists", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Choose which playlist to watch, refresh or edit it, or add another.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(
                onClick = onAddPlaylist,
                modifier = if (list.isEmpty()) Modifier.focusRequester(firstFocus) else Modifier,
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Add playlist")
            }
        }

        message?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(16.dp))

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            items(list, key = { it.id }) { playlist ->
                PlaylistRow(
                    playlist = playlist,
                    active = playlist.id == activeId,
                    refreshing = playlist.id in refreshing,
                    epgStatus = epgStatus[playlist.id],
                    modifier = if (playlist.id == activeId) Modifier.focusRequester(firstFocus) else Modifier,
                    onSelect = {
                        vm.select(playlist.id)
                        onDone()
                    },
                    onRefresh = { vm.refresh(playlist) },
                    onEdit = { onEditPlaylist(playlist.id) },
                    onDelete = { vm.delete(playlist) },
                )
            }
        }
    }
}

@Composable
private fun PlaylistRow(
    playlist: PlaylistEntity,
    active: Boolean,
    refreshing: Boolean,
    epgStatus: EpgStatus?,
    modifier: Modifier,
    onSelect: () -> Unit,
    onRefresh: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ListItem(
            selected = active,
            onClick = onSelect,
            modifier = modifier.weight(1f),
            headlineContent = { Text(playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent = {
                Column {
                    Text(
                        if (refreshing) {
                            "Updating…"
                        } else {
                            "${playlist.type.label}  ·  ${playlist.channelCount} channels  ·  updated " +
                                relativeTime(playlist.updatedAt)
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        when {
                            epgStatus is EpgStatus.Updating -> "TV guide: updating…"
                            epgStatus is EpgStatus.Failed -> "TV guide failed: ${epgStatus.message}"
                            playlist.epgUrl.isNullOrBlank() -> "TV guide: no EPG URL"
                            playlist.epgUpdatedAt == 0L -> "TV guide: not loaded yet"
                            else -> "TV guide: updated ${relativeTime(playlist.epgUpdatedAt)}"
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            },
            leadingContent = {
                Icon(
                    if (active) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                    contentDescription = if (active) "Active" else null,
                )
            },
        )
        Spacer(Modifier.width(12.dp))
        OutlinedButton(onClick = onRefresh, enabled = !refreshing) {
            Icon(Icons.Filled.Refresh, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("Refresh")
        }
        Spacer(Modifier.width(8.dp))
        OutlinedButton(onClick = onEdit) {
            Icon(Icons.Filled.Edit, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("Edit")
        }
        Spacer(Modifier.width(8.dp))

        // Two presses to delete: the first arms the button, moving focus away disarms it.
        var confirming by remember { mutableStateOf(false) }
        OutlinedButton(
            onClick = { if (confirming) onDelete() else confirming = true },
            modifier = Modifier.onFocusChanged { if (!it.hasFocus) confirming = false },
            colors = if (confirming) {
                OutlinedButtonDefaults.colors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                    focusedContainerColor = MaterialTheme.colorScheme.error,
                    focusedContentColor = MaterialTheme.colorScheme.onError,
                )
            } else {
                OutlinedButtonDefaults.colors()
            },
        ) {
            Icon(Icons.Filled.Delete, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text(if (confirming) "Press again" else "Delete")
        }
    }
}

private fun relativeTime(time: Long): String =
    if (time > 0) DateUtils.getRelativeTimeSpanString(time).toString() else "never"

val PlaylistType.label: String
    get() = when (this) {
        PlaylistType.M3U_URL -> "M3U URL"
        PlaylistType.M3U_FILE -> "M3U file"
        PlaylistType.XTREAM -> "Xtream Codes"
    }
