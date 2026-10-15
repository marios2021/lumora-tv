package com.lumora.tv.data.epg

import com.lumora.tv.data.db.ChannelEntity
import com.lumora.tv.data.db.PlaylistEntity
import com.lumora.tv.data.remote.XtreamClient
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Catch-up: playing a past programme from the provider's archive.
 *
 * Xtream panels flag archived streams with `tv_archive`; M3U playlists use `catchup`,
 * `catchup-days` and `catchup-source` attributes. The common catch-up types are:
 * - **xc**: `{server}/timeshift/{user}/{pass}/{minutes}/{YYYY-MM-DD:HH-MM}/{streamId}.ts`, time in the server's zone.
 * - **default**: `catchup-source` is a full URL template with placeholders.
 * - **append**: `catchup-source` (a query string) is appended to the stream URL.
 * - **shift**: `utc=` and `lutc=` are appended to the stream URL.
 * - **flussonic**: the archive is part of the stream path (`index-{start}-{duration}.m3u8`).
 */
object Catchup {
    /** Archives are kept for up to a week; the guide keeps that much history at most. */
    const val MAX_DAYS = 7

    private val DAY_MS = TimeUnit.DAYS.toMillis(1)

    fun hasArchive(channel: ChannelEntity): Boolean =
        channel.catchupDays > 0 && type(channel) != null

    /** True if [start, stop) of [channel] has finished and is still within its archive. */
    fun isAvailable(channel: ChannelEntity, start: Long, stop: Long, now: Long): Boolean =
        hasArchive(channel) && stop <= now && start >= now - channel.catchupDays.coerceAtMost(MAX_DAYS) * DAY_MS

    /** The URL that plays [start, stop) of [channel] from the archive, or null if there's no way to. */
    fun url(playlist: PlaylistEntity, channel: ChannelEntity, start: Long, stop: Long, now: Long): String? {
        val startSec = start / 1000
        val stopSec = stop / 1000
        val nowSec = now / 1000
        return when (type(channel)) {
            "xc" -> xtreamUrl(playlist, channel, start, stop)
            "default" -> channel.catchupSource?.let { fill(it, startSec, stopSec, nowSec) }
            "append" -> channel.catchupSource?.let { channel.streamUrl + fill(it, startSec, stopSec, nowSec) }
            "shift" -> channel.streamUrl + (if ('?' in channel.streamUrl) "&" else "?") + "utc=$startSec&lutc=$nowSec"
            "flussonic" -> flussonic(channel.streamUrl, startSec, stopSec - startSec)
            else -> null
        }
    }

    /** The catch-up type to use, from the playlist's hints. */
    private fun type(channel: ChannelEntity): String? {
        val declared = channel.catchupType?.lowercase()?.trim()
        return when {
            declared == "xc" || channel.channelKey.startsWith(XtreamClient.KEY_PREFIX) && declared == null -> "xc"
            declared in setOf("default", "append", "shift", "flussonic", "fs") -> if (declared == "fs") "flussonic" else declared
            // TiviMate convention: a source without a type is a full template; "timeshift" days alone mean shift.
            declared == null && !channel.catchupSource.isNullOrBlank() -> "default"
            declared == null -> "shift"
            else -> null
        }
    }

    private fun xtreamUrl(playlist: PlaylistEntity, channel: ChannelEntity, start: Long, stop: Long): String? {
        val streamId = channel.channelKey.removePrefix(XtreamClient.KEY_PREFIX).takeIf { it != channel.channelKey }
            ?: return null
        val base = runCatching { XtreamClient.baseUrl(playlist.url) }.getOrNull() ?: return null
        val minutes = ((stop - start + 59_999) / 60_000).coerceAtLeast(1)
        val format = SimpleDateFormat("yyyy-MM-dd:HH-mm", Locale.US).apply {
            timeZone = playlist.serverTimezone?.let { TimeZone.getTimeZone(it) } ?: TimeZone.getTimeZone("UTC")
        }
        return base.newBuilder()
            .addPathSegment("timeshift")
            .addPathSegment(playlist.username.orEmpty())
            .addPathSegment(playlist.password.orEmpty())
            .addPathSegment(minutes.toString())
            .addPathSegment(format.format(start))
            .addPathSegment("$streamId.ts")
            .build()
            .toString()
    }

    /** index.m3u8 → index-{start}-{duration}.m3u8; …/mpegts → …/timeshift_abs-{start}.ts */
    private fun flussonic(url: String, start: Long, duration: Long): String? {
        val query = url.substringAfter('?', "").let { if (it.isEmpty()) "" else "?$it" }
        val path = url.substringBefore('?')
        val m3u8 = Regex("""/([^/]+)\.m3u8$""").find(path)
        return when {
            m3u8 != null -> path.substring(0, m3u8.range.first) + "/${m3u8.groupValues[1]}-$start-$duration.m3u8" + query
            path.endsWith("/mpegts") -> path.removeSuffix("mpegts") + "timeshift_abs-$start.ts" + query
            else -> null
        }
    }

    // Android's regex engine needs the closing brace escaped too.
    private val placeholder = Regex("""\$?\{([a-zA-Z]+)(?::([^\}]*))?\}""")

    /**
     * Fills a catch-up template. Supports {utc}/{start}, {utcend}/{end}, {lutc}/{now}/{timestamp},
     * {duration} (seconds, or {duration:60} to divide), {offset} (seconds ago),
     * {Y} {m} {d} {H} {M} {S} (start, UTC), and the same with a leading $.
     */
    fun fill(template: String, start: Long, stop: Long, now: Long): String {
        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = start * 1000 }
        fun two(field: Int) = "%02d".format(utc.get(field))
        return placeholder.replace(template) { m ->
            val divisor = m.groupValues[2].toLongOrNull()?.takeIf { it > 0 } ?: 1
            when (m.groupValues[1]) {
                "utc", "start", "timestamp_start" -> start.toString()
                "utcend", "end" -> stop.toString()
                "lutc", "now", "timestamp" -> now.toString()
                "duration" -> ((stop - start) / divisor).toString()
                "offset" -> ((now - start) / divisor).toString()
                "Y" -> utc.get(Calendar.YEAR).toString()
                "m" -> "%02d".format(utc.get(Calendar.MONTH) + 1)
                "d" -> two(Calendar.DAY_OF_MONTH)
                "H" -> two(Calendar.HOUR_OF_DAY)
                "M" -> two(Calendar.MINUTE)
                "S" -> two(Calendar.SECOND)
                else -> m.value
            }
        }
    }
}
