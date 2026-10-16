package com.autel.sdksample.tak

/**
 * When a screen-capture encoder has stopped, as opposed to merely fallen behind.
 *
 * ⚠ **THIS EXISTS BECAUSE A STALLED ENCODER KILLED A CONTROLLER.** The encoder
 * stopped releasing buffers, so the release fence on our MediaProjection VirtualDisplay never
 * signalled, so SurfaceFlinger's main thread sat in `Fence::waitForever` — a wait with NO
 * TIMEOUT — and the whole display died. Force-stopping the application did not recover it.
 * Killing and respawning SurfaceFlinger did not recover it. Only a reboot did.
 *
 * The application cannot fix `Fence::waitForever`; that is the platform's. What it can do is
 * stop feeding a VirtualDisplay whose consumer has stopped.
 *
 * ## ⚠ THE TEST IS "NO OUTPUT AT ALL", AND IT IS NOT "BELOW PROFILE"
 *
 * These are different signals and confusing them would be worse than having no watchdog. On a
 * measured 49-minute run the encoder dipped to 10.9-11.8 fps against a 15 fps profile SIX
 * times, and every one recovered on its own within 10 to 20 seconds. A watchdog that fired on a
 * SLOW encoder would have torn the stream down six times on a link that was working perfectly —
 * `drops=0` for the whole run.
 *
 * A dip is still producing frames, about one every 90 ms. This policy only fires when NOTHING
 * has come out for [STALL_MS], which at a working 15 fps is about 150 missed frames in a row.
 *
 * ## ⚠ WHAT IT CANNOT DO
 *
 * The causality is encoder-stall FIRST and compositor-wedge SECOND, seconds apart. If the fence
 * is already dead when this fires, the teardown may itself block and nothing is saved — the
 * measured recovery attempts show that killing the entire process does not release that
 * wait. What this reliably converts is the SURVIVABLE case: an encoder that stops while the
 * compositor is still alive becomes a dropped stream the pilot can restart, instead of a feed
 * the team watches go silent with nobody on the controller knowing.
 *
 * Do not describe it as a fix for the freeze. It is a way of not causing it.
 */
object ScreenCaptureStallPolicy {

    /**
     * How long the encoder may produce NOTHING before the capture is torn down.
     *
     * ⚠ **GENEROUS ON PURPOSE, AND THE FALSE POSITIVE IS THE EXPENSIVE DIRECTION.** Firing
     * wrongly takes a working video feed away from a team in the field; firing late costs a few
     * more seconds of a feed that has already stopped. There is no case for tightening this
     * without evidence of a real stall that it failed to catch.
     *
     * ⚠ **A VIRTUAL DISPLAY ONLY PRODUCES A FRAME WHEN THE CONTENT CHANGES**, so a genuinely
     * static screen legitimately produces no output. The flight screen is never static while it
     * is up — the clock carries seconds and the HUD repaints twice a second — but that is a
     * property of THAT SCREEN, not a guarantee of the platform. Ten seconds is far past any
     * repaint interval this application has, which is what keeps a quiet screen from tripping
     * it.
     */
    const val STALL_MS = 10_000L

    /**
     * True when the capture should be torn down.
     *
     * @param running          whether the stream is supposed to be live at all. A stopped
     *                         stream produces no output for an excellent reason.
     * @param lastOutputMs     monotonic mark of the last REAL encoded buffer, or a negative
     *                         value before the first one.
     * @param nowMs            monotonic now.
     *
     * ⚠ **BEFORE THE FIRST FRAME THIS IS NEVER TRUE.** A `lastOutputMs` below zero means the
     * encoder has not yet produced anything since it started, and start-up — the codec
     * configuring, the first IDR — is not a stall. The caller marks the time at start so that a
     * codec which never produces ANYTHING is still caught; this guard is for the case where it
     * forgot to.
     */
    fun isStalled(running: Boolean, lastOutputMs: Long, nowMs: Long): Boolean {
        if (!running) return false
        if (lastOutputMs < 0) return false
        return nowMs - lastOutputMs >= STALL_MS
    }
}
