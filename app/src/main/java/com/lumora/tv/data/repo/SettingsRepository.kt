package com.lumora.tv.data.repo

import android.content.Context
import android.util.Base64
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.security.MessageDigest
import java.security.SecureRandom

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
    /** Address of the update file (update.json) published with each release; empty turns updates off. */
    val updateUrl: String = "",
    /** GitHub access token for update files in a private repository's releases; usually empty. */
    val updateToken: String = "",
    /** Check the update address at most once a day while the app is open. */
    val autoUpdateCheck: Boolean = true,
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
            p[UPDATE_URL] = new.updateUrl
            p[UPDATE_TOKEN] = new.updateToken
            p[AUTO_UPDATE_CHECK] = new.autoUpdateCheck
        }
    }

    /** When the update address was last read successfully (epoch ms), for the once-a-day check. */
    suspend fun lastUpdateCheck(): Long = context.dataStore.data.first()[LAST_UPDATE_CHECK] ?: 0L

    suspend fun setLastUpdateCheck(time: Long) {
        context.dataStore.edit { it[LAST_UPDATE_CHECK] = time }
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

    /** Whether a parental PIN has been set. Only a salted hash of it is stored. */
    val pinSet: Flow<Boolean> = context.dataStore.data.map { it[PIN_HASH] != null }

    suspend fun setPin(pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val saltText = Base64.encodeToString(salt, Base64.NO_WRAP)
        context.dataStore.edit {
            it[PIN_SALT] = saltText
            it[PIN_HASH] = pinHash(saltText, pin)
        }
    }

    suspend fun clearPin() {
        context.dataStore.edit {
            it.remove(PIN_SALT)
            it.remove(PIN_HASH)
        }
    }

    suspend fun checkPin(pin: String): Boolean {
        val p = context.dataStore.data.first()
        val salt = p[PIN_SALT] ?: return false
        val hash = p[PIN_HASH] ?: return false
        return MessageDigest.isEqual(hash.toByteArray(), pinHash(salt, pin).toByteArray())
    }

    private fun pinHash(salt: String, pin: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest((salt + pin).toByteArray())
        return Base64.encodeToString(digest, Base64.NO_WRAP)
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
        updateUrl = this[UPDATE_URL] ?: "",
        updateToken = this[UPDATE_TOKEN] ?: "",
        autoUpdateCheck = this[AUTO_UPDATE_CHECK] ?: true,
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
        val UPDATE_URL = stringPreferencesKey("update_url")
        val UPDATE_TOKEN = stringPreferencesKey("update_token")
        val AUTO_UPDATE_CHECK = booleanPreferencesKey("auto_update_check")
        val LAST_UPDATE_CHECK = longPreferencesKey("last_update_check")
        val PIN_HASH = stringPreferencesKey("pin_hash")
        val PIN_SALT = stringPreferencesKey("pin_salt")
    }
}
