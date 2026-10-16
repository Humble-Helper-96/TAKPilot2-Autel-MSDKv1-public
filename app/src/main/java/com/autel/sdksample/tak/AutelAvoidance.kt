package com.autel.sdksample.tak

import com.autel.common.CallbackWithOneParam
import com.autel.common.error.AutelError
import com.autel.common.flycontroller.visual.AvoidanceRadarInfo
import com.autel.common.flycontroller.visual.VisualSettingInfo
import com.taklite.util.AppLog

/**
 * Obstacle-avoidance state, cached process-wide.
 *
 * WHY THIS EXISTS. Without it the app is completely blind to avoidance: it never reads the
 * state, never displays it, and never sets it. Avoidance is then in whatever state something
 * else left it — Autel Explorer, or the aircraft's own default — and NOTHING on any screen
 * tells the pilot which. A disabled avoidance system that looks identical to an enabled one is
 * the same class of problem as the low-battery RTH the app could not see: the aircraft behaves
 * differently and the pilot has no way to know.
 *
 * PRE-FLIGHT IS THE SOURCE OF TRUTH, PUSHED AT CONNECT (design decision).
 *
 * This deliberately REVERSES an earlier rule that said the app must never push a saved avoidance
 * state. That rule was protecting against a stale checkbox silently disabling a safety system —
 * a real hazard, but it left a worse one in place: Autel's own app can set avoidance to anything
 * it likes, and a pilot launching TAKPilot had no idea what they were about to fly with. Leaving
 * "whatever was there" is not neutral, it is unknown.
 *
 * So the settings are now enforced from Pre-Flight on every connect, AND shown on the Enter
 * Flight card before the pilot can reach the flight screen. Enforcement plus visibility beats
 * non-interference — the pilot can always see what was applied, which is what makes pushing
 * safe. The defaults are all ON, so an install that has never been configured errs toward
 * protection rather than away from it.
 *
 * Two independent feeds, both from the fly controller's visual interface:
 *  - [VisualSettingInfo]  — the SETTINGS (is avoidance on, is it on during RTH, etc.)
 *  - [AvoidanceRadarInfo] — LIVE obstacle distances, six faces, six sub-sectors each
 */
object AutelAvoidance {
    private const val TAG = "AutelAvoidance"

    /** True/false once the aircraft has told us; null until then. Null is NOT "off" — the
     *  difference matters, because "we do not know yet" and "it is disabled" warrant different
     *  words in front of a pilot. */
    @Volatile var systemEnabled: Boolean? = null
        private set
    @Volatile var avoidDuringRth: Boolean? = null
        private set
    @Volatile var landingProtect: Boolean? = null
        private set

    /** Latest radar sample, or null if none has arrived. Faces are front/rear/left/right/
     *  top/bottom, each a float[6] of sub-sectors. UNITS ARE NOT YET VERIFIED — see
     *  [logRadarSample]. */
    @Volatile var radar: AvoidanceRadarInfo? = null
        private set

    /**
     * The most recent full [VisualSettingInfo], or null until the standing listener has fired.
     *
     * Exposed so other readers can get the vision fields (location/landing/mainFlyState/warnState)
     * WITHOUT opening their own `getVisualSettingInfo` — which is an uncancellable ~2Hz
     * subscription, not a one-shot. This object owns the single subscription; everyone else reads
     * this cache. See [AircraftSettingsDump].
     */
    @Volatile var latestVisualSetting: VisualSettingInfo? = null
        private set

    /** Wired from [AutelProductHolder] on every (re)connect — listener registrations do not
     *  survive a product cycle. */
    fun onProductConnected() {
        val fc = AutelProductHolder.evo2?.flyController ?: return
        runCatching {
            fc.setVisualSettingInfoListener(object : CallbackWithOneParam<VisualSettingInfo> {
                override fun onSuccess(info: VisualSettingInfo?) {
                    info ?: return
                    latestVisualSetting = info
                    val was = systemEnabled
                    systemEnabled = info.isAvoidanceSystemEnable
                    avoidDuringRth = info.isDetectObstacleEnableWhenReturn
                    landingProtect = info.isLandingProtectEnable
                    // Logged only on CHANGE: this listener pushes continuously, and a per-tick
                    // line would bury everything else in the flight log.
                    if (was != systemEnabled) {
                        AppLog.i(TAG, "avoidance system ${if (systemEnabled == true) "ENABLED" else "DISABLED"} " +
                            "(rth-avoid=$avoidDuringRth landing-protect=$landingProtect)")
                    }
                    // Self-heal: when the give-up warning is up and the aircraft state now
                    // matches what enforcement pushed (a Pre-Flight toggle fixed it, or the
                    // aircraft settled late), clear the warning. No write, no new subscription.
                    val wanted = lastDesired
                    if (wanted != null) {
                        val live = mapOf(
                            AvoidanceEnforcement.Switch.SYSTEM to systemEnabled,
                            AvoidanceEnforcement.Switch.RTH to avoidDuringRth,
                            AvoidanceEnforcement.Switch.LANDING to landingProtect,
                        )
                        val ok = AvoidanceEnforcement.matches(wanted, live)
                        if (FlightWarnings.avoidanceNotApplied && ok) {
                            FlightWarnings.avoidanceNotApplied = false
                            AppLog.i(TAG, "avoidance now matches Pre-Flight — warning cleared")
                        } else if (!FlightWarnings.avoidanceNotApplied && !ok &&
                            AutelTakBridge.airborne) {
                            // ⚠ RAISING IT IS GATED ON AIRBORNE, AND THAT IS WHAT KEEPS IT OUT
                            // OF ENFORCEMENT'S WAY. On the ground, enforcement writes and expects
                            // a transient mismatch between a write and the next push; warning
                            // there would cry wolf on every correction it is in the middle of
                            // making. In the air nothing writes, so a mismatch is the aircraft
                            // having drifted and there is nothing to wait for.
                            //
                            // This also covers the airborne-skip case where the live state was
                            // not yet known at connect: the comparison simply re-runs on the
                            // next push.
                            AppLog.e(TAG, "avoidance drifted from Pre-Flight IN THE AIR " +
                                "(aircraft holds sys=$systemEnabled rth=$avoidDuringRth " +
                                "land=$landingProtect) — warning the pilot")
                            FlightWarnings.avoidanceNotApplied = true
                        }
                    }
                }
                override fun onFailure(error: AutelError?) {
                    AppLog.w(TAG, "visual setting listener error: ${error?.description}")
                }
            })
            // NO separate getVisualSettingInfo here. It LOOKS like a one-shot read but its
            // callback fires ~2Hz forever on this firmware and cannot be de-registered, so calling
            // it created a second permanent stream of the same three booleans the listener above
            // already delivers — and, when productConnected re-fired, those streams accumulated
            // into a fly-controller flood that once ended in a wall strike. The standing
            // setVisualSettingInfoListener is the single source: it populates the cache within
            // about half a second of connect, which is soon enough for the Enter Flight card.
            fc.setAvoidanceRadarInfoListener(object : CallbackWithOneParam<AvoidanceRadarInfo> {
                override fun onSuccess(info: AvoidanceRadarInfo?) {
                    info ?: return
                    radar = info
                    logRadarSample(info)
                }
                override fun onFailure(error: AutelError?) {
                    AppLog.w(TAG, "avoidance radar listener error: ${error?.description}")
                }
            })
            AppLog.i(TAG, "avoidance listeners armed")
        }.onFailure { AppLog.w(TAG, "avoidance listener install failed: ${it.message}") }
    }

    fun onProductDisconnected() {
        systemEnabled = null; avoidDuringRth = null; landingProtect = null
        radar = null
        latestVisualSetting = null
        lastRadarLogMs = 0L
        appliedForThisConnect = false
        lastDesired = null
        FlightWarnings.avoidanceNotApplied = false
    }

    /**
     * Logs radar samples, biased toward the ones that matter.
     *
     * REPLACED A 20-SAMPLE CAP. The cap was written to characterise the feed on the ground
     * and it did that job, but it made the feed useless for the question that actually
     * mattered — why avoidance did not brake near an obstacle. All twenty samples were spent in
     * the first two seconds after connect, on the ground, and the flight itself logged nothing.
     *
     * So the rule is now "log what is close, and a slow heartbeat otherwise":
     *  - any face reporting a real obstacle nearer than [LOG_NEAR] is logged immediately, rate
     *    limited to [NEAR_MIN_GAP_MS] so a sustained approach does not flood
     *  - otherwise one heartbeat line every [IDLE_GAP_MS], to prove the feed is still alive
     *
     * READING A SAMPLE. Each face is a float[6] of sub-sectors. Two sentinel values, both
     * confirmed against live data: 0 means "this face was not in this push" (the aircraft sends
     * one face at a time, round-robin), and 10000 means "clear, nothing detected". Anything else
     * is a real distance. Units are CENTIMETRES — FIELD-VALIDATED by flying the
     * [ObstacleEdgeView] display (which reads these same values) against real obstacles; the
     * distances were accurate at flight-relevant ranges. Good to rely on; nobody should
     * quote it to the inch.
     */
    private fun logRadarSample(info: AvoidanceRadarInfo) {
        val now = android.os.SystemClock.elapsedRealtime()
        val near = nearestCm(info)
        val isNear = near != null && near < LOG_NEAR
        val gap = if (isNear) NEAR_MIN_GAP_MS else IDLE_GAP_MS
        if (now - lastRadarLogMs < gap) return
        lastRadarLogMs = now
        fun f(a: FloatArray?) = a?.joinToString(",") { "%.0f".format(it) } ?: "null"
        val tag = if (isNear) "NEAR ${near}cm" else "clear"
        AppLog.i(TAG, "radar($tag) F=[${f(info.front)}] R=[${f(info.rear)}] " +
            "L=[${f(info.left)}] Ri=[${f(info.right)}] " +
            "U=[${f(info.top)}] D=[${f(info.bottom)}] flying=${AutelTakBridge.airborne}")
    }

    /**
     * Smallest REAL obstacle distance across every face, or null if nothing is reporting.
     *
     * Skips both sentinels: 0 (face absent from this push) and 10000 (clear). Without that, the
     * minimum would always be 0 and every sample would look like an imminent collision.
     */
    fun nearestCm(info: AvoidanceRadarInfo?): Int? {
        info ?: return null
        var best: Float? = null
        for (face in listOf(info.front, info.rear, info.left, info.right, info.top, info.bottom)) {
            for (v in face ?: continue) {
                if (v <= 0f || v >= CLEAR_SENTINEL) continue
                if (best == null || v < best!!) best = v
            }
        }
        return best?.toInt()
    }

    /** "Nothing detected on this face." Confirmed live: clear faces report exactly this. */
    const val CLEAR_SENTINEL = 10000f

    /** Below this, a sample is worth a log line. ~15 m in the believed units. */
    private const val LOG_NEAR = 1500
    private const val NEAR_MIN_GAP_MS = 500L
    private const val IDLE_GAP_MS = 10_000L
    @Volatile private var lastRadarLogMs = 0L

    // ---- Pre-Flight's saved intent, enforced on every connect ----

    private const val PREFS = "takpilot2_avoid"
    private const val KEY_SYSTEM = "avoid_system"
    private const val KEY_RTH = "avoid_rth"
    private const val KEY_LANDING = "avoid_landing"

    /** Defaults are ON. An install nobody has configured must err toward protection. */
    fun savedSystem(c: android.content.Context) = prefs(c).getBoolean(KEY_SYSTEM, true)
    fun savedRth(c: android.content.Context) = prefs(c).getBoolean(KEY_RTH, true)
    fun savedLanding(c: android.content.Context) = prefs(c).getBoolean(KEY_LANDING, true)

    fun saveIntent(c: android.content.Context, system: Boolean, rth: Boolean, landing: Boolean) {
        prefs(c).edit().putBoolean(KEY_SYSTEM, system).putBoolean(KEY_RTH, rth)
            .putBoolean(KEY_LANDING, landing).apply()
    }

    private fun prefs(c: android.content.Context) =
        c.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)

    @Volatile private var appliedForThisConnect = false

    /** Write passes allowed per connect before enforcement gives up and warns the pilot. */
    private const val ENFORCE_MAX_ATTEMPTS = 3
    /** Wait between a write pass and its verify read of the cache — about four times the
     *  standing listener's ~0.5 s cache latency. */
    private const val VERIFY_DELAY_MS = 2000L
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** What enforcement last pushed, kept so the standing listener can clear the warning
     *  by itself when the aircraft state later comes to match (a Pre-Flight toggle, or a
     *  late aircraft-side settle). */
    @Volatile private var lastDesired: Map<AvoidanceEnforcement.Switch, Boolean>? = null

    /**
     * Enforces the Pre-Flight selection on the aircraft, once per connect — and VERIFIES it.
     *
     * Only writes switches that are actually WRONG. Every needless write costs a round trip and,
     * on the controller side, an audible acknowledgement — the same mistake that produced a burst
     * of beeps when the control-rate push went unguarded.
     *
     * WHY THE VERIFY CHAIN: on one flight a LANDING_PROTECT write timed out, the result
     * was ignored, and the aircraft flew the whole flight unprotected while the app showed the
     * Pre-Flight selection. The ack cannot be trusted, so each write pass is
     * followed by a read of the standing listener's cache; only mismatched switches are written
     * again, at most [ENFORCE_MAX_ATTEMPTS] passes. If a switch still does not match, the amber
     * AVOIDANCE SETTING NOT APPLIED banner goes up.
     *
     * THIS IS NOT A TIMER THAT WRITES. It is the existing connect-time write
     * plus bounded verification: worst case [ENFORCE_MAX_ATTEMPTS] write passes per switch per
     * physical connect, and the chain stops on verify success, attempt exhaustion, product
     * disconnect, or launch. Same shape as the gimbal-unlock retry in [AutelProductHolder].
     */
    fun applyAtConnect(context: android.content.Context) {
        if (appliedForThisConnect) return
        // NEVER rewrite a safety switch on an aircraft that is already flying. Enforcement is a
        // pre-flight act: the pilot reads what was applied on the Enter Flight card and then
        // launches. Writing avoidance settings underneath an airborne aircraft changes how it
        // behaves with nobody looking at the card, which is the opposite of the point.
        if (AutelTakBridge.airborne) {
            // ⚠ THE SKIP IS RIGHT, BUT IT MUST NOT BE SILENT. Never rewriting a safety
            // switch in the air is correct — but returning without a word means an aircraft that
            // came back from a link loss with avoidance RESET would fly the rest of the mission
            // unprotected while Pre-Flight still showed the pilot's selection. That is the shape
            // of the incident this whole verify chain was built for, reached through
            // a different door: not a failed write ignored, but a skipped write unmentioned.
            //
            // It is not hypothetical. A 44-second link loss once came back with
            // "avoidance system ENABLED (rth-avoid=true landing-protect=false)" against a
            // Pre-Flight selection of true. That aircraft happened to be on the ground, so
            // enforcement ran and fixed it. At altitude it would have been skipped in silence.
            //
            // ⚠ NO WRITE HERE, AND THERE MUST NEVER BE ONE. This only compares and tells. The
            // desired set is recorded so the standing listener can keep re-checking it, which is
            // what covers the case where the live state is not known yet at this instant.
            val desired = mapOf(
                AvoidanceEnforcement.Switch.SYSTEM to savedSystem(context),
                AvoidanceEnforcement.Switch.RTH to savedRth(context),
                AvoidanceEnforcement.Switch.LANDING to savedLanding(context),
            )
            lastDesired = desired
            val actual = mapOf(
                AvoidanceEnforcement.Switch.SYSTEM to systemEnabled,
                AvoidanceEnforcement.Switch.RTH to avoidDuringRth,
                AvoidanceEnforcement.Switch.LANDING to landingProtect,
            )
            if (AvoidanceEnforcement.matches(desired, actual)) {
                AppLog.w(TAG, "aircraft is airborne — SKIPPING avoidance enforcement this " +
                    "connect (it already matches Pre-Flight)")
            } else {
                AppLog.e(TAG, "aircraft is airborne and its avoidance does NOT match " +
                    "Pre-Flight (aircraft holds sys=$systemEnabled rth=$avoidDuringRth " +
                    "land=$landingProtect, wanted $desired) — NOT writing in the air, " +
                    "warning the pilot")
                FlightWarnings.avoidanceNotApplied = true
            }
            return
        }
        // Read the aircraft's live state from the cache the standing listener maintains — NOT by
        // firing our own getVisualSettingInfo, which is a permanent 2Hz subscription (that was
        // the wall-strike bug). This runs ~4.5s after connect, by which time the listener has
        // populated the cache many times over. If it is still null, the visual-setting feed is
        // dead, enforcement is impossible anyway, and we do NOT write blind — leave
        // appliedForThisConnect false so a later call can still try.
        if (systemEnabled == null || avoidDuringRth == null || landingProtect == null) {
            AppLog.w(TAG, "avoidance state not known yet — deferring enforcement this connect")
            return
        }
        appliedForThisConnect = true
        val desired = mapOf(
            AvoidanceEnforcement.Switch.SYSTEM to savedSystem(context),
            AvoidanceEnforcement.Switch.RTH to savedRth(context),
            AvoidanceEnforcement.Switch.LANDING to savedLanding(context),
        )
        lastDesired = desired
        FlightWarnings.avoidanceNotApplied = false
        enforcePass(desired, attempt = 1)
    }

    /**
     * One verify-and-write pass. Reads the standing listener's cache (never a get call —
     * the wall-strike rule), decides through [AvoidanceEnforcement.decide], writes only the
     * mismatched switches, and schedules the next pass. Terminates in at most
     * [ENFORCE_MAX_ATTEMPTS] write passes plus one final verify.
     */
    private fun enforcePass(desired: Map<AvoidanceEnforcement.Switch, Boolean>, attempt: Int) {
        if (AutelProductHolder.evo2 == null) {
            AppLog.w(TAG, "avoidance enforcement pass $attempt: product gone — stopping")
            return   // Disconnect clears the flags; the next connect starts fresh.
        }
        val actual = mapOf(
            AvoidanceEnforcement.Switch.SYSTEM to systemEnabled,
            AvoidanceEnforcement.Switch.RTH to avoidDuringRth,
            AvoidanceEnforcement.Switch.LANDING to landingProtect,
        )
        when (val d = AvoidanceEnforcement.decide(desired, actual, attempt, ENFORCE_MAX_ATTEMPTS)) {
            is AvoidanceEnforcement.Outcome.Verified -> {
                if (attempt == 1) AppLog.i(TAG, "avoidance already matches Pre-Flight — no writes")
                else AppLog.i(TAG, "avoidance VERIFIED against aircraft after ${attempt - 1} write pass(es)")
                FlightWarnings.avoidanceNotApplied = false
            }
            is AvoidanceEnforcement.Outcome.GiveUp -> {
                AppLog.w(TAG, "avoidance NOT VERIFIED after $ENFORCE_MAX_ATTEMPTS write passes: " +
                    "${d.switches} still mismatch (aircraft holds sys=$systemEnabled " +
                    "rth=$avoidDuringRth land=$landingProtect) — warning the pilot")
                FlightWarnings.avoidanceNotApplied = true
            }
            is AvoidanceEnforcement.Outcome.Retry -> {
                if (AutelTakBridge.airborne) {
                    // The aircraft launched mid-enforcement with an unverified switch: stop
                    // writing (never rewrite a safety switch in the air) and warn.
                    AppLog.w(TAG, "aircraft went airborne during enforcement with ${d.switches} " +
                        "unverified — stopping writes, warning the pilot")
                    FlightWarnings.avoidanceNotApplied = true
                    return
                }
                for (sw in d.switches) {
                    AppLog.i(TAG, "enforcing $sw -> ${desired[sw]} (attempt $attempt, aircraft had ${actual[sw]})")
                    // The ack is ignored, as before: the standing listener is the only truth,
                    // and the next pass reads it.
                    setSwitch(toSdk(sw), desired[sw]!!) { }
                }
                mainHandler.postDelayed({ enforcePass(desired, attempt + 1) }, VERIFY_DELAY_MS)
            }
        }
    }

    private fun toSdk(sw: AvoidanceEnforcement.Switch) = when (sw) {
        AvoidanceEnforcement.Switch.SYSTEM ->
            com.autel.common.flycontroller.visual.VisualSettingSwitchblade.AVOIDANCE_SYSTEM
        AvoidanceEnforcement.Switch.RTH ->
            com.autel.common.flycontroller.visual.VisualSettingSwitchblade.RETURN_TO_HOME_AVOIDANCE
        AvoidanceEnforcement.Switch.LANDING ->
            com.autel.common.flycontroller.visual.VisualSettingSwitchblade.LANDING_PROTECT
    }

    /**
     * Applies ONE avoidance switch on the aircraft, right now.
     *
     * Called from the Pre-Flight toggles AND from [applyAtConnect]. See the class note for why
     * pushing a saved state is the right trade here.
     *
     * @param onDone true if the aircraft ACKNOWLEDGED it — not that it took. This SDK returns
     *   success for things it does not do (see the camera's setAspectRatio), so the real state
     *   still has to be read back. That read comes from the continuous listener armed in
     *   [onProductConnected], which updates the cached values within about half a second.
     *
     * ⚠ Deliberately does NOT kick off its own read on completion. It used to, and that was half
     * of a fly-controller runaway: each write left behind another 2 Hz `getVisualSettingInfo`
     * reader that never stopped, so writes bred readers until the fly-controller channel gave
     * out. One standing listener is all this needs.
     */
    fun setSwitch(
        which: com.autel.common.flycontroller.visual.VisualSettingSwitchblade,
        enabled: Boolean,
        onDone: (Boolean) -> Unit,
    ) {
        val fc = AutelProductHolder.evo2?.flyController ?: run { onDone(false); return }
        runCatching {
            fc.setVisualSettingEnable(which, enabled, object : com.autel.common.CallbackWithNoParam {
                override fun onSuccess() {
                    AppLog.i(TAG, "avoidance switch $which -> $enabled: accepted")
                    onDone(true)       // the standing listener reports what actually took
                }
                override fun onFailure(error: AutelError?) {
                    AppLog.w(TAG, "avoidance switch $which -> $enabled failed: ${error?.description}")
                    onDone(false)
                }
            })
        }.onFailure {
            AppLog.w(TAG, "avoidance switch $which threw: ${it.message}")
            onDone(false)
        }
    }
}
