package com.lumora.tv.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lumora.tv.data.db.ChannelItem
import com.lumora.tv.data.db.GroupCount
import com.lumora.tv.data.db.PlaylistEntity
import com.lumora.tv.data.db.ProgramEntity
import com.lumora.tv.data.epg.EpgRepository
import com.lumora.tv.data.repo.GroupSelection
import com.lumora.tv.data.repo.PlaylistRepository
import com.lumora.tv.data.repo.SettingsRepository
import com.lumora.tv.ui.common.minuteTicker
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface PlaylistState {
    data object Loading : PlaylistState
    data object Empty : PlaylistState
    data class Ready(val playlist: PlaylistEntity) : PlaylistState
}

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(
    private val repo: PlaylistRepository,
    private val settings: SettingsRepository,
    private val epg: EpgRepository,
) : ViewModel() {

    val playlistState: StateFlow<PlaylistState> =
        combine(repo.playlists, settings.activePlaylistId) { playlists, activeId ->
            val active = playlists.firstOrNull { it.id == activeId } ?: playlists.firstOrNull()
            if (active == null) PlaylistState.Empty else PlaylistState.Ready(active)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlaylistState.Loading)

    private val playlistId = playlistState
        .map { (it as? PlaylistState.Ready)?.playlist?.id }
        .distinctUntilChanged()

    private val _selection = MutableStateFlow<GroupSelection>(GroupSelection.All)
    val selection: StateFlow<GroupSelection> = _selection.asStateFlow()

    val groups: StateFlow<List<GroupCount>> = playlistId
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else repo.groups(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val favoriteCount: StateFlow<Int> = playlistId
        .flatMapLatest { id -> if (id == null) flowOf(0) else repo.favoriteCount(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** Null while loading. */
    val channels: StateFlow<List<ChannelItem>?> = combine(playlistId, _selection) { id, sel -> id to sel }
        .flatMapLatest { (id, sel) ->
            when {
                id == null -> flowOf(emptyList())
                sel is GroupSelection.Favorites -> repo.favoriteChannels(id)
                sel is GroupSelection.Named -> repo.groupChannels(id, sel.name)
                else -> repo.allChannels(id)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The current time, refreshed every minute, for EPG progress. */
    val now: StateFlow<Long> = minuteTicker()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), System.currentTimeMillis())

    /** What is on now on each channel of the playlist, keyed by epg key. */
    val airing: StateFlow<Map<String, ProgramEntity>> = combine(playlistId, now) { id, time -> id to time }
        .flatMapLatest { (id, time) -> if (id == null) flowOf(emptyMap()) else epg.airing(id, time) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Set when the player opens, so the list can re-focus the last watched channel on return. */
    var returningFromPlayer = false

    suspend fun lastWatchedChannelId(): Long? = repo.channel(settings.lastChannel.first())?.id

    init {
        // A different playlist has different groups, so start again from "All channels".
        viewModelScope.launch {
            playlistId.drop(1).collect { _selection.value = GroupSelection.All }
        }
    }

    private var pendingSelection: Job? = null

    /** Selects a group straight away (OK on it, or restoring after the player). */
    fun select(selection: GroupSelection) {
        pendingSelection?.cancel()
        _selection.value = selection
    }

    /**
     * Selects a group once focus has rested on it for a moment. Moving focus past groups (say,
     * up from All channels to the buttons) then doesn't switch the list to each one on the way.
     */
    fun selectAfterPause(selection: GroupSelection) {
        pendingSelection?.cancel()
        if (selection == _selection.value) return
        pendingSelection = viewModelScope.launch {
            delay(GROUP_SELECT_DELAY_MS)
            _selection.value = selection
        }
    }

    /** The group showing when focus entered the group list; null while not browsing groups. */
    private var selectionBeforeBrowsing: GroupSelection? = null

    fun onGroupListFocusChanged(focused: Boolean) {
        if (focused) {
            if (selectionBeforeBrowsing == null) selectionBeforeBrowsing = _selection.value
        } else {
            pendingSelection?.cancel()
        }
    }

    /** Entering a channel list keeps the group browsed to. */
    fun onChannelListFocused() {
        selectionBeforeBrowsing = null
    }

    /**
     * Leaving the groups for the buttons above (Guide, Playlists, Settings) puts back the group
     * that was showing before: passing over Favorites on the way up shouldn't switch to it.
     */
    fun onHeaderFocused() {
        selectionBeforeBrowsing?.let { select(it) }
        selectionBeforeBrowsing = null
    }

    fun toggleFavorite(item: ChannelItem) {
        viewModelScope.launch { repo.setFavorite(item.channel, !item.isFavorite) }
    }

    private companion object {
        const val GROUP_SELECT_DELAY_MS = 250L
    }
}
