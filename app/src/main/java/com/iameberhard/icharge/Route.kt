package com.iameberhard.icharge

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.cos
import kotlin.math.sqrt

data class Place(val name: String, val lat: Double, val lon: Double)

/** A road route as a polyline with the cumulative distance (km) at every vertex. */
class Route(val lat: DoubleArray, val lon: DoubleArray, val cumKm: DoubleArray, val durationS: Double) {
    val totalKm: Double get() = if (cumKm.isEmpty()) 0.0 else cumKm.last()

    /** The point `km` along the route (clamped). */
    fun pointAt(km: Double): Pair<Double, Double> {
        if (lat.isEmpty()) return 0.0 to 0.0
        val k = km.coerceIn(0.0, totalKm)
        var i = cumKm.binarySearch(k)
        if (i < 0) i = -i - 2
        i = i.coerceIn(0, lat.size - 2)
        val seg = cumKm[i + 1] - cumKm[i]
        val f = if (seg <= 0) 0.0 else ((k - cumKm[i]) / seg).coerceIn(0.0, 1.0)
        return (lat[i] + (lat[i + 1] - lat[i]) * f) to (lon[i] + (lon[i + 1] - lon[i]) * f)
    }

    /** The vertices between two distances, with interpolated ends. */
    fun slice(fromKm: Double, toKm: Double): List<Pair<Double, Double>> {
        if (lat.size < 2 || toKm <= fromKm) return emptyList()
        val out = ArrayList<Pair<Double, Double>>()
        out += pointAt(fromKm)
        for (i in lat.indices) if (cumKm[i] > fromKm && cumKm[i] < toKm) out += lat[i] to lon[i]
        out += pointAt(toKm)
        return out
    }

    /** Where a position falls along the route: distance along it and how far off it is. */
    fun project(pLat: Double, pLon: Double): Projection {
        if (lat.size < 2) return Projection(0.0, Double.MAX_VALUE)
        val kx = 111.32 * cos(Math.toRadians(pLat))
        val ky = 110.57
        var bestKm = 0.0
        var bestOff = Double.MAX_VALUE
        for (i in 0 until lat.size - 1) {
            val ax = (lon[i] - pLon) * kx; val ay = (lat[i] - pLat) * ky
            val bx = (lon[i + 1] - pLon) * kx; val by = (lat[i + 1] - pLat) * ky
            val dx = bx - ax; val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 <= 0) 0.0 else ((-ax * dx - ay * dy) / len2).coerceIn(0.0, 1.0)
            val cx = ax + dx * t; val cy = ay + dy * t
            val d = sqrt(cx * cx + cy * cy)
            if (d < bestOff) {
                bestOff = d
                bestKm = cumKm[i] + (cumKm[i + 1] - cumKm[i]) * t
            }
        }
        return Projection(bestKm, bestOff)
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("lat", JSONArray(lat.toList())); put("lon", JSONArray(lon.toList())); put("cum", JSONArray(cumKm.toList())); put("dur", durationS)
    }

    companion object {
        fun fromJson(o: JSONObject): Route {
            fun arr(k: String) = o.getJSONArray(k).let { a -> DoubleArray(a.length()) { a.getDouble(it) } }
            return Route(arr("lat"), arr("lon"), arr("cum"), o.optDouble("dur", 0.0))
        }

        fun build(lats: DoubleArray, lons: DoubleArray, durationS: Double): Route {
            val cum = DoubleArray(lats.size)
            for (i in 1 until lats.size) cum[i] = cum[i - 1] + distanceM(lats[i - 1], lons[i - 1], lats[i], lons[i]) / 1000.0
            return Route(lats, lons, cum, durationS)
        }
    }
}

data class Projection(val km: Double, val offKm: Double)

/** Road routing by OSRM's public server, place search by Nominatim: both free, both OpenStreetMap. */
object Routing {
    fun route(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): Route {
        val url = "https://router.project-osrm.org/route/v1/driving/$fromLon,$fromLat;$toLon,$toLat?overview=full&geometries=geojson&steps=false"
        val o = JSONObject(Net.get(url))
        if (o.optString("code") != "Ok") throw java.io.IOException("No road route found (" + o.optString("code") + ")")
        val r = o.getJSONArray("routes").getJSONObject(0)
        val coords = r.getJSONObject("geometry").getJSONArray("coordinates")
        val lats = DoubleArray(coords.length()); val lons = DoubleArray(coords.length())
        for (i in 0 until coords.length()) { val c = coords.getJSONArray(i); lons[i] = c.getDouble(0); lats[i] = c.getDouble(1) }
        return Route.build(lats, lons, r.optDouble("duration", 0.0))
    }

    fun search(q: String): List<Place> {
        val url = "https://nominatim.openstreetmap.org/search?format=jsonv2&countrycodes=id&limit=8&q=" + java.net.URLEncoder.encode(q, "UTF-8")
        val a = JSONArray(Net.get(url))
        return (0 until a.length()).map { i ->
            val p = a.getJSONObject(i)
            Place(p.optString("display_name").ifBlank { q }, p.getDouble("lat"), p.getDouble("lon"))
        }
    }
}
