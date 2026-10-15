package com.lumora.tv.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
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

    @Query("SELECT COALESCE(MAX(catchupDays), 0) FROM channels WHERE playlistId = :playlistId")
    suspend fun maxCatchupDays(playlistId: Long): Int

    @Query("SELECT tvgId, name FROM channels WHERE playlistId = :playlistId")
    suspend fun epgInfo(playlistId: Long): List<ChannelEpgInfo>

    @Query("SELECT id FROM channels c WHERE playlistId = :playlistId AND c.groupTitle NOT IN (:hidden) ORDER BY sortOrder")
    suspend fun idsAll(playlistId: Long, hidden: List<String>): List<Long>

    @Query("SELECT id FROM channels WHERE playlistId = :playlistId AND groupTitle = :group ORDER BY sortOrder")
    suspend fun idsInGroup(playlistId: Long, group: String): List<Long>

    @Query(
        "SELECT c.id FROM channels c " +
            "INNER JOIN favorites f ON f.playlistId = c.playlistId AND f.channelKey = c.channelKey " +
            "WHERE c.playlistId = :playlistId AND c.groupTitle NOT IN (:hidden) ORDER BY f.addedAt",
    )
    suspend fun favoriteIds(playlistId: Long, hidden: List<String>): List<Long>

    @Query(
        "SELECT groupTitle AS name, COUNT(*) AS count FROM channels WHERE playlistId = :playlistId " +
            "GROUP BY groupTitle ORDER BY MIN(sortOrder)",
    )
    fun observeGroups(playlistId: Long): Flow<List<GroupCount>>

    @Query(
        "SELECT * FROM channels c WHERE playlistId = :playlistId AND name LIKE '%' || :query || '%' ESCAPE '\\' " +
            "AND c.groupTitle NOT IN (:hidden) ORDER BY sortOrder LIMIT :limit",
    )
    suspend fun search(playlistId: Long, query: String, hidden: List<String>, limit: Int): List<ChannelEntity>

    @Query("$CHANNEL_WITH_FAVORITE WHERE c.playlistId = :playlistId AND c.groupTitle NOT IN (:hidden) ORDER BY c.sortOrder")
    fun observeAll(playlistId: Long, hidden: List<String>): Flow<List<ChannelItem>>

    @Query("$CHANNEL_WITH_FAVORITE WHERE c.playlistId = :playlistId AND c.groupTitle = :group ORDER BY c.sortOrder")
    fun observeGroup(playlistId: Long, group: String): Flow<List<ChannelItem>>

    @Query(
        "SELECT c.*, 1 AS isFavorite FROM channels c " +
            "INNER JOIN favorites f ON f.playlistId = c.playlistId AND f.channelKey = c.channelKey " +
            "WHERE c.playlistId = :playlistId AND c.groupTitle NOT IN (:hidden) ORDER BY f.addedAt",
    )
    fun observeFavorites(playlistId: Long, hidden: List<String>): Flow<List<ChannelItem>>
}

@Dao
interface FavoriteDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun add(favorite: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE playlistId = :playlistId AND channelKey = :channelKey")
    suspend fun remove(playlistId: Long, channelKey: String)

    @Query(
        "SELECT COUNT(*) FROM favorites f INNER JOIN channels c " +
            "ON c.playlistId = f.playlistId AND c.channelKey = f.channelKey " +
            "WHERE f.playlistId = :playlistId AND c.groupTitle NOT IN (:hidden)",
    )
    fun observeCount(playlistId: Long, hidden: List<String>): Flow<Int>
}

@Dao
interface LockedGroupDao {
    @Query("SELECT groupTitle FROM locked_groups WHERE playlistId = :playlistId")
    fun observe(playlistId: Long): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun add(group: LockedGroupEntity)

    @Query("DELETE FROM locked_groups WHERE playlistId = :playlistId AND groupTitle = :group")
    suspend fun remove(playlistId: Long, group: String)

    @Query("DELETE FROM locked_groups")
    suspend fun clear()
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

    @Query(
        "SELECT * FROM programs WHERE playlistId = :playlistId AND stop > :now " +
            "AND title LIKE '%' || :query || '%' ESCAPE '\\' ORDER BY start LIMIT :limit",
    )
    suspend fun search(playlistId: Long, query: String, now: Long, limit: Int): List<ProgramEntity>

    @Query("SELECT COUNT(*) FROM programs WHERE playlistId = :playlistId")
    fun observeCount(playlistId: Long): Flow<Int>
}

@Dao
interface VodDao {
    @Insert
    suspend fun insertAll(items: List<VodEntity>)

    @Query("DELETE FROM vod WHERE playlistId = :playlistId AND kind = :kind")
    suspend fun deleteFor(playlistId: Long, kind: VodKind)

    @Query(
        "SELECT groupTitle AS name, COUNT(*) AS count FROM vod WHERE playlistId = :playlistId AND kind = :kind " +
            "GROUP BY groupTitle ORDER BY MIN(sortOrder)",
    )
    fun observeGroups(playlistId: Long, kind: VodKind): Flow<List<GroupCount>>

    @Query("SELECT * FROM vod WHERE playlistId = :playlistId AND kind = :kind ORDER BY sortOrder")
    fun observeAll(playlistId: Long, kind: VodKind): Flow<List<VodEntity>>

    @Query("SELECT * FROM vod WHERE playlistId = :playlistId AND kind = :kind AND groupTitle = :group ORDER BY sortOrder")
    fun observeGroup(playlistId: Long, kind: VodKind, group: String): Flow<List<VodEntity>>

    /** Movies or series started but not finished, most recent first. */
    @Query(
        "SELECT v.* FROM vod v INNER JOIN (" +
            "SELECT playlistId, COALESCE(seriesId, SUBSTR(contentKey, 7)) AS itemId, MAX(updatedAt) AS lastAt " +
            "FROM playback_progress WHERE playlistId = :playlistId " +
            "AND contentKey LIKE (CASE WHEN :kind = 'MOVIE' THEN 'movie:%' ELSE 'episode:%' END) " +
            "GROUP BY 1, 2) p ON p.playlistId = v.playlistId AND p.itemId = v.itemId " +
            "WHERE v.playlistId = :playlistId AND v.kind = :kind ORDER BY p.lastAt DESC",
    )
    fun observeContinue(playlistId: Long, kind: VodKind): Flow<List<VodEntity>>

    @Query("SELECT * FROM vod WHERE playlistId = :playlistId AND kind = :kind AND itemId = :itemId LIMIT 1")
    suspend fun get(playlistId: Long, kind: VodKind, itemId: String): VodEntity?

    @Query("SELECT COUNT(*) FROM vod WHERE playlistId = :playlistId AND kind = :kind")
    fun observeCount(playlistId: Long, kind: VodKind): Flow<Int>

    @Query(
        "SELECT * FROM vod WHERE playlistId = :playlistId AND name LIKE '%' || :query || '%' ESCAPE '\\' " +
            "ORDER BY kind, sortOrder LIMIT :limit",
    )
    suspend fun search(playlistId: Long, query: String, limit: Int): List<VodEntity>
}

@Dao
interface ProgressDao {
    @Upsert
    suspend fun save(progress: ProgressEntity)

    @Query("DELETE FROM playback_progress WHERE playlistId = :playlistId AND contentKey = :contentKey")
    suspend fun delete(playlistId: Long, contentKey: String)

    @Query("SELECT * FROM playback_progress WHERE playlistId = :playlistId AND contentKey = :contentKey")
    fun observe(playlistId: Long, contentKey: String): Flow<ProgressEntity?>

    @Query("SELECT * FROM playback_progress WHERE playlistId = :playlistId AND seriesId = :seriesId")
    fun observeSeries(playlistId: Long, seriesId: String): Flow<List<ProgressEntity>>
}
