package com.lumora.tv.data.repo

import androidx.room.withTransaction
import com.lumora.tv.data.db.GroupCount
import com.lumora.tv.data.db.LumoraDatabase
import com.lumora.tv.data.db.PlaylistEntity
import com.lumora.tv.data.db.PlaylistType
import com.lumora.tv.data.db.ProgressEntity
import com.lumora.tv.data.db.VodEntity
import com.lumora.tv.data.db.VodKind
import com.lumora.tv.data.remote.CatalogItem
import com.lumora.tv.data.remote.Episode
import com.lumora.tv.data.remote.MovieInfo
import com.lumora.tv.data.remote.SeriesInfo
import com.lumora.tv.data.remote.XtreamClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/** Catalogue loading state for one playlist. Absent means idle. */
sealed interface VodStatus {
    data object Loading : VodStatus
    data class Failed(val message: String) : VodStatus
}

/** Movies and series of Xtream playlists, and where playback of each stopped. */
class VodRepository(
    private val db: LumoraDatabase,
    private val xtream: XtreamClient,
    private val scope: CoroutineScope,
) {
    private val vodDao = db.vodDao()
    private val progressDao = db.progressDao()
    private val playlistDao = db.playlistDao()
    private val jobs = HashMap<Long, Job>()

    private val _status = MutableStateFlow<Map<Long, VodStatus>>(emptyMap())
    val status: StateFlow<Map<Long, VodStatus>> = _status.asStateFlow()

    fun groups(playlistId: Long, kind: VodKind): Flow<List<GroupCount>> = vodDao.observeGroups(playlistId, kind)
    fun all(playlistId: Long, kind: VodKind): Flow<List<VodEntity>> = vodDao.observeAll(playlistId, kind)
    fun group(playlistId: Long, kind: VodKind, group: String): Flow<List<VodEntity>> =
        vodDao.observeGroup(playlistId, kind, group)
    fun continueWatching(playlistId: Long, kind: VodKind): Flow<List<VodEntity>> =
        vodDao.observeContinue(playlistId, kind)
    fun count(playlistId: Long, kind: VodKind): Flow<Int> = vodDao.observeCount(playlistId, kind)

    suspend fun item(playlistId: Long, kind: VodKind, itemId: String): VodEntity? = vodDao.get(playlistId, kind, itemId)

    suspend fun search(playlistId: Long, query: String, limit: Int = 60): List<VodEntity> =
        vodDao.search(playlistId, likeEscape(query), limit)

    fun refreshInBackground(playlistId: Long) {
        synchronized(jobs) {
            if (jobs[playlistId]?.isActive == true) return
            jobs[playlistId] = scope.launch { runCatching { refresh(playlistId) } }
        }
    }

    /** Downloads both catalogues. A panel without movies or series simply ends up with none. */
    suspend fun refresh(playlistId: Long) = withContext(Dispatchers.IO) {
        val playlist = playlistDao.get(playlistId) ?: return@withContext
        if (playlist.type != PlaylistType.XTREAM) return@withContext
        _status.update { it + (playlistId to VodStatus.Loading) }
        try {
            val movies = xtream.loadMovies(playlist.url, playlist.user, playlist.pass)
            val series = xtream.loadSeries(playlist.url, playlist.user, playlist.pass)
            db.withTransaction {
                replace(playlistId, VodKind.MOVIE, movies)
                replace(playlistId, VodKind.SERIES, series)
            }
            _status.update { it - playlistId }
        } catch (e: Exception) {
            _status.update { it + (playlistId to VodStatus.Failed(e.message ?: "Couldn't load movies and series.")) }
            if (e !is IOException) throw e
        }
    }

    suspend fun clear(playlistId: Long) {
        db.withTransaction {
            vodDao.deleteFor(playlistId, VodKind.MOVIE)
            vodDao.deleteFor(playlistId, VodKind.SERIES)
        }
    }

    private suspend fun replace(playlistId: Long, kind: VodKind, items: List<CatalogItem>) {
        vodDao.deleteFor(playlistId, kind)
        items.asSequence()
            .mapIndexed { index, it ->
                VodEntity(
                    playlistId = playlistId,
                    kind = kind,
                    itemId = it.id,
                    name = it.name,
                    groupTitle = it.group,
                    poster = it.poster,
                    rating = it.rating,
                    year = it.year,
                    extension = it.extension,
                    added = it.added,
                    sortOrder = index,
                )
            }
            .chunked(1000)
            .forEach { vodDao.insertAll(it) }
    }

    suspend fun movieInfo(playlist: PlaylistEntity, movie: VodEntity): MovieInfo =
        xtream.movieInfo(playlist.url, playlist.user, playlist.pass, movie.itemId, movie.extension)

    suspend fun seriesInfo(playlist: PlaylistEntity, series: VodEntity): SeriesInfo =
        xtream.seriesInfo(playlist.url, playlist.user, playlist.pass, series.itemId)

    fun movieUrl(playlist: PlaylistEntity, movie: VodEntity, info: MovieInfo?): String =
        XtreamClient.movieUrl(playlist.url, playlist.user, playlist.pass, movie.itemId, info?.extension ?: movie.extension ?: "mp4")

    fun episodeUrl(playlist: PlaylistEntity, episode: Episode): String =
        XtreamClient.episodeUrl(playlist.url, playlist.user, playlist.pass, episode.id, episode.extension)

    fun progress(playlistId: Long, contentKey: String): Flow<ProgressEntity?> = progressDao.observe(playlistId, contentKey)

    /** Progress of every episode of a series, by content key. */
    fun seriesProgress(playlistId: Long, seriesId: String): Flow<Map<String, ProgressEntity>> =
        progressDao.observeSeries(playlistId, seriesId).map { list -> list.associateBy { it.contentKey } }

    /** Saves where playback stopped; near the end counts as finished and clears it. */
    suspend fun saveProgress(playlistId: Long, contentKey: String, positionMs: Long, durationMs: Long, seriesId: String?) {
        val finished = durationMs > 0 && positionMs > durationMs * 0.95
        if (finished || positionMs < 10_000) {
            // Keep a finished episode's row (at its end) so the series still knows where you are.
            if (finished && seriesId != null) {
                progressDao.save(ProgressEntity(playlistId, contentKey, durationMs, durationMs, System.currentTimeMillis(), seriesId))
            } else if (finished) {
                progressDao.delete(playlistId, contentKey)
            }
            return
        }
        progressDao.save(ProgressEntity(playlistId, contentKey, positionMs, durationMs, System.currentTimeMillis(), seriesId))
    }

    private val PlaylistEntity.user get() = username.orEmpty()
    private val PlaylistEntity.pass get() = password.orEmpty()

    companion object {
        fun movieKey(itemId: String) = "movie:$itemId"
        fun episodeKey(episodeId: String) = "episode:$episodeId"
    }
}
