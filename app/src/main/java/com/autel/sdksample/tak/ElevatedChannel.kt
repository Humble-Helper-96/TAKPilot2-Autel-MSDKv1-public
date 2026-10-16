package com.autel.sdksample.tak

import android.content.SharedPreferences
import com.taklite.client.tak.TakManager
import com.taklite.util.AppLog
import java.util.concurrent.Executors

/**
 * Every live change to the Elevated connection (cert B) — dial, clear, and BASIC's teardown —
 * on ONE thread, in ONE place.
 *
 * ## ⚠ WHY A SINGLE THREAD: THE SHARED CORE CANNOT STOP A CLIENT THAT HAS NOT STARTED
 *
 * `TakClient.run()` sets `mRun = true` as its first line. A `stopClient()` that lands BEFORE
 * the client's thread has entered `run()` is therefore overwritten: the client loops for ever,
 * TLS-connected as the Elevated user, with `videoClient` already null — nothing references it
 * and nothing can stop it. That is a defect in the shared `com.taklite` core (`run()` should
 * not reset a stop that came first), which this app layer works around rather than changes.
 *
 * Before this object, cert B was dialled from the reconnect worker and from two
 * TakServerActivity workers, and cleared from the main thread in four places (the mode change,
 * the TAK badge, the Elevated switch, Log Out) — so any two could interleave.
 *
 * What this object guarantees, in the app layer:
 *  1. **One thread.** Dials and clears cannot interleave.
 *  2. **The mode is read INSIDE the task**, at the moment of the dial. A dial queued before a
 *     change to BASIC runs after the mode was written (the transition writes it synchronously,
 *     first) and declines.
 *  3. **Nothing stops a client younger than [SETTLE_MS].** Before any clear or forced re-dial,
 *     the task waits out the rest of that window, so the client it stops has entered `run()`.
 *  4. **An automatic dial is idempotent.** `isVideoChannelConfigured()` is true exactly while a
 *     client object exists, and a live client re-dials its own socket every 5 s. So a second
 *     automatic dial adds nothing but a start/stop bounce, and is skipped.
 *
 * ⚠ READ BEFORE ROUTING ANYTHING AROUND THIS: a direct `TakManager.connectVideoChannel` or
 * `clearVideoChannel` anywhere else re-opens every race this object closes.
 */
internal object ElevatedChannel {

    private const val TAG = "ElevatedChannel"

    /** Thread start is milliseconds; this is generous by two orders of magnitude, and it is
     *  only ever waited when a stop follows a start this closely — which is the rare case. */
    internal const val SETTLE_MS = 500L

    /** The live verbs this object needs from the shared core. A seam, so [ElevatedChannelTest]
     *  can drive every branch without a socket. */
    internal interface Live {
        fun isVideoChannelConfigured(): Boolean
        fun connectVideoChannel(host: String, port: Int, trustStore: String, clientCert: String)
        fun clearVideoChannel()
        fun isEmergencyBroadcastActive(): Boolean
        fun cancelEmergencyBroadcast()
    }

    private object TakManagerLive : Live {
        private val tm get() = TakManager.getInstance()
        override fun isVideoChannelConfigured() = tm.isVideoChannelConfigured()
        override fun connectVideoChannel(host: String, port: Int, trustStore: String, clientCert: String) =
            // acceptInbound = true (design decision, made with the writable Elevated channel
            // rows). The Elevated account's channels are the pilot's to choose, so what arrives
            // on them is traffic they asked for — and a tick is the ONLY "ignore incoming"
            // control that can exist, because inbound CoT carries no channel label. The shared
            // core still DEFAULTS to discarding; this is the deliberate call site that opts in.
            tm.connectVideoChannel(host, port, trustStore, "atakatak", clientCert, "atakatak", true)
        override fun clearVideoChannel() = tm.clearVideoChannel()
        override fun isEmergencyBroadcastActive() = tm.isEmergencyBroadcastActive()
        override fun cancelEmergencyBroadcast() = tm.cancelEmergencyBroadcast()
    }

    // ---- Seams. Production values here; tests replace them. ----
    internal var live: Live = TakManagerLive
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "elevated-channel").apply { isDaemon = true }
    }
    internal var runner: (Runnable) -> Unit = { executor.execute(it) }
    /** Monotonic: a wall-clock step back would make the settle wait as long as the step. */
    internal var clock: () -> Long = { android.os.SystemClock.elapsedRealtime() }
    internal var sleeper: (Long) -> Unit = { Thread.sleep(it) }
    /** A line in the active flight's record. */
    internal var record: (String) -> Unit = { FlightPathLogger.event(it) }

    /** When the last client was started; touched only on the executor (and by the test seam). */
    @Volatile internal var lastDialAtMs = 0L

    /** Wait until any client started by this object has had [SETTLE_MS] to enter `run()`.
     *  ⚠ The wait is CAPPED at [SETTLE_MS]. Every teardown, clear and dial waits here on the
     *  one channel thread, so an uncapped wait blocks all of them. */
    private fun awaitSettled() {
        val wait = (lastDialAtMs + SETTLE_MS - clock()).coerceAtMost(SETTLE_MS)
        if (wait > 0) {
            AppLog.i(TAG, "waiting ${wait}ms before stopping a client that has just started")
            sleeper(wait)
        }
    }

    /** Runs [block] on the channel's thread; an exception is logged, never thrown (a throw on a
     *  plain thread reaches Android's default handler, which kills the process). */
    private fun post(what: String, block: () -> Unit) = runner(Runnable {
        try { block() } catch (t: Throwable) { AppLog.e(TAG, "$what failed: ${t.message}", t) }
    })

    /**
     * Dials cert B if the mode, the switch, the logout state and the certificates allow it.
     *
     * @param force a fresh enrollment's certificates must be dialled even over a configured
     *   channel; every automatic path passes false and is skipped when one is configured.
     * @param certsUsable checked on the channel thread, at the moment of the dial.
     */
    fun dial(
        prefs: SharedPreferences, host: String, cotPort: Int,
        trustStore: String, clientCert: String, force: Boolean, certsUsable: () -> Boolean,
    ) = post("dial") { dialNow(prefs, host, cotPort, trustStore, clientCert, force, certsUsable) }

    internal fun dialNow(
        prefs: SharedPreferences, host: String, cotPort: Int,
        trustStore: String, clientCert: String, force: Boolean, certsUsable: () -> Boolean,
    ) {
        // The mode, read HERE — at the moment of the dial, on this thread. See note 2 above.
        if (!AppMode.elevatedAccountAllowed(prefs)) {
            // BASIC: refuse — and heal a stray split, so a badge tap repairs rather than only
            // declining to make things worse.
            if (live.isVideoChannelConfigured()) {
                AppLog.i(TAG, "Basic mode: a video channel was configured — clearing it")
                awaitSettled()
                live.clearVideoChannel()
            }
            return
        }
        if (!prefs.getBoolean(TakAutoConnect.KEY_CHB_ENABLED, false) ||
            prefs.getBoolean("chb_logged_out", false) || !certsUsable()
        ) return
        if (!force && live.isVideoChannelConfigured()) {
            AppLog.v(TAG, "video channel already configured — not re-dialling")
            return
        }
        // A forced re-dial stops the current client inside connectVideoChannel.
        if (force) awaitSettled()
        live.connectVideoChannel(host, cotPort, trustStore, clientCert)
        lastDialAtMs = clock()
        AppLog.i(TAG, "Auto-connected video channel")
        // ⚠ Read AGAIN. Nothing on this thread can have changed it, but the transition writes
        // the mode from the MAIN thread — so a change that landed during the dial is seen here,
        // and the client is taken down once it has settled rather than the instant it started.
        if (!AppMode.elevatedAccountAllowed(prefs)) {
            AppLog.i(TAG, "mode became Basic during the dial — clearing the video channel")
            awaitSettled()
            live.clearVideoChannel()
        }
    }

    /** Removes the channel on purpose (switch off, Log Out, the badge's disconnect). */
    fun clear() = post("clear") { clearNow() }

    /**
     * [clear], and wait for it — for the application closing (swipe-away, STOP/QUIT, controller
     * power-off), where nothing runs afterwards to finish an asynchronous clear. Still on the
     * channel's own thread, behind any dial already queued, so no race this object closes is
     * re-opened.
     *
     * @return false if the clear did not finish within [timeoutMs].
     */
    fun clearAndWait(timeoutMs: Long): Boolean {
        val done = java.util.concurrent.CountDownLatch(1)
        post("clear (closing)") { try { clearNow() } finally { done.countDown() } }
        return done.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    internal fun clearNow() {
        if (!live.isVideoChannelConfigured()) return
        awaitSettled()
        live.clearVideoChannel()
    }

    /**
     * BASIC's live teardown, in the order [AppModeTransition] requires: the broadcast BEFORE
     * the split. The caller has ALREADY written the mode, so every dial queued behind this task
     * reads BASIC and declines.
     */
    fun teardownForBasic() = post("Basic teardown") { teardownForBasicNow() }

    internal fun teardownForBasicNow() {
        // The broadcast first. It is a timed override ON the split, its only stop control is
        // the flight-screen notice BASIC removes, and clearVideoChannel does NOT end it.
        if (live.isEmergencyBroadcastActive()) {
            AppLog.i(TAG, "Basic: cancelling the running Emergency Broadcast")
            // ⚠ The cancel's own record line is the core's plain "cancelled" — the same line as
            // the pilot's touch-and-hold stop. This line, just before it, says why. Written from
            // here because the shared com.taklite core is left unchanged.
            record("emergency-broadcast ended by the change to Basic mode")
            live.cancelEmergencyBroadcast()
        }
        // clearVideoChannel is the REMOVAL (restores single-connection behaviour);
        // disconnectVideoChannel is the drop, which fails closed and leaves nobody with video.
        if (live.isVideoChannelConfigured()) {
            AppLog.i(TAG, "Basic: clearing the Elevated connection")
            awaitSettled()
            live.clearVideoChannel()
        }
    }
}
