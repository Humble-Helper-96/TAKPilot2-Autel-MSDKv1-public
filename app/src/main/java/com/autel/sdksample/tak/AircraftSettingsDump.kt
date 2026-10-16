package com.autel.sdksample.tak

import com.autel.common.CallbackWithOneParam
import com.autel.common.error.AutelError
import com.taklite.util.AppLog

/**
 * Reads every aircraft/controller setting this SDK exposes and writes them to the log, once per
 * connect.
 *
 * WHY. Most of these values are configured in Autel's own app, not here, and until now nothing
 * recorded what they actually were. That left several questions unanswerable from this side:
 * what the stick-mode enum corresponds to on the controller's own "Mode 1/2/3" screen, what the
 * normal gimbal-dial and yaw values are for this airframe, and what the low-battery thresholds
 * are set to — the last of which is directly relevant to the unexplained RTH behaviour (one
 * descent began near 14%, another near 24%).
 *
 * The intended workflow: set everything in Autel's app, close it
 * completely, then open this app. What lands in the log is the aircraft's real configuration.
 *
 * STRICTLY READ-ONLY. Every call here is a getter. Nothing in this file writes to the aircraft
 * or the controller, so it can run automatically at connect without changing how anything flies.
 *
 * ⚠ Autel's channels are SINGLE-CLIENT. If Autel's app is still running, these reads will time
 * out or return stale values and the dump will be misleading rather than empty — close it fully
 * before trusting what appears here.
 */
object AircraftSettingsDump {
    private const val TAG = "AircraftSettings"

    /** How long to wait before re-issuing a read that timed out. */
    private const val RETRY_DELAY_MS = 6000L

    /** Total attempts per value. One retry: enough to survive a slow camera init, not enough
     *  to become traffic on a channel whose saturation once put an aircraft into a wall. */
    private const val MAX_ATTEMPTS = 2

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    @Volatile private var dumpedForThisConnect = false

    fun onProductDisconnected() { dumpedForThisConnect = false }

    /**
     * Runs the dump once per connect.
     *
     * Deliberately fire-and-forget with each value logged as it lands, rather than gathered into
     * one tidy block: several of these calls silently never call back on some firmware, and a
     * dump that waits for a full set would then print nothing at all. A partial answer that
     * arrives beats a complete one that does not.
     */
    fun dumpOnce() {
        if (dumpedForThisConnect) return
        dumpedForThisConnect = true
        val evo = AutelProductHolder.evo2 ?: run { dumpedForThisConnect = false; return }
        AppLog.i(TAG, "---- aircraft settings dump (read-only) ----")

        // Retries once on failure. WHY: once this dump ran 4s after connect, landed in
        // the middle of XT709 camera enumeration, and SIXTEEN of its reads — every fly-controller
        // parameter, including flight.returnHeight_m — expired together on the SDK's shared 10s
        // timeout. The reads themselves are fine; they were issued into a saturated channel. The
        // dump written to answer these questions was therefore never able to answer them.
        //
        // The retry is deliberately ONE. These are diagnostics, and re-issuing reads at volume on
        // a contended fly-controller channel is the exact shape of the mistake that caused the
        // wall strike. A value that stays missing is a fine outcome; a read storm is not.
        fun <T> read(
            name: String,
            attempt: Int = 1,
            onValue: ((T?) -> Unit)? = null,
            call: (CallbackWithOneParam<T>) -> Unit,
        ) {
            runCatching {
                call(object : CallbackWithOneParam<T> {
                    override fun onSuccess(v: T?) {
                        AppLog.i(TAG, "  $name = $v" + if (attempt > 1) " (attempt $attempt)" else "")
                        runCatching { onValue?.invoke(v) }
                    }
                    override fun onFailure(error: AutelError?) {
                        if (attempt < MAX_ATTEMPTS && AutelProductHolder.evo2 != null) {
                            handler.postDelayed({
                                // Re-check: the aircraft may have gone away while we waited.
                                if (AutelProductHolder.evo2 != null) read(name, attempt + 1, onValue, call)
                                else AppLog.i(TAG, "  $name = <failed, aircraft gone before retry>")
                            }, RETRY_DELAY_MS)
                        } else {
                            AppLog.i(TAG, "  $name = <failed after $attempt attempt(s): " +
                                "${error?.description}>")
                        }
                    }
                })
            }.onFailure { AppLog.i(TAG, "  $name = <threw: ${it.message}>") }
        }

        // SUBSCRIPTION CHECK, verified in the aar: every getter read here is a ONE-SHOT
        // request/response — the fly-controller getters route through `query*` →
        // `getParamsGetPacket` → `ParamsQueryPacket` → `sendPacket(…, callback)` (one reply); the
        // RC getters are measured one-shot; the camera getters are one-shot HTTP. NONE is a
        // repeating subscription. The one fly-controller getter that WAS a 2Hz subscription,
        // `getVisualSettingInfo`, is deliberately absent — its vision fields are read from
        // AutelAvoidance's cache instead (see the vision block below).
        val fc = evo.flyController
        read<String>("aircraft.serial") { fc.getSerialNumber(it) }
        read<Float>("flight.maxHeight_m") { fc.getMaxHeight(it) }
        read<Float>("flight.maxRange_m") { fc.getMaxRange(it) }
        read<Float>("flight.returnHeight_m") { fc.getReturnHeight(it) }
        read<Float>("flight.maxHorizontalSpeed_ms") { fc.getMaxHorizontalSpeed(it) }
        read<Float>("flight.maxVZUp_ms") { fc.getMaxVZUp(it) }
        read<Float>("flight.maxVZDown_ms") { fc.getMaxVZDown(it) }

        // Stick-feel family. These are the AIRCRAFT's response curves, distinct from the
        // controller's dial/yaw values below — worth capturing both to see which one the pilot
        // actually notices.
        read<Float>("feel.yawStrokeSensitivity") { fc.getYawStrokeSensitivity(it) }
        read<Float>("feel.pitchSensitivity") { fc.getPitchSensitivity(it) }
        read<Float>("feel.rollSensitivity") { fc.getRollSensitivity(it) }
        read<Float>("feel.brakeSensitivity") { fc.getBrakeSensitivity(it) }
        read<Float>("feel.gasPedalSensitivity") { fc.getGasPedalSensitivity(it) }
        read<Float>("feel.attiSensitivity") { fc.getATTISensitivity(it) }

        val rc = evo.remoteController
        read<String>("rc.serial") { rc.getSerialNumber(it) }
        // The one that settles the Mode 1/2/3 question: whatever this reports IS whatever the
        // controller's own Command Stick Mode screen currently has selected.
        read<com.autel.common.remotecontroller.RemoteControllerCommandStickMode>(
            "rc.commandStickMode") { rc.getCommandStickMode(it) }
        read<Int>("rc.gimbalDialAdjustSpeed") { rc.getGimbalDialAdjustSpeed(it) }
        read<Float>("rc.yawCoefficient") { rc.getYawCoefficient(it) }
        // The ACCEPTED RANGES, not just the current values. Without these a value the controller
        // silently clamps looks identical to one it honoured — which is how Precision yaw spent
        // two tuning rounds pinned at the hardware minimum while the log said it had been set.
        runCatching {
            rc.parameterRangeManager?.let { r ->
                AppLog.i(TAG, "  rc.range.yawCoefficient = ${r.yawCoefficient}")
                AppLog.i(TAG, "  rc.range.dialAdjustSpeed = ${r.dialAdjustSpeed}")
            } ?: AppLog.i(TAG, "  rc.range = <not reported>")
        }.onFailure { AppLog.w(TAG, "  rc.range read failed: ${it.message}") }
        // NO RF-power read. Nothing in this application requests or changes the radio
        // transmitter power — not even a read (design decision).
        read<com.autel.common.remotecontroller.RemoteControllerParameterUnit>(
            "rc.lengthUnit") { rc.getLengthUnit(it) }

        // Battery thresholds — these are NOTIFY levels, not the forced-RTH trigger, but they are
        // the only battery policy the SDK exposes and worth having on record next to the two
        // low-battery events that behaved differently.
        val bat = evo.battery
        // These two are also PUBLISHED, not just logged: the flight screen's battery gauge and
        // the ENTER FLIGHT card colour themselves from what the aircraft actually holds rather
        // than from the pilot's saved preference, and this dump is already reading both. A
        // second pair of reads for the same values would be traffic on the fly-controller
        // channel for nothing — see this file's header on why that matters here.
        read<Float>("battery.lowNotifyThreshold",
            onValue = { FlightLimitsController.reportAircraftBatteryLevel(false, it) }) {
            bat.getLowBatteryNotifyThreshold(it)
        }
        read<Float>("battery.criticalNotifyThreshold",
            onValue = { FlightLimitsController.reportAircraftBatteryLevel(true, it) }) {
            bat.getCriticalBatteryNotifyThreshold(it)
        }
        read<String>("battery.serial") { bat.getSerialNumber(it) }

        // Vision positioning — the downward sensors that HOLD A HOVER, distinct from the
        // obstacle-avoidance switches. Read because a hover that wanders or circles is most
        // often a positioning or compass problem, and until now nothing recorded whether the
        // vision system was even switched on. This app does not set it.
        //
        // Read the vision fields from AutelAvoidance's cache — do NOT open our own
        // getVisualSettingInfo. That call is an uncancellable ~2Hz subscription, not a one-shot;
        // opening it here just to log four lines left a permanent stream on the fly-controller
        // channel (the repeating-getter trap behind the wall strike). AutelAvoidance
        // owns the single subscription and publishes the latest object; we read it.
        val v = AutelAvoidance.latestVisualSetting
        if (v == null) {
            AppLog.i(TAG, "  vision.* = <not reported yet>")
        } else {
            AppLog.i(TAG, "  vision.locationEnabled = ${v.isVisualLocationEnable}")
            AppLog.i(TAG, "  vision.landingAccurately = ${v.isLandingAccuratelyEnable}")
            AppLog.i(TAG, "  vision.mainFlyState = ${v.visualMainFlyState}")
            AppLog.i(TAG, "  vision.warnState = ${v.visualWarnState}")
            AppLog.i(TAG, "  (avoidance switches are logged by AutelAvoidance)")
        }
    }
}
