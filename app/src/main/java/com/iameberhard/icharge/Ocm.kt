package com.iameberhard.icharge

import org.json.JSONArray
import org.json.JSONObject

/**
 * Open Charge Map (openchargemap.org, CC BY 4.0): the community registry most Indonesian chargers are
 * listed in, some of them credited to PLN. Reading needs a free API key (My Profile → My Apps).
 */
object Ocm {
    // The whole country: trips leave Jakarta.
    val url = "https://api.openchargemap.io/v3/poi/?output=json&countrycode=ID" +
        "&maxresults=10000&compact=false&verbose=false&client=icharge"

    fun fetch(key: String): String = Net.get(url, mapOf("X-API-Key" to key))

    fun parse(text: String): List<Station> {
        val arr = JSONArray(text)
        val out = ArrayList<Station>(arr.length())
        for (i in 0 until arr.length()) {
            val p = arr.optJSONObject(i) ?: continue
            val addr = p.optJSONObject("AddressInfo") ?: continue
            val lat = addr.optDouble("Latitude", Double.NaN)
            val lon = addr.optDouble("Longitude", Double.NaN)
            if (lat.isNaN() || lon.isNaN()) continue
            val id = p.optInt("ID")
            val connectors = ArrayList<Connector>()
            val conns = p.optJSONArray("Connections") ?: JSONArray()
            for (j in 0 until conns.length()) {
                val c = conns.optJSONObject(j) ?: continue
                val type = c.optJSONObject("ConnectionType")
                val title = type?.optString("Title") ?: ""
                val typeId = type?.optInt("ID", -1) ?: c.optInt("ConnectionTypeID", -1)
                val plug = plugOf(title, typeId)
                val kw = c.optDouble("PowerKW", Double.NaN).takeIf { !it.isNaN() && it > 0 }
                val currentId = c.optJSONObject("CurrentType")?.optInt("ID", -1) ?: c.optInt("CurrentTypeID", -1)
                val level = c.optJSONObject("Level")
                val dc = plug.dc || currentId == 30 || (level?.optBoolean("IsFastChargeCapable") == true)
                connectors += Connector(plug, kw, dc, c.optInt("Quantity", 1).coerceAtLeast(1), title.ifBlank { "Connector $typeId" })
            }
            val addressLine = listOf(
                addr.optString("AddressLine1"), addr.optString("AddressLine2"),
                addr.optString("Town"), addr.optString("StateOrProvince"),
            ).map { it.trim() }.filter { it.isNotEmpty() && it != "null" }.distinct().joinToString(", ")
            val operator = p.optJSONObject("OperatorInfo")?.optString("Title")?.takeIf { it != "null" } ?: ""
            val status = p.optJSONObject("StatusType")?.optString("Title")?.takeIf { it != "null" } ?: ""
            val access = p.optJSONObject("UsageType")?.optString("Title")?.takeIf { it != "null" } ?: ""
            val note = listOf(p.optString("GeneralComments"), addr.optString("AccessComments"))
                .map { it.trim() }.filter { it.isNotEmpty() && it != "null" }.joinToString("\n")
            out += Station(
                id = "ocm-$id",
                source = Source.OCM,
                name = addr.optString("Title").ifBlank { operator.ifBlank { "Charging station" } },
                address = addressLine,
                lat = lat, lon = lon,
                operator = operator,
                connectors = connectors,
                cost = p.optString("UsageCost").takeIf { it != "null" } ?: "",
                hours = "",
                access = access,
                status = status,
                note = note,
                verified = (p.optString("DateLastVerified").takeIf { it != "null" && it.isNotBlank() }
                    ?: p.optString("DateLastStatusUpdate").takeIf { it != "null" } ?: "").take(10),
                url = "https://openchargemap.org/site/poi/details/$id",
            )
        }
        return out
    }
}
