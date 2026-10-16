package com.taklite.client.tak

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the two things this application must give a receiver so the camera look-point (SPI) can
 * expire on its own: a SHORT stale window, and nothing that asks for the point to be kept.
 *
 * ⚠ **THE APPLICATION DOES NOT DELETE IT, AND THAT IS A DECISION.** A
 * `t-x-d-d` delete, sent whenever the camera stopped having a look-point, was built and then
 * removed. The controller does not tell the server to delete anything: how long a
 * point survives after it stops being refreshed is the receiving client's RETENTION POLICY. An
 * aircraft that reaches into every EUD on the net and removes a map item is a much larger
 * hammer than the problem.
 *
 * So these tests do not assert that the point goes away — they cannot, because that is not this
 * application's behaviour. They assert that nothing here STOPS it going away.
 */
class SensorPointRetentionTest {

    private fun spi(): String = CotBuilder.buildSensorPoint(
        "UID-DRONE-SPI", "UID-DRONE", "UAS-ALPHA-SPI", 45.2, -100.8, 300.0)

    private fun field(xml: String, name: String): String =
        Regex("""\b$name="([^"]+)"""").find(xml)!!.groupValues[1]

    private fun parse(t: String): Long =
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            .parse(t)!!.time

    /**
     * ⚠ The stale window must stay SHORT. It is the ONLY instruction this application gives a
     * receiver about the point's lifetime, so it is the only lever there is. Measured on the
     * wire: start and stale exactly 15 s apart.
     */
    @Test
    fun `the sensor point declares a short stale window`() {
        val xml = spi()
        val window = parse(field(xml, "stale")) - parse(field(xml, "start"))
        assertEquals("the one lifetime instruction a receiver gets", 15_000L, window)
    }

    /** `time` and `start` are the same instant: the point is current when it is sent, not
     *  backdated, or a receiver computes a window shorter than the one intended. */
    @Test
    fun `time and start are the same instant`() {
        val xml = spi()
        assertEquals(field(xml, "time"), field(xml, "start"))
    }

    /**
     * ⚠ NEVER ARCHIVED. `<archived/>` is what asks a client to keep an item for good, and this
     * application's OWN parser reads it exactly that way — see [CotParser.isPersistentType]. A
     * re-derived point that asked to be kept would be immortal by request, which is the one way
     * this class could cause the fault it is accused of.
     */
    @Test
    fun `the sensor point never asks to be archived`() {
        val xml = spi()
        assertFalse("archived" in xml)
        assertFalse("<archive" in xml)
    }

    /** Our own retention rule must agree: a sensor point is re-derived, never persistent, even
     *  if some sender did flag it. The two halves of this file are the same claim from the
     *  sending and the receiving side. */
    @Test
    fun `our own parser refuses to persist a sensor point whatever it is flagged`() {
        assertFalse(CotParser.isPersistentType("b-m-p-s-p-i", true, false))
        assertFalse(CotParser.isPersistentType("b-m-p-s-p-i", false, false))
    }

    /** The uid is stable, so repeated pushes UPDATE one point rather than littering the map
     *  with one per tick — each push renewing the same short window. */
    @Test
    fun `the uid is stable across pushes`() {
        assertEquals(field(spi(), "uid"), field(spi(), "uid"))
    }

    /** ⚠ NO DELETE IS BUILT ANY MORE. If this fails, someone has put the t-x-d-d back; read
     *  the note on SENSOR_POINT_STALE_MS before deciding that was wanted. */
    @Test
    fun `the builder exposes no delete`() {
        assertTrue(CotBuilder::class.java.methods.none {
            it.name.contains("Delete", ignoreCase = true)
        })
    }
}
