package com.iameberhard.icharge.ai

import android.content.Context
import com.iameberhard.icharge.IChargeApp
import com.iameberhard.icharge.Station
import com.iameberhard.icharge.TripState
import com.iameberhard.icharge.distanceM
import com.iameberhard.icharge.thisMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/**
 * Everything the model is told before the conversation: who it is, the rules for changing the
 * plan, and a compact sheet of the real trip (car, battery, route, the chargers the planner found,
 * the stops it chose). Numbers come straight from TripStore so the answers are about this trip.
 */
object TripSheet {

    const val ROLE = """You are the trip assistant inside iCharge, an app for driving an electric car in Indonesia. You help the driver plan charging stops, judge whether the battery is enough, and pick between chargers. Be brief and practical: a few short sentences or a short list, no headings. Answer in the language the driver uses (Indonesian or English). Use only the numbers in the trip sheet; never invent chargers, prices or distances. If something is not in the sheet, say so. Write charger names exactly as they appear in the sheet: the app turns every charger you name into a button that opens it on the map with directions, so you never need links or addresses. When you recommend a charger, say how far it is and what plug/power it has.

You can change the plan by ending your reply with one or more lines in exactly this form (one per line, nothing else on the line):
ACTION skip "charger name"      (do not stop at this charger; name as written in the sheet)
ACTION unskip                   (consider every charger again)
ACTION charge_to 90             (battery % to charge up to at each stop, 50-100)
ACTION reserve 15               (battery % to keep as a safety margin, 5-30)
ACTION battery 64               (the battery % the driver says the dashboard shows now)
Only add an ACTION line when the driver asks for a change or clearly agrees to one. Explain the change in a sentence before the ACTION line."""

    fun build(context: Context): String {
        val app = IChargeApp.of(context)
        val trip = app.trip
        val s = trip.state.value
        val car = trip.car
        val sb = StringBuilder()
        val now = ZonedDateTime.now(ZoneId.systemDefault())
        sb.append("TRIP SHEET (").append(now.format(DateTimeFormatter.ofPattern("EEE d MMM HH:mm"))).append(")\n")
        sb.append("Car: full range ${car.fullRangeKm.roundToInt()} km at 100%, battery ${car.batteryKwh} kWh, reserve ${car.reservePct.roundToInt()}%, charges to ${car.chargeToPct.roundToInt()}% at stops, loses ${car.idleDrainPctPerHour}%/h in slow traffic.\n")
        val kmPerPct = car.fullRangeKm / 100.0
        if (s == null) {
            sb.append("No trip is active. The driver can start one on the Trip tab by typing a destination.\n")
            val here = app.locator.position.value
            if (here != null) {
                val byDist = app.stations.stations.value
                    .map { it to distanceM(here.first, here.second, it.lat, it.lon) }
                    .sortedBy { it.second }
                val near = (byDist.take(8) + byDist.filter { it.first.fast }.take(6)).distinctBy { it.first.id }.sortedBy { it.second }
                if (near.isNotEmpty()) {
                    sb.append("Nearest chargers to the driver now (closest first):\n")
                    for ((st, m) in near) sb.append("- ").append(describe(st)).append(", ${fmtKm(m / 1000.0)} away\n")
                }
            }
        } else {
            sheetForTrip(sb, s, kmPerPct)
        }
        val month = thisMonth(app.spend.entries.value)
        if (month != null && month.spent > 0) sb.append("Charging spend this month so far: Rp ${"%,d".format(month.spent).replace(',', '.')}\n")
        sb.append("Home charging: PLN tariff about Rp 1.700/kWh for this house (23 kVA); 30% cheaper 22:00-05:00 for registered home EV charging; AC charging loses about 10%.\n")
        return sb.toString()
    }

    private fun sheetForTrip(sb: StringBuilder, s: TripState, kmPerPct: Double) {
        val plan = s.plan
        val pct = s.pctNow
        val remaining = s.remainingKm
        sb.append("Destination: ${s.dest.name}. Route ${s.route.totalKm.roundToInt()} km; driven ${s.alongKm.roundToInt()} km, ${remaining.roundToInt()} km to go.\n")
        sb.append("Battery now: ${pct.roundToInt()}% (about ${(pct * kmPerPct).roundToInt()} km of range).")
        val eta = s.eta
        if (eta != null) {
            val mins = (eta.remainingS / 60).roundToInt()
            sb.append(" Time to go: ${mins / 60} h ${mins % 60} min")
            if (eta.live && eta.delayS > 300) sb.append(" including ${(eta.delayS / 60).roundToInt()} min of traffic")
            sb.append(".")
        }
        sb.append("\n")
        if (s.offRouteKm > 2.0) sb.append("The car is ${fmtKm(s.offRouteKm)} off the route.\n")
        sb.append(
            if (plan.reachable) "Plan: ${if (plan.stops.isEmpty()) "no stop needed" else "${plan.stops.size} stop(s)"}, arriving with ${plan.arrivalPct.roundToInt()}%.\n"
            else "Plan: the destination is NOT reachable with the chargers along the route (${plan.note}).\n"
        )
        plan.runoutKm?.let { sb.append("Without charging the battery hits 0% at km ${it.roundToInt()}, ${(it - s.alongKm).roundToInt()} km ahead.\n") }
        if (plan.stops.isNotEmpty()) {
            sb.append("Planned stops:\n")
            plan.stops.forEachIndexed { i, st ->
                sb.append("${i + 1}. ${st.charger.station.name} at km ${st.charger.alongKm.roundToInt()} (${(st.charger.alongKm - s.alongKm).roundToInt()} km ahead): arrive ${st.arrivePct.roundToInt()}%, charge to ${st.leavePct.roundToInt()}%\n")
            }
        }
        val ahead = s.chargers.filter { it.alongKm >= s.alongKm - 1 }
        if (ahead.isNotEmpty()) {
            sb.append("Chargers along the route ahead (km along route, distance off route):\n")
            for (c in ahead.take(40)) {
                val skipped = c.station.id in s.ignored
                sb.append("- km ${c.alongKm.roundToInt()} (+${(c.alongKm - s.alongKm).roundToInt()} km), ${fmtKm(c.offKm)} off: ")
                    .append(describe(c.station))
                if (skipped) sb.append(" [SKIPPED by driver]")
                sb.append("\n")
            }
            if (ahead.size > 40) sb.append("(and ${ahead.size - 40} more further on)\n")
        } else sb.append("No chargers within 3 km of the rest of the route.\n")
        if (s.ignoreAll) sb.append("The driver chose to ignore all chargers (no stops planned).\n")
    }

    fun describe(st: Station): String {
        val plugs = st.connectors.groupBy { it.plug }.map { (plug, cs) ->
            val kw = cs.mapNotNull { it.powerKw }.maxOrNull()
            plug.label + (if (kw != null) " ${kw.roundToInt()} kW" else "") + if (cs.sumOf { it.count } > 1) " x${cs.sumOf { it.count }}" else ""
        }
        val b = StringBuilder(st.name)
        if (st.operator.isNotBlank()) b.append(" (").append(st.operator).append(")")
        b.append(": ").append(if (plugs.isEmpty()) "plug unknown" else plugs.joinToString(", "))
        b.append(if (st.fast) ", DC fast" else ", AC")
        if (st.cost.isNotBlank()) b.append(", cost ").append(st.cost.take(40))
        if (st.hours.isNotBlank()) b.append(", hours ").append(st.hours.take(30))
        if (st.status.isNotBlank() && !st.status.equals("Operational", true)) b.append(", status ").append(st.status)
        return b.toString()
    }

    private fun fmtKm(km: Double): String = if (km < 1.0) "${(km * 1000).roundToInt()} m" else "%.1f km".format(km)
}
