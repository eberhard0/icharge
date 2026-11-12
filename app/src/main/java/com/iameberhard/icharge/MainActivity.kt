package com.iameberhard.icharge

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.format.DateFormat
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import java.util.Date

val Bg = Color(0xFF0B0F14)
val CardColor = Color(0xFF161C24)
val Accent = Color(0xFF3DDC84)
val FastColor = Color(0xFFFFC43D)
val AcColor = Color(0xFF4DA3FF)
val Muted = Color(0xFF9AA5B1)

private val Scheme = darkColorScheme(
    primary = Accent, onPrimary = Color.Black,
    background = Bg, onBackground = Color.White,
    surface = CardColor, onSurface = Color.White,
    surfaceVariant = Color(0xFF1F2733), onSurfaceVariant = Muted,
    secondaryContainer = Color(0xFF1F4A38), onSecondaryContainer = Color.White,
    outline = Color(0xFF3A4654),
)

/** Jakarta (Monas): where distances are measured from until the phone has a fix. */
private val JAKARTA = -6.1754 to 106.8272

class MainActivity : ComponentActivity() {
    private lateinit var store: StationStore
    private lateinit var spend: SpendStore
    private lateinit var trip: TripStore
    private lateinit var locator: Locator

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashReport.install(this)
        MapFlavor.init(this)
        enableEdgeToEdge()
        store = StationStore(applicationContext)
        spend = SpendStore(applicationContext)
        trip = TripStore(applicationContext, store.stations)
        locator = Locator(applicationContext)
        setContent { MaterialTheme(colorScheme = Scheme) { App(store, spend, trip, locator) } }
    }

    override fun onStart() {
        super.onStart()
        locator.start()
    }

    override fun onStop() {
        locator.stop()
        super.onStop()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun App(store: StationStore, spend: SpendStore, trip: TripStore, locator: Locator) {
    val stations by store.stations.collectAsStateWithLifecycle()
    val favorites by store.favorites.collectAsStateWithLifecycle()
    val plugs by store.plugs.collectAsStateWithLifecycle()
    val fastOnly by store.fastOnly.collectAsStateWithLifecycle()
    val traffic by store.traffic.collectAsStateWithLifecycle()
    val here by locator.position.collectAsStateWithLifecycle()
    val fix by locator.fix.collectAsStateWithLifecycle()
    LaunchedEffect(fix) { fix?.let { trip.onFix(it.lat, it.lon, it.speedKmh, it.timeMs) } }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var savedOnly by rememberSaveable { mutableStateOf(false) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var prefill by remember { mutableStateOf<Prefill?>(null) }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { locator.start() }
    LaunchedEffect(Unit) {
        if (!locator.granted) permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    val origin = here ?: JAKARTA
    val filtered = remember(stations, query, plugs, fastOnly, savedOnly, favorites, origin) {
        stations
            .filter { s ->
                s.matches(query) && (!fastOnly || s.fast) &&
                    (plugs.isEmpty() || plugs.any { s.has(it) }) && (!savedOnly || s.id in favorites)
            }
            .map { it to distanceM(origin.first, origin.second, it.lat, it.lon) }
            .sortedBy { it.second }
    }
    val selected = selectedId?.let { id -> stations.firstOrNull { it.id == id } }

    val filters: @Composable () -> Unit = {
        FiltersRow(
            fastOnly = fastOnly, onFast = { store.setFastOnly(it) },
            plugs = plugs, onPlugs = { store.setPlugs(it) },
            savedOnly = savedOnly, onSaved = { savedOnly = it },
        )
    }

    // Fold 8 open (or a tablet): tabs on a side rail and the list beside the map. Closed Fold and
    // ordinary phones: bottom bar, one pane. Width is re-read on every fold/unfold.
    val widthDp = LocalConfiguration.current.screenWidthDp
    val wide = widthDp >= 600
    val twoPane = widthDp >= 840
    val tabs = listOf("Map" to Icons.Default.Place, "List" to Icons.Default.List, "Trip" to Icons.Default.Send, "Spend" to Icons.Default.ShoppingCart, "Guide" to Icons.Default.Info, "Settings" to Icons.Default.Settings)
    val content: @Composable (PaddingValues) -> Unit = { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                0 -> if (twoPane) Row(Modifier.fillMaxSize()) {
                    Box(Modifier.width(400.dp).fillMaxSize()) { ListTab(filtered, query, { query = it }, filters, onOpen = { selectedId = it }) }
                    MapTab(filtered, here, selectedId, traffic, { store.setTraffic(it) }, {}, onTap = { selectedId = it }, onLocate = { locator.start() })
                } else MapTab(filtered, here, selectedId, traffic, { store.setTraffic(it) }, filters, onTap = { selectedId = it }, onLocate = { locator.start() })
                1 -> ListTab(filtered, query, { query = it }, filters, onOpen = { selectedId = it })
                2 -> TripTab(trip, here, onOpenStation = { selectedId = it })
                3 -> Readable { SpendTab(spend, prefill, onPrefillUsed = { prefill = null }) }
                4 -> Readable { GuideTab() }
                else -> Readable { SettingsTab(store, trip) }
            }
        }
    }
    if (wide) {
        Row(Modifier.fillMaxSize().background(Bg)) {
            NavigationRail(containerColor = CardColor) {
                Spacer(Modifier.height(12.dp))
                tabs.forEachIndexed { i, (name, icon) ->
                    NavigationRailItem(tab == i, { tab = i }, { Icon(icon, null) }, label = { Text(name) })
                }
            }
            Scaffold(containerColor = Bg) { pad -> content(pad) }
        }
    } else {
        Scaffold(
            containerColor = Bg,
            bottomBar = {
                NavigationBar(containerColor = CardColor) {
                    tabs.forEachIndexed { i, (name, icon) ->
                        NavigationBarItem(tab == i, { tab = i }, { Icon(icon, null) }, label = { Text(name) })
                    }
                }
            },
        ) { pad -> content(pad) }
    }

    if (selected != null) {
        ModalBottomSheet(onDismissRequest = { selectedId = null }, containerColor = CardColor) {
            StationDetail(
                s = selected,
                distance = distanceM(origin.first, origin.second, selected.lat, selected.lon),
                measuredFromPhone = here != null,
                saved = selected.id in favorites,
                onSave = { store.toggleFavorite(selected.id) },
                onLogCharge = {
                    prefill = Prefill(Kind.CHARGE, guideFor(selected)?.name?.substringBefore(" (") ?: "Other", selected.name)
                    selectedId = null
                    tab = 3
                },
            )
        }
    }
}

/** A small map legend whose dots carry the colours used on the map. */
@Composable
fun Legend(vararg items: Pair<String, Color>, lead: String = "") {
    Text(
        buildAnnotatedString {
            if (lead.isNotBlank()) append("$lead  ")
            items.forEachIndexed { i, (label, color) ->
                withStyle(SpanStyle(color = color, fontWeight = FontWeight.Black)) { append("● ") }
                append(label)
                if (i < items.lastIndex) append("   ")
            }
        },
        color = Color.White, fontSize = 12.sp, maxLines = 2,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(CardColor.copy(alpha = 0.85f)).padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

/** Text screens read best under ~720 dp wide; on the open Fold they sit centred instead of stretching. */
@Composable
private fun Readable(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.widthIn(max = 720.dp).fillMaxSize()) { content() }
    }
}

@Composable
private fun FiltersRow(
    fastOnly: Boolean, onFast: (Boolean) -> Unit,
    plugs: Set<Plug>, onPlugs: (Set<Plug>) -> Unit,
    savedOnly: Boolean, onSaved: (Boolean) -> Unit,
) {
    val colors = FilterChipDefaults.filterChipColors(
        containerColor = CardColor.copy(alpha = 0.92f), labelColor = Color.White,
        selectedContainerColor = Accent, selectedLabelColor = Color.Black,
    )
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FilterChip(fastOnly, { onFast(!fastOnly) }, { Text("⚡ DC fast") }, colors = colors)
        for (plug in listOf(Plug.CCS2, Plug.TYPE2, Plug.GBT_DC, Plug.GBT_AC, Plug.CHADEMO)) {
            val on = plug in plugs
            FilterChip(on, { onPlugs(if (on) plugs - plug else plugs + plug) }, { Text(plug.label) }, colors = colors)
        }
        FilterChip(savedOnly, { onSaved(!savedOnly) }, { Text("♥ Saved") }, colors = colors)
    }
}

@Composable
private fun MapTab(
    stations: List<Pair<Station, Double>>,
    here: Pair<Double, Double>?,
    selectedId: String?,
    traffic: Boolean,
    onTraffic: (Boolean) -> Unit,
    filters: @Composable () -> Unit,
    onTap: (String) -> Unit,
    onLocate: () -> Unit,
) {
    val plain = remember(stations) { stations.map { it.first } }
    val map = rememberMapController()
    val scope = rememberCoroutineScope()
    var centredOnce by remember { mutableStateOf(false) }
    LaunchedEffect(here) {
        if (here != null && !centredOnce) { centredOnce = true; map.centre(here.first, here.second) }
    }
    Box(Modifier.fillMaxSize()) {
        ChargerMap(plain, here, selectedId, true, null, map, onTap, Modifier.fillMaxSize())
        Column(Modifier.align(Alignment.TopStart).fillMaxWidth()) {
            filters()
            Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Legend("DC fast" to FastColor, "AC" to AcColor, lead = "${stations.size} chargers")
            }
        }
        Row(Modifier.align(Alignment.BottomEnd).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { scope.launch { map.frameJakarta() } }) { Text("Jakarta") }
            Button(onClick = { onLocate(); here?.let { h -> scope.launch { map.centre(h.first, h.second) } } }, enabled = here != null) {
                Icon(Icons.Default.LocationOn, null)
                Spacer(Modifier.width(4.dp))
                Text(if (here == null) "No fix yet" else "Me")
            }
        }
    }
}

@Composable
private fun ListTab(
    stations: List<Pair<Station, Double>>,
    query: String, onQuery: (String) -> Unit,
    filters: @Composable () -> Unit,
    onOpen: (String) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query, onValueChange = onQuery, singleLine = true,
            placeholder = { Text("Mall, street, operator…") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        )
        filters()
        Text("${stations.size} chargers, nearest first", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(stations, key = { it.first.id }) { (s, d) -> StationRow(s, d) { onOpen(s.id) } }
        }
    }
}

@Composable
private fun StationRow(s: Station, distance: Double, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(CardColor).clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(s.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = listOf(s.operator, s.address).filter { it.isNotBlank() }.joinToString(" · ")
            if (sub.isNotBlank()) Text(sub, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(s.plugLine, color = if (s.fast) FastColor else AcColor, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(10.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(formatDistance(distance), fontWeight = FontWeight.Bold)
            Text(if (s.fast) "DC" else "AC", color = if (s.fast) FastColor else AcColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun StationDetail(s: Station, distance: Double, measuredFromPhone: Boolean, saved: Boolean, onSave: () -> Unit, onLogCharge: () -> Unit) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(s.name, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            IconButton(onClick = onSave) {
                Icon(if (saved) Icons.Default.Favorite else Icons.Default.FavoriteBorder, if (saved) "Saved" else "Save", tint = if (saved) Accent else Muted)
            }
        }
        if (s.operator.isNotBlank()) Text(s.operator, color = Accent, fontWeight = FontWeight.SemiBold)
        Text(
            formatDistance(distance) + (if (measuredFromPhone) " away" else " from Monas") + (if (s.address.isNotBlank()) " · ${s.address}" else ""),
            color = Muted, fontSize = 14.sp,
        )
        Spacer(Modifier.height(12.dp))
        // Where it is, at a glance; the phone's map apps take over from the Navigate button.
        MiniMap(s, Modifier.fillMaxWidth().height(170.dp).clip(RoundedCornerShape(12.dp)))
        Spacer(Modifier.height(10.dp))
        Button(onClick = { navigate(context, s) }, modifier = Modifier.fillMaxWidth()) { Text("Navigate") }
        Spacer(Modifier.height(14.dp))
        OperatorCard(s)
        TextButton(onClick = onLogCharge) { Text("Log a charge here") }
        Label("PLUGS")
        if (s.connectors.isEmpty()) Text("Not listed by the source.", color = Muted)
        for (c in s.connectors) {
            Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (c.dc) "DC" else "AC", color = if (c.dc) FastColor else AcColor, fontWeight = FontWeight.Bold, modifier = Modifier.width(34.dp))
                Text(
                    (if (c.count > 1) "${c.count}× " else "") + c.plug.label + (c.powerKw?.let { "  ·  ${formatKw(it)}" } ?: "") +
                        (if (c.plug == Plug.OTHER) "  (${c.raw})" else ""),
                )
            }
        }
        Fact("Cost", s.cost)
        Fact("Hours", s.hours)
        Fact("Access", s.access)
        Fact("Status", s.status + (if (s.verified.isNotBlank()) " · checked ${s.verified}" else ""))
        Fact("Notes", s.note)
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = { open(context, s.url) }) { Text("Open on ${s.source.label} (${s.source.licence})") }
        Text("Public data: it can be out of date. Live availability is only in the operators' apps (PLN Charge.IN, etc.).", color = Muted, fontSize = 12.sp)
    }
}

@Composable
private fun Label(text: String) = Text(text, color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))

@Composable
private fun Fact(label: String, value: String) {
    if (value.isBlank() || value.startsWith(" ·")) return
    Label(label.uppercase())
    Text(value, fontSize = 15.sp)
}

@Composable
private fun SettingsTab(store: StationStore, trip: TripStore) {
    val context = LocalContext.current
    val info by store.info.collectAsStateWithLifecycle()
    val busy by store.busy.collectAsStateWithLifecycle()
    val stations by store.stations.collectAsStateWithLifecycle()
    var key by rememberSaveable { mutableStateOf(if (store.builtInKey) "" else store.apiKey) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Trip planning", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        CarCard(trip)
        Spacer(Modifier.height(24.dp))
        Text("Station data", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(
            "${stations.size} chargers across Indonesia: ${info.ocm} from Open Charge Map, ${info.osm} more from OpenStreetMap." +
                (if (info.refreshedAt > 0) "\nLast refreshed ${DateFormat.getMediumDateFormat(context).format(Date(info.refreshedAt))} ${DateFormat.getTimeFormat(context).format(Date(info.refreshedAt))}." else "\nShowing the snapshot built into this version."),
            color = Muted, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp),
        )
        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(enabled = !busy, onClick = {
                scope.launch {
                    val msg = store.refresh()
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                }
            }) { Text("Refresh now") }
            if (busy) CircularProgressIndicator(Modifier.padding(start = 12.dp).width(22.dp).height(22.dp), strokeWidth = 2.dp)
        }
        if (info.message.isNotBlank()) Text(info.message, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))

        Spacer(Modifier.height(24.dp))
        Text("Open Charge Map API key", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(
            if (store.builtInKey) "A key is built into this version. Paste another one here only to override it."
            else "Refreshing from Open Charge Map needs a free key: openchargemap.org → sign in → My Profile → My Apps → register an app, then paste the key here.",
            color = Muted, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp),
        )
        OutlinedTextField(value = key, onValueChange = { key = it }, singleLine = true, placeholder = { Text("API key") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        TextButton(onClick = { store.apiKey = key; Toast.makeText(context, "Saved", Toast.LENGTH_SHORT).show() }) { Text("Save key") }

        Spacer(Modifier.height(24.dp))
        Text("About", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(
            "iCharge ${BuildConfig.VERSION_NAME} lists public EV chargers across Indonesia and plans charging stops for trips.\n\n" +
                "Data: Open Charge Map (openchargemap.org, CC BY 4.0) and OpenStreetMap contributors (ODbL). " +
                "Map tiles: OpenFreeMap, © OpenStreetMap contributors.\n\n" +
                "Map: ${MapFlavor.NAME}. Amber dots are DC fast chargers, blue dots are AC. Missing or wrong charger? Add it on openchargemap.org and refresh.",
            color = Muted, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** Always offers the chooser, so Google Maps, Waze or any other map app can be picked each time. */
fun navigate(context: Context, s: Station) {
    val uri = Uri.parse("geo:${s.lat},${s.lon}?q=${s.lat},${s.lon}(${Uri.encode(s.name)})")
    try {
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW, uri), "Navigate with"))
    } catch (_: ActivityNotFoundException) {
        open(context, "https://www.google.com/maps/dir/?api=1&destination=${s.lat},${s.lon}")
    }
}

private fun open(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "No app can open this link", Toast.LENGTH_SHORT).show()
    }
}
