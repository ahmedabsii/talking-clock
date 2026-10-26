package com.absi.talkingclock

import android.app.Application
import android.content.Context
import com.absi.talkingclock.notify.StatusNotification

class App : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        StatusNotification.createChannels(this)
    }
}

/** The app-wide object graph, reachable from activities, receivers, services and workers. */
val Context.graph: AppGraph
    get() = (applicationContext as App).graph
