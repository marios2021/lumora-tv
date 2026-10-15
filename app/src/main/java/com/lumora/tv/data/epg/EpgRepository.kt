package com.lumora.tv.data.epg

import android.content.Context
import android.net.Uri
import android.util.Xml
import androidx.room.withTransaction
import com.lumora.tv.data.db.LumoraDatabase
import com.lumora.tv.data.db.ProgramEntity
import kotlinx.coroutines.CancellationException
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
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

sealed interface EpgStatus {
    data object Updating : EpgStatus
    data class Failed(val message: String) : EpgStatus
}

class EpgRepository(
    private val context: Context,
    private val db: LumoraDatabase,
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
) {
    private val playlistDao = db.playlistDao()
    private val channelDao = db.channelDao()
    private val programDao = db.programDao()
    private val parser = XmltvParser { Xml.newPullParser() }
    private val jobs = HashMap<Long, Job>()

    /** In-progress or failed refreshes, by playlist id. Absent means idle. */
    private val _status = MutableStateFlow<Map<Long, EpgStatus>>(emptyMap())
    val status: StateFlow<Map<Long, EpgStatus>> = _status.asStateFlow()

    /** Programmes airing at [now], keyed by epg key. */
    fun airing(playlistId: Long, now: Long): Flow<Map<String, ProgramEntity>> =
        programDao.observeAiring(playlistId, now).map { list -> list.associateBy { it.epgKey } }

    /** The programme airing at [now] (if any) followed by what comes next. */
    fun upcoming(playlistId: Long, epgKey: String, now: Long, limit: Int = 2): Flow<List<ProgramEntity>> =
        programDao.observeUpcoming(playlistId, epgKey, now, limit)

    /** Programmes overlapping [from, to), grouped by epg key and sorted by start. */
    fun range(playlistId: Long, from: Long, to: Long): Flow<Map<String, List<ProgramEntity>>> =
        programDao.observeRange(playlistId, from, to).map { list -> list.groupBy { it.epgKey } }

    fun programCount(playlistId: Long): Flow<Int> = programDao.observeCount(playlistId)

    /** Starts a refresh unless one is already running for this playlist. */
    fun refreshInBackground(playlistId: Long) {
        synchronized(jobs) {
            if (jobs[playlistId]?.isActive == true) return
            jobs[playlistId] = scope.launch { runCatching { refresh(playlistId) } }
        }
    }


    /** Downloads every EPG URL of the playlist and replaces its programmes. Returns the count stored. */
    suspend fun refresh(playlistId: Long): Int = withContext(Dispatchers.IO) {
        val playlist = playlistDao.get(playlistId) ?: return@withContext 0
        val urls = playlist.epgUrl.orEmpty().split(',', ' ', '\n').map { it.trim() }.filter { it.isNotEmpty() }
        if (urls.isEmpty()) {
            // The EPG URL was removed: drop the old guide too.
            db.withTransaction {
                programDao.deleteForPlaylist(playlistId)
                playlistDao.update(playlist.copy(epgUpdatedAt = 0))
            }
            return@withContext 0
        }

        setStatus(playlistId, EpgStatus.Updating)
        try {
            val matcher = EpgMatcher(channelDao.epgInfo(playlistId))
            val now = System.currentTimeMillis()
            val keepFrom = now - KEEP_PAST_MS
            val keepTo = now + KEEP_FUTURE_MS
            val programs = ArrayList<ProgramEntity>()
            // Several EPG URLs (or one guide listing a channel twice) can repeat the same slot.
            val seen = HashSet<String>()
            val listener = object : XmltvParser.Listener {
                override fun onChannel(id: String, displayNames: List<String>) =
                    matcher.registerChannel(id, displayNames)

                override fun onProgramme(programme: XmltvProgramme) {
                    if (programme.stop <= keepFrom || programme.start >= keepTo) return
                    for (key in matcher.keysFor(programme.channelId)) {
                        if (!seen.add(key + '|' + programme.start)) continue
                        programs += ProgramEntity(
                            playlistId = playlistId,
                            epgKey = key,
                            start = programme.start,
                            stop = programme.stop,
                            title = programme.title,
                            description = programme.description,
                        )
                    }
                }
            }
            var failures = 0
            var lastError: Exception? = null
            for (url in urls) {
                try {
                    open(url).use { parser.parse(XmltvParser.maybeGunzip(it), listener) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failures++
                    lastError = e
                }
            }
            if (failures == urls.size) throw lastError ?: IOException("Couldn't load the EPG.")

            db.withTransaction {
                programDao.deleteForPlaylist(playlistId)
                programs.chunked(2_000).forEach { programDao.insertAll(it) }
                playlistDao.get(playlistId)?.let { playlistDao.update(it.copy(epgUpdatedAt = System.currentTimeMillis())) }
            }
            clearStatus(playlistId)
            programs.size
        } catch (e: CancellationException) {
            clearStatus(playlistId)
            throw e
        } catch (e: Exception) {
            setStatus(playlistId, EpgStatus.Failed(e.message ?: e.javaClass.simpleName))
            throw e
        }
    }

    private fun open(url: String): InputStream {
        if (url.startsWith("content://")) {
            return context.contentResolver.openInputStream(Uri.parse(url))
                ?: throw IOException("Can't open the EPG file.")
        }
        val request = try {
            Request.Builder().url(url).build()
        } catch (e: IllegalArgumentException) {
            throw IOException("\"$url\" is not a valid EPG URL.")
        }
        val response = http.newCall(request).execute()
        if (!response.isSuccessful) {
            response.close()
            throw IOException("EPG server returned HTTP ${response.code}.")
        }
        return response.body?.byteStream() ?: throw IOException("Empty EPG response.")
    }

    private fun setStatus(playlistId: Long, status: EpgStatus) = _status.update { it + (playlistId to status) }
    private fun clearStatus(playlistId: Long) = _status.update { it - playlistId }

    private companion object {
        val KEEP_PAST_MS = TimeUnit.HOURS.toMillis(6)
        val KEEP_FUTURE_MS = TimeUnit.DAYS.toMillis(7)
    }
}
