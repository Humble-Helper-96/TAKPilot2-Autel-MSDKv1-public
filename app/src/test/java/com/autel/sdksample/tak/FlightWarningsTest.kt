package com.autel.sdksample.tak

import com.autel.common.flycontroller.ARMWarning
import com.autel.common.flycontroller.FlyControllerStatus
import com.autel.common.flycontroller.FlyLimitAreaWarning
import com.autel.common.flycontroller.FlyMode
import com.autel.common.flycontroller.MainFlyState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for the warnings display policy. Pure logic: a fake FlyControllerStatus
 * goes in through onStatus, and displayAt(now) gives the banner decision with a stepped
 * clock. No device, no SDK connection.
 */
class FlightWarningsTest {

    private val t0 = 1_000_000L

    /** A healthy status. Each test changes only the fields it examines. */
    private fun status(
        compassValid: Boolean = true,
        gpsValid: Boolean = true,
        mainFlyState: MainFlyState = MainFlyState.GPS,
        flyMode: FlyMode = FlyMode.GPS_FLIGHT,
        overheated: Boolean = false,
        windHigh: Boolean = false,
        reachMaxHeight: Boolean = false,
        reachMaxRange: Boolean = false,
        nearRangeLimit: Boolean = false,
        homePointValid: Boolean = true,
        limitArea: FlyLimitAreaWarning = FlyLimitAreaWarning.NORMAL,
    ): FlyControllerStatus = object : FlyControllerStatus {
        override fun getMainFlyState() = mainFlyState
        override fun getArmErrorCode() = ARMWarning.NORMAL
        override fun getFlyMode() = flyMode
        override fun isReachMaxHeight() = reachMaxHeight
        override fun isReachMaxRange() = reachMaxRange
        override fun isGpsValid() = gpsValid
        override fun isHomePointValid() = homePointValid
        override fun isCompassValid() = compassValid
        override fun isFlightControllerLostRemoteControllerSignal() = false
        override fun isFlightControllerOverHeated() = overheated
        override fun isOneClickTakeOffValid() = true
        override fun isTakeOffValid() = true
        override fun isWarmingUp() = false
        override fun isHomePointLocationAccurate() = true
        override fun isGoHomePending() = false
        override fun getFlyLimitAreaWarning() = limitArea
        override fun isStickLimited() = false
        override fun isNearRangeLimit() = nearRangeLimit
        override fun isWindTooHigh() = windHigh
        override fun isSupportRtk() = false
    }

    @Before
    fun resetState() {
        FlightWarnings.reset()
        FlightWarnings.avoidanceNotApplied = false
        FlightWarnings.gimbalErratic = false
        FlightWarnings.debugLogOn = false
        FlightLimitsController.aircraftWarningPct = null
        FlightLimitsController.aircraftCriticalPct = null
    }

    @Test
    fun `the debug log shows in the banner with no aircraft and behind every aircraft warning`() {
        // No aircraft status at all: the app-side warning still shows (no aircraft connected).
        FlightWarnings.debugLogOn = true
        val alone = FlightWarnings.displayAt(t0)!!
        assertEquals("DEBUG LOG ON", alone.text)
        assertFalse(alone.red)
        // With an aircraft warning it is the one behind, listed last.
        FlightLimitsController.aircraftWarningPct = 30f
        FlightWarnings.onStatus(status(), batteryPct = 25, airborne = true)
        val both = FlightWarnings.displayAt(t0 + 1)!!
        assertEquals("BATTERY LOW  +1", both.text)
        assertEquals(listOf("BATTERY LOW", "DEBUG LOG ON"), both.all)
        // Off: gone at once.
        FlightWarnings.debugLogOn = false
        assertEquals(listOf("BATTERY LOW"), FlightWarnings.displayAt(t0 + 2)!!.all)
    }

    @Test
    fun `the debug log line is the one line the pilot cannot close`() {
        assertEquals(listOf("BATTERY LOW"), FlightWarnings.dismissable(listOf("BATTERY LOW", "DEBUG LOG ON")))
        assertEquals(emptyList<String>(), FlightWarnings.dismissable(listOf("DEBUG LOG ON")))
    }

    @Test
    fun healthyStatusShowsNothing() {
        FlightWarnings.onStatus(status(), batteryPct = 80, airborne = true)
        assertNull(FlightWarnings.displayAt(t0))
    }

    @Test
    fun compassInterferenceIsRedAndImmediate() {
        FlightWarnings.onStatus(status(compassValid = false), batteryPct = 80, airborne = false)
        val d = FlightWarnings.displayAt(t0)!!
        assertEquals("COMPASS INTERFERENCE", d.text)
        assertTrue(d.red)
    }

    @Test
    fun gpsLossIsSilentOnTheGround() {
        FlightWarnings.onStatus(status(gpsValid = false), batteryPct = 80, airborne = false)
        assertNull(FlightWarnings.displayAt(t0))
    }

    @Test
    fun gpsLossShowsWhenAirborne() {
        FlightWarnings.onStatus(status(gpsValid = false), batteryPct = 80, airborne = true)
        assertEquals("GPS LOST — AIRCRAFT DRIFTS", FlightWarnings.displayAt(t0)!!.text)
    }

    @Test
    fun attitudeModeCountsAsGpsLoss() {
        FlightWarnings.onStatus(
            status(mainFlyState = MainFlyState.ATTITUDE), batteryPct = 80, airborne = true)
        assertEquals("GPS LOST — AIRCRAFT DRIFTS", FlightWarnings.displayAt(t0)!!.text)
    }

    @Test
    fun noFlyZoneStaysOffTheBanner() {
        // Log-only by design decision (FAA exception). Active in the set, never displayed,
        // never counted in +N.
        FlightWarnings.onStatus(
            status(compassValid = false, limitArea = FlyLimitAreaWarning.AIRPORT_NO_FLY_ZONES),
            batteryPct = 80, airborne = true)
        val d = FlightWarnings.displayAt(t0)!!
        assertEquals("COMPASS INTERFERENCE", d.text)   // no "+1" from the no-fly zone
    }

    @Test
    fun worseWarningPreemptsImmediately() {
        FlightWarnings.onStatus(status(windHigh = true), batteryPct = 80, airborne = true)
        assertEquals("WIND TOO HIGH", FlightWarnings.displayAt(t0)!!.text)
        FlightWarnings.onStatus(
            status(windHigh = true, compassValid = false), batteryPct = 80, airborne = true)
        // 100 ms later, inside the hold window — the worse warning must still take the banner.
        val d = FlightWarnings.displayAt(t0 + 100)!!
        assertTrue(d.text.startsWith("COMPASS INTERFERENCE"))
        assertTrue(d.red)
    }

    @Test
    fun stackedWarningsShowACount() {
        FlightWarnings.onStatus(
            status(compassValid = false, windHigh = true), batteryPct = 80, airborne = true)
        assertEquals("COMPASS INTERFERENCE  +1", FlightWarnings.displayAt(t0)!!.text)
    }

    @Test
    fun clearedWarningHoldsThenHides() {
        FlightWarnings.onStatus(status(compassValid = false), batteryPct = 80, airborne = true)
        assertEquals("COMPASS INTERFERENCE", FlightWarnings.displayAt(t0)!!.text)
        FlightWarnings.onStatus(status(), batteryPct = 80, airborne = true)
        // Inside the 4 s hold: still visible, so a flicker cannot strobe the banner.
        assertEquals("COMPASS INTERFERENCE", FlightWarnings.displayAt(t0 + 2_000)!!.text)
        // After the hold: gone.
        assertNull(FlightWarnings.displayAt(t0 + 4_100))
    }

    @Test
    fun batteryUsesAircraftThresholdsAndIgnoresZero() {
        FlightLimitsController.aircraftCriticalPct = 10f
        FlightLimitsController.aircraftWarningPct = 25f
        // batteryPct 0 means "no battery frame yet", never a critical alarm.
        FlightWarnings.onStatus(status(), batteryPct = 0, airborne = true)
        assertNull(FlightWarnings.displayAt(t0))
        FlightWarnings.onStatus(status(), batteryPct = 20, airborne = true)
        assertEquals("BATTERY LOW", FlightWarnings.displayAt(t0 + 5_000)!!.text)
        FlightWarnings.onStatus(status(), batteryPct = 9, airborne = true)
        val d = FlightWarnings.displayAt(t0 + 10_000)!!
        assertEquals("BATTERY CRITICAL", d.text)
        assertTrue(d.red)
    }

    @Test
    fun autonomousReturnNamesItsReason() {
        FlightWarnings.onStatus(
            status(flyMode = FlyMode.RC_LOST_GO_HOME), batteryPct = 80, airborne = true)
        val d = FlightWarnings.displayAt(t0)!!
        assertEquals("RETURNING HOME — SIGNAL LOST", d.text)
        assertTrue(!d.red)
    }

    @Test
    fun avoidanceNotAppliedShowsAmberBanner() {
        FlightWarnings.avoidanceNotApplied = true
        FlightWarnings.onStatus(status(), batteryPct = 80, airborne = false)
        val d = FlightWarnings.displayAt(t0)!!
        assertEquals("AVOIDANCE SETTING NOT APPLIED", d.text)
        assertTrue(!d.red)
    }

    @Test
    fun avoidanceNotAppliedClearsWhenFlagClears() {
        FlightWarnings.avoidanceNotApplied = true
        FlightWarnings.onStatus(status(), batteryPct = 80, airborne = false)
        assertEquals("AVOIDANCE SETTING NOT APPLIED", FlightWarnings.displayAt(t0)!!.text)
        FlightWarnings.avoidanceNotApplied = false
        FlightWarnings.onStatus(status(), batteryPct = 80, airborne = false)
        // The banner rides out its hold, then hides.
        assertNull(FlightWarnings.displayAt(t0 + 4_100))
    }

    /**
     * Erratic gimbal pitch is LOGGED, never shown. The pilot is already watching the video it
     * is happening in (confirmed in flight), so the banner spent an interrupt on
     * something visible. Same treatment as the no-fly zone: active, logged, off screen.
     */
    @Test
    fun gimbalErraticIsLoggedButNeverShown() {
        FlightWarnings.gimbalErratic = true
        FlightWarnings.onStatus(status(), batteryPct = 80, airborne = true)
        assertNull(FlightWarnings.displayAt(t0))
    }

    @Test
    fun gimbalErraticIsNotEvenCountedBehindARedWarning() {
        FlightWarnings.gimbalErratic = true
        FlightWarnings.onStatus(status(compassValid = false), batteryPct = 80, airborne = true)
        val d = FlightWarnings.displayAt(t0)!!
        assertEquals("COMPASS INTERFERENCE", d.text)   // no "+1" from a log-only warning
        assertTrue(d.red)
    }

    @Test
    fun redWarningPreemptsExternalFlags() {
        FlightWarnings.avoidanceNotApplied = true
        FlightWarnings.onStatus(status(compassValid = false), batteryPct = 80, airborne = true)
        val d = FlightWarnings.displayAt(t0)!!
        assertEquals("COMPASS INTERFERENCE  +1", d.text)
        assertTrue(d.red)
    }

    @Test
    fun rthOutranksAvoidanceNotApplied() {
        // An autonomous return describes what the aircraft DOES right now; it must win the
        // banner over a setting mismatch. This pins the enum declaration order.
        FlightWarnings.avoidanceNotApplied = true
        FlightWarnings.onStatus(
            status(flyMode = FlyMode.LOW_BATTERY_GO_HOME), batteryPct = 80, airborne = true)
        assertEquals("RETURNING HOME — LOW BATTERY  +1", FlightWarnings.displayAt(t0)!!.text)
    }

    // ---- The open banner ----

    @Test
    fun openBannerListsEveryWarningWorstFirst() {
        FlightWarnings.avoidanceNotApplied = true
        FlightWarnings.onStatus(status(compassValid = false, windHigh = true), batteryPct = 80, airborne = true)
        val d = FlightWarnings.displayAt(t0)!!
        assertEquals("COMPASS INTERFERENCE  +2", d.text)
        assertEquals(
            listOf("COMPASS INTERFERENCE", "AVOIDANCE SETTING NOT APPLIED", "WIND TOO HIGH"),
            d.all,
        )
    }

    @Test
    fun openBannerNeverGoesBlankWhileAWarningRidesOutItsHold() {
        FlightWarnings.onStatus(status(windHigh = true), batteryPct = 80, airborne = true)
        assertEquals("WIND TOO HIGH", FlightWarnings.displayAt(t0)!!.text)
        FlightWarnings.onStatus(status(), batteryPct = 80, airborne = true)
        // Inside the 4 s hold: the live set is empty, and the list holds the one shown line.
        assertEquals(listOf("WIND TOO HIGH"), FlightWarnings.displayAt(t0 + 1_000)!!.all)
    }

    @Test
    fun logOnlyWarningsStayOutOfTheOpenBanner() {
        FlightWarnings.gimbalErratic = true
        FlightWarnings.onStatus(status(compassValid = false), batteryPct = 80, airborne = true)
        assertEquals(listOf("COMPASS INTERFERENCE"), FlightWarnings.displayAt(t0)!!.all)
    }

    // ---- returningHome: every go-home FlyMode, swept from the aar ----

    @Test
    fun `every go-home mode counts as returning home`() {
        // ⚠ THE THREE THAT WERE COVERED ARE THE THREE WITH A REASON. The ordinary ones were
        // silent, and NORMAL_GO_HOME — what an RC button press or an SDK goHome() produces — was
        // among them. On a 12.5-hour mission an aircraft returned home and the pilot could not
        // see why.
        for (m in listOf(
            FlyMode.NORMAL_GO_HOME,
            FlyMode.LOW_BATTERY_GO_HOME,
            FlyMode.EXCEED_RANGE_GO_HOME,
            FlyMode.RC_LOST_GO_HOME,
            FlyMode.GO_HOME_HOVER,
            FlyMode.MISSION_GO_HOME,
            FlyMode.FlightModeShotVideoGohome,
        )) {
            assertTrue("$m must count as returning home", FlightWarnings.returningHome(m))
        }
    }

    @Test
    fun `ordinary flight is not returning home`() {
        // The gate also decides whether the RTH menu offers Cancel Return. Offering to cancel
        // something that is not running teaches a pilot the menu does not mean what it says.
        for (m in listOf(
            FlyMode.GPS_FLIGHT, FlyMode.ATTI_FLIGHT, FlyMode.TAKEOFF, FlyMode.LANDING,
            FlyMode.DISARM, FlyMode.MOTOR_SPINNING, FlyMode.WAYPOINT_MODE, FlyMode.UNKNOWN,
        )) {
            assertFalse("$m must not count as returning home", FlightWarnings.returningHome(m))
        }
        assertFalse(FlightWarnings.returningHome(null))
    }
}
