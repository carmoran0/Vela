package app.vela.ui.map

import android.os.SystemClock
import android.util.Log
import app.vela.core.data.transit.Transitous
import app.vela.core.model.BikeShareStatus
import app.vela.core.model.LatLng
import app.vela.core.model.Route
import app.vela.core.model.TransitItinerary
import app.vela.core.model.TransitMode
import app.vela.core.model.TransitRental
import app.vela.core.model.TransitStep
import app.vela.core.model.TransitStopTime
import app.vela.core.model.TravelMode
import app.vela.core.model.distanceTo
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
 * An alert offers the nearest other station ([alternative]) and the trip through it. An empty
 * pickup is planned again from the rider first ([acceptReplan]): the planner skips empty stations
 * and times the rest of the trip. Otherwise, and always for a return, the trip is spliced
 * ([splicePickup], [spliceReturn]) with legs from the walk and bike routers ([routeOf]).
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
    /** The first route between two points by that mode; null on any failure. */
    private val routeOf: suspend (LatLng, LatLng, TravelMode) -> Route?,
    /** The fresh counts of the watched station, keyed for [BikeShareCounts]. */
    private val onCounts: (Map<String, BikeShareStatus>) -> Unit = BikeShareCounts::replace,
    /** The stations of those systems in a box `[south, west, north, east]`; null on any failure. */
    private val stationsOf: suspend (DoubleArray, List<String>) -> List<Transitous.BikeStation>? = { b, g ->
        withContext(Dispatchers.IO) { Transitous.bikeStationsInBox(http, b[0], b[1], b[2], b[3], g) }
    },
    /** Bike-share trips from a point to another on those systems; empty on any failure. */
    private val replanOf: suspend (LatLng, LatLng, List<String>) -> List<TransitItinerary> = { a, b, g ->
        withContext(Dispatchers.IO) { Transitous.planBikeShare(http, a, b, g) }
    },
    private val durText: (Long) -> String = { app.vela.ui.formatDuration(it.toDouble()) },
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
        val here = riderNear(hereOf(), at)
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
        val now = wallClock()
        val counts = mapOf(BikeShareCounts.keyOf(w.rental.providerId, w.stop.name) to BikeShareStations.statusOf(st, now))
        onCounts(counts)
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
        val a = alertFor(w, BikeShareAlert(key, w.end, level, w.stop.name, countOf(w.end, st)), st, at, stations, here)
        Log.i(TAG, "bike share watch: ${w.end} other station ${if (a.altName != null) "found" else "none"}, offer ${if (a.offer != null) "ready" else "none"}")
        stations.firstOrNull { it.name == a.altName && it.key.startsWith("${w.rental.providerId}/") }?.let { alt ->
            onCounts(counts + (BikeShareCounts.keyOf(w.rental.providerId, alt.name) to BikeShareStations.statusOf(alt, now)))
        }
        show(a)
        return true
    }

    /** [base] with the other station and the trip through it, when there are. */
    private suspend fun alertFor(
        w: Watch, base: BikeShareAlert, st: Transitous.BikeStation, at: LatLng,
        stations: List<Transitous.BikeStation>, here: LatLng?,
    ): BikeShareAlert {
        if (w.end == BikeEnd.PICKUP && base.level == BikeLevel.OUT && here != null) {
            val dest = destinationOf(w.itinerary)
            val groups = w.itinerary.steps.mapNotNull { it.rental?.groupId }.distinct()
            if (dest != null) {
                val trips = runCatching { replanOf(here, dest, groups) }.getOrDefault(emptyList())
                val t = acceptReplan(trips, w.stop.name, here, at)
                val board = t?.steps?.firstOrNull { it.mode == TransitMode.BIKE_SHARE }?.boardStop
                val boardAt = board?.location
                if (t != null && board != null && boardAt != null) {
                    val known = Transitous.stationFor(stations, w.rental.providerId, board.name, boardAt)
                    return base.copy(
                        altName = board.name, altDistanceM = at.distanceTo(boardAt),
                        altCount = known?.let { offered(BikeEnd.PICKUP, it) },
                        offer = BikeShareOffer(t, 0, w.itinerary),
                    )
                }
            }
        }
        val alt = alternative(stations, w, st.key, at, here) ?: return base
        val altAt = LatLng(alt.lat, alt.lon)
        val spliced = when (w.end) {
            BikeEnd.PICKUP -> {
                val from = here ?: w.itinerary.steps.getOrNull(w.bikeIndex - 1)?.walkFrom
                val to = w.itinerary.steps[w.bikeIndex].alightStop?.location
                if (from == null || to == null) null
                else splicePickup(w.itinerary, w.bikeIndex, from, alt, routeOf(from, altAt, TravelMode.WALK), routeOf(altAt, to, TravelMode.BICYCLE), durText)
            }
            BikeEnd.RETURN -> {
                val from = here ?: w.itinerary.steps[w.bikeIndex].boardStop?.location
                val next = w.itinerary.steps.getOrNull(w.bikeIndex + 1)
                val to = next?.takeIf { it.mode == TransitMode.WALK }?.walkTo ?: at
                if (from == null) null
                else spliceReturn(w.itinerary, w.bikeIndex, from, alt, routeOf(from, altAt, TravelMode.BICYCLE), routeOf(altAt, to, TravelMode.WALK), durText)
            }
        }
        return base.copy(
            altName = alt.name, altDistanceM = at.distanceTo(altAt), altCount = offered(w.end, alt),
            offer = spliced?.let { BikeShareOffer(it, w.stepIndex, w.itinerary) },
        )
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
        /** The phone this far or farther from the watched station is not on the trip (a trip
         *  planned from somewhere else): the leg's own start stands in for it. */
        const val RIDER_MAX_M = 3_000.0

        /** [here] when it is within [RIDER_MAX_M] of the watched station at [at], else null. */
        internal fun riderNear(here: LatLng?, at: LatLng): LatLng? = here?.takeIf { it.distanceTo(at) <= RIDER_MAX_M }
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

        /** A walk this fast and a ride this fast stand in for a router that did not answer. */
        private const val WALK_MPS = 1.3
        private const val BIKE_MPS = 4.0

        /** The free bikes or docks [st] offers at [end], null when it takes none there. */
        private fun offered(end: BikeEnd, st: Transitous.BikeStation): Int? = when (end) {
            BikeEnd.PICKUP -> st.bikes.takeIf { st.renting }
            BikeEnd.RETURN -> st.docks?.takeIf { st.returning }
        }

        /**
         * The other station for [w]: the same provider's, renting for a pickup or returning with a
         * dock count for a return, within [radiusM] of the watched station at [at], or for a pickup
         * of the rider at [here]. Nearest to the rider for a pickup, to [at] for a return: the
         * nearest with more than [BikeShareCounts.FEW] first, else the nearest with any. Null when
         * there is none.
         */
        internal fun alternative(
            stations: List<Transitous.BikeStation>, w: Watch, watchedKey: String, at: LatLng, here: LatLng?,
            radiusM: Double = ALT_RADIUS_M,
        ): Transitous.BikeStation? {
            val rider = here?.takeIf { w.end == BikeEnd.PICKUP }
            val near = stations.asSequence()
                .filter { it.key.startsWith("${w.rental.providerId}/") && it.key != watchedKey }
                .mapNotNull { st -> offered(w.end, st)?.takeIf { it > 0 }?.let { n -> st to n } }
                .filter { (st, _) -> inReach(LatLng(st.lat, st.lon), at, rider, radiusM) }
                .map { (st, n) -> Triple(st, n, (rider ?: at).distanceTo(LatLng(st.lat, st.lon))) }
                .toList()
            return (near.filter { it.second > BikeShareCounts.FEW }.minByOrNull { it.third } ?: near.minByOrNull { it.third })?.first
        }

        /** Within [radiusM] of the watched station at [at], or of the rider at [rider]. */
        private fun inReach(p: LatLng, at: LatLng, rider: LatLng?, radiusM: Double): Boolean =
            p.distanceTo(at) <= radiusM || (rider != null && p.distanceTo(rider) <= radiusM)

        /** The first of [trips] whose first bike leg starts somewhere else than the empty station
         *  [emptyName] at [emptyAt], within [radiusM] of it or of the rider at [here]: a re-plan
         *  around an empty pickup. */
        internal fun acceptReplan(
            trips: List<TransitItinerary>, emptyName: String, here: LatLng, emptyAt: LatLng, radiusM: Double = ALT_RADIUS_M,
        ): TransitItinerary? = trips.firstOrNull { t ->
            val board = t.steps.firstOrNull { it.mode == TransitMode.BIKE_SHARE }?.boardStop ?: return@firstOrNull false
            val p = board.location ?: return@firstOrNull false
            !board.name.trim().equals(emptyName.trim(), ignoreCase = true) && inReach(p, emptyAt, here, radiusM)
        }

        /** A leg's path from a router's answer: null for no answer, a line too short to draw. */
        private fun pathOf(r: Route?): List<LatLng>? = r?.polyline?.takeIf { it.size >= 2 }

        private fun secsOf(r: Route?, a: LatLng, b: LatLng, mps: Double): Long =
            (r?.durationSeconds ?: (a.distanceTo(b) / mps)).toLong().coerceAtLeast(60)

        /** Whether everything after step [i] is a walk: then the trip's arrival moves with a splice,
         *  and is not known any more. Behind a timed ride it is that ride's arrival and stays. */
        private fun onlyWalksAfter(itin: TransitItinerary, i: Int): Boolean =
            itin.steps.drop(i + 1).all { it.mode == TransitMode.WALK }

        private fun TransitItinerary.withSteps(steps: List<TransitStep>, from: Int): TransitItinerary =
            if (onlyWalksAfter(copy(steps = steps), from)) copy(steps = steps, arrivalText = null, arrivalEpochSec = null)
            else copy(steps = steps)

        /**
         * [itin] with the pickup of the bike leg at [bikeIndex] moved to [station]: the walk before
         * it goes from [here] to the station ([walk]), and the ride starts there ([bike]). The two
         * legs lose their clock times; a router that did not answer leaves a leg without a path and
         * with a time from its straight-line length.
         */
        internal fun splicePickup(
            itin: TransitItinerary, bikeIndex: Int, here: LatLng, station: Transitous.BikeStation,
            walk: Route?, bike: Route?, durText: (Long) -> String,
        ): TransitItinerary? {
            val walkStep = itin.steps.getOrNull(bikeIndex - 1)?.takeIf { it.mode == TransitMode.WALK } ?: return null
            val ride = itin.steps.getOrNull(bikeIndex)?.takeIf { it.mode == TransitMode.BIKE_SHARE } ?: return null
            val at = LatLng(station.lat, station.lon)
            val to = ride.alightStop?.location ?: return null
            val steps = itin.steps.toMutableList()
            steps[bikeIndex - 1] = walkStep.copy(
                walkFrom = here, walkTo = at, path = pathOf(walk), distanceText = null,
                durationText = durText(secsOf(walk, here, at, WALK_MPS)),
            )
            steps[bikeIndex] = ride.copy(
                boardStop = TransitStopTime(name = station.name, location = at),
                path = pathOf(bike), departText = null, arriveText = null,
                alightStop = ride.alightStop?.copy(timeText = null),
                durationText = durText(secsOf(bike, at, to, BIKE_MPS)),
            )
            return itin.withSteps(steps, bikeIndex)
        }

        /**
         * [itin] with the return of the bike leg at [bikeIndex] moved to [station]: the ride goes on
         * from [here] to the station ([bike]), and the walk after it starts there ([walk]), to where
         * that walk went. With no walk after the ride, one is added to the old return station, where
         * the trip went on from.
         */
        internal fun spliceReturn(
            itin: TransitItinerary, bikeIndex: Int, here: LatLng, station: Transitous.BikeStation,
            bike: Route?, walk: Route?, durText: (Long) -> String,
        ): TransitItinerary? {
            val ride = itin.steps.getOrNull(bikeIndex)?.takeIf { it.mode == TransitMode.BIKE_SHARE } ?: return null
            val old = ride.alightStop?.location ?: return null
            val at = LatLng(station.lat, station.lon)
            val steps = itin.steps.toMutableList()
            steps[bikeIndex] = ride.copy(
                alightStop = TransitStopTime(name = station.name, location = at),
                path = pathOf(bike), arriveText = null,
                durationText = durText(secsOf(bike, here, at, BIKE_MPS)),
            )
            val next = steps.getOrNull(bikeIndex + 1)
            val target = next?.takeIf { it.mode == TransitMode.WALK }?.walkTo ?: old
            val newWalk = TransitStep(
                mode = TransitMode.WALK, walkFrom = at, walkTo = target, path = pathOf(walk),
                durationText = durText(secsOf(walk, at, target, WALK_MPS)),
            )
            if (next?.mode == TransitMode.WALK) steps[bikeIndex + 1] = next.copy(
                walkFrom = at, path = newWalk.path, durationText = newWalk.durationText, distanceText = null,
            ) else steps.add(bikeIndex + 1, newWalk)
            return itin.withSteps(steps, bikeIndex)
        }

        /** Where [itin] ends: its last walk's end, else its last ride's stop. */
        internal fun destinationOf(itin: TransitItinerary): LatLng? =
            itin.steps.lastOrNull()?.let { it.walkTo ?: it.alightStop?.location }

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
