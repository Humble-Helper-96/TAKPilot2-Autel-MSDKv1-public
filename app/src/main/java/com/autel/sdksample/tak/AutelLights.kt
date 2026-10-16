package com.autel.sdksample.tak

import com.autel.common.CallbackWithNoParam
import com.autel.common.CallbackWithOneParam
import com.autel.common.error.AutelError
import com.autel.common.flycontroller.LedPilotLamp
import com.taklite.util.AppLog

/**
 * The aircraft's exterior LEDs.
 *
 * WHY THIS EXISTS. In public-safety use, flying lit at night tells anyone on the ground exactly
 * where the aircraft is. Being able to go dark is an operational requirement, not a convenience.
 *
 * ⚠ WHAT THIS DOES AND DOES NOT COVER — read before telling a pilot the aircraft is dark.
 *
 * The SDK exposes FOUR separate light systems and we do not own all of them:
 *
 *  - **Pilot lamp** (`setLedPilotLamp`) — the arm / navigation LEDs. This is the bright one, it
 *    is what makes the aircraft visible, and it is fully controllable AND readable
 *    (`getLedPilotLamp`). This class controls it.
 *  - **Auxiliary LED** (`AuxiliaryLedState`) — **READ-ONLY**. It appears only as a field on
 *    `VisualSettingInfo`/`VisualWarningStatus`. There is no setter anywhere in the aar; searched
 *    the whole class surface, not just the flycontroller package. We cannot turn it off.
 *  - **Night light** (`doNightCtrl` → `MAV_CMD_NLIGHT_CTRL`) and **search light**
 *    (`doSlightCtrl` → `MAV_CMD_SLIGHT_CTRL`) — both sent as `AccessoryCmdRequest`, i.e. bolt-on
 *    accessories rather than built-in lights. Not driven here; if an accessory light is ever
 *    fitted to this airframe, it must be added or the pilot will believe they are dark and not be.
 *
 * So: this darkens the navigation LEDs. It is NOT a verified "every exterior light is off".
 * Anything shown to a pilot must say what was actually confirmed.
 *
 * ⚠ REGULATORY. FAA 14 CFR 107.29 requires anti-collision lighting visible for 3 statute miles
 * for night operation. Operating dark is the remote pilot's decision under their own authority
 * or an FAA authorization. The app provides the control; it does not judge the legality.
 */
object AutelLights {

    private const val TAG = "TP2Lights"

    /**
     * What the AIRCRAFT last reported, not what we asked for. Null until it answers.
     *
     * The distinction is the whole point on this control: "I pressed off" and "the aircraft is
     * dark" are different claims, and on this airframe writes have been observed to report
     * success without taking effect. A pilot deciding whether they are visible from the ground
     * needs the aircraft's answer, not ours.
     */
    @Volatile var lamp: LedPilotLamp? = null
        private set

    /** True only when the aircraft has confirmed every pilot lamp is off. Null = not known. */
    val isDark: Boolean? get() = lamp?.let { it == LedPilotLamp.ALL_OFF }

    fun onProductDisconnected() { lamp = null }

    /**
     * Reads the current lamp state off the aircraft.
     *
     * `getLedPilotLamp` sends `MAV_CMD_GET_LED` via `sendPacket(…, callback)` — a one-shot
     * request/response, verified in the aar bytecode, not assumed from the name. It is
     * NOT one of the repeating-subscription getters that once caused a wall strike, so it
     * is safe to call on demand.
     */
    fun refresh(then: (() -> Unit)? = null) {
        val fc = AutelProductHolder.evo2?.flyController ?: run { then?.invoke(); return }
        runCatching {
            fc.getLedPilotLamp(object : CallbackWithOneParam<LedPilotLamp> {
                override fun onSuccess(v: LedPilotLamp?) {
                    lamp = v
                    AppLog.i(TAG, "aircraft reports exterior LEDs = $v")
                    then?.invoke()
                }
                override fun onFailure(error: AutelError?) {
                    lamp = null
                    AppLog.w(TAG, "LED state read failed: ${error?.description} — state unknown")
                    then?.invoke()
                }
            })
        }.onFailure { lamp = null; then?.invoke() }
    }

    /**
     * Turns the pilot lamps all off or all on, then RE-READS to confirm.
     *
     * [then] reports whether the aircraft confirmed the state we asked for — not merely whether
     * the write returned success. A write that reports OK and does not take would otherwise tell
     * a pilot they are dark while the aircraft is lit.
     */
    fun setAllOff(off: Boolean, then: (confirmed: Boolean) -> Unit) {
        val fc = AutelProductHolder.evo2?.flyController ?: run { then(false); return }
        val want = if (off) LedPilotLamp.ALL_OFF else LedPilotLamp.ALL_ON
        AppLog.i(TAG, "exterior LEDs -> $want")
        runCatching {
            fc.setLedPilotLamp(want, object : CallbackWithNoParam {
                override fun onSuccess() {
                    refresh { then(lamp == want) }
                }
                override fun onFailure(error: AutelError?) {
                    AppLog.w(TAG, "exterior LEDs $want failed: ${error?.description}")
                    refresh { then(false) }
                }
            })
        }.onFailure {
            AppLog.w(TAG, "exterior LEDs $want threw: ${it.message}")
            then(false)
        }
    }
}
