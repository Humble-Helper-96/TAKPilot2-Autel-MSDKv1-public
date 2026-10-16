package com.autel.sdksample.tak

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.autel.sdk.Autel
import com.autel.sdksample.BuildConfig
import com.autel.sdksample.R
import com.taklite.client.tak.TakManager
import com.taklite.util.AppLog

/**
 * TAKPilot2-Autel home screen:
 * logo + TAK status and a Quick Controls card on the left, the large "Enter Flight" card on
 * the right. TAK auto-connects in the background before the pilot even enters flight.
 *
 * Kept deliberately minimal (design decision). Three extras were REMOVED: an "SDK Test
 * Tools" button (opened the stock Autel sample's ProductActivity — debug only, and reachable
 * by other means), and two Quick Controls checkboxes for video streaming and camera look-point. Neither checkbox owned its
 * setting: look-point lives in Pre-Flight Setup and is applied on auto-connect by
 * [TakAutoConnect], and streaming is started from the flight screen's LIVE badge — so
 * removing them dropped no functionality. The Enter Flight card's battery line went too;
 * battery is on the flight toolbar's gauge.
 *
 * Aircraft status comes from [AutelProductHolder].
 */
class TakPilotHomeActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var aircraft: TextView
    private lateinit var aircraftImage: ImageView
    private lateinit var avoidance: TextView
    private lateinit var signalLoss: TextView
    private lateinit var stickMode: TextView
    private lateinit var controlResponse: TextView
    private lateinit var batteryLive: TextView
    private lateinit var batteryLevels: TextView
    private lateinit var storage: TextView
    private lateinit var initializing: TextView
    private lateinit var sdk: TextView
    private lateinit var takStatus: TextView
    private lateinit var takDot: android.view.View
    private lateinit var wifiStatus: TextView
    private lateinit var wifiDot: android.view.View
    private lateinit var permissionsStatus: TextView
    private lateinit var permissionsDot: android.view.View
    private lateinit var mediaStatus: TextView
    private lateinit var mediaDot: android.view.View

    /**
     * Last answer from [MediaServerProbe], and when it was taken.
     *
     * ⚠ CACHED AND PROBED OFF THE UI THREAD. The home screen repaints every 1.5 s and this is a
     * network round trip; running it inline would stall the screen for up to the probe timeout
     * every tick. The paint reads the cache, a background probe refreshes it on its own period.
     */
    @Volatile private var mediaProbe: MediaServerProbe.Result? = null
    @Volatile private var mediaProbeAtMs = 0L
    @Volatile private var mediaProbeRunning = false

    /** Camera free space for the storage line. The camera reports MEGABYTES; anything from a
     *  gigabyte up reads as GB, because "122049 MB" is a number a pilot has to stop and convert. */
    private fun spaceLabel(mb: Long): String =
        if (mb >= 1024) String.format("%.1f GB", mb / 1024.0) else "$mb MB"

    private val refresh = object : Runnable {
        override fun run() {
            updateStatus()
            handler.postDelayed(this, 1500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_takpilot2_home)
        AppLog.v(TAG, "onCreate")
        // Mark that this process has passed through Home. Read by FlightActivity to tell a normal
        // Home→Flight entry from Android resurrecting the flight screen into a cold process after
        // an OOM kill (see FlightActivity.onCreate's cold-restart guard).
        visitedThisProcess = true

        // Wire the aircraft-connection singleton once, then silently reconnect TAK with
        // saved enrollment so the pilot lands here already connected.
        AutelProductHolder.install()
        TakAutoConnect.tryReconnect(applicationContext)
        // Finish any flight track a crash left without its GPX. Posts to the
        // logger's own worker thread; Home is not slowed.
        FlightPathLogger.sweepOrphans()

        aircraft = findViewById(R.id.homeAircraft)
        aircraftImage = findViewById(R.id.homeAircraftImage)
        avoidance = findViewById(R.id.homeAvoidance)
        signalLoss = findViewById(R.id.homeSignalLoss)
        stickMode = findViewById(R.id.homeStickMode)
        controlResponse = findViewById(R.id.homeControlResponse)
        batteryLive = findViewById(R.id.homeBatteryLive)
        batteryLevels = findViewById(R.id.homeBatteryLevels)
        storage = findViewById(R.id.homeStorage)
        initializing = findViewById(R.id.homeInitializing)
        sdk = findViewById(R.id.homeSdk)
        takStatus = findViewById(R.id.homeTakStatus)
        takDot = findViewById(R.id.homeTakDot)
        wifiStatus = findViewById(R.id.homeWifiStatus)
        wifiDot = findViewById(R.id.homeWifiDot)
        permissionsStatus = findViewById(R.id.homePermissionsStatus)
        permissionsDot = findViewById(R.id.homePermissionsDot)
        mediaStatus = findViewById(R.id.homeMediaStatus)
        mediaDot = findViewById(R.id.homeMediaDot)
        // ⚠ THE LINE IS TAPPABLE WHEN DENIED, AND THAT IS THE POINT OF IT. Telling a pilot that
        // their position is not going out, on a screen where they cannot do anything about it,
        // is half a fix. See askForLocation.
        findViewById<android.view.View>(R.id.homePermissionsRow)
            .setOnClickListener { askForPermissions() }
        // Fixed at build time, not runtime state — set once, no need to touch it in updateStatus().
        // VERSION_NAME is real semver (see build.gradle); VERSION_CODE is Android's own internal
        // update-ordering integer and has no semver meaning, so it is deliberately not shown here
        // — BUILD_TIME already identifies an exact build more precisely than that number could.
        findViewById<TextView>(R.id.homeVersion).text =
            "v${BuildConfig.VERSION_NAME}  ·  built ${BuildConfig.BUILD_TIME}"

        // One tap target: the whole card. The separate "Enter Flight" button that used to sit
        // below it was removed — it duplicated the card's own click and cost ~56dp of a
        // height budget that was already over on shorter screens (see the layout comment).
        findViewById<android.view.View>(R.id.homeEnterFlight).setOnClickListener {
            if (initializingUntilMs > System.currentTimeMillis()) {
                AppLog.v(TAG, "Enter Flight tapped during initialise — ignored")
                android.widget.Toast.makeText(this,
                    "Wait. The app is setting up the aircraft.",
                    android.widget.Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            AppLog.v(TAG, "Enter Flight tapped")
            startActivity(Intent(this, FlightActivity::class.java))
        }
        findViewById<Button>(R.id.homeTakSetup).setOnClickListener {
            AppLog.v(TAG, "TAK Setup tapped")
            startActivity(Intent(this, TakConnectActivity::class.java))
        }

        findViewById<Button>(R.id.homeDataSync).setOnClickListener {
            AppLog.v(TAG, "Data Sync tapped")
            startActivity(Intent(this, DataSyncActivity::class.java))
        }
        findViewById<Button>(R.id.homeDebug).setOnClickListener {
            AppLog.v(TAG, "Debug tapped")
            startActivity(Intent(this, DebugActivity::class.java))
        }
        findViewById<Button>(R.id.homeQuit).setOnClickListener {
            AppLog.v(TAG, "STOP/QUIT tapped")
            confirmQuit()
        }
        findViewById<Button>(R.id.homeFieldGuide).setOnClickListener {
            AppLog.v(TAG, "Field Guide tapped")
            startActivity(Intent(this, FieldGuideActivity::class.java))
        }
    }

    /** The "nuclear option": tear down every long-lived TAKPilot2-Autel process (video
     *  stream, TAK connection + its foreground service, telemetry bridge) and then kill this
     *  process outright, so a relaunch starts completely clean — for clearing out any stuck
     *  state found mid-operation without having to know which subsystem is wedged. */
    private fun confirmQuit() {
        // Themed: an unthemed dialog renders as a white card on a dark screen.
        AlertDialog.Builder(this, R.style.TakDialogTheme_Destructive)
            .setTitle("Stop & Quit")
            .setMessage("Stop TAKPilot and close the video, the TAK connection, and telemetry? You must start the app again to fly.")
            .setPositiveButton("Stop & Quit") { _, _ -> doQuit() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun doQuit() {
        AppLog.i(TAG, "STOP/QUIT — tearing down and killing process")
        // Same teardown a swipe now performs — see AppTeardown. These two paths previously did
        // different subsets of the work, which is how the SDK ended up never being released on
        // task removal.
        runCatching { AppTeardown.releaseAll(applicationContext, "stop/quit") }
        handler.removeCallbacksAndMessages(null)
        finishAffinity()
        Handler(Looper.getMainLooper()).postDelayed({
            android.os.Process.killProcess(android.os.Process.myPid())
        }, 200)
    }

    override fun onResume() {
        super.onResume()
        AppLog.v(TAG, "onResume")
        AutelProductHolder.install()   // reclaim the global product listener (see holder docs)
        // ⚠ ON RESUME, NOT ONLY ON CREATE. This screen is singleTask, and the foreground
        // service keeps the process alive across a swipe-away — so onCreate's one attempt can
        // be the only one a pilot ever gets, even when they think they have relaunched the
        // application. See TakAutoConnect.retryIfDown.
        TakAutoConnect.retryIfDown(applicationContext)
        handler.post(refresh)
    }

    override fun onPause() {
        super.onPause()
        AppLog.v(TAG, "onPause")
        handler.removeCallbacks(refresh)
    }

    /**
     * Holds the pilot on this screen while the connect-time pushes land.
     *
     * The settings that decide how the aircraft handles — stick mode, control response,
     * obstacle avoidance — are enforced a few seconds AFTER the product connects. A pilot who
     * taps straight through lands on the flight screen mid-push and cannot know what was
     * applied. Five seconds is not about the pushes needing exactly that long; it is about the
     * pilot's eyes being on the card that states what they are about to fly with.
     *
     * The word PULSES rather than sitting still on purpose: a frozen label on a screen that
     * refuses taps reads as a crash, and a pilot who thinks the app has hung will force-quit it.
     */
    private fun startInitializing() {
        initializingUntilMs = System.currentTimeMillis() + INITIALIZING_MS
        initializing.visibility = View.VISIBLE
        initializing.alpha = 1f
        initializing.animate().cancel()
        pulseInitializing()
        handler.removeCallbacks(endInitializing)
        handler.postDelayed(endInitializing, INITIALIZING_MS)
    }

    private fun pulseInitializing() {
        if (initializingUntilMs <= System.currentTimeMillis()) return
        initializing.animate().alpha(0.25f).setDuration(700L).withEndAction {
            if (initializingUntilMs <= System.currentTimeMillis()) return@withEndAction
            initializing.animate().alpha(1f).setDuration(700L)
                .withEndAction { pulseInitializing() }.start()
        }.start()
    }

    private val endInitializing = Runnable {
        initializingUntilMs = 0L
        initializing.animate().cancel()
        initializing.visibility = View.GONE
        AppLog.v(TAG, "initialise hold released")
    }

    /** Long enough for the connect-time pushes AND for the pilot to read the card. 10 s; 5 s
     *  proved too short. */
    private val INITIALIZING_MS = 10_000L

    private var initializingUntilMs = 0L
    private var sawProduct = false

    private fun updateStatus() {
        val product = AutelProductHolder.product
        aircraft.text = product?.type?.toString() ?: "Not connected"
        // Shown only once an aircraft is actually connected, so the picture is a status
        // cue rather than decoration. INVISIBLE rather than GONE — see the layout note:
        // collapsing it would move "TAP TO ENTER" every time the aircraft comes and goes.
        aircraftImage.visibility = if (product != null) View.VISIBLE else View.INVISIBLE

        // Obstacle avoidance, reported the moment the aircraft syncs.
        //
        // THREE STATES, NOT TWO. "Unknown" is shown as its own thing rather than collapsed into
        // "off", because a pilot reading "AVOIDANCE OFF" will act on it, and telling them the
        // system is off when we simply have not been told yet would be a lie with consequences.
        // Amber for unknown, red for genuinely disabled, green for on.
        avoidance.text = when {
            product == null -> ""
            AutelAvoidance.systemEnabled == true -> "OBSTACLE AVOIDANCE: ON"
            AutelAvoidance.systemEnabled == false -> "OBSTACLE AVOIDANCE: OFF"
            else -> "OBSTACLE AVOIDANCE: —"
        }
        avoidance.setTextColor(androidx.core.content.ContextCompat.getColor(this,
            when (AutelAvoidance.systemEnabled) {
                true -> R.color.tp_state_go
                false -> R.color.tp_state_danger
                // Not answered yet. Unknown is its own state and its own colour.
                null -> R.color.tp_state_unknown
            }))

        // SIGNAL LOSS — a statement of what the airframe does, not a setting.
        //
        // This line first went up amber, telling the pilot to confirm the choice in Autel
        // Explorer. THERE IS NO SUCH CHOICE. Explorer V3.1.134 on the controller contains
        // exactly one lost-action type in the whole application, and it is
        // `com.autel.common.mission.evo.RemoteControlLostSignalAction` — a WAYPOINT MISSION
        // setting whose values are Continue and Return Home. No hover/land/return picker for
        // ordinary flight exists in Explorer's menus either.
        //
        // Which explains the rest of it: the SDK's EmergencyAction (NONE/HOVER/LAND/GO_HOME)
        // is a generic Autel enum this airframe does not implement, so `doEmergencyAction`
        // times out with no acknowledgement — there is nothing for it to set. Flight testing
        // confirmed the aircraft returns home on link loss. Return to Home is not the
        // configured behaviour; it is the ONLY behaviour.
        //
        // So the card answers the pilot's real question — "what happens if I fly out of
        // range" — and does not ask them to go check something that is not there. Neutral,
        // not amber: nothing here is unknown or wrong.
        //
        // ⚠ The one exception is a WAYPOINT MISSION, where the mission's own lost action can
        // be set to Continue. TAKPilot does not fly waypoint missions, so it cannot arise
        // from this app; revisit this line if that ever changes.
        signalLoss.text = if (product == null) "" else "SIGNAL LOSS: RETURNS HOME"
        signalLoss.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.tp_state_go))

        // Sticks and control feel. Both are enforced from Pre-Flight at connect, so these state
        // what the aircraft WILL do rather than what it happens to be set to.
        // The hold starts when the aircraft first appears — that is when the connect-time
        // pushes are scheduled, so that is when there is something to wait for.
        if (product != null && !sawProduct) {
            sawProduct = true
            startInitializing()
        } else if (product == null) {
            sawProduct = false
        }

        stickMode.text = if (product == null) "" else "STICKS: ${AutelControlRates.stickModeLabel()}"
        controlResponse.text = when {
            product == null -> ""
            AutelControlRates.precisionActive == true -> "CONTROL RESPONSE: PRECISION"
            AutelControlRates.precisionActive == false -> "CONTROL RESPONSE: NORMAL"
            else -> "CONTROL RESPONSE: —"
        }
        // Battery levels, straight from the aircraft — never the saved preference. If Apply to
        // Aircraft did not take, this is where it shows, so falling back to what the pilot typed
        // would hide the one failure this line exists to catch. Amber until they arrive, matching
        // the "unknown is not the same as known" rule the avoidance line above follows.
        val warn = FlightLimitsController.aircraftWarningPct
        val crit = FlightLimitsController.aircraftCriticalPct
        batteryLevels.text = when {
            product == null -> ""
            warn != null && crit != null ->
                "BATTERY LIMITS: WARN ${Math.round(warn)}% · CRIT ${Math.round(crit)}%"
            else -> "BATTERY LIMITS: —"
        }
        batteryLevels.setTextColor(androidx.core.content.ContextCompat.getColor(this,
            if (warn != null && crit != null) R.color.tp_accent
            else R.color.tp_state_unknown))

        renderLiveBattery(product != null, warn, crit)

        // Camera storage — WHERE THE FOOTAGE GOES, and whether there is room for it. Follows the
        // avoidance line's three-state rule: unknown is amber and says so, rather than being
        // dressed up as a working card. RED is reserved for "you will get no recording": the
        // camera pointed at internal memory (the silent-REC case), or a card that is
        // full or unusable. See AutelProductHolder's storage block.
        val h = AutelProductHolder
        val sd = h.sdCardState
        val sdReady = sd == com.autel.common.camera.base.SDCardState.CARD_READY
        storage.text = when {
            product == null -> ""
            h.recordingToInternal ->
                "RECORDING TO INTERNAL MEMORY" +
                    (h.mmcFreeMb?.let { " · ${spaceLabel(it)} FREE" } ?: "")
            h.storageTarget == com.autel.common.camera.media.SaveLocation.SD_CARD && sdReady ->
                "SD CARD" + (h.sdFreeMb?.let { " · ${spaceLabel(it)} FREE" } ?: "")
            h.storageTarget == com.autel.common.camera.media.SaveLocation.SD_CARD ->
                "SD CARD: ${sd?.toString() ?: "—"}"
            else -> "STORAGE: —"
        }
        storage.setTextColor(androidx.core.content.ContextCompat.getColor(this, when {
            h.recordingToInternal -> R.color.tp_state_danger
            h.storageTarget == com.autel.common.camera.media.SaveLocation.SD_CARD && sdReady ->
                R.color.tp_state_go
            h.storageTarget == com.autel.common.camera.media.SaveLocation.SD_CARD ->
                R.color.tp_state_danger
            // "STORAGE: —" — the camera has not reported a target yet.
            else -> R.color.tp_state_unknown
        }))

        val settled = AutelControlRates.precisionActive != null
        val infoColor = androidx.core.content.ContextCompat.getColor(this,
            if (settled) R.color.tp_accent else R.color.tp_state_unknown)
        stickMode.setTextColor(infoColor)
        controlResponse.setTextColor(infoColor)
        sdk.text = "Autel MSDK " + (runCatching { Autel.getSdkVersion() }.getOrNull() ?: "1.5")

        val connected = TakManager.getInstance().isConnected
        val color = androidx.core.content.ContextCompat.getColor(this,
            if (connected) R.color.tp_state_go else R.color.tp_state_danger)
        takStatus.text = if (connected) "TAK: Connected" else "TAK: Disconnected"
        takStatus.setTextColor(color)
        (takDot.background as? android.graphics.drawable.GradientDrawable)?.setColor(color)
            ?: takDot.background?.setTint(color)

        // Wifi, directly under the TAK line it explains — the network state IS the diagnosis
        // for most "enrollment failed" reports. Green only on a VALIDATED network:
        // an SSID with no upstream shows amber and says so, which is the case the TAK dot
        // alone cannot separate from "server down". SSID included when the OS shares it, so
        // a pilot on the wrong hotspot sees the wrong NAME, not just a weak signal.
        val net = NetworkStatus.read(this)
        wifiStatus.text = when (net.state) {
            NetworkStatus.State.CONNECTED ->
                "WIFI: ${net.ssid ?: "Connected"}  ${net.bars()}"
            NetworkStatus.State.NO_INTERNET ->
                "WIFI: ${net.ssid ?: "Connected"} — NO INTERNET"
            NetworkStatus.State.OFF -> "WIFI: NOT CONNECTED"
        }
        // App permissions, under the network lines. The pilot marker is what is usually at
        // stake: without location the aircraft still flies and streams, and the PILOT is simply
        // absent from the team's map — see AppPermissions for what that cost once.
        val permsOk = AppPermissions.allGranted(this)
        permissionsStatus.text =
            if (permsOk) "APP PERMISSIONS: Granted" else "APP PERMISSIONS: Denied"
        val permColor = androidx.core.content.ContextCompat.getColor(this,
            if (permsOk) R.color.tp_state_go else R.color.tp_state_danger)
        permissionsStatus.setTextColor(permColor)
        (permissionsDot.background as? android.graphics.drawable.GradientDrawable)?.setColor(permColor)
            ?: permissionsDot.background?.setTint(permColor)

        renderMediaServer()

        val wifiColor = androidx.core.content.ContextCompat.getColor(this, when (net.state) {
            NetworkStatus.State.CONNECTED -> R.color.tp_state_go
            NetworkStatus.State.NO_INTERNET -> R.color.tp_state_caution
            NetworkStatus.State.OFF -> R.color.tp_state_danger
        })
        wifiStatus.setTextColor(wifiColor)
        (wifiDot.background as? android.graphics.drawable.GradientDrawable)?.setColor(wifiColor)
            ?: wifiDot.background?.setTint(wifiColor)
    }

    companion object {
        private const val TAG = "TakPilotHomeActivity"

        private const val REQUEST_CODE_PERMISSIONS = 4301

        /** How often the media server is re-probed. The home screen repaints every 1.5 s and a
         *  probe is a network round trip; re-asking on every paint would be a port scan of the
         *  team's own server. */
        private const val MEDIA_PROBE_PERIOD_MS = 10_000L

        /** Whether this controller has been asked once. Android's
         *  shouldShowRequestPermissionRationale reads false BOTH before the first ask and after
         *  a permanent denial, so it cannot tell them apart; this is what does. */
        private const val KEY_ASKED_PERMISSIONS = "asked_app_permissions"

        /**
         * True once Home has run in the current process. Survives config changes (same process);
         * reset to false whenever the OS recreates the process. FlightActivity reads it to
         * distinguish a deliberate Home→Flight entry from Android resurrecting the flight screen
         * directly into a cold process after an OOM kill — in the cold-process case the Autel SDK
         * was never armed ([AutelProductHolder.install] runs from Home), so the flight screen would
         * otherwise come up with a dead aircraft link and a frozen HUD.
         */
        @Volatile var visitedThisProcess = false
            private set
    }

    /**
     * Asks for every permission still missing, and falls back to the settings page when Android
     * will not ask again.
     *
     * ⚠ **ANDROID ONLY SHOWS THE DIALOG WHILE IT FEELS LIKE IT.** Once a pilot has denied twice,
     * or chosen "don't ask again", `requestPermissions` returns immediately having shown
     * nothing — so a red line that cannot be cleared by touching it would be worse than no line
     * at all. `shouldShowRequestPermissionRationale` reads false in BOTH the never-asked and the
     * permanently-denied case and cannot separate them; what separates them is whether we have
     * asked before, which is remembered here.
     *
     * Asks for ALL of them in one call — the framework shows a dialog per permission, in the
     * order [AppPermissions.missing] returns them, so location comes first.
     *
     * ⚠ NOT A SETTINGS CHANGE TO THE CONTROLLER. The fallback opens the
     * application's OWN permission page and the pilot makes the change; nothing here writes it.
     */
    private fun askForPermissions() {
        val missing = AppPermissions.missing(this)
        if (missing.isEmpty()) return
        val asked = getPreferences(MODE_PRIVATE).getBoolean(KEY_ASKED_PERMISSIONS, false)
        val willPrompt = !asked || missing.any {
            androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(this, it)
        }
        if (willPrompt) {
            AppLog.i(TAG, "permissions denied (${missing.size}) — asking the pilot")
            getPreferences(MODE_PRIVATE).edit().putBoolean(KEY_ASKED_PERMISSIONS, true).apply()
            androidx.core.app.ActivityCompat.requestPermissions(
                this, missing.toTypedArray(), REQUEST_CODE_PERMISSIONS)
            return
        }
        AppLog.i(TAG, "permissions permanently denied — opening the settings page")
        runCatching {
            startActivity(android.content.Intent(
                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.fromParts("package", packageName, null)))
        }.onFailure {
            AppLog.w(TAG, "could not open the settings page: ${it.message}")
            android.widget.Toast.makeText(this,
                "Open Settings, Apps, TAKPilot2, Permissions, and allow them.",
                android.widget.Toast.LENGTH_LONG).show()
        }
    }


    /**
     * The live charge of the aircraft battery and of the controller's own battery.
     *
     * The numbers come from the bridge's cached telemetry ([TakBridgeHolder.hud]), which runs on
     * this screen as well as on the flight screen — [TakAutoConnect] arms it telemetry-only when
     * there is no TAK enrollment, so the line works on a controller that has never been enrolled.
     *
     * ⚠ **ZERO AND NULL BOTH MEAN "NOT REPORTED YET", AND NEITHER MAY DRAW AS A LOW BATTERY.**
     * `batteryPct` starts at 0 before the first battery frame and `rcBatteryPct` starts null. A
     * 0 shown in red would send a pilot to change a full battery; unknown takes the amber that
     * the avoidance and storage lines above already use for the same reason.
     *
     * The colour follows the aircraft only. The controller battery is shown because a pilot
     * should see it, but it is the AIRCRAFT that decides whether this sortie can start, and two
     * things driving one colour would leave the pilot unable to tell which one is low.
     */
    private fun renderLiveBattery(haveProduct: Boolean, warnPct: Float?, critPct: Float?) {
        val hud = TakBridgeHolder.hud()
        val air = hud?.batteryPct?.takeIf { it > 0 }
        val rc = hud?.rcBatteryPct?.takeIf { it > 0 }
        if (!haveProduct && air == null && rc == null) {
            batteryLive.text = ""
            return
        }
        batteryLive.text = "BATTERY: AIRCRAFT ${air?.let { "$it%" } ?: "—"}" +
            "  ·  RC ${rc?.let { "$it%" } ?: "—"}"
        batteryLive.setTextColor(androidx.core.content.ContextCompat.getColor(this, when {
            air == null -> R.color.tp_state_unknown
            critPct != null && air <= critPct -> R.color.tp_state_danger
            warnPct != null && air <= warnPct -> R.color.tp_state_caution
            else -> R.color.tp_state_go
        }))
    }

    /**
     * Draws the media-server line, and refreshes the probe behind it when it is stale.
     *
     * ⚠ **GREEN MEANS THE SERVER IS UP, NOT THAT IT WILL TAKE THE STREAM.** Whether this
     * publisher is accepted depends on credentials, on whether the path may be published to,
     * and on the codec — all answered during a real publish. The LIVE pill on the flight screen
     * stays the authority on that. See [MediaServerProbe].
     */
    private fun renderMediaServer() {
        val p = getSharedPreferences("takpilot2_tak", MODE_PRIVATE)
        val host = p.getString("video_host", "") ?: ""
        val port = p.getInt("video_rtsp_port", VideoTransport.RTSP.defaultPort)

        val now = android.os.SystemClock.elapsedRealtime()
        if (!mediaProbeRunning && now - mediaProbeAtMs > MEDIA_PROBE_PERIOD_MS) {
            mediaProbeRunning = true
            Thread {
                val r = MediaServerProbe.probe(host, port)
                mediaProbe = r
                mediaProbeAtMs = android.os.SystemClock.elapsedRealtime()
                mediaProbeRunning = false
                runOnUiThread { if (!isFinishing) renderMediaServer() }
            }.apply { isDaemon = true; name = "media-probe" }.start()
        }

        // Amber until the first answer — unknown is its own state, and a server that has not
        // been asked yet must not be drawn as either up or down.
        val res = mediaProbe
        mediaStatus.text = when (res) {
            MediaServerProbe.Result.REACHABLE -> "MEDIA SERVER: Reachable"
            MediaServerProbe.Result.UNREACHABLE -> "MEDIA SERVER: Not reachable"
            MediaServerProbe.Result.NOT_CONFIGURED -> "MEDIA SERVER: Not set"
            null -> "MEDIA SERVER: —"
        }
        val color = androidx.core.content.ContextCompat.getColor(this, when (res) {
            MediaServerProbe.Result.REACHABLE -> R.color.tp_state_go
            MediaServerProbe.Result.UNREACHABLE -> R.color.tp_state_danger
            else -> R.color.tp_state_unknown
        })
        mediaStatus.setTextColor(color)
        (mediaDot.background as? android.graphics.drawable.GradientDrawable)?.setColor(color)
            ?: mediaDot.background?.setTint(color)
    }

}
