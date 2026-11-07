package app.vela.ui.map

import app.vela.core.data.transit.Transitous
import app.vela.core.model.LatLng
import app.vela.core.model.TransitItinerary
import app.vela.core.model.TransitLine
import app.vela.core.model.TransitMode
import app.vela.core.model.TransitRental
import app.vela.core.model.TransitStep
import app.vela.core.model.TransitStopTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.emptyFlow
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
    }

    private fun watch(seen: Seen, stations: () -> List<Transitous.BikeStation>?, canAsk: Boolean = true, fake: Int? = null) =
        BikeShareTripWatch(
            scope = CoroutineScope(EmptyCoroutineContext),
            nav = emptyFlow(),
            http = OkHttpClient(),
            hereOf = { start },
            canAsk = { canAsk },
            onChange = { seen.alerts += it },
            onCounts = {},
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
}
