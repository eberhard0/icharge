package com.iameberhard.icharge.ai

import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.iameberhard.icharge.Accent
import com.iameberhard.icharge.Bg
import com.iameberhard.icharge.Card2
import com.iameberhard.icharge.CardColor
import com.iameberhard.icharge.FastColor
import com.iameberhard.icharge.Fg
import com.iameberhard.icharge.Muted

/** Full-screen chat with the on-device model. Opened from the Trip tab. */
@Composable
fun ChatScreen(chat: PlanChat, hasTrip: Boolean, onClose: () -> Unit, onOpenStation: (String) -> Unit) {
    val messages by chat.messages.collectAsStateWithLifecycle()
    val model by chat.model.collectAsStateWithLifecycle()
    val generating by chat.generating.collectAsStateWithLifecycle()
    var input by rememberSaveable { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val context = LocalContext.current

    BackHandler { onClose() }
    // Free the model's memory when the screen goes; keep the screen on while a 3 GB download runs.
    DisposableEffect(Unit) { onDispose { chat.unload() } }
    val window = (context as? android.app.Activity)?.window
    DisposableEffect(model is ModelState.Downloading) {
        if (model is ModelState.Downloading) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    LaunchedEffect(messages.size, messages.lastOrNull()?.text?.length) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    // No Scaffold here, so the content colour must be set by hand or text stays black in dark mode.
    Surface(color = Bg, contentColor = Fg, modifier = Modifier.fillMaxSize()) { Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().background(CardColor).padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Default.ArrowBack, "Back", tint = Fg) }
            Column(Modifier.weight(1f)) {
                Text("Plan with AI", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("${GemmaModel.NAME} · offline, on this phone", color = Muted, fontSize = 12.sp)
            }
            IconButton(onClick = { chat.clear() }, enabled = messages.isNotEmpty()) { Icon(Icons.Default.Refresh, "New chat", tint = if (messages.isNotEmpty()) Fg else Muted) }
            IconButton(onClick = { confirmDelete = true }, enabled = chat.installed) { Icon(Icons.Default.Delete, "Delete model", tint = if (chat.installed) Fg else Muted) }
        }

        when (val m = model) {
            is ModelState.NoEngine -> Notice("This build has no AI engine. Install the release build from GitHub.")
            is ModelState.NotDownloaded -> Column(Modifier.fillMaxWidth().background(Card2).padding(14.dp)) {
                Text("First time: download ${GemmaModel.NAME}", fontWeight = FontWeight.SemiBold)
                Text("About ${GemmaModel.SIZE / 1_000_000_000.0} GB once, over Wi-Fi ideally. It then runs offline. Needs about ${GemmaModel.RAM_MB / 1000.0} GB of free memory while answering.", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp, bottom = 10.dp))
                Button(onClick = { chat.download() }) { Text("Download ${GemmaModel.SIZE / 1_000_000_000.0} GB") }
            }
            is ModelState.Downloading -> Column(Modifier.fillMaxWidth().background(Card2).padding(14.dp)) {
                val pct = if (m.total > 0) m.have.toFloat() / m.total else 0f
                Text("Downloading ${GemmaModel.NAME} · ${(pct * 100).toInt()}% · ${m.have / 1_000_000} of ${m.total / 1_000_000} MB", fontSize = 13.sp)
                LinearProgressIndicator(progress = { pct }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), color = Accent)
                Row { OutlinedButton(onClick = { chat.cancelDownload() }) { Text("Pause") }; Spacer(Modifier.width(8.dp)); Text("Keep the app open; a paused download resumes where it stopped.", color = Muted, fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterVertically)) }
            }
            is ModelState.Loading -> Row(Modifier.fillMaxWidth().background(Card2).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.width(18.dp).height(18.dp), strokeWidth = 2.dp, color = Accent); Spacer(Modifier.width(10.dp)); Text("Loading the model…", fontSize = 13.sp)
            }
            is ModelState.Failed -> Column(Modifier.fillMaxWidth().background(Card2).padding(14.dp)) {
                Text(m.why, color = FastColor, fontSize = 13.sp)
                Row(Modifier.padding(top = 8.dp)) {
                    if (!chat.installed) Button(onClick = { chat.download() }) { Text("Download again") }
                }
            }
            is ModelState.Ready -> Unit
        }

        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (messages.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text(if (hasTrip) "Ask about this trip" else "No trip yet: ask about chargers near you, or start a trip on the Trip tab first", color = Muted, fontSize = 13.sp)
                    val examples = if (hasTrip) listOf("Is my battery enough to get there?", "Can I skip the first stop?", "Which stop is the fastest charger?", "Charge to 90% at stops", "Where should I charge if I leave with 60%?")
                    else listOf("Where is the nearest fast charger?", "How much does charging at home cost tonight?", "What is CCS2?")
                    for (ex in examples) Text(
                        ex, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp).clip(RoundedCornerShape(10.dp)).background(CardColor)
                            .clickable { if (model is ModelState.Ready && !generating) chat.ask(ex) }.padding(10.dp),
                    )
                }
            }
            items(messages) { msg -> Bubble(msg, onOpenStation) }
        }

        Row(Modifier.fillMaxWidth().background(CardColor).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f), maxLines = 4,
                placeholder = { Text(if (model is ModelState.Ready) "Ask about the trip…" else "Download the model first") },
                enabled = model is ModelState.Ready || model is ModelState.Loading,
            )
            Spacer(Modifier.width(8.dp))
            if (generating) OutlinedButton(onClick = { chat.stop() }) { Text("Stop") }
            else IconButton(onClick = { chat.ask(input); input = "" }, enabled = input.isNotBlank() && model is ModelState.Ready) { Icon(Icons.Default.Send, "Send", tint = if (input.isNotBlank() && model is ModelState.Ready) Accent else Muted) }
        }
    } }

    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete the model?") },
        text = { Text("Frees about ${GemmaModel.SIZE / 1_000_000_000.0} GB. You can download it again any time.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; chat.deleteModel() }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
    )
}

@Composable
private fun Notice(text: String) {
    Text(text, color = FastColor, fontSize = 13.sp, modifier = Modifier.fillMaxWidth().background(Card2).padding(14.dp))
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun Bubble(msg: ChatMessage, onOpenStation: (String) -> Unit) {
    Box(Modifier.fillMaxWidth(), contentAlignment = if (msg.fromUser) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(
            Modifier.widthIn(max = 560.dp).clip(RoundedCornerShape(14.dp))
                .background(if (msg.fromUser) Accent.copy(alpha = 0.22f) else CardColor).padding(12.dp),
        ) {
            if (msg.text.isNotBlank()) Text(msg.text, fontSize = 15.sp, lineHeight = 21.sp)
            if (msg.streaming && msg.text.isBlank()) Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.width(14.dp).height(14.dp), strokeWidth = 2.dp, color = Accent); Spacer(Modifier.width(8.dp)); Text("Thinking…", color = Muted, fontSize = 13.sp)
            }
            for (a in msg.applied) Text("✓ $a", color = Accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
            if (msg.stations.isNotEmpty()) {
                Text("On the map", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (st in msg.stations) Row(
                        Modifier.clip(RoundedCornerShape(16.dp)).background(Accent.copy(alpha = 0.18f)).clickable { onOpenStation(st.id) }.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.LocationOn, null, tint = Accent, modifier = Modifier.width(16.dp).height(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(st.name, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp))
                    }
                }
            }
        }
    }
}
