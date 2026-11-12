package com.iameberhard.icharge

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The station list: the raw Open Charge Map and OpenStreetMap files shipped in the APK, replaced by
 * fresher downloads kept in the app's files directory. Both are parsed on the phone, so the bundled
 * snapshot and a refresh go through the same code.
 */
class StationStore(private val context: Context) {

    data class Info(val ocm: Int = 0, val osm: Int = 0, val refreshedAt: Long = 0L, val message: String = "")

    private val prefs = context.getSharedPreferences("icharge", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _stations = MutableStateFlow<List<Station>>(emptyList())
    val stations: StateFlow<List<Station>> = _stations
    private val _info = MutableStateFlow(Info(refreshedAt = prefs.getLong("refreshed", 0L)))
    val info: StateFlow<Info> = _info
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    private val _favorites = MutableStateFlow(prefs.getStringSet("favorites", emptySet())!!.toSet())
    val favorites: StateFlow<Set<String>> = _favorites
    private val _plugs = MutableStateFlow(
        prefs.getStringSet("plugs", emptySet())!!.mapNotNull { n -> Plug.entries.firstOrNull { it.name == n } }.toSet(),
    )
    val plugs: StateFlow<Set<Plug>> = _plugs
    private val _fastOnly = MutableStateFlow(prefs.getBoolean("fastOnly", false))
    val fastOnly: StateFlow<Boolean> = _fastOnly
    private val _traffic = MutableStateFlow(prefs.getBoolean("traffic", true))
    val traffic: StateFlow<Boolean> = _traffic
    private val _theme = MutableStateFlow(prefs.getString("theme", "system") ?: "system")
    /** "system" | "light" | "dark" */
    val theme: StateFlow<String> = _theme
    fun setTheme(v: String) { _theme.value = v; prefs.edit().putString("theme", v).apply() }

    /** The key typed into Settings wins over the one built in. */
    var apiKey: String
        get() = prefs.getString("ocmKey", null)?.trim()?.takeIf { it.isNotEmpty() } ?: BuildConfig.OCM_API_KEY
        set(value) { prefs.edit().putString("ocmKey", value.trim()).apply() }
    val builtInKey: Boolean get() = BuildConfig.OCM_API_KEY.isNotEmpty()

    init {
        scope.launch { load() }
    }

    private fun file(name: String) = File(context.filesDir, name)

    private fun raw(name: String): String =
        file(name).takeIf { it.exists() && it.length() > 2 }?.readText()
            ?: context.assets.open(name).bufferedReader().use { it.readText() }

    private fun load() {
        val ocm = runCatching { Ocm.parse(raw("ocm.json")) }.getOrDefault(emptyList())
        val osm = runCatching { Osm.parse(raw("osm.json")) }.getOrDefault(emptyList())
        // The same charger mapped in both: keep the Open Charge Map entry (it lists the plugs).
        val extra = osm.filter { o -> ocm.none { c -> distanceM(c.lat, c.lon, o.lat, o.lon) < 80.0 } }
        _stations.value = ocm + extra
        _info.value = _info.value.copy(ocm = ocm.size, osm = extra.size)
    }

    /** Downloads both sources; each one that fails leaves the previous file in place. */
    suspend fun refresh(): String = withContext(Dispatchers.IO) {
        if (_busy.value) return@withContext "Already refreshing"
        _busy.value = true
        val notes = ArrayList<String>()
        try {
            val key = apiKey
            if (key.isEmpty()) {
                notes += "Open Charge Map skipped: no API key (Settings)"
            } else {
                runCatching { Ocm.fetch(key) }
                    .onSuccess { text ->
                        val n = runCatching { Ocm.parse(text).size }.getOrNull()
                        if (n == null) notes += "Open Charge Map: unreadable reply" else {
                            file("ocm.json").writeText(text)
                            notes += "Open Charge Map: $n locations"
                        }
                    }
                    .onFailure { notes += "Open Charge Map failed: ${it.message?.take(120)}" }
            }
            runCatching { Osm.fetch() }
                .onSuccess { text ->
                    val n = runCatching { Osm.parse(text).size }.getOrNull()
                    if (n == null) notes += "OpenStreetMap: unreadable reply" else {
                        file("osm.json").writeText(text)
                        notes += "OpenStreetMap: $n stations"
                    }
                }
                .onFailure { notes += "OpenStreetMap failed: ${it.message?.take(120)}" }
            load()
            val now = System.currentTimeMillis()
            prefs.edit().putLong("refreshed", now).apply()
            val message = notes.joinToString("\n")
            _info.value = _info.value.copy(refreshedAt = now, message = message)
            message
        } finally {
            _busy.value = false
        }
    }

    fun toggleFavorite(id: String) {
        val next = _favorites.value.toMutableSet().apply { if (!add(id)) remove(id) }
        _favorites.value = next
        prefs.edit().putStringSet("favorites", next).apply()
    }

    fun setPlugs(plugs: Set<Plug>) {
        _plugs.value = plugs
        prefs.edit().putStringSet("plugs", plugs.map { it.name }.toSet()).apply()
    }

    fun setTraffic(on: Boolean) {
        _traffic.value = on
        prefs.edit().putBoolean("traffic", on).apply()
    }

    fun setFastOnly(on: Boolean) {
        _fastOnly.value = on
        prefs.edit().putBoolean("fastOnly", on).apply()
    }
}
