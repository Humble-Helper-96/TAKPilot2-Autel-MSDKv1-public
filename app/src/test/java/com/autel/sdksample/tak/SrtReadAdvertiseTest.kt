package com.autel.sdksample.tak

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the SRT **READ** advertisement — the address the TEAM connects to, which is a different
 * leg from the SRT push that [VideoTransportUrlTest] covers.
 *
 * The formula is not ours to invent: it comes from testing against a live MediaMTX and real
 * clients. These tests exist because every mistake available here fails the same invisible
 * way — the push is perfect, the server is happy, and every viewer gets nothing.
 *
 * ⚠ The companion rule, that `ConnectionEntry.path` must carry the whole query string, is
 * pinned in `SrtConnectionEntryTest` — a correct url with a wrong `path` still plays on
 * nothing.
 */
class SrtReadAdvertiseTest {

    private fun cfg(
        user: String = "tak",
        pass: String = "tak",
        phrase: String = "REDACTED-PASSPHRASE",
        port: Int = 8890,
    ) = AutelVideoStreamer.VideoConfig(
        host = "stream.example.com",
        streamId = "TestFeed",
        username = "push-user", password = "push-pass",
        transport = VideoTransport.SRT,
        srtPassphrase = "PUBLISH-PHRASE-NOT-THIS-ONE",
        advertiseEnabled = true,
        advertiseHost = "stream.example.com",
        advertiseUser = user, advertisePass = pass,
        advertiseTransport = VideoTransport.SRT,
        advertiseSrtPort = port,
        advertisePassphrase = phrase,
    )

    /** The worked example from the reference document, field for field. */
    @Test
    fun `the advertised srt url matches the reference document`() {
        assertEquals(
            "srt://stream.example.com:8890" +
                "?streamid=read:TestFeed-Low:tak:tak&passphrase=REDACTED-PASSPHRASE",
            cfg().advertiseUrl())
    }

    /** `read:`, not `publish:`. The push leg builds the other one from the same path. */
    @Test
    fun `the stream id asks to READ and the push still asks to PUBLISH`() {
        val c = cfg()
        assertTrue("read:" in c.advertiseUrl())
        assertFalse("publish:" in c.advertiseUrl())
        assertTrue("publish:" in c.pushUrl())
        assertFalse("read:" in c.pushUrl())
    }

    /**
     * ⚠ The two passphrases are DIFFERENT SETTINGS on the server (`srtReadPassphrase` against
     * `srtPublishPassphrase`). Crossing them is the mistake that looks like bad credentials.
     */
    @Test
    fun `the read passphrase is used and the publish passphrase never leaks into it`() {
        val url = cfg().advertiseUrl()
        assertTrue("passphrase=REDACTED-PASSPHRASE" in url)
        assertFalse("PUBLISH-PHRASE-NOT-THIS-ONE" in url)
    }

    /** The document is explicit: do not emit a dangling `&passphrase=` with nothing after it. */
    @Test
    fun `no passphrase means no passphrase parameter at all`() {
        val url = cfg(phrase = "").advertiseUrl()
        assertEquals(
            "srt://stream.example.com:8890?streamid=read:TestFeed-Low:tak:tak", url)
        assertFalse("passphrase" in url)
    }

    /** A path with no auth must not be handed an empty user — trailing colons are a real,
     *  empty user to the server, and it refuses them. */
    @Test
    fun `no user means no credentials in the stream id`() {
        assertEquals(
            "srt://stream.example.com:8890?streamid=read:TestFeed-Low&passphrase=REDACTED-PASSPHRASE",
            cfg(user = "", pass = "").advertiseUrl())
    }

    /** The read port is its own field — reusing the RTSP read port would point the team at
     *  a port with no SRT listener on it. */
    @Test
    fun `the read srt port is independent of every other port`() {
        val c = cfg(port = 9999).copy(advertisePort = 8554, srtPort = 8890, rtspPort = 8554)
        assertTrue("stream.example.com:9999" in c.advertiseUrl())
    }

    /** RTSP stays byte-for-byte what it has always been. The whole point of a default is that
     *  existing installations do not move. */
    @Test
    fun `the rtsp advertisement is untouched by any of this`() {
        val c = cfg().copy(
            advertiseTransport = VideoTransport.RTSP,
            advertisePort = 8554,
            advertiseUser = "tak", advertisePass = "s3cret")
        assertEquals(
            "rtsp://tak:s3cret@stream.example.com:8554/TestFeed-Low?tcp", c.advertiseUrl())
    }

    /** The default must be RTSP: an unconfigured upgrade cannot start advertising an address
     *  many TAK clients fail to open. */
    @Test
    fun `the default read transport is rtsp`() {
        assertEquals(VideoTransport.RTSP,
            AutelVideoStreamer.VideoConfig(
                host = "h", streamId = "s", username = "", password = "").advertiseTransport)
    }

    /**
     * ⚠ A SECURITY CONTROL, not cosmetics. The read passphrase is the one secret that must
     * appear in a url, so the preview a pilot reads — and screenshots into training material —
     * must not show it.
     */
    @Test
    fun `the masked preview hides the passphrase and the password`() {
        val safe = cfg().advertiseUrlSafe()
        assertFalse("REDACTED-PASSPHRASE" in safe)
        assertTrue("passphrase=***" in safe)
        assertTrue("read:TestFeed-Low:tak:***" in safe)
    }

    /** An unset secret must not mask to the same thing as a set one, or the preview cannot
     *  answer the question it exists to answer. See VideoConfig.urlSafe. */
    @Test
    fun `an empty password reads as NO PASSWORD and not as stars`() {
        assertTrue("(NO PASSWORD)" in cfg(pass = "").advertiseUrlSafe())
    }

    /** Advertising off means no address at all, whatever the protocol says. */
    @Test
    fun `advertising off yields an empty url on both transports`() {
        assertEquals("", cfg().copy(advertiseEnabled = false).advertiseUrl())
        assertEquals("", cfg().copy(
            advertiseEnabled = false, advertiseTransport = VideoTransport.RTSP).advertiseUrl())
    }

    /**
     * ⚠ The url has to SURVIVE java.net.URI, because `CotBuilder.appendVideo` parses it to fill
     * ConnectionEntry. A form that throws there would silently fall back to the unparseable
     * branch and advertise a host of the whole url.
     */
    @Test
    fun `the advertised srt url parses as a URI with host port and query intact`() {
        val u = java.net.URI.create(cfg().advertiseUrl())
        assertEquals("srt", u.scheme)
        assertEquals("stream.example.com", u.host)
        assertEquals(8890, u.port)
        assertEquals("streamid=read:TestFeed-Low:tak:tak&passphrase=REDACTED-PASSPHRASE", u.rawQuery)
    }
}
