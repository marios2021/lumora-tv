package com.lumora.tv.ui.vod

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lumora.tv.data.db.GroupCount
import com.lumora.tv.data.db.VodEntity
import com.lumora.tv.data.db.VodKind
import com.lumora.tv.data.repo.VodRepository
import com.lumora.tv.data.repo.VodStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Which shelf of the catalogue is showing. */
sealed interface Shelf {
    data object Continue : Shelf
    data object All : Shelf
    data class Group(val name: String) : Shelf
}

@OptIn(ExperimentalCoroutinesApi::class)
class CatalogViewModel(
    private val vod: VodRepository,
    private val playlistId: Long,
    val kind: VodKind,
) : ViewModel() {

    val groups: StateFlow<List<GroupCount>> = vod.groups(playlistId, kind)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val continueWatching: StateFlow<List<VodEntity>> = vod.continueWatching(playlistId, kind)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val status: StateFlow<VodStatus?> = vod.status.map { it[playlistId] }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _shelf = MutableStateFlow<Shelf>(Shelf.All)
    val shelf: StateFlow<Shelf> = _shelf.asStateFlow()

    /** Null while loading. */
    val items: StateFlow<List<VodEntity>?> = _shelf
        .flatMapLatest { shelf ->
            when (shelf) {
                Shelf.Continue -> vod.continueWatching(playlistId, kind)
                Shelf.All -> vod.all(playlistId, kind)
                is Shelf.Group -> vod.group(playlistId, kind, shelf.name)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The poster last opened, to focus again on return. */
    var openedItemId: Long? = null

    private var pending: Job? = null

    fun select(shelf: Shelf) {
        pending?.cancel()
        _shelf.value = shelf
    }

    /** Like the channel groups: switch only once focus rests on a shelf for a moment. */
    fun selectAfterPause(shelf: Shelf) {
        pending?.cancel()
        if (shelf == _shelf.value) return
        pending = viewModelScope.launch {
            delay(250)
            _shelf.value = shelf
        }
    }

    fun retry() = vod.refreshInBackground(playlistId)

    init {
        // Playlists added before movies and series existed have no catalogue yet: load it now.
        viewModelScope.launch {
            if (vod.count(playlistId, kind).first() == 0 && status.value == null) vod.refreshInBackground(playlistId)
        }
    }
}
