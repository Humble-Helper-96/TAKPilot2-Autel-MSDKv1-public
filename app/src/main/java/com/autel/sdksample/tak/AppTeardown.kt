package com.autel.sdksample.tak

import android.content.Context
import com.taklite.client.tak.TakManager
import com.taklite.util.AppLog

/**
 * The single "shut everything down" path, shared by every way the app can be closed.
 *
 * **Why this exists:** a pilot who swipes the app away expects it to be closed.
 * `onTaskRemoved` alone (disconnect TAK, stop the service) is not enough: the Autel SDK's
 * connection lives at PROCESS scope, and Android keeps a swiped-away process cached, so an app
 * that does not release the SDK goes on holding the aircraft's camera and video channels after
 * the pilot believes it is gone. Those channels are single-client: Autel Explorer comes up to a
 * grey screen because an invisible, TAK-less, service-less process still owns the camera, and
 * neither app says anything.
 *
 * Every exit path — Stop & Quit and task removal — calls [releaseAll], so they cannot do
 * different subsets of the work. The controller powering off is the third path:
 * [TakForegroundService] calls it from an ACTION_SHUTDOWN receiver.
 *
 * Order matters: consumers of the aircraft link go first (video capture, then the telemetry
 * bridge, then TAK), and the aircraft link itself after them, so nothing is mid-write when the
 * transport disappears. The foreground service is stopped last — it is the delivery mechanism for
 * `onTaskRemoved` and holds no state, so stopping it after everything else costs nothing.
 */
object AppTeardown {
    private const val TAG = "AppTeardown"

    /** Generous: a TLS close is one write, and the power-off broadcast allows about 10 s in all;
     *  a whole power-off teardown has been measured at about 50 ms. */
    private const val ELEVATED_CLEAR_WAIT_MS = 1_500L

    /**
     * Stops everything this app owns. Safe to call more than once and from any thread; every
     * step is individually guarded so one failure cannot abort the rest — that guarding is not
     * defensive padding, it is why logout used to fail (a throw from the closing TAK socket
     * skipped the steps after it).
     */
    fun releaseAll(context: Context, why: String = "closing") {
        AppLog.i(TAG, "closing every connection ($why): video, bridge, both TAK accounts, " +
            "services and the SDK")
        val ctx = context.applicationContext
        runCatching { ScreenCaptureService.stop(ctx) }
        runCatching { VideoStreamerHolder.stop() }
        runCatching { TakBridgeHolder.stop() }
        runCatching { TakManager.getInstance().disconnect() }
        // ⚠ BOTH TAK CONNECTIONS, NOT ONE.
        // disconnect() closes cert A (Standard) and NOTHING ELSE: the Elevated connection is a
        // separate client with its own lifecycle. Where the process outlives a swipe, the
        // Elevated row stays on the TAK server's Connected Users for days, and the bridge keeps
        // ticking. Here both exit paths kill the process afterwards, which closes the socket
        // anyway — but the controller POWERING OFF does not, and the network can go first.
        // Through ElevatedChannel and never the core directly: a bare clearVideoChannel() can
        // stop a client a reconnect has only just started, which the shared core cannot survive.
        val cleared = runCatching {
            ElevatedChannel.clearAndWait(ELEVATED_CLEAR_WAIT_MS)
        }.getOrDefault(false)
        AppLog.i(TAG, if (cleared) "Elevated connection closed" else
            "Elevated connection: clear did not finish within ${ELEVATED_CLEAR_WAIT_MS}ms")
        runCatching { AutelProductHolder.release() }
        // The foreground service goes last — it holds no state, and it is the delivery mechanism
        // for onTaskRemoved, so it should outlive the things it was keeping alive.
        runCatching { TakForegroundService.stopForTeardown(ctx) }
        AppLog.i(TAG, "releaseAll: done")
        // The log buffer goes to the file NOW. Swipe and STOP/QUIT kill the process 200-400 ms
        // after this returns, inside the 1 s flush timer, and the teardown lines would be lost —
        // "Elevated connection closed" included, the one line that proves both connections closed.
        runCatching { AppLog.flush() }
    }
}
