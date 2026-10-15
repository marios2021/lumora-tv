package com.lumora.tv

import android.app.Application

class LumoraApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.autoRefresher.start()
    }
}
