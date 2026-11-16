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
    override suspend fun zoomBy(delta: Double) { camera.animate(CameraUpdateFactory.zoomBy(delta.toFloat()), 250) }
}

@Composable
fun rememberMapController(): MapController {
    val camera = rememberCameraPositionState { position = CameraPosition.fromLatLngZoom(JAKARTA_BOUNDS.center, 10f) }
    return remember { GoogleMapController(camera) }
}

/** The pin bitmaps one map needs, made once the SDK is up (inside the map content). */
private class Pins(density: Float) {
    private fun make(fast: Boolean, out: Boolean = false, alpha: Int = 255, scale: Float = 1f, ring: Int? = null) =
        BitmapDescriptorFactory.fromBitmap(MarkerArt.pin(density, MarkerArt.fillFor(fast, out), MarkerArt.glyphFor(fast), scale, alpha, ring))
    val dc = make(true); val ac = make(false)
    val dcOut = make(true, out = true); val acOut = make(false, out = true)
    val dcSkip = make(true, alpha = MarkerArt.SKIP_ALPHA); val acSkip = make(false, alpha = MarkerArt.SKIP_ALPHA)
    val dcSel = make(true, scale = 1.3f, ring = MarkerArt.SELECT_RING); val acSel = make(false, scale = 1.3f, ring = MarkerArt.SELECT_RING)
    val stop: BitmapDescriptor = BitmapDescriptorFactory.fromBitmap(MarkerArt.pin(density, MarkerArt.fillFor(true, false), MarkerArt.Glyph.BOLT, 1.25f, ring = Accent.toArgb()))
    val runout: BitmapDescriptor = BitmapDescriptorFactory.fromBitmap(MarkerArt.pin(density, MarkerArt.RUNOUT_FILL, MarkerArt.Glyph.BANG, 1.2f))
    val dest: BitmapDescriptor = BitmapDescriptorFactory.fromBitmap(MarkerArt.pin(density, MarkerArt.DEST_FILL, MarkerArt.Glyph.DOT, 1.2f))
    val me: BitmapDescriptor = dot(Color.White, 7f * density, ring = Accent)

    fun forStation(s: Station, selected: Boolean, skipped: Boolean): BitmapDescriptor = when {
        selected -> if (s.fast) dcSel else acSel
        skipped -> if (s.fast) dcSkip else acSkip
        s.outOfService -> if (s.fast) dcOut else acOut
        else -> if (s.fast) dc else ac
    }
}

/** The pin bitmap's tip sits a little above its bottom edge (shadow padding); anchor there. */
private fun pinAnchor(density: Float, scale: Float = 1f): Offset {
    val pad = MarkerArt.tipInset(density, scale)
    val w = MarkerArt.W_DP * density * scale
    val h = 20f * (w / 14f)
    return Offset(0.5f, (pad + h) / (h + 2 * pad))
}

/**
 * Google's map (dark or light with the theme, live traffic when asked) carrying the chargers as pins:
 * amber = DC fast, blue = AC, grey = out of service; the white dot is the phone. A trip adds the
 * route, the red "charge before here" stretch, and pins for the next stop, the run-out point and the
 * destination; chargers skipped for the trip are faded.
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
    onTapEmpty: () -> Unit = {},
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
        onMapClick = { onTapEmpty() },
    ) {
        val pins = remember { Pins(density) }
        val anchor = remember { pinAnchor(density) }
        val anchorSel = remember { pinAnchor(density, 1.3f) }
        val anchorBig = remember { pinAnchor(density, 1.2f) }
        trip?.let { t ->
            if (t.route.size >= 2) Polyline(points = t.route.map { it.ll() }, color = AcColor, width = 10f, zIndex = 1f)
            if (t.red.size >= 2) Polyline(points = t.red.map { it.ll() }, color = RedLine, width = 16f, zIndex = 2f)
            t.dest?.let { Marker(state = MarkerState(it.ll()), title = "Destination", icon = pins.dest, anchor = anchorBig, zIndex = 5f) }
            t.stop?.let { Marker(state = MarkerState(it.ll()), title = "Charge here", icon = pins.stop, anchor = pinAnchor(density, 1.25f), zIndex = 6f) }
            t.runout?.let { Marker(state = MarkerState(it.ll()), title = "Runs out of charge here", icon = pins.runout, anchor = anchorBig, zIndex = 6f) }
        }
        val skipped = trip?.skipped ?: emptySet()
        for (s in stations) key(s.id) {
            val selected = s.id == selectedId
            Marker(
                state = MarkerState(LatLng(s.lat, s.lon)),
                icon = pins.forStation(s, selected, s.id in skipped),
                anchor = if (selected) anchorSel else anchor,
                zIndex = if (selected) 4f else if (s.fast) 3f else 2.5f,
                onClick = { onTap(s.id); true },
            )
        }
        here?.let { Marker(state = MarkerState(it.ll()), icon = pins.me, anchor = Offset(0.5f, 0.5f), zIndex = 7f) }
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
        val density = context.resources.displayMetrics.density
        val icon = remember { BitmapDescriptorFactory.fromBitmap(MarkerArt.pin(density, MarkerArt.fillFor(s.fast, s.outOfService), MarkerArt.glyphFor(s.fast), 1.15f)) }
        Marker(state = MarkerState(LatLng(s.lat, s.lon)), icon = icon, anchor = pinAnchor(density, 1.15f))
    }
}

/** A filled circle with a coloured ring, for the phone's position. */
private fun dot(fill: Color, radiusPx: Float, ring: Color): BitmapDescriptor {
    val r = radiusPx + 3f
    val size = (r * 2).toInt() + 2
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val cx = size / 2f
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.color = ring.toArgb(); c.drawCircle(cx, cx, r, paint)
    paint.color = fill.toArgb(); c.drawCircle(cx, cx, radiusPx, paint)
    return BitmapDescriptorFactory.fromBitmap(bmp)
}
