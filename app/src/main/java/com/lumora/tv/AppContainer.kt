package com.lumora.tv

import android.content.Context
import com.lumora.tv.data.db.LumoraDatabase
import com.lumora.tv.data.epg.EpgRepository
import com.lumora.tv.data.remote.XtreamClient
import com.lumora.tv.data.repo.AutoRefresher
import com.lumora.tv.data.repo.PlaylistRepository
import com.lumora.tv.data.repo.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Manual dependency wiring. One instance lives in [LumoraApp]. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    /** For work that must outlive a screen, such as EPG downloads. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .addInterceptor { chain ->
            val request = chain.request()
            if (request.header("User-Agent") != null) {
                chain.proceed(request)
            } else {
                chain.proceed(request.newBuilder().header("User-Agent", USER_AGENT).build())
            }
        }
        .build()

    val database: LumoraDatabase = LumoraDatabase.create(appContext)
    val settings = SettingsRepository(appContext, appScope)
    val playlists = PlaylistRepository(appContext, database, http, XtreamClient(http))
    val epg = EpgRepository(appContext, database, http, appScope)
    val autoRefresher = AutoRefresher(playlists, epg, settings, appScope)

    companion object {
        const val USER_AGENT = "LumoraTV/0.1 (Linux; Android TV)"
    }
}
