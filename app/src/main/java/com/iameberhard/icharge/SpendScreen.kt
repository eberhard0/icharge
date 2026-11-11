package com.iameberhard.icharge

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** A new entry asked for from elsewhere (a charger's sheet): operator and station pre-filled. */
data class Prefill(val kind: Kind, val operator: String, val station: String)

@Composable
fun SpendTab(store: SpendStore, prefill: Prefill?, onPrefillUsed: () -> Unit) {
    val entries by store.entries.collectAsStateWithLifecycle()
    var weekly by rememberSaveable { mutableStateOf(true) }
    var editing by remember { mutableStateOf<Entry?>(null) }
    var creating by remember { mutableStateOf<Prefill?>(null) }
    var confirmDelete by remember { mutableStateOf<Entry?>(null) }
    LaunchedEffect(prefill) { if (prefill != null) { creating = prefill; onPrefillUsed() } }

    val week = remember(entries) { thisWeek(entries) }
    val month = remember(entries) { thisMonth(entries) }
    val periods = remember(entries, weekly) { periodTotals(entries, weekly) }
    val wallets = remember(entries) { balances(entries) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { creating = Prefill(Kind.CHARGE, "", "") }, modifier = Modifier.weight(1f)) { Text("Log a charge") }
            OutlinedButton(onClick = { creating = Prefill(Kind.TOP_UP, "", "") }, modifier = Modifier.weight(1f)) { Text("Log a top-up") }
        }
        Text("Record each charging session you paid for, and each time you add money to an operator's app.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TotalCard("THIS WEEK", week, Modifier.weight(1f))
            TotalCard("THIS MONTH", month, Modifier.weight(1f))
        }
        if (wallets.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(CardColor).padding(12.dp)) {
                Text("LEFT IN EACH APP", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text("Top-ups minus charges you logged here; the app itself is the truth.", color = Muted, fontSize = 11.sp)
                for ((op, left) in wallets) {
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        Text(op, modifier = Modifier.weight(1f))
                        Text(formatRp(left), fontWeight = FontWeight.SemiBold, color = if (left < 0) FastColor else Color.White)
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("History", fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            FilterChip(weekly, { weekly = true }, { Text("Weekly") })
            Spacer(Modifier.width(6.dp))
            FilterChip(!weekly, { weekly = false }, { Text("Monthly") })
        }
        if (periods.isEmpty()) Text("Nothing logged yet. Add a charge after each session and a top-up when you fill an app's balance.", color = Muted, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
        for (p in periods) {
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(p.label, fontWeight = FontWeight.SemiBold)
                    Text(
                        listOfNotNull(
                            if (p.sessions > 0) "${p.sessions} charge" + (if (p.sessions > 1) "s" else "") else null,
                            if (p.kwh > 0) "%.1f kWh".format(p.kwh) else null,
                            p.perKwh?.let { "${formatRp(it)}/kWh" },
                            if (p.topUps > 0) "top-ups ${formatRp(p.topUps)}" else null,
                        ).joinToString(" · "),
                        color = Muted, fontSize = 12.sp,
                    )
                }
                Text(formatRp(p.spent), fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }

        Spacer(Modifier.height(16.dp))
        Text("Entries", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text("Every entry can be changed or removed.", color = Muted, fontSize = 12.sp)
        val fmt = remember { DateTimeFormatter.ofPattern("EEE d MMM") }
        for (e in entries.take(200)) {
            Column(Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(10.dp)).background(CardColor).clickable { editing = e }.padding(start = 12.dp, end = 4.dp, top = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            (if (e.kind == Kind.TOP_UP) "Top-up · " else "Charge · ") + e.operator.ifBlank { "Other" } +
                                (if (e.station.isNotBlank()) " · ${e.station}" else ""),
                            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            dateOf(e.atMs).format(fmt) + (e.kwh?.let { " · %.1f kWh".format(it) } ?: "") + (if (e.note.isNotBlank()) " · ${e.note}" else ""),
                            color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        (if (e.kind == Kind.TOP_UP) "+" else "−") + formatRp(e.amount),
                        color = if (e.kind == Kind.TOP_UP) Accent else Color.White, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { editing = e }) { Text("Edit") }
                    TextButton(onClick = { confirmDelete = e }) { Text("Delete", color = FastColor) }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    creating?.let { pre ->
        EntryDialog(
            initial = Entry(kind = pre.kind, atMs = System.currentTimeMillis(), operator = pre.operator, amount = 0, station = pre.station),
            onSave = { store.save(it); creating = null },
            onDismiss = { creating = null },
        )
    }
    editing?.let { e ->
        EntryDialog(initial = e, onSave = { store.save(it); editing = null }, onDismiss = { editing = null }, onDelete = { editing = null; confirmDelete = e })
    }
    confirmDelete?.let { e ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete this entry?") },
            text = { Text("${if (e.kind == Kind.TOP_UP) "Top-up" else "Charge"} of ${formatRp(e.amount)} at ${e.operator.ifBlank { "Other" }}.") },
            confirmButton = { TextButton(onClick = { store.delete(e.id); confirmDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Keep") } },
        )
    }
}

@Composable
private fun TotalCard(title: String, p: PeriodTotal?, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(CardColor).padding(12.dp)) {
        Text(title, color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Text(formatRp(p?.spent ?: 0), fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(
            if (p == null || p.sessions == 0) "no charges yet"
            else "${p.sessions}× · %.1f kWh".format(p.kwh) + (p.perKwh?.let { " · ${formatRp(it)}/kWh" } ?: ""),
            color = Muted, fontSize = 12.sp,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EntryDialog(initial: Entry, onSave: (Entry) -> Unit, onDismiss: () -> Unit, onDelete: (() -> Unit)? = null) {
    var kind by remember { mutableStateOf(initial.kind) }
    var operator by remember { mutableStateOf(initial.operator) }
    var amount by remember { mutableStateOf(if (initial.amount > 0) initial.amount.toString() else "") }
    var kwh by remember { mutableStateOf(initial.kwh?.let { if (it == it.toLong().toDouble()) it.toLong().toString() else it.toString() } ?: "") }
    var station by remember { mutableStateOf(initial.station) }
    var note by remember { mutableStateOf(initial.note) }
    var atMs by remember { mutableStateOf(initial.atMs) }
    var pickDate by remember { mutableStateOf(false) }
    val dateFmt = remember { DateTimeFormatter.ofPattern("EEE, d MMM yyyy") }
    val amountValue = amount.filter { it.isDigit() }.toLongOrNull() ?: 0L

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text((if (initial.id == 0L) "Log a " else "Edit ") + (if (kind == Kind.TOP_UP) "top-up" else "charge")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(kind == Kind.CHARGE, { kind = Kind.CHARGE }, { Text("Charge") })
                    FilterChip(kind == Kind.TOP_UP, { kind = Kind.TOP_UP }, { Text("Top-up") })
                }
                Text("Operator / app", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (name in OPERATOR_NAMES) FilterChip(operator == name, { operator = name }, { Text(name) })
                }
                // One tap for the usual amounts; the field stays for anything else.
                val quick = if (kind == Kind.TOP_UP) listOf(50_000L, 100_000L, 200_000L, 500_000L, 1_000_000L) else listOf(50_000L, 100_000L, 150_000L, 200_000L, 300_000L)
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (q in quick) FilterChip(amountValue == q, { amount = q.toString() }, { Text(if (q >= 1_000_000) "${q / 1_000_000}jt" else "${q / 1000}rb") })
                }
                OutlinedTextField(
                    value = amount, onValueChange = { amount = it.filter { ch -> ch.isDigit() } }, singleLine = true,
                    label = { Text("Amount (Rp)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    supportingText = { if (amountValue > 0) Text(formatRp(amountValue)) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                if (kind == Kind.CHARGE) {
                    OutlinedTextField(
                        value = kwh, onValueChange = { kwh = it.replace(',', '.').filter { ch -> ch.isDigit() || ch == '.' } }, singleLine = true,
                        label = { Text("Energy (kWh, optional)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                    OutlinedTextField(
                        value = station, onValueChange = { station = it }, singleLine = true,
                        label = { Text("Where (optional)") }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                }
                OutlinedTextField(
                    value = note, onValueChange = { note = it }, singleLine = true,
                    label = { Text("Note (optional)") }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
                TextButton(onClick = { pickDate = true }, modifier = Modifier.padding(top = 4.dp)) {
                    Text("Date: " + dateOf(atMs).format(dateFmt))
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = amountValue > 0,
                onClick = {
                    onSave(
                        initial.copy(
                            kind = kind, operator = operator, amount = amountValue, atMs = atMs,
                            kwh = if (kind == Kind.CHARGE) kwh.toDoubleOrNull()?.takeIf { it > 0 } else null,
                            station = if (kind == Kind.CHARGE) station.trim() else "", note = note.trim(),
                        ),
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (onDelete != null) TextButton(onClick = onDelete) { Text("Delete", color = FastColor) }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )

    if (pickDate) {
        val state = rememberDatePickerState(initialSelectedDateMillis = dateOf(atMs).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { utc ->
                        val day = Instant.ofEpochMilli(utc).atZone(ZoneOffset.UTC).toLocalDate()
                        val time = Instant.ofEpochMilli(atMs).atZone(ZoneId.systemDefault()).toLocalTime()
                        atMs = day.atTime(time).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    }
                    pickDate = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text("Cancel") } },
        ) { DatePicker(state = state) }
    }
}
