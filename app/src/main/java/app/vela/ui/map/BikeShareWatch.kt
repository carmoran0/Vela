package app.vela.ui.map

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import app.vela.R
import app.vela.core.model.TransitItinerary

/** Which end of a bike-share leg is watched: where the bike is taken, or where it goes back. */
enum class BikeEnd { PICKUP, RETURN }

/** [OUT]: no bike to take or no dock to leave it in. [FEW]: [BikeShareCounts.FEW] or fewer. */
enum class BikeLevel { OUT, FEW }

/** The trip guidance takes on Switch: [itinerary] from [stepIndex], built for [base], the
 *  itinerary in guidance when the offer was made. A guidance that has moved on ignores it. */
data class BikeShareOffer(val itinerary: TransitItinerary, val stepIndex: Int, val base: TransitItinerary)

/**
 * A watched station that ran out or is running low during transit guidance (SPEC 8, Bike share
 * in directions). [count] is its free bikes or docks, 0 when it is out of service. The other
 * station, when one was found, is [altName] at [altDistanceM] with [altCount] bikes or docks
 * (null when the count is not known). [offer] is null when no other trip could be built.
 */
data class BikeShareAlert(
    val key: String,
    val end: BikeEnd,
    val level: BikeLevel,
    val station: String,
    val count: Int,
    val altName: String? = null,
    val altDistanceM: Double? = null,
    val altCount: Int? = null,
    val offer: BikeShareOffer? = null,
)

/**
 * The bike-share alert of the guided trip, written by the view model from [BikeShareTripWatch]
 * and read by the transit guidance sheet. A holder, not map state: MapScreen is at its size
 * limits and takes no new parameter. [onSwitch] and [onKeep] are the view model's.
 */
object BikeShareWatch {
    val alert = mutableStateOf<BikeShareAlert?>(null)
    @Volatile var onSwitch: (() -> Unit)? = null
    @Volatile var onKeep: (() -> Unit)? = null

    /** The first line: what happened at the watched station. */
    fun headline(c: Context, a: BikeShareAlert): String = when {
        a.level == BikeLevel.OUT && a.end == BikeEnd.PICKUP -> c.getString(R.string.transit_bike_no_bikes, a.station)
        a.level == BikeLevel.OUT -> c.getString(R.string.transit_bike_no_docks, a.station)
        a.end == BikeEnd.PICKUP -> c.resources.getQuantityString(R.plurals.transit_bike_few_bikes, a.count, a.count, a.station)
        else -> c.resources.getQuantityString(R.plurals.transit_bike_few_docks, a.count, a.count, a.station)
    }

    /** What the voice says once. */
    fun spoken(c: Context, a: BikeShareAlert): String = headline(c, a)
}
