package com.autel.sdksample.tak

import android.content.Context
import android.util.Log
import com.taklite.client.tak.TakManager
import java.io.File
import java.util.UUID

/**
 * Silent TAK reconnect using previously-saved enrollment certs — used on app boot so the
 * pilot lands in the flight screen already connected (no menu, no re-entry).
 *
 * Shares the same SharedPreferences keys as [TakConnectActivity].
 */
object TakAutoConnect {
    private const val TAG = "TakAutoConnect"
    private const val PREFS = "takpilot2_tak"
    private const val KEY_HOST = "host"
    private const val KEY_COT_PORT = "cot_port"
    private const val KEY_USERNAME = "username"
    private const val KEY_CALLSIGN = "callsign"
    private const val KEY_UID = "uid"
    private const val KEY_TRUSTSTORE = "truststore_path"
    private const val KEY_CLIENTCERT = "clientcert_path"
    private const val KEY_CAMERA_POINT = "camera_point"
    private const val KEY_LOGGED_OUT = "logged_out"

    // ---- Video channel (cert B). This file keeps its OWN copy of these keys rather
    // than sharing TakConnectActivity's (that's the existing convention here — see KEY_HOST et
    // al. above, already duplicated from that Activity). ----
    internal const val KEY_CHB_ENABLED = "chb_enabled"
    private const val KEY_CHB_TRUSTSTORE = "chb_truststore_path"
    private const val KEY_CHB_CLIENTCERT = "chb_clientcert_path"

    /** Reconnect in the background if we have saved certs and aren't already connected. */
    fun tryReconnect(context: Context) {
        if (TakManager.getInstance().isConnected) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_LOGGED_OUT, false)) {
            Log.i(TAG, "User logged out — skipping auto-connect")
            startTelemetryOnlyBridge(prefs)
            return
        }
        if (!hasSavedCerts(prefs)) {
            Log.i(TAG, "No saved enrollment — skipping auto-connect")
            startTelemetryOnlyBridge(prefs)
            return
        }
        reconnect(context)
    }

    /**
     * Reconnect if TAK is down and we have what we need.
     *
     * ⚠ **THIS EXISTS BECAUSE MOVING THE TAK CONFIGURATION OFF PRE-FLIGHT TOOK A RETRY WITH
     * IT.** [tryReconnect] fires from the HOME screen's `onCreate`, and that screen is
     * `singleTask` — so coming back to it does not run again. The other retry that actually
     * covered a pilot was Pre-Flight's own `onCreate`, which reconnected from saved certs on
     * the way past, and that went to [TakServerActivity] with the fields. What was left was an
     * application that would not reconnect unless the pilot opened a screen they have no other
     * reason to open, or tapped the flight screen's TAK badge.
     *
     * ⚠ **A LIVE PROCESS IS THE CASE THAT MATTERS.** The foreground service keeps this process
     * alive across a swipe-away, so "launching the application again" is often not a new
     * process at all and no `onCreate` runs. Tie the retry to a SCREEN BEING SHOWN, not to a
     * process starting.
     *
     * Safe to call as often as you like: [reconnect] refuses while an attempt is already in
     * flight, so this cannot spawn parallel connects.
     */
    fun retryIfDown(context: Context) {
        if (TakManager.getInstance().isConnected) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_LOGGED_OUT, false)) return
        if (!hasSavedCerts(prefs)) return
        Log.i(TAG, "TAK is down and an enrollment is saved — retrying the connection")
        reconnect(context.applicationContext)
    }

    /**
     * Arms the telemetry side of the bridge with no TAK connection. The flight path
     * logger feeds from the bridge's fly-controller callback. Without this function,
     * the two paths above would leave the bridge stopped, so a controller with no enrollment (or a
     * logged-out one) would record no flights. The bridge operates safely without TAK: its CoT
     * push does nothing while TAK is disconnected, and its other work (subscribe, cache
     * telemetry) is exactly what the logger needs. The uid and callsign fall back to
     * defaults. They reach the network only after a real connect, and a real connect
     * restarts the bridge with the enrolled identity. That restart is a swap, not a
     * teardown (stop(finalizeFlight = false) in TakBridgeHolder.start), so an enrollment
     * during a flight continues the same track file.
     */
    internal fun startTelemetryOnlyBridge(prefs: android.content.SharedPreferences) {
        if (TakBridgeHolder.isRunning) return
        val callsign = prefs.getString(KEY_CALLSIGN, "TAKPilot2-EVO2") ?: "TAKPilot2-EVO2"
        val uid = prefs.getString(KEY_UID, "") ?: ""
        TakBridgeHolder.start(
            (uid.ifEmpty { "TAKPilot2-local" }) + "-DRONE", callsign)
        Log.i(TAG, "Telemetry-only bridge started (flight logging without TAK)")
    }

    /**
     * Flight-screen TAK badge tap: disconnect if connected, otherwise reconnect.
     *
     * Deliberately does NOT set [KEY_LOGGED_OUT]. That flag means "the pilot signed out in
     * Pre-Flight Setup, do not auto-connect again"; a badge tap is a temporary in-flight
     * action, and treating it as a sign-out would silently stop the next launch from
     * reconnecting.
     */
    fun toggle(context: Context, onResult: (ok: Boolean, msg: String) -> Unit) {
        if (TakManager.getInstance().isConnected) {
            Log.i(TAG, "TAK icon tap — disconnecting")
            runCatching { TakManager.getInstance().disconnect() }
            // A removal, not a drop: with TAK off on purpose there is no split to fail closed
            // for, and the re-tap below reconnects the Elevated account if it is enabled.
            // Through ElevatedChannel: a direct clear here could stop a client a reconnect had
            // just started, which the shared core cannot survive. See ElevatedChannel.
            ElevatedChannel.clear()
            // NOT stop(): disconnecting TAK does not mean the app is done. The aircraft may
            // still be connected — AutelProductHolder started this service precisely so a swipe
            // tears the aircraft down — and an Explorer restore may still be pending.
            runCatching { TakForegroundService.releaseIfIdle(context.applicationContext) }
            onResult(true, "TAK disconnected")
            return
        }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!hasSavedCerts(prefs)) {
            onResult(false, "No saved TAK enrollment — set up the server in Pre-Flight Setup first")
            return
        }
        Log.i(TAG, "TAK icon tap — reconnecting")
        reconnect(context.applicationContext)
        onResult(true, "Reconnecting to TAK…")
    }

    /** Enrollment present and the cert files still on disk. Checked before every connect —
     *  a saved host with a deleted truststore would otherwise fail deep inside the socket. */
    private fun hasSavedCerts(prefs: android.content.SharedPreferences): Boolean {
        val host = prefs.getString(KEY_HOST, "") ?: ""
        val ts = prefs.getString(KEY_TRUSTSTORE, "") ?: ""
        val cc = prefs.getString(KEY_CLIENTCERT, "") ?: ""
        return host.isNotEmpty() && ts.isNotEmpty() && cc.isNotEmpty() &&
            File(ts).exists() && File(cc).exists()
    }

    /** Same check for cert B (the video channel). Mirrors [hasSavedCerts]. */
    private fun hasSavedVideoCerts(prefs: android.content.SharedPreferences): Boolean {
        val host = prefs.getString(KEY_HOST, "") ?: "" // cert B reuses cert A's host
        val ts = prefs.getString(KEY_CHB_TRUSTSTORE, "") ?: ""
        val cc = prefs.getString(KEY_CHB_CLIENTCERT, "") ?: ""
        return host.isNotEmpty() && ts.isNotEmpty() && cc.isNotEmpty() &&
            File(ts).exists() && File(cc).exists()
    }

    /**
     * True while a connect attempt is on its worker thread.
     *
     * ⚠ Without this a second trigger spawns a PARALLEL connect, and `TakManager.connect()` is
     * not reentrant — it disconnects and reassigns its client field with no lock, so two racing
     * calls can orphan a live TakClient socket thread that keeps publishing PLI for the same
     * uid with nothing left holding a reference to stop it. It became load-bearing when
     * [retryIfDown] started firing on every resume of two screens.
     */
    @Volatile internal var connecting = false

    /** The actual connect, on a background thread. Shared by [tryReconnect], [retryIfDown] and
     *  [toggle] so the paths can't drift — the channel routing and bridge startup below are
     *  easy to forget in a second copy. */
    private fun reconnect(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val host = prefs.getString(KEY_HOST, "") ?: ""
        val username = prefs.getString(KEY_USERNAME, "") ?: ""
        val cotPort = prefs.getInt(KEY_COT_PORT, 8089)
        val ts = prefs.getString(KEY_TRUSTSTORE, "") ?: ""
        val cc = prefs.getString(KEY_CLIENTCERT, "") ?: ""
        val callsign = prefs.getString(KEY_CALLSIGN, "TAKPilot2-EVO2") ?: "TAKPilot2-EVO2"

        var uid = prefs.getString(KEY_UID, "") ?: ""
        if (uid.isEmpty()) {
            uid = "TAKPilot2-" + UUID.randomUUID().toString().substring(0, 8)
            prefs.edit().putString(KEY_UID, uid).apply()
        }

        if (connecting) {
            Log.i(TAG, "reconnect already in flight — ignoring this request")
            return
        }
        connecting = true

        Thread {
          // An exception on a plain Thread reaches Android's default uncaught handler, which
          // KILLS THE PROCESS — so a bad cert file or an unreachable host could take the whole
          // flight screen down, from a background thread, for a failure that only ever needed a
          // log line. It matters more now that a screen resume can start this.
          try {
            // 2nd arg is the CALLSIGN, not the username — see the same fix in
            // TakServerActivity.connectWithCerts. Both connect paths had it wrong, so the
            // aircraft reported the pilot's login name to the whole team either way.
            TakManager.getInstance().connect(
                uid, callsign, "Cyan", "Team Member",
                host, cotPort, ts, "atakatak", cc, "atakatak",
            )
            // Cert B — the Elevated account. See dialVideoChannel, which holds the mode gate.
            dialVideoChannel(prefs, host, cotPort)
            // NOTHING IS RE-APPLIED, and no channel list is read. A controller that ran an
            // older build can still hold one, and feeding it to the old setChannels path
            // would put <dest group> back on every message and destroy the markers again. The
            // channels live on the server.
            TakBridgeHolder.start("$uid-DRONE", callsign)
            TakBridgeHolder.setCameraPointEnabled(prefs.getBoolean(KEY_CAMERA_POINT, false))
            TakForegroundService.start(context.applicationContext, callsign)
            Log.i(TAG, "Auto-connected to $host:$cotPort as $callsign")
          } catch (t: Throwable) {
            Log.e(TAG, "TAK reconnect failed: ${t.message}", t)
          } finally {
            connecting = false
          }
        }.start()
    }

    /**
     * Cert B — the Elevated account. Queued on [ElevatedChannel], which reads the mode at the
     * moment of the dial, skips a channel that is already configured, and re-reads the mode
     * after dialling. Every gate and every race note lives there, so this path and the
     * return-to-Elevated path cannot disagree. Reuses cert A's host and CoT port (one aircraft,
     * one controller, two certificates).
     *
     * ## ⚠ THE MODE GATE, AND IT IS THE ONE THAT MATTERS
     *
     * [reconnect] is the single chokepoint for boot, the home screen's resume, Pre-Flight's
     * resume AND the in-flight TAK badge. Ungated, a BASIC pilot who taps the badge to clear a
     * link wobble — a normal action on an airframe that drops its RF link for seconds at a time
     * — re-arms the split WITH THE STREAM UP, and the Standard channels silently lose the feed
     * from a tap meant to restore the connection. [AppModeTransition] also writes
     * chb_enabled=false; keep both, because the flag is a preference a restored backup could
     * carry.
     */
    internal fun dialVideoChannel(prefs: android.content.SharedPreferences, host: String, cotPort: Int) {
        ElevatedChannel.dial(
            prefs, host, cotPort,
            prefs.getString(KEY_CHB_TRUSTSTORE, "") ?: "",
            prefs.getString(KEY_CHB_CLIENTCERT, "") ?: "",
            force = false,
        ) { hasSavedVideoCerts(prefs) }
    }

    /**
     * Brings cert B back after a change to ELEVATED, without bouncing cert A.
     *
     * ⚠ IT ALWAYS QUEUES THE DIAL — it never returns early because a reconnect is in flight.
     * A reconnect that had already passed its own cert-B step while the mode was still BASIC
     * would then finish with no cert B: the split would never come back while Pre-Flight said it
     * had. The dial is idempotent, so a reconnect that also reaches it
     * adds nothing. With cert A down and nothing in flight, a full [reconnect] brings cert A up
     * as well.
     */
    fun dialVideoChannelIfAllowed(context: Context) =
        redialAfterEnhanced(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE),
            isConnected = { TakManager.getInstance().isConnected },
        ) { reconnect(context.applicationContext) }

    /** [dialVideoChannelIfAllowed] with its two live inputs passed in, for the unit tests. */
    internal fun redialAfterEnhanced(
        prefs: android.content.SharedPreferences,
        isConnected: () -> Boolean,
        reconnect: () -> Unit,
    ) {
        val host = prefs.getString(KEY_HOST, "") ?: ""
        if (host.isEmpty()) return
        dialVideoChannel(prefs, host, prefs.getInt(KEY_COT_PORT, 8089))
        // The same two guards as every other path to reconnect(): a pilot who logged out in
        // Basic must not be reconnected by the return to Elevated, and with no saved
        // certificates there is nothing to connect with.
        if (!isConnected() && !connecting &&
            !prefs.getBoolean(KEY_LOGGED_OUT, false) && hasSavedCerts(prefs)) reconnect()
    }
}
