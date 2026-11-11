package com.iameberhard.icharge

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Where the phone is, as (lat, lon); null until the first fix. Fused provider when the OS has it. */
class Locator(private val context: Context) {
    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    data class Fix(val lat: Double, val lon: Double, val speedKmh: Double, val timeMs: Long)

    private val _position = MutableStateFlow<Pair<Double, Double>?>(null)
    val position: StateFlow<Pair<Double, Double>?> = _position
    private val _fix = MutableStateFlow<Fix?>(null)
    val fix: StateFlow<Fix?> = _fix
    private var listening = false

    // All four callbacks: before Android 11 the interface has no default implementations.
    private val listener = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            _position.value = loc.latitude to loc.longitude
            _fix.value = Fix(loc.latitude, loc.longitude, if (loc.hasSpeed()) loc.speed * 3.6 else 0.0, loc.time.takeIf { it > 0 } ?: System.currentTimeMillis())
        }
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
    }

    val granted: Boolean
        get() = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun start() {
        if (listening || !granted) return
        val providers = buildList {
            if (Build.VERSION.SDK_INT >= 31) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.GPS_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
        }.filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        try {
            var best: Location? = null
            for (p in providers) {
                val l = lm.getLastKnownLocation(p) ?: continue
                if (best == null || l.time > best.time) best = l
            }
            best?.let { _position.value = it.latitude to it.longitude }
            for (p in providers) lm.requestLocationUpdates(p, 5_000L, 10f, listener, Looper.getMainLooper())
            listening = providers.isNotEmpty()
        } catch (_: SecurityException) {
        }
    }

    fun stop() {
        if (!listening) return
        listening = false
        lm.removeUpdates(listener)
    }
}
