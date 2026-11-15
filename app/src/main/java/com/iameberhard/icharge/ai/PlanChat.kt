package com.iameberhard.icharge.ai

import android.content.Context
import android.util.Log
import com.iameberhard.icharge.IChargeApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

import com.iameberhard.icharge.Station

/** One bubble. [stations] are the chargers the answer names, matched by the app so they can be opened on the map. */
data class ChatMessage(val fromUser: Boolean, val text: String, val applied: List<String> = emptyList(), val streaming: Boolean = false, val stations: List<Station> = emptyList())

/** What the chat screen shows about the model: download needed, downloading, loading, ready, or a problem. */
sealed class ModelState {
    object NoEngine : ModelState()
    object NotDownloaded : ModelState()
    data class Downloading(val have: Long, val total: Long) : ModelState()
    object Loading : ModelState()
    object Ready : ModelState()
    data class Failed(val why: String) : ModelState()
}

/**
 * One conversation with the on-device model, owned by the Application so it survives tab changes.
 * The model is loaded lazily on the first question and freed with [unload] when the screen goes.
 */
class PlanChat(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages
    private val _model = MutableStateFlow<ModelState>(if (!LlamaNative.available) ModelState.NoEngine else if (GemmaModel.installed(context)) ModelState.Ready else ModelState.NotDownloaded)
    val model: StateFlow<ModelState> = _model
    private val _generating = MutableStateFlow(false)
    val generating: StateFlow<Boolean> = _generating

    private var engine: GemmaEngine? = null
    private var downloadJob: Job? = null
    private var genJob: Job? = null
    private var sheetAtStart: String? = null

    val installed: Boolean get() = GemmaModel.installed(context)

    fun download() {
        if (downloadJob?.isActive == true) return
        downloadJob = scope.launch {
            _model.value = ModelState.Downloading(GemmaModel.partialBytes(context), GemmaModel.SIZE)
            try {
                GemmaModel.download(context) { have, total -> _model.value = ModelState.Downloading(have, total) }
                _model.value = ModelState.Ready
            } catch (e: kotlinx.coroutines.CancellationException) {
                _model.value = ModelState.NotDownloaded
            } catch (e: Exception) {
                Log.w("PlanChat", "download failed", e)
                _model.value = ModelState.Failed(e.message ?: "download failed")
            }
        }
    }

    fun cancelDownload() { downloadJob?.cancel() }

    fun deleteModel() {
        cancelDownload()
        unload()
        GemmaModel.delete(context)
        _model.value = ModelState.NotDownloaded
    }

    fun clear() {
        stop()
        _messages.value = emptyList()
        sheetAtStart = null
    }

    fun stop() {
        engine?.stop()
        genJob?.cancel()
    }

    /** Frees the ~3.6 GB the model holds. Called when the chat screen is left. */
    fun unload() {
        stop()
        val e = engine ?: return
        engine = null
        scope.launch { runCatching { e.close() } }
        if (_model.value is ModelState.Ready || _model.value is ModelState.Loading) _model.value = if (installed) ModelState.Ready else ModelState.NotDownloaded
    }

    fun ask(question: String) {
        val q = question.trim()
        if (q.isEmpty() || _generating.value) return
        _messages.value = _messages.value + ChatMessage(true, q) + ChatMessage(false, "", streaming = true)
        _generating.value = true
        genJob = scope.launch {
            try {
                val e = engine ?: run {
                    _model.value = ModelState.Loading
                    GemmaEngine.load(context).also { engine = it; _model.value = ModelState.Ready }
                }
                // The sheet is fixed for the conversation so the cached prompt prefix stays valid;
                // a fresh chat (or a new trip) picks up the current numbers.
                val sheet = sheetAtStart ?: TripSheet.build(context).also { sheetAtStart = it }
                val prompt = buildPrompt(sheet)
                val sb = StringBuilder()
                e.generate(prompt) { piece ->
                    sb.append(piece)
                    replaceLast { it.copy(text = visibleText(sb.toString())) }
                }
                val full = sb.toString()
                val applied = applyActions(full)
                val named = mentioned(visibleText(full))
                replaceLast { it.copy(text = visibleText(full).ifBlank { if (applied.isEmpty()) "(no answer)" else "" }, applied = applied, streaming = false, stations = named) }
                if (applied.isNotEmpty()) sheetAtStart = null   // the plan changed: next turn re-reads it
            } catch (e: kotlinx.coroutines.CancellationException) {
                replaceLast { it.copy(streaming = false, text = it.text.ifBlank { "(stopped)" }) }
            } catch (e: Exception) {
                Log.w("PlanChat", "ask failed", e)
                _model.value = ModelState.Failed(e.message ?: "failed")
                replaceLast { it.copy(streaming = false, text = "Could not answer: ${e.message}") }
            } finally {
                _generating.value = false
            }
        }
    }

    /** Chargers whose name appears in the answer, in the order they are mentioned. */
    private fun mentioned(text: String): List<Station> {
        if (text.isBlank()) return emptyList()
        val app = IChargeApp.of(context)
        val lower = text.lowercase()
        val onRoute = app.trip.state.value?.chargers?.map { it.station } ?: emptyList()
        val all = (onRoute + app.stations.stations.value).distinctBy { it.id }
        return all.mapNotNull { st ->
            val name = st.name.trim().lowercase()
            if (name.length < 6) return@mapNotNull null
            val at = lower.indexOf(name)
            if (at < 0) null else at to st
        }.sortedBy { it.first }.map { it.second }.distinctBy { it.name.lowercase() }.take(8)
    }

    private fun replaceLast(f: (ChatMessage) -> ChatMessage) {
        val list = _messages.value
        if (list.isEmpty()) return
        _messages.value = list.dropLast(1) + f(list.last())
    }

    /** Gemma 4 chat format. The sheet travels in the system turn; earlier turns are replayed verbatim. */
    private fun buildPrompt(sheet: String): String {
        val sb = StringBuilder()
        sb.append("<|turn>system\n").append(TripSheet.ROLE).append("\n\n").append(sheet).append("<turn|>\n")
        val history = _messages.value.dropLast(1)     // the empty assistant placeholder
        // Keep the prompt inside the context: drop the oldest turns first.
        var budget = 9000 - TripSheet.ROLE.length - sheet.length
        val kept = ArrayList<ChatMessage>()
        for (m in history.asReversed()) {
            if (budget - m.text.length < 0 && kept.isNotEmpty()) break
            kept.add(m); budget -= m.text.length
        }
        for (m in kept.asReversed()) {
            sb.append(if (m.fromUser) "<|turn>user\n" else "<|turn>model\n").append(m.text.trim()).append("<turn|>\n")
        }
        sb.append("<|turn>model\n")
        return sb.toString()
    }

    private val actionLine = Regex("""^\s*ACTION\s+(\w+)\s*(.*?)\s*$""", RegexOption.IGNORE_CASE)

    private fun visibleText(raw: String): String =
        raw.lines().filterNot { actionLine.matches(it) }.joinToString("\n").trim()

    /** Runs the ACTION lines against the trip store; returns a human line per change made. */
    private fun applyActions(raw: String): List<String> {
        val app = IChargeApp.of(context)
        val trip = app.trip
        val out = ArrayList<String>()
        for (line in raw.lines()) {
            val m = actionLine.matchEntire(line) ?: continue
            val verb = m.groupValues[1].lowercase()
            val arg = m.groupValues[2].trim().trim('"', '“', '”', '\'')
            when (verb) {
                "skip" -> {
                    val s = trip.state.value ?: continue
                    val target = s.chargers.firstOrNull { it.station.name.equals(arg, true) }
                        ?: s.chargers.firstOrNull { it.station.name.contains(arg, true) || arg.contains(it.station.name, true) }
                    if (target == null) { out.add("Could not find a charger called “$arg”"); continue }
                    trip.ignoreStop(target.station.id)
                    out.add("Skipping ${target.station.name}")
                }
                "unskip" -> { trip.clearIgnored(); trip.setIgnoreAll(false); out.add("Considering every charger again") }
                "charge_to" -> arg.toDoubleOrNull()?.takeIf { it in 50.0..100.0 }?.let { trip.updateCar(trip.car.copy(chargeToPct = it)); out.add("Charge to ${it.roundToInt()}% at stops") }
                "reserve" -> arg.toDoubleOrNull()?.takeIf { it in 5.0..30.0 }?.let { trip.updateCar(trip.car.copy(reservePct = it)); out.add("Reserve ${it.roundToInt()}%") }
                "battery" -> arg.toDoubleOrNull()?.takeIf { it in 1.0..100.0 }?.let { if (trip.state.value != null) { trip.setPct(it); out.add("Battery set to ${it.roundToInt()}%") } }
            }
        }
        return out
    }
}
