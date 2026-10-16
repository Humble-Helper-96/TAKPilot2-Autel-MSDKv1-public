package com.autel.sdksample.tak

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins what BASIC does to the video-server configuration — and what it does to the PLAIN keys
 * the streamer actually reads. The plain-key cases each close a hole that the per-slot tests
 * alone cannot see.
 *
 * ⚠ **THE FAULT THIS GUARDS AGAINST IS A SERVER THE PILOT CANNOT SEE, ON THE TEAM'S CoT.**
 * Hiding the Server 2 card does not remove it from the wire. Two separate settings reach past
 * it, and a correct fix has to deal with BOTH:
 *
 *  1. `video_active_slot` — the slot the stream actually publishes to.
 *  2. `video_s1_advertise = "other"` — which resolves the whole advertisement (host, port, login
 *     and read passphrase) from slot 2, no matter which slot is active.
 *
 * ⚠ **AND BOTH ARE PER-SLOT KEYS, WHICH THE STREAMER NEVER READS.** It reads `video_adv_*`,
 * written only by [VideoServersActivity.mirrorActiveSlot]. A fix that edits the slots and stops
 * there is correct in every per-slot assertion and still advertises Server 2 on the next LIVE.
 */
class AppModeVideoSlotTest {

    private fun prefs(vararg kv: Pair<String, Any?>) = FakePrefs(mutableMapOf(*kv)).slotsMigrated()

    @Test
    fun `an active slot 2 is forced back to slot 1`() {
        val p = prefs(VideoServersActivity.KEY_V_ACTIVE_SLOT to 2)
        AppModeTransition.forceSingleVideoServer(p)
        assertEquals(1, p.getInt(VideoServersActivity.KEY_V_ACTIVE_SLOT, -1))
    }

    /**
     * ⚠ **THE ONE A "FORCE THE SLOT TO 1" FIX MISSES ENTIRELY.** With `advertise = "other"` the
     * CoT names SERVER 2 — its host, its read port and its read passphrase — while the stream
     * publishes to Server 1. Forcing the active slot does not touch it.
     */
    @Test
    fun `an advertisement pointing at the hidden server is switched off`() {
        val p = prefs(VideoServersActivity.vKey(1, "advertise") to VideoServersActivity.ADV_OTHER)
        AppModeTransition.forceSingleVideoServer(p)
        assertEquals(
            VideoServersActivity.ADV_OFF,
            p.getString(VideoServersActivity.vKey(1, "advertise"), null))
    }

    /**
     * ⚠ **OFF, NEVER "self".** Self would advertise slot 1's own address, which nobody chose for
     * this and which may not be reachable from the team. An address that looks right and does
     * not play reads as a dead feed; no advertisement at all is visibly absent.
     */
    @Test
    fun `the replacement is off and never self`() {
        val p = prefs(VideoServersActivity.vKey(1, "advertise") to VideoServersActivity.ADV_OTHER)
        AppModeTransition.forceSingleVideoServer(p)
        val got = p.getString(VideoServersActivity.vKey(1, "advertise"), null)
        assertEquals(false, got == VideoServersActivity.ADV_SELF)
    }

    @Test
    fun `a self advertisement is left alone`() {
        val p = prefs(VideoServersActivity.vKey(1, "advertise") to VideoServersActivity.ADV_SELF)
        AppModeTransition.forceSingleVideoServer(p)
        assertEquals(
            VideoServersActivity.ADV_SELF,
            p.getString(VideoServersActivity.vKey(1, "advertise"), null))
    }

    @Test
    fun `an off advertisement stays off`() {
        val p = prefs(VideoServersActivity.vKey(1, "advertise") to VideoServersActivity.ADV_OFF)
        AppModeTransition.forceSingleVideoServer(p)
        assertEquals(
            VideoServersActivity.ADV_OFF,
            p.getString(VideoServersActivity.vKey(1, "advertise"), null))
    }

    // ---- The plain keys the streamer reads ----

    /** A store advertising Server 2 through slot 1, with both servers fully configured. */
    private fun advertisingThroughServer2() = prefs(
        VideoServersActivity.KEY_V_ACTIVE_SLOT to 1,
        VideoServersActivity.vKey(1, "host") to "push.example.com",
        VideoServersActivity.vKey(1, "advertise") to VideoServersActivity.ADV_OTHER,
        VideoServersActivity.vKey(2, "host") to "hidden.example.com",
        VideoServersActivity.vKey(2, "adv_transport") to "srt",
        VideoServersActivity.vKey(2, "adv_srt_phrase") to "HIDDEN-PHRASE",
    ).also { VideoServersActivity.mirrorActiveSlot(it) }

    /** Precondition for the next test: the plain keys really do name the hidden server. */
    @Test
    fun `before the change the plain keys advertise the hidden server`() {
        val p = advertisingThroughServer2()
        assertTrue(p.getBoolean("video_adv_on", false))
        assertEquals("hidden.example.com", p.getString("video_adv_host", null))
    }

    /**
     * ⚠ **THE HOLE THE PER-SLOT TESTS CANNOT SEE.** After the BASIC transition the plain
     * `video_adv_on` must be false — that is the key the next LIVE reads. Without the mirror in
     * [AppModeTransition.apply] it stays true and still names Server 2, whatever the slot keys
     * say. Drives the slot fix and the mirror together, as `apply` does.
     */
    @Test
    fun `after forcing a single server the streamer no longer advertises the hidden one`() {
        val p = advertisingThroughServer2()
        AppModeTransition.forceSingleVideoServer(p)
        VideoServersActivity.mirrorActiveSlot(p)
        assertFalse("video_adv_on is what the streamer reads; it must be off",
            p.getBoolean("video_adv_on", true))
    }

    // ---- ONE mirror, not two ----

    /**
     * ⚠ **THE TWO MIRRORS HAD DRIFTED, AND THIS IS THE BUG IT CAUSED.** Pre-Flight carried its
     * own copy that never wrote the three read-leg keys. Switching the active server ON
     * PRE-FLIGHT re-pointed `video_adv_host` at the new server while leaving the OLD server's
     * `video_adv_transport`, SRT read port and read passphrase in place — an
     * `srt://new-host:old-port?…&passphrase=old` that nobody can open. There is one mirror now;
     * this pins that the read leg travels with the host on a slot switch.
     */
    @Test
    fun `the read leg follows the host when the active server changes`() {
        val p = prefs(
            VideoServersActivity.KEY_V_ACTIVE_SLOT to 1,
            VideoServersActivity.vKey(1, "host") to "one.example.com",
            VideoServersActivity.vKey(1, "adv_transport") to "srt",
            VideoServersActivity.vKey(1, "adv_srt_port") to 8890,
            VideoServersActivity.vKey(1, "adv_srt_phrase") to "PHRASE-ONE",
            VideoServersActivity.vKey(2, "host") to "two.example.com",
            VideoServersActivity.vKey(2, "adv_transport") to "rtsp",
            VideoServersActivity.vKey(2, "adv_srt_port") to 9990,
            VideoServersActivity.vKey(2, "adv_srt_phrase") to "",
        )
        VideoServersActivity.mirrorActiveSlot(p)
        assertEquals("srt", p.getString("video_adv_transport", null))

        p.edit().putInt(VideoServersActivity.KEY_V_ACTIVE_SLOT, 2).apply()
        VideoServersActivity.mirrorActiveSlot(p)

        assertEquals("two.example.com", p.getString("video_adv_host", null))
        assertEquals("the transport must be server 2's, not server 1's",
            "rtsp", p.getString("video_adv_transport", null))
        assertEquals(9990, p.getInt("video_adv_srt_port", -1))
        assertEquals("server 1's read passphrase must not survive the switch",
            "", p.getString("video_adv_srt_phrase", null))
    }

    /**
     * ⚠ The active slot is NORMALISED. A stored 0 or 3 used to mirror vKey(3, …) — an empty host —
     * on the Video Servers screen while Pre-Flight's copy treated it as 1.
     */
    @Test
    fun `an out-of-range active slot mirrors slot 1, not an empty slot`() {
        for (bad in listOf(0, 3, -1)) {
            val p = prefs(
                VideoServersActivity.KEY_V_ACTIVE_SLOT to bad,
                VideoServersActivity.vKey(1, "host") to "one.example.com",
            )
            VideoServersActivity.mirrorActiveSlot(p)
            assertEquals("slot $bad", "one.example.com", p.getString("video_host", null))
        }
    }

    /** And the one key only the old Pre-Flight copy wrote must still be written. */
    @Test
    fun `the mirror still carries the active server's profile`() {
        val p = prefs(
            VideoServersActivity.KEY_V_ACTIVE_SLOT to 2,
            VideoServersActivity.vKey(2, "profile") to "high",
        )
        VideoServersActivity.mirrorActiveSlot(p)
        assertEquals("high", p.getString("video_profile", null))
    }
}
