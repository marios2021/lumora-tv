package com.lumora.tv.data.repo

import com.lumora.tv.data.epg.EpgRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

/**
 * Keeps playlists and TV guides fresh while the app runs: checks shortly after start and then
 * every half hour, refreshing anything older than the interval chosen in Settings.
 */
class AutoRefresher(
    private val playlists: PlaylistRepository,
    private val epg: EpgRepository,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    fun start() {
        scope.launch {
            delay(START_DELAY_MS) // let the first screen load before downloading anything
            while (isActive) {
                refresh(force = false)
                delay(CHECK_EVERY_MS)
            }
        }
    }

    /** "Refresh all now" in Settings. Returns a summary for the user. */
    suspend fun refreshAllNow(): String = refresh(force = true)

    private suspend fun refresh(force: Boolean): String = mutex.withLock {
        val hours = settings.settings.first().autoRefreshHours
        if (!force && hours == 0) return@withLock "Auto-refresh is off."
        val maxAge = TimeUnit.HOURS.toMillis(hours.toLong())
        var refreshed = 0
        var failed = 0
        _running.value = true
        try {
            for (playlist in playlists.all()) {
                val now = System.currentTimeMillis()
                if (force || now - playlist.updatedAt > maxAge) {
                    if (attempt { playlists.refresh(playlist.id) }) refreshed++ else failed++
                }
                // Re-read: a refresh can fill in the EPG URL from the playlist header.
                val current = playlists.get(playlist.id) ?: continue
                if (!current.epgUrl.isNullOrBlank() && (force || now - current.epgUpdatedAt > maxAge)) {
                    if (!attempt { epg.refresh(current.id) }) failed++
                }
            }
        } finally {
            _running.value = false
        }
        when {
            failed > 0 -> "Refreshed $refreshed playlists; $failed downloads failed."
            else -> "Refreshed $refreshed playlists and their TV guides."
        }
    }

    private suspend fun attempt(block: suspend () -> Unit): Boolean = try {
        block()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    private companion object {
        val START_DELAY_MS = TimeUnit.SECONDS.toMillis(5)
        val CHECK_EVERY_MS = TimeUnit.MINUTES.toMillis(30)
    }
}
