package com.autel.sdksample.tak

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the erratic-pitch detector. Synthetic 10 Hz streams with a stepped clock —
 * the same feed rate as the bridge's gimbal listener. The "incident" stream reproduces
 * a logged runaway's signature: full-range triangle wave at ~8°/s. The "normal" streams
 * reproduce what healthy logs from the same flight show: fast sweeps that end in holds.
 */
class GimbalPitchMonitorTest {

    private val stepMs = 100L   // 10 Hz

    /** Feed a stream defined by (durationMs, degreesPerSecond) segments. Returns the
     *  monitor's verdict after the last sample. */
    private fun run(
        monitor: GimbalPitchMonitor,
        startDeg: Double,
        segments: List<Pair<Long, Double>>,
        startMs: Long = 0L,
    ): Boolean {
        var t = startMs
        var pitch = startDeg
        var verdict = false
        for ((durationMs, ratePerS) in segments) {
            var elapsed = 0L
            while (elapsed < durationMs) {
                t += stepMs
                elapsed += stepMs
                pitch += ratePerS * stepMs / 1000.0
                verdict = monitor.onSample(pitch, t)
            }
        }
        return verdict
    }

    /** The incident, replayed from 37 s of logged samples (time offset
     *  seconds, pitch degrees). Legs of 20–55° with a reversal each 3–6 s. */
    private val incidentTrace = listOf(
        0.0 to 13.0, 3.0 to -9.9, 6.0 to 28.7, 9.0 to 10.4, 13.0 to -12.9,
        16.0 to -8.2, 19.0 to -23.7, 22.0 to 28.7, 25.0 to 28.7, 28.0 to 18.2,
        31.0 to -4.2, 34.0 to -26.1, 37.0 to 28.8,
    )

    /** Feed a (timeS, pitch) trace with linear interpolation at 10 Hz. */
    private fun replay(monitor: GimbalPitchMonitor, trace: List<Pair<Double, Double>>): Boolean {
        var verdict = false
        for (i in 1 until trace.size) {
            val (t0s, p0) = trace[i - 1]
            val (t1s, p1) = trace[i]
            var t = (t0s * 1000).toLong()
            val end = (t1s * 1000).toLong()
            while (t < end) {
                t += stepMs
                val f = (t - t0s * 1000) / (t1s * 1000 - t0s * 1000)
                verdict = monitor.onSample(p0 + (p1 - p0) * f, t)
            }
        }
        return verdict
    }

    @Test
    fun incidentReplayTrips() {
        // 37 s of the real logged stream must trip — the pilot took 39 s. Replaying the
        // full logged series offline puts the trip at 30 s after onset.
        assertTrue(replay(GimbalPitchMonitor(), incidentTrace))
    }

    /**
     * Six minutes of REAL deliberate searching from the same flight (sampled every ~2 s)
     * must stay silent. This is the negative case the thresholds were
     * tuned against; without it, "never false-fires" is only an assertion.
     */
    @Test
    fun realSearchingFlightNeverTrips() {
        val m = GimbalPitchMonitor()
        // Legs and holds as flown: sweep, hold, sweep deeper, hold, ease back, hold.
        val trace = listOf(
            0.0 to -33.1, 12.0 to -14.8, 20.0 to -12.4, 25.0 to -19.1, 62.0 to -19.1,
            66.0 to -19.4, 74.0 to -37.5, 80.0 to -34.6, 86.0 to -34.6, 90.0 to -66.9,
            120.0 to -66.9, 128.0 to -65.8, 140.0 to -44.2, 150.0 to -48.9, 175.0 to -48.9,
            185.0 to -52.9, 195.0 to -65.4, 240.0 to -65.4, 250.0 to -57.1, 262.0 to -46.9,
            285.0 to -44.5, 300.0 to -37.0, 340.0 to -37.0, 350.0 to -31.9, 360.0 to -19.5,
            375.0 to -17.9, 385.0 to -16.2,
        )
        assertFalse(replay(m, trace))
    }

    @Test
    fun singleDeliberateSweepDoesNotTrip() {
        val m = GimbalPitchMonitor()
        // The rate-alone trap: 10°/s is FASTER than the incident, but one sweep and a
        // hold has one reversal at most.
        val verdict = run(m, 0.0, listOf(
            9_000L to -10.0,   // 0 -> -90, fast deliberate sweep
            30_000L to 0.0,    // hold
        ))
        assertFalse(verdict)
    }

    @Test
    fun threeReversalScanDoesNotTrip() {
        val m = GimbalPitchMonitor()
        // Aggressive scan: down, up, down, then hold. Three reversals stays under four.
        val verdict = run(m, 0.0, listOf(
            6_000L to -10.0,   // 0 -> -60
            6_000L to 10.0,    // -60 -> 0
            6_000L to -10.0,   // 0 -> -60
            20_000L to 0.0,    // hold
        ))
        assertFalse(verdict)
    }

    @Test
    fun hoverJitterDoesNotTrip() {
        val m = GimbalPitchMonitor()
        // ±1° stabilisation noise for 60 s: sub-minSwingDeg legs never qualify.
        var verdict = false
        var t = 0L
        for (i in 0 until 600) {
            t += stepMs
            val pitch = -30.0 + if (i % 2 == 0) 1.0 else -1.0
            verdict = m.onSample(pitch, t)
        }
        assertFalse(verdict)
    }

    @Test
    fun clearsAfterMotionStops() {
        val m = GimbalPitchMonitor()
        assertTrue(replay(m, incidentTrace))
        // Motion stops. After the window drains past the relatch hold, the verdict clears.
        val verdict = run(m, 28.8, listOf(70_000L to 0.0), startMs = 37_000L)
        assertFalse(verdict)
    }

    @Test
    fun resetDropsHistory() {
        val m = GimbalPitchMonitor()
        assertTrue(replay(m, incidentTrace))
        m.reset()
        assertFalse(m.erratic)
        // One quiet sample after reset stays quiet — no stale window.
        assertFalse(m.onSample(-30.0, 100_000L))
    }
}
