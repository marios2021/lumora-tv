package com.lumora.tv.data.repo

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.lumora.tv.data.db.ChannelEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** How Xtream Codes live streams are requested. Applied when a channel is tuned. */
enum class StreamFormat(val extension: String, val label: String) {
    TS("ts", "MPEG-TS (.ts)"),
    HLS("m3u8", "HLS (.m3u8)"),
}

/** Player buffering: faster channel changes versus fewer stalls on slow connections. */
enum class BufferProfile(val label: String) {
    FAST_START("Fast start"),
    BALANCED("Balanced"),
    SMOOTH("Smooth playback"),
}

/**
 * How video reaches the screen. SurfaceView is the most efficient (and the only option for some
 * HDR / 4K paths); TextureView draws inside the app window, which avoids a black picture with
 * sound on some older phones and tablets.
 */
enum class VideoOutput(val label: String) {
    AUTO("Automatic"),
    SURFACE_VIEW("Standard (SurfaceView)"),
    TEXTURE_VIEW("Compatibility (TextureView)"),
}

data class LastChannel(val playlistId: Long, val channelKey: String)

data class AppSettings(
    /** false: Up / CH+ goes to the next (higher) channel. true: the reverse. */
    val invertZapping: Boolean = false,
    val xtreamFormat: StreamFormat = StreamFormat.TS,
    val bufferProfile: BufferProfile = BufferProfile.BALANCED,
    /** Hours between automatic playlist and TV guide refreshes; 0 turns it off. */
    val autoRefreshHours: Int = 12,
    val startOnLastChannel: Boolean = false,
    val videoOutput: VideoOutput = VideoOutput.AUTO,
) {
    companion object {
        val AUTO_REFRESH_CHOICES = listOf(0, 6, 12, 24, 48)
    }
}

class SettingsRepository(private val context: Context, scope: CoroutineScope) {

    val settings: Flow<AppSettings> = context.dataStore.data.map { it.toSettings() }

    /** Always-loaded copy, for code that can't suspend (such as building the player). */
    val current: StateFlow<AppSettings> = settings.stateIn(scope, SharingStarted.Eagerly, AppSettings())

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { p ->
            val new = transform(p.toSettings())
            p[INVERT_ZAPPING] = new.invertZapping
            p[XTREAM_FORMAT] = new.xtreamFormat.name
            p[BUFFER_PROFILE] = new.bufferProfile.name
            p[AUTO_REFRESH_HOURS] = new.autoRefreshHours
            p[START_ON_LAST_CHANNEL] = new.startOnLastChannel
            p[VIDEO_OUTPUT] = new.videoOutput.name
        }
    }

    val activePlaylistId: Flow<Long?> = context.dataStore.data.map { it[ACTIVE_PLAYLIST] }

    suspend fun setActivePlaylist(id: Long?) {
        context.dataStore.edit {
            if (id == null) it.remove(ACTIVE_PLAYLIST) else it[ACTIVE_PLAYLIST] = id
        }
    }

    /**
     * The channel most recently tuned in the player. Stored by playlist and channel key, because
     * refreshing a playlist recreates its channel rows with new ids.
     */
    val lastChannel: Flow<LastChannel?> = context.dataStore.data.map { p ->
        val playlistId = p[LAST_CHANNEL_PLAYLIST] ?: return@map null
        val key = p[LAST_CHANNEL_KEY] ?: return@map null
        LastChannel(playlistId, key)
    }

    suspend fun setLastChannel(channel: ChannelEntity) {
        context.dataStore.edit {
            it[LAST_CHANNEL_PLAYLIST] = channel.playlistId
            it[LAST_CHANNEL_KEY] = channel.channelKey
        }
    }

    private fun Preferences.toSettings() = AppSettings(
        invertZapping = this[INVERT_ZAPPING] ?: false,
        xtreamFormat = this[XTREAM_FORMAT]?.let { runCatching { StreamFormat.valueOf(it) }.getOrNull() }
            ?: StreamFormat.TS,
        bufferProfile = this[BUFFER_PROFILE]?.let { runCatching { BufferProfile.valueOf(it) }.getOrNull() }
            ?: BufferProfile.BALANCED,
        autoRefreshHours = this[AUTO_REFRESH_HOURS] ?: 12,
        startOnLastChannel = this[START_ON_LAST_CHANNEL] ?: false,
        videoOutput = this[VIDEO_OUTPUT]?.let { runCatching { VideoOutput.valueOf(it) }.getOrNull() }
            ?: VideoOutput.AUTO,
    )

    private companion object {
        val ACTIVE_PLAYLIST = longPreferencesKey("active_playlist_id")
        val LAST_CHANNEL_PLAYLIST = longPreferencesKey("last_channel_playlist")
        val LAST_CHANNEL_KEY = stringPreferencesKey("last_channel_key")
        val INVERT_ZAPPING = booleanPreferencesKey("invert_zapping")
        val XTREAM_FORMAT = stringPreferencesKey("xtream_format")
        val BUFFER_PROFILE = stringPreferencesKey("buffer_profile")
        val AUTO_REFRESH_HOURS = intPreferencesKey("auto_refresh_hours")
        val START_ON_LAST_CHANNEL = booleanPreferencesKey("start_on_last_channel")
        val VIDEO_OUTPUT = stringPreferencesKey("video_output")
    }
}
