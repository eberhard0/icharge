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
    /** What the model is doing while the answer bubble is still empty ("Thinking…" when null). */
    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status

    /**
     * Compaction: the context holds about 4k tokens, so once the chat grows past it the oldest turns
     * are summarised by the model and the summary rides in the system turn. [summaryCovers] is how
     * many leading messages the summary stands for; they are no longer replayed verbatim.
     */
    private var summary: String? = null
    private var summaryCovers = 0

    private var engine: GemmaEngine? = null
    private var downloadJob: Job? = null
    private var genJob: Job? = null
    private var sheetAtStart: String? = null

    val installed: Boolean get() = GemmaModel.installed(context)

    init { runCatching { GemmaModel.removeOld(context) } }

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
        summary = null
        summaryCovers = 0
    }

    fun stop() {
        engine?.stop()
        genJob?.cancel()
    }

    /** Frees the ~5.6 GB the model holds. Called when the chat screen is left. */
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
                val (dropped, kept) = split(sheet)
                if (dropped.isNotEmpty()) {
                    _status.value = "Compacting earlier messages…"
                    try {
                        summary = summarize(e, summary, dropped)
                        summaryCovers += dropped.size
                    } catch (ce: kotlinx.coroutines.CancellationException) { throw ce }
                    catch (ex: Exception) { Log.w("PlanChat", "compaction failed", ex) }
                    _status.value = null
                }
                val prompt = buildPrompt(sheet, kept)
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
                _status.value = null
            }
        }
    }

    /**
     * Splits the replayable history into the turns that no longer fit the budget (oldest first) and
     * the ones to replay. The newest user turn is always kept; a kept run never starts with a model turn.
     */
    private fun split(sheet: String): Pair<List<ChatMessage>, List<ChatMessage>> {
        val history = _messages.value.dropLast(1).drop(summaryCovers)   // minus the empty placeholder
        var budget = 9000 - TripSheet.ROLE.length - sheet.length - (summary?.length ?: 0) - 200
        val kept = ArrayList<ChatMessage>()
        for (m in history.asReversed()) {
            if (budget - m.text.length < 0 && kept.isNotEmpty()) break
            kept.add(m); budget -= m.text.length
        }
        kept.reverse()
        while (kept.size > 1 && !kept.first().fromUser) kept.removeAt(0)
        return history.take(history.size - kept.size) to kept
    }

    /** Asks the model for a short summary of [dropped], folding in the previous [prev] summary. */
    private suspend fun summarize(e: GemmaEngine, prev: String?, dropped: List<ChatMessage>): String {
        val transcript = StringBuilder()
        for (m in dropped) {
            transcript.append(if (m.fromUser) "DRIVER: " else "ASSISTANT: ").append(m.text.trim().take(1500))
            if (m.applied.isNotEmpty()) transcript.append(" [plan changed: ").append(m.applied.joinToString("; ")).append("]")
            transcript.append('\n')
        }
        val text = transcript.toString().let { if (it.length > 6000) "…" + it.takeLast(6000) else it }
        val sb = StringBuilder()
        sb.append("<|turn>system\nYou compress conversations. Write a summary that lets a trip assistant continue the conversation without seeing the earlier messages. Keep the driver's goal, preferences and instructions, decisions made (chargers skipped, battery or charging settings), and facts and charger names mentioned. Plain text, under 120 words, no preamble, no ACTION lines.<turn|>\n")
        sb.append("<|turn>user\n")
        if (!prev.isNullOrBlank()) sb.append("Summary of the conversation before this part:\n").append(prev).append("\n\n")
        sb.append("Conversation to summarise:\n").append(text).append("\nSummary:<turn|>\n<|turn>model\n")
        val out = StringBuilder()
        e.generate(sb.toString(), maxTokens = 220, temperature = 0.3f) { out.append(it) }
        return out.toString().trim().ifBlank { prev ?: "" }
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

    /**
     * Gemma 4 chat format. The sheet and the compacted summary travel in the system turn; the [kept]
     * turns are replayed verbatim.
     */
    private fun buildPrompt(sheet: String, kept: List<ChatMessage>): String {
        val sb = StringBuilder()
        sb.append("<|turn>system\n").append(TripSheet.ROLE).append("\n\n").append(sheet)
        summary?.takeIf { it.isNotBlank() }?.let {
            sb.append("\n\nEarlier in this conversation (older messages were compacted into this summary; treat it as what was said so far):\n").append(it)
        }
        sb.append("<turn|>\n")
        for (m in kept) {
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
