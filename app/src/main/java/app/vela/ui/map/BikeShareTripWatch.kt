package app.vela.ui.map

import android.os.SystemClock
import android.util.Log
import app.vela.core.data.transit.Transitous
import app.vela.core.model.BikeShareStatus
import app.vela.core.model.LatLng
import app.vela.core.model.TransitItinerary
import app.vela.core.model.TransitMode
import app.vela.core.model.TransitRental
import app.vela.core.model.TransitStopTime
import app.vela.ui.AppTune
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/**
 * The stations of a guided bike-share trip, checked on the way (SPEC 8, Bike share in
 * directions). On the walk to a pickup its free bikes are asked for, on the ride its return's
 * free docks, once as the leg starts and then every [refreshMs], with the screen off too: the
 * guidance is a foreground service the rider started. Nothing is asked with "Use less data" or
 * offline ([canAsk]). A return whose feed gives no dock count is asked for once, then left.
 *
 * A station at none, or out of service, raises a [BikeLevel.OUT] alert; one at
 * [BikeShareCounts.FEW] or fewer a [BikeLevel.FEW] one. A failed request or a station missing from
 * the reply changes nothing. Keep ([dismiss]) drops an alert for good; a low count that then runs
 * out raises a new one.
 *
 * Construct it ABOVE the view model's `init` and call [bind] from there: the collector's first
 * pass runs inline.
 */
internal class BikeShareTripWatch(
    private val scope: CoroutineScope,
    private val nav: Flow<TransitNavState?>,
    http: OkHttpClient,
    private val hereOf: () -> LatLng?,
    private val canAsk: () -> Boolean,
    /** A new alert, or null when there is none any more. */
    private val onChange: (BikeShareAlert?) -> Unit,
    /** The fresh counts of the watched station, keyed for [BikeShareCounts]. */
    private val onCounts: (Map<String, BikeShareStatus>) -> Unit = BikeShareCounts::replace,
    /** The stations of those systems in a box `[south, west, north, east]`; null on any failure. */
    private val stationsOf: suspend (DoubleArray, List<String>) -> List<Transitous.BikeStation>? = { b, g ->
        withContext(Dispatchers.IO) { Transitous.bikeStationsInBox(http, b[0], b[1], b[2], b[3], g) }
    },
    /** The adb-only test hook: the watched station reads as having this many bikes and docks. */
    private val fake: () -> Int? = { AppTune.local("bikeShareFake")?.toInt() },
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val refreshMs: Long = BikeShareStations.REFRESH_MS,
) {
    /** The alert on screen, if any. Written on the view model's scope only. */
    internal var alert: BikeShareAlert? = null
        private set
    private val dismissed = HashSet<String>()

    fun bind() {
        scope.launch {
            nav.map(::watchOf)
                .distinctUntilChanged { a, b -> a?.stepIndex == b?.stepIndex && a?.end == b?.end && a?.itinerary === b?.itinerary }
                .collectLatest { w ->
                    show(null)
                    if (w == null) {
                        dismissed.clear()
                        return@collectLatest
                    }
                    while (true) {
                        if (!check(w)) return@collectLatest
                        delay(refreshMs)
                    }
                }
        }
    }

    /** Keep: the alert on screen goes and does not come back for that station and level. */
    fun dismiss() {
        alert?.let { dismissed += it.key }
        show(null)
    }

    /** One check of [w]'s station. False when the leg is not worth asking about again. */
    internal suspend fun check(w: Watch): Boolean {
        if (!canAsk()) return true
        val at = w.stop.location ?: return false
        val here = hereOf()
        val box = if (w.end == BikeEnd.PICKUP && here != null) Transitous.paddedBox(here, at, ALT_RADIUS_M)
            else Transitous.paddedBox(at, at, ALT_RADIUS_M)
        val t0 = clock()
        val stations = runCatching { stationsOf(box, listOf(w.rental.groupId)) }.getOrNull()
        if (stations == null) {
            Log.i(TAG, "bike share watch: ${w.end} check failed after ${clock() - t0} ms")
            return true
        }
        val found = Transitous.stationFor(stations, w.rental.providerId, w.stop.name, at) ?: run {
            Log.i(TAG, "bike share watch: ${w.end} station not in the reply")
            return true
        }
        val st = fake()?.let { n -> found.copy(bikes = n, docks = n) } ?: found
        onCounts(mapOf(BikeShareCounts.keyOf(w.rental.providerId, w.stop.name) to BikeShareStations.statusOf(st, wallClock())))
        if (w.end == BikeEnd.RETURN && st.docks == null) {
            Log.i(TAG, "bike share watch: the return's feed gives no dock count, not watched")
            return false
        }
        val level = levelOf(w.end, st)
        Log.i(TAG, "bike share watch: ${w.end} ${level ?: "fine"} in ${clock() - t0} ms")
        if (level == null) {
            show(null)
            return true
        }
        val key = "bike:${w.stepIndex}:${st.key}:${w.end}:$level"
        if (key in dismissed) {
            show(null)
            return true
        }
        if (alert?.key == key) return true
        show(BikeShareAlert(key, w.end, level, w.stop.name, countOf(w.end, st)))
        return true
    }

    private fun show(a: BikeShareAlert?) {
        if (a == alert) return
        alert = a
        onChange(a)
    }

    /** The station [end] of the bike leg at [bikeIndex] of [itinerary], watched while the
     *  guidance is at [stepIndex]. */
    internal data class Watch(
        val stepIndex: Int,
        val bikeIndex: Int,
        val end: BikeEnd,
        val rental: TransitRental,
        val stop: TransitStopTime,
        val itinerary: TransitItinerary,
    )

    companion object {
        /** How far from the rider, or from a full return, another station is looked for. The
         *  stations' box is padded by as much. */
        const val ALT_RADIUS_M = 800.0
        private const val TAG = "VelaTransit"

        /** What guidance at [tn] watches: the pickup of a bike leg while walking to it, the return
         *  while riding. Nothing on any other leg: a ride before the walk is no place to act from. */
        internal fun watchOf(tn: TransitNavState?): Watch? {
            if (tn == null || tn.arrived) return null
            val steps = tn.itinerary.steps
            val i = tn.stepIndex
            val s = steps.getOrNull(i) ?: return null
            if (s.mode == TransitMode.BIKE_SHARE) {
                return Watch(i, i, BikeEnd.RETURN, s.rental ?: return null, s.alightStop ?: return null, tn.itinerary)
            }
            if (s.mode != TransitMode.WALK) return null
            val next = steps.getOrNull(i + 1)?.takeIf { it.mode == TransitMode.BIKE_SHARE } ?: return null
            return Watch(i, i + 1, BikeEnd.PICKUP, next.rental ?: return null, next.boardStop ?: return null, tn.itinerary)
        }

        /** The free bikes at a pickup, the free docks at a return; 0 out of service. */
        internal fun countOf(end: BikeEnd, st: Transitous.BikeStation): Int = when (end) {
            BikeEnd.PICKUP -> if (st.renting) st.bikes else 0
            BikeEnd.RETURN -> if (st.returning) st.docks ?: 0 else 0
        }

        /** The alert [st] calls for at [end], null when it has enough. A return with no dock count
         *  calls for none. */
        internal fun levelOf(end: BikeEnd, st: Transitous.BikeStation): BikeLevel? {
            if (end == BikeEnd.RETURN && st.docks == null) return null
            val n = countOf(end, st)
            return when {
                n <= 0 -> BikeLevel.OUT
                n <= BikeShareCounts.FEW -> BikeLevel.FEW
                else -> null
            }
        }
    }
}
