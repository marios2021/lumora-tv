package com.lumora.tv.data.remote

import android.util.JsonReader
import android.util.JsonToken
import com.lumora.tv.data.parser.DEFAULT_GROUP
import okhttp3.HttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.io.Reader

/** A movie or series in an Xtream catalogue. */
data class CatalogItem(
    val id: String,
    val name: String,
    val group: String,
    val poster: String?,
    val rating: String?,
    val year: String?,
    /** Movies only: the file type to request (mp4, mkv...). */
    val extension: String?,
    val added: Long,
)

data class MovieInfo(
    val name: String,
    val plot: String?,
    val cast: String?,
    val director: String?,
    val genre: String?,
    val releaseDate: String?,
    val durationSecs: Int?,
    val rating: String?,
    val poster: String?,
    val backdrop: String?,
    val extension: String,
)

data class Episode(
    val id: String,
    val season: Int,
    val number: Int,
    val title: String,
    val plot: String?,
    val durationSecs: Int?,
    val image: String?,
    val extension: String,
)

data class SeriesInfo(
    val name: String,
    val plot: String?,
    val cast: String?,
    val genre: String?,
    val releaseDate: String?,
    val rating: String?,
    val cover: String?,
    val backdrop: String?,
    /** Season numbers in order, each with its episodes in order. */
    val seasons: List<Pair<Int, List<Episode>>>,
)

/** Movies and series from `player_api.php` (get_vod_*, get_series*). */
internal object XtreamCatalog {

    fun categories(json: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return out
        for (i in 0 until array.length()) {
            val c = array.optJSONObject(i) ?: continue
            out[c.optString("category_id")] = c.optString("category_name").ifBlank { DEFAULT_GROUP }
        }
        return out
    }

    /** Streams a get_vod_streams / get_series array; [idField] is stream_id or series_id. */
    fun items(source: Reader, idField: String, imageField: String, categories: Map<String, String>): List<CatalogItem> {
        val out = ArrayList<CatalogItem>()
        JsonReader(source).use { r ->
            if (r.peek() != JsonToken.BEGIN_ARRAY) {
                r.skipValue()
                return out
            }
            r.beginArray()
            while (r.hasNext()) {
                if (r.peek() != JsonToken.BEGIN_OBJECT) {
                    r.skipValue()
                    continue
                }
                var id: String? = null
                var name = ""
                var image: String? = null
                var rating: String? = null
                var year: String? = null
                var ext: String? = null
                var category: String? = null
                var added = 0L
                r.beginObject()
                while (r.hasNext()) {
                    when (r.nextName()) {
                        idField -> id = r.stringOrNull()
                        "name" -> name = r.stringOrNull().orEmpty()
                        imageField -> image = r.stringOrNull()
                        "rating" -> rating = r.stringOrNull()
                        "year" -> year = r.stringOrNull()
                        "releaseDate", "release_date" -> if (year == null) year = r.stringOrNull()?.take(4) else r.skipValue()
                        "container_extension" -> ext = r.stringOrNull()
                        "category_id" -> category = r.stringOrNull()
                        "added", "last_modified" -> added = r.stringOrNull()?.toLongOrNull() ?: 0L
                        else -> r.skipValue()
                    }
                }
                r.endObject()
                if (id != null) {
                    out += CatalogItem(
                        id = id,
                        name = name.trim().ifBlank { "Untitled" },
                        group = category?.let { categories[it] } ?: DEFAULT_GROUP,
                        poster = image?.ifBlank { null },
                        rating = rating?.takeIf { it.isNotBlank() && it != "0" },
                        year = year?.takeIf { it.length == 4 && it.all(Char::isDigit) },
                        extension = ext?.ifBlank { null },
                        added = added,
                    )
                }
            }
            r.endArray()
        }
        return out
    }

    fun movieInfo(json: String, fallbackExtension: String?): MovieInfo {
        val root = JSONObject(json)
        val info = root.optJSONObject("info") ?: JSONObject()
        val data = root.optJSONObject("movie_data") ?: JSONObject()
        return MovieInfo(
            name = info.text("name") ?: data.text("name") ?: "Untitled",
            plot = info.text("plot") ?: info.text("description"),
            cast = info.text("cast") ?: info.text("actors"),
            director = info.text("director"),
            genre = info.text("genre"),
            releaseDate = info.text("releasedate") ?: info.text("release_date"),
            durationSecs = info.optInt("duration_secs", 0).takeIf { it > 0 },
            rating = info.text("rating")?.takeIf { it != "0" },
            poster = info.text("movie_image") ?: info.text("cover_big"),
            backdrop = firstImage(info.opt("backdrop_path")),
            extension = data.text("container_extension") ?: fallbackExtension ?: "mp4",
        )
    }

    fun seriesInfo(json: String): SeriesInfo {
        val root = JSONObject(json)
        val info = root.optJSONObject("info") ?: JSONObject()
        val seasons = sortedMapOf<Int, MutableList<Episode>>()
        // "episodes" is usually an object keyed by season, but some panels send an array of arrays.
        when (val episodes = root.opt("episodes")) {
            is JSONObject -> episodes.keys().forEach { key -> addEpisodes(episodes.optJSONArray(key), key.toIntOrNull(), seasons) }
            is JSONArray -> for (i in 0 until episodes.length()) addEpisodes(episodes.optJSONArray(i), null, seasons)
        }
        return SeriesInfo(
            name = info.text("name") ?: "Untitled",
            plot = info.text("plot"),
            cast = info.text("cast"),
            genre = info.text("genre"),
            releaseDate = info.text("releaseDate") ?: info.text("release_date"),
            rating = info.text("rating")?.takeIf { it != "0" },
            cover = info.text("cover"),
            backdrop = firstImage(info.opt("backdrop_path")),
            seasons = seasons.map { (n, list) -> n to list.sortedBy { it.number } },
        )
    }

    private fun addEpisodes(array: JSONArray?, seasonKey: Int?, into: MutableMap<Int, MutableList<Episode>>) {
        array ?: return
        for (i in 0 until array.length()) {
            val e = array.optJSONObject(i) ?: continue
            val id = e.text("id") ?: continue
            val season = e.optInt("season", seasonKey ?: 1)
            val number = e.optInt("episode_num", i + 1)
            val info = e.optJSONObject("info")
            into.getOrPut(season) { mutableListOf() } += Episode(
                id = id,
                season = season,
                number = number,
                title = e.text("title") ?: "Episode $number",
                plot = info?.text("plot"),
                durationSecs = info?.optInt("duration_secs", 0)?.takeIf { it > 0 },
                image = info?.text("movie_image"),
                extension = e.text("container_extension") ?: "mp4",
            )
        }
    }

    private fun firstImage(value: Any?): String? = when (value) {
        is JSONArray -> (0 until value.length()).asSequence().map { value.optString(it) }.firstOrNull { it.isNotBlank() }
        is String -> value.ifBlank { null }
        else -> null
    }

    private fun JSONObject.text(name: String): String? =
        if (isNull(name)) null else optString(name).trim().takeIf { it.isNotEmpty() && it != "null" }

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

    /** {base}/movie/{user}/{pass}/{id}.{ext} or {base}/series/{user}/{pass}/{id}.{ext} */
    fun fileUrl(base: HttpUrl, kind: String, username: String, password: String, id: String, ext: String): String =
        base.newBuilder()
            .addPathSegment(kind)
            .addPathSegment(username)
            .addPathSegment(password)
            .addPathSegment("$id.$ext")
            .build()
            .toString()
}
