package com.taklite.client.tak

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two forms of the pilot PLI: with a fix, and with no fix.
 *
 * A receiver must be able to tell them apart from the message alone. These tests pin the three
 * fields that do that — `how`, `ce`, and the presence of `<precisionlocation>` — and the fields
 * that must be in BOTH forms, because they are what keep the client in the contact list.
 */
class PilotPliNoFixTest {

    private fun noFix() = CotBuilder.buildPLINoFix(
        "PILOT-1", "UAS-BRAVO-Pilot", "Cyan", "Team Member", 77,
        "TAKPilot2", "SmartController", "Android", "1.7.5", null)

    private fun withFix(ce: Double) = CotBuilder.buildPLI(
        "PILOT-1", "UAS-BRAVO-Pilot", "Cyan", "Team Member", true,
        45.1, -100.9, 35.0, ce, 180.0, 0.0, 77,
        "TAKPilot2", "SmartController", "Android", "1.7.5", null)

    @Test
    fun noFixFormSaysThePointIsNotAFix() {
        val xml = noFix()
        assertTrue("how must say the point came from nowhere", "how=\"h-g-i-g-o\"" in xml)
        assertTrue("lat=\"0.0\" lon=\"0.0\"" in xml)
        assertTrue("hae=\"9999999\"" in xml)
        assertTrue("ce=\"9999999\"" in xml)
        assertTrue("le=\"9999999\"" in xml)
        // No course, no speed, no GPS source: there is nothing to report.
        assertFalse("<track" in xml)
        assertFalse("<precisionlocation" in xml)
    }

    @Test
    fun noFixFormStillMakesTheClientReachable() {
        val xml = noFix()
        assertTrue("callsign=\"UAS-BRAVO-Pilot\"" in xml)
        assertTrue("endpoint=\"*:-1:stcp\"" in xml)
        assertTrue("<__group name=\"Cyan\"" in xml)
        assertTrue("<takv" in xml)
        assertTrue("<status battery=\"77\" />" in xml)
        assertTrue("a-f-G-U-C" in xml)
    }

    @Test
    fun fixFormIsAGpsFixWithItsTrueAccuracy() {
        val xml = withFix(12.0)
        assertTrue("how=\"m-g\"" in xml)
        assertTrue("ce=\"12.0\"" in xml)
        assertTrue("le=\"9999999\"" in xml)
        assertTrue("<track course=\"180.0\"" in xml)
        assertTrue("<precisionlocation geopointsrc=\"GPS\"" in xml)
    }

    @Test
    fun ceIsRoundedToOneDecimalNotPrintedAsFloatResidue() {
        // The receiver reports a float. Widened to a double, 4.567f is 4.566999912261963 and
        // that is what went on the wire before.
        val xml = withFix(4.567f.toDouble())
        assertTrue("ce=\"4.6\"" in xml)
        assertFalse("4.5669999" in xml)
    }

    @Test
    fun anUnknownAccuracyOnARealFixGoesOutAsUnknownNotZero() {
        // 0 means "the receiver gave no accuracy". It must not go on the wire as a 0 m error.
        val xml = withFix(0.0)
        assertTrue("ce=\"9999999\"" in xml)
        assertFalse("ce=\"0.0\"" in xml)
    }

    @Test
    fun theOldOverloadsStillProduceTheFixForm() {
        // Callers that do not know the accuracy (the back-compat overloads) still send a fix.
        val xml = CotBuilder.buildPLI(
            "PILOT-1", "UAS-BRAVO-Pilot", "Cyan", "Team Member",
            45.1, -100.9, 35.0, 180.0, 0.0, 77,
            "TAKPilot2", "SmartController", "Android", "1.7.5")
        assertTrue("how=\"m-g\"" in xml)
        assertTrue("lat=\"45.1\"" in xml)
        assertTrue("ce=\"9999999\"" in xml)
        assertTrue("<precisionlocation" in xml)
    }
}
