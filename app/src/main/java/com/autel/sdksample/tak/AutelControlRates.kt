package com.autel.sdksample.tak

import android.content.Context
import com.autel.common.CallbackWithNoParam
import com.autel.common.CallbackWithOneParam
import com.autel.common.error.AutelError
import com.autel.common.remotecontroller.RemoteControllerCommandStickMode
import com.taklite.util.AppLog

/**
 * Controller feel: gimbal-wheel speed and yaw rate, plus the (read-only) stick mode.
 *
 * WHAT "PRECISION" IS. Both values live on the REMOTE CONTROLLER, not the aircraft:
 *   setGimbalDialAdjustSpeed(int)  — how fast the gimbal wheel drives the camera
 *   setYawCoefficient(float)       — how fast the stick yaws the aircraft
 * Precision slows both. It changes how the CONTROLS respond, not
 * how the aircraft flies — the flight-sensitivity family (pitch/roll/brake/ATTI) is a separate
 * thing and is deliberately not touched here.
 *
 * NORMAL AND PRECISION ARE EXPLICIT VALUES, chosen after reading the aircraft's
 * own numbers off a settings dump. The airframe shipped with a gimbal dial of 100
 * and had been moved to 50 in Autel's app; neither felt right, so the values
 * below were tuned by feel in flight rather than discovered. An earlier version LEARNED normal from whatever the
 * controller happened to report — which sounds safer but silently inherits whatever the last
 * app left behind, including a Precision value, and would ratchet the controls slower on every
 * toggle.
 *
 * Nothing here runs on connect. Same rule as obstacle avoidance: the app never changes how the
 * aircraft handles unless the pilot asks it to.
 */
object AutelControlRates {
    private const val TAG = "AutelControlRates"
    // Gimbal wheel speed. HIGHER IS FASTER — confirmed in flight, not assumed.
    // Tuning history, because these numbers look arbitrary otherwise: shipped at 100, Autel's
    // app had 50, 75 felt too slow, 100 felt too fast. 90/75 is where tuning settled.
    //
    // Note how NARROW that is. Precision is a deliberate nudge, not a different mode — the
    // useful range on this wheel turned out to be much smaller than a halving.
    private const val NORMAL_DIAL = 90
    private const val PRECISION_DIAL = 75

    // ---- YAW: TWO SEPARATE PARAMETERS, AND THEY ARE NOT INTERCHANGEABLE --------------------
    //
    // Both are AIRCRAFT flight-controller parameters, despite one of them being exposed on the
    // SDK's remote-controller interface. Traced through the SDK bytecode and matched against
    // Autel Explorer's own UI:
    //
    //   SDK setYawCoefficient()        -> SM_YAW_SEN_NEW  = Explorer "EXP > Rotate"
    //                                     the STICK CURVE. Range 0.2-0.7, per Explorer's own
    //                                     caption ("X = physical stick output, Y = logical
    //                                     output"). LOW = soft around centre, high = linear.
    //   SDK setYawStrokeSensitivity()  -> SM_YAW_SCH_SEN  = Explorer "Sensitivity > Yaw Movement"
    //                                     the RATE. Range 20-200%, sent as a fraction (the
    //                                     aircraft reported 0.75 for Explorer's 75%).
    //
    // WHY THIS MATTERS: the curve was already pinned at its 0.2 floor on this airframe, so two
    // rounds of "make Precision yaw slower" (0.13, then 0.10) were silently clamped back up to
    // 0.2 and changed nothing, while the log reported "-> PRECISION" each time. Yaw felt fast
    // because the RATE had never been touched. Curve and rate are different questions: the curve
    // decides how twitchy the centre feels, the rate decides how fast full deflection yaws.

    /**
     * Stick curve — THE SAME 0.2 IN BOTH PRESETS, deliberately (design decision).
     *
     * 0.2 is the floor of the published 0.2-0.7 range: the softest centre, where a small stick
     * movement yaws least. It is also what this aircraft has actually been flying, because
     * TAKPilot has been writing this parameter on every connect while believing it was a rate
     * control (Normal wrote 0.25 then 0.20; Precision asked for 0.13/0.10 and was clamped up).
     *
     * It was briefly set to 0.5 for Normal on the theory that 0.5 is Autel's default — the other
     * three axes read 0.5. That is NOT established for yaw, and raising it would have made
     * Normal MORE sensitive, which is the wrong direction. Left at the flown value: the two
     * presets differ by RATE alone, so tuning changes one variable at a time.
     */
    private const val NORMAL_YAW_EXP = 0.20f
    private const val PRECISION_YAW_EXP = 0.20f

    /** Yaw rate, as a fraction of Explorer's percentage. 0.75 = 75%, this airframe's setting. */
    private const val NORMAL_YAW_RATE = 0.75f
    /**
     * Yaw rate in Precision = 35%.
     *
     * **FLOWN AND ACCEPTED** — chosen as roughly half of Normal, inside the 20-200%
     * range Explorer offers, and confirmed in the air rather than left as a guess. This is the
     * first setting that made Precision yaw genuinely slower, because the parameter the app had
     * been tuning for two rounds was the stick curve, not the rate.
     *
     * If it ever needs revisiting: lower toward 0.20 for slower, raise for more authority. The
     * settings dump logs what the aircraft reports back as `feel.yawStrokeSensitivity`.
     */
    private const val PRECISION_YAW_RATE = 0.35f

    /**
     * Stick curve for THROTTLE, PITCH and ROLL — Explorer's other three EXP graphs.
     *
     * Same parameter family and same 0.2-0.7 range as the yaw curve, confirmed by reading them
     * back: Explorer showed Throttle/Forward/Right at 0.5 and the aircraft reported
     * gasPedal/pitch/roll = 0.5. Normal keeps that; Precision softens the centre so small stick
     * movements move the aircraft less, which is what Precision is for.
     *
     * 0.30, not the 0.2 floor. Yaw sits at the floor because it is almost purely an AIMING axis
     * — a slow centre costs nothing. These three also FLY the aircraft, and bottoming them out
     * risks Precision feeling sluggish to reposition rather than merely fine.
     *
     * **FLOWN AND ACCEPTED** at 0.30. Drop toward 0.2 only if a pilot asks for softer;
     * it was deliberately not taken to the floor.
     */
    private const val NORMAL_STICK_EXP = 0.50f
    private const val PRECISION_STICK_EXP = 0.30f


    /**
     * Guards the connect-time push. productConnected fires REPEATEDLY — three times in 17
     * seconds has been observed — and an unguarded apply rewrote the controller's settings on
     * every one of them. The controller beeps to acknowledge each write, so the pilot got a
     * burst of beeps for a change that had already been made.
     */
    @Volatile private var appliedForThisConnect = false

    @Volatile var stickMode: RemoteControllerCommandStickMode? = null
        private set
    @Volatile var dialSpeed: Int? = null
        private set
    @Volatile var yawCoefficient: Float? = null
        private set

    /** True/false once we can tell, null while values are unknown. */
    @Volatile var precisionActive: Boolean? = null
        private set

    /**
     * Reads stick mode, gimbal dial speed and yaw coefficient from the controller.
     *
     * @param then invoked on completion whether or not every read succeeded — the caller
     *   re-renders from whatever arrived rather than waiting for a full set that may never come.
     */
    fun refresh(context: Context, then: (() -> Unit)? = null) {
        val rc = AutelProductHolder.evo2?.remoteController ?: run { then?.invoke(); return }
        // getCommandStickMode / getGimbalDialAdjustSpeed / getYawCoefficient are ONE-SHOT — the
        // RC getters are measured one-shot (verified on hardware), and the `outstanding`
        // counter below is itself the proof at runtime: if any callback repeated, it would
        // decrement past zero and re-fire `then`, which has never been observed across connects.
        // Unlike the fly-controller's getVisualSettingInfo, these do not subscribe.
        var outstanding = 3
        fun done() { if (--outstanding <= 0) { evaluate(context); then?.invoke() } }

        runCatching {
            rc.getCommandStickMode(object : CallbackWithOneParam<RemoteControllerCommandStickMode> {
                override fun onSuccess(m: RemoteControllerCommandStickMode?) {
                    stickMode = m; AppLog.i(TAG, "stick mode: $m"); done()
                }
                override fun onFailure(error: AutelError?) {
                    AppLog.w(TAG, "getCommandStickMode failed: ${error?.description}"); done()
                }
            })
            rc.getGimbalDialAdjustSpeed(object : CallbackWithOneParam<Int> {
                override fun onSuccess(v: Int?) { dialSpeed = v; done() }
                override fun onFailure(error: AutelError?) {
                    AppLog.w(TAG, "getGimbalDialAdjustSpeed failed: ${error?.description}"); done()
                }
            })
            rc.getYawCoefficient(object : CallbackWithOneParam<Float> {
                override fun onSuccess(v: Float?) { yawCoefficient = v; done() }
                override fun onFailure(error: AutelError?) {
                    AppLog.w(TAG, "getYawCoefficient failed: ${error?.description}"); done()
                }
            })
        }.onFailure { AppLog.w(TAG, "rates refresh threw: ${it.message}"); then?.invoke() }
    }

    /**
     * Reports which preset the controller is on, from the GIMBAL DIAL alone.
     *
     * The dial is the only value that differs between the two presets and is also cached here.
     * The yaw CURVE is 0.2 in both by design, so it cannot discriminate — and including a value
     * that is identical in both states does not add rigour, it just adds a way to report
     * "neither" when someone changes it in Autel's app. The yaw RATE, which does differ, is an
     * aircraft parameter rather than a controller one and is not in this cached read; fetching
     * it would put traffic on the fly-controller channel on every refresh, so the settings dump
     * reports it once per connect instead.
     *
     * Exact match, not a tolerance: an earlier version compared with a shared fuzzy tolerance
     * and that hid a real defect for two rounds of tuning. Still THREE outcomes — a dial on
     * neither 90 nor 75 reports null rather than being rounded to whichever is closer, because
     * claiming PRECISION for an aircraft that is not on the Precision numbers is how a pilot
     * ends up trusting a feel they do not have.
     */
    private fun evaluate(@Suppress("UNUSED_PARAMETER") context: Context) {
        val d = dialSpeed ?: return
        precisionActive = when (d) {
            PRECISION_DIAL -> true
            NORMAL_DIAL -> false
            else -> null
        }
    }

    /**
     * Switches between Normal and Precision.
     *
     * Both values are clamped to the ranges the controller itself publishes, so a halved value
     * can never fall below what the hardware accepts.
     */
    fun setPrecision(context: Context, on: Boolean, then: (Boolean) -> Unit) {
        val rc = AutelProductHolder.evo2?.remoteController ?: run { then(false); return }
        var targetDial = if (on) PRECISION_DIAL else NORMAL_DIAL
        var targetYaw = if (on) PRECISION_YAW_EXP else NORMAL_YAW_EXP
        // Clamp to what the controller itself says it accepts, so a constant edited later can
        // never push a value the hardware rejects.
        runCatching {
            rc.parameterRangeManager?.let { r ->
                r.dialAdjustSpeed?.let { targetDial = targetDial.coerceIn(it.valueFrom, it.valueTo) }
                r.yawCoefficient?.let { targetYaw = targetYaw.coerceIn(it.valueFrom, it.valueTo) }
            }
        }
        // SAY SO WHEN THE HARDWARE OVERRULES US. The clamp above used to be silent, and that hid
        // a real defect for two rounds of tuning: the yaw curve was set to 0.13, then 0.10, and
        // BOTH were raised to the controller's published minimum on the way out — so Precision
        // yaw was never actually slower than Normal, while the log cheerfully reported
        // "-> PRECISION". A constant the pilot asked for and the hardware refused is exactly the
        // thing that must not pass quietly.
        if (targetDial != (if (on) PRECISION_DIAL else NORMAL_DIAL)) {
            AppLog.w(TAG, "gimbal dial ${if (on) PRECISION_DIAL else NORMAL_DIAL} is outside the " +
                "controller's accepted range — clamped to $targetDial")
        }
        val wantedYaw = if (on) PRECISION_YAW_EXP else NORMAL_YAW_EXP
        if (kotlin.math.abs(targetYaw - wantedYaw) >= 0.0001f) {
            AppLog.w(TAG, "yaw $wantedYaw is outside the controller's accepted range " +
                "(${runCatching { rc.parameterRangeManager?.yawCoefficient }.getOrNull()}) — " +
                "clamped to $targetYaw. The requested feel is NOT what the aircraft will fly.")
        }

        // THE AIRCRAFT-SIDE HALF OF THE PRESET. The dial and the yaw curve above go to the
        // controller; these four go to the fly controller, so they are separate writes on a
        // different channel — and they happen BEFORE the "already correct" check below, which
        // only knows about the two CONTROLLER values. Placed after it they would be skipped on
        // exactly the common case: a controller that kept its dial across a power cycle
        // short-circuits the write, and the feel the pilot asked for would never reach the
        // aircraft.
        //
        // Unconditional, because nothing caches these and reading them back to compare would
        // add fly-controller traffic for no benefit — four writes per connect, the same order
        // as the flight limits. Fire-and-forget: none of them is part of the state check, so a
        // failure must not make the whole preset report failure when the controller half landed.
        AutelProductHolder.evo2?.flyController?.let { fc ->
            val exp = if (on) PRECISION_STICK_EXP else NORMAL_STICK_EXP
            fun push(name: String, value: Float, call: (Float, CallbackWithOneParam<Boolean>) -> Unit) {
                runCatching {
                    call(value, object : CallbackWithOneParam<Boolean> {
                        override fun onSuccess(v: Boolean?) { AppLog.i(TAG, "$name -> $value: OK") }
                        override fun onFailure(error: AutelError?) {
                            AppLog.w(TAG, "$name -> $value FAILED: ${error?.description}")
                        }
                    })
                }.onFailure { AppLog.w(TAG, "$name threw: ${it.message}") }
            }
            push("yaw rate", if (on) PRECISION_YAW_RATE else NORMAL_YAW_RATE, fc::setYawStrokeSensitivity)
            push("throttle curve", exp, fc::setGasPedalSensitivity)
            push("pitch curve", exp, fc::setPitchSensitivity)
            push("roll curve", exp, fc::setRollSensitivity)
        }

        // DO NOT WRITE A VALUE THE CONTROLLER ALREADY HOLDS. Each write draws an acknowledgement
        // beep from the controller, so a redundant push is not merely wasteful — the pilot hears
        // it. This is the second guard: even if something calls in again, an unchanged setting
        // makes no noise.
        val dialSame = dialSpeed?.let { it == targetDial } ?: false
        val yawSame = yawCoefficient?.let { kotlin.math.abs(it - targetYaw) < 0.001f } ?: false
        if (dialSame && yawSame) {
            AppLog.i(TAG, "control rates already ${if (on) "PRECISION" else "NORMAL"} " +
                "(dial=$targetDial yaw=$targetYaw) — no write")
            precisionActive = on
            then(true)
            return
        }

        var outstanding = 2
        var ok = true
        fun done(success: Boolean) {
            if (!success) ok = false
            if (--outstanding <= 0) {
                // Confirm against the controller rather than trusting the acks — this SDK has
                // been caught reporting success for changes it did not make.
                refresh(context) { then(ok) }
            }
        }
        AppLog.i(TAG, "control rates -> ${if (on) "PRECISION" else "NORMAL"} (dial=$targetDial yaw=$targetYaw)")
        runCatching {
            rc.setGimbalDialAdjustSpeed(targetDial, object : CallbackWithNoParam {
                override fun onSuccess() { done(true) }
                override fun onFailure(error: AutelError?) {
                    AppLog.w(TAG, "setGimbalDialAdjustSpeed failed: ${error?.description}"); done(false)
                }
            })
            rc.setYawCoefficient(targetYaw, object : CallbackWithNoParam {
                override fun onSuccess() { done(true) }
                override fun onFailure(error: AutelError?) {
                    AppLog.w(TAG, "setYawCoefficient failed: ${error?.description}"); done(false)
                }
            })
        }.onFailure { AppLog.w(TAG, "control rate change threw: ${it.message}"); then(false) }
    }

    /**
     * The stick mode as the pilot's own "Mode 1/2/3".
     *
     * MODE 3 = CHINA IS CONFIRMED ON HARDWARE: the controller's own Command Stick
     * Mode screen had Mode 3 selected and the SDK reported CHINA. Modes 1 and 2 follow the
     * standard RC convention from that anchor and have NOT been separately confirmed — if a
     * pilot ever sees this disagree with the controller's own screen, that is why, and the
     * mapping below is the only thing to change.
     */
    fun stickModeLabel(): String = when (stickMode) {
        RemoteControllerCommandStickMode.JAPAN -> "Mode 1"
        RemoteControllerCommandStickMode.USA -> "Mode 2"
        RemoteControllerCommandStickMode.CHINA -> "Mode 3"
        else -> "—"
    }

    /**
     * Enforces the pilot's chosen stick mode, skipping the write if the controller already has
     * it — a needless write costs an audible acknowledgement beep.
     *
     * Does nothing if no mode has been chosen yet. The app must never INVENT a stick mode: a
     * default would swap throttle and pitch for a pilot who never opened Pre-Flight. Pre-Flight
     * seeds the choice from whatever the controller reports the first time, and from then on it
     * is enforced so Autel's app cannot change it behind the pilot's back.
     */
    fun pushStickMode(context: Context, then: (Boolean) -> Unit) {
        val chosen = savedStickModeId(context)
        if (chosen.isEmpty()) {
            AppLog.i(TAG, "no stick mode chosen yet — leaving the controller's own setting")
            then(true); return
        }
        val want = stickModeFor(chosen)
        if (stickMode == want) {
            AppLog.i(TAG, "stick mode already Mode $chosen ($want) — no write")
            then(true); return
        }
        val rc = AutelProductHolder.evo2?.remoteController ?: run { then(false); return }
        runCatching {
            rc.setCommandStickMode(want, object : CallbackWithNoParam {
                override fun onSuccess() {
                    AppLog.i(TAG, "stick mode enforced: Mode $chosen ($want)")
                    refresh(context) { then(true) }
                }
                override fun onFailure(error: AutelError?) {
                    AppLog.w(TAG, "stick mode Mode $chosen failed: ${error?.description}")
                    refresh(context) { then(false) }
                }
            })
        }.onFailure { then(false) }
    }

    /** The pilot's selection, applied to the controller at connect. */
    fun stickModeFor(id: String): RemoteControllerCommandStickMode = when (id) {
        "1" -> RemoteControllerCommandStickMode.JAPAN
        "3" -> RemoteControllerCommandStickMode.CHINA
        else -> RemoteControllerCommandStickMode.USA
    }
    fun idFor(m: RemoteControllerCommandStickMode?): String = when (m) {
        RemoteControllerCommandStickMode.JAPAN -> "1"
        RemoteControllerCommandStickMode.CHINA -> "3"
        else -> "2"
    }

    // ---- Pilot's saved choices, pushed to the controller on connect ----

    private const val PREFS = "takpilot2_rates"
    private const val KEY_PRECISION = "precision_selected"
    private const val KEY_STICK_MODE = "stick_mode_id"

    /**
     * The pilot's control-response choice, or **null until they have made one** — the same
     * contract as [savedStickModeId], so the app never imposes a setting it was not told.
     *
     * It used to be a plain Boolean defaulting to false, and [saveSelection] was never called
     * from anywhere. The two faults compounded: the preference stayed unwritten, so every connect
     * pushed NORMAL. A pilot who selected Precision had it actively undone at each launch rather
     * than merely forgotten.
     */
    fun savedPrecisionOrNull(context: Context): Boolean? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_PRECISION)) return null
        return prefs.getBoolean(KEY_PRECISION, false)
    }

    /** For UI that needs a concrete value; treats "never chosen" as Normal. */
    fun savedPrecision(context: Context): Boolean = savedPrecisionOrNull(context) ?: false

    fun saveSelection(context: Context, precision: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_PRECISION, precision).apply()
    }

    /** Blank until the pilot chooses, so the app does not impose a stick mode it was never told. */
    fun savedStickModeId(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_STICK_MODE, "") ?: ""

    fun saveStickModeId(context: Context, id: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_STICK_MODE, id).apply()
    }

    /**
     * Applies the pilot's saved choices to the controller at connect.
     *
     * ⚠ STICK MODE IS ONLY PUSHED IF THE PILOT HAS ACTUALLY CHOSEN ONE. An unset preference
     * leaves the controller alone, because a default that silently rewrote the stick mode would
     * swap throttle and pitch for anyone who never opened Pre-Flight. Once chosen, it IS pushed
     * every connect — which is the point, and why the Enter Flight card shows it before the
     * pilot can reach the flight screen.
     *
     * CONTROL RESPONSE IS DIFFERENT: IT IS ALWAYS PUSHED (design decision). Launching
     * TAKPilot sets a clean slate — the Pre-Flight choice is applied to the airframe every
     * time, so whatever Autel Explorer or another pilot left behind is overwritten rather than
     * inherited. An unset preference means NORMAL, which is a real state to assert, not a reason
     * to leave the controller alone.
     *
     * (This is the opposite of the stick-mode rule above, deliberately. A wrong stick mode swaps
     * throttle and pitch and can only be discovered in the air, so the app refuses to guess one.
     * Control response only changes how fast the gimbal wheel and yaw respond — asserting a
     * known feel is safer than inheriting an unknown one.)
     */
    fun applyAtConnect(context: Context) {
        if (appliedForThisConnect) return
        appliedForThisConnect = true
        pushStickMode(context) { }
        val want = savedPrecision(context)
        // READ FIRST. Without the current values the "already correct" check below has nothing
        // to compare against, so a controller that was already in the right state would still be
        // written to — and still beep.
        refresh(context) {
            setPrecision(context, want) { ok ->
                AppLog.i(TAG, "control response applied at connect " +
                    "(${if (want) "PRECISION" else "NORMAL"}): ${if (ok) "OK" else "FAILED"}")
            }
        }
    }

    fun onProductDisconnected() {
        stickMode = null; dialSpeed = null; yawCoefficient = null; precisionActive = null
        appliedForThisConnect = false
    }
}
