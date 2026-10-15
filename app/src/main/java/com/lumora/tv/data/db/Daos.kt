package com.lumora.tv.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY id")
    fun observeAll(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun get(id: Long): PlaylistEntity?

    @Query("SELECT * FROM playlists")
    suspend fun getAll(): List<PlaylistEntity>

    @Insert
    suspend fun insert(playlist: PlaylistEntity): Long

    @Update
    suspend fun update(playlist: PlaylistEntity)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun delete(id: Long)
}

private const val CHANNEL_WITH_FAVORITE =
    "SELECT c.*, (f.channelKey IS NOT NULL) AS isFavorite FROM channels c " +
        "LEFT JOIN favorites f ON f.playlistId = c.playlistId AND f.channelKey = c.channelKey"

@Dao
interface ChannelDao {
    @Insert
    suspend fun insertAll(channels: List<ChannelEntity>)

    @Query("DELETE FROM channels WHERE playlistId = :playlistId")
    suspend fun deleteForPlaylist(playlistId: Long)

    @Query("SELECT * FROM channels WHERE id = :id")
    suspend fun get(id: Long): ChannelEntity?

    @Query("SELECT * FROM channels WHERE playlistId = :playlistId AND channelKey = :channelKey LIMIT 1")
    suspend fun getByKey(playlistId: Long, channelKey: String): ChannelEntity?

    @Query("SELECT * FROM channels WHERE playlistId = :playlistId AND sortOrder = :sortOrder LIMIT 1")
    suspend fun getBySortOrder(playlistId: Long, sortOrder: Int): ChannelEntity?

    @Query("SELECT tvgId, name FROM channels WHERE playlistId = :playlistId")
    suspend fun epgInfo(playlistId: Long): List<ChannelEpgInfo>

    @Query("SELECT id FROM channels WHERE playlistId = :playlistId ORDER BY sortOrder")
    suspend fun idsAll(playlistId: Long): List<Long>

    @Query("SELECT id FROM channels WHERE playlistId = :playlistId AND groupTitle = :group ORDER BY sortOrder")
    suspend fun idsInGroup(playlistId: Long, group: String): List<Long>

    @Query(
        "SELECT c.id FROM channels c " +
            "INNER JOIN favorites f ON f.playlistId = c.playlistId AND f.channelKey = c.channelKey " +
            "WHERE c.playlistId = :playlistId ORDER BY f.addedAt",
    )
    suspend fun favoriteIds(playlistId: Long): List<Long>

    @Query(
        "SELECT groupTitle AS name, COUNT(*) AS count FROM channels WHERE playlistId = :playlistId " +
            "GROUP BY groupTitle ORDER BY MIN(sortOrder)",
    )
    fun observeGroups(playlistId: Long): Flow<List<GroupCount>>

    @Query("$CHANNEL_WITH_FAVORITE WHERE c.playlistId = :playlistId ORDER BY c.sortOrder")
    fun observeAll(playlistId: Long): Flow<List<ChannelItem>>

    @Query("$CHANNEL_WITH_FAVORITE WHERE c.playlistId = :playlistId AND c.groupTitle = :group ORDER BY c.sortOrder")
    fun observeGroup(playlistId: Long, group: String): Flow<List<ChannelItem>>

    @Query(
        "SELECT c.*, 1 AS isFavorite FROM channels c " +
            "INNER JOIN favorites f ON f.playlistId = c.playlistId AND f.channelKey = c.channelKey " +
            "WHERE c.playlistId = :playlistId ORDER BY f.addedAt",
    )
    fun observeFavorites(playlistId: Long): Flow<List<ChannelItem>>
}

@Dao
interface FavoriteDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun add(favorite: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE playlistId = :playlistId AND channelKey = :channelKey")
    suspend fun remove(playlistId: Long, channelKey: String)

    @Query("SELECT COUNT(*) FROM favorites WHERE playlistId = :playlistId")
    fun observeCount(playlistId: Long): Flow<Int>
}

@Dao
interface ProgramDao {
    @Insert
    suspend fun insertAll(programs: List<ProgramEntity>)

    @Query("DELETE FROM programs WHERE playlistId = :playlistId")
    suspend fun deleteForPlaylist(playlistId: Long)

    @Query("SELECT * FROM programs WHERE playlistId = :playlistId AND start <= :now AND stop > :now")
    fun observeAiring(playlistId: Long, now: Long): Flow<List<ProgramEntity>>

    @Query(
        "SELECT * FROM programs WHERE playlistId = :playlistId AND epgKey = :epgKey AND stop > :now " +
            "ORDER BY start LIMIT :limit",
    )
    fun observeUpcoming(playlistId: Long, epgKey: String, now: Long, limit: Int): Flow<List<ProgramEntity>>

    @Query("SELECT * FROM programs WHERE playlistId = :playlistId AND stop > :from AND start < :to ORDER BY start")
    fun observeRange(playlistId: Long, from: Long, to: Long): Flow<List<ProgramEntity>>

    @Query("SELECT COUNT(*) FROM programs WHERE playlistId = :playlistId")
    fun observeCount(playlistId: Long): Flow<Int>
}
