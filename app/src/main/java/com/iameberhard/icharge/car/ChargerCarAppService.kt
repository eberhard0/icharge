package com.iameberhard.icharge.car

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator
import androidx.car.app.CarContext
import androidx.car.app.hardware.CarHardwareManager
import androidx.car.app.hardware.common.CarValue
import androidx.car.app.hardware.info.EnergyLevel
import androidx.car.app.hardware.info.CarInfo
import com.iameberhard.icharge.IChargeApp

/** Reads the EV battery level the head unit shares (needs the CAR_FUEL permission, asked once on the car). */
object CarBatteryReader {
    private const val PERMISSION = "com.google.android.gms.permission.CAR_FUEL"

    fun start(carContext: CarContext) {
        if (carContext.checkSelfPermission(PERMISSION) == android.content.pm.PackageManager.PERMISSION_GRANTED) listen(carContext)
        else runCatching {
            carContext.requestPermissions(listOf(PERMISSION)) { granted, _ -> if (PERMISSION in granted) listen(carContext) }
        }
    }

    private fun listen(carContext: CarContext) {
        runCatching {
            val info: CarInfo = carContext.getCarService(CarHardwareManager::class.java).carInfo
            info.addEnergyLevelListener(carContext.mainExecutor) { level: EnergyLevel ->
                val v = level.batteryPercent
                if (v.status == CarValue.STATUS_SUCCESS) v.value?.let { IChargeApp.of(carContext).trip.onCarBattery(it.toDouble()) }
            }
        }
    }
}

/**
 * iCharge on Android Auto: a "points of interest" app, so the car draws the map and the charger
 * markers itself and we supply the list, the details and the Navigate hand-off to Google Maps.
 * Sideloaded, so any host is accepted (Android Auto needs "Unknown sources" on in its developer settings).
 */
class ChargerCarAppService : CarAppService() {
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = object : Session() {
        override fun onCreateScreen(intent: Intent): Screen {
            CarBatteryReader.start(carContext)
            return ChargerListScreen(carContext)
        }
    }
}
