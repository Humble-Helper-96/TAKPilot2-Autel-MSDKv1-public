package com.autel.sdksample.tak

import android.os.Handler
import android.os.Looper
import com.autel.common.CallbackWithNoParam
import com.autel.common.CallbackWithOneParam
import com.autel.common.camera.XT706.PipBlenderBase
import com.autel.common.camera.XT706.PipBlenderBaseType
import com.autel.common.camera.XT706.PipBlenderRatio
import com.autel.common.error.AutelError
import com.autel.sdk.camera.AutelXT706
import com.taklite.util.AppLog

/**
 * How the camera BLENDS in its PictureInPicture view: the thermal picture, plain, in the
 * centre of the visible frame.
 *
 * ## What the two controls do, measured (XL726, firmware V5.0.6.49)
 *
 * - **`PipBlenderBase` chooses what fills the centre window.** `IR` puts the thermal picture
 *   there whole, in its palette (white hot in the test), at the visible lens's scale, so the
 *   fence line and the tree trunks inside the window sit on the same objects outside it.
 *   `Visible` keeps the colour picture in the window and lays the thermal picture's EDGES over
 *   it as bright outlines. `None` is what the camera holds from the factory.
 * - **`PipBlenderRatio` is a 16-bit weight pair, IR first.** Autel Explorer's slider writes
 *   IR = pct/100 × 65536 and Visible = 65535 − IR. It sets how strongly the thermal layer shows
 *   over a `Visible` base; over an `IR` base the window read as plain thermal at 20 % and at
 *   100 %. It is written here so the pair is a known value rather than the factory's
 *   65535 / 1.
 *
 * The goal is the thermal image overlaid on the visible one and meshed, thus the
 * base is `IR`. The edge-outline look is one write away if a mission wants it.
 *
 * ⚠ **THE IR OFFSET IS NOT WRITTEN.** `SetIrPosition` exists (DeltaX / DeltaY, clamped by the
 * camera to ±20 px, measured), but it is the camera's own registration trim, it read 0,0
 * with the pictures already lined up, and an application that overwrote it on every
 * connect would erase a correction a pilot made in Autel Explorer. Read only, never written.
 *
 * ## Applied at every connect, like [AutelRecordingFormat], for the same reason
 *
 * The camera keeps these across power cycles and Autel Explorer is free to change them. One
 * write per camera session, never on a timer. ⚠ Both writes were honoured DURING a recording
 * (read back mid-record), unlike the display mode — but this still refuses while
 * recording, because the read-back that verifies it shares the channel that was measured
 * timing out after RECORD_START, and a camera write without its read-back is not allowed.
 *
 * ## Verified by read-back
 *
 * `getPipBlenderBase` and `getPipBlenderRatio` are single `CameraHttpRequest` POSTs, not 2 Hz
 * subscriptions — confirmed in the aar bytecode before use. Neither value is
 * in `GetAllSettings`, so they are read one each.
 *
 * ⚠ `setPipBlenderBase` takes a raw `String`; the SDK's own `PipBlenderBaseType` enum is dead
 * code that nothing in the aar references. The enum's `value()` is used here anyway so the
 * string is never typed by hand — a wrong string would get status 0 and change nothing.
 */
object AutelBlendFormat {

    private const val TAG = "TP2Blend"

    /**
     * Thermal, whole, in the centre window — which on this firmware is `None`, NOT `IR`
     * (measured).
     *
     * ⚠ **`IR` IS THE EDGE-OUTLINE LOOK HERE, AND THIS CONSTANT ONCE HELD IT.** The file
     * header's earlier measurement describes `Visible` as the base that "lays the thermal
     * picture's EDGES over it as bright outlines" and `IR` as the plain thermal window. On the
     * aircraft that is the wrong way round: `IR` put white outlines on every panel gap, badge
     * and wheel arch, and the pilot saw it in Autel Explorer too, because the setting belongs
     * to the CAMERA and both applications share it.
     *
     * `None` is what the camera's own control selects when the outlines are switched off — read
     * straight off the camera the moment before this object overwrote it, which is the only
     * reason it is known. It is also the factory value.
     *
     * ⚠ **THE RATIO IS NOT PART OF THIS.** The camera held `ratio=32768/32767` — this object's
     * own pair — in the state the pilot chose, thus the pair below reproduces it unchanged.
     */
    val BASE: String = PipBlenderBaseType.None.value()

    /**
     * How much of the THERMAL layer shows in the window, as Explorer's own slider writes it:
     * IR = pct/100 × 65536, Visible = 65535 − IR. 49151/16384 is 75 %.
     *
     * ⚠ **IT IS AN OPACITY MIX, AND IT IS LIVE UNDER `None`** (measured). Two
     * earlier claims in this file were wrong and are corrected here: that the ratio "does not
     * change the look over an IR base", and that `None` means no blending. Moving
     * Explorer's slider takes the window from no thermal at all, through the mixed
     * look, to saturated at the top; and the window under `None` plainly carries VISIBLE-lens
     * detail — gravel sharp enough to count the stones — which a 640x512 thermal cannot
     * produce. Explorer's slider also reads back 50 % after this application connects, which is
     * this pair rendered as a percentage.
     *
     * ⚠ **THIS IS A DEFAULT, NOT A CONSTANT IN SPIRIT.** The pilot adjusts it in flight with
     * the slider ([sendPercent]); every connect writes the pilot's saved value ([percent]), or
     * this default when none is saved.
     */
    const val DEFAULT_PERCENT = 75

    /** The pair the camera wants, from a thermal percentage. Explorer's own arithmetic:
     *  IR = pct/100 × 65536, clamped to the 16-bit range, Visible = 65535 − IR. Pure, so
     *  [AutelBlendFormatTest] can pin it without a camera. */
    fun ratioFor(percent: Int): Pair<Int, Int> {
        val ir = ((percent.coerceIn(0, 100) / 100.0) * 65536).toInt().coerceIn(0, 65535)
        return ir to (65535 - ir)
    }

    private const val PREFS = "takpilot2_tak"
    private const val KEY_PERCENT = "pip_blend_percent"

    /** What the pilot last set with the slider, or [DEFAULT_PERCENT]. */
    fun percent(context: android.content.Context): Int =
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .getInt(KEY_PERCENT, DEFAULT_PERCENT)

    fun setPercent(context: android.content.Context, percent: Int) {
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .edit().putInt(KEY_PERCENT, percent.coerceIn(0, 100)).apply()
    }

    /**
     * Sends a thermal percentage to the camera, for the pilot's slider.
     *
     * ⚠ **THIS IS THE ONE WRITE HERE THAT IS NOT AT CONNECT, AND IT IS A BUTTON PRESS, NOT A
     * TIMER.** The strict write discipline is about the fly-controller channel; this is the camera's, the
     * same channel the zoom and the palette already use from a tap. The caller throttles the
     * drag — see FlightActivity — so a stroke of the thumb is a few writes and not one per
     * pixel.
     *
     * No read-back: the pilot is looking at the result. A verify that lands 1.5 s after a
     * control the pilot is still moving would be arguing with them.
     */
    fun sendPercent(camera: AutelXT706?, percent: Int) {
        camera ?: return
        if (AutelProductHolder.isRecording) {
            AppLog.i(TAG, "blend ratio $percent% not sent: the camera is recording")
            return
        }
        val (ir, vis) = ratioFor(percent)
        runCatching {
            camera.setPipBlenderRatio(ir, vis, object : CallbackWithNoParam {
                override fun onSuccess() { AppLog.i(TAG, "blend ratio -> $percent% ($ir/$vis)") }
                override fun onFailure(error: AutelError?) {
                    AppLog.w(TAG, "blend ratio $percent% failed: ${error?.description}")
                }
            })
        }.onFailure { AppLog.w(TAG, "setPipBlenderRatio threw: ${it.message}") }
    }

    /** The same settle as the recording format and the lens verify. */
    const val VERIFY_SETTLE_MS = 1500L

    /** What the last read-back said. Null until a camera has answered once. */
    @Volatile
    var lastVerified: Boolean? = null
        private set

    private val handler = Handler(Looper.getMainLooper())

    /**
     * True when the camera reports exactly what was asked for. Pure, so the comparison the
     * verify depends on can be tested without an aircraft. A null field is NOT a match.
     */
    fun agrees(base: String?, ratioIr: Int?, ratioVisible: Int?): Boolean =
        base == BASE && ratioIr == wanted.first && ratioVisible == wanted.second

    /** The pair the last [applyAtConnect] asked for — what [agrees] compares against. The
     *  pilot's slider moves it, so it cannot be a constant. */
    @Volatile
    var wanted: Pair<Int, Int> = ratioFor(DEFAULT_PERCENT)
        private set

    /** What the camera held the last time this object looked, BEFORE any write of ours.
     *  Null until a camera has answered once. Shown on the Debug screen. */
    @Volatile
    var heldBase: String? = null
        private set
    @Volatile
    var heldRatio: Pair<Int?, Int?>? = null
        private set

    /**
     * Applies the blend format, and READS THE CAMERA FIRST.
     *
     * ⚠ **THE READ IS NOT DECORATION. IT IS THE THING THIS CODE ONCE LACKED.** The
     * write worked perfectly from the day it landed; it wrote the WRONG VALUE, and neither the
     * log nor the read-back could show it, because the only read happened AFTER the write and
     * so could only ever confirm our own value back to us. Measured: the outlines
     * were switched off by hand in Autel Explorer, this application connected, wrote
     * `base=IR ratio=32768/32767`, read back `agrees=true` — and the outlines were on the
     * screen again. Every connect had done the same, invisibly.
     *
     * So: never overwrite a camera setting without recording what was there. The line below is
     * what tells the next person which value the pilot actually wanted.
     */
    fun applyAtConnect(camera: AutelXT706?) {
        camera ?: return
        val pct = com.autel.sdksample.TestApplication.getInstance()?.let { percent(it) }
            ?: DEFAULT_PERCENT
        wanted = ratioFor(pct)
        if (AutelProductHolder.isRecording) {
            AppLog.i(TAG, "not applying the blend format: the camera is recording")
            return
        }
        readHeld(camera) { applyAfterRead(camera) }
    }

    /** Reads the base and the ratio the camera is holding, then runs [then] whatever happened.
     *  Both are single POSTs, not subscriptions, same as the verify. */
    private fun readHeld(camera: AutelXT706, then: () -> Unit) {
        camera.getPipBlenderBase(object : CallbackWithOneParam<PipBlenderBase> {
            override fun onSuccess(b: PipBlenderBase?) {
                heldBase = b?.Base
                camera.getPipBlenderRatio(object : CallbackWithOneParam<PipBlenderRatio> {
                    override fun onSuccess(r: PipBlenderRatio?) {
                        heldRatio = r?.IR to r?.Visible
                        AppLog.i(TAG, "the camera HELD, before this write: " +
                            "base=$heldBase ratio=${r?.IR}/${r?.Visible}")
                        then()
                    }
                    override fun onFailure(error: AutelError?) {
                        heldRatio = null
                        AppLog.w(TAG, "the camera HELD base=$heldBase; " +
                            "getPipBlenderRatio failed: ${error?.description}")
                        then()
                    }
                })
            }
            override fun onFailure(error: AutelError?) {
                heldBase = null; heldRatio = null
                AppLog.w(TAG, "getPipBlenderBase failed before the write: ${error?.description}")
                then()
            }
        })
    }

    private fun applyAfterRead(camera: AutelXT706) {
        AppLog.i(TAG, "applying blend format: base=$BASE " +
            "ratio=${wanted.first}/${wanted.second}")
        camera.setPipBlenderBase(BASE, object : CallbackWithNoParam {
            override fun onSuccess() {
                AppLog.i(TAG, "setPipBlenderBase($BASE): OK")
                setRatio(camera)
            }
            override fun onFailure(error: AutelError?) {
                AppLog.w(TAG, "setPipBlenderBase($BASE) failed: ${error?.description}")
                setRatio(camera)
            }
        })
    }

    private fun setRatio(camera: AutelXT706) {
        camera.setPipBlenderRatio(wanted.first, wanted.second, object : CallbackWithNoParam {
            override fun onSuccess() {
                AppLog.i(TAG, "setPipBlenderRatio(${wanted.first}, ${wanted.second}): OK")
                handler.postDelayed({ verify(camera) }, VERIFY_SETTLE_MS)
            }
            override fun onFailure(error: AutelError?) {
                AppLog.w(TAG, "setPipBlenderRatio failed: ${error?.description}")
                // Verify anyway: what the camera HOLDS is the only fact.
                handler.postDelayed({ verify(camera) }, VERIFY_SETTLE_MS)
            }
        })
    }

    private fun verify(camera: AutelXT706) {
        camera.getPipBlenderBase(object : CallbackWithOneParam<PipBlenderBase> {
            override fun onSuccess(b: PipBlenderBase?) {
                val base = b?.Base
                camera.getPipBlenderRatio(object : CallbackWithOneParam<PipBlenderRatio> {
                    override fun onSuccess(r: PipBlenderRatio?) {
                        val ok = agrees(base, r?.IR, r?.Visible)
                        lastVerified = ok
                        AppLog.i(TAG, "blend format read-back: base=$base " +
                            "ratio=${r?.IR}/${r?.Visible} agrees=$ok")
                        if (!ok) {
                            AppLog.w(TAG, "THE CAMERA DID NOT TAKE THE BLEND FORMAT. Asked for " +
                                "$BASE ${wanted.first}/${wanted.second}, " +
                                "it holds $base ${r?.IR}/${r?.Visible}")
                        }
                    }
                    override fun onFailure(error: AutelError?) {
                        lastVerified = null
                        AppLog.w(TAG, "getPipBlenderRatio failed: ${error?.description}")
                    }
                })
            }
            override fun onFailure(error: AutelError?) {
                lastVerified = null
                AppLog.w(TAG, "getPipBlenderBase failed: ${error?.description}")
            }
        })
    }
}
