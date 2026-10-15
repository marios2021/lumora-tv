package com.lumora.tv.ui.vod

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lumora.tv.data.db.PlaylistEntity
import com.lumora.tv.data.db.ProgressEntity
import com.lumora.tv.data.db.VodEntity
import com.lumora.tv.data.db.VodKind
import com.lumora.tv.data.remote.Episode
import com.lumora.tv.data.remote.MovieInfo
import com.lumora.tv.data.remote.SeriesInfo
import com.lumora.tv.data.repo.PlaylistRepository
import com.lumora.tv.data.repo.VodRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface DetailsState<out T> {
    data object Loading : DetailsState<Nothing>
    data object Missing : DetailsState<Nothing>
    /** [info] is null when the details couldn't be fetched; the title can usually still play. */
    data class Ready<T>(val playlist: PlaylistEntity, val item: VodEntity, val info: T?, val error: String?) : DetailsState<T>
}

class MovieDetailsViewModel(
    private val vod: VodRepository,
    playlists: PlaylistRepository,
    playlistId: Long,
    itemId: String,
) : ViewModel() {
    private val _state = MutableStateFlow<DetailsState<MovieInfo>>(DetailsState.Loading)
    val state: StateFlow<DetailsState<MovieInfo>> = _state.asStateFlow()

    val progress: StateFlow<ProgressEntity?> = vod.progress(playlistId, VodRepository.movieKey(itemId))
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        viewModelScope.launch {
            val playlist = playlists.get(playlistId)
            val movie = vod.item(playlistId, VodKind.MOVIE, itemId)
            if (playlist == null || movie == null) {
                _state.value = DetailsState.Missing
                return@launch
            }
            _state.value = try {
                DetailsState.Ready(playlist, movie, vod.movieInfo(playlist, movie), null)
            } catch (e: Exception) {
                DetailsState.Ready(playlist, movie, null, e.message ?: "Couldn't load the details.")
            }
        }
    }

    fun request(fromStart: Boolean): VodRequest? {
        val s = _state.value as? DetailsState.Ready ?: return null
        val resume = progress.value?.positionMs?.takeIf { !fromStart } ?: 0L
        return VodRequest(
            url = vod.movieUrl(s.playlist, s.item, s.info),
            title = s.info?.name ?: s.item.name,
            subtitle = listOfNotNull(s.item.year, s.info?.genre).joinToString("  ·  ").ifEmpty { null },
            playlistId = s.playlist.id,
            contentKey = VodRepository.movieKey(s.item.itemId),
            seriesId = null,
            startMs = resume,
        )
    }
}

class SeriesDetailsViewModel(
    private val vod: VodRepository,
    playlists: PlaylistRepository,
    playlistId: Long,
    private val itemId: String,
) : ViewModel() {
    private val _state = MutableStateFlow<DetailsState<SeriesInfo>>(DetailsState.Loading)
    val state: StateFlow<DetailsState<SeriesInfo>> = _state.asStateFlow()

    /** Progress of each episode, by content key. */
    val progress: StateFlow<Map<String, ProgressEntity>> = vod.seriesProgress(playlistId, itemId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val _season = MutableStateFlow<Int?>(null)
    val season: StateFlow<Int?> = _season.asStateFlow()

    init {
        viewModelScope.launch {
            val playlist = playlists.get(playlistId)
            val series = vod.item(playlistId, VodKind.SERIES, itemId)
            if (playlist == null || series == null) {
                _state.value = DetailsState.Missing
                return@launch
            }
            _state.value = try {
                val info = vod.seriesInfo(playlist, series)
                _season.value = lastWatched(info)?.season ?: info.seasons.firstOrNull()?.first
                DetailsState.Ready(playlist, series, info, null)
            } catch (e: Exception) {
                DetailsState.Ready(playlist, series, null, e.message ?: "Couldn't load the episodes.")
            }
        }
    }

    fun selectSeason(number: Int) {
        _season.value = number
    }

    /** The episode watched most recently, to start from. */
    fun lastWatched(info: SeriesInfo): Episode? {
        val latest = progress.value.values.maxByOrNull { it.updatedAt } ?: return null
        return info.seasons.flatMap { it.second }.firstOrNull { VodRepository.episodeKey(it.id) == latest.contentKey }
    }

    fun request(episode: Episode): VodRequest? {
        val s = _state.value as? DetailsState.Ready ?: return null
        val p = progress.value[VodRepository.episodeKey(episode.id)]
        val finished = p != null && p.durationMs > 0 && p.positionMs >= p.durationMs * 0.95
        return VodRequest(
            url = vod.episodeUrl(s.playlist, episode),
            title = s.info?.name ?: s.item.name,
            subtitle = "S${episode.season} E${episode.number}  ·  ${episode.title}",
            playlistId = s.playlist.id,
            contentKey = VodRepository.episodeKey(episode.id),
            seriesId = itemId,
            startMs = if (p == null || finished) 0L else p.positionMs,
        )
    }
}
