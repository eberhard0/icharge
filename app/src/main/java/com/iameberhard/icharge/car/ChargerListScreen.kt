package com.iameberhard.icharge.car

import android.content.Intent
import android.net.Uri
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.CarLocation
import androidx.car.app.model.Distance
import androidx.car.app.model.DistanceSpan
import androidx.car.app.model.ItemList
import androidx.car.app.model.Metadata
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Place
import androidx.car.app.model.PlaceListMapTemplate
import androidx.car.app.model.PlaceMarker
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.iameberhard.icharge.IChargeApp
import com.iameberhard.icharge.R
import com.iameberhard.icharge.Station
import com.iameberhard.icharge.distanceM
import com.iameberhard.icharge.guideFor
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** The nearest chargers around the car (and the trip's next stop first), on the car's own map. */
class ChargerListScreen(carContext: CarContext) : Screen(carContext) {
    private val app = IChargeApp.of(carContext)
    private var rows: List<Pair<Station, Double>> = emptyList()
    private var nextStopId: String? = null

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) { app.locator.start() }
        })
        lifecycleScope.launch {
            combine(app.stations.stations, app.locator.position, app.trip.state) { stations, here, trip -> Triple(stations, here, trip) }
                .collect { (stations, here, trip) ->
                    val origin = here ?: (-6.1754 to 106.8272)
                    val next = trip?.plan?.nextStop?.takeIf { !trip.ignoreAll }
                    nextStopId = next?.charger?.station?.id
                    val nearest = stations.asSequence()
                        .filter { it.id != nextStopId }
                        .map { it to distanceM(origin.first, origin.second, it.lat, it.lon) }
                        .sortedBy { it.second }
                        .take(if (next != null) 5 else 6)
                        .toList()
                    rows = (next?.let { listOf(it.charger.station to distanceM(origin.first, origin.second, it.charger.station.lat, it.charger.station.lon)) } ?: emptyList()) + nearest
                    invalidate()
                }
        }
    }

    override fun onGetTemplate(): Template {
        val list = ItemList.Builder()
        if (rows.isEmpty()) list.setNoItemsMessage("Loading chargers…")
        for ((s, d) in rows) {
            val isNext = s.id == nextStopId
            val title = android.text.SpannableString((if (isNext) "⚡ Next charge: " else "") + s.name)
            val sub = android.text.SpannableString("  · " + s.plugLine + (if (s.operator.isNotBlank()) " · " + s.operator else ""))
            sub.setSpan(DistanceSpan.create(Distance.create(d / 1000.0, Distance.UNIT_KILOMETERS)), 0, 1, android.text.Spanned.SPAN_INCLUSIVE_INCLUSIVE)
            list.addItem(
                Row.Builder()
                    .setTitle(title)
                    .addText(sub)
                    .setMetadata(
                        Metadata.Builder().setPlace(
                            Place.Builder(CarLocation.create(s.lat, s.lon))
                                .setMarker(PlaceMarker.Builder().setColor(if (isNext) CarColor.YELLOW else if (s.fast) CarColor.PRIMARY else CarColor.BLUE).build())
                                .build(),
                        ).build(),
                    )
                    .setOnClickListener { screenManager.push(ChargerDetailScreen(carContext, s, isNext)) }
                    .build(),
            )
        }
        return PlaceListMapTemplate.Builder()
            .setTitle("iCharge")
            .setHeaderAction(Action.APP_ICON)
            .setCurrentLocationEnabled(true)
            .setItemList(list.build())
            .setActionStrip(ActionStrip.Builder().addAction(Action.Builder().setTitle("Refresh").setOnClickListener { invalidate() }.build()).build())
            .build()
    }
}

/** One charger: plugs, price, the app to pay with, and Navigate (hands over to Google Maps on the car). */
class ChargerDetailScreen(carContext: CarContext, private val s: Station, private val isNext: Boolean) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        val g = guideFor(s)
        val pane = Pane.Builder()
            .addRow(Row.Builder().setTitle("Plugs").addText(s.plugLine).build())
        if (s.operator.isNotBlank() || g != null) pane.addRow(Row.Builder().setTitle("Operator").addText((s.operator.ifBlank { g?.name ?: "" }) + (g?.let { " · app: ${it.app}" } ?: "")).build())
        g?.let { pane.addRow(Row.Builder().setTitle("Price").addText(it.price.take(120)).build()) }
        if (s.address.isNotBlank()) pane.addRow(Row.Builder().setTitle("Address").addText(s.address.take(120)).build())
        pane.addAction(
            Action.Builder().setTitle("Navigate").setFlags(Action.FLAG_PRIMARY).setOnClickListener {
                carContext.startCarApp(Intent(CarContext.ACTION_NAVIGATE, Uri.parse("geo:${s.lat},${s.lon}?q=${s.lat},${s.lon}(${Uri.encode(s.name)})")))
            }.build(),
        )
        if (isNext) pane.addAction(
            Action.Builder().setTitle("Ignore stop").setOnClickListener {
                IChargeApp.of(carContext).trip.ignoreStop(s.id)
                screenManager.pop()
            }.build(),
        )
        return PaneTemplate.Builder(pane.build())
            .setTitle(s.name)
            .setHeaderAction(Action.BACK)
            .build()
    }
}
