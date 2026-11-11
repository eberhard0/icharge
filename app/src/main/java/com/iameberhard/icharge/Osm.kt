package com.iameberhard.icharge

import org.json.JSONObject

/** OpenStreetMap charging stations (amenity=charging_station) across Indonesia, via Overpass. */
object Osm {
    private const val BBOX = "-11.5,94.5,6.5,141.5"
    const val ENDPOINT = "https://overpass-api.de/api/interpreter"
    val query = """[out:json][timeout:120];
(
  node["amenity"="charging_station"]($BBOX);
  way["amenity"="charging_station"]($BBOX);
);
out center tags;"""

    fun fetch(): String = Net.post(ENDPOINT, "data=" + java.net.URLEncoder.encode(query, "UTF-8"))

    fun parse(text: String): List<Station> {
        val elements = JSONObject(text).optJSONArray("elements") ?: return emptyList()
        val out = ArrayList<Station>()
        for (i in 0 until elements.length()) {
            val e = elements.optJSONObject(i) ?: continue
            val centre = e.optJSONObject("center")
            val lat = (centre ?: e).optDouble("lat", Double.NaN)
            val lon = (centre ?: e).optDouble("lon", Double.NaN)
            if (lat.isNaN() || lon.isNaN()) continue
            val tags = e.optJSONObject("tags") ?: JSONObject()
            val operator = tags.optString("operator").ifBlank { tags.optString("brand") }
            val connectors = ArrayList<Connector>()
            for (key in tags.keys()) {
                if (!key.startsWith("socket:") || key.count { it == ':' } != 1) continue
                val socket = key.removePrefix("socket:")
                val count = tags.optString(key).toIntOrNull() ?: 1
                if (count == 0) continue
                val kw = tags.optString("$key:output").lowercase().removeSuffix("kw").trim().replace(",", ".").toDoubleOrNull()
                val plug = plugOf(socket)
                val dc = plug.dc || socket.endsWith("_dc") || socket.contains("combo") || socket == "chademo"
                connectors += Connector(plug, kw, dc, count, socket)
            }
            val address = listOf(
                tags.optString("addr:housename"),
                (tags.optString("addr:street") + " " + tags.optString("addr:housenumber")).trim(),
                tags.optString("addr:suburb"), tags.optString("addr:city"),
            ).filter { it.isNotBlank() }.joinToString(", ")
            val type = e.optString("type")
            val id = e.optLong("id")
            val fee = tags.optString("fee")
            out += Station(
                id = "osm-$type-$id",
                source = Source.OSM,
                name = tags.optString("name").ifBlank { operator.ifBlank { "Charging station" } },
                address = address,
                lat = lat, lon = lon,
                operator = operator,
                connectors = connectors,
                cost = listOf(tags.optString("charge"), if (fee == "no") "Free" else "").filter { it.isNotBlank() }.joinToString(", "),
                hours = tags.optString("opening_hours"),
                access = when (tags.optString("access")) { "private" -> "Private"; "customers" -> "Customers only"; "yes", "public" -> "Public"; else -> "" },
                status = "",
                note = listOf(tags.optString("description"), tags.optString("note")).filter { it.isNotBlank() }.joinToString("\n"),
                verified = "",
                url = "https://www.openstreetmap.org/$type/$id",
            )
        }
        return out
    }
}
