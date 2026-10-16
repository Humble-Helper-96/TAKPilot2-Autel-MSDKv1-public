package com.autel.sdksample.tak

import android.content.SharedPreferences
import com.taklite.util.AppLog

/**
 * The ONE place a controller changes between [AppMode.ENHANCED] and [AppMode.BASIC].
 *
 * The writes go in ONE editor, the plain keys are re-mirrored, what BASIC muted is restored on
 * the way back rather than left switched off, and the pilot's disclosure is generated from the
 * state instead of typed.
 *
 * ⚠ **THE ORDER OF THE EFFECTS IS THE SAFETY PROPERTY.** Do not reorder them, and do not
 * perform any of them from a screen — a mode that is right on five screens and wrong on the
 * sixth is the failure this design exists to prevent.
 *
 * ## ⚠ THE MODE IS WRITTEN FIRST, BEFORE ANYTHING IS TORN DOWN
 *
 * Every gate reads the mode — above all the cert-B dial gate, which is [ElevatedChannel.dialNow]
 * (every dial and clear of the Elevated connection runs through [ElevatedChannel], on one
 * thread). Writing the mode first means a resume that lands in the middle of this function finds
 * the gate already closed and declines to re-dial. And the dial reads it AGAIN after dialling,
 * so a dial already past its gate when the mode changes takes the split back down itself.
 *
 * ## ⚠ WHAT THIS DOES **NOT** DO
 *
 * - **It does not delete the Elevated certificate.** BASIC is a MUTE, not a removal. The certs
 *   stay on disk, so the return trip needs no re-enrollment, and `chb_enabled=false` is what
 *   stops the dial. Deleting them also sets `chb_logged_out`, which nothing but a fresh
 *   enrollment clears, and mints a new CSR identity on every round trip.
 * - **It touches nothing under `com.taklite`.** Every verb it needs is already public there.
 * - **It does not write channel activebits.** Those belong to the SHARED account; a write here
 *   would change what every controller on that account receives.
 */
object AppModeTransition {

    private const val TAG = "AppMode"

    // ---- The snapshot BASIC takes of what it mutes, so ENHANCED can give it back. ----
    // Written in the same editor as the mode, so a persisted BASIC always has its snapshot.
    internal const val KEY_SNAP_TAKEN = "app_mode_snap_taken"
    internal const val KEY_SNAP_CHB_ENABLED = "app_mode_snap_chb_enabled"
    internal const val KEY_SNAP_ACTIVE_SLOT = "app_mode_snap_active_slot"
    internal const val KEY_SNAP_S1_ADVERTISE = "app_mode_snap_s1_advertise"
    /** The three locks as they stood before BASIC — see [toEnhanced] for why they come back.
     *  ⚠ The battery lock's key is "battery_levels_locked". */
    internal val LOCK_KEYS = listOf(
        TakConnectActivity.KEY_TAK_LOCKED,
        TakConnectActivity.KEY_VIDEO_LOCKED,
        TakConnectActivity.KEY_BATTERY_LOCKED,
    )
    internal fun snapLockKey(lockKey: String) = "app_mode_snap_$lockKey"

    /**
     * Removes any video address the bridge is still advertising when nothing is streaming.
     *
     * ⚠ A STALE URL CAN OUTLIVE ITS STREAM. A restart that failed used to leave
     * the old streamer's url on the bridge with `VideoStreamerHolder.isActive` false — so the
     * caller's "no stream is live" check passes — and the next bridge start publishes it. In
     * BASIC there is one connection, so that url (a hidden Server 2's host and credentials,
     * possibly) would reach every channel. The streamer clears it on a failed start now; this is
     * the backstop. A seam, so a test can see it is called.
     */
    internal var clearStaleVideoUrl: () -> Unit = {
        if (!VideoStreamerHolder.isActive) TakBridgeHolder.setVideoUrl(null)
    }

    /**
     * Applies [target] and returns true if the mode actually changed.
     *
     * ⚠ **THE CALLER MUST HAVE TAKEN THE PASSWORD ALREADY** (both directions — see
     * [AppMode.unlockNeedsPassword] for why neither direction is the safe one), must refuse in
     * flight or with a stream live, and must rebuild the screen afterwards: the screens wire
     * themselves in `onCreate`, so the controls this changes are stale until rebuilt.
     * ⚠ Rebuild WITHOUT saved state — NOT `recreate()`, whose view-state restore replays the old
     * radios through their listeners and writes the old mode's choices back over this one.
     * See `TakConnectActivity.rebuildWithoutSavedState`.
     *
     * @param redialElevated called on the way to ENHANCED when the Elevated account was on
     *   before BASIC muted it — the caller passes `TakAutoConnect.dialVideoChannelIfAllowed`. A
     *   parameter, not a direct call, so this function stays a pure function of the preferences
     *   that a unit test can drive.
     */
    fun apply(
        prefs: SharedPreferences,
        target: AppMode,
        redialElevated: () -> Unit = {},
    ): Boolean {
        val current = AppMode.of(prefs)
        if (current == target) return false
        AppLog.i(TAG, "mode change: $current -> $target")
        return when (target) {
            AppMode.BASIC -> { toBasic(prefs); true }
            AppMode.ENHANCED -> { toEnhanced(prefs, redialElevated); true }
        }
    }

    private fun toBasic(prefs: SharedPreferences) {
        // 1. EVERY PREFERENCE, IN ONE EDITOR, BEFORE ANYTHING LIVE IS TOUCHED.
        //    Writing the mode, chb_enabled and the slot through separate editors lets a
        //    process death between them persist BASIC with chb_enabled still true
        //    and Server 2 still on the wire — a state TakServerActivity's own dial then acted on.
        //    One editor is atomic. Every pref here is a gate input, and the live teardown below
        //    still runs after all of them.
        val activeSlot = if (prefs.getInt(VideoServersActivity.KEY_V_ACTIVE_SLOT, 1) == 2) 2 else 1
        val s1Advertise = prefs.getString(VideoServersActivity.vKey(1, "advertise"),
            VideoServersActivity.ADV_SELF) ?: VideoServersActivity.ADV_SELF
        val e = prefs.edit()
            .putString(AppMode.KEY_MODE, AppMode.BASIC.name)
            // The snapshot: what BASIC is about to mute, so ENHANCED can unmute it.
            .putBoolean(KEY_SNAP_TAKEN, true)
            .putBoolean(KEY_SNAP_CHB_ENABLED, prefs.getBoolean(TakAutoConnect.KEY_CHB_ENABLED, false))
            .putInt(KEY_SNAP_ACTIVE_SLOT, activeSlot)
            .putString(KEY_SNAP_S1_ADVERTISE, s1Advertise)
        for (k in LOCK_KEYS) e.putBoolean(snapLockKey(k), prefs.getBoolean(k, false))
        forceBasicPrefs(prefs, e)
        e.apply()

        // 2 + 3. The live teardown — the broadcast BEFORE the split, then the Elevated socket —
        //    queued on ElevatedChannel, where every cert-B dial also runs (clearing cert B here,
        //    on the MAIN thread, while the reconnect dials on a worker loses the clear when it
        //    lands before a new client's thread starts in the shared core). The mode is already written, so a dial queued behind this
        //    declines, and one already running finishes before this clears it.
        ElevatedChannel.teardownForBasic()
        clearStaleVideoUrl()

        // 4. ⚠ RE-MIRROR, AND IT IS NOT OPTIONAL. Step 1 edits the PER-SLOT keys,
        //    but the streamer reads the PLAIN video_* keys, which only the mirror writes, and a
        //    pilot can reach LIVE without opening a screen that mirrors. Do not rely on the
        //    COINCIDENCE that Pre-Flight's setup ends in a mirror.
        VideoServersActivity.mirrorActiveSlot(prefs)
    }

    private fun toEnhanced(prefs: SharedPreferences, redialElevated: () -> Unit) {
        // ⚠ BASIC IS A MUTE, SO ENHANCED UNMUTES. Acting only on the way to BASIC would leave
        // chb_enabled false — so after the pilot was told "a team that can see the video now
        // will lose it" and gave the password, NOTHING would narrow, and the video would go on
        // reaching every channel. The snapshot makes the statement true.
        val e = prefs.edit().putString(AppMode.KEY_MODE, AppMode.ENHANCED.name)
        var restoreElevated = false
        if (prefs.getBoolean(KEY_SNAP_TAKEN, false)) {
            restoreElevated = prefs.getBoolean(KEY_SNAP_CHB_ENABLED, false)
            e.putBoolean(TakAutoConnect.KEY_CHB_ENABLED, restoreElevated)
            e.putInt(VideoServersActivity.KEY_V_ACTIVE_SLOT,
                if (prefs.getInt(KEY_SNAP_ACTIVE_SLOT, 1) == 2) 2 else 1)
            // The advertisement comes back ONLY if it is still the value BASIC forced. A pilot
            // who changed it while in BASIC chose something; the snapshot does not get to
            // overrule a choice made after it.
            val advKey = VideoServersActivity.vKey(1, "advertise")
            val snapAdv = prefs.getString(KEY_SNAP_S1_ADVERTISE, null)
            if (snapAdv == VideoServersActivity.ADV_OTHER &&
                prefs.getString(advKey, null) == VideoServersActivity.ADV_OFF
            ) {
                e.putString(advKey, VideoServersActivity.ADV_OTHER)
            }
            // ⚠ THE LOCKS COME BACK. BASIC unlocks with no password, so a lock cleared while in
            // BASIC used to STAY cleared after the return: a managed controller handed back
            // "locked" with every section editable, after the disclosure said the locks would
            // ask for the password again. Each lock that was set before BASIC is set again.
            // ONLY EVER LOCK: setting a lock never asks (it is the safe direction), and a
            // snapshot must never unlock anything.
            var relocked = 0
            for (k in LOCK_KEYS) {
                if (prefs.getBoolean(snapLockKey(k), false) && !prefs.getBoolean(k, false)) {
                    e.putBoolean(k, true); relocked++
                }
                e.remove(snapLockKey(k))
            }
            e.remove(KEY_SNAP_TAKEN).remove(KEY_SNAP_CHB_ENABLED)
                .remove(KEY_SNAP_ACTIVE_SLOT).remove(KEY_SNAP_S1_ADVERTISE)
            AppLog.i(TAG, "mode change: restoring what Basic muted " +
                "(elevated=$restoreElevated, locks set again=$relocked)")
        }
        e.apply()
        clearStaleVideoUrl()
        VideoServersActivity.mirrorActiveSlot(prefs)
        if (restoreElevated) redialElevated()
    }

    /**
     * The BASIC invariants, re-asserted. Preference writes only — no socket, no broadcast.
     *
     * ⚠ **CALLED AT EVERY START, FROM [AppMode.settle].** [toBasic] writes these atomically, so
     * in the ordinary case this changes nothing. It is the backstop for every case it cannot
     * see: a crash before the mirror's write reached the disk, a future code path that writes
     * one of these keys, a controller that arrived in BASIC by the first-run rule rather than by
     * the toggle. It NEVER touches the snapshot: forcing values into it here would overwrite the
     * configuration ENHANCED is meant to give back.
     *
     * ⚠ The caller must have run the video slot migrations first. Mirroring a controller whose
     * slots do not exist yet would copy EMPTY slots over its real host and stream id.
     */
    fun enforceBasicInvariants(prefs: SharedPreferences) {
        if (AppMode.of(prefs) != AppMode.BASIC) return
        val e = prefs.edit()
        forceBasicPrefs(prefs, e)
        e.apply()
        VideoServersActivity.mirrorActiveSlot(prefs)
    }

    /** chb_enabled off, and [forceSingleVideoServer] — into a caller's editor. */
    private fun forceBasicPrefs(prefs: SharedPreferences, e: SharedPreferences.Editor) {
        if (prefs.getBoolean(TakAutoConnect.KEY_CHB_ENABLED, false)) {
            AppLog.i(TAG, "Basic: Elevated account switched off (muted, certificates kept)")
            e.putBoolean(TakAutoConnect.KEY_CHB_ENABLED, false)
        }
        forceSingleVideoServer(prefs, e)
    }

    /**
     * Pins BASIC to one video server, in the two ways that matter.
     *
     * ⚠ **THE ACTIVE SLOT ALONE IS NOT ENOUGH, AND THAT IS THE TRAP.** Slot 1 can carry
     * `advertise = "other"`, which resolves the WHOLE advertisement — host, port, login and read
     * passphrase — from slot 2. Hiding slot 2 while leaving that set puts a server the pilot can
     * no longer see, or edit, on the team's CoT.
     *
     * ⚠ **A STORED "other" BECOMES OFF, NEVER "self".** Self would advertise slot 1's own
     * address, which was never chosen for this and may not be reachable from the team — a
     * plausible address that silently does not play is worse than no advertisement, which the
     * pilot can see is missing.
     *
     * ⚠ PER-SLOT keys only. The callers in this file mirror afterwards; any other caller must
     * mirror too, or the stream never sees it.
     */
    internal fun forceSingleVideoServer(prefs: SharedPreferences, e: SharedPreferences.Editor) {
        if (prefs.getInt(VideoServersActivity.KEY_V_ACTIVE_SLOT, 1) != 1) {
            AppLog.i(TAG, "Basic: active video server forced to 1")
            e.putInt(VideoServersActivity.KEY_V_ACTIVE_SLOT, 1)
        }
        val advKey = VideoServersActivity.vKey(1, "advertise")
        if (prefs.getString(advKey, VideoServersActivity.ADV_SELF) ==
            VideoServersActivity.ADV_OTHER
        ) {
            AppLog.i(TAG, "Basic: advertisement was 'other' (a hidden server) — set to off")
            e.putString(advKey, VideoServersActivity.ADV_OFF)
        }
    }

    /** Convenience for tests and single callers: forces and applies, but does NOT mirror. */
    internal fun forceSingleVideoServer(prefs: SharedPreferences) {
        val e = prefs.edit()
        forceSingleVideoServer(prefs, e)
        e.apply()
    }

    // ---- What the pilot is told ----

    /**
     * Which server the CoT advertises, or null when the advertisement is off — computed EXACTLY
     * as [VideoServersActivity.mirrorActiveSlot] computes it: from the ACTIVE slot's own
     * `advertise` key. Pure, so the disclosure and the summaries can never disagree with the
     * wire.
     */
    internal fun effectiveAdvert(
        activeSlot: Int, adv1: String?, adv2: String?,
        host1: String? = null, host2: String? = null, hostsKnown: Boolean = false,
    ): Int? {
        val slot = if (activeSlot == 2) 2 else 1
        // ⚠ A PUBLISH SLOT WITH NO HOST STREAMS NOTHING: LIVE cannot build a configuration, so
        // nothing is advertised whatever the advertise key says.
        if (hostsKnown && (if (slot == 2) host2 else host1).isNullOrBlank()) return null
        return when (if (slot == 2) adv2 else adv1) {
            VideoServersActivity.ADV_OFF -> null
            VideoServersActivity.ADV_OTHER -> if (slot == 1) 2 else 1
            else -> slot // "self", and the unset default
        }
    }

    /**
     * The facts the disclosure depends on — what holds NOW, and what will hold AFTER a change to
     * the given target, read before the change. A value type, so the wording can be held by a
     * test without a controller.
     *
     * @param elevatedEnrolled an Elevated enrollment exists and is not logged out.
     * @param elevatedInUse enrolled AND switched on: a second audience exists today.
     * @param elevatedReturns going to ENHANCED switches an Elevated account back on.
     * @param advertNow / [advertAfter] the server the CoT names, or null for none.
     * @param publishNow / [publishAfter] the server the stream publishes to.
     * @param locksReturn how many locks going to ENHANCED sets again.
     */
    data class Facts(
        val elevatedEnrolled: Boolean,
        val elevatedInUse: Boolean,
        val elevatedReturns: Boolean,
        val advertNow: Int?,
        val advertAfter: Int?,
        val publishNow: Int,
        val publishAfter: Int,
        val locksReturn: Int,
        /** An Emergency Broadcast is running now. Going to BASIC ends it. */
        val emergencyActive: Boolean = false,
    )

    /** Reads [Facts] for a change to [target]. [hasElevatedEnrollment] is passed in so this stays
     *  a function of the preferences that a unit test can drive.
     *  ⚠ It is the PREFERENCES-ONLY check (TakMissionManager.hasElevatedEnrollment), on purpose
     *  — the same one the first-run rule uses. It is WIDER than the dial's
     *  TakAutoConnect.hasSavedVideoCerts, which also needs the files on disk. Do not swap one for
     *  the other: here a wider check errs toward "an Elevated account exists", the safe side. */
    fun facts(
        prefs: SharedPreferences, hasElevatedEnrollment: Boolean, target: AppMode,
        emergencyActive: Boolean = false,
    ): Facts {
        val enrolled = hasElevatedEnrollment && !prefs.getBoolean("chb_logged_out", false)
        val slotNow = if (prefs.getInt(VideoServersActivity.KEY_V_ACTIVE_SLOT, 1) == 2) 2 else 1
        val adv1 = prefs.getString(VideoServersActivity.vKey(1, "advertise"), null)
        val adv2 = prefs.getString(VideoServersActivity.vKey(2, "advertise"), null)
        val snap = prefs.getBoolean(KEY_SNAP_TAKEN, false)
        val host1 = prefs.getString(VideoServersActivity.vKey(1, "host"), null)
        val host2 = prefs.getString(VideoServersActivity.vKey(2, "host"), null)
        // The state AFTER, by the same rules toBasic / toEnhanced apply.
        var slotAfter = slotNow
        var adv1After = adv1
        var returns = false
        var locks = 0
        when (target) {
            AppMode.BASIC -> {
                slotAfter = 1
                if (adv1 == VideoServersActivity.ADV_OTHER) adv1After = VideoServersActivity.ADV_OFF
            }
            AppMode.ENHANCED -> if (snap) {
                slotAfter = if (prefs.getInt(KEY_SNAP_ACTIVE_SLOT, 1) == 2) 2 else 1
                if (prefs.getString(KEY_SNAP_S1_ADVERTISE, null) == VideoServersActivity.ADV_OTHER &&
                    adv1 == VideoServersActivity.ADV_OFF
                ) adv1After = VideoServersActivity.ADV_OTHER
                returns = enrolled && prefs.getBoolean(KEY_SNAP_CHB_ENABLED, false)
                locks = LOCK_KEYS.count {
                    prefs.getBoolean(snapLockKey(it), false) && !prefs.getBoolean(it, false)
                }
            }
        }
        return Facts(
            elevatedEnrolled = enrolled,
            elevatedInUse = enrolled && prefs.getBoolean(TakAutoConnect.KEY_CHB_ENABLED, false),
            elevatedReturns = returns,
            advertNow = effectiveAdvert(slotNow, adv1, adv2, host1, host2, hostsKnown = true),
            advertAfter = effectiveAdvert(slotAfter, adv1After, adv2, host1, host2, hostsKnown = true),
            publishNow = slotNow,
            publishAfter = slotAfter,
            locksReturn = locks,
            emergencyActive = emergencyActive,
        )
    }

    /**
     * What the pilot is shown BEFORE the change, in both directions.
     *
     * ⚠ **IT SAYS WHO RECEIVES THE AIRCRAFT, NOT WHO CAN PLAY THE VIDEO.** The Elevated socket
     * carries the position, the camera point and the markers as well as the video link, so a
     * supervisor watching on that channel loses the TRACK when it closes.
     *
     * ⚠ **GENERATED, NOT TYPED.** Fixed paragraphs shown whatever the controller held could both
     * be false ("will lose it" with no Elevated audience; "the link goes only to the Elevated
     * channel" with none coming back), and could not mention the password change or a change to
     * the advertisement. Where the facts match the common case the words stay the same.
     *
     * ⚠ **EVERY CLAIM ABOUT THE VIDEO LINK IS CONDITIONED ON THE ADVERTISEMENT.** "Every channel
     * gets the video link" is false when the advertisement is off — including the state BASIC
     * itself creates when the CoT named Server 2. And a change can START an advertisement as
     * well as stop one, which a pilot must be told before it happens: that link carries the
     * media server's credentials.
     */
    private const val ELEVATED_GETS_TRACK =
        "The Elevated account is switched on again, so its channels get this aircraft again — " +
            "its position, its camera point, its markers.\n\n"

    fun describeAudienceChange(target: AppMode, f: Facts): String = buildString {
        val video = f.advertAfter != null
        when (target) {
            AppMode.BASIC -> if (f.elevatedInUse) {
                append("Basic mode uses one TAK account.\n\n")
                append("Every active channel will then receive this aircraft — its position, its ")
                append("camera point, its markers")
                if (video) append(" AND the live-video link, which carries the video password")
                append(".\n\n")
                append("Anyone watching this aircraft on the Elevated channel alone will lose it.")
            } else {
                append("Basic mode uses one TAK account and one video server.\n\n")
                append("No Elevated account is in use, so who receives this aircraft's position ")
                append("and markers does not change.")
            }
            AppMode.ENHANCED -> when {
                // ⚠ Both returns WIDEN who gets the track: the Elevated account's channels get the
                // aircraft back, and they may belong to another organisation. Said in both branches, as
                // the BASIC direction says the matching loss.
                f.elevatedReturns && video -> {
                    append("Elevated mode uses two TAK accounts.\n\n")
                    append(ELEVATED_GETS_TRACK)
                    append("The live-video link goes only to the Elevated account's channel. Every ")
                    append("other channel keeps the position and the markers, with no video.")
                    // Only when a team HAS the video now: in BASIC with the advertisement off,
                    // nobody does.
                    if (f.advertNow != null) append("\n\nA team that can see the video now will lose it.")
                }
                f.elevatedReturns -> {
                    append("Elevated mode uses two TAK accounts.\n\n")
                    append(ELEVATED_GETS_TRACK)
                    append("No video address is advertised, so no channel gets a video link.")
                }
                f.elevatedEnrolled -> {
                    append("Elevated mode allows a second TAK account and a second video server.\n\n")
                    append("The Elevated account is set up but stays switched off. Switch it on ")
                    append("in Configure TAK Server to send the video link only to its channel.")
                    if (video) append(" Until then every active channel gets the video link.")
                }
                else -> {
                    append("Elevated mode allows a second TAK account and a second video server.\n\n")
                    append("No Elevated account is set up")
                    // "keeps" only if a link goes out NOW. From a BASIC that advertises nothing,
                    // the return STARTS the link on every channel.
                    if (video) append(", so every active channel ")
                        .append(if (f.advertNow != null) "keeps getting" else "will get")
                        .append(" the video link until one is set up in Configure TAK Server")
                    append(".")
                }
            }
        }
        // What happens to the video address in the CoT.
        when {
            f.advertNow != null && f.advertAfter == null -> {
                append("\n\nThe video address in the CoT names Server ${f.advertNow}")
                if (target == AppMode.BASIC && f.advertNow == 2) append(", which Basic does not have")
                append(". It will be switched OFF — the team will have no video link until one is ")
                append("set.")
            }
            f.advertNow == null && f.advertAfter != null -> {
                append("\n\nThe CoT will START carrying a video address — Server ${f.advertAfter}, ")
                append("with its login — so channels that get video will be able to play it.")
            }
            f.advertNow != null && f.advertNow != f.advertAfter -> {
                append("\n\nThe video address in the CoT will change from Server ${f.advertNow} to ")
                append("Server ${f.advertAfter}.")
            }
        }
        // ⚠ A running broadcast outlives the flight screen (its timer is in the core), and BASIC
        // ends it — the pilot is told before, not left to find out.
        if (target == AppMode.BASIC && f.emergencyActive) {
            append("\n\nThe Emergency Broadcast that is running now will END.")
        }
        if (f.publishAfter != f.publishNow) {
            append("\n\nThe stream will publish to Server ${f.publishAfter} instead of ")
            append("Server ${f.publishNow}.")
        }
        // What happens to the passwords.
        when (target) {
            AppMode.BASIC -> append("\n\nThe configuration locks will then unlock with no password.")
            AppMode.ENHANCED -> {
                append("\n\n")
                if (f.locksReturn > 0) {
                    append(if (f.locksReturn == 1) "The lock that was set before Basic is set again"
                           else "The ${f.locksReturn} locks that were set before Basic are set again")
                    append(", and ")
                }
                append(if (f.locksReturn > 0) "locked sections ask for the password."
                       else "Locked sections ask for the password again.")
            }
        }
    }

    /**
     * The dialog title, built here so a test can hold it. A literal such as
     * `"Change to ${'$'}{target.name…} mode?"` escapes its own template with the `${'$'}`, and
     * the pilot is shown raw Kotlin. See [AppModeTest].
     */
    fun dialogTitle(target: AppMode): String =
        "Change to ${target.label} mode?"
}
