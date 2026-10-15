package com.lumora.tv.ui.playlists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lumora.tv.data.db.PlaylistEntity
import com.lumora.tv.data.epg.EpgRepository
import com.lumora.tv.data.epg.EpgStatus
import com.lumora.tv.data.repo.PlaylistRepository
import com.lumora.tv.data.repo.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class PlaylistsViewModel(
    private val repo: PlaylistRepository,
    private val settings: SettingsRepository,
    private val epg: EpgRepository,
) : ViewModel() {

    /** EPG downloads in progress or failed, by playlist id. */
    val epgStatus: StateFlow<Map<Long, EpgStatus>> = epg.status

    val playlists: StateFlow<List<PlaylistEntity>?> =
        repo.playlists.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val activeId: StateFlow<Long?> =
        settings.activePlaylistId.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _refreshing = MutableStateFlow<Set<Long>>(emptySet())
    val refreshing: StateFlow<Set<Long>> = _refreshing.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun select(id: Long) {
        viewModelScope.launch { settings.setActivePlaylist(id) }
    }

    fun refresh(playlist: PlaylistEntity) {
        if (playlist.id in _refreshing.value) return
        viewModelScope.launch {
            _refreshing.update { it + playlist.id }
            _message.value = "Updating ${playlist.name}…"
            _message.value = try {
                val count = repo.refresh(playlist.id)
                if (playlist.epgUrl.isNullOrBlank()) {
                    "${playlist.name}: $count channels loaded."
                } else {
                    _message.value = "${playlist.name}: $count channels loaded. Updating the TV guide…"
                    val programmes = epg.refresh(playlist.id)
                    "${playlist.name}: $count channels and $programmes guide entries loaded."
                }
            } catch (e: Exception) {
                "${playlist.name}: ${friendlyError(e)}"
            }
            _refreshing.update { it - playlist.id }
        }
    }

    fun delete(playlist: PlaylistEntity) {
        viewModelScope.launch {
            repo.delete(playlist.id)
            if (settings.activePlaylistId.first() == playlist.id) settings.setActivePlaylist(null)
            _message.value = "Deleted ${playlist.name}."
        }
    }
}

fun friendlyError(e: Throwable): String = when (e) {
    is UnknownHostException -> "Can't reach the server. Check the address and your internet connection."
    is SocketTimeoutException -> "The server took too long to respond."
    is IllegalArgumentException, is IOException -> e.message ?: "Couldn't load the playlist."
    is SecurityException -> "No permission to read that file anymore. Pick it again."
    else -> e.message ?: e.javaClass.simpleName
}
