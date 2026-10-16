package com.taklite.client.tak

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins what the aircraft tells the team about heights and accuracy: an unknown height says unknown, a known one is sent as it is, and the aircraft's
 * position never claims to be perfect.
 */
class OutboundHeightsTest {

    @Test
    fun `an SPI with no known height says unknown and claims no terrain source`() {
        val xml = CotBuilder.buildSensorPoint("spi", "drone", "UAS-SPI", 45.2, -100.9, 400.0)
        assertTrue("hae=\"9999999\"" in xml)
        assertFalse("hae=\"0\"" in xml)
        assertFalse("DTED0" in xml)
    }

    @Test
    fun `an SPI with a height sends it and names DTED as the source`() {
        val xml = CotBuilder.buildSensorPoint("spi", "drone", "UAS-SPI", 45.2, -100.9, 400.0, 188.4)
        assertTrue("hae=\"188.4\"" in xml)
        assertTrue("altsrc=\"DTED\"" in xml)
    }

    private fun pli(ce: Double, le: Double) = CotBuilder.buildDronePLI(
        "drone", "UAS", 45.2, -100.9, 300.0, 90.0, 5.0, 80, null, null,
        60.0, 40.0, 90.0, -20.0, 400.0, 0.0, 0.0, -20.0, 90.0, true, 0, 0, 0, 0.0, "op", ce, le)

    @Test
    fun `the aircraft position carries the receiver's accuracy`() {
        val xml = pli(3.9, 5.2)
        assertTrue("ce=\"3.9\"" in xml)
        assertTrue("le=\"5.2\"" in xml)
    }

    @Test
    fun `an unknown accuracy is unknown, never a perfect zero`() {
        val xml = pli(-1.0, -1.0)
        assertTrue("ce=\"9999999\"" in xml)
        assertFalse("ce=\"0.0\"" in xml)
    }
}
