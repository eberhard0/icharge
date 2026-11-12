package com.iameberhard.icharge

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.cos
import kotlin.math.sqrt

data class Place(val name: String, val lat: Double, val lon: Double)

/** A road route as a polyline with the cumulative distance (km) at every vertex. */
class Route(val lat: DoubleArray, val lon: DoubleArray, val cumKm: DoubleArray, val durationS: Double, val staticDurationS: Double = durationS, val live: Boolean = false) {
    /** Extra minutes the current traffic adds over free-flowing roads. */
    val delayS: Double get() = (durationS - staticDurationS).coerceAtLeast(0.0)
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
        put("lat", JSONArray(lat.toList())); put("lon", JSONArray(lon.toList())); put("cum", JSONArray(cumKm.toList()))
        put("dur", durationS); put("static", staticDurationS); put("live", live)
    }

    companion object {
        fun fromJson(o: JSONObject): Route {
            fun arr(k: String) = o.getJSONArray(k).let { a -> DoubleArray(a.length()) { a.getDouble(it) } }
            return Route(arr("lat"), arr("lon"), arr("cum"), o.optDouble("dur", 0.0), o.optDouble("static", o.optDouble("dur", 0.0)), o.optBoolean("live", false))
        }

        fun build(lats: DoubleArray, lons: DoubleArray, durationS: Double, staticDurationS: Double = durationS, live: Boolean = false): Route {
            val cum = DoubleArray(lats.size)
            for (i in 1 until lats.size) cum[i] = cum[i - 1] + distanceM(lats[i - 1], lons[i - 1], lats[i], lons[i]) / 1000.0
            return Route(lats, lons, cum, durationS, staticDurationS, live)
        }
    }
}

data class Projection(val km: Double, val offKm: Double)

/** The time left to the destination with current traffic, refreshed every few minutes on a trip. */
data class Eta(val remainingS: Double, val delayS: Double, val live: Boolean, val atMs: Long)

/**
 * Road routing: Google's Routes API with live traffic when the app has a Google key (restricted to
 * this package and certificate, sent as the X-Android-* headers), otherwise OSRM without traffic.
 * Place search is Nominatim either way.
 */
object Routing {
    fun hasGoogle(): Boolean = BuildConfig.GOOGLE_MAPS_KEY.isNotEmpty()

    fun route(context: android.content.Context, fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): Route =
        if (hasGoogle()) googleRoute(context, fromLat, fromLon, toLat, toLon) else osrmRoute(fromLat, fromLon, toLat, toLon)

    /** Just the time: the geometry stays, only duration / delay are read. */
    fun eta(context: android.content.Context, fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): Eta {
        val r = route(context, fromLat, fromLon, toLat, toLon)
        return Eta(r.durationS, r.delayS, r.live, System.currentTimeMillis())
    }

    private fun googleRoute(context: android.content.Context, fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): Route {
        val body = JSONObject()
            .put("origin", JSONObject().put("location", JSONObject().put("latLng", JSONObject().put("latitude", fromLat).put("longitude", fromLon))))
            .put("destination", JSONObject().put("location", JSONObject().put("latLng", JSONObject().put("latitude", toLat).put("longitude", toLon))))
            .put("travelMode", "DRIVE")
            .put("routingPreference", "TRAFFIC_AWARE")
            .put("polylineQuality", "HIGH_QUALITY")
            .put("languageCode", "id-ID")
        val headers = mapOf(
            "X-Goog-Api-Key" to BuildConfig.GOOGLE_MAPS_KEY,
            "X-Goog-FieldMask" to "routes.duration,routes.staticDuration,routes.distanceMeters,routes.polyline.encodedPolyline",
            "X-Android-Package" to context.packageName,
            "X-Android-Cert" to signingSha1(context),
        )
        val o = JSONObject(Net.postJson("https://routes.googleapis.com/directions/v2:computeRoutes", body.toString(), headers))
        val routes = o.optJSONArray("routes") ?: throw java.io.IOException("No road route found")
        if (routes.length() == 0) throw java.io.IOException("No road route found")
        val r = routes.getJSONObject(0)
        val (lats, lons) = decodePolyline(r.getJSONObject("polyline").getString("encodedPolyline"))
        val dur = seconds(r.optString("duration"))
        val stat = seconds(r.optString("staticDuration")).takeIf { it > 0 } ?: dur
        return Route.build(lats, lons, dur, stat, live = true)
    }

    private fun seconds(v: String): Double = v.removeSuffix("s").toDoubleOrNull() ?: 0.0

    /** Google's encoded polyline (precision 1e-5). */
    private fun decodePolyline(enc: String): Pair<DoubleArray, DoubleArray> {
        val lats = ArrayList<Double>(); val lons = ArrayList<Double>()
        var i = 0; var lat = 0; var lon = 0
        while (i < enc.length) {
            var shift = 0; var result = 0; var b: Int
            do { b = enc[i++].code - 63; result = result or ((b and 0x1f) shl shift); shift += 5 } while (b >= 0x20)
            lat += if (result and 1 != 0) (result shr 1).inv() else result shr 1
            shift = 0; result = 0
            do { b = enc[i++].code - 63; result = result or ((b and 0x1f) shl shift); shift += 5 } while (b >= 0x20)
            lon += if (result and 1 != 0) (result shr 1).inv() else result shr 1
            lats += lat / 1e5; lons += lon / 1e5
        }
        return lats.toDoubleArray() to lons.toDoubleArray()
    }

    /** SHA-1 of the app's signing certificate, which the Android-restricted key is tied to. */
    private fun signingSha1(context: android.content.Context): String {
        val pm = context.packageManager
        val cert = if (android.os.Build.VERSION.SDK_INT >= 28) {
            val info = pm.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES)
            info.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_SIGNATURES).signatures?.firstOrNull()?.toByteArray()
        } ?: return ""
        return java.security.MessageDigest.getInstance("SHA-1").digest(cert).joinToString("") { "%02X".format(it) }
    }

    private fun osrmRoute(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): Route {
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
