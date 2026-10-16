package com.lumora.tv.data.repo

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.lumora.tv.data.db.ChannelEntity
import com.lumora.tv.data.db.ChannelItem
import com.lumora.tv.data.db.FavoriteEntity
import com.lumora.tv.data.db.GroupCount
import com.lumora.tv.data.db.LumoraDatabase
import com.lumora.tv.data.db.PlaylistEntity
import com.lumora.tv.data.db.PlaylistType
import com.lumora.tv.data.parser.LoadedPlaylist
import com.lumora.tv.data.parser.M3uParser
import com.lumora.tv.data.parser.ParsedChannel
import com.lumora.tv.data.remote.XtreamClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistRepository(
    private val context: Context,
    private val db: LumoraDatabase,
    private val http: OkHttpClient,
    private val xtream: XtreamClient,
    private val parental: ParentalControl,
    private val vod: VodRepository,
) {
    private val playlistDao = db.playlistDao()
    private val channelDao = db.channelDao()
    private val favoriteDao = db.favoriteDao()

    val playlists: Flow<List<PlaylistEntity>> = playlistDao.observeAll()

    fun groups(playlistId: Long): Flow<List<GroupCount>> = channelDao.observeGroups(playlistId)

    // Every channel list leaves out groups hidden by the parental lock.
    private fun hidden(playlistId: Long) = parental.hiddenGroups(playlistId)

    fun allChannels(playlistId: Long): Flow<List<ChannelItem>> =
        hidden(playlistId).flatMapLatest { channelDao.observeAll(playlistId, it) }
    fun groupChannels(playlistId: Long, group: String): Flow<List<ChannelItem>> =
        hidden(playlistId).flatMapLatest { if (group in it) flowOf(emptyList()) else channelDao.observeGroup(playlistId, group) }
    fun favoriteChannels(playlistId: Long): Flow<List<ChannelItem>> =
        hidden(playlistId).flatMapLatest { channelDao.observeFavorites(playlistId, it) }
    fun favoriteCount(playlistId: Long): Flow<Int> =
        hidden(playlistId).flatMapLatest { favoriteDao.observeCount(playlistId, it) }

    /** True if [channel] is in a group the parental lock hides right now. */
    suspend fun isHidden(channel: ChannelEntity): Boolean = channel.groupTitle in parental.hidden(channel.playlistId)

    suspend fun channel(id: Long): ChannelEntity? = channelDao.get(id)

    /** The current row for a remembered channel, if it still exists. */
    suspend fun channel(last: LastChannel?): ChannelEntity? =
        last?.let { channelDao.getByKey(it.playlistId, it.channelKey) }

    /** Channel numbers are 1-based positions in the whole playlist. */
    suspend fun channelByNumber(playlistId: Long, number: Int): ChannelEntity? =
        if (number < 1) null else channelDao.getBySortOrder(playlistId, number - 1)?.takeUnless { isHidden(it) }

    /** Channels listed for [selection], in display order. */
    suspend fun channelsFor(playlistId: Long, selection: GroupSelection): List<ChannelEntity> = when (selection) {
        GroupSelection.All -> allChannels(playlistId)
        GroupSelection.Favorites -> favoriteChannels(playlistId)
        is GroupSelection.Named -> groupChannels(playlistId, selection.name)
    }.first().map { it.channel }

    /** Channel ids in the order they are listed for [selection]: the zapping order in the player. */
    suspend fun channelIds(playlistId: Long, selection: GroupSelection): List<Long> {
        val hidden = parental.hidden(playlistId)
        return when (selection) {
            GroupSelection.All -> channelDao.idsAll(playlistId, hidden)
            GroupSelection.Favorites -> channelDao.favoriteIds(playlistId, hidden)
            is GroupSelection.Named ->
                if (selection.name in hidden) emptyList() else channelDao.idsInGroup(playlistId, selection.name)
        }
    }

    /** Downloads and parses the source first, so a bad URL never leaves an empty playlist behind. */
    suspend fun add(draft: PlaylistEntity): Long = withContext(Dispatchers.IO) {
        val loaded = load(draft)
        if (loaded.channels.isEmpty()) throw IOException("The playlist loaded, but it has no channels.")
        db.withTransaction {
            val id = playlistDao.insert(
                draft.copy(
                    epgUrl = draft.epgUrl ?: loaded.epgUrl,
                    serverTimezone = loaded.serverTimezone,
                    updatedAt = System.currentTimeMillis(),
                    channelCount = loaded.channels.size,
                ),
            )
            insertChannels(id, loaded.channels)
            id
        }.also { if (draft.type == PlaylistType.XTREAM) vod.refreshInBackground(it) }
    }

    /** Re-downloads a playlist and replaces its channels. Returns the new channel count. */
    suspend fun refresh(id: Long): Int = withContext(Dispatchers.IO) {
        val playlist = playlistDao.get(id) ?: throw IOException("Playlist no longer exists.")
        val loaded = load(playlist)
        db.withTransaction {
            channelDao.deleteForPlaylist(id)
            insertChannels(id, loaded.channels)
            playlistDao.update(
                playlist.copy(
                    epgUrl = playlist.epgUrl ?: loaded.epgUrl,
                    serverTimezone = loaded.serverTimezone ?: playlist.serverTimezone,
                    updatedAt = System.currentTimeMillis(),
                    channelCount = loaded.channels.size,
                ),
            )
        }
        if (playlist.type == PlaylistType.XTREAM) vod.refreshInBackground(id)
        loaded.channels.size
    }

    /** Channels whose name contains [query] (case-insensitive), in playlist order. */
    suspend fun searchChannels(playlistId: Long, query: String, limit: Int = 100): List<ChannelEntity> =
        channelDao.search(playlistId, likeEscape(query), parental.hidden(playlistId), limit)

    suspend fun get(id: Long): PlaylistEntity? = playlistDao.get(id)

    suspend fun all(): List<PlaylistEntity> = playlistDao.getAll()

    /**
     * Saves an edited playlist. When the source changed (URL, file or login), its channels are
     * downloaded again first, so a bad edit is rejected and the old channels are kept.
     * Returns true when the TV guide should be reloaded.
     */
    suspend fun update(edited: PlaylistEntity): Boolean = withContext(Dispatchers.IO) {
        val old = playlistDao.get(edited.id) ?: throw IOException("Playlist no longer exists.")
        val sourceChanged = old.type != edited.type || old.url != edited.url ||
            old.username != edited.username || old.password != edited.password
        if (!sourceChanged) {
            playlistDao.update(old.copy(name = edited.name, epgUrl = edited.epgUrl))
            return@withContext old.epgUrl != edited.epgUrl
        }
        val loaded = load(edited)
        if (loaded.channels.isEmpty()) throw IOException("The playlist loaded, but it has no channels.")
        db.withTransaction {
            channelDao.deleteForPlaylist(edited.id)
            insertChannels(edited.id, loaded.channels)
            playlistDao.update(
                old.copy(
                    name = edited.name,
                    type = edited.type,
                    url = edited.url,
                    username = edited.username,
                    password = edited.password,
                    epgUrl = edited.epgUrl ?: loaded.epgUrl,
                    serverTimezone = loaded.serverTimezone,
                    updatedAt = System.currentTimeMillis(),
                    channelCount = loaded.channels.size,
                ),
            )
        }
        vod.clear(edited.id)
        if (edited.type == PlaylistType.XTREAM) vod.refreshInBackground(edited.id)
        true
    }

    suspend fun delete(id: Long) = playlistDao.delete(id)

    suspend fun setFavorite(channel: ChannelEntity, favorite: Boolean) {
        if (favorite) {
            favoriteDao.add(FavoriteEntity(channel.playlistId, channel.channelKey, System.currentTimeMillis()))
        } else {
            favoriteDao.remove(channel.playlistId, channel.channelKey)
        }
    }

    private suspend fun insertChannels(playlistId: Long, channels: List<ParsedChannel>) {
        channels.asSequence()
            .mapIndexed { index, c ->
                ChannelEntity(
                    playlistId = playlistId,
                    channelKey = c.key,
                    name = c.name,
                    groupTitle = c.group,
                    logo = c.logo,
                    streamUrl = c.url,
                    tvgId = c.tvgId,
                    sortOrder = index,
                    catchupDays = c.catchupDays,
                    catchupType = c.catchupType,
                    catchupSource = c.catchupSource,
                )
            }
            .chunked(1000)
            .forEach { channelDao.insertAll(it) }
        db.guideDao().applyOverrides(playlistId)
    }

    private suspend fun load(playlist: PlaylistEntity): LoadedPlaylist = when (playlist.type) {
        PlaylistType.M3U_URL -> loadM3uUrl(playlist.url)
        PlaylistType.M3U_FILE -> {
            val stream = context.contentResolver.openInputStream(Uri.parse(playlist.url))
                ?: throw IOException("Can't open the playlist file.")
            stream.bufferedReader().use { M3uParser.parse(it) }
        }
        PlaylistType.XTREAM -> xtream.loadLive(
            server = playlist.url,
            username = playlist.username.orEmpty(),
            password = playlist.password.orEmpty(),
        )
    }

    private fun loadM3uUrl(url: String): LoadedPlaylist {
        val request = try {
            Request.Builder().url(url.trim()).build()
        } catch (e: IllegalArgumentException) {
            throw IOException("\"$url\" is not a valid URL.")
        }
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Server returned HTTP ${response.code}.")
            val body = response.body ?: throw IOException("Empty response from server.")
            return body.charStream().buffered().use { M3uParser.parse(it) }
        }
    }
}

/** Escapes LIKE wildcards so a search for "100%" means exactly that. Queries use ESCAPE '\\'. */
fun likeEscape(query: String): String =
    query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
