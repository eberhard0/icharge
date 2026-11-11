package com.iameberhard.icharge

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * What a driver needs to know per operator: the app, how to sign up and pay, the published price.
 * Prices are from the operators' pages and Indonesian motoring press as of November 2026; every
 * operator app shows the live price before you start, so the card says "check in the app".
 */
data class OperatorGuide(
    val name: String,
    /** Lower-case fragments matched against the operator and station names. */
    val keywords: List<String>,
    val app: String,
    /** What to search for on Google Play (packages change; a search always lands). */
    val playQuery: String,
    val signup: String,
    val pay: String,
    val price: String,
    val notes: String = "",
)

const val GUIDE_DATE = "November 2026"

val OPERATORS = listOf(
    OperatorGuide(
        name = "PLN (SPKLU PLN)",
        keywords = listOf("pln", "spklu"),
        app = "PLN Mobile",
        playQuery = "PLN Mobile",
        signup = "Free account with your phone number and email (the old Charge.IN app now lives inside PLN Mobile, menu Electric Vehicle). No membership.",
        pay = "Cashless only, in the app: e-wallet / QRIS / virtual account; BRIZZI tap cards at units marked EV-Tap. Top up before you arrive.",
        price = "Rp 2.466/kWh (government-set rate) + street-lighting tax. DC fast adds a service fee of up to Rp 25.000 per session (22–50 kW) or up to Rp 57.000 (above 50 kW); many PLN sites charge less or none. AC: no service fee.",
        notes = "The app shows live availability, a trip planner and the queue (AntreEV). Largest network in Jakarta and along the toll roads.",
    ),
    OperatorGuide(
        name = "Voltron",
        keywords = listOf("voltron"),
        app = "Voltron Indonesia",
        playQuery = "Voltron Indonesia EV charging",
        signup = "Free account (phone/email). No membership.",
        pay = "In-app balance via QRIS / e-wallet. Scan the QR on the unit, plug in, Start in the app.",
        price = "Rp 2.467/kWh + Rp 4.000 admin fee + 5% street-lighting tax + VAT, so roughly Rp 3.400/kWh all-in below 50 kW and Rp 3.600/kWh above 50 kW.",
        notes = "Idle fine after three warnings once charging ends: Rp 500/min AC, Rp 1.000/min DC. Has 360 kW hyper-fast units (Living World Alam Sutera).",
    ),
    OperatorGuide(
        name = "Starvo",
        keywords = listOf("starvo"),
        app = "Starvo",
        playQuery = "Starvo EV charging",
        signup = "Free account. No membership.",
        pay = "In-app: plug in, scan the barcode on the unit, Stop in the app when done.",
        price = "About Rp 1.800–2.200/kWh on AC units, up to about Rp 2.466/kWh on 120 kW DC. Some hotel and mall sites bill per hour or add a valet/parking fee instead.",
    ),
    OperatorGuide(
        name = "CHARGE+ (Utomo Charge+)",
        keywords = listOf("charge+", "charge plus", "utomo"),
        app = "Charge+",
        playQuery = "Charge+ EV charging Utomo",
        signup = "Account with email or phone number. No membership.",
        pay = "QRIS or credit card in the app.",
        price = "Government base rate per kWh plus the regulated DC service fee (applied since May 2024). Exact figure shown in the app before you start.",
        notes = "Jakarta sites include Gama Tower, Plaza Galeon and PRJ Kemayoran.",
    ),
    OperatorGuide(
        name = "Hyundai",
        keywords = listOf("hyundai"),
        app = "myHyundai Indonesia",
        playQuery = "myHyundai Indonesia",
        signup = "Open to every EV brand: register, pick your car model (Hyundai owners enter the VIN). No membership; the old charging card is discontinued.",
        pay = "Top up an EV Token balance by virtual account, QRIS, card or e-wallet. Scan the charger's QR, plug in, wait for 'preparing', tap Start Charging.",
        price = "Tariff from 1 Oct 2026: electricity per kWh varies by site + Rp 3.000 admin per session (waived for Hyundai EVs). Plaza Indonesia DC: service fee Rp 1.581/kWh up to 40 kWh (max Rp 63.270). Idle fee after 15 min: Rp 1.000/min DC, Rp 500/min AC.",
        notes = "Some chargers are reserved for Hyundai cars and dealer chargers are Hyundai-only. Partner chargers (Voltron, Daya Green, Casion, Charge+) also work through this app.",
    ),
    OperatorGuide(
        name = "Shell Recharge",
        keywords = listOf("shell"),
        app = "Shell (Shell Recharge)",
        playQuery = "Shell Recharge",
        signup = "Free Shell account. No membership.",
        pay = "In the Shell app at the pump-side chargers.",
        price = "Last published: Rp 2.166/kWh + Rp 25.000 service fee per DC session (CCS2/CHAdeMO). Check the app, this figure is older.",
    ),
    OperatorGuide(
        name = "Casion",
        keywords = listOf("casion"),
        app = "Casion",
        playQuery = "Casion EV charging",
        signup = "Free account. No membership.",
        pay = "In-app / QRIS.",
        price = "Billed per minute (about Rp 1.000/min), not per kWh, so slow-charging cars pay more per kWh.",
    ),
    OperatorGuide(
        name = "Terra Charge",
        keywords = listOf("terra"),
        app = "Terra Charge",
        playQuery = "Terra Charge",
        signup = "Free account. No membership.",
        pay = "In-app.",
        price = "About Rp 3.022/kWh.",
    ),
    OperatorGuide(
        name = "Daya Green",
        keywords = listOf("daya green", "dayagreen"),
        app = "Daya Green or myHyundai",
        playQuery = "Daya Green EV charging",
        signup = "Free account; also reachable as a partner inside myHyundai.",
        pay = "In-app / QRIS.",
        price = "Shown in the app before you start.",
    ),
)

/** The guide for a station, by operator name first and station name second; null if unknown. */
fun guideFor(s: Station): OperatorGuide? {
    val op = s.operator.lowercase()
    val name = s.name.lowercase()
    return OPERATORS.firstOrNull { g -> g.keywords.any { it in op } }
        ?: OPERATORS.firstOrNull { g -> g.keywords.any { it in name } }
}

fun openPlay(context: Context, query: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=${Uri.encode(query)}&c=apps")))
    } catch (_: ActivityNotFoundException) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/search?q=${Uri.encode(query)}&c=apps")))
    }
}

/** The compact card inside a charger's sheet. */
@Composable
fun OperatorCard(s: Station) {
    val context = LocalContext.current
    val g = guideFor(s)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF1F2733)).padding(12.dp)) {
        if (g == null) {
            Text("BEFORE YOU CHARGE", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text("Operator not identified. Look for the QR sticker on the unit: it names the app. Most take QRIS; none need a membership.", fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
        } else {
            Text("BEFORE YOU CHARGE · ${g.name.uppercase()}", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text("App: ${g.app}", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
            Text(g.price, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                Button(onClick = { openPlay(context, g.playQuery) }) { Text("Get app") }
                Spacer(Modifier.padding(4.dp))
                Text("Full details in the Guide tab", color = Muted, fontSize = 12.sp)
            }
        }
    }
}

/** The Guide tab: how charging works in Jakarta, then every operator. */
@Composable
fun GuideTab() {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        H("How charging works in Jakarta")
        P(
            "No membership anywhere. Each operator has its own free app: register with a phone number or email, " +
                "top up a balance (QRIS, e-wallet, virtual account or card), then at the charger scan the QR code on the unit, " +
                "plug in, tap Start in the app and Stop when you are done. Nobody takes cash. Install the apps at home, " +
                "not in a car park with one bar of signal.",
        )
        H("What it costs")
        P(
            "Electricity is government-set at Rp 2.466 per kWh (ESDM). DC fast charging may add a service fee capped by " +
                "regulation at Rp 25.000 per session for 22–50 kW units and Rp 57.000 for faster ones; AC charging has no " +
                "service fee. Private operators add admin fees, the 5% street-lighting tax and VAT, so expect roughly " +
                "Rp 2.500–3.600 per kWh all-in. Several charge an idle fee per minute if the car stays plugged in after it finishes.",
        )
        P(
            "Example: a 60 kWh battery from 20% to 80% takes about 36 kWh. At Rp 2.466 that is about Rp 89.000, plus a " +
                "Rp 25.000 fast-charging fee: around Rp 114.000. At home on a 2.200 VA meter the same energy costs about Rp 52.000.",
        )
        H("Before you plug in")
        P(
            "• Match the plug: nearly every DC charger here is CCS2; AC posts are Type 2 (a 'socket only' post needs your own cable).\n" +
                "• A charger's kW is its ceiling; your car's own limit decides the speed, and DC slows above 80%.\n" +
                "• Check the charger is free and working in the operator's app, this app has no live status.\n" +
                "• Mall chargers usually sit in paid parking; some Hyundai dealer units are Hyundai-only.\n" +
                "• Unplug promptly when finished: idle fees run per minute.",
        )
        H("Operators in Jakarta (prices as of $GUIDE_DATE)")
        P("Published figures from the operators and motoring press; the app always shows the live price before you start.")
        for (g in OPERATORS) {
            Column(Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(12.dp)).background(CardColor).padding(12.dp)) {
                Text(g.name, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Fact2("App", g.app)
                Fact2("Sign up", g.signup)
                Fact2("Pay", g.pay)
                Fact2("Price", g.price)
                if (g.notes.isNotBlank()) Fact2("Notes", g.notes)
                TextButton(onClick = { openPlay(context, g.playQuery) }) { Text("Get ${g.app} on Google Play") }
            }
        }
        Spacer(Modifier.height(8.dp))
        P("Sources: ESDM decree 182.K/TL.04/MEM.S/2023 (service-fee caps), hyundai.com/id (tariff 1 Oct 2026), Otodriver and GarduOto price lists (2025–2026), operator announcements. Prices change; the operator app is the final word.")
    }
}

@Composable
private fun H(text: String) = Text(text, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))

@Composable
private fun P(text: String) = Text(text, color = Color(0xFFD5DBE2), fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(bottom = 6.dp))

@Composable
private fun Fact2(label: String, value: String) {
    Text(label.uppercase(), color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
    Text(value, fontSize = 14.sp, lineHeight = 19.sp)
}
