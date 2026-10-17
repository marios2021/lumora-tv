package com.lumora.tv.data.update

import org.json.JSONException
import org.json.JSONObject
import java.net.URI

/** A published version, read from the update file (`update.json`) at the address set in Settings. */
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    /** Where to download the APK: absolute, already resolved against the update file's address. */
    val apkUrl: String,
    val notes: String,
)

/**
 * The update file is a small JSON object written next to the APK by the `updateJson` Gradle task:
 *
 * ```
 * {"versionCode": 20, "versionName": "0.1.20", "url": "LumoraTV-0.1.20-release.apk", "notes": "…"}
 * ```
 *
 * `url` may be absolute or relative to the update file, so the two files can be uploaded side by
 * side anywhere. `versionCode` decides whether it is newer; the name is only shown.
 */
object UpdateManifest {

    /** Reads the update file fetched from [manifestUrl]. Throws [IllegalArgumentException] with a readable reason. */
    fun parse(json: String, manifestUrl: String): UpdateInfo {
        val root = try {
            JSONObject(json.trim().removePrefix("﻿"))
        } catch (e: JSONException) {
            throw IllegalArgumentException("The update file isn't valid JSON.")
        }
        val code = when (val raw = root.opt("versionCode")) {
            is Number -> raw.toLong().takeIf { raw.toDouble() == it.toDouble() }
            is String -> raw.trim().toLongOrNull()
            else -> null
        }
        require(code != null && code in 1..Int.MAX_VALUE) { "The update file has no valid versionCode." }
        val url = root.text("url")
        require(url.isNotEmpty()) { "The update file has no APK url." }
        val apkUrl = resolve(manifestUrl, url)
        require(apkUrl.startsWith("http://") || apkUrl.startsWith("https://")) {
            "The APK url must be an http or https address."
        }
        val name = root.text("versionName").ifEmpty { code.toString() }
        return UpdateInfo(
            versionCode = code.toInt(),
            versionName = name,
            apkUrl = apkUrl,
            notes = root.text("notes"),
        )
    }

    /** Android installs an update only over a lower version code, and an equal one is the same build. */
    fun isNewer(available: UpdateInfo, installedVersionCode: Long): Boolean =
        available.versionCode.toLong() > installedVersionCode

    /** Android's optString turns a JSON null into "null". */
    private fun JSONObject.text(key: String): String = if (isNull(key)) "" else optString(key).trim()

    private fun resolve(base: String, url: String): String = try {
        URI(base.trim()).resolve(URI(url.replace(" ", "%20"))).toString()
    } catch (e: Exception) {
        throw IllegalArgumentException("The APK url \"$url\" isn't a valid address.")
    }
}
