package com.lumora.tv.data.epg

import com.lumora.tv.data.db.ChannelEntity
import com.lumora.tv.data.db.ChannelEpgInfo

/**
 * The key a channel's programmes are stored under: its tvg-id, or its normalized name when the
 * playlist gives none. Stable across playlist refreshes, unlike channel row ids.
 */
fun epgKeyFor(tvgId: String?, name: String): String =
    tvgId?.trim()?.takeIf { it.isNotEmpty() }?.lowercase() ?: ("n:" + normalizeChannelName(name))

val ChannelEntity.epgKey: String get() = epgKeyFor(tvgId, name)

private val bracketed = Regex("""\([^)]*\)|\[[^]]*]""")
private val qualitySuffix = Regex("""\b(uhd|fhd|hd|sd|4k|hevc|h265|backup)\b""")
private val nonAlphanumeric = Regex("""[^\p{L}\p{N}]""")

/** "BBC One HD (1080p) [Geo-blocked]" -> "bbcone". */
fun normalizeChannelName(name: String): String =
    name.lowercase()
        .replace(bracketed, " ")
        .replace(qualitySuffix, " ")
        .replace(nonAlphanumeric, "")

/** iptv-org style ids carry a feed suffix: "BBCNews.uk@UKHD" -> "bbcnews.uk". */
private fun stripFeed(id: String): String = id.substringBefore('@').trim().lowercase()

/**
 * Decides which playlist channels an XMLTV channel feeds. Tries, in order: exact tvg-id, tvg-id
 * without the "@feed" suffix, then display names against normalized channel names.
 */
class EpgMatcher(channels: List<ChannelEpgInfo>) {
    private val byId = HashMap<String, MutableSet<String>>()
    private val byName = HashMap<String, MutableSet<String>>()
    private val resolved = HashMap<String, Set<String>>()

    init {
        for (c in channels) {
            val key = epgKeyFor(c.tvgId, c.name)
            c.tvgId?.trim()?.takeIf { it.isNotEmpty() }?.let { id ->
                byId.getOrPut(id.lowercase()) { HashSet(1) } += key
                byId.getOrPut(stripFeed(id)) { HashSet(1) } += key
            }
            normalizeChannelName(c.name).takeIf { it.isNotEmpty() }?.let { n ->
                byName.getOrPut(n) { HashSet(1) } += key
            }
        }
    }

    /** Called for each `<channel>`; display names allow name-based matching. */
    fun registerChannel(xmltvId: String, displayNames: List<String>) {
        resolved[xmltvId] = idMatch(xmltvId) ?: displayNames.asSequence()
            .mapNotNull { byName[normalizeChannelName(it)] }
            .firstOrNull()
            .orEmpty()
    }

    /** Playlist epg keys that should receive programmes of [xmltvId]; empty when none match. */
    fun keysFor(xmltvId: String): Set<String> = resolved.getOrPut(xmltvId) { idMatch(xmltvId).orEmpty() }

    private fun idMatch(xmltvId: String): Set<String>? =
        byId[xmltvId.lowercase()] ?: byId[stripFeed(xmltvId)]
}
