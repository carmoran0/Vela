package app.vela.ui.map

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import app.vela.R
import app.vela.core.model.TransitItinerary
import app.vela.ui.formatDistance

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

    /** The second line: the other station, or that there is none. Null for a low count with no
     *  other station, where the first line says it all. */
    fun altLine(c: Context, a: BikeShareAlert): String? {
        val name = a.altName
        val d = a.altDistanceM
        if (name == null || d == null) return if (a.level == BikeLevel.OUT) c.getString(R.string.transit_bike_no_alt) else null
        val n = a.altCount ?: return c.getString(R.string.transit_bike_alt_plain, name, formatDistance(d))
        val count = c.resources.getQuantityString(
            if (a.end == BikeEnd.PICKUP) R.plurals.place_bike_share_bikes else R.plurals.place_bike_share_docks, n, n,
        )
        return c.getString(R.string.transit_bike_alt, name, formatDistance(d), count)
    }

    /** The notification's heads-up: both lines. */
    fun text(c: Context, a: BikeShareAlert): String = listOfNotNull(headline(c, a), altLine(c, a)).joinToString(" ")

    /** What the voice says once: the first line and where there are some. */
    fun spoken(c: Context, a: BikeShareAlert): String =
        listOfNotNull(headline(c, a), a.altName?.let { c.getString(R.string.transit_bike_spoken_alt, it) }).joinToString(" ")
}
