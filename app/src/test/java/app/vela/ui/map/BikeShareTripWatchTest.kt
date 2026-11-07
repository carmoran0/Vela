package app.vela.ui.map

import app.vela.core.data.transit.Transitous
import app.vela.core.model.BikeShareStatus
import app.vela.core.model.LatLng
import app.vela.core.model.Route
import app.vela.core.model.TransitItinerary
import app.vela.core.model.TransitLine
import app.vela.core.model.TransitMode
import app.vela.core.model.TransitRental
import app.vela.core.model.TransitStep
import app.vela.core.model.TransitStopTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.EmptyCoroutineContext

/** What guidance watches on a bike-share trip and when it raises an alert (Davis fixture). */
class BikeShareTripWatchTest {
    private val start = LatLng(38.5449, -121.7405)
    private val covell = LatLng(38.5582, -121.7521)
    private val downtown = LatLng(38.5446, -121.7405)
    private val end = LatLng(38.5420, -121.7380)
    private val rental = TransitRental(providerId = "test-bikes", groupId = "test", system = "Test Bikes")

    private val walkIn = TransitStep(TransitMode.WALK, durationText = "5 min", walkFrom = start, walkTo = covell)
    private val ride = TransitStep(
        TransitMode.BIKE_SHARE, durationText = "10 min", line = TransitLine("Test Bikes", TransitMode.BIKE_SHARE),
        boardStop = TransitStopTime("Covell Park", location = covell),
        alightStop = TransitStopTime("Downtown", location = downtown),
        rental = rental,
    )
    private val walkOut = TransitStep(TransitMode.WALK, durationText = "3 min", walkFrom = downtown, walkTo = end)
    private val trip = TransitItinerary(steps = listOf(walkIn, ride, walkOut))

    private fun station(id: String, name: String, at: LatLng, bikes: Int, docks: Int?, renting: Boolean = true, returning: Boolean = true) =
        Transitous.BikeStation(
            key = "test-bikes/$id", name = name, lat = at.lat, lon = at.lng, address = null,
            bikes = bikes, docks = docks, renting = renting, returning = returning,
        )

    private class Seen {
        val alerts = ArrayList<BikeShareAlert?>()
        var asks = 0
        var groups: List<String>? = null
        var replans = 0
        var routes = 0
        val counts = HashMap<String, BikeShareStatus>()
    }

    private val near = LatLng(38.5590, -121.7510) // about 140 m from Covell Park
    private val far = LatLng(38.5700, -121.7521) // about 1.3 km from Covell Park
    private val durText: (Long) -> String = { "${it / 60} min" }

    private fun route(line: List<LatLng>, secs: Double) = Route(polyline = line, legs = emptyList(), distanceMeters = 0.0, durationSeconds = secs, durationInTrafficSeconds = null)

    private fun watch(
        seen: Seen, stations: () -> List<Transitous.BikeStation>?, canAsk: Boolean = true, fake: Int? = null,
        here: LatLng? = start, replan: List<TransitItinerary> = emptyList(), onReplan: suspend () -> Unit = {},
    ) =
        BikeShareTripWatch(
            scope = CoroutineScope(EmptyCoroutineContext),
            nav = emptyFlow(),
            http = OkHttpClient(),
            hereOf = { here },
            canAsk = { canAsk },
            onChange = { seen.alerts += it },
            routeOf = { a, b, _ -> seen.routes++; route(listOf(a, b), 600.0) },
            replanOf = { _, _, _ -> seen.replans++; onReplan(); replan },
            durText = durText,
            onCounts = { seen.counts += it },
            stationsOf = { _, g -> seen.asks++; seen.groups = g; stations() },
            fake = { fake },
            clock = { 0L },
            wallClock = { 0L },
        )

    @Test fun `the walk to a bike leg watches its pickup and the ride its return`() {
        val pickup = BikeShareTripWatch.watchOf(TransitNavState(trip, 0))!!
        assertEquals(BikeEnd.PICKUP, pickup.end)
        assertEquals("Covell Park", pickup.stop.name)
        assertEquals(1, pickup.bikeIndex)
        val ret = BikeShareTripWatch.watchOf(TransitNavState(trip, 1))!!
        assertEquals(BikeEnd.RETURN, ret.end)
        assertEquals("Downtown", ret.stop.name)
        assertNull(BikeShareTripWatch.watchOf(TransitNavState(trip, 2)))
        assertNull(BikeShareTripWatch.watchOf(TransitNavState(trip, 1, arrived = true)))
        assertNull(BikeShareTripWatch.watchOf(null))
    }

    @Test fun `a ride before the walk to the bike watches nothing`() {
        val bus = TransitStep(TransitMode.BUS, line = TransitLine("42"), boardStop = TransitStopTime("A"), alightStop = TransitStopTime("B"))
        val t = TransitItinerary(steps = listOf(bus, walkIn, ride, walkOut))
        assertNull(BikeShareTripWatch.watchOf(TransitNavState(t, 0)))
        assertEquals(BikeEnd.PICKUP, BikeShareTripWatch.watchOf(TransitNavState(t, 1))?.end)
    }

    @Test fun `none or out of service is out, two or fewer is few`() {
        val p = BikeEnd.PICKUP
        assertEquals(BikeLevel.OUT, BikeShareTripWatch.levelOf(p, station("1", "A", covell, 0, 5)))
        assertEquals(BikeLevel.OUT, BikeShareTripWatch.levelOf(p, station("1", "A", covell, 9, 5, renting = false)))
        assertEquals(BikeLevel.FEW, BikeShareTripWatch.levelOf(p, station("1", "A", covell, 2, 5)))
        assertNull(BikeShareTripWatch.levelOf(p, station("1", "A", covell, 5, 5)))
        val r = BikeEnd.RETURN
        assertEquals(BikeLevel.OUT, BikeShareTripWatch.levelOf(r, station("1", "A", covell, 9, 0)))
        assertEquals(BikeLevel.OUT, BikeShareTripWatch.levelOf(r, station("1", "A", covell, 9, 6, returning = false)))
        assertEquals(BikeLevel.FEW, BikeShareTripWatch.levelOf(r, station("1", "A", covell, 9, 1)))
        assertNull(BikeShareTripWatch.levelOf(r, station("1", "A", covell, 0, 6)))
        // No dock count, no return alert, even out of service.
        assertNull(BikeShareTripWatch.levelOf(r, station("1", "A", covell, 0, null, returning = false)))
    }

    @Test fun `an empty pickup raises one alert on the trip's own system`() = runBlocking {
        val seen = Seen()
        val w = watch(seen, { listOf(station("1", "COVELL PARK", covell, 0, 4)) })
        val pickup = BikeShareTripWatch.watchOf(TransitNavState(trip, 0))!!
        assertTrue(w.check(pickup))
        assertTrue(w.check(pickup))
        assertEquals(listOf("test"), seen.groups)
        assertEquals(1, seen.alerts.size)
        val a = seen.alerts.single()!!
        assertEquals(BikeLevel.OUT, a.level)
        assertEquals(BikeEnd.PICKUP, a.end)
        assertEquals("Covell Park", a.station)
    }

    @Test fun `a failed check or a missing station leaves the alert as it was`() = runBlocking {
        val seen = Seen()
        var reply: List<Transitous.BikeStation>? = listOf(station("1", "Covell Park", covell, 1, 4))
        val w = watch(seen, { reply })
        val pickup = BikeShareTripWatch.watchOf(TransitNavState(trip, 0))!!
        w.check(pickup)
        assertEquals(BikeLevel.FEW, w.alert?.level)
        reply = null
        assertTrue(w.check(pickup))
        reply = listOf(station("9", "Elsewhere", end, 0, 0))
        assertTrue(w.check(pickup))
        assertEquals(BikeLevel.FEW, w.alert?.level)
        assertEquals(1, seen.alerts.size)
    }

    @Test fun `a station that fills up again drops its alert`() = runBlocking {
        val seen = Seen()
        var bikes = 0
        val w = watch(seen, { listOf(station("1", "Covell Park", covell, bikes, 4)) })
        val pickup = BikeShareTripWatch.watchOf(TransitNavState(trip, 0))!!
        w.check(pickup)
        bikes = 6
        w.check(pickup)
        assertNull(w.alert)
        assertEquals(2, seen.alerts.size)
    }

    @Test fun `keep drops an alert for good, and running out after a few raises a new one`() = runBlocking {
        val seen = Seen()
        var bikes = 2
        val w = watch(seen, { listOf(station("1", "Covell Park", covell, bikes, 4)) })
        val pickup = BikeShareTripWatch.watchOf(TransitNavState(trip, 0))!!
        w.check(pickup)
        w.dismiss()
        w.check(pickup)
        assertNull(w.alert)
        bikes = 0
        w.check(pickup)
        assertEquals(BikeLevel.OUT, w.alert?.level)
    }

    @Test fun `nothing is asked when asking is off`() = runBlocking {
        val seen = Seen()
        val w = watch(seen, { listOf(station("1", "Covell Park", covell, 0, 4)) }, canAsk = false)
        assertTrue(w.check(BikeShareTripWatch.watchOf(TransitNavState(trip, 0))!!))
        assertEquals(0, seen.asks)
        assertTrue(seen.alerts.isEmpty())
    }

    @Test fun `a return with no dock count is asked once and then left`() = runBlocking {
        val seen = Seen()
        val w = watch(seen, { listOf(station("2", "Downtown", downtown, 3, null)) })
        assertFalse(w.check(BikeShareTripWatch.watchOf(TransitNavState(trip, 1))!!))
        assertEquals(1, seen.asks)
        assertTrue(seen.alerts.isEmpty())
    }

    @Test fun `the test hook stands in for the station's counts`() = runBlocking {
        val seen = Seen()
        val w = watch(seen, { listOf(station("2", "Downtown", downtown, 3, null)) }, fake = 0)
        assertTrue(w.check(BikeShareTripWatch.watchOf(TransitNavState(trip, 1))!!))
        assertEquals(BikeLevel.OUT, w.alert?.level)
        assertEquals(BikeEnd.RETURN, w.alert?.end)
    }

    @Test fun `the other station is the nearest with more than a few, else the nearest with any`() {
        val pickup = BikeShareTripWatch.watchOf(TransitNavState(trip, 0))!!
        val stations = listOf(
            station("1", "Covell Park", covell, 0, 4),
            station("2", "Close", near, 1, 4),
            station("3", "Further", LatLng(38.5600, -121.7480), 7, 4),
            station("4", "Too far", far, 9, 4),
            station("5", "Closed", near, 9, 4, renting = false),
        )
        assertEquals("Further", BikeShareTripWatch.alternative(stations, pickup, "test-bikes/1", covell, null)?.name)
        assertEquals("Close", BikeShareTripWatch.alternative(stations.filter { it.name != "Further" }, pickup, "test-bikes/1", covell, null)?.name)
        assertNull(BikeShareTripWatch.alternative(listOf(stations[0], stations[3], stations[4]), pickup, "test-bikes/1", covell, null))
        val other = Transitous.BikeStation("other/9", "Other system", near.lat, near.lng, null, 9, 9, true, true)
        assertNull(BikeShareTripWatch.alternative(listOf(other), pickup, "test-bikes/1", covell, null))
    }

    @Test fun `a return needs a dock count to be the other station`() {
        val ret = BikeShareTripWatch.watchOf(TransitNavState(trip, 1))!!
        val noCount = station("6", "No count", downtown, 3, null)
        val counted = station("7", "Counted", LatLng(38.5450, -121.7400), 3, 5)
        assertEquals("Counted", BikeShareTripWatch.alternative(listOf(noCount, counted), ret, "test-bikes/2", downtown, covell)?.name)
        assertNull(BikeShareTripWatch.alternative(listOf(noCount), ret, "test-bikes/2", downtown, covell))
    }

    @Test fun `a pickup splice walks to the new station and rides from it`() {
        val st = station("2", "Close", near, 6, 4)
        val walk = route(listOf(start, near), 300.0)
        val bike = route(listOf(near, downtown), 900.0)
        val t = BikeShareTripWatch.splicePickup(trip.copy(arrivalText = "5:00 PM", arrivalEpochSec = 100L), 1, start, st, walk, bike, durText)!!
        assertEquals(near, t.steps[0].walkTo)
        assertEquals(listOf(start, near), t.steps[0].path)
        assertEquals("5 min", t.steps[0].durationText)
        assertEquals("Close", t.steps[1].boardStop?.name)
        assertEquals("Downtown", t.steps[1].alightStop?.name)
        assertEquals("15 min", t.steps[1].durationText)
        assertEquals(walkOut, t.steps[2])
        // Only a walk follows, so the old arrival is no longer known.
        assertNull(t.arrivalText)
    }

    @Test fun `a router that does not answer leaves the leg without a path`() {
        val st = station("2", "Close", near, 6, 4)
        val t = BikeShareTripWatch.splicePickup(trip, 1, start, st, null, null, durText)!!
        assertNull(t.steps[0].path)
        assertNull(t.steps[1].path)
        assertTrue(t.steps[1].durationText!!.endsWith("min"))
    }

    @Test fun `a return splice rides to the new station and walks on from it`() {
        val st = station("7", "Counted", near, 3, 5)
        val t = BikeShareTripWatch.spliceReturn(trip, 1, covell, st, route(listOf(covell, near), 120.0), route(listOf(near, end), 600.0), durText)!!
        assertEquals(3, t.steps.size)
        assertEquals("Counted", t.steps[1].alightStop?.name)
        assertEquals("Covell Park", t.steps[1].boardStop?.name)
        assertEquals(near, t.steps[2].walkFrom)
        assertEquals(end, t.steps[2].walkTo)
        assertEquals("10 min", t.steps[2].durationText)
    }

    @Test fun `a return splice adds a walk where the ride was last, and keeps a train's arrival`() {
        val train = TransitStep(TransitMode.TRAIN, line = TransitLine("Capitol"), boardStop = TransitStopTime("Davis", location = downtown), alightStop = TransitStopTime("Sacramento"))
        val st = station("7", "Counted", near, 3, 5)
        val withTrain = TransitItinerary(arrivalText = "6:00 PM", steps = listOf(walkIn, ride, train))
        val t = BikeShareTripWatch.spliceReturn(withTrain, 1, covell, st, null, null, durText)!!
        assertEquals(4, t.steps.size)
        assertEquals(TransitMode.WALK, t.steps[2].mode)
        assertEquals(downtown, t.steps[2].walkTo)
        assertEquals("6:00 PM", t.arrivalText)
    }

    @Test fun `a re-plan is taken only off the empty station and within reach`() {
        fun plan(name: String, at: LatLng) = TransitItinerary(steps = listOf(walkIn, ride.copy(boardStop = TransitStopTime(name, location = at)), walkOut))
        assertNull(BikeShareTripWatch.acceptReplan(listOf(plan("covell park", covell)), "Covell Park", start, covell))
        assertNull(BikeShareTripWatch.acceptReplan(listOf(plan("Far", LatLng(38.5600, -121.7700))), "Covell Park", start, covell))
        val ok = plan("Close", LatLng(38.5480, -121.7420))
        assertEquals(ok, BikeShareTripWatch.acceptReplan(listOf(plan("Covell Park", covell), ok), "Covell Park", start, covell))
    }

    @Test fun `a pickup station near the rider counts even when far from the empty one`() {
        val pickup = BikeShareTripWatch.watchOf(TransitNavState(trip, 0))!!
        val byRider = station("8", "By the rider", LatLng(38.5460, -121.7410), 6, 4)
        assertEquals("By the rider", BikeShareTripWatch.alternative(listOf(byRider), pickup, "test-bikes/1", covell, start)?.name)
        assertNull(BikeShareTripWatch.alternative(listOf(byRider), pickup, "test-bikes/1", covell, null))
    }

    @Test fun `an empty pickup is planned again first and the offer starts the new trip`() = runBlocking {
        val seen = Seen()
        val again = TransitItinerary(steps = listOf(walkIn.copy(walkTo = near), ride.copy(boardStop = TransitStopTime("Close", location = near)), walkOut))
        val w = watch(seen, { listOf(station("1", "Covell Park", covell, 0, 4), station("2", "Close", near, 6, 4)) }, replan = listOf(again))
        w.check(BikeShareTripWatch.watchOf(TransitNavState(trip, 0))!!)
        val a = w.alert!!
        assertEquals(1, seen.replans)
        assertEquals(0, seen.routes)
        assertEquals("Close", a.altName)
        assertEquals(6, a.altCount)
        assertEquals(again, a.offer?.itinerary)
        assertEquals(0, a.offer?.stepIndex)
        assertTrue(a.offer?.base === trip)
    }

    @Test fun `a re-plan with nothing to take falls back to a splice`() = runBlocking {
        val seen = Seen()
        val w = watch(seen, { listOf(station("1", "Covell Park", covell, 0, 4), station("2", "Close", near, 6, 4)) })
        w.check(BikeShareTripWatch.watchOf(TransitNavState(trip, 0))!!)
        val a = w.alert!!
        assertEquals(1, seen.replans)
        assertEquals(2, seen.routes)
        assertEquals("Close", a.offer?.itinerary?.steps?.get(1)?.boardStop?.name)
        assertEquals(0, a.offer?.stepIndex)
    }

    @Test fun `a few bikes left are spliced without a re-plan`() = runBlocking {
        val seen = Seen()
        val w = watch(seen, { listOf(station("1", "Covell Park", covell, 2, 4), station("2", "Close", near, 6, 4)) })
        w.check(BikeShareTripWatch.watchOf(TransitNavState(trip, 0))!!)
        assertEquals(BikeLevel.FEW, w.alert?.level)
        assertEquals(0, seen.replans)
        assertTrue(w.alert?.offer != null)
    }

    @Test fun `a full return offers a splice from the ride`() = runBlocking {
        val seen = Seen()
        val w = watch(seen, { listOf(station("2", "Downtown", downtown, 9, 0), station("7", "Counted", LatLng(38.5450, -121.7400), 3, 5)) }, here = covell)
        w.check(BikeShareTripWatch.watchOf(TransitNavState(trip, 1))!!)
        val a = w.alert!!
        assertEquals(BikeEnd.RETURN, a.end)
        assertEquals("Counted", a.altName)
        assertEquals(1, a.offer?.stepIndex)
        assertEquals("Counted", a.offer?.itinerary?.steps?.get(1)?.alightStop?.name)
        assertEquals(0, seen.replans)
    }

    @Test fun `with no other station the alert offers nothing`() = runBlocking {
        val seen = Seen()
        val w = watch(seen, { listOf(station("1", "Covell Park", covell, 0, 4)) })
        w.check(BikeShareTripWatch.watchOf(TransitNavState(trip, 0))!!)
        assertEquals(BikeLevel.OUT, w.alert?.level)
        assertNull(w.alert?.altName)
        assertNull(w.alert?.offer)
    }

    @Test fun `a phone far from the trip is not taken for the rider`() = runBlocking {
        val seen = Seen()
        var box: DoubleArray? = null
        val w = BikeShareTripWatch(
            scope = CoroutineScope(EmptyCoroutineContext), nav = emptyFlow(), http = OkHttpClient(),
            hereOf = { LatLng(38.5816, -121.4944) }, canAsk = { true }, onChange = { seen.alerts += it },
            routeOf = { a, b, _ -> route(listOf(a, b), 600.0) }, onCounts = {},
            stationsOf = { b, _ -> box = b; listOf(station("1", "Covell Park", covell, 0, 4), station("2", "Close", near, 6, 4)) },
            replanOf = { _, _, _ -> seen.replans++; emptyList() }, durText = durText, fake = { null },
        )
        w.check(BikeShareTripWatch.watchOf(TransitNavState(trip, 0))!!)
        // The box is the station's alone, nothing is planned from the phone, and the walk starts where it did.
        assertTrue(box!![3] - box!![1] < 0.05)
        assertEquals(0, seen.replans)
        assertEquals(start, w.alert?.offer?.itinerary?.steps?.get(0)?.walkFrom)
        assertTrue(w.alert!!.altDistanceM!! < 200.0)
    }

    @Test fun `a check cancelled on the way shows nothing`() = runBlocking {
        val seen = Seen()
        val w = watch(
            seen, { listOf(station("1", "Covell Park", covell, 0, 4), station("2", "Close", near, 6, 4)) },
            onReplan = { currentCoroutineContext().cancel() },
        )
        launch { w.check(BikeShareTripWatch.watchOf(TransitNavState(trip, 0))!!) }.join()
        assertEquals(1, seen.replans)
        assertTrue(seen.alerts.isEmpty())
        assertNull(w.alert)
    }

    @Test fun `an alert that holds takes the new counts and keeps its offer`() = runBlocking {
        val seen = Seen()
        var bikes = 2
        var alt = 6
        val w = watch(seen, { listOf(station("1", "Covell Park", covell, bikes, 4), station("2", "Close", near, alt, 4)) })
        val pickup = BikeShareTripWatch.watchOf(TransitNavState(trip, 0))!!
        w.check(pickup)
        val first = w.alert!!
        assertEquals("test-bikes/2", first.altKey)
        bikes = 1
        alt = 5
        w.check(pickup)
        assertEquals(1, w.alert?.count)
        assertEquals(5, w.alert?.altCount)
        assertTrue(w.alert?.offer === first.offer)
        assertEquals(2, seen.routes)
        assertEquals(5, seen.counts[BikeShareCounts.keyOf("test-bikes", "Close")]?.bikes)
        assertEquals(1, seen.counts[BikeShareCounts.keyOf("test-bikes", "Covell Park")]?.bikes)
    }

    @Test fun `an alert whose other station runs out offers another`() = runBlocking {
        val seen = Seen()
        var reply = listOf(station("1", "Covell Park", covell, 2, 4), station("2", "Close", near, 6, 4))
        val w = watch(seen, { reply })
        val pickup = BikeShareTripWatch.watchOf(TransitNavState(trip, 0))!!
        w.check(pickup)
        assertEquals("Close", w.alert?.altName)
        reply = listOf(station("1", "Covell Park", covell, 2, 4), station("2", "Close", near, 0, 4), station("3", "Further", LatLng(38.5600, -121.7480), 7, 4))
        w.check(pickup)
        assertEquals("Further", w.alert?.altName)
        assertEquals("Further", w.alert?.offer?.itinerary?.steps?.get(1)?.boardStop?.name)
        assertEquals(4, seen.routes)
    }

    @Test fun `the watch's counts go beside the trip's others`() {
        val s = BikeShareStatus(bikes = 4, docks = 3, renting = true, returning = true, atMs = 0L)
        BikeShareCounts.replace(mapOf(BikeShareCounts.keyOf("test-bikes", "Downtown") to s))
        BikeShareCounts.put(mapOf(BikeShareCounts.keyOf("test-bikes", "Covell Park") to s.copy(bikes = 0)))
        assertEquals(s, BikeShareCounts.of(rental, TransitStopTime("Downtown")))
        assertEquals(0, BikeShareCounts.of(rental, TransitStopTime("Covell Park"))?.bikes)
        BikeShareCounts.replace(emptyMap())
    }
}
