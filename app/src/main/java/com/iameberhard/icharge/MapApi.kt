package com.iameberhard.icharge

/**
 * What the rest of the app needs from a map, implemented once per edition: the "google" flavour
 * (Google map, live traffic) and the "open" flavour (MapLibre + OpenStreetMap tiles). Each flavour
 * provides `MapFlavor`, `rememberMapController()`, `ChargerMap(...)` and `MiniMap(...)`.
 */
interface MapController {
    suspend fun frame(points: List<Pair<Double, Double>>)
    suspend fun centre(lat: Double, lon: Double, zoom: Double = 13.0)
    suspend fun frameJakarta()
}

/** What a trip draws over the map, as (lat, lon) pairs. */
data class TripOverlay(
    val route: List<Pair<Double, Double>>,
    val red: List<Pair<Double, Double>>,
    val stop: Pair<Double, Double>?,
    val runout: Pair<Double, Double>?,
    val dest: Pair<Double, Double>?,
)
