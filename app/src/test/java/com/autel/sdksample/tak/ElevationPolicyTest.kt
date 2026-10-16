package com.autel.sdksample.tak

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ElevationPolicyTest {

    // ---- where the aircraft's MSL comes from ----

    @Test
    fun `the takeoff reference wins when it has latched`() {
        val (msl, src) = estimateAircraftMsl(60.0, 75.0, 45.7)
        assertEquals(105.7, msl!!, 1e-9); assertEquals(MslSource.TAKEOFF_REFERENCE, src)
    }

    @Test
    fun `without it the ground under the aircraft stands in`() {
        // Ground test: home never set, DTED covers the area. Before this the overlay went
        // to the flat plane even with DTED loaded.
        val (msl, src) = estimateAircraftMsl(null, 75.0, 45.7)
        assertEquals(120.7, msl!!, 1e-9); assertEquals(MslSource.TERRAIN_UNDER_AIRCRAFT, src)
    }

    @Test
    fun `with no terrain at all there is no MSL`() {
        val (msl, src) = estimateAircraftMsl(null, null, 45.7)
        assertNull(msl); assertEquals(MslSource.NONE, src)
    }

    // ---- the pin at sea level ----

    @Test
    fun `a pin with a real elevation is differenced against the aircraft`() {
        assertEquals(-45.7, pinHeightAboveAircraft(60.0, 61.0, 105.7, 45.7), 1e-9)
    }

    @Test
    fun `a pin with no elevation sits on the terrain under it, not at sea level`() {
        // A measured case: markers on ground 15 m above the takeoff point.
        // Old behaviour: 0.0 - 105.7 = -105.7, 60 m of fictitious depth.
        assertEquals(-30.7, pinHeightAboveAircraft(Double.NaN, 75.0, 105.7, 45.7), 1e-9)
    }

    @Test
    fun `and only with nothing under it does the flat plane return`() {
        assertEquals(-45.7, pinHeightAboveAircraft(Double.NaN, null, 105.7, 45.7), 1e-9)
        assertEquals(-45.7, pinHeightAboveAircraft(Double.NaN, null, null, 45.7), 1e-9)
        // No aircraft MSL: a pin elevation cannot be differenced either.
        assertEquals(-45.7, pinHeightAboveAircraft(60.0, 61.0, null, 45.7), 1e-9)
    }

    // ---- the geoid ----

    @Test
    fun `the separation is the receiver's own two altitudes differenced`() {
        // A measured example: about +12 m (12.2).
        assertEquals(12.2, geoidSeparation(206.2, 194.0)!!, 1e-9)
    }

    @Test
    fun `no fix and nonsense give no separation`() {
        assertNull(geoidSeparation(0.0, 0.0))
        // The SDK's first-fix placeholder: Altitude 0 with a valid sea-level height.
        assertNull(geoidSeparation(0.0, 40.0))
        assertNull(geoidSeparation(52.5, 0.0))
        assertNull(geoidSeparation(Double.NaN, 194.0))
        assertNull(geoidSeparation(500.0, 194.0))   // 306 m: not a geoid on this planet
    }

    @Test
    fun `the separation is seeded by the first sample and then moves slowly`() {
        // Measured in flight: raw samples walked 10 to 16 m around a true 12.5.
        assertEquals(12.0, smoothGeoid(null, 12.0), 1e-9)
        var n: Double? = 12.0
        for (s in listOf(16.0, 10.0, 16.0, 10.0)) n = smoothGeoid(n, s)
        // Four extreme samples move it by less than half a metre.
        assertEquals(12.0, n!!, 0.5)
    }

    @Test
    fun `a reported hae comes into the DTED frame when N is known and is left alone when not`() {
        assertEquals(194.0, reportedHaeToMsl(206.2, 12.2), 1e-9)
        assertEquals(206.2, reportedHaeToMsl(206.2, null), 1e-9)
    }
}
