package com.autel.sdksample.tak

import android.content.Context
import com.autel.common.error.AutelError
import com.autel.sdk.flycontroller.Evo2FlyController
import com.taklite.util.AppLog

/**
 * Pushes the pilot-configured flight-safety limits (Pre-Flight Setup screen, "Aircraft Settings"
 * section) to the aircraft on connect: max altitude, max distance (radius), RTH altitude.
 *
 * Each is optional — an empty field means "do not override, leave the aircraft's current/default
 * setting alone." Fields are entered/persisted in feet (matching the AGL readout on the flight
 * screen); converted to meters only here, at the point of calling the SDK (confirmed via `javap`
 * against the bundled `autel-sdk-release.aar`, `AutelFlyController` interface — all three take a
 * plain `double` meters; the accepted ranges are checked first, see "Aircraft-accepted ranges"
 * below):
 *   AutelFlyController.setMaxHeight(double meters, CallbackWithNoParam)
 *   AutelFlyController.setMaxRange(double meters, CallbackWithNoParam)
 *   AutelFlyController.setReturnHeight(double meters, CallbackWithNoParam)
 *
 * **There is NO signal-loss failsafe control here, and there must not be one.**
 *
 * This file used to send `doEmergencyAction(GO_HOME)` and Pre-Flight used to offer a picker for
 * it. Both are gone. The reasoning that built them was careful and still wrong:
 * the SDK call does send `MAV_CMD_SET_MSN_EMERGENCY`, and `MAV_CMD_SET_*` does set a parameter
 * rather than command an action — but the parameter has no home on this airframe. The write
 * timed out after 10 s on every attempt for eleven days, the SDK has no getter to read it back
 * (whole-aar sweep: seven classes touch `EmergencyAction`, all write-only), and Autel Explorer
 * V3.1.134 — decompiled off the controller — offers no such setting either. Its only
 * lost-action control is a WAYPOINT MISSION one.
 *
 * The aircraft returns home on link loss because that is the only thing it does. The Enter
 * Flight card states that; nothing here writes it.
 */
object FlightLimitsController {
    private const val TAG = "TP2LimitsAutel"
    private const val PREFS = "takpilot2_tak"
    private const val KEY_MAX_ALT_FT = "limit_max_altitude_ft"
    private const val KEY_MAX_RADIUS_FT = "limit_max_radius_ft"
    private const val KEY_RTH_ALT_FT = "limit_rth_altitude_ft"
    private const val KEY_LOW_BATT_PCT = "limit_low_battery_pct"
    private const val KEY_CRIT_BATT_PCT = "limit_critical_battery_pct"

    private const val FT_PER_M = 3.28084

    fun savedMaxAltitudeFt(context: Context): String = pref(context, KEY_MAX_ALT_FT, "200")
    fun savedMaxRadiusFt(context: Context): String = pref(context, KEY_MAX_RADIUS_FT, "5280")
    fun savedRthAltitudeFt(context: Context): String = pref(context, KEY_RTH_ALT_FT, "150")

    /**
     * Battery thresholds, percent.
     *
     * THESE ARE THE AIRCRAFT'S OWN AUTOMATIC ACTIONS, not app warnings.
     *
     * **Low WARNS. Critical RETURNS HOME** (observed flying 25/20 on the EVO II 640T). Low beeps
     * and puts a low-battery warning on the screen; it does not start a return. The return
     * begins at Critical.
     *
     * ⚠ AN EARLIER READING had Low starting the return and Critical forcing a landing. That
     * reading is kept below because it came from a measured flight and a future observation may
     * have to reconcile the two. Do not "correct" the text back without a new flight.
     *
     * The defaults below (15 / 10) are a deliberate choice, not Autel's: more usable flight
     * time, with a smaller reserve.
     *
     * **SUPERSEDED — an earlier flight record, one full battery in the air at 15/10.** Kept
     * for the trigger-point finding, which still stands. Its Low/Critical ACTIONS do not:
     *  - The alert fires at the threshold EXACTLY — the controller read 15% when it beeped. An
     *    earlier note here claimed the action landed ~1% low (RTH "near 24%" at a 25% setting);
     *    that did not reproduce and should not be relied on. Those 25/15 numbers were the
     *    FACTORY values this airframe shipped with — they were never properties of the
     *    aircraft, just what it was set to at the time. Read the thresholds from the log
     *    (`battery.lowNotifyThreshold`), never from memory.
     *  - The Low alert was recorded as a DEFERRABLE automatic return, cancelled by the physical
     *    RTH button. Under the current reading there is no return to defer at Low: the beep is
     *    the whole action, and pressing RTH acknowledges the alert only. UNRESOLVED — if a future
     *    flight sees the aircraft turn for home at the Low threshold, reopen this.
     *  - Confirmed applied BY THIS APP, not by Explorer: `applyBatteryThresholds` succeeded and
     *    the aircraft read back 0.15/0.1 on every connect of that flight.
     *
     * ⚠ Battery percentage is not written to the debug log anywhere, so the trigger point above
     * rests on the pilot reading the screen. Anything that needs to settle a battery question
     * from the log alone has to add that first.
     *
     * ⚠ Do not set Low at or below Critical. The warning and the return home would arrive in the
     * same moment, which removes the notice that Low exists to give.
     */
    fun savedLowBatteryPct(context: Context): String = pref(context, KEY_LOW_BATT_PCT, "15")
    fun savedCriticalBatteryPct(context: Context): String = pref(context, KEY_CRIT_BATT_PCT, "10")

    private fun pref(context: Context, key: String, default: String): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key, default) ?: default

    // ---- Aircraft-accepted ranges -------------------------------------------------------
    //
    // WHY THIS EXISTS. The SDK documents no range for these setters, but it DOES expose the
    // ranges (FlyControllerParameterRangeManager, reachable from getParameterRangeManager()),
    // and leaving out-of-range values for the aircraft to reject is not a strategy because the
    // rejection is invisible to the pilot.
    //
    // MEASURED: with 50 ft entered here, `setReturnHeight(15)` came back "The command
    // parameters are out of range" and the aircraft carried on holding 46 m / 151 ft. Pre-Flight
    // showed 50 ft the whole time. A readback (getReturnHeight) confirmed the aircraft's real
    // value. Nothing surfaced the difference. That is the bug this section closes.
    //
    // These are plain synchronous getters — getParameterRangeManager() returns the manager
    // directly, and RangePair is a value object. No callback, so none of the
    // is-this-a-subscription hazard that applies to the get*(callback) family.

    /** An aircraft-accepted range, in METERS. */
    data class RangeM(val fromM: Float, val toM: Float) {
        val fromFt: Int get() = Math.round(fromM * FT_PER_M).toInt()
        val toFt: Int get() = Math.round(toM * FT_PER_M).toInt()
        fun containsM(m: Int): Boolean = m >= Math.floor(fromM.toDouble()) &&
            m <= Math.ceil(toM.toDouble())
    }

    private fun rangeManager(): com.autel.common.flycontroller.FlyControllerParameterRangeManager? =
        runCatching { AutelProductHolder.evo2?.flyController?.parameterRangeManager }.getOrNull()

    private fun rangeOf(
        pick: (com.autel.common.flycontroller.FlyControllerParameterRangeManager) ->
            com.autel.common.RangePair<Float>?,
    ): RangeM? = runCatching {
        val pair = rangeManager()?.let(pick) ?: return null
        val from = pair.valueFrom ?: return null
        val to = pair.valueTo ?: return null
        RangeM(from, to)
    }.getOrNull()

    /** Null when no aircraft is connected — the ranges come from the live product. */
    fun returnHeightRange(): RangeM? = rangeOf { it.returnHeightRange }
    fun maxHeightRange(): RangeM? = rangeOf { it.heightRange }
    fun maxRangeRange(): RangeM? = rangeOf { it.rangeOfMaxRange }

    fun save(context: Context, maxAltFt: String, maxRadiusFt: String, rthAltFt: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MAX_ALT_FT, maxAltFt.trim())
            .putString(KEY_MAX_RADIUS_FT, maxRadiusFt.trim())
            .putString(KEY_RTH_ALT_FT, rthAltFt.trim())
            .apply()
    }

    /**
     * Saves the battery levels locally. Separate from [save] because these are the aircraft's
     * own AUTOMATIC ACTIONS rather than geometric limits, and because an invalid pair here is
     * dangerous in a way an out-of-range altitude is not — see [applyBatteryThresholds], which
     * refuses to push low <= critical.
     *
     * Stored as typed, not clamped: a blank field means "keep the aircraft's present setting",
     * matching every other field on the Pre-Flight screen.
     */
    fun saveBatteryLevels(context: Context, lowPct: String, criticalPct: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_LOW_BATT_PCT, lowPct.trim())
            .putString(KEY_CRIT_BATT_PCT, criticalPct.trim())
            .apply()
    }

    @Volatile private var appliedForThisConnect = false

    fun onProductDisconnected() {
        appliedForThisConnect = false
        // The HUD must not keep showing the last aircraft's RTH altitude as if it were current.
        aircraftReturnHeightM = null
        // Same for the battery levels: the next aircraft may be configured differently, and a
        // gauge still banded from the last one would be quietly wrong.
        aircraftWarningPct = null
        aircraftCriticalPct = null
    }

    /**
     * Applies the pilot's limits once per AIRCRAFT CONNECT.
     *
     * WHY THIS MOVED. This used to be driven from `AutelTakBridge`'s first-telemetry one-shot,
     * latched on the TAK connection. That had two consequences:
     *
     *  1. **No TAK connection meant no limits, ever.** The push lived inside the bridge's telemetry
     *     listener, so an aircraft flown without a TAK server connected got none of the pilot's
     *     altitude, distance, RTH or battery settings. A network link to a TAK server has no
     *     business gating aircraft safety parameters.
     *  2. **A reconnect never re-applied.** `onProductConnected()` re-armed the listeners but
     *     left the latch set, so after a battery swap or an aircraft reboot the limits were
     *     silently never re-sent. An RTH altitude changed in Pre-Flight was ignored for two
     *     flights — because nothing between the edit and the flight ever started a TAK
     *     connection.
     *
     * Pre-Flight tells the pilot "Applied automatically when the aircraft connects". This is now
     * driven by exactly that, alongside the other at-connect settings in [AutelProductHolder].
     */
    fun applyAtConnect(context: Context) {
        if (appliedForThisConnect) return
        val fc = AutelProductHolder.evo2?.flyController ?: run {
            AppLog.i(TAG, "applyAtConnect: no aircraft yet, will retry on next connect")
            return
        }
        appliedForThisConnect = true
        // A collector for this push. Nothing reads it — that is the point: a refusal from a
        // re-connect must not appear in the report for a Pre-Flight button press.
        beginApply()
        applyDefaults(context, fc)
    }

    /**
     * Pushes JUST the numeric limits, immediately, if an aircraft is connected.
     * Returns false when there is nothing to push to.
     *
     * Called from the DEBOUNCED Pre-Flight edit path — never wire this straight to a TextWatcher.
     * Those fields fire on every keystroke, so typing "200" without a debounce would send three
     * separate writes (2ft, 20ft, 200ft) to the fly-controller channel. Bursting writes at that
     * channel once put an aircraft into a wall.
     */
    fun pushLimitsNow(context: Context): Boolean {
        val fc = AutelProductHolder.evo2?.flyController ?: return false
        applyNumericLimits(context, fc)
        return true
    }

    /** As [pushLimitsNow], for the battery thresholds. */
    fun pushBatteryNow(context: Context): Boolean {
        AutelProductHolder.evo2 ?: return false
        applyBatteryThresholds(context)
        return true
    }

    /**
     * The RTH altitude **the aircraft last reported**, in metres. Null until it answers.
     *
     * This is what the flight HUD displays. It is deliberately NOT the Pre-Flight value: a
     * requested number and an applied number have been seen to diverge with no way for the pilot
     * to see it. Null renders as "RTH --" — unknown has to look unknown, because a confident wrong
     * number on a flight screen is worse than no number.
     *
     * Only ever written from an aircraft reply.
     */
    @Volatile var aircraftReturnHeightM: Float? = null
        private set

    /**
     * The battery levels the AIRCRAFT actually holds, in percent — null until it tells us.
     *
     * Distinct from [savedLowBatteryPct]/[savedCriticalBatteryPct], which are what the PILOT
     * typed. The two disagree whenever a value has been edited but not yet applied, or when an
     * apply failed, and anything a pilot reads in flight must show the aircraft's state rather
     * than our intent — a battery gauge coloured from an unsent setting is exactly the kind of
     * confident-and-wrong readout this app tries not to produce.
     *
     * NOT read by a call of its own. These are filled in by [AircraftSettingsDump], which
     * already reads both once per connect; issuing a second pair of reads would add traffic to
     * the fly-controller channel for a value we are handed anyway. Only ever written from an
     * aircraft reply.
     */
    // internal, not private, set: FlightWarningsTest injects thresholds. Production
    // writes stay in this file only.
    @Volatile var aircraftWarningPct: Float? = null
        internal set
    @Volatile var aircraftCriticalPct: Float? = null
        internal set

    /**
     * Records a battery level reported by the aircraft. Takes the SDK's FRACTION (0.15) and
     * stores percent; a value outside 0-1 is discarded rather than shown, since a nonsense
     * threshold on the gauge would recolour the whole readout.
     */
    fun reportAircraftBatteryLevel(isCritical: Boolean, fraction: Float?) {
        val pct = fraction?.takeIf { it > 0f && it <= 1f }?.let { it * 100f }
        if (isCritical) aircraftCriticalPct = pct else aircraftWarningPct = pct
    }

    /**
     * One-shot read of the aircraft's RTH altitude, cached into [aircraftReturnHeightM].
     *
     * `getReturnHeight` is a one-shot `ParamsQueryPacket` (`SM_RTH_Height`) — verified in the
     * bytecode, not one of the repeating-listener getters. Called at connect and after an Apply,
     * NOT polled: the fly-controller channel is the one that must not be loaded.
     */
    fun refreshReturnHeight() {
        val fc = AutelProductHolder.evo2?.flyController ?: return
        runCatching {
            fc.getReturnHeight(object : com.autel.common.CallbackWithOneParam<Float> {
                override fun onSuccess(v: Float?) {
                    aircraftReturnHeightM = v
                    AppLog.i(TAG, "aircraft reports RTH altitude = ${v}m " +
                        "(${v?.let { Math.round(it * FT_PER_M) }}ft)")
                }
                override fun onFailure(error: AutelError?) {
                    aircraftReturnHeightM = null
                    AppLog.w(TAG, "RTH altitude read failed: ${error?.description} — HUD shows unknown")
                }
            })
        }
    }

    /** HUD string. "RTH --" whenever the aircraft has not told us. */
    fun rthHudLabel(): String =
        aircraftReturnHeightM?.let { "RTH ${Math.round(it * FT_PER_M)} ft" } ?: "RTH --"

    /**
     * What the report says about ONE limit, independent of the SDK.
     *
     * [gotM] null means the aircraft did not answer. That is not a failure and it is not a
     * match. [wantM] null means the pilot left the field empty, thus nothing was requested and
     * what the aircraft holds is correct by definition.
     */
    data class LimitReadBack(val label: String, val wantM: Int?, val gotM: Float?)

    /**
     * The three states a read-back can end in.
     *
     * UNKNOWN is its own state on purpose. "The aircraft did not answer" is not "the aircraft
     * refused", and to show the second when the first occurred tells the pilot something that is
     * not true. With only two states, a getter that answered with no value became a red "did
     * not take all the settings" with the value missing from the line.
     */
    enum class ReportState { CONFIRMED, UNKNOWN, PROBLEM }

    /** What the aircraft actually reports back, rendered for the pilot. */
    data class ReadBackReport(val text: String, val state: ReportState)

    /**
     * The writes the aircraft REFUSED during one apply.
     *
     * WHY THIS EXISTS. Each setter in this file used to put its failure in the log and nowhere
     * else, thus an aircraft that refused the RF power, the battery levels or the signal-loss
     * behaviour told the pilot nothing. Two of those settings have no getter, thus the read-back
     * cannot find them: if the write does not record its own refusal, the refusal is lost.
     *
     * SDK callbacks arrive on the SDK thread, thus every method is synchronized. A LinkedHashSet
     * keeps one entry for each setting, in the order the settings were sent.
     */
    class RefusedWrites {
        private val labels = LinkedHashSet<String>()
        @Synchronized fun add(label: String) { labels.add(label) }
        @Synchronized fun snapshot(): List<String> = labels.toList()
    }

    /**
     * The collector for the apply that runs now.
     *
     * REPLACED, NEVER CLEARED. Each apply function reads this field ONE time into a local, thus a
     * setter that has already started keeps the instance it read. A new apply — or the automatic
     * push that runs when an aircraft re-connects in the middle of one — cannot take a refusal
     * away from the report it belongs to, and a late callback cannot put a refusal into a report
     * that came later. Do not "simplify" those locals away.
     */
    @Volatile private var refusedThisApply = RefusedWrites()

    /**
     * Starts an apply-and-verify cycle and gives back the collector to hand to [readBack].
     *
     * The automatic at-connect push calls this too. Its collector is never read, which is the
     * point: a refusal from a re-connect must not show in the report for a button press.
     */
    fun beginApply(): RefusedWrites {
        val fresh = RefusedWrites()
        refusedThisApply = fresh
        return fresh
    }

    /**
     * Tolerance, in metres. The values go over the wire as metres rounded from feet, thus an
     * exact comparison would call a correct setting wrong.
     */
    private const val MATCH_TOLERANCE_M = 0.6f

    /**
     * How long the read-back waits for the three getters before it says "unknown".
     *
     * The fly-controller channel times a request out at about 10 seconds, thus this is past the
     * point where an answer can still arrive. It exists for the case where no answer arrives AND
     * no failure is reported either: the Apply button stays disabled until this function reports,
     * and a getter that never called back used to leave it disabled for the life of the screen.
     */
    private const val READ_BACK_TIMEOUT_MS = 12_000L

    /**
     * Builds the line the pilot reads. A pure function: no SDK, no aircraft, no clock. The caller
     * has already collected the answers. This is what the unit tests pin.
     *
     * THE RULE IT HOLDS. Three outcomes, and they do not collapse into two:
     *  - CONFIRMED: each limit answered, and each answer agrees with what was asked.
     *  - UNKNOWN:   nothing disagrees, but the aircraft did not answer for one limit or more.
     *  - PROBLEM:   an answer disagrees, or a write was refused.
     * PROBLEM beats UNKNOWN, and UNKNOWN beats CONFIRMED.
     */
    internal fun buildReadBackReport(
        values: List<LimitReadBack>,
        refusedWrites: List<String>,
    ): ReadBackReport {
        val confirmed = mutableListOf<String>()
        val wrong = mutableListOf<String>()
        val unknown = mutableListOf<String>()

        for (v in values) {
            val got = v.gotM
            if (got == null) { unknown += v.label; continue }
            val ft = Math.round(got * FT_PER_M).toInt()
            if (v.wantM != null && Math.abs(got - v.wantM) > MATCH_TOLERANCE_M) {
                wrong += "${v.label} is $ft ft, not ${Math.round(v.wantM * FT_PER_M)} ft"
            } else {
                confirmed += "${v.label} $ft ft"
            }
        }

        // Only settings the pilot can actually act on reach this report. The two the firmware
        // never accepts — RF power and the signal-loss behaviour — are not recorded as refusals
        // at all (design decision): a pilot cannot correct them, so a red "correct the values"
        // on every single apply would be false instruction, and it is how a dialog meant to
        // carry real problems becomes wallpaper. The signal-loss behaviour is stated on the
        // Enter Flight card instead, where it belongs.
        val state = when {
            wrong.isNotEmpty() || refusedWrites.isNotEmpty() -> ReportState.PROBLEM
            unknown.isNotEmpty() -> ReportState.UNKNOWN
            else -> ReportState.CONFIRMED
        }

        val text = buildString {
            when (state) {
                ReportState.CONFIRMED ->
                    append("The aircraft confirms: ${confirmed.joinToString(", ")}.")
                ReportState.UNKNOWN -> {
                    append("⚠ The aircraft did not answer for: ${unknown.joinToString(", ")}.")
                    if (confirmed.isNotEmpty()) {
                        append(" It confirms: ${confirmed.joinToString(", ")}.")
                    }
                    append(" Press the button again.")
                }
                ReportState.PROBLEM -> {
                    append("⚠ The aircraft did not take all the settings.")
                    if (refusedWrites.isNotEmpty()) {
                        append(" It refused: ${refusedWrites.joinToString(", ")}.")
                    }
                    if (wrong.isNotEmpty()) append(" ${wrong.joinToString(", ")}.")
                    if (confirmed.isNotEmpty()) {
                        append(" It confirms: ${confirmed.joinToString(", ")}.")
                    }
                    if (unknown.isNotEmpty()) {
                        append(" It did not answer for: ${unknown.joinToString(", ")}.")
                    }
                    append(" Correct the values, then press the button again.")
                }
            }
        }
        return ReadBackReport(text, state)
    }

    /**
     * Reads the three numeric limits back off the aircraft and compares them to what Pre-Flight
     * says, then hands the caller a line fit to show a pilot.
     *
     * WHY THIS IS THE IMPORTANT HALF. Every layer has been found capable of lying on its own: `setReturnHeight` reported OK for a value the aircraft did not fly, an out-of-range
     * value was rejected while Pre-Flight kept displaying it, and the pilot flew two sorties on a
     * setting they believed they had changed. The only statement worth making to a pilot is what
     * the aircraft answers when asked.
     *
     * The getters are one-shot ParamsQueryPackets (`SM_RTH_Height` and friends) — verified in the
     * bytecode, NOT the repeating-listener kind. Safe to call on demand.
     */
    fun readBack(context: Context, refused: RefusedWrites?, done: (ReadBackReport) -> Unit) {
        val fc = AutelProductHolder.evo2?.flyController ?: run {
            done(ReadBackReport(
                "Aircraft disconnected before it could be verified.", ReportState.PROBLEM))
            return
        }
        val wantAlt = ftToM(savedMaxAltitudeFt(context))
        val wantRad = ftToM(savedMaxRadiusFt(context))
        val wantRth = ftToM(savedRthAltitudeFt(context))

        val got = java.util.concurrent.ConcurrentHashMap<String, Float>()
        val outstanding = java.util.concurrent.atomic.AtomicInteger(3)
        // ONE-SHOT. This SDK fires some callbacks twice. A second fire drove the old counter to
        // -1, which is also "not greater than 0", thus the report was built and delivered again.
        // It also closes the race between the last getter and the watchdog.
        val reported = java.util.concurrent.atomic.AtomicBoolean(false)
        // ⚠ Built HERE, not as a field of this object. The unit tests run with
        // `returnDefaultValues = true`, thus Looper.getMainLooper() gives null in a test and a
        // field would fail class-initialization — which would take the other suites down with it.
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        var watchdog: Runnable? = null

        fun report() {
            if (!reported.compareAndSet(false, true)) return
            watchdog?.let { main.removeCallbacks(it) }
            done(buildReadBackReport(
                listOf(
                    LimitReadBack("Max altitude", wantAlt, got["alt"]),
                    LimitReadBack("Max distance", wantRad, got["rad"]),
                    LimitReadBack("RTH altitude", wantRth, got["rth"]),
                ),
                refused?.snapshot() ?: emptyList(),
            ))
        }
        fun oneDown() { if (outstanding.decrementAndGet() <= 0) report() }

        watchdog = Runnable {
            if (!reported.get()) {
                AppLog.w(TAG, "read-back timed out after ${READ_BACK_TIMEOUT_MS}ms with " +
                    "${outstanding.get()} getter(s) unanswered — they report as unknown")
                report()
            }
        }
        main.postDelayed(watchdog, READ_BACK_TIMEOUT_MS)

        /**
         * One getter. A failure and a silence are the same thing to the pilot — neither says what
         * the aircraft holds — thus both leave the value absent and the report calls it unknown.
         * The difference stays in the log, where it helps.
         */
        fun read(key: String, label: String, call: (com.autel.common.CallbackWithOneParam<Float>) -> Unit) {
            runCatching {
                call(object : com.autel.common.CallbackWithOneParam<Float> {
                    override fun onSuccess(v: Float?) {
                        if (v == null) AppLog.w(TAG, "$label read answered with no value — unknown")
                        v?.let { got[key] = it }
                        // Keep the flight HUD in step with what we just learned.
                        if (key == "rth") aircraftReturnHeightM = v
                        oneDown()
                    }
                    override fun onFailure(error: AutelError?) {
                        AppLog.w(TAG, "$label read failed: ${error?.description} — unknown")
                        if (key == "rth") aircraftReturnHeightM = null
                        oneDown()
                    }
                })
            }.onFailure {
                AppLog.w(TAG, "$label read threw: ${it.message} — unknown")
                oneDown()
            }
        }
        read("alt", "max altitude") { fc.getMaxHeight(it) }
        read("rad", "max distance") { fc.getMaxRange(it) }
        read("rth", "RTH altitude") { fc.getReturnHeight(it) }
    }

    /** Apply whichever limits are configured. Skips any limit whose field is empty/unparseable —
     *  that limit is simply not touched. */
    fun applyDefaults(context: Context, fc: Evo2FlyController) {
        val maxAltM = ftToM(savedMaxAltitudeFt(context))
        val maxRadiusM = ftToM(savedMaxRadiusFt(context))
        val rthAltM = ftToM(savedRthAltitudeFt(context))
        AppLog.i(TAG, "applyDefaults: maxAltM=$maxAltM maxRadiusM=$maxRadiusM rthAltM=$rthAltM " +
            "(null = not configured, skipped)")

        applyNumericLimits(context, fc)
        applyBatteryThresholds(context)
    }

    /**
     * The three numeric limits, each checked against the aircraft's own accepted range first.
     *
     * Split out from [applyDefaults] so a Pre-Flight edit can push JUST these. Re-running the
     * whole of applyDefaults on every edit would also re-fire the failsafe write, which takes a
     * 10-second timeout to fail on this firmware — turning one keystroke into ten seconds of
     * pending traffic on the fly-controller channel.
     */
    fun applyNumericLimits(context: Context, fc: Evo2FlyController) {
        // Read ONE time into a local — see the note on [refusedThisApply].
        val refused = refusedThisApply
        val maxAltM = ftToM(savedMaxAltitudeFt(context))
        val maxRadiusM = ftToM(savedMaxRadiusFt(context))
        val rthAltM = ftToM(savedRthAltitudeFt(context))

        val rthRange = returnHeightRange()
        val altRange = maxHeightRange()
        val radRange = maxRangeRange()
        AppLog.i(TAG, "aircraft-accepted ranges: returnHeight=$rthRange maxHeight=$altRange " +
            "maxRange=$radRange (null = aircraft did not report one)")

        /** Refuses a push we already know the aircraft will reject, and says why. Pushing it
         *  anyway would only produce "out of range" in the log and leave the pilot's Pre-Flight
         *  value looking applied. */
        fun inRange(label: String, name: String, m: Int, range: RangeM?): Boolean {
            if (range == null || range.containsM(m)) return true
            AppLog.w(TAG, "REFUSING $name(${m}m / ${Math.round(m * FT_PER_M)}ft): aircraft " +
                "accepts ${range.fromM}-${range.toM}m (${range.fromFt}-${range.toFt}ft). " +
                "THE AIRCRAFT KEEPS ITS CURRENT VALUE — Pre-Flight does not match the aircraft.")
            refused.add("$label (outside the range the aircraft accepts)")
            return false
        }

        maxAltM?.let { m ->
            if (!inRange("Max altitude", "setMaxHeight", m, altRange)) return@let
            fc.setMaxHeight(m.toDouble(), object : com.autel.common.CallbackWithNoParam {
                override fun onSuccess() { AppLog.i(TAG, "setMaxHeight($m): OK") }
                override fun onFailure(error: AutelError?) {
                    AppLog.w(TAG, "setMaxHeight($m) failed: ${error?.description}")
                    refused.add("Max altitude")
                }
            })
        }
        maxRadiusM?.let { m ->
            if (!inRange("Max distance", "setMaxRange", m, radRange)) return@let
            fc.setMaxRange(m.toDouble(), object : com.autel.common.CallbackWithNoParam {
                override fun onSuccess() { AppLog.i(TAG, "setMaxRange($m): OK") }
                override fun onFailure(error: AutelError?) {
                    AppLog.w(TAG, "setMaxRange($m) failed: ${error?.description}")
                    refused.add("Max distance")
                }
            })
        }
        rthAltM?.let { m ->
            if (!inRange("RTH altitude", "setReturnHeight", m, rthRange)) return@let
            fc.setReturnHeight(m.toDouble(), object : com.autel.common.CallbackWithNoParam {
                override fun onSuccess() { AppLog.i(TAG, "setReturnHeight($m): OK") }
                override fun onFailure(error: AutelError?) {
                    AppLog.w(TAG, "setReturnHeight($m) failed: ${error?.description}")
                    refused.add("RTH altitude")
                }
            })
        }
    }

    /**
     * SIGNAL-LOSS FAILSAFE: there is no write here any more, on purpose.
     *
     * `doEmergencyAction` used to go out on every connect and every apply. It never landed —
     * it fails with "The execution of this process has timed out" after a full 10 SECONDS,
     * every time (measured, on every apply). That timeout was the whole
     * reason the apply-and-verify cycle had to wait 11 s.
     *
     * It is gone because the setting it targets does not exist on this airframe. The SDK's
     * `EmergencyAction` (NONE/HOVER/LAND/GO_HOME) is generic Autel scaffolding, and Autel
     * Explorer V3.1.134 — decompiled — has no signal-loss picker either: its only
     * lost-action control is a WAYPOINT MISSION setting. The aircraft returns home on link
     * loss because that is the only thing it does, confirmed in flight. Nothing was being
     * set, so nothing is lost by not asking, and the pilot now gets their settings confirmed
     * in about 2 s instead of 11.
     *
     * The Enter Flight card states the behaviour. Do not add this write back.
     */

    /** Parses a feet string to a rounded meters int, or null if blank/unparseable. */
    private fun ftToM(feetStr: String): Int? {
        val ft = feetStr.trim().toDoubleOrNull() ?: return null
        return Math.round(ft / FT_PER_M).toInt()
    }

    /**
     * Pushes the battery thresholds. See [savedLowBatteryPct] for what they actually do.
     *
     * The SDK takes a FRACTION (0.15), not a percent — the aircraft reported 0.25/0.15 for its
     * 25%/15% settings. Sent low-first so that if only one call lands, the aircraft is left with
     * a return level below its landing level rather than the other way round.
     */
    private fun applyBatteryThresholds(context: Context) {
        // Read ONE time into a local — see the note on [refusedThisApply].
        val refused = refusedThisApply
        val bat = AutelProductHolder.evo2?.battery ?: return
        val low = savedLowBatteryPct(context).trim().toFloatOrNull()
        val crit = savedCriticalBatteryPct(context).trim().toFloatOrNull()
        if (low == null || crit == null) {
            AppLog.w(TAG, "battery thresholds not configured — leaving the aircraft's own values")
            return
        }
        if (low <= crit) {
            AppLog.e(TAG, "REFUSING battery thresholds: low ($low%) is not above critical ($crit%) " +
                "— the aircraft would return home and force-land at the same moment")
            refused.add("Battery levels (Warning must be above Critical)")
            return
        }
        bat.setLowBatteryNotifyThreshold(low / 100f, object : com.autel.common.CallbackWithNoParam {
            override fun onSuccess() { AppLog.i(TAG, "low battery threshold set to $low%: OK") }
            override fun onFailure(error: AutelError?) {
                AppLog.w(TAG, "low battery threshold $low% failed: ${error?.description}")
                refused.add("Low battery level")
            }
        })
        bat.setCriticalBatteryNotifyThreshold(crit / 100f, object : com.autel.common.CallbackWithNoParam {
            override fun onSuccess() { AppLog.i(TAG, "critical battery threshold set to $crit%: OK") }
            override fun onFailure(error: AutelError?) {
                AppLog.w(TAG, "critical battery threshold $crit% failed: ${error?.description}")
                refused.add("Critical battery level")
            }
        })
    }

    // ⚠ THE RF POWER REGION IS NOT WRITTEN (design decision). This file used to push
    // `setRFPower(FCC)` at every connect and on every apply, and every path to it was refused:
    // the public setter, the retry, and the internal `DspRFManager2.enableFCCMode` route that
    // Autel Explorer uses (the Debug screen's probe). The region is pinned on this controller
    // and it is an Autel limit. The write is gone so the log stops carrying a failure nobody can act on; do not
    // put it back and do not open it again.
}
