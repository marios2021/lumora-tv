package com.lumora.tv.data.epg

import com.lumora.tv.data.db.GuideChannelEntity

/** Finds TV guide channels to pick for a playlist channel by hand. */
class GuideChannelSearch(channels: List<GuideChannelEntity>) {
    private class Entry(val channel: GuideChannelEntity, val label: String, val forms: List<String>)

    private val entries = channels
        .map { c ->
            val names = c.displayNames
            val forms = (names.flatMap(::nameForms) + normalizeChannelName(c.guideChannelId)).filter { it.isNotEmpty() }
            Entry(c, names.firstOrNull() ?: c.guideChannelId, forms.distinct())
        }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })

    val size: Int get() = entries.size

    /**
     * Guide channels named like [channelName]: "UK: CHANNEL 4 FHD" suggests "Channel 4" and
     * "Channel 4 +1", "UK: DAVE HD" suggests "U and Dave". Exact names first, then names starting
     * with it, then names containing it; closest in length first.
     */
    fun suggestionsFor(channelName: String, limit: Int = 30): List<GuideChannelEntity> {
        val wanted = nameForms(channelName).filter { it.length >= 2 }
        if (wanted.isEmpty()) return emptyList()
        return entries
            .mapNotNull { e -> closeness(e.forms, wanted)?.let { it to e } }
            .sortedBy { it.first }
            .take(limit)
            .map { it.second.channel }
    }

    /** Lower is closer; null when no form is like any wanted name. */
    private fun closeness(forms: List<String>, wanted: List<String>): Int? =
        forms.flatMap { f ->
            wanted.mapNotNull { w ->
                when {
                    f == w -> 0
                    f.startsWith(w) -> f.length - w.length
                    w.length >= 3 && w in f -> CONTAINS + f.length - w.length
                    else -> null
                }
            }
        }.minOrNull()

    /**
     * Guide channels whose id or a name contains [query], ignoring case, spaces and punctuation;
     * names starting with it first. All of them, by name, for a blank query.
     */
    fun search(query: String): List<GuideChannelEntity> {
        val q = normalizeChannelName(query)
        val raw = query.trim().lowercase()
        if (raw.isEmpty()) return entries.map { it.channel }
        return entries
            .filter { e -> (q.isNotEmpty() && e.forms.any { q in it }) || raw in e.channel.guideChannelId.lowercase() }
            .sortedBy { e -> if (q.isNotEmpty() && e.forms.any { it.startsWith(q) }) 0 else 1 }
            .map { it.channel }
    }

    private companion object {
        const val CONTAINS = 100
    }
}
