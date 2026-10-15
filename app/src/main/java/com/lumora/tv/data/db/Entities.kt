package com.lumora.tv.data.db

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class PlaylistType { M3U_URL, M3U_FILE, XTREAM }

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: PlaylistType,
    /** M3U URL, content:// URI of a local file, or Xtream server URL. */
    val url: String,
    val username: String? = null,
    val password: String? = null,
    val epgUrl: String? = null,
    val updatedAt: Long = 0,
    val channelCount: Int = 0,
    @ColumnInfo(defaultValue = "0") val epgUpdatedAt: Long = 0,
)

@Entity(
    tableName = "channels",
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("playlistId", "groupTitle"), Index("playlistId", "channelKey")],
)
data class ChannelEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    /** Stable identity across refreshes: the stream URL, or the Xtream stream id. */
    val channelKey: String,
    val name: String,
    val groupTitle: String,
    val logo: String?,
    val streamUrl: String,
    val tvgId: String?,
    val sortOrder: Int,
) {
    val number: Int get() = sortOrder + 1
}

/** Kept apart from [ChannelEntity] so favorites survive a playlist refresh. */
@Entity(
    tableName = "favorites",
    primaryKeys = ["playlistId", "channelKey"],
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class FavoriteEntity(
    val playlistId: Long,
    val channelKey: String,
    val addedAt: Long,
)

/** A group locked with the parental PIN. Kept by name, so the lock survives a playlist refresh. */
@Entity(
    tableName = "locked_groups",
    primaryKeys = ["playlistId", "groupTitle"],
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class LockedGroupEntity(
    val playlistId: Long,
    val groupTitle: String,
)

data class ChannelItem(
    @Embedded val channel: ChannelEntity,
    val isFavorite: Boolean,
)

/** One EPG entry, stored under the [epgKey] of the playlist channel(s) it was matched to. */
@Entity(
    tableName = "programs",
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("playlistId", "epgKey", "start"), Index("playlistId", "stop")],
)
data class ProgramEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val epgKey: String,
    val start: Long,
    val stop: Long,
    val title: String,
    val description: String?,
) {
    fun progress(now: Long): Float =
        if (stop <= start) 0f else ((now - start).toFloat() / (stop - start)).coerceIn(0f, 1f)
}

data class ChannelEpgInfo(
    val tvgId: String?,
    val name: String,
)

data class GroupCount(
    val name: String,
    val count: Int,
)
