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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

class PlaylistRepository(
    private val context: Context,
    private val db: LumoraDatabase,
    private val http: OkHttpClient,
    private val xtream: XtreamClient,
) {
    private val playlistDao = db.playlistDao()
    private val channelDao = db.channelDao()
    private val favoriteDao = db.favoriteDao()

    val playlists: Flow<List<PlaylistEntity>> = playlistDao.observeAll()

    fun groups(playlistId: Long): Flow<List<GroupCount>> = channelDao.observeGroups(playlistId)
    fun allChannels(playlistId: Long): Flow<List<ChannelItem>> = channelDao.observeAll(playlistId)
    fun groupChannels(playlistId: Long, group: String): Flow<List<ChannelItem>> =
        channelDao.observeGroup(playlistId, group)
    fun favoriteChannels(playlistId: Long): Flow<List<ChannelItem>> = channelDao.observeFavorites(playlistId)
    fun favoriteCount(playlistId: Long): Flow<Int> = favoriteDao.observeCount(playlistId)

    suspend fun channel(id: Long): ChannelEntity? = channelDao.get(id)

    /** The current row for a remembered channel, if it still exists. */
    suspend fun channel(last: LastChannel?): ChannelEntity? =
        last?.let { channelDao.getByKey(it.playlistId, it.channelKey) }

    /** Channel numbers are 1-based positions in the whole playlist. */
    suspend fun channelByNumber(playlistId: Long, number: Int): ChannelEntity? =
        if (number < 1) null else channelDao.getBySortOrder(playlistId, number - 1)

    /** Channels listed for [selection], in display order. */
    suspend fun channelsFor(playlistId: Long, selection: GroupSelection): List<ChannelEntity> = when (selection) {
        GroupSelection.All -> channelDao.observeAll(playlistId)
        GroupSelection.Favorites -> channelDao.observeFavorites(playlistId)
        is GroupSelection.Named -> channelDao.observeGroup(playlistId, selection.name)
    }.first().map { it.channel }

    /** Channel ids in the order they are listed for [selection]: the zapping order in the player. */
    suspend fun channelIds(playlistId: Long, selection: GroupSelection): List<Long> = when (selection) {
        GroupSelection.All -> channelDao.idsAll(playlistId)
        GroupSelection.Favorites -> channelDao.favoriteIds(playlistId)
        is GroupSelection.Named -> channelDao.idsInGroup(playlistId, selection.name)
    }

    /** Downloads and parses the source first, so a bad URL never leaves an empty playlist behind. */
    suspend fun add(draft: PlaylistEntity): Long = withContext(Dispatchers.IO) {
        val loaded = load(draft)
        if (loaded.channels.isEmpty()) throw IOException("The playlist loaded, but it has no channels.")
        db.withTransaction {
            val id = playlistDao.insert(
                draft.copy(
                    epgUrl = draft.epgUrl ?: loaded.epgUrl,
                    updatedAt = System.currentTimeMillis(),
                    channelCount = loaded.channels.size,
                ),
            )
            insertChannels(id, loaded.channels)
            id
        }
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
                    updatedAt = System.currentTimeMillis(),
                    channelCount = loaded.channels.size,
                ),
            )
        }
        loaded.channels.size
    }

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
                    updatedAt = System.currentTimeMillis(),
                    channelCount = loaded.channels.size,
                ),
            )
        }
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
                )
            }
            .chunked(1000)
            .forEach { channelDao.insertAll(it) }
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
