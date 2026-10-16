package com.autel.sdksample.tak

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the flight-record formats: the CSV row cycle and the GPX document.
 * These formats are the crash-recovery contract — the orphan sweep parses the CSV that a
 * dead process left behind, so a row must survive the trip out and back.
 */
class FlightPathLoggerFormatTest {

    private fun point(
        timeMs: Long = 1_754_580_000_000L,   // whole second, so the trip out and back is exact
        lat: Double = 45.1234567, lon: Double = -100.1234567,
        msl: Double = 123.4, rel: Double = 45.6,
        speed: Double = 5.0, heading: Double = 270.0,
        battery: Int = 88, sats: Int = 17,
    ) = FlightPathLogger.Point(timeMs, lat, lon, msl, rel, speed, heading, battery, sats)

    @Test
    fun csvRowSurvivesTheRoundTrip() {
        val p = point()
        val row = FlightPathLogger.csvRow(p).trimEnd('\n')
        val back = FlightPathLogger.parseCsvRow(row)!!
        assertEquals(p.timeMs, back.timeMs)
        assertEquals(p.lat, back.lat, 1e-7)
        assertEquals(p.lon, back.lon, 1e-7)
        assertEquals(p.mslAltM, back.mslAltM, 0.05)
        assertEquals(p.relAltM, back.relAltM, 0.05)
        assertEquals(p.batteryPct, back.batteryPct)
        assertEquals(p.satellites, back.satellites)
    }

    @Test
    fun missingMslBecomesEmptyFieldAndComesBackAsNaN() {
        val row = FlightPathLogger.csvRow(point(msl = Double.NaN)).trimEnd('\n')
        assertTrue(",," in row)   // the empty field is present, the column count holds
        val back = FlightPathLogger.parseCsvRow(row)!!
        assertTrue(back.mslAltM.isNaN())
        assertEquals(45.6, back.relAltM, 0.05)
    }

    @Test
    fun aRowCutByACrashIsRejectedNotMisread() {
        val full = FlightPathLogger.csvRow(point()).trimEnd('\n')
        val cut = full.substring(0, full.length / 2)
        assertNull(FlightPathLogger.parseCsvRow(cut))
    }

    @Test
    fun theHeaderLineIsNotAPoint() {
        assertNull(FlightPathLogger.parseCsvRow(
            "utc_time,lat,lon,alt_msl_m,alt_above_takeoff_m,speed_ms,heading_deg,battery_pct,satellites"))
    }

    @Test
    fun gpxHoldsOneTrkptPerPointWithIsoTimes() {
        val gpx = FlightPathLogger.gpxDocument(listOf(point(), point(timeMs = 1_754_580_001_000L)))
        assertEquals(2, Regex("<trkpt ").findAll(gpx).count())
        assertEquals(2, Regex("<time>").findAll(gpx).count())
        assertTrue("<ele>123.4</ele>" in gpx)
        assertTrue(Regex("<time>\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z</time>").containsMatchIn(gpx))
        assertTrue(gpx.trimEnd().endsWith("</gpx>"))
    }

    @Test
    fun gpxEleFallsBackToAboveTakeoffWhenMslIsMissing() {
        val gpx = FlightPathLogger.gpxDocument(listOf(point(msl = Double.NaN)))
        assertTrue("<ele>45.6</ele>" in gpx)
    }

    // ---- Events file ----

    @Test
    fun eventLineIsIsoUtcSpaceTextNewline() {
        // 1_754_580_000_000 is a whole second: 2025-08-07T15:20:00Z.
        assertEquals("2025-08-07T15:20:00Z emergency-broadcast started\n",
            FlightPathLogger.eventLine(1_754_580_000_000L, "emergency-broadcast started"))
    }

    @Test
    fun eventLineHoldsExactlyOneNewlineAtTheEnd() {
        // The file is read one event per line; a break inside the text would split one event.
        val line = FlightPathLogger.eventLine(1_754_580_000_000L, "emergency-broadcast cancelled")
        assertEquals(1, line.count { it == '\n' })
        assertTrue(line.endsWith("\n"))
    }
}
