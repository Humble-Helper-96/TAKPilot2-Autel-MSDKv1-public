package com.taklite.client.tak

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the video-split/Emergency-Broadcast policy: [TakManager.videoFor] is the one
 * place that decides whether a given outbound connection carries the `__video` element. It is
 * pure and socket-free so this test needs no [TakClient], no [TakManager] instance, and no
 * sockets — see that method's doc.
 */
class VideoSplitPolicyTest {

    private val url = "rtsp://user:pass@host:8554/Feed-A"

    // ---- Split configured, no emergency override ----

    @Test
    fun splitConfiguredConnectionANeverCarriesVideo() {
        assertNull(TakManager.videoFor("A", true, false, url))
    }

    @Test
    fun splitConfiguredConnectionBCarriesVideoUnchanged() {
        assertEquals(url, TakManager.videoFor("B", true, false, url))
    }

    // ---- Split NOT configured — today's single-connection behaviour, unchanged (rollback case) ----

    @Test
    fun splitOffConnectionAPassesVideoThrough() {
        assertEquals(url, TakManager.videoFor("A", false, false, url))
    }

    @Test
    fun splitOffConnectionBPassesVideoThrough() {
        assertEquals(url, TakManager.videoFor("B", false, false, url))
    }

    // ---- Emergency Broadcast override — wins regardless of split state ----

    @Test
    fun emergencyActiveConnectionACarriesVideoEvenWithSplitConfigured() {
        assertEquals(url, TakManager.videoFor("A", true, true, url))
    }

    @Test
    fun emergencyActiveConnectionBCarriesVideoWithSplitConfigured() {
        assertEquals(url, TakManager.videoFor("B", true, true, url))
    }

    @Test
    fun emergencyActiveOverridesEvenWithSplitOff() {
        // Degenerate but must still hold: the override is checked first and unconditionally,
        // independent of splitConfigured — see videoFor's doc on why nothing after that check
        // may re-narrow it.
        assertEquals(url, TakManager.videoFor("A", false, true, url))
        assertEquals(url, TakManager.videoFor("B", false, true, url))
    }

    @Test
    fun nullUrlStaysNullOnEveryPath() {
        // No video to begin with — must not be conjured from nothing by any policy branch.
        assertNull(TakManager.videoFor("A", true, false, null))
        assertNull(TakManager.videoFor("B", true, false, null))
        assertNull(TakManager.videoFor("A", true, true, null))
        assertNull(TakManager.videoFor("B", false, false, null))
    }

    // ---- Structural: A's and B's drone XML describe the SAME aircraft, differing only in video ----

    @Test
    fun aAndBDroneXmlShareUidAndOnlyBCarriesVideo() {
        val droneUid = "UID-DRONE"
        val urlA = TakManager.videoFor("A", true, false, url)
        val urlB = TakManager.videoFor("B", true, false, url)

        val xmlA = droneXml(droneUid, urlA)
        val xmlB = droneXml(droneUid, urlB)

        assertTrue("uid=\"$droneUid\"" in xmlA)
        assertTrue("uid=\"$droneUid\"" in xmlB)
        assertTrue("__video" !in xmlA)
        assertTrue("__video" in xmlB)
    }

    private fun droneXml(droneUid: String, videoUrl: String?): String = CotBuilder.buildDronePLI(
        droneUid, "UAS-BRAVO",
        45.2, -100.8, 100.0, 250.0, 7.5, 66,
        videoUrl, "$droneUid-SPI",
        65.8, 39.9, 250.0, -10.0, 300.0, 0.0,
        0.0, -10.0, 250.0,
        true, 300,
        7100, 4686, 15.9,
        "PILOT-1")
}
