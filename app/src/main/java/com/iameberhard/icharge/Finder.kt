package com.iameberhard.icharge

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.CancellationSignal
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Phone finder: every 15 minutes (and on demand) the phone posts where it is to the owner's Mac,
 * through the same bridge the iMessage / iPhotos apps use (connection borrowed from the iMessage app,
 * same signing key). The Mac keeps the trail and shows it at <bridge>/v1/phone/page.
 */
object Finder {
    private const val WORK = "phone-finder"

    private fun prefs(c: Context) = c.getSharedPreferences("finder", Context.MODE_PRIVATE)
    private fun secure(c: Context) = EncryptedSharedPreferences.create(
        "finder-bridge", MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC), c,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV, EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun enabled(c: Context) = prefs(c).getBoolean("enabled", false)
    fun lastNote(c: Context) = prefs(c).getString("lastNote", "") ?: ""
    fun baseUrl(c: Context): String = secure(c).getString("baseUrl", "") ?: ""
    private fun token(c: Context): String = secure(c).getString("token", "") ?: ""
    fun configured(c: Context) = baseUrl(c).isNotEmpty() && token(c).isNotEmpty()

    /** Takes the bridge address and token from the iMessage app. */
    fun borrow(c: Context): Boolean {
        val b = runCatching { c.contentResolver.call(Uri.parse("content://com.eberhard.relay.connection"), "connection", null, null) }.getOrNull() ?: return false
        val url = b.getString("base_url").orEmpty(); val tok = b.getString("token").orEmpty()
        if (url.isEmpty() || tok.isEmpty()) return false
        secure(c).edit().putString("baseUrl", url).putString("token", tok).apply()
        return true
    }

    fun backgroundAllowed(c: Context): Boolean =
        Build.VERSION.SDK_INT < 29 || ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun setEnabled(c: Context, on: Boolean) {
        prefs(c).edit().putBoolean("enabled", on).apply()
        val wm = WorkManager.getInstance(c)
        if (on) {
            wm.enqueueUniquePeriodicWork(
                WORK, ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<FinderWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build(),
            )
            reportNow(c)
        } else wm.cancelUniqueWork(WORK)
    }

    fun reportNow(c: Context) {
        WorkManager.getInstance(c).enqueueUniqueWork("$WORK-now", ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<FinderWorker>().build())
    }

    fun note(c: Context, text: String) = prefs(c).edit().putString("lastNote", text).putLong("lastAt", System.currentTimeMillis()).apply()

    /** One report: a fresh fix (up to 25 s), else the last known one. */
    suspend fun report(c: Context): String = withContext(Dispatchers.IO) {
        if (!configured(c) && !borrow(c)) return@withContext "No bridge connection: open the iMessage app once"
        val loc = currentLocation(c) ?: return@withContext "No location fix"
        val bm = c.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val cm = c.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val net = when {
            caps == null -> "offline"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
            else -> "other"
        }
        val body = JSONObject().put("device", Build.MODEL).put("lat", loc.latitude).put("lon", loc.longitude)
            .put("acc", loc.accuracy.toDouble()).put("battery", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)).put("net", net).put("at", loc.time)
        Net.postJson(baseUrl(c) + "/v1/phone/location", body.toString(), mapOf("Authorization" to "Bearer " + token(c)))
        "Reported ${SimpleDateFormat("d MMM HH:mm", Locale.US).format(Date())} · ±${loc.accuracy.toInt()} m · $net"
    }

    private suspend fun currentLocation(c: Context): Location? {
        if (ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
        val lm = c.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = buildList { if (Build.VERSION.SDK_INT >= 31) add(LocationManager.FUSED_PROVIDER); add(LocationManager.GPS_PROVIDER); add(LocationManager.NETWORK_PROVIDER) }
            .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        if (Build.VERSION.SDK_INT >= 30) for (p in providers) {
            val fresh = withTimeoutOrNull(25_000) {
                suspendCancellableCoroutine<Location?> { cont ->
                    val signal = CancellationSignal()
                    cont.invokeOnCancellation { signal.cancel() }
                    try {
                        lm.getCurrentLocation(p, signal, c.mainExecutor) { if (cont.isActive) cont.resume(it) }
                    } catch (e: SecurityException) { if (cont.isActive) cont.resume(null) }
                }
            }
            if (fresh != null) return fresh
        }
        return providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
    }
}

class FinderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext
        return try {
            Finder.note(c, Finder.report(c))
            Result.success()
        } catch (e: IOException) {
            Finder.note(c, "Mac not reachable (${e.message?.take(60)}); will retry")
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        } catch (e: Exception) {
            Finder.note(c, "Failed: ${e.message?.take(80)}")
            Result.failure()
        }
    }
}

/** The Settings section. */
@Composable
fun FinderSection() {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(Finder.enabled(context)) }
    var background by remember { mutableStateOf(Finder.backgroundAllowed(context)) }
    var tick by remember { mutableStateOf(0) }
    val note = remember(tick, enabled) { Finder.lastNote(context) }
    val askBackground = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { background = Finder.backgroundAllowed(context); tick++ }
    Text("Phone finder", fontSize = 18.sp, fontWeight = FontWeight.Bold)
    Text(
        "Posts this phone's position to your Mac every 15 minutes (through the iMessage bridge, over Tailscale). " +
            "If the phone goes missing, open the trail page on the Mac or iPhone: " + (Finder.baseUrl(context).ifBlank { "http://<mac>:8765" }) + "/v1/phone/page",
        color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp),
    )
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Report my location to the Mac", modifier = Modifier.weight(1f))
        Switch(checked = enabled, onCheckedChange = { on ->
            enabled = on
            Finder.setEnabled(context, on)
            if (on && !background && Build.VERSION.SDK_INT >= 29) askBackground.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            tick++
        })
    }
    if (enabled && !background) Text("Android needs location set to “Allow all the time” for reports while the app is closed: Settings → Apps → iCharge → Permissions → Location.", color = FastColor, fontSize = 12.sp)
    if (enabled && !Finder.configured(context)) Text("No bridge connection yet: open the iMessage app once, then toggle this again.", color = FastColor, fontSize = 12.sp)
    if (note.isNotBlank()) Text(note, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
    Row(Modifier.padding(top = 6.dp)) {
        OutlinedButton(enabled = enabled, onClick = { Finder.reportNow(context); tick++ }) { Text("Report now") }
    }
    Spacer(Modifier.height(24.dp))
}
