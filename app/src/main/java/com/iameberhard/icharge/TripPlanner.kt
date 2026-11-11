package com.iameberhard.icharge

/** How the car is modelled. The range at 100% is what the user sees on a full battery on this kind of road. */
data class Car(
    val fullRangeKm: Double = 400.0,
    val reservePct: Double = 10.0,
    val chargeToPct: Double = 80.0,
    /** Battery lost per hour while crawling in traffic (air-con, electronics), on top of distance. */
    val idleDrainPctPerHour: Double = 2.0,
)

data class NearCharger(val station: Station, val alongKm: Double, val offKm: Double)

data class Stop(val charger: NearCharger, val arrivePct: Double, val leavePct: Double)

data class Plan(
    val stops: List<Stop>,
    /** Estimated battery on arrival (negative = doesn't make it). */
    val arrivalPct: Double,
    /** Where the battery would hit 0% on the current charge if nothing is done; null if it won't. */
    val runoutKm: Double?,
    /** The red stretch: from the next charger to the point where the battery hits the reserve. */
    val redFromKm: Double?,
    val redToKm: Double?,
    val reachable: Boolean,
    val note: String,
) {
    val nextStop: Stop? get() = stops.firstOrNull()
}

object TripPlanner {

    /** Chargers within `maxOffKm` of the route, with where along it they sit. The route is thinned for speed. */
    fun chargersNear(route: Route, stations: List<Station>, maxOffKm: Double = 3.0): List<NearCharger> {
        if (route.lat.size < 2) return emptyList()
        val stride = maxOf(1, route.lat.size / 1500)
        val idx = (route.lat.indices step stride).toMutableList().apply { if (last() != route.lat.lastIndex) add(route.lat.lastIndex) }
        val thin = Route(DoubleArray(idx.size) { route.lat[idx[it]] }, DoubleArray(idx.size) { route.lon[idx[it]] }, DoubleArray(idx.size) { route.cumKm[idx[it]] }, route.durationS)
        val pad = maxOffKm / 100.0 + 0.01
        val minLat = route.lat.min() - pad; val maxLat = route.lat.max() + pad
        val minLon = route.lon.min() - pad; val maxLon = route.lon.max() + pad
        return stations.asSequence()
            .filter { it.lat in minLat..maxLat && it.lon in minLon..maxLon }
            .mapNotNull { s ->
                val p = thin.project(s.lat, s.lon)
                if (p.offKm <= maxOffKm) NearCharger(s, p.km, p.offKm) else null
            }
            .sortedBy { it.alongKm }
            .toList()
    }

    /**
     * From where the car is now with the battery it has, which chargers to stop at. Each stop is the
     * furthest usable charger before the reserve runs out (DC fast preferred, with a safety margin).
     */
    fun plan(route: Route, chargers: List<NearCharger>, car: Car, alongKm: Double, pct: Double, ignored: Set<String>, ignoreAll: Boolean): Plan {
        val total = route.totalKm
        val stops = ArrayList<Stop>()
        var along = alongKm
        var p = pct
        var redFrom: Double? = null
        var redTo: Double? = null
        val kmPerPct = car.fullRangeKm / 100.0
        repeat(12) {
            val toReserve = along + maxOf(0.0, p - car.reservePct) * kmPerPct
            val toEmpty = along + maxOf(0.0, p) * kmPerPct
            val arrival = p - (total - along) / kmPerPct
            if (toReserve >= total && !ignoreAll) {
                return Plan(stops, arrival, null, redFrom, redTo, true, if (stops.isEmpty()) "No charging needed" else "${stops.size} stop" + (if (stops.size > 1) "s" else ""))
            }
            if (ignoreAll) {
                val runout = if (toEmpty < total) toEmpty else null
                return Plan(stops, arrival, runout, runout, if (runout != null) total else null, runout == null, if (runout == null) "No stops: arrives with ${arrival.toInt()}%" else "Without charging the car runs out ${(total - runout).toInt()} km short")
            }
            val candidates = chargers.filter { it.alongKm > along + 0.5 && it.alongKm <= toReserve && it.station.id !in ignored }
            if (candidates.isEmpty()) {
                val runout = if (toEmpty < total) toEmpty else null
                return Plan(stops, arrival, runout, toReserve.coerceAtMost(total), total, false, "No charger within reach before the reserve: nearest ahead is " + (chargers.firstOrNull { it.alongKm > along && it.station.id !in ignored }?.let { "${(it.alongKm - along).toInt()} km away" } ?: "none"))
            }
            val margin = minOf(15.0, (toReserve - along) * 0.2)
            val safe = candidates.filter { it.alongKm <= toReserve - margin }
            val pool = safe.ifEmpty { candidates }
            val choice = pool.filter { it.station.fast }.maxByOrNull { it.alongKm } ?: pool.maxByOrNull { it.alongKm }!!
            val arrivePct = p - (choice.alongKm - along) / kmPerPct
            stops += Stop(choice, arrivePct, car.chargeToPct)
            if (redFrom == null) { redFrom = choice.alongKm; redTo = toReserve.coerceAtMost(total) }
            along = choice.alongKm
            p = car.chargeToPct
        }
        return Plan(stops, 0.0, null, redFrom, redTo, false, "Too many stops; check the car's range")
    }
}
