package com.iameberhard.icharge

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.NumberFormat
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class Kind { TOP_UP, CHARGE }

/** One line in the log: money put into an operator's app, or one charging session. */
data class Entry(
    val id: Long = 0,
    val kind: Kind,
    val atMs: Long,
    val operator: String,
    /** Rupiah. */
    val amount: Long,
    val kwh: Double? = null,
    val station: String = "",
    val note: String = "",
)

/** Short operator names for the chips, from the guide plus "Other". */
val OPERATOR_NAMES: List<String> = OPERATORS.map { it.name.substringBefore(" (") } + "Other"

private val rupiah: NumberFormat = NumberFormat.getIntegerInstance(Locale("in", "ID"))
fun formatRp(n: Long): String = "Rp " + rupiah.format(n)

fun dateOf(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()

/** Totals for one calendar week (Monday start) or month. */
data class PeriodTotal(
    val label: String,
    val start: LocalDate,
    val spent: Long,
    val kwh: Double,
    val sessions: Int,
    val topUps: Long,
) {
    val perKwh: Long? get() = if (kwh > 0) (spent / kwh).toLong() else null
}

fun periodTotals(entries: List<Entry>, weekly: Boolean): List<PeriodTotal> {
    val groups = entries.groupBy { e ->
        val d = dateOf(e.atMs)
        if (weekly) d.with(DayOfWeek.MONDAY) else d.withDayOfMonth(1)
    }
    val day = DateTimeFormatter.ofPattern("d MMM")
    val month = DateTimeFormatter.ofPattern("MMMM yyyy")
    return groups.entries.sortedByDescending { it.key }.map { (start, es) ->
        val charges = es.filter { it.kind == Kind.CHARGE }
        PeriodTotal(
            label = if (weekly) "${start.format(day)} – ${start.plusDays(6).format(day)}" else start.format(month),
            start = start,
            spent = charges.sumOf { it.amount },
            kwh = charges.sumOf { it.kwh ?: 0.0 },
            sessions = charges.size,
            topUps = es.filter { it.kind == Kind.TOP_UP }.sumOf { it.amount },
        )
    }
}

/** What is presumably left in each operator's app: top-ups minus charges, for operators ever topped up. */
fun balances(entries: List<Entry>): List<Pair<String, Long>> =
    entries.filter { it.kind == Kind.TOP_UP }.map { it.operator }.distinct().sorted().map { op ->
        op to entries.filter { it.operator == op }.sumOf { if (it.kind == Kind.TOP_UP) it.amount else -it.amount }
    }

fun thisWeek(entries: List<Entry>): PeriodTotal? {
    val monday = LocalDate.now().with(DayOfWeek.MONDAY)
    return periodTotals(entries, weekly = true).firstOrNull { it.start == monday }
}

fun thisMonth(entries: List<Entry>): PeriodTotal? {
    val first = YearMonth.now().atDay(1)
    return periodTotals(entries, weekly = false).firstOrNull { it.start == first }
}

class SpendStore(context: Context) {
    private val helper = object : SQLiteOpenHelper(context, "spend.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE entries(id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT NOT NULL, at INTEGER NOT NULL, " +
                    "operator TEXT NOT NULL, amount INTEGER NOT NULL, kwh REAL, station TEXT NOT NULL DEFAULT '', note TEXT NOT NULL DEFAULT '')",
            )
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    private val _entries = MutableStateFlow(load())
    val entries: StateFlow<List<Entry>> = _entries

    private fun load(): List<Entry> {
        val out = ArrayList<Entry>()
        helper.readableDatabase.query("entries", null, null, null, null, null, "at DESC, id DESC").use { c ->
            while (c.moveToNext()) {
                out += Entry(
                    id = c.getLong(0),
                    kind = runCatching { Kind.valueOf(c.getString(1)) }.getOrDefault(Kind.CHARGE),
                    atMs = c.getLong(2),
                    operator = c.getString(3),
                    amount = c.getLong(4),
                    kwh = if (c.isNull(5)) null else c.getDouble(5),
                    station = c.getString(6),
                    note = c.getString(7),
                )
            }
        }
        return out
    }

    private fun values(e: Entry) = ContentValues().apply {
        put("kind", e.kind.name)
        put("at", e.atMs)
        put("operator", e.operator)
        put("amount", e.amount)
        if (e.kwh == null) putNull("kwh") else put("kwh", e.kwh)
        put("station", e.station)
        put("note", e.note)
    }

    fun save(e: Entry) {
        val db = helper.writableDatabase
        if (e.id == 0L) db.insert("entries", null, values(e))
        else db.update("entries", values(e), "id=?", arrayOf(e.id.toString()))
        _entries.value = load()
    }

    fun delete(id: Long) {
        helper.writableDatabase.delete("entries", "id=?", arrayOf(id.toString()))
        _entries.value = load()
    }
}
