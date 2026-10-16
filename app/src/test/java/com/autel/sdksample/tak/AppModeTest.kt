package com.autel.sdksample.tak

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the first-run rule — the single most dangerous part of the Basic/Enhanced feature.
 *
 * See [AppMode.decideFirstRun] for why the rule has four signs.
 *
 * ⚠ **THE UPGRADE IS THE HAZARD, NOT THE FEATURE.** This version installs over earlier builds on
 * controllers that are already enrolled on both accounts. A mode read from a
 * `getBoolean(KEY, false)` default would put every one of them into BASIC on first launch, with
 * no pilot action and no screen saying so — moving the video link, media-server credentials
 * included, from one channel to every active channel on the net.
 *
 * The rule these tests hold is therefore: **a controller that shows ANY sign of having been set
 * up stays ENHANCED.**
 */
class AppModeTest {

    // ---- The first-run decision ----

    /** ⚠ THE ONE THAT MATTERS. An enrolled controller must not be flipped by an upgrade. */
    @Test
    fun `an enrolled controller upgrades to ENHANCED`() {
        assertEquals(AppMode.ENHANCED, AppMode.decideFirstRun(
            hasElevated = true, anySectionLocked = false, secondVideoServerInUse = false, elevatedLoggedOut = false))
    }

    /**
     * A locked controller was deliberately set up by somebody, thus it is not a fresh device —
     * even if its Elevated account was never enrolled. ⚠ This is the case that catches a
     * single-account locked controller, which has no cert B to detect.
     */
    @Test
    fun `a locked controller upgrades to ENHANCED even with no Elevated account`() {
        assertEquals(AppMode.ENHANCED, AppMode.decideFirstRun(
            hasElevated = false, anySectionLocked = true, secondVideoServerInUse = false, elevatedLoggedOut = false))
    }

    @Test
    fun `an enrolled and locked controller upgrades to ENHANCED`() {
        assertEquals(AppMode.ENHANCED, AppMode.decideFirstRun(
            hasElevated = true, anySectionLocked = true, secondVideoServerInUse = false, elevatedLoggedOut = false))
    }

    /**
     * ⚠ **THE CASE A TWO-SIGN RULE MISSES.** No Elevated account, no lock — and the
     * controller publishes to, or advertises through, its second video server. Under such a
     * rule it upgrades into BASIC, its Server 2 card disappears, and Server 2 stays on the
     * wire, because the first-run write never runs the transition that would force it back.
     */
    @Test
    fun `a controller using its second video server upgrades to ENHANCED`() {
        assertEquals(AppMode.ENHANCED, AppMode.decideFirstRun(
            hasElevated = false, anySectionLocked = false, secondVideoServerInUse = true, elevatedLoggedOut = false))
    }

    /**
     * ⚠ **THE FOURTH SIGN.** A two-account controller logged out of both accounts at the
     * upgrade has no enrollment and maybe no lock. In BASIC its Elevated section is hidden, and
     * nothing reminds the pilot re-enrolling the Standard account that the second one is gone.
     */
    @Test
    fun `a controller whose Elevated account was logged out upgrades to ENHANCED`() {
        assertEquals(AppMode.ENHANCED, AppMode.decideFirstRun(
            hasElevated = false, anySectionLocked = false, secondVideoServerInUse = false,
            elevatedLoggedOut = true))
    }

    /** Only a device with no sign of setup at all starts in BASIC. */
    @Test
    fun `a brand new controller starts in BASIC`() {
        assertEquals(AppMode.BASIC, AppMode.decideFirstRun(
            hasElevated = false, anySectionLocked = false, secondVideoServerInUse = false, elevatedLoggedOut = false))
    }

    // ---- The third sign, read from the store ----

    @Test
    fun `an active slot 2 counts as the second server in use`() {
        assertTrue(AppMode.secondVideoServerInUse(
            FakePrefs().put(VideoServersActivity.KEY_V_ACTIVE_SLOT, 2)))
    }

    /** ⚠ The one a "check the active slot" reading misses — the CoT names Server 2 anyway. */
    @Test
    fun `an advertisement through server 2 counts as the second server in use`() {
        assertTrue(AppMode.secondVideoServerInUse(
            FakePrefs().put(VideoServersActivity.vKey(1, "advertise"),
                VideoServersActivity.ADV_OTHER)))
    }

    @Test
    fun `a configured server 2 counts as the second server in use`() {
        assertTrue(AppMode.secondVideoServerInUse(
            FakePrefs().put(VideoServersActivity.vKey(2, "host"), "media.example.com")))
    }

    @Test
    fun `an untouched store shows no second server`() {
        assertFalse(AppMode.secondVideoServerInUse(FakePrefs()))
        // A blank host is not configuration — a field that was cleared again.
        assertFalse(AppMode.secondVideoServerInUse(
            FakePrefs().put(VideoServersActivity.vKey(2, "host"), "   ")))
    }

    // ---- ensureMigrated writes once, and then never again ----

    /** ⚠ Written EXPLICITLY, so no later read ever falls back on a default. */
    @Test
    fun `first run writes the mode and the migrated flag`() {
        val p = FakePrefs()
        AppMode.ensureMigrated(p, hasElevated = true, anySectionLocked = false,
            secondVideoServerInUse = false, elevatedLoggedOut = false)
        assertEquals(AppMode.ENHANCED.name, p.getString(AppMode.KEY_MODE, null))
        assertTrue(p.getBoolean(AppMode.KEY_MODE_MIGRATED, false))
    }

    /**
     * ⚠ The decision is made ONCE. A pilot who later chooses BASIC must not be put back in
     * ENHANCED by the next launch re-reading the enrollment, and vice versa.
     */
    @Test
    fun `a second run never overrides a mode that was chosen`() {
        val p = FakePrefs(AppMode.BASIC).put(AppMode.KEY_MODE_MIGRATED, true)
        AppMode.ensureMigrated(p, hasElevated = true, anySectionLocked = true,
            secondVideoServerInUse = true, elevatedLoggedOut = true)
        assertEquals(AppMode.BASIC, AppMode.of(p))
    }

    // ---- settle(): the whole boot step, in its load-bearing order ----

    /**
     * ⚠ **THE ORDER IS THE POINT.** A pre-slot controller holds its video
     * server on the plain keys only. The migration must copy them into slot 1 BEFORE anything
     * mirrors, or the mirror copies an EMPTY slot over the real host — data loss on upgrade.
     */
    @Test
    fun `settle migrates a pre-slot controller before anything mirrors over it`() {
        val p = FakePrefs()
            .put("video_host", "media.example.com").put("video_streamid", "UAS-1")
            .put("video_user", "pilot").put("video_pass", "s3cret")
        AppMode.settle(p, hasElevated = false)
        assertEquals("media.example.com", p.getString(VideoServersActivity.vKey(1, "host"), null))
        assertEquals("the plain host must survive the boot mirror",
            "media.example.com", p.getString("video_host", null))
        assertEquals("UAS-1", p.getString("video_streamid", null))
    }

    /** A brand-new install settles to BASIC, with its invariants in force. */
    @Test
    fun `settle puts a brand-new controller in BASIC`() {
        val p = FakePrefs()
        AppMode.settle(p, hasElevated = false)
        assertEquals(AppMode.BASIC, AppMode.of(p))
        assertTrue(p.getBoolean(AppMode.KEY_MODE_MIGRATED, false))
    }

    /**
     * ⚠ [AppMode.decideFirstRun] is held above, but that alone does not hold that
     * [AppMode.settle] PASSES each sign to it. Each sign alone, through the boot step, must
     * keep an upgrading controller in ENHANCED; with none it goes BASIC.
     */
    @Test
    fun `settle passes each of the four first-run signs to the decision`() {
        fun settled(hasElevated: Boolean = false, setUp: FakePrefs.() -> Unit = {}): AppMode {
            val p = FakePrefs().slotsMigrated().apply(setUp)
            AppMode.settle(p, hasElevated)
            return AppMode.of(p)
        }
        assertEquals("an Elevated enrollment", AppMode.ENHANCED, settled(hasElevated = true))
        for (lock in AppModeTransition.LOCK_KEYS) {
            assertEquals("lock $lock", AppMode.ENHANCED, settled { put(lock, true) })
        }
        assertEquals("a second video server", AppMode.ENHANCED,
            settled { put(VideoServersActivity.vKey(2, "host"), "two.example.com") })
        assertEquals("a logged-out Elevated account", AppMode.ENHANCED,
            settled { put("chb_logged_out", true) })
        assertEquals("no sign at all", AppMode.BASIC, settled())
    }

    /**
     * ⚠ Without this test the boot call to enforceBasicInvariants could be deleted with every
     * other test green. A BASIC controller that has drifted is repaired AT BOOT, through settle.
     */
    @Test
    fun `settle enforces the BASIC invariants at boot`() {
        val p = FakePrefs(AppMode.BASIC).slotsMigrated().put(AppMode.KEY_MODE_MIGRATED, true)
            .put(TakAutoConnect.KEY_CHB_ENABLED, true)
            .put(VideoServersActivity.KEY_V_ACTIVE_SLOT, 2)
            .put(VideoServersActivity.vKey(1, "host"), "one.example.com")
            .put(VideoServersActivity.vKey(2, "host"), "two.example.com")
            .put("video_host", "two.example.com")
        AppMode.settle(p, hasElevated = true)
        assertEquals(false, p.getBoolean(TakAutoConnect.KEY_CHB_ENABLED, true))
        assertEquals(1, p.getInt(VideoServersActivity.KEY_V_ACTIVE_SLOT, 0))
        assertEquals("the plain keys follow", "one.example.com", p.getString("video_host", null))
    }

    /**
     * ⚠ The boot mirror runs in ENHANCED too. An older, drifted Pre-Flight mirror can have left
     * one server's read leg on another's host; without this it would stay on the wire until a
     * screen happened to mirror.
     */
    @Test
    fun `settle re-derives an ENHANCED controller's drifted plain keys`() {
        val p = FakePrefs(AppMode.ENHANCED).put(AppMode.KEY_MODE_MIGRATED, true)
            .put("video_slots_migrated", true).put("video_port_per_protocol_migrated", true)
            .put(VideoServersActivity.KEY_V_ACTIVE_SLOT, 2)
            .put(VideoServersActivity.vKey(2, "host"), "two.example.com")
            .put(VideoServersActivity.vKey(2, "adv_transport"), "rtsp")
            // The drift: the plain read leg still carries server 1's SRT.
            .put("video_adv_host", "two.example.com").put("video_adv_transport", "srt")
        AppMode.settle(p, hasElevated = true)
        assertEquals(AppMode.ENHANCED, AppMode.of(p))
        assertEquals("rtsp", p.getString("video_adv_transport", null))
    }

    /**
     * ⚠ Why TestApplication GUARDS this call. Android's preferences throw on a key stored
     * as a different type; this step runs at every process start, so unguarded a drifted key
     * would crash every launch, Home and the flight screen included.
     */
    @Test(expected = ClassCastException::class)
    fun `settle throws on a drifted key type, which is why its caller is guarded`() {
        // The migrations must already have run: on a fresh store the slot migration WRITES the
        // active slot as an Int first, which would mask the drift this test is about.
        AppMode.settle(
            FakePrefs().put("video_slots_migrated", true).put("video_port_per_protocol_migrated", true)
                .put(VideoServersActivity.KEY_V_ACTIVE_SLOT, "2"),
            hasElevated = false)
    }

    /**
     * ⚠ After the guarded failure above, Pre-Flight still mirrors on open.
     * On a controller from before the slots, the plain keys are the only copy of the
     * configuration, and a mirror of the empty slots would blank them.
     */
    @Test
    fun `a failed slot migration leaves the plain video keys alone`() {
        val p = FakePrefs()
            .put("video_host", "media.example.com").put("video_streamid", "uas1")
            .put("video_user", "pilot")
            .put("video_port", "8554")              // the drift: a String where an Int belongs
        runCatching { AppMode.settle(p, hasElevated = false) }
        VideoServersActivity.mirrorActiveSlot(p)   // what Pre-Flight's onCreate and onResume do
        assertEquals("media.example.com", p.getString("video_host", null))
        assertEquals("uas1", p.getString("video_streamid", null))
        assertEquals("pilot", p.getString("video_user", null))
    }

    // ---- The predicates ----

    /**
     * ⚠ EVERY FEATURE READS A PREDICATE, AND THE PREDICATES AGREE WITH THE MODE. These look
     * trivial and they are the point: a later change that makes one feature read the enum
     * directly, or invert one of these, is how BASIC starts sending video to everyone while the
     * other predicates still look right.
     */
    @Test
    fun `BASIC forbids every Enhanced-only feature`() {
        val p = FakePrefs(AppMode.BASIC)
        assertEquals(false, AppMode.elevatedAccountAllowed(p))
        assertEquals(false, AppMode.secondVideoServerAllowed(p))
        assertEquals(false, AppMode.emergencyBroadcastAllowed(p))
    }

    @Test
    fun `ENHANCED allows every Enhanced-only feature`() {
        val p = FakePrefs(AppMode.ENHANCED)
        assertEquals(true, AppMode.elevatedAccountAllowed(p))
        assertEquals(true, AppMode.secondVideoServerAllowed(p))
        assertEquals(true, AppMode.emergencyBroadcastAllowed(p))
    }

    /**
     * ⚠ **A DESIGN DECISION, AND IT IS THE REASON THE MODE ITSELF IS PASSWORD-GATED.** In BASIC the three configuration locks take no password: the tick alone
     * stops an accidental tap. The password survives on the one control that changes who
     * receives this aircraft. If this test is ever changed to make BASIC ask for a password,
     * read the note on [AppMode.unlockNeedsPassword] before touching it.
     */
    @Test
    fun `BASIC unlocks a configuration section with no password`() {
        assertEquals(false, AppMode.unlockNeedsPassword(FakePrefs(AppMode.BASIC)))
        assertEquals(true, AppMode.unlockNeedsPassword(FakePrefs(AppMode.ENHANCED)))
    }

    /** An unreadable or absent value is ENHANCED — the safe side, which withholds video. */
    @Test
    fun `an unset mode reads as ENHANCED`() {
        assertEquals(AppMode.ENHANCED, AppMode.of(FakePrefs(null)))
        assertEquals(AppMode.ENHANCED, AppMode.of(FakePrefs(raw = "nonsense")))
    }

    // ---- The confirmation dialog's title ----

    /**
     * ⚠ **AN ESCAPED TEMPLATE BREAKS THIS DIALOG'S TITLE.** A literal such as
     * `"Change to ${'$'}{target.name…} mode?"` — the `${'$'}` escapes the template, so the pilot
     * reads the raw Kotlin expression where "Basic" should be. Proved by compiling and running
     * that exact literal. The title is built in one function here so this can
     * hold it, on the dialog that changes who receives the aircraft.
     */
    @Test
    fun `the mode dialog title names the mode in words`() {
        assertEquals("Change to Basic mode?", AppModeTransition.dialogTitle(AppMode.BASIC))
        assertEquals("Change to Elevated mode?", AppModeTransition.dialogTitle(AppMode.ENHANCED))
        assertFalse(AppModeTransition.dialogTitle(AppMode.BASIC).contains("$"))
        assertFalse(AppModeTransition.dialogTitle(AppMode.BASIC).contains("{"))
    }
}
