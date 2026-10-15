package com.lumora.tv

import android.app.Application
import kotlinx.coroutines.launch

class LumoraApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.autoRefresher.start()
        // Alarms don't survive an app update: arm scheduled recordings again, close interrupted ones.
        container.appScope.launch { runCatching { container.recordings.restore(serviceRunning = false) } }
    }
}
