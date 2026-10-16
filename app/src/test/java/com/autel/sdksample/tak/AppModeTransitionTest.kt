package com.autel.sdksample.tak

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pins [AppModeTransition] — the one place the mode changes.
 *
 * Each case is a fault found in an earlier draft: the BASIC writes are ATOMIC, the plain keys are re-mirrored, the live teardown runs
 * on [ElevatedChannel], BASIC is a mute that ENHANCED UNDOES — locks included — a stale advertised
 * url is cleared, and the disclosure is GENERATED from the effective state.
 */
class AppModeTransitionTest {

    private lateinit var seams: LiveSeams
    @Before fun install() { seams = LiveSeams() }
    @After fun restore() { seams.restore() }

    private val chb = TakAutoConnect.KEY_CHB_ENABLED
    private val slot = VideoServersActivity.KEY_V_ACTIVE_SLOT
    private val adv1 = VideoServersActivity.vKey(1, "advertise")
    private val adv2 = VideoServersActivity.vKey(2, "advertise")

    /** A controller as flown in ENHANCED: split on, publishing to Server 2 (which
     *  advertises Server 1 — "other" from slot 2), all three locks set. Everything BASIC mutes. */
    private fun elevated() = FakePrefs(AppMode.ENHANCED).slotsMigrated()
        .put(chb, true)
        .put(slot, 2)
        .put(adv1, VideoServersActivity.ADV_OTHER)
        .put(adv2, VideoServersActivity.ADV_SELF)
        .put(VideoServersActivity.vKey(1, "host"), "one.example.com")
        .put(VideoServersActivity.vKey(2, "host"), "two.example.com")
        .put(TakConnectActivity.KEY_TAK_LOCKED, true)
        .put(TakConnectActivity.KEY_VIDEO_LOCKED, true)
        .put(TakConnectActivity.KEY_BATTERY_LOCKED, true)

    // ---- To BASIC ----

    @Test
    fun `to BASIC mutes the split, pins one server and switches the hidden advertisement off`() {
        val p = elevated()
        assertTrue(AppModeTransition.apply(p, AppMode.BASIC))
        assertEquals(AppMode.BASIC, AppMode.of(p))
        assertFalse(p.getBoolean(chb, true))
        assertEquals(1, p.getInt(slot, -1))
        assertEquals(VideoServersActivity.ADV_OFF, p.getString(adv1, null))
    }

    /** ⚠ The teardown really happens — the broadcast cancelled, THEN the split cleared. */
    @Test
    fun `to BASIC cancels a running broadcast and clears the Elevated socket, in that order`() {
        seams.live.emergency = true
        seams.live.configured = true
        AppModeTransition.apply(elevated(), AppMode.BASIC)
        assertEquals(listOf("cancelEB", "clear"), seams.live.calls)
    }

    /** ⚠ The mode is written BEFORE the live teardown runs, so a dial queued behind it declines. */
    @Test
    fun `the mode is already BASIC when the teardown runs`() {
        val p = elevated()
        var modeAtTeardown: AppMode? = null
        ElevatedChannel.runner = { modeAtTeardown = AppMode.of(p); it.run() }
        AppModeTransition.apply(p, AppMode.BASIC)
        assertEquals(AppMode.BASIC, modeAtTeardown)
    }

    /** ⚠ A url left on the bridge by a failed restart must not survive into BASIC. */
    @Test
    fun `both directions clear a stale advertised url`() {
        val p = elevated()
        AppModeTransition.apply(p, AppMode.BASIC)
        AppModeTransition.apply(p, AppMode.ENHANCED)
        assertEquals(2, seams.staleUrlCleared)
    }

    /** ⚠ The plain keys the next LIVE reads — with no earlier mirror to mask a missing one. */
    @Test
    fun `to BASIC rewrites the plain keys itself`() {
        val p = elevated().put("video_adv_on", true).put("video_host", "two.example.com")
        AppModeTransition.apply(p, AppMode.BASIC)
        assertEquals("one.example.com", p.getString("video_host", null))
        // Slot 1 is now active and advertises off.
        assertFalse(p.getBoolean("video_adv_on", true))
    }

    @Test
    fun `to BASIC keeps the Elevated certificate paths`() {
        val p = elevated().put("chb_truststore_path", "/data/ts.p12").put("chb_clientcert_path", "/data/cc.p12")
        AppModeTransition.apply(p, AppMode.BASIC)
        assertEquals("/data/ts.p12", p.getString("chb_truststore_path", null))
        assertEquals("/data/cc.p12", p.getString("chb_clientcert_path", null))
        assertFalse(p.getBoolean("chb_logged_out", false))
    }

    @Test
    fun `a change to the current mode does nothing`() {
        val p = elevated()
        assertFalse(AppModeTransition.apply(p, AppMode.ENHANCED))
        assertTrue(p.getBoolean(chb, false))
        assertEquals(emptyList<String>(), seams.live.calls)
    }

    // ---- Back to ENHANCED ----

    @Test
    fun `the round trip gives back exactly what BASIC muted, and redials`() {
        val p = elevated()
        AppModeTransition.apply(p, AppMode.BASIC)
        var redialled = false
        AppModeTransition.apply(p, AppMode.ENHANCED) { redialled = true }
        assertEquals(AppMode.ENHANCED, AppMode.of(p))
        assertTrue(p.getBoolean(chb, false))
        assertEquals(2, p.getInt(slot, -1))
        assertEquals(VideoServersActivity.ADV_OTHER, p.getString(adv1, null))
        assertTrue(redialled)
        assertFalse(p.getBoolean(AppModeTransition.KEY_SNAP_TAKEN, false))
    }

    /** ⚠ The plain keys follow the restore on their own, with no screen to mirror for them. */
    @Test
    fun `to ENHANCED rewrites the plain keys itself`() {
        val p = elevated()
        AppModeTransition.apply(p, AppMode.BASIC)
        p.edit().putString("video_host", "stale").apply()
        AppModeTransition.apply(p, AppMode.ENHANCED)
        assertEquals("two.example.com", p.getString("video_host", null))
    }

    /**
     * ⚠ **THE LOCKS COME BACK.** BASIC unlocks with no password, so a lock cleared there
     * used to stay cleared — an Elevated controller handed back "locked" with every section open.
     */
    @Test
    fun `locks cleared in BASIC are set again on the return`() {
        val p = elevated()
        AppModeTransition.apply(p, AppMode.BASIC)
        p.edit().putBoolean(TakConnectActivity.KEY_TAK_LOCKED, false)
            .putBoolean(TakConnectActivity.KEY_VIDEO_LOCKED, false).apply()
        AppModeTransition.apply(p, AppMode.ENHANCED)
        assertTrue(p.getBoolean(TakConnectActivity.KEY_TAK_LOCKED, false))
        assertTrue(p.getBoolean(TakConnectActivity.KEY_VIDEO_LOCKED, false))
        assertTrue(p.getBoolean(TakConnectActivity.KEY_BATTERY_LOCKED, false))
    }

    /** Only ever LOCK: a lock that was off before BASIC is not switched on by the snapshot. */
    @Test
    fun `the return never sets a lock that was off before BASIC`() {
        val p = elevated().put(TakConnectActivity.KEY_BATTERY_LOCKED, false)
        AppModeTransition.apply(p, AppMode.BASIC)
        AppModeTransition.apply(p, AppMode.ENHANCED)
        assertFalse(p.getBoolean(TakConnectActivity.KEY_BATTERY_LOCKED, true))
    }

    /** ⚠ The other half of "only ever lock" — a lock the pilot
     *  SET while in BASIC must not be cleared by a snapshot that had it off. */
    @Test
    fun `a snapshot never unlocks a lock set while in BASIC`() {
        val p = elevated().put(TakConnectActivity.KEY_BATTERY_LOCKED, false)
        AppModeTransition.apply(p, AppMode.BASIC)
        p.edit().putBoolean(TakConnectActivity.KEY_BATTERY_LOCKED, true).apply()
        AppModeTransition.apply(p, AppMode.ENHANCED)
        assertTrue(p.getBoolean(TakConnectActivity.KEY_BATTERY_LOCKED, false))
    }

    @Test
    fun `an advertisement chosen while in BASIC survives the return`() {
        val p = elevated()
        AppModeTransition.apply(p, AppMode.BASIC)
        p.edit().putString(adv1, VideoServersActivity.ADV_SELF).apply()
        AppModeTransition.apply(p, AppMode.ENHANCED)
        assertEquals(VideoServersActivity.ADV_SELF, p.getString(adv1, null))
    }

    @Test
    fun `a first-run BASIC controller returns to ENHANCED with nothing restored`() {
        val p = FakePrefs(AppMode.BASIC)
        var redialled = false
        AppModeTransition.apply(p, AppMode.ENHANCED) { redialled = true }
        assertEquals(AppMode.ENHANCED, AppMode.of(p))
        assertFalse(p.getBoolean(chb, false))
        assertFalse(redialled)
    }

    @Test
    fun `an Elevated account that was off before BASIC is not switched on by the return`() {
        val p = elevated().put(chb, false)
        AppModeTransition.apply(p, AppMode.BASIC)
        var redialled = false
        AppModeTransition.apply(p, AppMode.ENHANCED) { redialled = true }
        assertFalse(p.getBoolean(chb, true))
        assertFalse(redialled)
    }

    // ---- The boot-time backstop ----

    /** ⚠ Drift repaired, and the plain key re-derived BY THIS CALL — the drifted plain value
     *  is planted after BASIC, so no earlier mirror can be what makes the assertion pass. */
    @Test
    fun `enforcing BASIC repairs drift, re-mirrors, and leaves the snapshot alone`() {
        val p = elevated()
        AppModeTransition.apply(p, AppMode.BASIC)
        p.edit().putBoolean(chb, true).putInt(slot, 2)
            .putString(adv1, VideoServersActivity.ADV_OTHER)
            .putBoolean("video_adv_on", true).putString("video_adv_host", "two.example.com").apply()
        AppModeTransition.enforceBasicInvariants(p)
        assertFalse(p.getBoolean(chb, true))
        assertEquals(1, p.getInt(slot, -1))
        assertEquals(VideoServersActivity.ADV_OFF, p.getString(adv1, null))
        assertFalse("the plain key must be re-derived by enforceBasicInvariants itself",
            p.getBoolean("video_adv_on", true))
        assertTrue(p.getBoolean(AppModeTransition.KEY_SNAP_CHB_ENABLED, false))
        assertEquals(2, p.getInt(AppModeTransition.KEY_SNAP_ACTIVE_SLOT, -1))
    }

    @Test
    fun `enforcing BASIC on an ENHANCED controller changes nothing`() {
        val p = elevated()
        AppModeTransition.enforceBasicInvariants(p)
        assertTrue(p.getBoolean(chb, false))
        assertEquals(2, p.getInt(slot, -1))
    }

    // ---- The effective advertisement ----

    /** ⚠ From the ACTIVE slot's own key — the first version read slot 1's whatever was active. */
    @Test
    fun `the effective advertisement is read from the active slot`() {
        assertEquals(1, AppModeTransition.effectiveAdvert(1, "self", "off"))
        assertEquals(2, AppModeTransition.effectiveAdvert(1, "other", "off"))
        assertNull(AppModeTransition.effectiveAdvert(1, "off", "self"))
        assertEquals("slot 2's key governs when slot 2 is active",
            2, AppModeTransition.effectiveAdvert(2, "other", "self"))
        assertNull(AppModeTransition.effectiveAdvert(2, "self", "off"))
        assertEquals(1, AppModeTransition.effectiveAdvert(2, "off", "other"))
        assertEquals("unset is self", 1, AppModeTransition.effectiveAdvert(1, null, null))
    }

    // ---- What the pilot is told ----

    private fun f(
        enrolled: Boolean = false, inUse: Boolean = false, returns: Boolean = false,
        now: Int? = 1, after: Int? = 1, pubNow: Int = 1, pubAfter: Int = 1, locks: Int = 0,
    ) = AppModeTransition.Facts(enrolled, inUse, returns, now, after, pubNow, pubAfter, locks)

    @Test
    fun `to BASIC with an Elevated audience names everything that audience loses`() {
        val t = AppModeTransition.describeAudienceChange(AppMode.BASIC, f(enrolled = true, inUse = true))
        assertTrue(t.contains("position"))
        assertTrue(t.contains("markers"))
        assertTrue(t.contains("live-video link"))
        assertTrue(t.contains("will lose it"))
    }

    /** ⚠ No video link is promised when none will be advertised. */
    @Test
    fun `to BASIC does not promise a video link that will not be advertised`() {
        val t = AppModeTransition.describeAudienceChange(AppMode.BASIC,
            f(enrolled = true, inUse = true, now = 2, after = null))
        assertFalse(t.contains("live-video link, which"))
        assertTrue(t.contains("switched OFF"))
    }

    @Test
    fun `to BASIC with no Elevated audience does not claim anyone loses the aircraft`() {
        val t = AppModeTransition.describeAudienceChange(AppMode.BASIC, f())
        assertFalse(t.contains("will lose it"))
        assertTrue(t.contains("does not change"))
    }

    /** ⚠ A change that STARTS an advertisement must say so — it carries the credentials. */
    @Test
    fun `a change that starts an advertisement says so`() {
        val t = AppModeTransition.describeAudienceChange(AppMode.BASIC,
            f(now = null, after = 1, pubNow = 2, pubAfter = 1))
        assertTrue(t.contains("START carrying a video address"))
        assertTrue(t.contains("publish to Server 1 instead of Server 2"))
    }

    @Test
    fun `both directions say what happens to the passwords`() {
        assertTrue(AppModeTransition.describeAudienceChange(AppMode.BASIC, f())
            .contains("unlock with no password"))
        assertTrue(AppModeTransition.describeAudienceChange(AppMode.ENHANCED, f())
            .contains("password again"))
        assertTrue(AppModeTransition.describeAudienceChange(AppMode.ENHANCED, f(locks = 2))
            .contains("2 locks that were set before Basic are set again"))
    }

    @Test
    fun `to ENHANCED with an account coming back says the team will lose the video`() {
        val t = AppModeTransition.describeAudienceChange(AppMode.ENHANCED,
            f(enrolled = true, returns = true))
        assertTrue(t.contains("will lose it"))
    }

    /** ⚠ An account that is set up but switched off is not "no account" — tell them to switch it
     *  on, not to go and enroll one. */
    @Test
    fun `to ENHANCED with an account set up but off says switch it on`() {
        val t = AppModeTransition.describeAudienceChange(AppMode.ENHANCED, f(enrolled = true))
        assertTrue(t.contains("set up but stays switched off"))
        assertFalse(t.contains("until one is set up"))
        assertFalse(t.contains("will lose it"))
    }

    @Test
    fun `to ENHANCED with no account at all does not promise a narrowing`() {
        val t = AppModeTransition.describeAudienceChange(AppMode.ENHANCED, f())
        assertFalse(t.contains("will lose it"))
        assertTrue(t.contains("No Elevated account is set up"))
    }

    /** ⚠ From a BASIC that advertises nothing, the return STARTS the link —
     *  "keeps getting" would describe it as no change. */
    @Test
    fun `to ENHANCED with no account does not say keeps when nothing is advertised now`() {
        val t = AppModeTransition.describeAudienceChange(AppMode.ENHANCED, f(now = null, after = 2))
        assertFalse(t.contains("keeps getting"))
        assertTrue(t.contains("will get the video link"))
        assertTrue(t.contains("START carrying a video address"))
    }

    /** ⚠ Nobody can lose a video they do not have. */
    @Test
    fun `to ENHANCED does not say a team will lose a video nobody has now`() {
        val t = AppModeTransition.describeAudienceChange(AppMode.ENHANCED,
            f(enrolled = true, returns = true, now = null, after = 1))
        assertFalse(t.contains("will lose it"))
    }

    /** ⚠ The return WIDENS who gets the track, and both branches say so. */
    @Test
    fun `to ENHANCED with an account coming back says its channels get the aircraft again`() {
        for (after in listOf(1, null)) {
            val t = AppModeTransition.describeAudienceChange(AppMode.ENHANCED,
                f(enrolled = true, returns = true, after = after))
            assertTrue("after=$after", t.contains("its channels get this aircraft again"))
            assertTrue("after=$after", t.contains("position"))
        }
    }

    /** A broadcast outlives the flight screen, and BASIC ends it. */
    @Test
    fun `to BASIC with a broadcast running says it will end`() {
        val running = AppModeTransition.Facts(false, false, false, 1, 1, 1, 1, 0, emergencyActive = true)
        assertTrue(AppModeTransition.describeAudienceChange(AppMode.BASIC, running)
            .contains("Emergency Broadcast that is running now will END"))
        assertFalse(AppModeTransition.describeAudienceChange(AppMode.BASIC, f())
            .contains("Emergency Broadcast"))
    }

    /** ⚠ A publish slot with no host streams nothing, so nothing is advertised. */
    @Test
    fun `a publish slot with no host advertises nothing`() {
        val p = elevated().put(VideoServersActivity.vKey(2, "host"), "")
        assertNull(AppModeTransition.facts(p, true, AppMode.ENHANCED).advertNow)
    }

    @Test
    fun `the facts are read as the disclosure needs them`() {
        val toBasic = AppModeTransition.facts(elevated(), hasElevatedEnrollment = true, AppMode.BASIC)
        assertTrue(toBasic.elevatedInUse)
        assertEquals("slot 2 active, advertising 'self'", 2, toBasic.advertNow)
        assertEquals("after: slot 1, whose 'other' is forced off", null, toBasic.advertAfter)
        assertEquals(2, toBasic.publishNow)
        assertEquals(1, toBasic.publishAfter)
        val loggedOut = elevated().put("chb_logged_out", true)
        assertFalse(AppModeTransition.facts(loggedOut, true, AppMode.BASIC).elevatedInUse)
    }

    @Test
    fun `the facts for a return describe what the snapshot will give back`() {
        val p = elevated()
        AppModeTransition.apply(p, AppMode.BASIC)
        p.edit().putBoolean(TakConnectActivity.KEY_TAK_LOCKED, false).apply()
        val back = AppModeTransition.facts(p, hasElevatedEnrollment = true, AppMode.ENHANCED)
        assertTrue(back.elevatedReturns)
        assertEquals(2, back.publishAfter)
        assertEquals(1, back.locksReturn)
    }
}
