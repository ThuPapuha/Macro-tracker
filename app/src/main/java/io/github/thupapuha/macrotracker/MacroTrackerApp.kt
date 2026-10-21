package io.github.thupapuha.macrotracker

import android.app.Application

class MacroTrackerApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.summarySync.start()
        container.groupNotifier.start()
    }
}
