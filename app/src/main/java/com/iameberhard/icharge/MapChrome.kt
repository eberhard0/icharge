package com.iameberhard.icharge

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The frosted panel look shared by the map's controls, legend and charger card. */
fun Modifier.glass(radius: Int = 14, alpha: Float = 0.94f): Modifier {
    val shape = RoundedCornerShape(radius.dp)
    return this.shadow(8.dp, shape, clip = false, ambientColor = Color.Black.copy(alpha = 0.35f), spotColor = Color.Black.copy(alpha = 0.35f))
        .clip(shape).background(CardColor.copy(alpha = alpha)).border(1.dp, Fg.copy(alpha = 0.08f), shape)
}

/**
 * Floating map controls, stacked at the right edge: zoom in, zoom out, my position, and an
 * optional framing button ("Jakarta", "Whole trip") beneath them.
 */
@Composable
fun MapControls(
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onLocate: () -> Unit,
    locateEnabled: Boolean,
    frameLabel: String? = null,
    onFrame: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier, horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.glass()) {
            ControlButton(onZoomIn) { Text("+", color = Fg, fontSize = 22.sp, fontWeight = FontWeight.Medium) }
            Box(Modifier.width(44.dp).height(1.dp).background(Fg.copy(alpha = 0.1f)))
            ControlButton(onZoomOut) { Text("−", color = Fg, fontSize = 22.sp, fontWeight = FontWeight.Medium) }
            Box(Modifier.width(44.dp).height(1.dp).background(Fg.copy(alpha = 0.1f)))
            ControlButton(onLocate) { Icon(Icons.Default.LocationOn, "My position", tint = if (locateEnabled) Accent else Muted) }
        }
        if (frameLabel != null && onFrame != null) {
            Box(Modifier.glass().clickable(onClick = onFrame).padding(horizontal = 12.dp, vertical = 9.dp)) {
                Text(frameLabel, color = Fg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun ControlButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(Modifier.size(44.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) { content() }
}

/**
 * The card that floats over the map when a pin is tapped: the pin, name, plugs, distance and
 * status, with Navigate and Details. Tapping the body opens the details too.
 */
@Composable
fun StationCard(
    s: Station,
    distance: Double,
    measuredFromPhone: Boolean,
    onDetails: () -> Unit,
    onNavigate: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalContext.current.resources.displayMetrics.density
    val pin = remember(s.id) { MarkerArt.pin(density, MarkerArt.fillFor(s.fast, s.outOfService), MarkerArt.glyphFor(s.fast), 1.2f).asImageBitmap() }
    Column(modifier.fillMaxWidth().glass(radius = 18, alpha = 0.97f).clickable(onClick = onDetails).padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(pin, null, Modifier.height(48.dp))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(s.name, color = Fg, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(s.plugLine, color = Fg2, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row {
                    Text(formatDistance(distance) + (if (measuredFromPhone) " away" else " from Monas"), color = Muted, fontSize = 12.sp)
                    if (s.outOfService) Text("  ·  ${s.status}", color = Color(0xFFFF7A7A), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    else if (s.fast) Text("  ·  DC fast", color = FastColor, fontSize = 12.sp)
                    else Text("  ·  AC", color = AcColor, fontSize = 12.sp)
                }
            }
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close", tint = Muted) }
        }
        Row(Modifier.padding(end = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onNavigate, modifier = Modifier.weight(1f)) { Text("Navigate") }
            OutlinedButton(onClick = onDetails, modifier = Modifier.weight(1f)) { Text("Details") }
        }
    }
}
