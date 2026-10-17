package com.lumora.tv.data.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.IntentCompat
import androidx.core.content.pm.PackageInfoCompat
import com.lumora.tv.LumoraApp
import com.lumora.tv.MainActivity
import com.lumora.tv.data.repo.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Where the update in Settings stands. */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val versionName: String) : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    /** [progress] is 0..1, or null when the server doesn't say how big the file is. */
    data class Downloading(val info: UpdateInfo, val progress: Float?) : UpdateState
    /** Handed to Android, which asks the user to confirm and then replaces the app. */
    data class Installing(val info: UpdateInfo) : UpdateState
    /** "Install unknown apps" is off for Lumora TV; Settings explains and opens that screen. */
    data class NeedsPermission(val info: UpdateInfo) : UpdateState
    /** [info] is kept when a found update failed to install, so Install can be tried again. */
    data class Failed(val message: String, val info: UpdateInfo?) : UpdateState
}

/**
 * Updates the app from an update file the user publishes (see README, "Updating without a cable"):
 * checks the address set in Settings (by hand, or at most once a day while the app is open),
 * downloads the APK, checks it is this app signed with the same key, and installs it with
 * [PackageInstaller]. Android may ask the user to confirm; it then replaces the app, and
 * [UpdateRelaunchReceiver] opens the new version.
 */
class Updater(
    private val context: Context,
    private val http: OkHttpClient,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private var job: Job? = null
    private var announcedVersion = 0

    private val downloadDir get() = File(context.cacheDir, "update")

    val installedVersionCode: Long
        get() = PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0))

    val installedVersionName: String
        get() = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"

    fun start() {
        scope.launch(Dispatchers.IO) {
            // Leftovers of an earlier attempt: the APK in the cache, and unfinished install sessions.
            downloadDir.deleteRecursively()
            runCatching {
                val installer = context.packageManager.packageInstaller
                installer.mySessions.forEach { installer.abandonSession(it.sessionId) }
            }
            delay(AUTO_START_DELAY_MS) // let the first screen load first
            while (isActive) {
                runCatching { autoCheck() }
                delay(AUTO_EVERY_MS)
            }
        }
    }

    private suspend fun autoCheck() {
        val s = settings.current.value
        if (!s.autoUpdateCheck || s.updateUrl.isBlank()) return
        if (System.currentTimeMillis() - settings.lastUpdateCheck() < AUTO_INTERVAL_MS) return
        if (job?.isActive == true || _state.value.isBusy()) return
        val found = runCheck(quiet = true) ?: return
        if (found.versionCode != announcedVersion) {
            announcedVersion = found.versionCode
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    context,
                    "Lumora TV ${found.versionName} is available. Install it from Settings → Updates.",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    /** "Check for updates" in Settings. */
    fun checkNow() {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) { runCheck(quiet = false) }
    }

    /** Returns the newer version found, if any. A quiet (automatic) check that fails leaves the state alone. */
    private suspend fun runCheck(quiet: Boolean): UpdateInfo? {
        val url = settings.current.value.updateUrl.trim()
        if (url.isEmpty()) {
            _state.value = UpdateState.Failed("Set the update address first.", null)
            return null
        }
        val before = _state.value
        _state.value = UpdateState.Checking
        return try {
            val json = open(url, binary = false).use { it.body!!.string() }
            val info = UpdateManifest.parse(json, url)
            settings.setLastUpdateCheck(System.currentTimeMillis())
            if (UpdateManifest.isNewer(info, installedVersionCode)) {
                _state.value = UpdateState.Available(info)
                info
            } else {
                _state.value = UpdateState.UpToDate(installedVersionName)
                null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = if (quiet) before else UpdateState.Failed("Couldn't check for updates: ${e.readable()}", null)
            null
        }
    }

    /** "Install" in Settings: download, check and install [info]. */
    fun install(info: UpdateInfo) {
        if (job?.isActive == true) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            _state.value = UpdateState.NeedsPermission(info)
            return
        }
        job = scope.launch(Dispatchers.IO) {
            try {
                _state.value = UpdateState.Downloading(info, 0f)
                val apk = try {
                    download(info)
                } catch (e: IOException) {
                    if (e is HttpFailure) throw e
                    // Wi-Fi drops and servers that close early: one more go before giving up.
                    delay(RETRY_DELAY_MS)
                    try {
                        download(info)
                    } catch (e: IOException) {
                        throw IOException("Couldn't download the update: ${e.readable()}", e)
                    }
                }
                verify(apk)
                _state.value = UpdateState.Installing(info)
                commit(apk)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                downloadDir.deleteRecursively()
                _state.value = UpdateState.Failed(e.readable(), info)
            }
        }
    }

    /** Back from Android's "Install unknown apps" screen: carry on if it was allowed. */
    fun onReturnFromPermissionScreen() {
        val waiting = _state.value as? UpdateState.NeedsPermission ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()) {
            install(waiting.info)
        }
    }

    /** Android's "Install unknown apps" screen for this app, or the nearest settings screen this device has. */
    fun permissionScreenIntent(): Intent {
        val pm = context.packageManager
        val candidates = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                add(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
            }
            add(Intent(Settings.ACTION_SECURITY_SETTINGS))
            add(Intent(Settings.ACTION_SETTINGS))
        }
        return candidates.firstOrNull { it.resolveActivity(pm) != null } ?: candidates.last()
    }

    fun dismissPermissionRequest() {
        val waiting = _state.value as? UpdateState.NeedsPermission ?: return
        _state.value = UpdateState.Available(waiting.info)
    }

    private fun download(info: UpdateInfo): File {
        downloadDir.deleteRecursively()
        downloadDir.mkdirs()
        val file = File(downloadDir, "LumoraTV-${info.versionCode}.apk")
        open(info.apkUrl, binary = true).use { response ->
            val body = response.body!!
            val total = body.contentLength().takeIf { it > 0 }
            var done = 0L
            body.byteStream().use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var shown = -1
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        done += n
                        val percent = if (total == null) 0 else (done * 100 / total).toInt()
                        if (total == null || percent != shown) {
                            shown = percent
                            _state.value = UpdateState.Downloading(info, total?.let { done.toFloat() / it })
                        }
                    }
                }
            }
            if (total != null && done != total) throw IOException("the download stopped at ${done * 100 / total} %.")
        }
        return file
    }

    /** The download must be this app, newer, and signed with the same key, or Android would refuse it anyway. */
    private fun verify(apk: File) {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        val archive = pm.getPackageArchiveInfo(apk.path, flags)
            ?: throw IOException("The download isn't an Android app (APK). Check the url in the update file.")
        if (archive.packageName != context.packageName) {
            throw IOException("The download is a different app (${archive.packageName}), not Lumora TV.")
        }
        val installedCode = installedVersionCode
        if (PackageInfoCompat.getLongVersionCode(archive) <= installedCode) {
            throw IOException("The download is version ${archive.versionName}, which isn't newer than this one ($installedVersionName).")
        }
        val theirs = signers(archive)
        val ours = signers(pm.getPackageInfo(context.packageName, flags))
        if (theirs.isEmpty() || theirs != ours) {
            throw IOException(
                "The download is signed with a different key, so Android can't install it over this copy " +
                    "without deleting your playlists. Publish a release build signed with your own key.",
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun signers(info: PackageInfo): Set<Signature> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners?.toSet().orEmpty()
        } else {
            info.signatures?.toSet().orEmpty()
        }

    private fun commit(apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            // Android 12+: no confirmation once Lumora TV installed itself (it is then the installer of record).
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                session.openWrite("base.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                val callback = Intent(context, UpdateInstallReceiver::class.java).setAction(ACTION_INSTALL_STATUS)
                val mutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                val pending = PendingIntent.getBroadcast(
                    context, sessionId, callback, PendingIntent.FLAG_UPDATE_CURRENT or mutable,
                )
                UpdateRelaunchReceiver.arm(context, true)
                session.commit(pending.intentSender)
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(sessionId) }
            throw e
        } finally {
            apk.delete() // the session holds its own copy
        }
    }

    /** From [UpdateInstallReceiver]. On success Android replaces and restarts the app, so there is nothing to do. */
    internal fun onInstallFailed(status: Int, message: String?) {
        UpdateRelaunchReceiver.arm(context, false)
        val info = when (val s = _state.value) {
            is UpdateState.Installing -> s.info
            is UpdateState.Downloading -> s.info
            is UpdateState.Available -> s.info
            else -> null
        }
        val text = when (status) {
            PackageInstaller.STATUS_FAILURE_ABORTED -> "The update was cancelled."
            PackageInstaller.STATUS_FAILURE_BLOCKED -> "This device doesn't allow installing the update."
            PackageInstaller.STATUS_FAILURE_STORAGE -> "Not enough free storage to install the update."
            PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                "Android refused the update" + (message?.let { ": $it" } ?: ".")
            else -> "The update couldn't be installed" + (message?.let { ": $it" } ?: ".")
        }
        _state.value = UpdateState.Failed(text, info)
    }

    private fun UpdateState.isBusy() =
        this is UpdateState.Downloading || this is UpdateState.Installing || this is UpdateState.NeedsPermission

    /**
     * Opens [url]. With an access token set, files of a GitHub release are fetched through the API, which
     * also works for private repositories; anything else is a plain download. The caller closes the response.
     */
    private fun open(url: String, binary: Boolean): Response {
        val token = settings.current.value.updateToken.trim()
        val ref = if (token.isEmpty()) null else GitHubReleases.parse(url)
        val request = if (ref == null) {
            Request.Builder().url(url).header("Cache-Control", "no-cache").build()
        } else {
            val release = call(
                Request.Builder().url(GitHubReleases.releaseApiUrl(ref))
                    .header("Authorization", "Bearer $token")
                    .header("Accept", "application/vnd.github+json")
                    .build(),
                gitHub = true,
            ).use { it.body!!.string() }
            val asset = GitHubReleases.assetApiUrl(release, ref.fileName)
                ?: throw IOException("The GitHub release has no file called ${ref.fileName}.")
            // GitHub answers with a redirect to a storage link; OkHttp drops the token when following it.
            Request.Builder().url(asset)
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/octet-stream")
                .build()
        }
        return call(request, gitHub = ref != null)
    }

    /** The server answered with an error: trying again right away won't help. */
    private class HttpFailure(message: String) : IOException(message)

    private fun call(request: Request, gitHub: Boolean): Response {
        val response = http.newCall(request).execute()
        if (response.isSuccessful) return response
        response.close()
        throw HttpFailure(
            when {
                gitHub && (response.code == 401 || response.code == 403) -> "GitHub refused the access token (HTTP ${response.code})."
                gitHub && response.code == 404 ->
                    "GitHub couldn't find that release. Check the address, and that the token can read the repository."
                response.code == 404 -> "Nothing found at that address (HTTP 404)."
                else -> "The server answered HTTP ${response.code}."
            },
        )
    }

    private fun Exception.readable(): String = when (this) {
        is java.net.UnknownHostException -> "can't reach ${message ?: "the server"}. Check the address and the network."
        is java.net.SocketTimeoutException -> "the server didn't answer in time."
        is java.net.ConnectException -> "can't connect to the server."
        is java.net.ProtocolException, is java.io.EOFException, is java.net.SocketException ->
            "the connection broke off. Try again."
        else -> message ?: javaClass.simpleName
    }

    companion object {
        const val ACTION_INSTALL_STATUS = "com.lumora.tv.UPDATE_INSTALL_STATUS"
        private val AUTO_START_DELAY_MS = TimeUnit.SECONDS.toMillis(20)
        private val AUTO_EVERY_MS = TimeUnit.HOURS.toMillis(1)
        private val AUTO_INTERVAL_MS = TimeUnit.DAYS.toMillis(1)
        private val RETRY_DELAY_MS = TimeUnit.SECONDS.toMillis(2)
    }
}

/** Receives the result of an install session; shows Android's confirmation screen when it asks for one. */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Updater.ACTION_INSTALL_STATUS) return
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> Unit
            else -> (context.applicationContext as LumoraApp).container.updater
                .onInstallFailed(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
        }
    }
}

/**
 * Opens the new version after Lumora TV updated itself (Android leaves the user on the home screen).
 * Only after an update started in Settings, not after installs by cable. Android 10+ may not allow
 * an app to open itself from the background; the user then opens it from the launcher.
 */
class UpdateRelaunchReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_RELAUNCH, false)) return
        prefs.edit().remove(KEY_RELAUNCH).apply()
        runCatching {
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    companion object {
        private const val PREFS = "update"
        private const val KEY_RELAUNCH = "relaunch_after_update"

        /** Saved synchronously: the process is killed as soon as the install starts. */
        fun arm(context: Context, relaunch: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_RELAUNCH, relaunch).commit()
        }
    }
}
