package com.lumora.tv.data.parser

import java.io.BufferedReader

/** A channel as read from a source, before it is stored. */
data class ParsedChannel(
    val key: String,
    val name: String,
    val group: String,
    val logo: String?,
    val url: String,
    val tvgId: String?,
    val catchupDays: Int = 0,
    val catchupType: String? = null,
    val catchupSource: String? = null,
)

data class LoadedPlaylist(
    /** EPG URL(s) advertised by the source; may be a comma-separated list. */
    val epgUrl: String?,
    val channels: List<ParsedChannel>,
    /** Xtream: the panel's time zone. */
    val serverTimezone: String? = null,
)

const val DEFAULT_GROUP = "Uncategorized"

/**
 * Reads extended M3U line by line, so very large playlists never need to fit in memory as text.
 *
 * Handles `#EXTM3U url-tvg=...`, `#EXTINF` attributes (tvg-id, tvg-name, tvg-logo, group-title),
 * `#EXTGRP`, commas inside quoted attributes, a UTF-8 BOM, and bare URL lines without `#EXTINF`.
 */
object M3uParser {
    private val attributeRegex = Regex("""([A-Za-z0-9_-]+)\s*=\s*"([^"]*)"""")

    fun parse(reader: BufferedReader): LoadedPlaylist {
        var epgUrl: String? = null
        val channels = ArrayList<ParsedChannel>()
        var pendingInfo: String? = null
        var pendingGroup: String? = null
        var firstLine = true

        reader.forEachLine { raw ->
            var line = raw.trim()
            if (firstLine) {
                line = line.removePrefix("﻿")
                firstLine = false
            }
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXTM3U", ignoreCase = true) -> {
                    val attrs = parseAttributes(line)
                    epgUrl = attrs["url-tvg"] ?: attrs["x-tvg-url"] ?: attrs["tvg-url"]
                }
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    pendingInfo = line
                    pendingGroup = null
                }
                line.startsWith("#EXTGRP:", ignoreCase = true) -> {
                    pendingGroup = line.substringAfter(':').trim().ifEmpty { null }
                }
                line.startsWith("#") -> Unit
                else -> {
                    channels += buildChannel(pendingInfo, pendingGroup, line)
                    pendingInfo = null
                    pendingGroup = null
                }
            }
        }
        return LoadedPlaylist(epgUrl?.trim()?.ifEmpty { null }, channels)
    }

    private fun buildChannel(info: String?, extGroup: String?, url: String): ParsedChannel {
        var name = ""
        var attrs = emptyMap<String, String>()
        if (info != null) {
            val body = info.substringAfter(':', "")
            val comma = findNameComma(body)
            if (comma >= 0) {
                attrs = parseAttributes(body.substring(0, comma))
                name = body.substring(comma + 1).trim()
            } else {
                attrs = parseAttributes(body)
            }
        }
        if (name.isEmpty()) name = attrs["tvg-name"].orEmpty()
        if (name.isEmpty()) name = url.substringAfterLast('/').substringBefore('?').ifEmpty { url }

        val group = attrs["group-title"]?.trim()?.ifEmpty { null } ?: extGroup ?: DEFAULT_GROUP
        return ParsedChannel(
            key = url,
            name = name,
            group = group,
            logo = attrs["tvg-logo"]?.trim()?.ifEmpty { null },
            url = url,
            tvgId = attrs["tvg-id"]?.trim()?.ifEmpty { null },
            // catchup-days is standard; "timeshift" and "tvg-rec" are older names for it.
            catchupDays = (attrs["catchup-days"] ?: attrs["timeshift"] ?: attrs["tvg-rec"])?.trim()?.toIntOrNull()?.coerceAtLeast(0)
                ?: if (attrs["catchup"] != null || attrs["catchup-source"] != null) 1 else 0,
            catchupType = attrs["catchup"]?.trim()?.ifEmpty { null },
            catchupSource = attrs["catchup-source"]?.trim()?.ifEmpty { null },
        )
    }

    /** Index of the comma that separates attributes from the display name, ignoring commas in quotes. */
    private fun findNameComma(body: String): Int {
        var inQuotes = false
        for (i in body.indices) {
            when (body[i]) {
                '"' -> inQuotes = !inQuotes
                ',' -> if (!inQuotes) return i
            }
        }
        return -1
    }

    private fun parseAttributes(text: String): Map<String, String> =
        attributeRegex.findAll(text).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
}
