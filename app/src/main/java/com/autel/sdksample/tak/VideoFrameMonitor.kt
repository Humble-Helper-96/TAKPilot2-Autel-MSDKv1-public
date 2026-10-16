package com.autel.sdksample.tak

/**
 * Watches the FPV picture arrive, so a stutter stops being a thing only the pilot saw.
 *
 * ⚠ **A PILOT CAN SEE STUTTERS THE REST OF THE LOG CANNOT.** Pilot-reported stutters have shown
 * no error, no reconnect, no stalled tick, flat CPU and GPU, and `sig=100% dsp=100%` on every
 * sample either side — which is what it looks like when the cause is below where the rest of
 * the instrumentation reaches.
 *
 * `AutelCodecView` hands over a per-frame callback (`onRenderFrameTimestamp`), and a stutter IS
 * frames not arriving on time. That callback is the one signal that describes the fault, so
 * this class consumes it.
 *
 * ## What it measures, and why it measures two things
 *
 * **Arrival gap** is this application's own clock between rendered frames. It answers "did the
 * picture freeze", which is the pilot's question.
 *
 * **The SDK's timestamp delta** is carried alongside, uninterpreted. If the two disagree the
 * cause is narrowed for free: frames arriving late in a burst while their timestamps stay
 * evenly spaced is a LINK or DECODE hiccup — the pictures exist and got here late. Timestamps
 * that jump with the arrivals means the source itself skipped, which is the aircraft or the
 * encoder, not the controller.
 *
 * ⚠ THE TIMESTAMP'S UNITS ARE NOT DOCUMENTED and are deliberately NOT assumed here. Nothing
 * branches on it; it is reported raw so the first flight tells us what it is. Guessing a unit
 * and dividing by it is how a diagnostic starts lying about the thing it was added to explain.
 *
 * ## It must not cost anything
 *
 * This runs on the SDK's render thread, once per frame, on the video pipeline, which must
 * never regress. The common path is a subtraction and two comparisons, and it allocates only
 * when it has something to say. A stall is rate-limited, and a periodic summary proves the
 * monitor is alive rather than merely silent — a diagnostic that says nothing is
 * indistinguishable from one that is not running.
 */
internal class VideoFrameMonitor(
    private val stallMs: Long = STALL_MS,
    private val summaryMs: Long = SUMMARY_MS,
    private val cooldownMs: Long = STALL_COOLDOWN_MS,
) {

    sealed class Report {
        /** The picture froze for [gapMs]. [ptsDelta] is the SDK timestamp's own step, raw. */
        data class Stall(val gapMs: Long, val ptsDelta: Long, val sinceLastStallMs: Long) : Report()

        /** Routine "still running" line: what the picture has been doing for a period. */
        data class Summary(
            val frames: Int,
            val periodMs: Long,
            val worstGapMs: Long,
        ) : Report() {
            /** Frames per second over the period, to one decimal. */
            val fps: Double get() = if (periodMs <= 0) 0.0 else frames * 1000.0 / periodMs
        }
    }

    private var lastFrameMs = 0L
    private var lastPts = 0L
    private var lastStallMs = 0L
    private var periodStartMs = 0L
    private var framesThisPeriod = 0
    private var worstGapThisPeriod = 0L
    private var started = false

    /**
     * Call once per rendered frame.
     *
     * @return at most ONE report, or null on the ordinary frame. A stall wins over a summary
     *   that falls on the same frame; the summary follows on the next one, which matters to
     *   nobody and keeps the return allocation-free in the common case.
     */
    fun onFrame(nowMs: Long, pts: Long): Report? {
        if (!started) {
            // ⚠ THE FIRST FRAME IS NOT A GAP. There is nothing to measure from, and treating the
            // wait for the first picture as a stall would report one on every connect.
            started = true
            lastFrameMs = nowMs
            lastPts = pts
            periodStartMs = nowMs
            framesThisPeriod = 1
            return null
        }

        val gap = nowMs - lastFrameMs
        val ptsDelta = pts - lastPts
        lastFrameMs = nowMs
        lastPts = pts
        framesThisPeriod++
        if (gap > worstGapThisPeriod) worstGapThisPeriod = gap

        if (gap >= stallMs) {
            // ⚠ RATE-LIMITED ON ITS OWN COOLDOWN, NOT ON [stallMs]. A bad patch drops frames for
            // seconds, and one line per dropped frame buries the report inside its own noise.
            //
            // The cooldown has to be INDEPENDENT of the stall threshold: gating on "stallMs
            // since the last stall" limits nothing whenever the drops are spaced further apart
            // than the threshold — which is every burst, since a frame that is late by more than
            // the threshold is the definition of the thing being counted.
            //
            // Nothing is lost to the suppression: the periodic summary carries the WORST gap of
            // its period, so the size of a burst survives even when its individual lines do not.
            if (lastStallMs == 0L || nowMs - lastStallMs >= cooldownMs) {
                val since = if (lastStallMs == 0L) -1L else nowMs - lastStallMs
                lastStallMs = nowMs
                return Report.Stall(gap, ptsDelta, since)
            }
        }

        val period = nowMs - periodStartMs
        if (period >= summaryMs) {
            val r = Report.Summary(framesThisPeriod, period, worstGapThisPeriod)
            periodStartMs = nowMs
            framesThisPeriod = 0
            worstGapThisPeriod = 0L
            return r
        }
        return null
    }

    /**
     * Forget the last frame, WITHOUT forgetting the stall history.
     *
     * For a deliberate break in the picture — a stream resync, or the codec being torn down and
     * rebuilt — where the gap that follows is ours and not a fault. The stall clock is kept so
     * the rate limit still holds across the break.
     */
    fun reset() {
        started = false
        framesThisPeriod = 0
        worstGapThisPeriod = 0L
    }

    companion object {
        /**
         * A gap at or above this is reported. About three frames at 15 fps and six at 30, thus
         * comfortably a freeze a pilot can see rather than one dropped frame.
         *
         * ⚠ NOT DERIVED FROM A NOMINAL FRAME RATE, on purpose. The FPV rate is not ours to
         * choose — it changes with the lens and the camera mode — and a threshold expressed in
         * frames would move with it, which is the opposite of what a fixed "the picture froze"
         * question needs.
         */
        const val STALL_MS = 200L

        /** How often the routine line is written. Low enough to prove the monitor is alive,
         *  rare enough that it cannot bury anything. */
        const val SUMMARY_MS = 10_000L

        /** Shortest interval between two stall lines. A second is long enough that a burst
         *  reports as a handful of lines rather than one per dropped frame, and short enough
         *  that two separate freezes a second apart are still two reports. */
        const val STALL_COOLDOWN_MS = 1_000L
    }
}
