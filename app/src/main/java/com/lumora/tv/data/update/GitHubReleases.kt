package com.lumora.tv.data.update

import org.json.JSONObject

/**
 * Files attached to a GitHub release. Public releases download straight from their
 * `github.com/…/releases/…/download/…` address. A private repository's files need an access token
 * and the REST API instead: look the release up, find the file by name, and download it as
 * `application/octet-stream` (GitHub then redirects to a short-lived storage link).
 */
object GitHubReleases {

    /** A release download address taken apart. [tag] null means the latest release. */
    data class AssetRef(val owner: String, val repo: String, val tag: String?, val fileName: String)

    private val DOWNLOAD = Regex("""^https://github\.com/([^/]+)/([^/]+)/releases/(?:latest/download|download/([^/]+))/([^/?#]+)$""")

    /** Reads `https://github.com/<owner>/<repo>/releases/latest/download/<file>` or `…/releases/download/<tag>/<file>`. */
    fun parse(url: String): AssetRef? {
        val m = DOWNLOAD.matchEntire(url.trim()) ?: return null
        val (owner, repo, tag, file) = m.destructured
        return AssetRef(owner, repo, tag.ifEmpty { null }, file)
    }

    /** The REST API address of the release holding [ref]. */
    fun releaseApiUrl(ref: AssetRef): String {
        val base = "https://api.github.com/repos/${ref.owner}/${ref.repo}/releases"
        return if (ref.tag == null) "$base/latest" else "$base/tags/${ref.tag}"
    }

    /** The API download address of the file called [fileName] in a release (`GET /releases/…` JSON), or null. */
    fun assetApiUrl(releaseJson: String, fileName: String): String? {
        val assets = JSONObject(releaseJson).optJSONArray("assets") ?: return null
        val wanted = java.net.URLDecoder.decode(fileName, "UTF-8")
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            if (asset.optString("name") == wanted) return asset.optString("url").takeIf { it.isNotEmpty() }
        }
        return null
    }
}
