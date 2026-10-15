package com.lumora.tv.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.CalendarViewWeek
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lumora.tv.ui.common.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import com.lumora.tv.ui.common.IconButton
import com.lumora.tv.ui.common.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.lumora.tv.data.db.ChannelItem
import com.lumora.tv.data.db.PlaylistEntity
import com.lumora.tv.data.db.ProgramEntity
import com.lumora.tv.data.epg.epgKey
import com.lumora.tv.data.repo.GroupSelection
import com.lumora.tv.ui.common.ChannelLogo
import com.lumora.tv.ui.common.ProgressLine
import com.lumora.tv.ui.common.rememberTimeFormat
import com.lumora.tv.ui.common.span
import com.lumora.tv.ui.common.appContainer
import com.lumora.tv.ui.common.findActivity
import kotlinx.coroutines.delay
import com.lumora.tv.ui.common.requestFocusAfterLayout
import com.lumora.tv.ui.theme.LumoraColors
import java.text.DateFormat

@Composable
fun HomeScreen(
    onPlay: (channelId: Long, context: GroupSelection) -> Unit,
    onOpenPlaylists: () -> Unit,
    onAddPlaylist: () -> Unit,
    onOpenGuide: (playlistId: Long, context: GroupSelection) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val container = appContainer()
    val vm: HomeViewModel = viewModel { HomeViewModel(container.playlists, container.settings, container.epg) }
    val state by vm.playlistState.collectAsStateWithLifecycle()

    when (val s = state) {
        PlaylistState.Loading -> Box(Modifier.fillMaxSize())
        PlaylistState.Empty -> WelcomeScreen(onAddPlaylist)
        is PlaylistState.Ready -> ChannelBrowser(
            vm = vm,
            playlist = s.playlist,
            onPlay = { id ->
                vm.returningFromPlayer = true
                onPlay(id, vm.selection.value)
            },
            onOpenPlaylists = onOpenPlaylists,
            onOpenGuide = {
                // The guide leads on to the player, so restore focus on return just as after playing.
                vm.returningFromPlayer = true
                onOpenGuide(s.playlist.id, vm.selection.value)
            },
            onOpenSettings = onOpenSettings,
        )
    }
}

@Composable
private fun WelcomeScreen(onAddPlaylist: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocusAfterLayout() }
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Welcome to Lumora TV", style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(12.dp))
        Text(
            "Add an M3U playlist or an Xtream Codes account to start watching.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(32.dp))
        Button(onClick = onAddPlaylist, modifier = Modifier.focusRequester(focus)) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Add playlist")
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ChannelBrowser(
    vm: HomeViewModel,
    playlist: PlaylistEntity,
    onPlay: (Long) -> Unit,
    onOpenPlaylists: () -> Unit,
    onOpenGuide: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val selection by vm.selection.collectAsStateWithLifecycle()
    val airing by vm.airing.collectAsStateWithLifecycle()
    val now by vm.now.collectAsStateWithLifecycle()
    val timeFormat = rememberTimeFormat()
    val groups by vm.groups.collectAsStateWithLifecycle()
    val favoriteCount by vm.favoriteCount.collectAsStateWithLifecycle()
    val channels by vm.channels.collectAsStateWithLifecycle()

    val selectedGroupFocus = remember { FocusRequester() }
    val playedChannelFocus = remember { FocusRequester() }
    val channelEntryFocus = remember { FocusRequester() }
    val channelListState = rememberSaveable(selection, saver = LazyListState.Saver) { LazyListState() }
    // Right from the groups lands on the top visible channel; Left from the channels lands on the
    // selected group. Done by hand because focusRestorer() crashes in Compose 1.7 lazy lists.
    val channelEntryIndex by remember(channelListState) {
        derivedStateOf { channelListState.firstVisibleItemIndex }
    }
    val enterChannels = { runCatching { channelEntryFocus.requestFocus() }.isSuccess }
    val enterGroups = { runCatching { selectedGroupFocus.requestFocus() }.isSuccess }

    // Once per visit (app start included): focus the last channel watched, which after zapping may
    // differ from the one opened. If it isn't in the list, focus the selected group instead.
    var restoreChannelId by remember { mutableStateOf<Long?>(null) }
    var initialFocusDone by remember { mutableStateOf(false) }
    LaunchedEffect(channels) {
        val list = channels ?: return@LaunchedEffect
        if (initialFocusDone) return@LaunchedEffect
        val watchedId = vm.lastWatchedChannelId()
        val index = list.indexOfFirst { it.channel.id == watchedId }
        if (index < 0 && watchedId != null && vm.returningFromPlayer && vm.selection.value != GroupSelection.All) {
            // Jumped outside this group by number: show it in All channels instead.
            vm.select(GroupSelection.All)
            return@LaunchedEffect
        }
        vm.returningFromPlayer = false
        initialFocusDone = true
        if (index >= 0) {
            restoreChannelId = watchedId
            channelListState.scrollToItem(index)
            if (playedChannelFocus.requestFocusAfterLayout()) return@LaunchedEffect
        }
        selectedGroupFocus.requestFocusAfterLayout()
    }

    // Back: from the channel list go to the groups first; then press twice within a moment to exit.
    var channelListFocused by remember { mutableStateOf(false) }
    var exitArmed by remember { mutableStateOf(false) }
    LaunchedEffect(exitArmed) {
        if (exitArmed) {
            delay(EXIT_CONFIRM_MS)
            exitArmed = false
        }
    }
    val activity = LocalContext.current.findActivity()
    BackHandler {
        when {
            channelListFocused && enterGroups() -> Unit
            exitArmed -> activity?.finish()
            else -> exitArmed = true
        }
    }

    Box(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize()) {
            // Sidebar: playlist + groups
            Column(
                modifier = Modifier
                    .width(300.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(start = 20.dp, end = 20.dp, top = 24.dp),
            ) {
                Row(
                    modifier = Modifier.onFocusChanged { if (it.hasFocus) vm.onHeaderFocused() },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Lumora",
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            playlist.name,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.onFocusChanged { if (it.hasFocus) vm.onHeaderFocused() },
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(onClick = onOpenGuide, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.CalendarViewWeek, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Guide", maxLines = 1)
                    }
                    Button(onClick = onOpenPlaylists, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.VideoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Playlists", maxLines = 1)
                    }
                }
                Spacer(Modifier.height(16.dp))

                val total = groups.sumOf { it.count }
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .onFocusChanged { vm.onGroupListFocusChanged(it.hasFocus) }
                        .onPreviewKeyEvent { it.isKeyDown(Key.DirectionRight) && enterChannels() },
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    item(key = "__favorites") {
                        GroupRow(
                            title = "Favorites",
                            count = favoriteCount,
                            selected = selection == GroupSelection.Favorites,
                            focusRequester = selectedGroupFocus.takeIf { selection == GroupSelection.Favorites },
                            onFocus = { vm.selectAfterPause(GroupSelection.Favorites) },
                            onClick = { if (selection == GroupSelection.Favorites) enterChannels() else vm.select(GroupSelection.Favorites) },
                        )
                    }
                    item(key = "__all") {
                        GroupRow(
                            title = "All channels",
                            count = total,
                            selected = selection == GroupSelection.All,
                            focusRequester = selectedGroupFocus.takeIf { selection == GroupSelection.All },
                            onFocus = { vm.selectAfterPause(GroupSelection.All) },
                            onClick = { if (selection == GroupSelection.All) enterChannels() else vm.select(GroupSelection.All) },
                        )
                    }
                    items(groups, key = { "g:" + it.name }) { group ->
                        val named = GroupSelection.Named(group.name)
                        GroupRow(
                            title = group.name,
                            count = group.count,
                            selected = selection == named,
                            focusRequester = selectedGroupFocus.takeIf { selection == named },
                            onFocus = { vm.selectAfterPause(named) },
                            onClick = { if (selection == named) enterChannels() else vm.select(named) },
                        )
                    }
                }
            }

            // Channel list
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(start = 32.dp, end = 32.dp, top = 24.dp),
            ) {
                Text(
                    text = when (val s = selection) {
                        GroupSelection.Favorites -> "Favorites"
                        GroupSelection.All -> "All channels"
                        is GroupSelection.Named -> s.name
                    },
                    style = MaterialTheme.typography.headlineSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "OK to play  ·  hold OK to add or remove a favorite",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))

                val list = channels
                when {
                    list == null -> Unit
                    list.isEmpty() -> EmptyChannels(selection)
                    else -> LazyColumn(
                        state = channelListState,
                        modifier = Modifier
                            .fillMaxSize()
                            .onFocusChanged {
                                channelListFocused = it.hasFocus
                                if (it.hasFocus) vm.onChannelListFocused()
                            }
                            .onPreviewKeyEvent { it.isKeyDown(Key.DirectionLeft) && enterGroups() },
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        contentPadding = PaddingValues(top = 4.dp, bottom = 32.dp),
                    ) {
                        itemsIndexed(list, key = { _, it -> it.channel.id }) { index, item ->
                            ChannelRow(
                                item = item,
                                program = airing[item.channel.epgKey],
                                now = now,
                                timeFormat = timeFormat,
                                modifier = Modifier
                                    .then(
                                        if (item.channel.id == restoreChannelId) {
                                            Modifier.focusRequester(playedChannelFocus)
                                        } else {
                                            Modifier
                                        },
                                    )
                                    .then(
                                        if (index == channelEntryIndex) Modifier.focusRequester(channelEntryFocus) else Modifier,
                                    ),
                                onClick = { onPlay(item.channel.id) },
                                onLongClick = { vm.toggleFavorite(item) },
                            )
                        }
                    }
                }
            }
        }
        if (exitArmed) ExitHint(Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun ExitHint(modifier: Modifier) {
    Text(
        "Press Back again to exit",
        style = MaterialTheme.typography.titleMedium,
        modifier = modifier
            .padding(bottom = 40.dp)
            .background(MaterialTheme.colorScheme.inverseSurface, RoundedCornerShape(24.dp))
            .padding(horizontal = 24.dp, vertical = 10.dp),
        color = MaterialTheme.colorScheme.inverseOnSurface,
    )
}

@Composable
private fun GroupRow(
    title: String,
    count: Int,
    selected: Boolean,
    focusRequester: FocusRequester?,
    onFocus: () -> Unit,
    onClick: () -> Unit,
) {
    ListItem(
        selected = selected,
        onClick = onClick,
        modifier = Modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { if (it.hasFocus) onFocus() },
        headlineContent = {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        trailingContent = {
            Text(count.toString(), style = MaterialTheme.typography.labelMedium)
        },
    )
}

@Composable
private fun ChannelRow(
    item: ChannelItem,
    program: ProgramEntity?,
    now: Long,
    timeFormat: DateFormat,
    modifier: Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val channel = item.channel
    ListItem(
        selected = false,
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier,
        headlineContent = {
            Text(channel.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = program?.let { p ->
            {
                Column {
                    Text(
                        timeFormat.span(p) + "   " + p.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    ProgressLine(p.progress(now), Modifier.width(240.dp))
                }
            }
        },
        leadingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    channel.number.toString(),
                    modifier = Modifier.width(48.dp),
                    textAlign = TextAlign.End,
                    style = MaterialTheme.typography.labelLarge,
                )
                Spacer(Modifier.width(16.dp))
                ChannelLogo(channel.logo, channel.name, size = 44.dp)
            }
        },
        trailingContent = {
            if (item.isFavorite) {
                Icon(Icons.Filled.Star, contentDescription = "Favorite", tint = LumoraColors.Favorite)
            }
        },
    )
}

@Composable
private fun EmptyChannels(selection: GroupSelection) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = if (selection == GroupSelection.Favorites) {
                "No favorites yet. Hold OK on any channel to add it here."
            } else {
                "No channels in this group."
            },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private const val EXIT_CONFIRM_MS = 2_500L

private fun KeyEvent.isKeyDown(k: Key) = type == KeyEventType.KeyDown && key == k
