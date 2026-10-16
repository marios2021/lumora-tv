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
    /** Xtream: the panel's time zone (e.g. Europe/London), used for catch-up URLs. */
    val serverTimezone: String? = null,
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
    /** How many days back the provider archives this channel (0 = no catch-up). */
    @ColumnInfo(defaultValue = "0") val catchupDays: Int = 0,
    /** xc, default, append, shift or flussonic; see Catchup. */
    val catchupType: String? = null,
    /** M3U catch-up URL template. */
    val catchupSource: String? = null,
    /**
     * The TV guide channel chosen by hand ("" = no guide); null to match automatically.
     * A copy of [GuideOverrideEntity], set whenever channels are stored or an override changes.
     */
    val guideOverride: String? = null,
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

enum class VodKind { MOVIE, SERIES }

/** A movie or a series from an Xtream playlist's catalogue. Details are fetched when opened. */
@Entity(
    tableName = "vod",
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("playlistId", "kind", "groupTitle"), Index("playlistId", "kind", "itemId")],
)
data class VodEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val kind: VodKind,
    /** The provider's stream_id (movies) or series_id. */
    val itemId: String,
    val name: String,
    val groupTitle: String,
    val poster: String?,
    val rating: String?,
    val year: String?,
    /** Movies: the container to request (mp4, mkv...). */
    val extension: String?,
    val added: Long,
    val sortOrder: Int,
)

/**
 * Where playback of a movie or episode stopped, so it can resume. [contentKey] is "movie:<id>"
 * or "episode:<id>"; [seriesId] links episodes back to their series.
 */
@Entity(
    tableName = "playback_progress",
    primaryKeys = ["playlistId", "contentKey"],
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ProgressEntity(
    val playlistId: Long,
    val contentKey: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
    val seriesId: String? = null,
) {
    val fraction: Float get() = if (durationMs <= 0) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
}

enum class RecordingStatus { SCHEDULED, RECORDING, DONE, FAILED }

/** A recording: scheduled, running or saved. Kept even if its playlist is deleted. */
@Entity(tableName = "recordings", indices = [Index("status")])
data class RecordingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val channelKey: String,
    val channelName: String,
    val title: String,
    /** The stream to record. */
    val url: String,
    /** The guide programme this records, if any (to mark it in the guide). */
    val programStart: Long?,
    /** Recording window, padding included. */
    val startAt: Long,
    val endAt: Long,
    val status: RecordingStatus,
    val createdAt: Long,
    val startedAt: Long? = null,
    val filePath: String? = null,
    @ColumnInfo(defaultValue = "0") val bytes: Long = 0,
    val error: String? = null,
)

/**
 * A TV guide channel picked by hand for a playlist channel, because providers often tag channels
 * with the wrong guide id. Kept apart from [ChannelEntity] so it survives a playlist refresh.
 * [guideChannelId] is an XMLTV channel id, or "" for no guide at all.
 */
@Entity(
    tableName = "guide_overrides",
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
data class GuideOverrideEntity(
    val playlistId: Long,
    val channelKey: String,
    val guideChannelId: String,
)

/** A `<channel>` of the playlist's TV guide as of its last refresh, to pick from for an override. */
@Entity(
    tableName = "guide_channels",
    primaryKeys = ["playlistId", "guideChannelId"],
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class GuideChannelEntity(
    val playlistId: Long,
    val guideChannelId: String,
    /** Its display names, one per line. */
    val names: String,
) {
    val displayNames: List<String> get() = names.split('\n').filter { it.isNotBlank() }
}

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
    val guideOverride: String? = null,
)

data class GroupCount(
    val name: String,
    val count: Int,
)
