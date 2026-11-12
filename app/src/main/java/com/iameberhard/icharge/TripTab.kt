package com.iameberhard.icharge

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

val RedLine = Color(0xFFE53935)

@Composable
fun TripTab(trip: TripStore, here: Pair<Double, Double>?, onOpenStation: (String) -> Unit) {
    val state by trip.state.collectAsStateWithLifecycle()
    val busy by trip.busy.collectAsStateWithLifecycle()
    val s = state
    if (s == null) TripSetup(trip, here, busy) else TripLive(trip, s, here, onOpenStation)
}

@Composable
private fun TripSetup(trip: TripStore, here: Pair<Double, Double>?, busy: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Place>>(emptyList()) }
    var dest by remember { mutableStateOf<Place?>(null) }
    var pct by rememberSaveable { mutableStateOf("80") }
    var searching by remember { mutableStateOf(false) }
    var planning by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Plan a trip", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("Where to, and how full the battery is now. The route comes from OpenStreetMap; chargers within 3 km of it count as stops.", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp, bottom = 10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true, label = { Text("Destination (city, hotel, address…)") }, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Button(enabled = query.length >= 3 && !searching, onClick = {
                searching = true; error = ""
                scope.launch {
                    results = withContext(Dispatchers.IO) { runCatching { Routing.search(query) }.getOrElse { error = "Search failed: ${it.message}"; emptyList() } }
                    if (results.isEmpty() && error.isBlank()) error = "Nothing found in Indonesia for “$query”"
                    searching = false
                }
            }) { Text(if (searching) "…" else "Search") }
        }
        for (p in results) {
            Text(
                p.name, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(8.dp))
                    .background(if (dest == p) Accent.copy(alpha = 0.25f) else CardColor).clickable { dest = p; results = listOf(p) }.padding(10.dp),
            )
        }
        OutlinedTextField(
            value = pct, onValueChange = { pct = it.filter { c -> c.isDigit() }.take(3) }, singleLine = true,
            label = { Text("Battery now (%)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        )
        Spacer(Modifier.height(10.dp))
        CarCard(trip)
        if (error.isNotBlank()) Text(error, color = FastColor, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(12.dp))
        Button(
            enabled = dest != null && (pct.toDoubleOrNull() ?: 0.0) in 1.0..100.0 && !planning && !busy,
            onClick = {
                val d = dest ?: return@Button
                val from = here
                if (from == null) { Toast.makeText(context, "Waiting for a GPS fix: the trip starts from where you are", Toast.LENGTH_LONG).show(); return@Button }
                planning = true; error = ""
                scope.launch {
                    val route = withContext(Dispatchers.IO) { runCatching { Routing.route(context, from.first, from.second, d.lat, d.lon) }.getOrElse { error = "Routing failed: ${it.message}"; null } }
                    if (route != null) trip.start(d, route, pct.toDouble(), from)
                    planning = false
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (planning || busy) "Planning…" else "Plan trip from here") }
        if (here == null) Text("No GPS fix yet; the trip starts from your current position.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
    }
}

/** The car's numbers at a glance, with an Edit button; shown on the trip screens and in Settings. */
@Composable
fun CarCard(trip: TripStore) {
    var editing by remember { mutableStateOf(false) }
    var version by remember { mutableStateOf(0) }
    val car = remember(version) { trip.car }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(CardColor).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("YOUR CAR", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text("Range ${car.fullRangeKm.toInt()} km at 100% · reserve ${car.reservePct.toInt()}% · charge to ${car.chargeToPct.toInt()}%", fontSize = 14.sp)
                Text("Traffic drain ${car.idleDrainPctPerHour} %/h while crawling", color = Muted, fontSize = 12.sp)
            }
            Button(onClick = { editing = true }) { Text("Edit") }
        }
    }
    if (editing) EditCarDialog(car, onSave = { trip.updateCar(it); version++; editing = false }, onDismiss = { editing = false })
}

@Composable
private fun EditCarDialog(car: Car, onSave: (Car) -> Unit, onDismiss: () -> Unit) {
    var range by remember { mutableStateOf(car.fullRangeKm.toInt().toString()) }
    var reserve by remember { mutableStateOf(car.reservePct.toInt().toString()) }
    var chargeTo by remember { mutableStateOf(car.chargeToPct.toInt().toString()) }
    var idle by remember { mutableStateOf(car.idleDrainPctPerHour.toString()) }
    val num = KeyboardOptions(keyboardType = KeyboardType.Decimal)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Car settings") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Use the real-world range you get on a full battery: highway speeds and air-con eat into the brochure figure.", color = Muted, fontSize = 12.sp)
                OutlinedTextField(range, { range = it.filter { c -> c.isDigit() } }, label = { Text("Range at 100% (km)") }, singleLine = true, keyboardOptions = num, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                OutlinedTextField(reserve, { reserve = it.filter { c -> c.isDigit() } }, label = { Text("Reserve: never plan below (%)") }, singleLine = true, keyboardOptions = num, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                OutlinedTextField(chargeTo, { chargeTo = it.filter { c -> c.isDigit() } }, label = { Text("Charge to at each stop (%)") }, singleLine = true, keyboardOptions = num, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                OutlinedTextField(idle, { idle = it.replace(',', '.') }, label = { Text("Drain in slow traffic (% per hour)") }, singleLine = true, keyboardOptions = num, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(Car(range.toDoubleOrNull()?.coerceIn(50.0, 2000.0) ?: 400.0, reserve.toDoubleOrNull()?.coerceIn(0.0, 50.0) ?: 10.0, chargeTo.toDoubleOrNull()?.coerceIn(20.0, 100.0) ?: 80.0, idle.toDoubleOrNull()?.coerceIn(0.0, 30.0) ?: 2.0))
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun TripLive(trip: TripStore, s: TripState, here: Pair<Double, Double>?, onOpenStation: (String) -> Unit) {
    val context = LocalContext.current
    val map = rememberMapController()
    val scope = rememberCoroutineScope()
    var askPct by remember { mutableStateOf(false) }
    var fullMap by rememberSaveable { mutableStateOf(false) }
    var confirmEnd by remember { mutableStateOf(false) }
    val plan = s.plan
    val nearStations = remember(s.chargers) { s.chargers.map { it.station } }
    val routePts = remember(s.route) { s.route.lat.indices.map { s.route.lat[it] to s.route.lon[it] } }
    val redPts = remember(plan.redFromKm, plan.redToKm) { if (plan.redFromKm != null && plan.redToKm != null) s.route.slice(plan.redFromKm, plan.redToKm) else emptyList() }
    val stopPt = plan.nextStop?.charger?.station?.let { it.lat to it.lon }
    val runoutPt = plan.runoutKm?.let { s.route.pointAt(it) }
    val overlay = remember(routePts, redPts, stopPt, runoutPt, s.dest) { TripOverlay(routePts, redPts, stopPt, runoutPt, s.dest.lat to s.dest.lon) }
    LaunchedEffect(s.dest) { map.frame(routePts) }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().weight(if (fullMap) 4f else 1f)) {
            ChargerMap(nearStations, here, null, true, overlay, map, onOpenStation, Modifier.fillMaxSize())
            Row(Modifier.align(Alignment.TopStart).padding(8.dp)) {
                Legend("route" to AcColor, "charge before here" to RedLine, "next stop" to FastColor, "runs out" to RedLine)
            }
            Row(Modifier.align(Alignment.BottomEnd).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { fullMap = !fullMap }) { Text(if (fullMap) "Panel" else "Expand") }
                OutlinedButton(onClick = { scope.launch { map.frame(routePts) } }) { Text("Whole trip") }
                Button(onClick = { here?.let { h -> scope.launch { map.centre(h.first, h.second) } } }, enabled = here != null) { Text("Me") }
            }
        }
        if (fullMap) {
            // Full-screen map: one line with what matters while driving.
            val next = plan.nextStop
            Row(Modifier.fillMaxWidth().background(CardColor).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                val pct = s.pctNow
                Text("${pct.toInt()}%", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = when { pct <= s.car.reservePct -> RedLine; pct < 30 -> FastColor; else -> Accent })
                Spacer(Modifier.width(12.dp))
                Text(
                    if (next != null && !s.ignoreAll) "Charge at ${next.charger.station.name} in ${(next.charger.alongKm - s.alongKm).toInt()} km" else "${s.remainingKm.toInt()} km to go · arrive ≈ ${plan.arrivalPct.toInt()}%",
                    fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
            }
        } else Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 8.dp)) {
            val pct = s.pctNow
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${pct.toInt()}%", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = when { pct <= s.car.reservePct -> RedLine; pct < 30 -> FastColor; else -> Accent })
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("estimated battery now", color = Muted, fontSize = 12.sp)
                    Text("${s.alongKm.toInt()} of ${s.route.totalKm.toInt()} km · ${s.remainingKm.toInt()} km to go", fontSize = 13.sp)
                }
            }
            Text("To ${s.dest.name}", color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            s.eta?.let { eta ->
                Text(
                    "ETA ${java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(System.currentTimeMillis() + (eta.remainingS * 1000).toLong()))}" +
                        " · ${(eta.remainingS / 60).toInt()} min" + (if (eta.delayS >= 120) ", ${(eta.delayS / 60).toInt()} min of it traffic" else ", traffic light") +
                        (if (!eta.live) " (free-flow estimate, no live traffic)" else ""),
                    color = if (eta.delayS >= 900) FastColor else Muted, fontSize = 12.sp,
                )
                if (!eta.live && Routing.hasGoogle()) Routing.lastGoogleError?.let { Text("Google routing: " + it.substringBefore("\n").take(120), color = FastColor, fontSize = 11.sp) }
            }
            if (s.offRouteKm > 2.0 && s.lastFixMs > s.anchorTimeMs) Text("Off the planned route by ${s.offRouteKm.toInt()} km; the estimate holds until you're back on it.", color = FastColor, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))

            val next = plan.nextStop
            if (next != null && !s.ignoreAll) {
                val st = next.charger.station
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(CardColor).padding(12.dp)) {
                    Text("NEXT CHARGE", color = FastColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text(st.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "in ${(next.charger.alongKm - s.alongKm).toInt()} km · arrive ≈ ${next.arrivePct.toInt()}% · ${st.plugLine}" +
                            (if (next.charger.offKm > 0.3) " · ${"%.1f".format(next.charger.offKm)} km off the road" else ""),
                        color = Muted, fontSize = 12.sp,
                    )
                    plan.redToKm?.let { Text("Red line: charge before km ${it.toInt()} (${(it - s.alongKm).toInt()} km ahead) or the battery drops below ${s.car.reservePct.toInt()}%.", fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)) }
                    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(onClick = { navigate(context, st) }) { Text("Navigate") }
                        OutlinedButton(onClick = { onOpenStation(st.id) }) { Text("Details") }
                        TextButton(onClick = { trip.ignoreStop(st.id) }) { Text("Ignore") }
                    }
                }
            } else {
                Text(plan.note, fontWeight = FontWeight.SemiBold, color = if (plan.reachable) Accent else RedLine)
            }
            plan.runoutKm?.let {
                Text("Runs out of charge at km ${it.toInt()}, ${(s.route.totalKm - it).toInt()} km before the destination.", color = RedLine, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
            }
            Text("Arrive with ≈ ${plan.arrivalPct.toInt()}%" + (if (plan.stops.isNotEmpty() && !s.ignoreAll) " after ${plan.stops.size} stop" + (if (plan.stops.size > 1) "s" else "") else ""), fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))

            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { askPct = true }) { Text("I charged / set %") }
                Spacer(Modifier.weight(1f))
                Text("No charging", fontSize = 12.sp)
                Switch(checked = s.ignoreAll, onCheckedChange = { trip.setIgnoreAll(it) })
            }
            if (plan.stops.size > 1 && !s.ignoreAll) {
                Text("ALL STOPS", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
                for ((i, st) in plan.stops.withIndex()) {
                    Text("${i + 1}. ${st.charger.station.name} · km ${st.charger.alongKm.toInt()} · arrive ≈ ${st.arrivePct.toInt()}%", fontSize = 13.sp, modifier = Modifier.padding(top = 3.dp).clickable { onOpenStation(st.charger.station.id) })
                }
            }
            if (s.ignored.isNotEmpty()) TextButton(onClick = { trip.clearIgnored() }) { Text("Consider the ${s.ignored.size} ignored charger" + (if (s.ignored.size > 1) "s" else "") + " again") }
            Spacer(Modifier.height(10.dp))
            CarCard(trip)
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = { confirmEnd = true }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("End trip", color = RedLine, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
            Spacer(Modifier.height(16.dp))
        }
    }

    if (askPct) {
        var v by remember { mutableStateOf(s.pctNow.toInt().coerceIn(0, 100).toString()) }
        AlertDialog(
            onDismissRequest = { askPct = false },
            title = { Text("Battery on the dashboard") },
            text = {
                Column {
                    Text("Type what the car shows now. Use it after charging, or whenever the estimate drifts.", color = Muted, fontSize = 13.sp)
                    OutlinedTextField(v, { v = it.filter { c -> c.isDigit() }.take(3) }, singleLine = true, label = { Text("%") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = { TextButton(enabled = (v.toDoubleOrNull() ?: -1.0) in 0.0..100.0, onClick = { trip.setPct(v.toDouble()); askPct = false }) { Text("Set") } },
            dismissButton = { TextButton(onClick = { askPct = false }) { Text("Cancel") } },
        )
    }
    if (confirmEnd) {
        AlertDialog(
            onDismissRequest = { confirmEnd = false },
            title = { Text("End this trip?") },
            text = { Text("The route and plan are cleared. Chargers and your spending log stay.") },
            confirmButton = { TextButton(onClick = { trip.end(); confirmEnd = false }) { Text("End trip") } },
            dismissButton = { TextButton(onClick = { confirmEnd = false }) { Text("Keep") } },
        )
    }
}
