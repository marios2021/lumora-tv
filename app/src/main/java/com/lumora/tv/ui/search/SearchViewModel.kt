package com.lumora.tv.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lumora.tv.data.db.ChannelEntity
import com.lumora.tv.data.db.ProgramEntity
import com.lumora.tv.data.epg.EpgRepository
import com.lumora.tv.data.epg.epgKey
import com.lumora.tv.data.repo.GroupSelection
import com.lumora.tv.data.repo.PlaylistRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn

data class ProgramHit(val program: ProgramEntity, val channel: ChannelEntity)

data class SearchResults(
    val query: String = "",
    val channels: List<ChannelEntity> = emptyList(),
    val programs: List<ProgramHit> = emptyList(),
) {
    val isEmpty get() = channels.isEmpty() && programs.isEmpty()
}

const val MIN_QUERY = 2

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class SearchViewModel(
    private val repo: PlaylistRepository,
    private val epg: EpgRepository,
    private val playlistId: Long,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /** Results for the query once typing pauses; null while the first search is running. */
    val results: StateFlow<SearchResults?> = _query
        .debounce(300)
        .map { it.trim() }
        .distinctUntilChanged()
        .mapLatest { q -> if (q.length < MIN_QUERY) SearchResults(q) else search(q) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchResults())

    /** Programmes are stored by EPG key: this finds the channel each one belongs to. */
    private var channelsByEpgKey: Map<String, ChannelEntity>? = null

    fun setQuery(value: String) {
        _query.value = value
    }

    private suspend fun search(q: String): SearchResults {
        val channels = repo.searchChannels(playlistId, q)
        val byKey = channelsByEpgKey
            ?: repo.channelsFor(playlistId, GroupSelection.All)
                .asReversed()
                .associateBy { it.epgKey }
                .also { channelsByEpgKey = it }
        val programs = epg.searchPrograms(playlistId, q, System.currentTimeMillis())
            .mapNotNull { p -> byKey[p.epgKey]?.let { ProgramHit(p, it) } }
        return SearchResults(q, channels, programs)
    }
}
