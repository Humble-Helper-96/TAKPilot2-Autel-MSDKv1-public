package com.autel.sdksample.tak

import org.junit.Assert.assertEquals
import org.junit.Test

/** Conversions and angle normalisation. Small, but these feed the HUD and the
 *  marker math — a wrong sign or factor here is a wrong picture for the whole team. */
class UnitsAndAnglesTest {

    @Test
    fun metersToFeetUsesTheSurveyFactor() {
        assertEquals(328.084, Units.metersToFeet(100.0), 0.001)
        assertEquals(0.0, Units.metersToFeet(0.0), 0.0)
    }

    @Test
    fun norm360WrapsBothDirections() {
        assertEquals(270.0, CameraSlantPoint.norm360(-90.0), 1e-9)
        assertEquals(90.0, CameraSlantPoint.norm360(450.0), 1e-9)
        assertEquals(0.0, CameraSlantPoint.norm360(360.0), 1e-9)
        assertEquals(359.5, CameraSlantPoint.norm360(-0.5), 1e-9)
    }
}
