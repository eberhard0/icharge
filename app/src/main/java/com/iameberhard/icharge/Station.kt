package com.iameberhard.icharge

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** The plug standards seen in Indonesia. DC ones are the fast chargers. */
enum class Plug(val label: String, val dc: Boolean) {
    CCS2("CCS2", true),
    GBT_DC("GB/T DC", true),
    CHADEMO("CHAdeMO", true),
    CCS1("CCS1", true),
    TESLA("Tesla", true),
    TYPE2("Type 2", false),
    GBT_AC("GB/T AC", false),
    TYPE1("Type 1", false),
    OTHER("Other", false),
}

enum class Source(val label: String, val licence: String) {
    OCM("Open Charge Map", "CC BY 4.0"),
    OSM("OpenStreetMap", "ODbL"),
}

data class Connector(
    val plug: Plug,
    val powerKw: Double?,
    val dc: Boolean,
    val count: Int,
    /** What the source called it, for the detail view. */
    val raw: String,
)

data class Station(
    val id: String,
    val source: Source,
    val name: String,
    val address: String,
    val lat: Double,
    val lon: Double,
    val operator: String,
    val connectors: List<Connector>,
    val cost: String,
    val hours: String,
    val access: String,
    val status: String,
    val note: String,
    val verified: String,
    val url: String,
) {
    val fast: Boolean get() = connectors.any { it.dc }
    val maxKw: Double? get() = connectors.mapNotNull { it.powerKw }.maxOrNull()
    val plugs: List<Plug> get() = connectors.map { it.plug }.distinct()
    fun has(plug: Plug) = connectors.any { it.plug == plug }

    /** One line for the list: "CCS2 120 kW · Type 2 22 kW". */
    val plugLine: String
        get() = connectors
            .groupBy { it.plug }
            .map { (plug, cs) ->
                val kw = cs.mapNotNull { it.powerKw }.maxOrNull()
                plug.label + (if (kw != null) " ${formatKw(kw)}" else "")
            }
            .joinToString(" · ")
            .ifBlank { "Plug type not listed" }

    fun matches(text: String): Boolean {
        if (text.isBlank()) return true
        val q = text.trim().lowercase()
        return name.lowercase().contains(q) || address.lowercase().contains(q) || operator.lowercase().contains(q)
    }
}

fun formatKw(kw: Double): String = if (kw == kw.toLong().toDouble()) "${kw.toLong()} kW" else "%.1f kW".format(kw)

fun formatDistance(m: Double): String = when {
    m < 1000 -> "${m.toInt()} m"
    m < 10_000 -> "%.1f km".format(m / 1000)
    else -> "${(m / 1000).toInt()} km"
}

/** Great-circle distance in metres. */
fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6_371_000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2) * sin(dLat / 2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
    return 2 * r * atan2(sqrt(a), sqrt(1 - a))
}

/** Which plug a source's free-text name means. Titles first, then Open Charge Map's numeric ids. */
fun plugOf(title: String, ocmId: Int = -1): Plug {
    val t = title.lowercase()
    return when {
        "ccs" in t && ("type 2" in t || "type2" in t || "combo 2" in t) -> Plug.CCS2
        "ccs" in t && ("type 1" in t || "type1" in t || "combo 1" in t) -> Plug.CCS1
        "ccs2" in t || "type2_combo" in t -> Plug.CCS2
        "ccs1" in t || "type1_combo" in t -> Plug.CCS1
        "chademo" in t -> Plug.CHADEMO
        ("gb/t" in t || "gb-t" in t || "gb_" in t || "gbt" in t) && "dc" in t -> Plug.GBT_DC
        "gb/t" in t || "gb-t" in t || "gb_" in t || "gbt" in t -> Plug.GBT_AC
        "tesla" in t || "nacs" in t -> Plug.TESLA
        "type 2" in t || "type2" in t || "mennekes" in t || "62196-2" in t -> Plug.TYPE2
        "type 1" in t || "type1" in t || "j1772" in t -> Plug.TYPE1
        else -> when (ocmId) {
            33 -> Plug.CCS2
            32 -> Plug.CCS1
            2 -> Plug.CHADEMO
            1040 -> Plug.GBT_DC
            1039 -> Plug.GBT_AC
            25, 1036 -> Plug.TYPE2
            1 -> Plug.TYPE1
            8, 27, 30, 1038 -> Plug.TESLA
            else -> Plug.OTHER
        }
    }
}
