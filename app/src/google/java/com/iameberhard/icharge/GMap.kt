package com.iameberhard.icharge

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMapOptions
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.CameraPositionState
import com.google.maps.android.compose.ComposeMapColorScheme
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState

object MapFlavor {
    const val TRAFFIC = true
    const val NAME = "Google Maps (live traffic)"

    /** Must run before any marker icon is created, or the SDK throws "IBitmapDescriptorFactory is not initialized". */
    fun init(context: android.content.Context) {
        runCatching { com.google.android.gms.maps.MapsInitializer.initialize(context.applicationContext, com.google.android.gms.maps.MapsInitializer.Renderer.LATEST, null) }
    }
}

private val JAKARTA_BOUNDS = LatLngBounds(LatLng(-6.42, 106.64), LatLng(-6.08, 107.02))
private fun Pair<Double, Double>.ll() = LatLng(first, second)

class GoogleMapController(val camera: CameraPositionState) : MapController {
    override suspend fun frame(points: List<Pair<Double, Double>>) {
        if (points.isEmpty()) return
        if (points.size == 1) { camera.animate(CameraUpdateFactory.newLatLngZoom(points[0].ll(), 13f)); return }
        val b = LatLngBounds.builder()
        for (p in points) b.include(p.ll())
        runCatching { camera.animate(CameraUpdateFactory.newLatLngBounds(b.build(), 120)) }
    }
    override suspend fun centre(lat: Double, lon: Double, zoom: Double) {
        camera.animate(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lon), maxOf(camera.position.zoom, zoom.toFloat())))
    }
    override suspend fun frameJakarta() { runCatching { camera.animate(CameraUpdateFactory.newLatLngBounds(JAKARTA_BOUNDS, 40)) } }
}

@Composable
fun rememberMapController(): MapController {
    val camera = rememberCameraPositionState { position = CameraPosition.fromLatLngZoom(JAKARTA_BOUNDS.center, 10f) }
    return remember { GoogleMapController(camera) }
}

/**
 * Google's map (dark, live traffic when asked) carrying the chargers as dots: amber = DC fast,
 * blue = AC; the white dot is the phone. A trip adds the route, the red "charge before here"
 * stretch, and pins for the next stop, the run-out point and the destination.
 */
@Composable
fun ChargerMap(
    stations: List<Station>,
    here: Pair<Double, Double>?,
    selectedId: String?,
    traffic: Boolean,
    trip: TripOverlay?,
    map: MapController,
    onTap: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = context.resources.displayMetrics.density
    remember { MapFlavor.init(context) }
    GoogleMap(
        modifier = modifier,
        cameraPositionState = (map as GoogleMapController).camera,
        mapColorScheme = if (Palette.dark) ComposeMapColorScheme.DARK else ComposeMapColorScheme.LIGHT,
        properties = MapProperties(isTrafficEnabled = traffic),
        uiSettings = MapUiSettings(
            zoomControlsEnabled = false, mapToolbarEnabled = false, compassEnabled = false,
            myLocationButtonEnabled = false, rotationGesturesEnabled = false, tiltGesturesEnabled = false,
        ),
    ) {
        // Icons are made here, inside the map content, once the SDK is up.
        val dc = remember { dot(FastColor, 7f * density) }
        val ac = remember { dot(AcColor, 6f * density) }
        val me = remember { dot(Color.White, 7f * density, ring = Accent) }
        val chosen = remember { dot(Color.White, 10f * density, ring = FastColor) }
        trip?.let { t ->
            if (t.route.size >= 2) Polyline(points = t.route.map { it.ll() }, color = AcColor, width = 10f, zIndex = 1f)
            if (t.red.size >= 2) Polyline(points = t.red.map { it.ll() }, color = RedLine, width = 16f, zIndex = 2f)
            t.dest?.let { Marker(state = MarkerState(it.ll()), title = "Destination", zIndex = 5f) }
            t.stop?.let { Marker(state = MarkerState(it.ll()), title = "Charge here", icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_YELLOW), zIndex = 6f) }
            t.runout?.let { Marker(state = MarkerState(it.ll()), title = "Runs out of charge here", icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED), zIndex = 6f) }
        }
        for (s in stations) key(s.id) {
            Marker(
                state = MarkerState(LatLng(s.lat, s.lon)),
                icon = if (s.id == selectedId) chosen else if (s.fast) dc else ac,
                anchor = Offset(0.5f, 0.5f),
                zIndex = if (s.id == selectedId) 4f else if (s.fast) 3f else 2.5f,
                onClick = { onTap(s.id); true },
            )
        }
        here?.let { Marker(state = MarkerState(it.ll()), icon = me, anchor = Offset(0.5f, 0.5f), zIndex = 7f) }
    }
}

/** A small static map of one charger, for the detail sheet. */
@Composable
fun MiniMap(s: Station, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val camera = rememberCameraPositionState { position = CameraPosition.fromLatLngZoom(LatLng(s.lat, s.lon), 15f) }
    remember { MapFlavor.init(context) }
    GoogleMap(
        modifier = modifier,
        cameraPositionState = camera,
        googleMapOptionsFactory = { GoogleMapOptions().liteMode(true) },
        mapColorScheme = if (Palette.dark) ComposeMapColorScheme.DARK else ComposeMapColorScheme.LIGHT,
        uiSettings = MapUiSettings(zoomControlsEnabled = false, mapToolbarEnabled = false, compassEnabled = false, myLocationButtonEnabled = false, scrollGesturesEnabled = false, zoomGesturesEnabled = false),
    ) {
        val icon = remember { dot(if (s.fast) FastColor else AcColor, 9f * context.resources.displayMetrics.density) }
        Marker(state = MarkerState(LatLng(s.lat, s.lon)), icon = icon, anchor = Offset(0.5f, 0.5f))
    }
}

/** A filled circle with a thin dark outline (or a coloured ring) as a marker icon. */
private fun dot(fill: Color, radiusPx: Float, ring: Color? = null): BitmapDescriptor {
    val r = radiusPx + 3f
    val size = (r * 2).toInt() + 2
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val cx = size / 2f
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.color = (ring ?: Color.Black).toArgb(); c.drawCircle(cx, cx, if (ring != null) r else radiusPx + 2f, paint)
    paint.color = fill.toArgb(); c.drawCircle(cx, cx, radiusPx, paint)
    return BitmapDescriptorFactory.fromBitmap(bmp)
}
