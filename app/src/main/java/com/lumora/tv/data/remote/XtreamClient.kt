package com.lumora.tv.data.remote

import android.util.JsonReader
import android.util.JsonToken
import com.lumora.tv.data.parser.DEFAULT_GROUP
import com.lumora.tv.data.parser.LoadedPlaylist
import com.lumora.tv.data.parser.ParsedChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.Reader

/** Talks to the Xtream Codes `player_api.php` interface. Live TV only for now. */
class XtreamClient(private val http: OkHttpClient) {

    suspend fun loadLive(
        server: String,
        username: String,
        password: String,
        streamExtension: String = "ts",
    ): LoadedPlaylist = withContext(Dispatchers.IO) {
        val base = baseUrl(server)

        val account = JSONObject(fetchText(api(base, username, password, null)))
        val userInfo = account.optJSONObject("user_info")
        if (userInfo == null || userInfo.optInt("auth", 0) != 1) {
            throw IOException("Login failed. Check the server, username and password.")
        }
        val status = userInfo.optString("status")
        if (status.isNotEmpty() && !status.equals("Active", ignoreCase = true)) {
            throw IOException("Account status is \"$status\".")
        }

        val categoryOrder = LinkedHashMap<String, String>()
        val categories = JSONArray(fetchText(api(base, username, password, "get_live_categories")))
        for (i in 0 until categories.length()) {
            val c = categories.optJSONObject(i) ?: continue
            categoryOrder[c.optString("category_id")] = c.optString("category_name").ifBlank { DEFAULT_GROUP }
        }
        val categoryIndex = categoryOrder.keys.withIndex().associate { it.value to it.index }

        val streams = fetch(api(base, username, password, "get_live_streams")) { reader ->
            parseStreams(reader)
        }

        val channels = streams
            .sortedBy { categoryIndex[it.categoryId] ?: Int.MAX_VALUE } // stable: keeps provider order inside a group
            .map { s ->
                ParsedChannel(
                    key = KEY_PREFIX + s.streamId,
                    name = s.name.ifBlank { "Channel ${s.streamId}" },
                    group = s.categoryId?.let { categoryOrder[it] } ?: DEFAULT_GROUP,
                    logo = s.icon?.ifBlank { null },
                    url = streamUrl(base, username, password, s.streamId, streamExtension),
                    tvgId = s.epgId?.ifBlank { null },
                )
            }

        val epg = base.newBuilder()
            .addPathSegment("xmltv.php")
            .addQueryParameter("username", username)
            .addQueryParameter("password", password)
            .build()
            .toString()

        LoadedPlaylist(epg, channels)
    }

    private class Stream(
        val streamId: String,
        val name: String,
        val icon: String?,
        val epgId: String?,
        val categoryId: String?,
    )

    /** Streams the (often huge) JSON array instead of loading it into a single string. */
    private fun parseStreams(source: Reader): List<Stream> {
        val out = ArrayList<Stream>()
        JsonReader(source).use { r ->
            if (r.peek() != JsonToken.BEGIN_ARRAY) {
                r.skipValue() // some panels return {} when there are no streams
                return out
            }
            r.beginArray()
            while (r.hasNext()) {
                if (r.peek() != JsonToken.BEGIN_OBJECT) {
                    r.skipValue()
                    continue
                }
                var streamId: String? = null
                var name = ""
                var icon: String? = null
                var epgId: String? = null
                var categoryId: String? = null
                r.beginObject()
                while (r.hasNext()) {
                    when (r.nextName()) {
                        "stream_id" -> streamId = r.stringOrNull()
                        "name" -> name = r.stringOrNull().orEmpty()
                        "stream_icon" -> icon = r.stringOrNull()
                        "epg_channel_id" -> epgId = r.stringOrNull()
                        "category_id" -> categoryId = r.stringOrNull()
                        else -> r.skipValue()
                    }
                }
                r.endObject()
                if (streamId != null) out += Stream(streamId, name.trim(), icon, epgId, categoryId)
            }
            r.endArray()
        }
        return out
    }

    private fun JsonReader.stringOrNull(): String? = when (peek()) {
        JsonToken.NULL -> {
            nextNull()
            null
        }
        JsonToken.STRING, JsonToken.NUMBER -> nextString()
        JsonToken.BOOLEAN -> nextBoolean().toString()
        else -> {
            skipValue()
            null
        }
    }

    private fun fetchText(url: HttpUrl): String = fetch(url) { it.readText() }

    private fun <T> fetch(url: HttpUrl, read: (Reader) -> T): T {
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Server returned HTTP ${response.code}.")
            val body = response.body ?: throw IOException("Empty response from server.")
            return body.charStream().use(read)
        }
    }

    companion object {
        /** Channel keys of Xtream streams start with this; M3U channels are keyed by URL. */
        const val KEY_PREFIX = "xt:"

        private val liveExtension = Regex("""\.(ts|m3u8)$""", RegexOption.IGNORE_CASE)

        /**
         * The URL to play for a channel: Xtream live streams get the extension of the chosen
         * format (the panel serves both), anything else is returned unchanged.
         */
        fun playableUrl(channelKey: String, streamUrl: String, extension: String): String =
            if (channelKey.startsWith(KEY_PREFIX)) streamUrl.replace(liveExtension, ".$extension") else streamUrl

        /** Accepts "host:port", "http://host:port/", or a full get.php / player_api.php link. */
        fun baseUrl(server: String): HttpUrl {
            val trimmed = server.trim()
            val withScheme = if (trimmed.contains("://")) trimmed else "http://$trimmed"
            val parsed = withScheme.toHttpUrlOrNull()
                ?: throw IllegalArgumentException("\"$server\" is not a valid server address.")
            return HttpUrl.Builder().scheme(parsed.scheme).host(parsed.host).port(parsed.port).build()
        }

        fun api(base: HttpUrl, username: String, password: String, action: String?): HttpUrl =
            base.newBuilder()
                .addPathSegment("player_api.php")
                .addQueryParameter("username", username)
                .addQueryParameter("password", password)
                .apply { if (action != null) addQueryParameter("action", action) }
                .build()

        fun streamUrl(base: HttpUrl, username: String, password: String, streamId: String, ext: String): String =
            base.newBuilder()
                .addPathSegment("live")
                .addPathSegment(username)
                .addPathSegment(password)
                .addPathSegment("$streamId.$ext")
                .build()
                .toString()
    }
}
