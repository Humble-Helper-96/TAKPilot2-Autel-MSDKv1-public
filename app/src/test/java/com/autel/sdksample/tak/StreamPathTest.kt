package com.autel.sdksample.tak

import com.taklite.client.tak.CotBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the stream path composer and the two rules around it.
 *
 *  - OFF: the path is byte-identical to what every earlier version built, so an installation
 *    that never opts in sees no change on the wire.
 *  - ON: `<id>-<8 lowercase hex>-Low`, the token BETWEEN the id and the suffix. The media
 *    server keys on the `-Low` ending and on the id prefix; both must survive.
 *  - The same path reaches every address — the push, the advertisement, the preview — from
 *    one function, and the CoT video uid does NOT follow the token.
 */
class StreamPathTest {

    private val shape = Regex("^[A-Za-z]+-[A-Za-z0-9-]+-[0-9a-f]{8}-Low$")

    private fun cfg(randomize: Boolean, transport: VideoTransport = VideoTransport.SRT) =
        AutelVideoStreamer.VideoConfig(
            host = "stream.example.com",
            streamId = "UAS-ALPHA",
            transport = transport,
            username = "tak", password = "s3cret",
            advertiseHost = "stream.example.com",
            advertisePort = VideoTransport.RTSP.defaultPort,
            advertiseUser = "tak", advertisePass = "s3cret",
            randomizePath = randomize,
        )

    // ---- The composer ----

    @Test
    fun offIsExactlyTheOldPath() {
        assertEquals("UAS-ALPHA-Low", StreamPath.compose("UAS-ALPHA", randomize = false))
        // The one sanitizing step the path ever had: surrounding slashes trimmed.
        assertEquals("UAS-ALPHA-Low", StreamPath.compose("/UAS-ALPHA/", randomize = false))
    }

    @Test
    fun onPutsTheTokenBetweenTheIdAndTheSuffix() {
        assertEquals(
            "UAS-ALPHA-7f3a9c2d-Low",
            StreamPath.compose("UAS-ALPHA", randomize = true, token = "7f3a9c2d"))
        assertEquals(
            "UAS-ALPHA-7f3a9c2d-Low",
            StreamPath.compose("/UAS-ALPHA/", randomize = true, token = "7f3a9c2d"))
    }

    @Test
    fun onMatchesTheServerShape() {
        val path = StreamPath.compose("UAS-ALPHA", randomize = true)
        assertTrue("not the server shape: $path", shape.matches(path))
        assertTrue(path.endsWith(StreamPath.SUFFIX))
        assertTrue(path.startsWith("UAS-"))
    }

    @Test
    fun theTokenIsEightLowercaseHexCharacters() {
        assertTrue(Regex("^[0-9a-f]{8}$").matches(StreamPath.sessionToken))
        assertEquals(StreamPath.TOKEN_LENGTH, StreamPath.sessionToken.length)
    }

    /** One token per process. Every read in this JVM is the same value. */
    @Test
    fun theTokenDoesNotChangeWithinTheProcess() {
        val first = StreamPath.sessionToken
        repeat(50) { assertEquals(first, StreamPath.sessionToken) }
        assertEquals(
            StreamPath.compose("UAS-ALPHA", true), StreamPath.compose("UAS-ALPHA", true))
    }

    /** OFF produces the same string whatever the token is: a stale token cannot leak into a
     *  configuration that never asked for one. */
    @Test
    fun offIgnoresTheToken() {
        assertEquals(
            StreamPath.compose("UAS-ALPHA", false, token = "deadbeef"),
            StreamPath.compose("UAS-ALPHA", false, token = "00000000"))
    }

    // ---- One path, every address ----

    @Test
    fun offLeavesEveryAddressByteIdenticalToTheOldOnes() {
        val c = cfg(randomize = false)
        assertEquals("UAS-ALPHA-Low", c.streamPath())
        assertEquals(
            "srt://stream.example.com:8890/publish:UAS-ALPHA-Low:tak:s3cret", c.pushUrl())
        assertEquals(
            "rtsp://tak:s3cret@stream.example.com:8554/UAS-ALPHA-Low?tcp", c.advertiseUrl())
        assertEquals(
            "srt://stream.example.com:8890/publish:UAS-ALPHA-Low:tak:***", c.urlSafe())
        assertEquals(
            "rtsp://stream.example.com:8554/UAS-ALPHA-Low",
            cfg(false, VideoTransport.RTSP).pushUrl())
    }

    @Test
    fun onCarriesTheSameTokenInTheStreamIdTheCotUrlAndTheConnectionEntryPath() {
        val c = cfg(randomize = true)
        val path = c.streamPath()
        assertTrue(shape.matches(path))
        val token = StreamPath.sessionToken

        // The SRT stream id.
        assertEquals("srt://stream.example.com:8890/publish:$path:tak:s3cret", c.pushUrl())
        // The CoT url.
        assertEquals("rtsp://tak:s3cret@stream.example.com:8554/$path?tcp", c.advertiseUrl())
        // The masked preview the pilot reads.
        assertEquals("srt://stream.example.com:8890/publish:$path:tak:***", c.urlSafe())
        // The RTSP push, for completeness.
        assertEquals("rtsp://stream.example.com:8554/$path", cfg(true, VideoTransport.RTSP).pushUrl())

        // The ConnectionEntry path is parsed out of the advertised url by the shared core.
        val xml = CotBuilder.buildPLI(
            "PILOT-1", "UAS-BRAVO-Pilot", "Cyan", "Team Member",
            45.1, -100.9, 35.0, 180.0, 0.0, 77,
            "TAKPilot2", "SmartController", "Android", "2.3.9", c.advertiseUrl())
        assertTrue("ConnectionEntry path lost the token", "path=\"/$path\"" in xml)
        assertTrue("url lost the token", "url=\"${c.advertiseUrl()}\"" in xml)
        assertTrue(token in path)
    }

    // ---- The uid stays put ----

    /** The `__video uid` a client keys its video entry on, read from the emitted CoT. The
     *  shared core's helper is package-private, so the rule is tested through the wire. */
    private fun videoUidOf(url: String): String {
        val xml = CotBuilder.buildPLI(
            "PILOT-1", "UAS-BRAVO-Pilot", "Cyan", "Team Member",
            45.1, -100.9, 35.0, 180.0, 0.0, 77,
            "TAKPilot2", "SmartController", "Android", "2.3.9", url)
        return Regex("<__video uid=\"([^\"]+)\"").find(xml)!!.groupValues[1]
    }

    /**
     * ⚠ THE RULE THAT STOPS ATAK ACCUMULATING AN ALIAS PER FLIGHT. The uid is derived from the
     * url with the token segment removed, so two sessions with two tokens — and a session with
     * the toggle off — advertise one video entry.
     */
    @Test
    fun theVideoUidDoesNotFollowTheToken() {
        val off = cfg(randomize = false).advertiseUrl()
        val a = "rtsp://tak:s3cret@stream.example.com:8554/UAS-ALPHA-7f3a9c2d-Low?tcp"
        val b = "rtsp://tak:s3cret@stream.example.com:8554/UAS-ALPHA-0badcafe-Low?tcp"
        assertNotEquals(a, b)
        assertEquals(videoUidOf(off), videoUidOf(a))
        assertEquals(videoUidOf(a), videoUidOf(b))
        // And the live token, whatever it is this run.
        assertEquals(videoUidOf(off), videoUidOf(cfg(true).advertiseUrl()))
    }

    @Test
    fun theUidStillTellsTwoFeedsApart() {
        val one = "rtsp://h:8554/UAS-ALPHA-7f3a9c2d-Low?tcp"
        val two = "rtsp://h:8554/UAS-BRAVO-7f3a9c2d-Low?tcp"
        assertNotEquals(videoUidOf(one), videoUidOf(two))
        // A segment that is not a token is left alone: wrong length, upper case, not before -Low.
        val plain = videoUidOf("rtsp://h:8554/UAS-ALPHA-Low?tcp")
        for (url in listOf(
            "rtsp://h:8554/UAS-ALPHA-7f3a9c-Low?tcp",
            "rtsp://h:8554/UAS-ALPHA-7F3A9C2D-Low?tcp",
            "rtsp://h:8554/UAS-7f3a9c2d-B2-Low?tcp",
        )) {
            assertNotEquals("$url was treated as tokenised", plain, videoUidOf(url))
        }
    }

    @Test
    fun thePasswordIsNotInThePath() {
        assertFalse("s3cret" in cfg(true).streamPath())
    }
}
