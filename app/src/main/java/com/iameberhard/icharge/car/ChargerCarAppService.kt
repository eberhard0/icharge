package com.iameberhard.icharge.car

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

/**
 * iCharge on Android Auto: a "points of interest" app, so the car draws the map and the charger
 * markers itself and we supply the list, the details and the Navigate hand-off to Google Maps.
 * Sideloaded, so any host is accepted (Android Auto needs "Unknown sources" on in its developer settings).
 */
class ChargerCarAppService : CarAppService() {
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = object : Session() {
        override fun onCreateScreen(intent: Intent): Screen = ChargerListScreen(carContext)
    }
}
