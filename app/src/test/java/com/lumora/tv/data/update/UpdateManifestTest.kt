package com.lumora.tv.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class UpdateManifestTest {

    private val manifestUrl = "https://example.com/lumora/update.json"

    @Test
    fun readsAllFields() {
        val info = UpdateManifest.parse(
            """{"versionCode": 20, "versionName": "0.1.20", "url": "https://cdn.example/LumoraTV-0.1.20-release.apk", "notes": " Faster guide "}""",
            manifestUrl,
        )
        assertEquals(UpdateInfo(20, "0.1.20", "https://cdn.example/LumoraTV-0.1.20-release.apk", "Faster guide"), info)
    }

    @Test
    fun resolvesTheApkNextToTheUpdateFile() {
        val info = UpdateManifest.parse("""{"versionCode": 21, "url": "LumoraTV-0.1.21-release.apk"}""", manifestUrl)
        assertEquals("https://example.com/lumora/LumoraTV-0.1.21-release.apk", info.apkUrl)
        val gitHub = UpdateManifest.parse(
            """{"versionCode": 21, "url": "LumoraTV-0.1.21-release.apk"}""",
            "https://github.com/me/lumora/releases/latest/download/update.json",
        )
        assertEquals("https://github.com/me/lumora/releases/latest/download/LumoraTV-0.1.21-release.apk", gitHub.apkUrl)
    }

    @Test
    fun acceptsANumericStringAndFillsInMissingText() {
        val info = UpdateManifest.parse("""{"versionCode": "22", "url": "a.apk", "notes": null, "versionName": null}""", manifestUrl)
        assertEquals(22, info.versionCode)
        assertEquals("22", info.versionName)
        assertEquals("", info.notes)
    }

    @Test
    fun toleratesAByteOrderMarkAndWhitespace() {
        val info = UpdateManifest.parse("﻿\n  {\"versionCode\": 5, \"url\": \"a.apk\"}\n", manifestUrl)
        assertEquals(5, info.versionCode)
    }

    @Test
    fun rejectsBrokenFiles() {
        assertRejected("<html>Not found</html>", "isn't valid JSON")
        assertRejected("""{"url": "a.apk"}""", "versionCode")
        assertRejected("""{"versionCode": 0, "url": "a.apk"}""", "versionCode")
        assertRejected("""{"versionCode": 20.5, "url": "a.apk"}""", "versionCode")
        assertRejected("""{"versionCode": "twenty", "url": "a.apk"}""", "versionCode")
        assertRejected("""{"versionCode": 20}""", "url")
        assertRejected("""{"versionCode": 20, "url": "ftp://host/a.apk"}""", "http")
    }

    @Test
    fun onlyAHigherVersionCodeIsAnUpdate() {
        val info = UpdateInfo(20, "0.1.20", "https://x/a.apk", "")
        assertTrue(UpdateManifest.isNewer(info, 19))
        assertFalse(UpdateManifest.isNewer(info, 20))
        assertFalse(UpdateManifest.isNewer(info, 21))
        // Compared as numbers, not as version names: 0.1.100 is newer than 0.1.99.
        assertTrue(UpdateManifest.isNewer(UpdateInfo(100, "0.1.100", "https://x/a.apk", ""), 99))
    }

    @Test
    fun takesGitHubReleaseAddressesApart() {
        assertEquals(
            GitHubReleases.AssetRef("me", "lumora", null, "update.json"),
            GitHubReleases.parse("https://github.com/me/lumora/releases/latest/download/update.json"),
        )
        val tagged = GitHubReleases.parse("https://github.com/me/lumora/releases/download/v0.1.20/LumoraTV-0.1.20-release.apk")
        assertEquals(GitHubReleases.AssetRef("me", "lumora", "v0.1.20", "LumoraTV-0.1.20-release.apk"), tagged)
        assertEquals("https://api.github.com/repos/me/lumora/releases/tags/v0.1.20", GitHubReleases.releaseApiUrl(tagged!!))
        assertEquals(
            "https://api.github.com/repos/me/lumora/releases/latest",
            GitHubReleases.releaseApiUrl(GitHubReleases.AssetRef("me", "lumora", null, "update.json")),
        )
        assertNull(GitHubReleases.parse("https://example.com/me/lumora/releases/latest/download/update.json"))
        assertNull(GitHubReleases.parse("https://github.com/me/lumora/blob/main/update.json"))
    }

    @Test
    fun findsAReleaseFileByName() {
        val release = """
            {"tag_name": "v0.1.20", "assets": [
              {"name": "update.json", "url": "https://api.github.com/repos/me/lumora/releases/assets/1"},
              {"name": "LumoraTV 0.1.20.apk", "url": "https://api.github.com/repos/me/lumora/releases/assets/2"}
            ]}
        """
        assertEquals("https://api.github.com/repos/me/lumora/releases/assets/1", GitHubReleases.assetApiUrl(release, "update.json"))
        assertEquals("https://api.github.com/repos/me/lumora/releases/assets/2", GitHubReleases.assetApiUrl(release, "LumoraTV%200.1.20.apk"))
        assertNull(GitHubReleases.assetApiUrl(release, "other.apk"))
        assertNull(GitHubReleases.assetApiUrl("{}", "update.json"))
    }

    private fun assertRejected(json: String, reasonContains: String) {
        try {
            UpdateManifest.parse(json, manifestUrl)
            fail("Accepted $json")
        } catch (e: IllegalArgumentException) {
            assertTrue("\"${e.message}\" should mention $reasonContains", e.message!!.contains(reasonContains))
        }
    }
}
