package com.iameberhard.icharge

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class TripState(
    val dest: Place,
    val route: Route,
    val chargers: List<NearCharger>,
    val car: Car,
    /** The last known battery and where/when it was known; the estimate runs from here. */
    val anchorPct: Double,
    val anchorKm: Double,
    val anchorTimeMs: Long,
    val slowHours: Double,
    val alongKm: Double,
    val offRouteKm: Double,
    val lastFixMs: Long,
    val ignored: Set<String>,
    val ignoreAll: Boolean,
    val plan: Plan,
    val eta: Eta? = null,
) {
    val pctNow: Double get() = anchorPct - (alongKm - anchorKm) / (car.fullRangeKm / 100.0) - slowHours * car.idleDrainPctPerHour
    val remainingKm: Double get() = route.totalKm - alongKm
}

/** The current trip, its live battery estimate and plan; survives app restarts. */
class TripStore(private val context: Context, private val stations: StateFlow<List<Station>>) {
    private val prefs = context.getSharedPreferences("icharge", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val file = File(context.filesDir, "trip.json")

    private val _state = MutableStateFlow<TripState?>(null)
    val state: StateFlow<TripState?> = _state
    val busy = MutableStateFlow(false)
    private val _traffic = MutableStateFlow(prefs.getBoolean("traffic", true))
    val traffic: StateFlow<Boolean> = _traffic
    fun setTraffic(on: Boolean) { _traffic.value = on; prefs.edit().putBoolean("traffic", on).apply() }
    private var etaJob: kotlinx.coroutines.Job? = null

    /** The battery the car itself reported (through Android Auto), with when; null if the car never did. */
    data class CarBattery(val pct: Double, val atMs: Long)
    private val _carBattery = MutableStateFlow<CarBattery?>(null)
    val carBattery: StateFlow<CarBattery?> = _carBattery

    /** The car reported its battery: trust it over the estimate (only re-anchors on a real change). */
    fun onCarBattery(pct: Double) {
        val now = System.currentTimeMillis()
        _carBattery.value = CarBattery(pct, now)
        val s = _state.value ?: return
        if (kotlin.math.abs(s.pctNow - pct) >= 1.0) setPct(pct)
    }

    var car: Car
        get() = Car(
            prefs.getFloat("fullRange", 400f).toDouble(), prefs.getFloat("reserve", 10f).toDouble(),
            prefs.getFloat("chargeTo", 80f).toDouble(), prefs.getFloat("idleDrain", 2f).toDouble(),
        )
        set(v) = prefs.edit().putFloat("fullRange", v.fullRangeKm.toFloat()).putFloat("reserve", v.reservePct.toFloat())
            .putFloat("chargeTo", v.chargeToPct.toFloat()).putFloat("idleDrain", v.idleDrainPctPerHour.toFloat()).apply()

    init {
        scope.launch { restore() }
    }

    private fun replan(s: TripState): TripState =
        s.copy(plan = TripPlanner.plan(s.route, s.chargers, s.car, s.alongKm, s.pctNow, s.ignored, s.ignoreAll))

    /** Starts a trip from the current position (or the route's start) with the battery the user typed. */
    fun start(dest: Place, route: Route, startPct: Double, here: Pair<Double, Double>?) {
        busy.value = true
        scope.launch {
            try {
                val chargers = TripPlanner.chargersNear(route, stations.value)
                val along = here?.let { route.project(it.first, it.second) }?.takeIf { it.offKm < 2.0 }?.km ?: 0.0
                val now = System.currentTimeMillis()
                val s = TripState(dest, route, chargers, car, startPct, along, now, 0.0, along, 0.0, now, emptySet(), false,
                    Plan(emptyList(), 0.0, null, null, null, true, ""), Eta(route.durationS, route.delayS, route.live, now))
                _state.value = replan(s)
                save()
                startEtaRefresh()
            } finally {
                busy.value = false
            }
        }
    }

    /** A GPS fix: moves the car along the route and drains the battery estimate. */
    fun onFix(lat: Double, lon: Double, speedKmh: Double, timeMs: Long) {
        val s = _state.value ?: return
        val p = s.route.project(lat, lon)
        val dtH = ((timeMs - s.lastFixMs).coerceIn(0, 120_000)) / 3_600_000.0
        val slow = s.slowHours + if (speedKmh < 15.0 && p.offKm < 2.0) dtH else 0.0
        // Only move forward along the route, and only while actually on it.
        val along = if (p.offKm < 2.0 && p.km > s.alongKm) p.km else s.alongKm
        val next = replan(s.copy(alongKm = along, offRouteKm = p.offKm, slowHours = slow, lastFixMs = timeMs))
        _state.value = next
        if (timeMs - (prefs.getLong("tripSaved", 0)) > 30_000) save()
    }

    /** The user read the dashboard (or charged): the estimate restarts from here. */
    fun setPct(pct: Double) {
        val s = _state.value ?: return
        _state.value = replan(s.copy(anchorPct = pct, anchorKm = s.alongKm, anchorTimeMs = System.currentTimeMillis(), slowHours = 0.0))
        save()
    }

    fun ignoreStop(stationId: String) {
        val s = _state.value ?: return
        _state.value = replan(s.copy(ignored = s.ignored + stationId))
        save()
    }

    fun clearIgnored() {
        val s = _state.value ?: return
        _state.value = replan(s.copy(ignored = emptySet()))
        save()
    }

    fun setIgnoreAll(on: Boolean) {
        val s = _state.value ?: return
        _state.value = replan(s.copy(ignoreAll = on))
        save()
    }

    fun updateCar(c: Car) {
        car = c
        val s = _state.value ?: return
        _state.value = replan(s.copy(car = c))
        save()
    }

    fun end() {
        etaJob?.cancel()
        _state.value = null
        file.delete()
    }

    /** Every 10 minutes, the time left with current traffic from where the car is (a few Routes calls per trip). */
    private fun startEtaRefresh() {
        etaJob?.cancel()
        etaJob = scope.launch {
            while (true) {
                kotlinx.coroutines.delay(10 * 60_000L)
                val s = _state.value ?: break
                val from = s.route.pointAt(s.alongKm)
                val eta = runCatching { Routing.eta(context, from.first, from.second, s.dest.lat, s.dest.lon) }.getOrNull() ?: continue
                _state.value?.let { cur -> _state.value = cur.copy(eta = eta) }
            }
        }
    }

    private fun save() {
        val s = _state.value ?: return
        val o = JSONObject().apply {
            put("dest", JSONObject().put("name", s.dest.name).put("lat", s.dest.lat).put("lon", s.dest.lon))
            put("route", s.route.toJson())
            put("anchorPct", s.anchorPct); put("anchorKm", s.anchorKm); put("anchorTime", s.anchorTimeMs)
            put("slowHours", s.slowHours); put("alongKm", s.alongKm); put("lastFix", s.lastFixMs)
            put("ignored", JSONArray(s.ignored.toList())); put("ignoreAll", s.ignoreAll)
        }
        runCatching { file.writeText(o.toString()) }
        prefs.edit().putLong("tripSaved", System.currentTimeMillis()).apply()
    }

    private suspend fun restore() {
        if (!file.exists()) return
        val o = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return
        // Stations load in the background; wait for them so the chargers can be matched.
        var tries = 0
        while (stations.value.isEmpty() && tries++ < 100) kotlinx.coroutines.delay(200)
        val d = o.getJSONObject("dest")
        val route = Route.fromJson(o.getJSONObject("route"))
        val ign = o.optJSONArray("ignored")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: emptySet()
        val s = TripState(
            Place(d.getString("name"), d.getDouble("lat"), d.getDouble("lon")), route, TripPlanner.chargersNear(route, stations.value), car,
            o.getDouble("anchorPct"), o.getDouble("anchorKm"), o.getLong("anchorTime"), o.optDouble("slowHours", 0.0),
            o.getDouble("alongKm"), 0.0, o.optLong("lastFix", System.currentTimeMillis()), ign, o.optBoolean("ignoreAll", false),
            Plan(emptyList(), 0.0, null, null, null, true, ""),
        )
        _state.value = replan(s.copy(eta = Eta(route.durationS, route.delayS, route.live, System.currentTimeMillis())))
        startEtaRefresh()
    }
}
