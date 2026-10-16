package com.taklite.client.tak

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the ONE fact that makes a CoT-advertised SRT feed playable in ATAK.
 *
 * Tested against a live TAK Server, a real MediaMTX SRT listener and real clients:
 *
 * > For ATAK, put the whole query string — `?streamid=…&passphrase=…` — into
 * > `ConnectionEntry.path`, not just into `url=`.
 *
 * ⚠ **ATAK DOES NOT READ `url` FOR SRT.** It rebuilds the connection from `ConnectionEntry`
 * and its parser matches on the literal `?streamid=` text. Given a bare stream name, or the
 * streamid without that prefix, it hands an EMPTY stream id to its native SRT call; the
 * server answers `invalid stream ID ''` and the open fails instantly, every time. A test that
 * only checked `url` would pass while nothing played.
 */
class SrtConnectionEntryTest {

    private val srtUrl =
        "srt://stream.example.com:8890" +
            "?streamid=read:TestFeed-Low:tak:tak&passphrase=REDACTED-PASSPHRASE"

    private fun droneXml(videoUrl: String?): String = CotBuilder.buildDronePLI(
        "UID-DRONE", "UAS-ALPHA",
        45.2, -100.8, 100.0, 250.0, 7.5, 66,
        videoUrl, "UID-DRONE-SPI",
        65.8, 39.9, 250.0, -10.0, 300.0, 0.0,
        0.0, -10.0, 250.0,
        true, 300,
        7100, 4686, 15.9,
        "PILOT-1")

    private fun attr(xml: String, name: String): String {
        val m = Regex("""<ConnectionEntry\b[^>]*?\s$name="([^"]*)"""").find(xml)
        // Unescaped, so a test can state the LOGICAL value. That the wire really is escaped
        // is a separate assertion below — both matter and they are different claims.
        return (m?.groupValues?.get(1) ?: error("no ConnectionEntry $name in:\n$xml"))
            .replace("&amp;", "&").replace("&quot;", "\"").replace("&apos;", "'")
            .replace("&lt;", "<").replace("&gt;", ">")
    }

    /** THE test. The leading "?" is part of it — ATAK matches on "?streamid=". */
    @Test
    fun `the whole query string lands in ConnectionEntry path, leading question mark included`() {
        val path = attr(droneXml(srtUrl), "path")
        assertEquals(
            "?streamid=read:TestFeed-Low:tak:tak&passphrase=REDACTED-PASSPHRASE", path)
    }

    /** The shapes that were seen to produce an empty stream id. None may reappear. */
    @Test
    fun `the path is never the bare stream name nor a streamid without its prefix`() {
        val path = attr(droneXml(srtUrl), "path")
        assertFalse("bare stream name", path == "TestFeed-Low")
        assertFalse("missing the ?streamid= prefix", path == "read:TestFeed-Low:tak:tak")
        assertTrue("must keep the prefix ATAK matches on", path.startsWith("?streamid="))
    }

    /** `&` has to be XML-escaped in the attribute, or the CoT is not well-formed. */
    @Test
    fun `the ampersand is escaped in the emitted xml`() {
        val xml = droneXml(srtUrl)
        assertTrue("&amp;passphrase=" in xml)
        assertFalse("a raw ampersand would break the parse",
            Regex("""&(?!amp;|lt;|gt;|quot;|apos;|#)""").containsMatchIn(xml))
    }

    /** Scheme, host and port still come out right — the query handling must not disturb them. */
    @Test
    fun `protocol address and port are taken from the srt url`() {
        val xml = droneXml(srtUrl)
        assertEquals("srt", attr(xml, "protocol"))
        assertEquals("stream.example.com", attr(xml, "address"))
        assertEquals("8890", attr(xml, "port"))
    }

    /**
     * TAK Aware reads `url` verbatim and ignores ConnectionEntry; ATAK does the opposite. Both
     * halves must therefore be populated, and testing confirmed the two clients' needs do
     * not conflict — TAK Aware notices the mismatch and uses the url anyway.
     */
    @Test
    fun `the url attribute still carries the full address for clients that read it`() {
        assertTrue("""url="$srtUrl"""".replace("&", "&amp;") in droneXml(srtUrl))
    }

    /**
     * ⚠ RTSP MUST NOT MOVE. Its `?tcp` has never been part of its path and both clients have
     * played this form for years. Widening the query rule to every scheme would break the one
     * case that works to fix one that does not.
     */
    @Test
    fun `rtsp keeps its old path and does not gain the query`() {
        val xml = droneXml("rtsp://tak:s3cret@stream.example.com:8554/TestFeed-Low?tcp")
        assertEquals("/TestFeed-Low", attr(xml, "path"))
        assertEquals("rtsp", attr(xml, "protocol"))
    }

    /** No video is still no video — no branch here may conjure a block. */
    @Test
    fun `a null or empty url advertises nothing`() {
        assertFalse("__video" in droneXml(null))
        assertFalse("__video" in droneXml(""))
    }

    /** The uid must stay stable across reports, SRT included, or a client churns the entry. */
    @Test
    fun `the video uid is stable across repeated reports`() {
        assertEquals(attr(droneXml(srtUrl), "uid"), attr(droneXml(srtUrl), "uid"))
    }
}
