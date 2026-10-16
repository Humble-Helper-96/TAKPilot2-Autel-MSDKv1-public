package com.autel.sdksample.tak

import android.content.Context
import android.content.SharedPreferences

/**
 * ENHANCED or BASIC — which application this controller is.
 *
 * ⚠ **THIS IS NOT A PREFERENCE. IT DECIDES WHO RECEIVES THIS AIRCRAFT.** In ENHANCED the
 * Elevated account carries the live-video link to one channel while the Standard account
 * carries the position, the camera point and the markers to everyone else. In BASIC there is
 * ONE account, thus every active channel gets everything, the video link included — and that
 * link carries the media-server credentials.
 *
 * ⚠ **THE ELEVATED SOCKET IS NOT "THE VIDEO CONNECTION". IT IS A WHOLE AUDIENCE.** A supervisor
 * who watches this aircraft on the Elevated channel loses the TRACK, the camera point (within
 * [com.taklite.client.tak.CotBuilder.SENSOR_POINT_STALE_MS], 15 s) and every marker the pilot
 * drops when that socket closes — whether or not anything is streaming. Any wording shown to a
 * pilot says "who receives this aircraft", never "who can play the video".
 *
 * ## ⚠ THE PREDICATES ARE THE WHOLE FEATURE
 *
 * BASIC is NOT "hide four things". Four independent hiding jobs across five activities is how
 * one screen keeps a rule while another quietly stops — the same shape as the copied
 * configuration lock that produced [ConfigLock]. Everything reads [elevatedAccountAllowed],
 * [secondVideoServerAllowed], [emergencyBroadcastAllowed] and [unlockNeedsPassword], and nothing
 * outside this file tests the mode directly.
 *
 * ## ⚠ THE MODE IS WRITTEN ON FIRST RUN, NEVER DEFAULTED
 *
 * See [ensureMigrated]. A `getBoolean(KEY, false)` here would flip every already-enrolled
 * controller to BASIC on the install of this version, with no pilot action and no
 * screen saying so.
 */
enum class AppMode(
    /**
     * The word the PILOT sees. ⚠ NOT the stored value: [name] is what is written to the
     * preferences (KEY_MODE), and every controller that ran an earlier build already holds
     * "ENHANCED" — renaming the constant would read every one of them as an unknown value. On
     * screen the mode is called "Elevated": it is the mode that uses the account with elevated
     * permission.
     */
    val label: String,
) {
    /** The two-account build: two TAK accounts, two video servers, Emergency Broadcast. On screen,
     *  "Elevated". */
    ENHANCED("Elevated"),

    /** One TAK account, one video server, no Emergency Broadcast. */
    BASIC("Basic");

    companion object {

        /** @see ensureMigrated — never read with a default, always written first. */
        internal const val KEY_MODE = "app_mode"

        /** Set once, by [ensureMigrated], so the decision below cannot run twice. */
        internal const val KEY_MODE_MIGRATED = "app_mode_migrated"

        private const val TAG = "AppMode"

        fun of(context: Context): AppMode = of(prefs(context))

        /**
         * ⚠ An absent or unreadable value is ENHANCED — the side that WITHHOLDS the video from
         * the wider net. [ensureMigrated] means this default is never actually relied on for an
         * upgraded controller; it is the answer for a damaged VALUE. ⚠ A whole preferences FILE
         * that cannot be read is different: Android loads it EMPTY, and [settle] then treats
         * the controller as a fresh install, which is BASIC. Every
         * setting is lost with it, so the controller must be set up again in any case.
         */
        fun of(prefs: SharedPreferences): AppMode =
            if (prefs.getString(KEY_MODE, ENHANCED.name) == BASIC.name) BASIC else ENHANCED

        // ---- The predicates. Every call site reads one of these, never the enum. ----

        /**
         * May a second TAK account (the Elevated / video-channel connection) exist at all?
         *
         * ⚠ **THE GATE IS [ElevatedChannel.dialNow], AND EVERY DIAL GOES THROUGH IT.** Boot,
         * both screen resumes, the in-flight TAK badge and [TakServerActivity]'s connect paths
         * all call `ElevatedChannel.dial`, which reads this at the moment of the dial and again
         * after it. So a pilot who taps the badge to clear a link wobble mid-flight cannot bring
         * the split back with the stream up — which would silently take the feed from the
         * Standard channels. A new dial site must call `ElevatedChannel.dial`; it must not read
         * this predicate itself and then dial the core directly.
         */
        fun elevatedAccountAllowed(prefs: SharedPreferences): Boolean = of(prefs) == ENHANCED

        /** May the Video Servers screen offer a second server card, or Pre-Flight a choice? */
        fun secondVideoServerAllowed(prefs: SharedPreferences): Boolean = of(prefs) == ENHANCED

        /**
         * May Emergency Broadcast be started?
         *
         * It exists to push the video past the split for 15 minutes. With one account every
         * active channel already has the link, thus in BASIC the control would change nothing.
         */
        fun emergencyBroadcastAllowed(prefs: SharedPreferences): Boolean = of(prefs) == ENHANCED

        /**
         * Does unlocking a configuration section ask for the password?
         *
         * ⚠ **BASIC: NO (design decision).** The tick alone stops an accidental tap, which
         * is what the lock is for on a single-operator aircraft. The password stays on the ONE
         * control that changes who receives this aircraft — the mode itself — and a fresh-install
         * BASIC user never meets a password at all.
         *
         * ⚠ **THE MODE SWITCH IS THEREFORE THE ONLY GATE, AND IT GUARDS ITSELF IN BOTH
         * DIRECTIONS.** ENHANCED -> BASIC must ask, or it is a free route to a password-less
         * unlock on a locked two-account controller. BASIC -> ENHANCED must ask too: it is not the
         * safe direction, because it re-narrows the audience and a team that had the video loses
         * it. This departs from [ConfigLock]'s own asymmetry (locking never asks) on purpose —
         * that rule holds because locking is safe, and here neither direction is.
         */
        fun unlockNeedsPassword(prefs: SharedPreferences): Boolean = of(prefs) == ENHANCED

        // ---- First run ----

        /**
         * Writes the mode EXPLICITLY the first time this version runs, and never again.
         *
         * ⚠ **THE UPGRADE IS THE DANGEROUS PART, NOT THE FEATURE.** This version installs over
         * earlier builds on controllers that are already enrolled on both accounts. A mode read
         * from a `getBoolean` default would move every one of them to BASIC on first launch —
         * each controller's video audience going from one channel to the whole net, from an
         * installer's action, with nothing on any screen saying so.
         *
         * An already-configured controller is ENHANCED. Only a controller that shows no sign of
         * ever having been set up starts in BASIC.
         *
         * ⚠ **THIS WRITES THE MODE AND NOTHING ELSE — IT DOES NOT RUN [AppModeTransition].** That
         * is exactly why "a second video server is in use" has to be a sign of setup below: a
         * controller that arrived in BASIC here keeps whatever slot it was publishing to and
         * whatever its advertisement pointed at, with no transition to force them back.
         *
         * @param hasElevated [TakMissionManager.hasElevatedEnrollment] — deliberately the
         *   PREFERENCES-ONLY check, wider than the dial's `TakAutoConnect.hasSavedVideoCerts`
         *   (which also needs the files on disk). Wider errs toward ENHANCED, the safe side; do
         *   not narrow it.
         * @param anySectionLocked whether any configuration lock is set. A locked controller was
         *   deliberately set up by somebody, thus it is not a fresh device.
         * @param secondVideoServerInUse see [secondVideoServerInUse]. Passed in, like the other
         *   signs, so the decision stays pure for [AppModeTest].
         */
        fun ensureMigrated(
            prefs: SharedPreferences,
            hasElevated: Boolean,
            anySectionLocked: Boolean,
            secondVideoServerInUse: Boolean,
            elevatedLoggedOut: Boolean,
        ) {
            if (prefs.getBoolean(KEY_MODE_MIGRATED, false)) return
            val mode = decideFirstRun(hasElevated, anySectionLocked, secondVideoServerInUse,
                elevatedLoggedOut)
            prefs.edit()
                .putString(KEY_MODE, mode.name)
                .putBoolean(KEY_MODE_MIGRATED, true)
                .apply()
            com.taklite.util.AppLog.i(
                TAG,
                "first run: mode set to $mode (elevated enrolled=$hasElevated, " +
                    "a section locked=$anySectionLocked, " +
                    "second video server in use=$secondVideoServerInUse, " +
                    "elevated logged out=$elevatedLoggedOut)",
            )
        }

        /**
         * The first-run rule, pure, so [AppModeTest] can hold it.
         *
         * ⚠ **FOUR SIGNS, NOT TWO — "Elevated enrolled OR any lock set" alone has a gap:**
         *  - **A second video server in use or configured.** Without it, a controller with
         *    neither of the first two signs that PUBLISHES TO, or ADVERTISES THROUGH, Server 2
         *    would upgrade into BASIC with Server 2 still on the wire while its card disappeared,
         *    because [ensureMigrated] never runs the transition that would force it back.
         *  - **An Elevated enrollment that was logged out.** Log Out clears BOTH accounts and sets
         *    `chb_logged_out`. A two-account controller caught mid-handover at the upgrade has no
         *    enrollment and maybe no lock, so it would go BASIC — where the Elevated section is
         *    HIDDEN, and the pilot re-enrolling the Standard account would have nothing reminding
         *    them the second account was gone.
         * Each sign only ever WIDENS "stays ENHANCED", which is the pre-mode behaviour and the
         * safe side.
         */
        fun decideFirstRun(
            hasElevated: Boolean,
            anySectionLocked: Boolean,
            secondVideoServerInUse: Boolean,
            elevatedLoggedOut: Boolean,
        ): AppMode =
            if (hasElevated || anySectionLocked || secondVideoServerInUse || elevatedLoggedOut)
                ENHANCED else BASIC

        /**
         * Does this controller use its second video server in any way the wire can see — or
         * has somebody configured one?
         *
         * Three readings, any one is enough:
         *  - the active slot is 2 (the stream PUBLISHES there);
         *  - slot 1 advertises "other" (the CoT names Server 2 whatever is published);
         *  - Server 2 has a host (somebody set it up; hiding it would lose that configuration
         *    behind a password the pilot may not have).
         */
        fun secondVideoServerInUse(prefs: SharedPreferences): Boolean =
            prefs.getInt(VideoServersActivity.KEY_V_ACTIVE_SLOT, 1) == 2 ||
                prefs.getString(VideoServersActivity.vKey(1, "advertise"),
                    VideoServersActivity.ADV_SELF) == VideoServersActivity.ADV_OTHER ||
                !(prefs.getString(VideoServersActivity.vKey(2, "host"), "") ?: "").isBlank()

        /**
         * The whole boot step, as a function of the preferences — so its ORDER, which is
         * load-bearing, is held by a test rather than only by a comment. Called from
         * TestApplication, inside a guard: see there.
         *
         * ⚠ **IN THE APPLICATION, NOT IN PRE-FLIGHT.** Run from Pre-Flight's onCreate, it would
         * come after the boot dial, the flight screen and the Field Guide were already reachable
         * — each of them would read the unsettled default until a pilot happened to open
         * Pre-Flight.
         *
         *  1. The video slot migrations FIRST. The first-run rule reads the slots, and the mirror
         *     in 3 would otherwise copy EMPTY slots over a pre-slot controller's real host and
         *     stream id. Both are idempotent behind their own flags.
         *  2. The first-run decision. Writes the mode explicitly, once — never a default.
         *  3. The plain video keys re-derived from the slots — in BASIC through the invariants,
         *     in ENHANCED directly.
         *
         * @param hasElevated [TakMissionManager.hasElevatedEnrollment] — passed in so this stays a
         *   function of the preferences.
         */
        fun settle(prefs: SharedPreferences, hasElevated: Boolean) {
            TakConnectActivity.migrateVideoSlots(prefs)
            TakConnectActivity.migrateVideoProtocolSplit(prefs)
            ensureMigrated(
                prefs,
                hasElevated = hasElevated,
                anySectionLocked = AppModeTransition.LOCK_KEYS.any { prefs.getBoolean(it, false) },
                secondVideoServerInUse = secondVideoServerInUse(prefs),
                elevatedLoggedOut = prefs.getBoolean("chb_logged_out", false),
            )
            if (of(prefs) == BASIC) AppModeTransition.enforceBasicInvariants(prefs)
            else VideoServersActivity.mirrorActiveSlot(prefs)
        }

        internal fun prefs(context: Context): SharedPreferences =
            context.getSharedPreferences(TakConnectActivity.PREFS, Context.MODE_PRIVATE)
    }
}
