package com.jlock.heyjason

import android.app.Application

/** Runs first whenever the app starts — including in the background for alarms and syncing. */
class HeyJasonApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        ShopStore.init(this)
        Notifs.createChannels(this)
        Sync.install(this)
    }
}
