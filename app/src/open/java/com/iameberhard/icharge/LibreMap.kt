package com.iameberhard.icharge

import android.content.Context
import android.graphics.PointF
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression.eq
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.expressions.Expression.literal
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.iconAnchor
import org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.iconImage
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.geojson.LineString
import androidx.core.content.ContextCompat
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleOpacity
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

object MapFlavor {
    const val TRAFFIC = false
    const val NAME = "OpenStreetMap (OpenFreeMap tiles, no traffic)"
    fun init(context: Context) { runCatching { MapLibre.getInstance(context.applicationContext) } }
}

/** Wraps the MapLibre view so the app can drive it the same way as the Google edition. */
class LibreController(context: Context) : MapController {
    val engine = StationMap(context) { id -> onTap?.invoke(id) }
    var onTap: ((String) -> Unit)? = null
    override suspend fun frame(points: List<Pair<Double, Double>>) = engine.framePoints(points)
    override suspend fun centre(lat: Double, lon: Double, zoom: Double) = engine.centreOn(lat to lon)
    override suspend fun frameJakarta() = engine.frameJakarta()
}

@Composable
fun rememberMapController(): MapController {
    val context = androidx.compose.ui.platform.LocalContext.current
    return androidx.compose.runtime.remember { LibreController(context) }
}

@Composable
fun ChargerMap(
    stations: List<Station>,
    here: Pair<Double, Double>?,
    selectedId: String?,
    @Suppress("UNUSED_PARAMETER") traffic: Boolean, // no traffic source in the open edition
    trip: TripOverlay?,
    map: MapController,
    onTap: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = map as LibreController
    c.onTap = onTap
    LaunchedEffect(trip) { c.engine.showTrip(trip?.route ?: emptyList(), trip?.red ?: emptyList(), trip?.stop, trip?.runout, trip?.dest) }
    StationMapView(c.engine, stations, here, selectedId, modifier)
}

/** A small, non-interactive map of one charger for the detail sheet. */
@Composable
fun MiniMap(s: Station, modifier: Modifier = Modifier) = MiniMapView(s, modifier)

/** The map with every charger as a dot: amber = DC fast, blue = AC. Tap a dot to open it. */
@Composable
fun StationMapView(
    map: StationMap,
    stations: List<Station>,
    here: Pair<Double, Double>?,
    selectedId: String?,
    modifier: Modifier = Modifier,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        lifecycle.addObserver(map.lifecycleObserver)
        onDispose {
            lifecycle.removeObserver(map.lifecycleObserver)
            map.destroy()
        }
    }
    LaunchedEffect(stations) { map.showStations(stations) }
    LaunchedEffect(here) { map.showPosition(here) }
    LaunchedEffect(selectedId) { map.showSelected(stations.firstOrNull { it.id == selectedId }) }
    AndroidView(factory = { map.view }, modifier = modifier)
}

class StationMap(context: Context, private val onTap: (String) -> Unit) {

    val view: MapView
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var stations: List<Station> = emptyList()
    private var position: Pair<Double, Double>? = null
    private var selected: Station? = null
    private var tripRoute: List<Pair<Double, Double>> = emptyList()
    private var tripRed: List<Pair<Double, Double>> = emptyList()
    private var tripStop: Pair<Double, Double>? = null
    private var tripRunout: Pair<Double, Double>? = null
    private var tripDest: Pair<Double, Double>? = null
    private var pendingCamera: (() -> Unit)? = null
    private var framedOnce = false

    private var started = false
    private var resumed = false
    private var destroyed = false

    val lifecycleObserver = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_START -> if (!started && !destroyed) { started = true; view.onStart() }
            Lifecycle.Event.ON_RESUME -> if (!resumed && !destroyed) { resumed = true; view.onResume() }
            Lifecycle.Event.ON_PAUSE -> if (resumed) { resumed = false; view.onPause() }
            Lifecycle.Event.ON_STOP -> if (started) { started = false; view.onStop() }
            else -> Unit
        }
    }

    init {
        MapLibre.getInstance(context)
        view = MapView(context)
        view.onCreate(null)
        view.getMapAsync { ready ->
            map = ready
            ready.uiSettings.apply {
                setLogoEnabled(false)
                setAttributionEnabled(false) // credited in Settings instead
                setCompassEnabled(false)
                setRotateGesturesEnabled(false)
                setTiltGesturesEnabled(false)
            }
            val json = context.assets.open("map_dark.json").bufferedReader().use { it.readText() }
            ready.setStyle(Style.Builder().fromJson(json)) { loaded ->
                // Trip layers first, so the charger dots draw on top of the route.
                loaded.addSource(GeoJsonSource(ROUTE))
                loaded.addLayer(LineLayer(ROUTE, ROUTE).withProperties(lineColor(AcColor.toArgb()), lineWidth(5f), lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND)))
                loaded.addSource(GeoJsonSource(RED))
                loaded.addLayer(LineLayer(RED, RED).withProperties(lineColor(RedLine.toArgb()), lineWidth(8f), lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND)))
                loaded.addSource(GeoJsonSource(STATIONS))
                loaded.addLayer(
                    CircleLayer(AC, STATIONS).withFilter(eq(get("kind"), literal("ac"))).withProperties(
                        circleRadius(7f), circleColor(AcColor.toArgb()),
                        circleStrokeWidth(2f), circleStrokeColor(android.graphics.Color.BLACK),
                    ),
                )
                loaded.addLayer(
                    CircleLayer(DC, STATIONS).withFilter(eq(get("kind"), literal("dc"))).withProperties(
                        circleRadius(8f), circleColor(FastColor.toArgb()),
                        circleStrokeWidth(2f), circleStrokeColor(android.graphics.Color.BLACK),
                    ),
                )
                loaded.addSource(GeoJsonSource(SELECTED))
                loaded.addLayer(
                    CircleLayer(SELECTED, SELECTED).withProperties(
                        circleRadius(14f), circleColor(android.graphics.Color.TRANSPARENT), circleOpacity(0f),
                        circleStrokeWidth(3f), circleStrokeColor(android.graphics.Color.WHITE),
                    ),
                )
                loaded.addSource(GeoJsonSource(TRIP))
                loaded.addLayer(
                    CircleLayer(TRIP_STOP, TRIP).withFilter(eq(get("kind"), literal("stop"))).withProperties(
                        circleRadius(12f), circleColor(FastColor.toArgb()), circleStrokeWidth(3f), circleStrokeColor(android.graphics.Color.WHITE),
                    ),
                )
                loaded.addLayer(
                    CircleLayer(TRIP_RUNOUT, TRIP).withFilter(eq(get("kind"), literal("runout"))).withProperties(
                        circleRadius(11f), circleColor(RedLine.toArgb()), circleStrokeWidth(3f), circleStrokeColor(android.graphics.Color.WHITE),
                    ),
                )
                ContextCompat.getDrawable(context, R.drawable.ic_pin)?.let { loaded.addImage(PIN, it) }
                loaded.addLayer(
                    SymbolLayer(TRIP_DEST, TRIP).withFilter(eq(get("kind"), literal("dest"))).withProperties(
                        iconImage(PIN), iconAnchor(Property.ICON_ANCHOR_BOTTOM), iconAllowOverlap(true), iconIgnorePlacement(true),
                    ),
                )
                loaded.addSource(GeoJsonSource(ME))
                loaded.addLayer(
                    CircleLayer(ME, ME).withProperties(
                        circleRadius(8f), circleColor(android.graphics.Color.WHITE),
                        circleStrokeWidth(3.5f), circleStrokeColor(Accent.toArgb()),
                    ),
                )
                style = loaded
                draw()
                pendingCamera?.invoke()
                pendingCamera = null
            }
            ready.addOnMapClickListener { latLng ->
                val point: PointF = ready.projection.toScreenLocation(latLng)
                val hit = ready.queryRenderedFeatures(point, DC, AC).firstOrNull()
                    ?: ready.queryRenderedFeatures(android.graphics.RectF(point.x - 24, point.y - 24, point.x + 24, point.y + 24), DC, AC).firstOrNull()
                val id = hit?.getStringProperty("id")
                if (id != null) onTap(id)
                id != null
            }
        }
    }

    fun showStations(list: List<Station>) {
        stations = list
        draw()
        if (!framedOnce && list.isNotEmpty()) {
            framedOnce = true
            if (position == null) frameJakarta()
        }
    }

    fun showPosition(here: Pair<Double, Double>?) {
        val first = position == null && here != null
        position = here
        draw()
        if (first) centreOn(here)
    }

    fun showSelected(s: Station?) {
        selected = s
        draw()
        if (s != null) whenReady { m ->
            val zoom = maxOf(m.cameraPosition.zoom, 13.5)
            m.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(s.lat, s.lon), zoom), 500)
        }
    }

    fun centreOn(here: Pair<Double, Double>?) {
        if (here == null) return
        whenReady { m ->
            val zoom = maxOf(m.cameraPosition.zoom, 13.0)
            m.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(here.first, here.second), zoom), 600)
        }
    }

    fun showTrip(route: List<Pair<Double, Double>>, red: List<Pair<Double, Double>>, stop: Pair<Double, Double>?, runout: Pair<Double, Double>?, dest: Pair<Double, Double>?) {
        tripRoute = route; tripRed = red; tripStop = stop; tripRunout = runout; tripDest = dest
        draw()
    }

    fun frameTrip() {
        val pts = tripRoute
        if (pts.size < 2) return
        whenReady { m ->
            val b = LatLngBounds.Builder()
            for (p in pts) b.include(LatLng(p.first, p.second))
            runCatching { m.animateCamera(CameraUpdateFactory.newLatLngBounds(b.build(), 60), 600) }
        }
    }

    fun framePoints(points: List<Pair<Double, Double>>) {
        if (points.isEmpty()) return
        if (points.size == 1) { centreOn(points[0]); return }
        whenReady { m ->
            val b = LatLngBounds.Builder()
            for (p in points) b.include(LatLng(p.first, p.second))
            runCatching { m.animateCamera(CameraUpdateFactory.newLatLngBounds(b.build(), 60), 600) }
        }
    }

    fun frameJakarta() {
        whenReady { m ->
            val bounds = LatLngBounds.Builder().include(LatLng(-6.42, 106.64)).include(LatLng(-6.08, 107.02)).build()
            m.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 40))
        }
    }

    fun destroy() {
        if (resumed) { resumed = false; view.onPause() }
        if (started) { started = false; view.onStop() }
        if (!destroyed) {
            destroyed = true
            style = null
            map = null
            view.onDestroy()
        }
    }

    private fun whenReady(action: (MapLibreMap) -> Unit) {
        val ready = map
        if (ready != null && style != null) action(ready) else pendingCamera = { map?.let(action) }
    }

    private fun draw() {
        val loaded = style ?: return
        loaded.getSourceAs<GeoJsonSource>(STATIONS)?.setGeoJson(
            FeatureCollection.fromFeatures(
                stations.map { s ->
                    Feature.fromGeometry(Point.fromLngLat(s.lon, s.lat)).apply {
                        addStringProperty("id", s.id)
                        addStringProperty("kind", if (s.fast) "dc" else "ac")
                    }
                },
            ),
        )
        val sel = selected
        loaded.getSourceAs<GeoJsonSource>(SELECTED)?.setGeoJson(
            if (sel == null) NOTHING else FeatureCollection.fromFeature(Feature.fromGeometry(Point.fromLngLat(sel.lon, sel.lat))),
        )
        loaded.getSourceAs<GeoJsonSource>(ROUTE)?.setGeoJson(line(tripRoute))
        loaded.getSourceAs<GeoJsonSource>(RED)?.setGeoJson(line(tripRed))
        loaded.getSourceAs<GeoJsonSource>(TRIP)?.setGeoJson(
            FeatureCollection.fromFeatures(
                listOfNotNull(
                    tripStop?.let { Feature.fromGeometry(Point.fromLngLat(it.second, it.first)).apply { addStringProperty("kind", "stop") } },
                    tripRunout?.let { Feature.fromGeometry(Point.fromLngLat(it.second, it.first)).apply { addStringProperty("kind", "runout") } },
                    tripDest?.let { Feature.fromGeometry(Point.fromLngLat(it.second, it.first)).apply { addStringProperty("kind", "dest") } },
                ),
            ),
        )
        val here = position
        loaded.getSourceAs<GeoJsonSource>(ME)?.setGeoJson(
            if (here == null) NOTHING else FeatureCollection.fromFeature(Feature.fromGeometry(Point.fromLngLat(here.second, here.first))),
        )
    }

    private fun line(pts: List<Pair<Double, Double>>): FeatureCollection =
        if (pts.size < 2) NOTHING
        else FeatureCollection.fromFeature(Feature.fromGeometry(LineString.fromLngLats(pts.map { Point.fromLngLat(it.second, it.first) })))

    private companion object {
        const val ROUTE = "trip-route"
        const val RED = "trip-red"
        const val TRIP = "trip-marks"
        const val TRIP_STOP = "trip-stop"
        const val TRIP_RUNOUT = "trip-runout"
        const val TRIP_DEST = "trip-dest"
        const val PIN = "trip-pin"
        const val STATIONS = "stations"
        const val AC = "stations-ac"
        const val DC = "stations-dc"
        const val SELECTED = "stations-selected"
        const val ME = "me"
        val NOTHING: FeatureCollection = FeatureCollection.fromFeatures(emptyList<Feature>())
    }
}

/** A small, non-interactive map of one charger for the detail sheet. */
@Composable
fun MiniMapView(s: Station, modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val map = androidx.compose.runtime.remember(s.id) { MiniMap(context, s) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(map) {
        lifecycle.addObserver(map.lifecycleObserver)
        onDispose {
            lifecycle.removeObserver(map.lifecycleObserver)
            map.destroy()
        }
    }
    AndroidView(factory = { map.view }, modifier = modifier)
}

class MiniMap(context: Context, private val s: Station) {
    val view: MapView
    private var started = false
    private var resumed = false
    private var destroyed = false

    val lifecycleObserver = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_START -> if (!started && !destroyed) { started = true; view.onStart() }
            Lifecycle.Event.ON_RESUME -> if (!resumed && !destroyed) { resumed = true; view.onResume() }
            Lifecycle.Event.ON_PAUSE -> if (resumed) { resumed = false; view.onPause() }
            Lifecycle.Event.ON_STOP -> if (started) { started = false; view.onStop() }
            else -> Unit
        }
    }

    init {
        MapLibre.getInstance(context)
        view = MapView(context)
        view.onCreate(null)
        // The sheet starts resumed; a view created mid-lifecycle gets no ON_START/ON_RESUME replay.
        started = true; view.onStart()
        resumed = true; view.onResume()
        view.getMapAsync { ready ->
            ready.uiSettings.apply {
                setLogoEnabled(false)
                setAttributionEnabled(false)
                setCompassEnabled(false)
                setAllGesturesEnabled(false)
            }
            ready.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(s.lat, s.lon), 15.0))
            val json = context.assets.open("map_dark.json").bufferedReader().use { it.readText() }
            ready.setStyle(Style.Builder().fromJson(json)) { loaded ->
                loaded.addSource(GeoJsonSource("mini", FeatureCollection.fromFeature(Feature.fromGeometry(Point.fromLngLat(s.lon, s.lat)))))
                loaded.addLayer(
                    CircleLayer("mini", "mini").withProperties(
                        circleRadius(9f), circleColor((if (s.fast) FastColor else AcColor).toArgb()),
                        circleStrokeWidth(3f), circleStrokeColor(android.graphics.Color.WHITE),
                    ),
                )
            }
        }
    }

    fun destroy() {
        if (resumed) { resumed = false; view.onPause() }
        if (started) { started = false; view.onStop() }
        if (!destroyed) { destroyed = true; view.onDestroy() }
    }
}
