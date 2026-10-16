package com.taklite.client.tak

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the shortening of the SECOND connection's outbound log line.
 *
 * The split made every outbound CoT go out twice, and both copies logged the whole event.
 * Measured on the controller: `CoT OUT` was 30.7 % of a log file that filled 1 MB every 132 s,
 * and AppLog flushes synchronously on the calling thread every 8 KB — about once a second.
 * The elevated connection's line now names the difference instead of repeating the wire.
 *
 * ⚠ **THE POINT OF THESE TESTS IS THAT THE SHORTENING CANNOT HIDE A REAL DIFFERENCE.** The
 * two wires are allowed to differ by the `__video` block and by nothing else; anything else
 * must fall back to logging the full wire. A shortening that silently swallowed a changed
 * uid, callsign or position would make the log actively misleading, which is worse than the
 * duplication it replaced.
 */
class OutboundLogShorteningTest {

    private val head = """<event version="2.0" type="a-f-A-M-H-Q" uid="D-DRONE"><detail>"""
    private val tail = """<status battery="89"/></detail></event>"""

    /** The real shape: the playable block, with its own self-closing child inside it. */
    private val videoBlock =
        """<__video uid="v1" sensor="UAS-ALPHA" url="rtsp://stream.example.com:8554/x?tcp">""" +
            """<ConnectionEntry uid="v1" address="stream.example.com" port="8554" """ +
            """rtspReliable="0" ignoreEmbeddedKLV="false"/></__video>"""

    /** The older shape, which has no closing tag — the only case the "/>" branch is for. */
    private val bareVideoBlock = """<__video uid="v1" sensor="UAS-ALPHA" url="rtsp://h:8554/x"/>"""

    @Test
    fun `a wire with no video block is returned unchanged`() {
        val xml = head + tail
        assertEquals(xml, TakManager.withoutVideoBlock(xml))
    }

    /**
     * THE REGRESSION THIS GUARD EXISTS FOR. Cutting at the first "/>" would slice the nested
     * ConnectionEntry in half and leave a fragment, so the two wires would never compare equal
     * and the shortening would never fire on the one shape it was written for.
     */
    @Test
    fun `the closing tag wins over the nested self-closing ConnectionEntry`() {
        val stripped = TakManager.withoutVideoBlock(head + videoBlock + tail)
        assertEquals(head + tail, stripped)
        assertFalse("no fragment of the block may survive", "ConnectionEntry" in stripped)
        assertFalse("__video" in stripped)
    }

    @Test
    fun `the bare self-closing video shape is removed too`() {
        assertEquals(head + tail, TakManager.withoutVideoBlock(head + bareVideoBlock + tail))
    }

    @Test
    fun `two wires differing only by the video block match`() {
        assertTrue(TakManager.sameButForVideoBlock(head + tail, head + videoBlock + tail))
        assertTrue(TakManager.sameButForVideoBlock(head + videoBlock + tail, head + tail))
        // Both carrying it is the Emergency Broadcast case.
        assertTrue(
            TakManager.sameButForVideoBlock(head + videoBlock + tail, head + videoBlock + tail))
    }

    @Test
    fun `a different uid does NOT match, video block or not`() {
        val other = """<event version="2.0" type="a-f-A-M-H-Q" uid="OTHER"><detail>""" + tail
        assertFalse(TakManager.sameButForVideoBlock(head + tail, other))
        assertFalse(TakManager.sameButForVideoBlock(head + videoBlock + tail, other))
    }

    /** A moved aircraft between the two sends must log in full — the position is the payload. */
    @Test
    fun `a different position does NOT match`() {
        val a = """<event uid="D"><point lat="45.3089" lon="-100.5300"/></event>"""
        val b = """<event uid="D"><point lat="45.3090" lon="-100.5300"/></event>"""
        assertFalse(TakManager.sameButForVideoBlock(a, b))
    }

    /** Two DIFFERENT video blocks are still a real difference outside the block's presence —
     *  but both strip to the same thing, so this documents the one case the shortening treats
     *  as equal on purpose: the block's CONTENT is not compared, only its presence. The full
     *  url still reaches the log on the line that carries it. */
    @Test
    fun `only the presence of the block is compared, not its contents`() {
        val withOther = head +
            """<__video uid="v2" sensor="UAS-ALPHA" url="rtsp://other:8554/y"/>""" + tail
        assertTrue(TakManager.sameButForVideoBlock(head + bareVideoBlock + tail, withOther))
    }

    @Test
    fun `null is never a match and never throws`() {
        assertEquals(null, TakManager.withoutVideoBlock(null))
        assertFalse(TakManager.sameButForVideoBlock(null, head + tail))
        assertFalse(TakManager.sameButForVideoBlock(head + tail, null))
        assertFalse(TakManager.sameButForVideoBlock(null, null))
    }
}
