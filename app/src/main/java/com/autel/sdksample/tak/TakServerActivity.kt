package com.autel.sdksample.tak

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.autel.sdksample.R
import com.taklite.client.tak.TakCertEnroller
import com.taklite.client.tak.TakManager
import com.taklite.client.tak.TakMissionClient
import com.taklite.util.AppLog
import java.io.File
import java.util.UUID

/**
 * The TAK server connection — BOTH ACCOUNTS — moved off Pre-Flight.
 *
 * Same move, and the same reasoning, that [VideoServersActivity] made for the video settings.
 * Pre-Flight keeps what a pilot READS before a flight: which user this aircraft is
 * signed in as, and which channels it reaches. Everything that CONFIGURES the connection — two
 * enrollments, six fields, two channel lists and the lock — is entered once and then locked, and
 * a column of it on the main screen buried the things that actually change.
 *
 * ## The two accounts
 *
 *  - **Standard** is the connection every message goes out on. Its channels are this aircraft's
 *    audience.
 *  - **Elevated** is a second certificate under its own TAK Server username, so the live-video
 *    link reaches only the channel that account is in.
 *
 * They share the host and both ports: one controller, two certificates.
 *
 * ## What this screen does NOT own
 *
 *  - **The split policy.** `TakManager.videoFor` decides which connection carries the video
 *    url. This screen only decides whether a second connection exists at all.
 *  - **The channel state.** The server holds it. Both lists are mirrors that follow it, and a
 *    tick is a request, not a local truth — the list is read again after every write.
 *
 * ⚠ **THE LOCK CAME WITH IT.** The video screen left its lock on Pre-Flight because that screen
 * kept an active-server toggle for it to guard. Nothing of the TAK configuration stayed behind,
 * so the lock moved here, and Pre-Flight's TAK section has no control for a lock to protect.
 *
 * ⚠ **NOTHING MIGRATED.** Every preference key below is the key Pre-Flight already wrote and
 * every view id is the id it had there, so a controller that upgrades finds its server
 * configured and connected.
 *
 * ⚠ **A SCREEN THAT IS NOT OPENED CANNOT RECONNECT ANYTHING.** Pre-Flight's `onCreate` used to
 * retry the TAK connection on the way past, and that retry left with these fields. It now lives
 * in [TakAutoConnect.retryIfDown], called from the RESUME of the home screen and of Pre-Flight —
 * read that method before changing where it is called from.
 */
class TakServerActivity : AppCompatActivity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tak_server)
        AppLog.v(TAG, "onCreate")
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_menu)
        }

        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)

        // ⚠ BASIC HAS ONE TAK ACCOUNT. Hiding the section is the VISIBLE half only — the
        // socket, splitConfigured and chb_enabled were already torn down, in that order, by
        // AppModeTransition. Nothing here may re-enroll or re-dial: a screen that could would
        // put the split back behind a UI that no longer mentions it.
        if (!AppMode.elevatedAccountAllowed(prefs)) {
            findViewById<android.view.View>(R.id.takElevatedSection).visibility =
                android.view.View.GONE
        }

        val host = findViewById<EditText>(R.id.takHost)
        val enrollPort = findViewById<EditText>(R.id.takEnrollPort)
        val cotPort = findViewById<EditText>(R.id.takCotPort)
        val username = findViewById<EditText>(R.id.takUsername)
        val password = findViewById<EditText>(R.id.takPassword)
        val callsign = findViewById<EditText>(R.id.takCallsign)
        status = findViewById(R.id.takStatus)

        // Restore last-used values (except password).
        host.setText(prefs.getString(KEY_HOST, ""))
        enrollPort.setText(prefs.getInt(KEY_ENROLL_PORT, 8446).toString())
        cotPort.setText(prefs.getInt(KEY_COT_PORT, 8089).toString())
        username.setText(prefs.getString(KEY_USERNAME, ""))
        callsign.setText(prefs.getString(KEY_CALLSIGN, "TAKPilot2-EVO2"))

        // Camera look-point toggle (applies live to the running bridge + persists).
        val cameraPoint = findViewById<android.widget.CheckBox>(R.id.takCameraPoint)
        cameraPoint.isChecked = prefs.getBoolean(KEY_CAMERA_POINT, false)
        cameraPoint.setOnCheckedChangeListener { _, isOn ->
            AppLog.v(TAG, "camera point toggle -> $isOn")
            prefs.edit().putBoolean(KEY_CAMERA_POINT, isOn).apply()
            TakBridgeHolder.setCameraPointEnabled(isOn)
        }
        TakBridgeHolder.setCameraPointEnabled(cameraPoint.isChecked)

        // My Channels. The channels come from the server and go back to the server, and no
        // <dest group> goes on any message — that attribute made the server drop every
        // marker when an earlier version sent it.
        refreshChannels()
        // The server pushes t-x-g-c when the channels change, from this controller or from an
        // administrator in TAK Portal. Listening beats a timer: the screen follows in about a
        // second, and it asks the server nothing while nothing changes.
        TakManager.getInstance().addGroupChangeListener(groupChangeListener)
        // AND read them again when TAK connects. The refresh above needs a connection, so a
        // screen opened before TAK is up would otherwise show an empty list for ever.
        //
        // ⚠ THE LISTENERS HOLD THIS ACTIVITY. TakManager outlives the screen, so they are
        // removed in onDestroy below. Without that this Activity leaks and its dead views are
        // repainted.
        TakManager.getInstance().addListener(connectionListener)

        // Reflect live state on open, and silently reconnect with saved certs if the socket is
        // not up — so the pilot never has to re-enter credentials or re-enroll.
        when {
            TakManager.getInstance().isConnected -> {
                // Track it, so connectionListener can correct this line if the link later drops
                // while the pilot is on this screen.
                trackedCallsign = callsign.text.toString().trim().ifEmpty { "TAKPilot2-EVO2" }
                setStatus("Connected. Sending the aircraft position to TAK.", androidx.core.content.ContextCompat.getColor(applicationContext, R.color.tp_state_go))
            }
            prefs.getBoolean(KEY_LOGGED_OUT, false) ->
                setStatus("Logged out. Enter host, username and password to sign in.", androidx.core.content.ContextCompat.getColor(applicationContext, R.color.tp_text_secondary))
            hasSavedCerts(prefs) -> {
                setStatus("Reconnecting with saved enrollment …", androidx.core.content.ContextCompat.getColor(applicationContext, R.color.tp_text_secondary))
                reconnectFromSaved(prefs, callsign.text.toString().trim().ifEmpty { "TAKPilot2-EVO2" })
            }
            else -> setStatus("Not connected.", androidx.core.content.ContextCompat.getColor(applicationContext, R.color.tp_text_secondary))
        }

        findViewById<Button>(R.id.takConnectButton).setOnClickListener {
            AppLog.v(TAG, "Connect tapped")
            // Held down for the whole attempt. Enrollment takes seconds
            // and isConnected stays false throughout, so the guard below is no defence against
            // a second tap: two enrollments write the same two certificate files and both call
            // the non-reentrant TakManager.connect().
            setConnectBusy(true)
            val h = host.text.toString().trim()
            val u = username.text.toString().trim()
            val p = password.text.toString()
            val cs = callsign.text.toString().trim().ifEmpty { "TAKPilot2-EVO2" }
            val ep = enrollPort.text.toString().trim().toIntOrNull() ?: 8446
            val cp = cotPort.text.toString().trim().toIntOrNull() ?: 8089

            if (TakManager.getInstance().isConnected) {
                setStatus("Already connected.", androidx.core.content.ContextCompat.getColor(applicationContext, R.color.tp_state_go))
                setConnectBusy(false)
                return@setOnClickListener
            }
            // Fail here, in words, when the controller has no route out — BEFORE the enroller
            // turns the same fact into a generic TLS/socket error. Field reports had
            // pilots reading "enrollment failed" as a server or credential fault
            // when the controller simply had no network. Guards both paths below: fresh
            // enrollment and reconnect-from-saved both need the network.
            if (!NetworkStatus.hasInternet(this)) {
                AppLog.w(TAG, "Connect blocked: no validated network")
                setStatus("No network connection. Connect the controller to wifi first — " +
                    "check the WIFI line on the home screen.", androidx.core.content.ContextCompat.getColor(applicationContext, R.color.tp_state_danger))
                setConnectBusy(false)
                return@setOnClickListener
            }
            prefs.edit()
                .putString(KEY_HOST, h.ifEmpty { prefs.getString(KEY_HOST, "") })
                .putInt(KEY_ENROLL_PORT, ep)
                .putInt(KEY_COT_PORT, cp)
                .putString(KEY_USERNAME, u.ifEmpty { prefs.getString(KEY_USERNAME, "") })
                .putString(KEY_CALLSIGN, cs)
                .apply()

            // If we already enrolled before, reconnect with saved certs — no password needed.
            if (hasSavedCerts(prefs) && p.isEmpty()) {
                reconnectFromSaved(prefs, cs)
                return@setOnClickListener
            }
            if (h.isEmpty() || u.isEmpty() || p.isEmpty()) {
                setStatus("Host, username and password are required for first enrollment.",
                    androidx.core.content.ContextCompat.getColor(applicationContext, R.color.tp_state_danger))
                setConnectBusy(false)
                return@setOnClickListener
            }
            enrollAndConnect(h, ep, cp, u, p, cs)
        }

        findViewById<Button>(R.id.takDisconnectButton).setOnClickListener {
            AppLog.v(TAG, "Logout tapped")
            // Full LOG OUT: stop everything AND clear the saved enrollment so the app will not silently
            // reconnect the old user, and a different user can enroll cleanly. Each teardown step is
            // guarded — a throw from the closing socket must NOT abort the logout (that crash was
            // why logout never stuck). clearEnrollment + the logged-out flag always run.
            runCatching { VideoStreamerHolder.stop() }
            runCatching { TakBridgeHolder.stop() }
            runCatching { TakManager.getInstance().disconnect() }
            // NOT stop(): logging out of TAK does not mean the app is done. See releaseIfIdle.
            runCatching { TakForegroundService.releaseIfIdle(applicationContext) }
            runCatching { clearEnrollment(prefs) }
            // Keep the fly-controller listener alive: the bridge owns it, and it feeds the
            // airborne flag that Change Mode, Format Card and the avoidance writes trust, plus
            // the HUD and the flight record. A stopped bridge froze that flag for the rest of
            // the process. The telemetry-only bridge sends nothing to TAK.
            runCatching { TakAutoConnect.startTelemetryOnlyBridge(prefs) }
            // Reset the UI fields so it's clearly a fresh login.
            username.setText("")
            password.setText("")
            // Nothing local to clear: the channels live on the server now. Logging out does
            // not change them, which is correct — they belong to the certificate.
            latestChannels = emptyList()
            runCatching { findViewById<android.widget.LinearLayout>(R.id.takChannelsList).removeAllViews() }
            runCatching { findViewById<TextView>(R.id.takChannelsStatus).text = "" }
            channelsStatusIsEmptyNotice = false
            // clearEnrollment() above already cleared cert B's files/prefs and disconnected it —
            // this just resets THIS screen's Elevated-account fields to match.
            runCatching { findViewById<android.widget.Switch>(R.id.takVideoChannelEnabled).isChecked = false }
            runCatching { findViewById<EditText>(R.id.takVideoUsername).setText("") }
            runCatching { findViewById<EditText>(R.id.takVideoPassword).setText("") }
            runCatching { findViewById<android.widget.LinearLayout>(R.id.takVideoChannelsList).removeAllViews() }
            latestVideoChannels = emptyList()
            setVideoChannelStatus("", androidx.core.content.ContextCompat.getColor(applicationContext, R.color.tp_text_secondary))
            setStatus("Logged out. Enter host, username and password to sign in as another user.",
                androidx.core.content.ContextCompat.getColor(applicationContext, R.color.tp_text_secondary))
        }

        setupVideoChannelSection(prefs, host, enrollPort, cotPort)
        // LAST, deliberately. Every setup above populates and enables its own fields, so a lock
        // applied before them would be undone on the way past.
        setupTakLock()
    }

    override fun onDestroy() {
        // The listeners hold this Activity. TakManager outlives the screen, thus leaving them
        // attached leaks the whole Activity and repaints views that are gone.
        runCatching { TakManager.getInstance().removeGroupChangeListener(groupChangeListener) }
        runCatching { TakManager.getInstance().removeListener(connectionListener) }
        super.onDestroy()
    }

    /** The action-bar menu button returns to Pre-Flight, the screen that sent us here. */
    override fun onSupportNavigateUp(): Boolean {
        AppLog.v(TAG, "menu tapped — back to Pre-Flight")
        finish()
        return true
    }

    /**
     * The TAK configuration lock, now the only lock on this screen.
     *
     * The channel rows are built in code, so [ConfigLock.apply] cannot reach them by id — they
     * are painted again instead, and each row reads the lock as it is built. BOTH lists need
     * that, not just the Standard one.
     */
    private fun setupTakLock() {
        ConfigLock.install(
            this, R.id.takLockConfig, TakConnectActivity.KEY_TAK_LOCKED, takLockedFields,
            "Unlock TAK server settings?",
            "The lock prevents an accidental change to a server that works. " +
                "A wrong value stops the aircraft sending data to your team.",
            afterChange = {
                renderChannels(latestChannels)
                if (latestVideoChannels.isNotEmpty()) renderVideoChannels(latestVideoChannels)
            },
        )
    }

    /**
     * What the TAK lock disables.
     *
     * The list includes **Log Out**, which is not a field: it clears the saved enrollment, so it
     * is destructive and belongs behind the lock. Enroll & Connect is
     * deliberately NOT here — reconnecting is safe, and needing to reconnect is exactly when a
     * pilot must not be fighting a lock.
     *
     * ⚠ The channel rows are NOT here either, and must not be: the 45 % dim greys the TICK, and
     * the tick is what a pilot opens this screen to read. They are locked in
     * [renderChannels]/[renderVideoChannels] by dropping their touch response instead.
     */
    private val takLockedFields = listOf(
        R.id.takHost, R.id.takEnrollPort, R.id.takCotPort,
        R.id.takUsername, R.id.takPassword, R.id.takCallsign,
        R.id.takDisconnectButton,
        // The Elevated account locks with the server fields — it is the MORE privileged
        // account, and a stray tap on its switch would put video on every channel.
        R.id.takVideoChannelEnabled, R.id.takVideoUsername, R.id.takVideoPassword,
        R.id.takVideoConnectButton,
    )

    private fun enrollAndConnect(
        host: String, enrollPort: Int, cotPort: Int,
        username: String, password: String, droneCallsign: String,
    ) {
        AppLog.v(TAG, "enrollAndConnect: host=$host enrollPort=$enrollPort cotPort=$cotPort user=$username")
        setStatus("Enrolling with $host:$enrollPort …", androidx.core.content.ContextCompat.getColor(applicationContext, R.color.tp_text_secondary))

        // Stable controller uid persisted across launches.
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        var uid = prefs.getString(KEY_UID, "") ?: ""
        if (uid.isEmpty()) {
            uid = "TAKPilot2-" + UUID.randomUUID().toString().substring(0, 8)
            prefs.edit().putString(KEY_UID, uid).apply()
        }
        // The aircraft gets its own distinct uid so it shows as a separate air track.
        val droneUid = "$uid-DRONE"

        Thread {
            TakCertEnroller.enroll(host, enrollPort, username, password, uid, filesDir,
                object : TakCertEnroller.EnrollmentCallback {
                    override fun onSuccess(trustStorePath: String, clientCertPath: String) {
                        // Persist certs so we never have to re-enroll — future connects reuse these.
                        prefs.edit()
                            .putString(KEY_TRUSTSTORE, trustStorePath)
                            .putString(KEY_CLIENTCERT, clientCertPath)
                            .putBoolean(KEY_LOGGED_OUT, false)   // new enrollment → allow auto-reconnect again
                            .apply()
                        runOnUiThread { setStatus("Enrolled. Connecting …", androidx.core.content.ContextCompat.getColor(applicationContext, R.color.tp_text_secondary)) }
                        // connectWithCerts frees the Connect button, but it can throw, and this
                        // is a worker thread: without the catch the button stays dead.
                        runCatching {
                            connectWithCerts(uid, username, droneUid, droneCallsign,
                                host, cotPort, trustStorePath, clientCertPath)
                        }.onFailure {
                            AppLog.e(TAG, "connect after enrollment failed: ${it.message}", it)
                            runOnUiThread {
                                setStatus("Enrolled, but the connection failed: ${it.message}",
                                    androidx.core.content.ContextCompat.getColor(applicationContext, R.color.tp_state_danger))
                            }
                            setConnectBusy(false)
                        }
                    }

                    override fun onError(error: String) {
                        AppLog.w(TAG, "enrollment failed: $error")
                        runOnUiThread { setStatus("Error: $error", androidx.core.content.ContextCompat.getColor(applicationContext, R.color.tp_state_danger)) }
                        setConnectBusy(false)
                    }
                })
        }.start()
    }

    /** Connect using already-enrolled cert files (no re-enrollment / re-entry of password). */
    private fun connectWithCerts(
        uid: String, username: String, droneUid: String, droneCallsign: String,
        host: String, cotPort: Int, trustStorePath: String, clientCertPath: String,
    ) {
        val certPw = "atakatak"
        // 2nd arg is the CALLSIGN, not the username. Passing `username` here made the aircraft
        // appear on the TAK server (and in the flight HUD, which reads TakManager.callsign)
        // under the pilot's login name instead of the callsign set in Pre-Flight Setup —
        // so a team saw the login name where they expected the aircraft's name. The username still
        // identifies the account for enrollment; it is not what the team should see.
        TakManager.getInstance().connect(
            uid, droneCallsign, "Cyan", "Team Member",
            host, cotPort, trustStorePath, certPw, clientCertPath, certPw,
        )
        trackedCallsign = droneCallsign
        runOnUiThread {
            // "Connecting", not "Connected": connect() only starts the
            // socket thread, so a dead server, a wrong port or a revoked certificate all left
            // a green "Connected" here. connectionListener paints the answer when the socket
            // reports it. Unknown is its own state, in amber.
            setStatus("Connecting to $host:$cotPort as \"$droneCallsign\" …",
                androidx.core.content.ContextCompat.getColor(applicationContext, R.color.tp_state_unknown))
            // Both routes (fresh enrollment and saved-certificate reconnect) end here.
            setConnectBusy(false)
            TakBridgeHolder.start(droneUid, droneCallsign)
            TakForegroundService.start(applicationContext, droneCallsign)
        }
    }

    /** Reconnect using saved certs + saved server settings, no UI entry needed. */
    private fun reconnectFromSaved(prefs: android.content.SharedPreferences, droneCallsign: String) {
        val host = prefs.getString(KEY_HOST, "") ?: ""
        val username = prefs.getString(KEY_USERNAME, "") ?: ""
        val cotPort = prefs.getInt(KEY_COT_PORT, 8089)
        val ts = prefs.getString(KEY_TRUSTSTORE, "") ?: ""
        val cc = prefs.getString(KEY_CLIENTCERT, "") ?: ""
        var uid = prefs.getString(KEY_UID, "") ?: ""
        if (uid.isEmpty()) {
            uid = "TAKPilot2-" + UUID.randomUUID().toString().substring(0, 8)
            prefs.edit().putString(KEY_UID, uid).apply()
        }
        if (host.isEmpty() || ts.isEmpty() || cc.isEmpty()) {
            setStatus("Saved enrollment incomplete — enroll again.", androidx.core.content.ContextCompat.getColor(applicationContext, R.color.tp_state_danger))
            setConnectBusy(false)
            return
        }
        Thread {
            runCatching {
                connectWithCerts(uid, username, "$uid-DRONE", droneCallsign, host, cotPort, ts, cc)
            }.onFailure {
                AppLog.e(TAG, "reconnect from saved enrollment failed: ${it.message}", it)
                runOnUiThread {
                    setStatus("Could not connect: ${it.message}",
                        androidx.core.content.ContextCompat.getColor(applicationContext, R.color.tp_state_danger))
                }
                setConnectBusy(false)
            }
        }.start()
    }

    /**
     * The callsign whose connection this screen is following — set when it starts an attempt,
     * or on open with a live link. Non-null also means this screen may report a failure; while
     * it is null an unrelated disconnect must not paint red over it.
     */
    @Volatile private var trackedCallsign: String? = null

    /** Holds the Connect button down for an attempt. Any thread; safe after the
     *  screen has gone, since findViewById returns null then. */
    private fun setConnectBusy(busy: Boolean) {
        runOnUiThread { findViewById<Button>(R.id.takConnectButton)?.isEnabled = !busy }
    }

    /** Delete the saved enrollment (cert files + prefs) so a different user can sign in clean.
     *  Also clears cert B (the video channel) — a logout must not leave a second,
     *  more-privileged certificate behind for the next person to sign in on top of. */
    private fun clearEnrollment(prefs: android.content.SharedPreferences) {
        val ts = prefs.getString(KEY_TRUSTSTORE, "") ?: ""
        val cc = prefs.getString(KEY_CLIENTCERT, "") ?: ""
        if (ts.isNotEmpty()) { val f = java.io.File(ts); val ok = runCatching { f.delete() }.getOrDefault(false); AppLog.i(TAG, "delete truststore $ts -> $ok (exists=${f.exists()})") }
        if (cc.isNotEmpty()) { val f = java.io.File(cc); val ok = runCatching { f.delete() }.getOrDefault(false); AppLog.i(TAG, "delete clientcert $cc -> $ok (exists=${f.exists()})") }
        // Also nuke any cert files by their well-known names, in case the prefs paths drifted.
        listOf("tak_clientcert.p12", "tak_truststore.p12").forEach {
            val f = java.io.File(filesDir, it); if (f.exists()) { val ok = runCatching { f.delete() }.getOrDefault(false); AppLog.i(TAG, "delete $it -> $ok") }
        }
        prefs.edit()
            .remove(KEY_TRUSTSTORE)
            .remove(KEY_CLIENTCERT)
            .remove(KEY_UID)
            .remove(KEY_USERNAME)
            // Controllers that ran an earlier version may still hold a stored channel list. Nothing
            // reads it — the channels live on the server — so clearing it stops dead state
            // outliving a logout.
            .remove(KEY_CHANNELS)
            .putBoolean(KEY_LOGGED_OUT, true)   // block auto-reconnect until a fresh enroll
            .apply()
        AppLog.i(TAG, "enrollment cleared")
        clearVideoEnrollment(prefs)
    }

    /** True if we have saved cert files on disk from a previous enrollment. */
    private fun hasSavedCerts(prefs: android.content.SharedPreferences): Boolean {
        val ts = prefs.getString(KEY_TRUSTSTORE, "") ?: ""
        val cc = prefs.getString(KEY_CLIENTCERT, "") ?: ""
        return ts.isNotEmpty() && cc.isNotEmpty() &&
            java.io.File(ts).exists() && java.io.File(cc).exists()
    }

    // ---- Video channel (cert B) ----
    //
    // Cert B enrolls under its OWN TAK Server username/password (see TakCertEnroller's doc: the
    // username/password authenticate a CSR signing request, there is no cert file to "upload"),
    // so it lands in a different channel/group than cert A. It reuses cert A's host/enroll
    // port/CoT port — one aircraft, one controller, two certificates — only the username,
    // password and file-name prefix differ.

    /** Enroll cert B and, on success, connect the video channel. Mirrors [enrollAndConnect]. */
    private fun enrollAndConnectVideo(
        host: String, enrollPort: Int, cotPort: Int, username: String, password: String,
    ) {
        AppLog.v(TAG, "enrollAndConnectVideo: host=$host enrollPort=$enrollPort cotPort=$cotPort user=$username")
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        // Basic has no second account. The button is inside the hidden section; this is the
        // backstop, so no path can enroll one.
        if (!AppMode.elevatedAccountAllowed(prefs)) {
            AppLog.i(TAG, "Basic mode: Elevated enroll refused")
            return
        }
        var uid = prefs.getString(KEY_CHB_UID, "") ?: ""
        if (uid.isEmpty()) {
            uid = "TAKPilot2-" + UUID.randomUUID().toString().substring(0, 8) + "-VIDEO"
            prefs.edit().putString(KEY_CHB_UID, uid).apply()
        }
        setVideoChannelStatus("Enrolling video channel with $host:$enrollPort …",
            androidx.core.content.ContextCompat.getColor(applicationContext, R.color.tp_text_secondary))
        Thread {
            TakCertEnroller.enroll(host, enrollPort, username, password, uid, filesDir, "tak_video_",
                object : TakCertEnroller.EnrollmentCallback {
                    override fun onSuccess(trustStorePath: String, clientCertPath: String) {
                        prefs.edit()
                            .putString(KEY_CHB_TRUSTSTORE, trustStorePath)
                            .putString(KEY_CHB_CLIENTCERT, clientCertPath)
                            .putString(KEY_CHB_USERNAME, username)
                            .putBoolean(KEY_CHB_LOGGED_OUT, false)
                            .apply()
                        runOnUiThread {
                            setVideoChannelStatus("Enrolled. Connecting …",
                                androidx.core.content.ContextCompat.getColor(applicationContext, R.color.tp_text_secondary))
                        }
                        connectVideoChannelWithCerts(host, cotPort, trustStorePath, clientCertPath)
                    }

                    override fun onError(error: String) {
                        AppLog.w(TAG, "video channel enrollment failed: $error")
                        runOnUiThread {
                            setVideoChannelStatus("Error: $error",
                                androidx.core.content.ContextCompat.getColor(applicationContext, R.color.tp_state_danger))
                        }
                    }
                })
        }.start()
    }

    /** Connect cert B using already-enrolled files. Mirrors [connectWithCerts]. */
    private fun connectVideoChannelWithCerts(host: String, cotPort: Int, trustStorePath: String, clientCertPath: String) {
        // ⚠ THE FUNNEL, GATED. Every cert-B dial on this screen comes through here
        // — enroll, the switch, and the onCreate auto-redial — and this screen dials OUTSIDE
        // TakAutoConnect.reconnect, so the gate there does not cover it. The first version hid
        // this screen's section in Basic and gated none of its dials, so opening the screen put
        // the split straight back. Defence in depth: the onCreate path is gated at its own
        // condition too.
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (!AppMode.elevatedAccountAllowed(prefs)) {
            AppLog.i(TAG, "Basic mode: Elevated dial refused")
            return
        }
        // ⚠ Queued on ElevatedChannel, never dialled from here directly. These
        // calls come from worker threads that outlive the screen (enrollment's onSuccess, the
        // saved-cert reconnect), and a direct dial could interleave with a clear elsewhere —
        // stopping a client before its thread started, which the shared core cannot survive.
        // The channel reads the mode again at the moment of the dial, and AGAIN after it.
        // FORCED: a fresh enrollment's certificates must be dialled even over a configured
        // channel; the channel waits for any just-started client to settle first.
        ElevatedChannel.dial(prefs, host, cotPort, trustStorePath, clientCertPath, force = true) {
            java.io.File(trustStorePath).exists() && java.io.File(clientCertPath).exists()
        }
        // The Elevated connection can be up with no Standard connection and no aircraft (a
        // fresh setup, a failed Standard enrollment). The foreground service is what holds
        // onTaskRemoved and the power-off receiver, so it must run for cert B alone too.
        runCatching { TakForegroundService.start(applicationContext, TakForegroundService.callsignFor(applicationContext)) }
            .onFailure { AppLog.w(TAG, "foreground service start failed: ${it.message}") }
        runOnUiThread {
            // "Connecting", not "connected": the socket is being dialled on its own thread and
            // nothing has answered yet. The channel list that follows is the proof it works.
            setVideoChannelStatus("Elevated account: connecting …",
                androidx.core.content.ContextCompat.getColor(applicationContext, R.color.tp_text_secondary))
            refreshVideoChannels()
        }
    }

    /** Reconnect cert B using saved certs, no UI entry needed. Mirrors [reconnectFromSaved]. */
    private fun reconnectVideoFromSaved(prefs: android.content.SharedPreferences) {
        val host = prefs.getString(KEY_HOST, "") ?: ""
        val cotPort = prefs.getInt(KEY_COT_PORT, 8089)
        val ts = prefs.getString(KEY_CHB_TRUSTSTORE, "") ?: ""
        val cc = prefs.getString(KEY_CHB_CLIENTCERT, "") ?: ""
        if (host.isEmpty() || ts.isEmpty() || cc.isEmpty()) return
        Thread { connectVideoChannelWithCerts(host, cotPort, ts, cc) }.start()
    }

    /** True if cert B has saved cert files on disk from a previous enrollment. Mirrors
     *  [hasSavedCerts]. */
    private fun hasSavedVideoCerts(prefs: android.content.SharedPreferences): Boolean {
        val ts = prefs.getString(KEY_CHB_TRUSTSTORE, "") ?: ""
        val cc = prefs.getString(KEY_CHB_CLIENTCERT, "") ?: ""
        return ts.isNotEmpty() && cc.isNotEmpty() &&
            java.io.File(ts).exists() && java.io.File(cc).exists()
    }

    /** Deletes cert B's files + prefs and disconnects it. Called from [clearEnrollment] (full
     *  logout) and also usable on its own if the pilot turns the video-channel switch off. */
    private fun clearVideoEnrollment(prefs: android.content.SharedPreferences) {
        // A removal, not a drop: single-connection behaviour comes back. Through ElevatedChannel,
        // so it cannot stop a client another path has only just started.
        ElevatedChannel.clear()
        val ts = prefs.getString(KEY_CHB_TRUSTSTORE, "") ?: ""
        val cc = prefs.getString(KEY_CHB_CLIENTCERT, "") ?: ""
        if (ts.isNotEmpty()) { val f = java.io.File(ts); runCatching { f.delete() } }
        if (cc.isNotEmpty()) { val f = java.io.File(cc); runCatching { f.delete() } }
        listOf("tak_video_clientcert.p12", "tak_video_truststore.p12").forEach {
            val f = java.io.File(filesDir, it); if (f.exists()) runCatching { f.delete() }
        }
        prefs.edit()
            .remove(KEY_CHB_TRUSTSTORE)
            .remove(KEY_CHB_CLIENTCERT)
            .remove(KEY_CHB_UID)
            .remove(KEY_CHB_USERNAME)
            .putBoolean(KEY_CHB_LOGGED_OUT, true)
            .apply()
        AppLog.i(TAG, "video channel enrollment cleared")
    }

    private fun setVideoChannelStatus(text: String, color: Int) {
        findViewById<TextView>(R.id.takVideoChannelStatus)?.let {
            it.text = text
            it.setTextColor(color)
        }
    }

    /** Re-reads the Elevated account's channels and repaints. Mirrors [refreshChannels]. */
    private fun refreshVideoChannels() {
        // One reader for the Elevated list, shared with the flight screen's dialog.
        TakMissionManager.listElevatedChannels(this) { chans -> renderVideoChannels(chans ?: return@listElevatedChannels) }
    }

    /**
     * Wires the "Video Channel" section (cert B) — OFF by default. [host]/[enrollPort]/
     * [cotPort] are cert A's already-on-screen fields, reused as-is (one aircraft, one
     * controller, two certificates — only the username/password/file-prefix differ).
     */
    private fun setupVideoChannelSection(
        prefs: android.content.SharedPreferences,
        host: EditText, enrollPort: EditText, cotPort: EditText,
    ) {
        val enabledSwitch = findViewById<android.widget.Switch>(R.id.takVideoChannelEnabled)
        val fields = findViewById<android.widget.LinearLayout>(R.id.takVideoChannelFields)
        val connectButton = findViewById<Button>(R.id.takVideoConnectButton)
        val channelsLabel = findViewById<TextView>(R.id.takVideoChannelsLabel)
        val videoUsername = findViewById<EditText>(R.id.takVideoUsername)
        val videoPassword = findViewById<EditText>(R.id.takVideoPassword)

        videoUsername.setText(prefs.getString(KEY_CHB_USERNAME, ""))

        fun paintEnabled(on: Boolean) {
            fields.visibility = if (on) android.view.View.VISIBLE else android.view.View.GONE
            connectButton.visibility = if (on) android.view.View.VISIBLE else android.view.View.GONE
            channelsLabel.visibility = if (on) android.view.View.VISIBLE else android.view.View.GONE
        }

        val enabled = prefs.getBoolean(KEY_CHB_ENABLED, false)
        enabledSwitch.isChecked = enabled
        paintEnabled(enabled)
        // ⚠ The mode is the FIRST conjunct. This runs on every onCreate and dials
        // OUTSIDE TakAutoConnect.reconnect; on chb_enabled alone it would put the split back in
        // Basic the moment the pilot opened this screen, behind a section that is hidden.
        if (AppMode.elevatedAccountAllowed(prefs) &&
            enabled && hasSavedVideoCerts(prefs) && !prefs.getBoolean(KEY_CHB_LOGGED_OUT, false)
        ) {
            // ⚠ DO NOT RE-DIAL A CONNECTION THAT IS ALREADY UP. This runs
            // on every onCreate, and a reconnect tears the Elevated socket down and re-dials
            // it — which also used to end a running Emergency Broadcast. A pilot
            // opening this screen to read a channel must change nothing on the wire.
            if (TakManager.getInstance().isVideoChannelConnected()) {
                // ⚠ BUT THE LIST STILL HAS TO BE READ. refreshVideoChannels() used to be reachable only
                // THROUGH the reconnect, so the one path a pilot is most likely to be on —
                // arriving with the Elevated account already up — left the list empty and the
                // status line blank under a heading that promised both. Worse, the overlap
                // check needs this list: with it unread, the one server mistake this screen can
                // see (a channel ACTIVE on both accounts, which fails the split OPEN) went
                // unreported on exactly that path. Reading is an HTTPS GET on a worker; it
                // touches no socket of ours.
                setVideoChannelStatus("Elevated account connected.",
                    androidx.core.content.ContextCompat.getColor(applicationContext, R.color.tp_state_go))
                refreshVideoChannels()
            } else {
                setVideoChannelStatus("Reconnecting the Elevated account …",
                    androidx.core.content.ContextCompat.getColor(applicationContext, R.color.tp_text_secondary))
                reconnectVideoFromSaved(prefs)
            }
        }

        enabledSwitch.setOnCheckedChangeListener { _, isOn ->
            AppLog.v(TAG, "video channel enable toggle -> $isOn")
            prefs.edit().putBoolean(KEY_CHB_ENABLED, isOn).apply()
            paintEnabled(isOn)
            if (!isOn) {
                // Turning the switch off REMOVES the video channel until it is switched on — on purpose,
                // thus clearVideoChannel() and not disconnectVideoChannel(): the Standard
                // connection carries video again, as before the split existed. It does NOT
                // delete the saved enrollment (that's clearVideoEnrollment's job, on full Log
                // Out); switching back on reconnects from the same saved certs.
                ElevatedChannel.clear()
                setVideoChannelStatus("Elevated account off — video goes out on the Standard account again.",
                    androidx.core.content.ContextCompat.getColor(applicationContext, R.color.tp_text_secondary))
            } else if (hasSavedVideoCerts(prefs)) {
                reconnectVideoFromSaved(prefs)
            }
        }

        connectButton.setOnClickListener {
            val h = host.text.toString().trim()
            val ep = enrollPort.text.toString().trim().toIntOrNull() ?: 8446
            val cp = cotPort.text.toString().trim().toIntOrNull() ?: 8089
            val u = videoUsername.text.toString().trim()
            val p = videoPassword.text.toString()
            if (h.isEmpty() || u.isEmpty() || p.isEmpty()) {
                setVideoChannelStatus("Host (above), video username and password are required.",
                    androidx.core.content.ContextCompat.getColor(applicationContext, R.color.tp_state_danger))
                return@setOnClickListener
            }
            enrollAndConnectVideo(h, ep, cp, u, p)
        }
    }

    /**
     * The Elevated account's channel rows — WRITABLE, the same as the Standard account's.
     * They were read-only at first, on the reasoning that this application never changes what
     * the Elevated account is a member of. The case for reversing it: a server administrator
     * may put that account in several channels, and the pilot needs to choose which of them the
     * aircraft reaches.
     *
     * ⚠ **THIS IS ALSO THE "IGNORE INCOMING" CONTROL, AND IT IS THE ONLY ONE THAT CAN EXIST.**
     * Inbound CoT carries no channel label — a TAK client sends and receives plain CoT and the
     * SERVER decides delivery; `<dest group>` is the invention that destroyed markers when it
     * was tried and must never come back. So the application cannot
     * filter arriving traffic by channel. Unticking a channel is what stops it arriving, and it
     * works because the server stops sending it, not because the client throws it away. Do not
     * re-propose a client-side channel filter.
     */
    private fun renderVideoChannels(channels: List<TakMissionClient.Channel>) {
        val list = findViewById<android.widget.LinearLayout>(R.id.takVideoChannelsList) ?: return
        list.removeAllViews()
        latestVideoChannels = channels
        if (channels.isEmpty()) {
            setVideoChannelStatus("This server has no channels for the Elevated account.",
                androidx.core.content.ContextCompat.getColor(applicationContext, R.color.tp_text_secondary))
            return
        }
        checkChannelOverlap()
        updatingVideoChannels = true
        for (ch in channels) {
            val row = android.widget.CheckBox(this).apply {
                text = TakMissionManager.channelLabel(ch)
                // ⚠ LOCKED IS NOT DISABLED — the same rule as the Standard rows above. Dimming
                // the tick hides which channels are on, and that is the one thing a pilot opens
                // this screen to read.
                setTextColor(androidx.core.content.ContextCompat.getColor(
                    applicationContext,
                    if (takConfigLocked()) R.color.tp_text_secondary else R.color.tp_text_primary))
                isChecked = ch.active
                isClickable = !takConfigLocked()
                isFocusable = !takConfigLocked()
                buttonTintList = android.content.res.ColorStateList.valueOf(
                    androidx.core.content.ContextCompat.getColor(
                        applicationContext, R.color.tp_accent))
                setOnCheckedChangeListener { _, checked ->
                    if (updatingVideoChannels) return@setOnCheckedChangeListener
                    ch.active = checked
                    pushElevatedActiveChannels()
                }
            }
            list.addView(row)
        }
        updatingVideoChannels = false
    }

    /** True while the Elevated boxes are being set from server data, so the listener does not
     *  treat a repaint as a pilot's tap and PUT it straight back. The Standard rows have the
     *  same guard and the same reason. */
    private var updatingVideoChannels = false

    /**
     * Sends the COMPLETE set of the Elevated account's active channels to the server.
     *
     * ⚠ **IT CHANGES EVERY CONTROLLER ON THE ACCOUNT.** activebits is absolute and belongs to
     * the ACCOUNT, and the Elevated account is typically one shared TAK Server user across every
     * controller. Unticking the video channel here takes video away
     * from every controller signed in as that account, not just this one. The section says so
     * above the rows; this is the comment for whoever reads the code instead.
     */
    private fun pushElevatedActiveChannels() {
        val bits = latestVideoChannels.filter { it.active && it.bitpos >= 0 }.map { it.bitpos }
        setVideoChannelStatus("Sending ${bits.size} active channel(s) for the Elevated account…",
            androidx.core.content.ContextCompat.getColor(applicationContext, R.color.tp_text_secondary))
        TakMissionManager.setElevatedActiveChannels(this, bits) { ok ->
            if (ok) {
                AppLog.i(TAG, "Elevated active channels -> ${bits.size} channel(s)")
                setVideoChannelStatus(
                    "The server has ${bits.size} active channel(s) for the Elevated account. " +
                        "This applies to every controller on that account.",
                    androidx.core.content.ContextCompat.getColor(applicationContext, R.color.tp_state_go))
            } else {
                AppLog.w(TAG, "the server refused the Elevated channel change")
                setVideoChannelStatus("The server refused the change.",
                    androidx.core.content.ContextCompat.getColor(applicationContext, R.color.tp_state_danger))
            }
            // Re-read either way: on success to confirm what the server actually holds, on
            // failure because the boxes now show something the server did not accept. The same
            // reasoning the Standard rows follow — this screen mirrors the server, it does not
            // hold the state.
            refreshVideoChannels()
        }
    }

    private fun setStatus(text: String, color: Int) {
        status.text = text
        status.setTextColor(color)
    }

    // ---- My Channels ----


    /**
     * The channels, as the SERVER holds them.
     *
     * This is not a local preference any more. The check box shows the server's `active` state,
     * and a change PUTs the new set to the server — the method a real TAK client uses. Nothing
     * is stored on the controller, thus nothing here can disagree with the server.
     *
     * EVERY CHANNEL CAN BE SWITCHED ON AND OFF, including a receive-only one. The check box is
     * the `active` flag, and `active` governs RECEIVE as well as send. A first version disabled
     * the box on a receive-only channel, which confused "cannot publish to it" with "cannot use
     * it" — and left a channel that could be switched off from TAK Portal with no way to switch
     * it back on from the controller. ADS-B is exactly the channel a
     * pilot wants to turn off and on: it is noisy, and switching it off stops the traffic.
     *
     * The direction is shown as text instead. It tells the pilot what the channel will and will
     * not carry, and it takes nothing away from them.
     */
    private fun renderChannels(channels: List<TakMissionClient.Channel>) {
        val list = findViewById<android.widget.LinearLayout>(R.id.takChannelsList)
        list.removeAllViews()
        latestChannels = channels
        val status = findViewById<TextView>(R.id.takChannelsStatus)
        if (channels.isEmpty()) {
            // A server with channels turned off returns none. Say so, and offer no control:
            // writing to such a server is reported to cause real trouble on it.
            status.text = "This server has no channels."
            status.setTextColor(androidx.core.content.ContextCompat.getColor(
                applicationContext, R.color.tp_text_secondary))
            channelsStatusIsEmptyNotice = true
            return
        }
        // ⚠ CLEAR THE "no channels" NOTICE. The list is empty on the first read, before the
        // server answers, thus the notice is written once and then stood above a full list of
        // channels (seen on hardware). Only that message is cleared: a send, a server
        // answer or a server-side change writes this same line and then re-reads the
        // channels, and those messages must survive the repaint that follows.
        if (channelsStatusIsEmptyNotice) {
            status.text = ""
            channelsStatusIsEmptyNotice = false
        }
        for (ch in channels) {
            val row = android.widget.CheckBox(this).apply {
                // Two-way is the normal case and gets no label — a note on every row is
                // noise, and the exception is what a pilot needs to see.
                text = when {
                    ch.canSend && ch.canReceive -> ch.name
                    ch.canReceive -> "${ch.name} - Rx Only"
                    ch.canSend -> "${ch.name} - Tx Only"
                    else -> "${ch.name} - no direction"
                }
                // Secondary text is the only hint that the row is locked. The tick stays
                // full contrast, because the tick is the information.
                setTextColor(androidx.core.content.ContextCompat.getColor(
                    applicationContext,
                    if (takConfigLocked()) R.color.tp_text_secondary else R.color.tp_text_primary))
                // Enabled for every channel. See the note above: the box is `active`, and a
                // receive-only channel is still one a pilot may want on or off.
                // ⚠ THE LOCK STOPS A CHANGE, NOT THE READING. The rows still follow the
                // server while locked — a pilot must always be able to SEE the scope of this
                // aircraft. The lock exists to stop an accidental change, not to hide the truth.
                //
                // ⚠ LOCKED IS NOT DISABLED. isEnabled=false greys the tick as well as the row,
                // and a pilot then cannot tell a checked box from an unchecked one — which
                // defeats the paragraph above. The row stays at full contrast and stops taking
                // touches instead. The check box keeps its own tint for the same reason.
                isChecked = ch.active
                isClickable = !takConfigLocked()
                isFocusable = !takConfigLocked()
                buttonTintList = android.content.res.ColorStateList.valueOf(
                    androidx.core.content.ContextCompat.getColor(
                        applicationContext, R.color.tp_accent))
                setOnCheckedChangeListener { _, checked ->
                    if (updatingChannels) return@setOnCheckedChangeListener
                    ch.active = checked
                    pushActiveChannels()
                }
            }
            list.addView(row)
        }
        checkChannelOverlap()
    }

    /** The Elevated account's channels, as last read — for [checkChannelOverlap]. */
    private var latestVideoChannels: List<TakMissionClient.Channel> = emptyList()

    /**
     * The one server mistake this screen can see: a channel ACTIVE on BOTH accounts.
     * Everyone in it would get the Elevated copy of the aircraft, video included — the split
     * fails open and nothing on the server says so. Checked whenever either list is painted.
     * The fix is on the server, so the line says that and offers no control.
     */
    private fun checkChannelOverlap() {
        val standard = latestChannels.filter { it.active }.map { it.name }.toSet()
        val elevated = latestVideoChannels.filter { it.active }.map { it.name }.toSet()
        val shared = standard.intersect(elevated)
        if (shared.isEmpty()) {
            // Clear ONLY our own line, so a connect or enrollment status is not wiped — and
            // clear it the moment the server is corrected.
            if (overlapWarningShown) {
                overlapWarningShown = false
                setVideoChannelStatus("", androidx.core.content.ContextCompat.getColor(
                    applicationContext, R.color.tp_text_secondary))
            }
            return
        }
        overlapWarningShown = true
        AppLog.w(TAG, "Standard and Elevated accounts share active channel(s): $shared")
        setVideoChannelStatus("Standard and Elevated share channel ${shared.joinToString()} — " +
            "everyone in it will get video. Fix this on the server.",
            androidx.core.content.ContextCompat.getColor(applicationContext, R.color.tp_state_danger))
    }

    /** True while takVideoChannelStatus holds the overlap warning, so checkChannelOverlap can
     *  retract its own line and nothing else's. */
    private var overlapWarningShown = false

    /**
     * Sends the COMPLETE set of active channels to the server.
     *
     * ⚠ activebits is ABSOLUTE. Anything not in this list is switched off, thus the whole set
     * goes every time and never a change. ⚠ It applies to the CERTIFICATE — every controller
     * enrolled as this user gets this set.
     */
    private fun pushActiveChannels() {
        // ⚠ NEVER WRITE TO A SERVER THAT HAS NO CHANNELS. The TAK Aware developer reported
        // that a channel change sent to a server which does not have channels
        // enabled can do real damage server side — days of debugging on one deployment. No row
        // exists when the list is empty, thus no toggle can fire this, but the guard is here
        // so that stays true if a caller is ever added.
        if (latestChannels.isEmpty()) {
            AppLog.w(TAG, "channel write refused — this server returned no channels")
            return
        }
        val bits = latestChannels.filter { it.active && it.bitpos >= 0 }.map { it.bitpos }
        val status = findViewById<TextView>(R.id.takChannelsStatus)
        channelsStatusIsEmptyNotice = false
        status.text = "Sending ${bits.size} active channel(s) to the server…"
        TakMissionManager.setActiveChannels(bits) { ok ->
            status.text = if (ok) "Server accepted ${bits.size} active channel(s)."
                          else "The server refused the change. See the log."
            status.setTextColor(androidx.core.content.ContextCompat.getColor(applicationContext,
                if (ok) R.color.tp_state_go else R.color.tp_state_danger))
            // Read it back. The server is the truth, not what was just tapped.
            refreshChannels()
        }
    }

    /** Re-reads the channels from the server and repaints. The server can be changed from TAK
     *  Portal by an administrator, thus the screen must follow it and not a local copy. */
    private fun refreshChannels() {
        TakMissionManager.listChannels { chans ->
            updatingChannels = true
            renderChannels(chans)
            updatingChannels = false
        }
    }

    /** The server told us the channels changed. Read them again — the event carries a notice,
     *  not a list. */
    private val groupChangeListener = TakManager.GroupChangeListener {
        AppLog.i(TAG, "channels changed on the server — re-reading")
        refreshChannels()
        channelsStatusIsEmptyNotice = false
        findViewById<TextView>(R.id.takChannelsStatus)?.text =
            "The server changed the channels. The list is up to date."
    }

    /**
     * Reads the channels again when TAK connects, and paints the connection status.
     * The false branch was empty, so a failed connect could never be corrected. TakClient
     * reports a failed connect as a disconnect and retries about every 5 s, so this repaints
     * while a bad host keeps failing — harmless, and honest for both cases.
     */
    private val connectionListener = object : TakManager.TakUserListener {
        override fun onTakUserUpdated(user: com.taklite.client.tak.TakUser) {}
        override fun onTakUserRemoved(uid: String) {}
        override fun onTakUserDeleted(uid: String) {}
        override fun onTakConnectionChanged(connected: Boolean) {
            val cs = trackedCallsign
            runOnUiThread {
                if (connected) {
                    AppLog.i(TAG, "TAK connected — reading the channels")
                    setStatus(
                        if (cs != null) "Connected. Sending the aircraft position to TAK as \"$cs\"."
                        else "Connected. Sending the aircraft position to TAK.",
                        androidx.core.content.ContextCompat.getColor(applicationContext, R.color.tp_state_go))
                    refreshChannels()
                } else if (cs != null) {
                    AppLog.w(TAG, "TAK not connected — retrying")
                    setStatus("Not connected. Check the server and the certificates. Retrying …",
                        androidx.core.content.ContextCompat.getColor(applicationContext, R.color.tp_state_danger))
                }
            }
        }
    }

    /** The TAK configuration lock. The channel rows read it each time they are painted, thus a
     *  lock or unlock takes effect without leaving the screen. */
    private fun takConfigLocked(): Boolean =
        getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(TakConnectActivity.KEY_TAK_LOCKED, false)

    private var latestChannels: List<TakMissionClient.Channel> = emptyList()
    /** True while the check boxes are being set from server data, so the listener does not
     *  treat a repaint as a pilot's tap and PUT it straight back. */
    private var updatingChannels = false
    /** True while takChannelsStatus holds the "no channels" notice that renderChannels wrote.
     *  The other writers of that line (a send, a server answer, a server-side change) must not
     *  be wiped by a later repaint, thus renderChannels clears only its own message. */
    private var channelsStatusIsEmptyNotice = false

    companion object {
        private const val TAG = "TakServerActivity"

        /** ⚠ The same preference FILE Pre-Flight uses — nothing migrated when this screen took
         *  the fields, so every key below is the key that was already written. */
        private val PREFS get() = TakConnectActivity.PREFS

        private const val KEY_HOST = "host"
        private const val KEY_ENROLL_PORT = "enroll_port"
        private const val KEY_COT_PORT = "cot_port"
        private const val KEY_USERNAME = "username"
        private const val KEY_CALLSIGN = "callsign"
        private const val KEY_CAMERA_POINT = "camera_point"
        private const val KEY_CHANNELS = "channels"
        private const val KEY_LOGGED_OUT = "logged_out"
        private const val KEY_UID = "uid"
        private const val KEY_TRUSTSTORE = "truststore_path"
        private const val KEY_CLIENTCERT = "clientcert_path"

        // ---- Elevated account (cert B). "CHB" = "channel B". ----
        private const val KEY_CHB_ENABLED = "chb_enabled"
        private const val KEY_CHB_USERNAME = "chb_username"
        private const val KEY_CHB_UID = "chb_uid"
        private const val KEY_CHB_TRUSTSTORE = "chb_truststore_path"
        private const val KEY_CHB_CLIENTCERT = "chb_clientcert_path"
        private const val KEY_CHB_LOGGED_OUT = "chb_logged_out"
    }
}
