package com.lumora.tv.data.epg

import com.lumora.tv.data.db.ChannelEntity
import com.lumora.tv.data.db.ChannelEpgInfo
import java.util.Locale

/**
 * The key a channel's programmes are stored under: its tvg-id, or its normalized name when the
 * playlist gives none. Stable across playlist refreshes, unlike channel row ids.
 */
fun epgKeyFor(tvgId: String?, name: String): String =
    tvgId?.trim()?.takeIf { it.isNotEmpty() }?.lowercase() ?: ("n:" + normalizeChannelName(name))

/**
 * The key for a channel whose guide channel was picked by hand ([guideOverride], "" for none):
 * programmes of that guide channel are stored under "g:<id>", whatever its tvg-id or name match.
 * Null when the channel is matched automatically.
 */
fun overrideKeyFor(guideOverride: String?): String? = guideOverride?.let { "g:" + it.trim().lowercase() }

fun epgKeyFor(tvgId: String?, name: String, guideOverride: String?): String =
    overrideKeyFor(guideOverride) ?: epgKeyFor(tvgId, name)

val ChannelEntity.epgKey: String get() = epgKeyFor(tvgId, name, guideOverride)

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

/** ISO 3166 codes plus "uk", which IPTV lists use for Great Britain. "sd" and "tv" read as quality, not Sudan or Tuvalu. */
private val countryCodes: Set<String> =
    Locale.getISOCountries().mapTo(HashSet()) { it.lowercase() }.apply { add("uk"); remove("sd"); remove("tv") }

/** "UK: ITV HD", "[PL] TVN", "DE | Das Erste", "FR - TF1": the code, then the rest of the name. */
private val countryPrefix = Regex("""^[\s\[(|]*([a-z]{2})(?:\s*[:|\])]|\s+[-–])[\s:|\-–]*(.+)$""")

/** "TF1 (FR)", "ITV [UK]". */
private val countryInBrackets = Regex("""[\[(]\s*([a-z]{2})\s*[\])]""")

private fun countryOf(code: String): String? = code.takeIf { it in countryCodes }?.let { if (it == "uk") "gb" else it }

/** "BBCNews.uk@HD" -> "gb". */
private fun idCountry(id: String?): String? =
    id?.let(::stripFeed)?.substringAfterLast('.', "")?.takeIf { it.length == 2 }?.let(::countryOf)

private fun nameCountry(name: String): String? {
    val lower = name.lowercase()
    countryPrefix.find(lower)?.let { m -> countryOf(m.groupValues[1])?.let { return it } }
    return countryInBrackets.findAll(lower).firstNotNullOfOrNull { countryOf(it.groupValues[1]) }
}

/**
 * A channel's country, lower-case ISO 3166 ("uk" reads as "gb"): from a tvg-id suffix like ".uk", else from
 * a name prefix like "UK:" or a bracketed code like "(UK)". Null when neither says.
 */
fun channelCountry(tvgId: String?, name: String): String? = idCountry(tvgId) ?: nameCountry(name)

/** Normalized names a channel goes by: its whole name, and without a country prefix ("UK: ITV HD" -> ukitv, itv). */
internal fun nameForms(name: String): List<String> {
    val full = normalizeChannelName(name)
    val base = countryPrefix.find(name.lowercase())
        ?.takeIf { countryOf(it.groupValues[1]) != null }
        ?.let { normalizeChannelName(it.groupValues[2]) }
    return listOfNotNull(full.ifEmpty { null }, base?.takeIf { it.isNotEmpty() && it != full })
}

/** Two channels can be the same when at most one of them names a country, or both name the same one. */
private fun compatible(a: String?, b: String?): Boolean = a == null || b == null || a == b

/**
 * Decides which playlist channels an XMLTV channel feeds. Tries, in order: exact tvg-id, tvg-id
 * without the "@feed" suffix, then display names against normalized channel names. Countries (see
 * [channelCountry]) must not disagree. A name match also needs the guide channel to be the only one
 * of that name for the playlist channel: the only one in its country, or failing that, the only one
 * not in another country. Playlist channels that some guide channel matches by id take no name matches.
 * Channels with a guide channel picked by hand (see [overrideKeyFor]) take part in none of this: they
 * get exactly the programmes of the guide channel picked, or none.
 */
class EpgMatcher(channels: List<ChannelEpgInfo>) {
    private class Target(val key: String, val country: String?, val ids: List<String>, val names: List<String>)

    private val byId = HashMap<String, MutableSet<String>>()
    private val byName = HashMap<String, MutableList<Target>>()
    private val keyCountry = HashMap<String, String>()

    private val guideCountry = HashMap<String, String>()
    private val guideNames = HashMap<String, MutableSet<String>>()
    private val guideByName = HashMap<String, MutableSet<String>>()
    /** Id forms of registered guide channels -> their countries (null for none). */
    private val guideIds = HashMap<String, MutableSet<String?>>()

    /** Guide channels picked by hand for some playlist channel, by [overrideKeyFor] key. */
    private val pickedKeys = HashSet<String>()

    private val resolved = HashMap<String, Set<String>>()
    private var stale = false

    init {
        for (c in channels) {
            val overrideKey = overrideKeyFor(c.guideOverride)
            if (overrideKey != null) {
                if (c.guideOverride!!.isNotBlank()) pickedKeys += overrideKey
                continue
            }
            val key = epgKeyFor(c.tvgId, c.name)
            val country = channelCountry(c.tvgId, c.name)
            if (country != null && key !in keyCountry) keyCountry[key] = country
            val ids = c.tvgId?.trim()?.takeIf { it.isNotEmpty() }?.let { listOf(it.lowercase(), stripFeed(it)).distinct() }.orEmpty()
            for (id in ids) byId.getOrPut(id) { HashSet(1) } += key
            val target = Target(key, country, ids, nameForms(c.name))
            for (n in target.names) byName.getOrPut(n) { ArrayList(1) } += target
        }
    }

    /** Called for each `<channel>`; display names allow name-based matching. */
    fun registerChannel(xmltvId: String, displayNames: List<String>) {
        val country = guideCountry[xmltvId] ?: idCountry(xmltvId) ?: displayNames.firstNotNullOfOrNull(::nameCountry)
        if (country != null) guideCountry[xmltvId] = country
        for (id in listOf(xmltvId.lowercase(), stripFeed(xmltvId))) guideIds.getOrPut(id) { HashSet(1) } += country
        val names = guideNames.getOrPut(xmltvId) { HashSet(2) }
        for (n in displayNames.flatMap(::nameForms)) {
            names += n
            guideByName.getOrPut(n) { HashSet(1) } += xmltvId
        }
        // A later namesake can change earlier answers; guides list their channels before programmes.
        stale = true
    }

    /** Playlist epg keys that should receive programmes of [xmltvId]; empty when none match. */
    fun keysFor(xmltvId: String): Set<String> {
        if (stale) {
            resolved.clear()
            stale = false
        }
        return resolved.getOrPut(xmltvId) {
            val picked = overrideKeyFor(xmltvId)!!
            if (picked in pickedKeys) resolve(xmltvId) + picked else resolve(xmltvId)
        }
    }

    private fun resolve(xmltvId: String): Set<String> {
        val country = guideCountry[xmltvId]
        val byIdKeys = (byId[xmltvId.lowercase()] ?: byId[stripFeed(xmltvId)])
            ?.filterTo(HashSet()) { compatible(country, keyCountry[it]) }
        if (!byIdKeys.isNullOrEmpty()) return byIdKeys

        val keys = HashSet<String>()
        for (n in guideNames[xmltvId].orEmpty()) {
            for (t in byName[n].orEmpty()) {
                if (t.key !in keys && !hasIdMatch(t) && onlyNamesake(t) == xmltvId) keys += t.key
            }
        }
        return keys
    }

    private fun hasIdMatch(t: Target): Boolean =
        t.ids.any { id -> guideIds[id]?.any { compatible(it, t.country) } == true }

    /** The guide channel [t] takes by name, or null when its namesakes can't be told apart. */
    private fun onlyNamesake(t: Target): String? {
        val namesakes = t.names.flatMapTo(HashSet()) { guideByName[it].orEmpty() }
            .filter { compatible(guideCountry[it], t.country) }
        val sameCountry = if (t.country == null) emptyList() else namesakes.filter { guideCountry[it] == t.country }
        return sameCountry.ifEmpty { namesakes }.singleOrNull()
    }
}
