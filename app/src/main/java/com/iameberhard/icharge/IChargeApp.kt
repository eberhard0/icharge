package com.iameberhard.icharge

import android.app.Application

/** One set of stores for the phone UI and the Android Auto screens. */
class IChargeApp : Application() {
    val stations: StationStore by lazy { StationStore(this) }
    val spend: SpendStore by lazy { SpendStore(this) }
    val trip: TripStore by lazy { TripStore(this, stations.stations) }
    val locator: Locator by lazy { Locator(this) }

    override fun onCreate() {
        super.onCreate()
        Finder.ensureRunning(this)
    }

    companion object {
        fun of(context: android.content.Context): IChargeApp = context.applicationContext as IChargeApp
    }
}
