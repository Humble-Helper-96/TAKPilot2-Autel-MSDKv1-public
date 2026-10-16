package com.autel.sdksample.tak

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.taklite.client.tak.CotBuilder
import com.taklite.client.tak.TakCertEnroller
import com.taklite.client.tak.TakMissionClient
import com.taklite.client.tak.TakManager
import com.autel.sdksample.R
import com.taklite.util.AppLog
import java.io.File
import java.util.UUID

/**
 * Minimal TAK enroll + connect screen for TAKPilot2.
 *
 * Reuses taklite's TakCertEnroller (cert enrollment over HTTPS) and TakManager
 * (TLS CoT client). On success it starts a DroneTakBridge that streams the aircraft's
 * position to the server as an air track. This is the fast path to verify
 * aircraft -> CoT -> TAK end-to-end; the full QR enrollment wizard comes later.
 */
class TakConnectActivity : AppCompatActivity() {

    override fun onDestroy() {
        // A debounced write must not outlive the screen that scheduled it — leaving the pilot's
        // half-typed value to land on the aircraft after they navigated away.
        cancelPendingSettingPushes()
        // ⚠ THE TAK LISTENERS ARE NOT HERE ANY MORE. They went to TakServerActivity with the
        // TAK configuration, and that screen attaches and removes its
        // own. This screen holds none, so there is nothing here to detach.
        super.onDestroy()
    }

    /** The action-bar menu button returns to the home screen, same as the flight toolbar's. */
    override fun onSupportNavigateUp(): Boolean {
        AppLog.v(TAG, "menu tapped — back to home")
        finish()
        return true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tak_connect)
        AppLog.v(TAG, "onCreate")

        // Menu button on the left of the action bar, matching the flight screen's ic_menu — it
        // returns to the home screen (finish(), same as the flight toolbar's back button).
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_menu)
        }

        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)

        // The mode is NOT decided here. AppMode.settle runs the video slot migrations and the
        // first-run decision from TestApplication, before any screen, the boot dial or the
        // flight screen can read the mode. This screen only offers the change.

        setupDroneSettingsSection()
        setupDtedSection()
        setupUasfmSection()

        wireAvoidanceSection()
        wireControlRatesSection()

        // ⚠ THE TAK CONFIGURATION IS NOT ON THIS SCREEN — it
        // is in TakServerActivity, the same move VideoServersActivity made. What
        // stays is the summary a pilot reads before a flight. See paintTakSummary.
        findViewById<Button>(R.id.takConfigureServer).setOnClickListener {
            AppLog.v(TAG, "tap: Configure TAK Server")
            startActivity(Intent(this, TakServerActivity::class.java))
        }

        // The summary is painted here AND on every resume — see onResume for why.
        paintTakSummary()

        setupVideoControls(prefs)

        setupAppModeSection(prefs)

        // LAST, deliberately: every other setup call above populates or rebinds these fields,
        // and the lock has to be the final word on whether they are editable. Applying it
        // earlier would leave a later setText/rebind free to quietly re-enable a locked field.
        setupConfigLocks()
    }

    /**
     * Video server config. Mirrors the blueprint: NO Start/Stop here — the flight screen's LIVE
     * pill owns starting and stopping the stream; this screen only edits and SAVES the config.
     *
     * Persisting on every change matters more than it looks: the LIVE pill reads prefs, not this
     * screen's live state. The previous version only wrote prefs when its (now removed) "Start
     * Video" button was tapped, so edits made here and never "started" would have been silently
     * lost the moment the buttons went away.
     */
    /**
     * The video controls that change in the field: the quality, every flight, and which server
     * is active, per callout.
     *
     * ⚠ **Everything else is in [VideoServersActivity]** (design decision). Twenty
     * set-once controls in one column made the sub-sections easy to mix up, and the worst of
     * it was that every field belonged to whichever server the toggle above had selected —
     * nothing said which one once the pilot had scrolled into them. The servers screen shows
     * both at once and each field writes its own.
     *
     * The summary line is generated so it cannot go stale: it states where the video is pushed
     * and what the CoT will tell the team, which is what a pilot needs to read before a flight.
     */
    private fun setupVideoControls(prefs: android.content.SharedPreferences) {
        // ⚠ The two one-shot migrations that sat here run from AppMode.settle now, BEFORE the
        // mode is read or enforced. A migration must never sit behind something a mode can gate
        // — and the first-run rule reads the slots, so they must exist before it runs.
        val vServerGroup = findViewById<android.widget.RadioGroup>(R.id.videoServerGroup)
        val vServer1 = findViewById<android.widget.RadioButton>(R.id.videoServer1)
        val vServer2 = findViewById<android.widget.RadioButton>(R.id.videoServer2)
        val vProfileGroup = findViewById<android.widget.RadioGroup>(R.id.videoProfileGroup)
        val vSummary = findViewById<TextView>(R.id.videoSummary)

        // ⚠ BASIC HAS ONE VIDEO SERVER, so the choice between two is hidden — but ONLY the label
        // and the radios, never the row. The row also holds videoLockConfig. Hiding it would NOT
        // trip ConfigLock's early return (findViewById still finds a GONE view; that return fires
        // only on an id missing from the layout) — it would leave the lock APPLIED AND
        // UNREACHABLE: a locked controller whose video configuration nobody can unlock, in the
        // mode whose whole point is the free unlock.
        // ⚠ INVISIBLE, not GONE, for BOTH. The row is weighted, and with the
        // group GONE the lock box stretched into a full-width, unlabelled touch target; with the
        // label GONE the lock row rose under "High", the most-touched control here, where a low
        // tap unlocked with no prompt. INVISIBLE keeps the box where it always is, and an
        // INVISIBLE view takes no touches.
        if (!AppMode.secondVideoServerAllowed(prefs)) {
            findViewById<TextView>(R.id.videoActiveServerLabel).visibility =
                android.view.View.INVISIBLE
            vServerGroup.visibility = android.view.View.INVISIBLE
        }

        fun selectedProfile(): String = when (vProfileGroup.checkedRadioButtonId) {
            R.id.videoProfileLow -> "low"
            R.id.videoProfileHigh -> "high"
            else -> "standard"
        }

        // The quality belongs to the SLOT, so both servers keep their own — an external server
        // often wants a different one from an internal server. It is written straight through
        // because the flight screen's LIVE button reads the mirror, not this screen.
        vProfileGroup.setOnCheckedChangeListener { _, _ ->
            val slot = activeVideoSlot(prefs)
            prefs.edit()
                .putString(vKey(slot, "profile"), selectedProfile())
                .putString(KEY_V_PROFILE, selectedProfile())
                .apply()
            AppLog.v(TAG, "video profile -> ${selectedProfile()}")
        }

        /**
         * The active-server choice. Selecting one makes it live, which is acceptable here and
         * nowhere else: the flight screen stops the stream in onStop, so nothing can be
         * streaming while this screen is showing.
         */
        vServerGroup.setOnCheckedChangeListener { _, checkedId ->
            val slot = if (checkedId == R.id.videoServer2) 2 else 1
            // ⚠ Refused at the WRITE, not only hidden: a hidden radio can still be
            // checked by a view-state restore, and its listener then writes. See
            // rebuildWithoutSavedState.
            if (slot == 2 && !AppMode.secondVideoServerAllowed(prefs)) {
                AppLog.i(TAG, "Basic mode: active server 2 refused")
                return@setOnCheckedChangeListener
            }
            if (slot == activeVideoSlot(prefs)) return@setOnCheckedChangeListener
            prefs.edit().putInt(KEY_V_ACTIVE_SLOT, slot).apply()
            mirrorActiveSlot(prefs)
            when (prefs.getString(vKey(slot, "profile"), "standard")) {
                "low" -> vProfileGroup.check(R.id.videoProfileLow)
                "high" -> vProfileGroup.check(R.id.videoProfileHigh)
                else -> vProfileGroup.check(R.id.videoProfileStandard)
            }
            paintVideoSummary(prefs)
            // §3 and §6 both say who receives the video link, and the link follows the ACTIVE
            // slot — so a change here makes both stale on this same screen.
            paintTakSummary()
            paintAppModeSummary(prefs)
            AppLog.i(TAG, "active video server -> slot $slot (${slotName(prefs, slot)})")
        }

        findViewById<android.widget.Button>(R.id.videoConfigureServers).setOnClickListener {
            startActivity(android.content.Intent(this, VideoServersActivity::class.java))
        }

        vServerGroup.check(
            if (activeVideoSlot(prefs) == 2) R.id.videoServer2 else R.id.videoServer1)
        when (prefs.getString(vKey(activeVideoSlot(prefs), "profile"), "standard")) {
            "low" -> vProfileGroup.check(R.id.videoProfileLow)
            "high" -> vProfileGroup.check(R.id.videoProfileHigh)
            else -> vProfileGroup.check(R.id.videoProfileStandard)
        }
        paintVideoSummary(prefs)
        mirrorActiveSlot(prefs)
    }

    /**
     * ⚠ The servers screen can rename a server, change its transport or change where it
     * advertises, and all three show in section 2. Without this the pilot comes back to a
     * summary line describing the configuration as it was BEFORE they edited it — which is
     * worse than no summary, because it reads as authoritative.
     */
    override fun onResume() {
        super.onResume()
        val p = getSharedPreferences("takpilot2_tak", MODE_PRIVATE)
        paintVideoSummary(p)
        mirrorActiveSlot(p)
        // §6 is GENERATED from state that the TAK Server and Video Servers screens change, so it
        // is repainted here as well as in onCreate — painted once, it went stale about who
        // receives the video when the pilot came back from either screen.
        paintAppModeSummary(p)
        // ⚠ RETRY THE TAK CONNECTION HERE. Before the TAK configuration moved to its own
        // screen this screen's onCreate did it on the way past, through its
        // reconnect-from-saved branch, and nobody had to think about it. Pre-Flight is the
        // screen a pilot actually opens before a flight, so it is the right place for it —
        // see TakAutoConnect.retryIfDown for the whole fault.
        TakAutoConnect.retryIfDown(applicationContext)
        // Repainted on EVERY resume, not only on create: coming back from TakServerActivity is
        // the one moment the summary is most likely to be wrong, because the pilot just went
        // there to change exactly what it reports.
        paintTakSummary()
        setupSdCardSection()
        sdHandler.post(sdTick)
    }

    override fun onPause() {
        super.onPause()
        sdHandler.removeCallbacks(sdTick)
    }


    // ---- 0. Memory Card ---------------------------------------------------------------

    private val sdHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * Paints the card state and free space, and drives the Format button.
     *
     * READ-ONLY POLLING. Nothing here asks the aircraft for anything: [AutelProductHolder] already
     * receives the card state and the free space on the camera's own ~2 Hz push, thus this only
     * re-reads fields that are already being kept current. A tick is the simplest way to follow
     * them, and a read tick is not the timer the fly-controller rule forbids.
     *
     * ⚠ NO CAPACITY. The SDK reports free space for the SD card and no total — see the layout
     * comment for the search that established it.
     */
    private val sdTick = object : Runnable {
        override fun run() {
            renderSdCard()
            sdHandler.postDelayed(this, 1000)
        }
    }

    private fun setupSdCardSection() {
        findViewById<android.widget.Button>(R.id.sdCardFormatButton).setOnClickListener {
            confirmFormatSdCard()
        }
        renderSdCard()
    }

    /** Human text for the card state. Every value the SDK can report is named: an unnamed state
     *  would read as a fault when several of them are normal. */
    private fun sdStateText(s: com.autel.common.camera.base.SDCardState?): String = when (s) {
        null -> "Not known"
        com.autel.common.camera.base.SDCardState.CARD_READY -> "Ready"
        com.autel.common.camera.base.SDCardState.NO_CARD -> "No card"
        com.autel.common.camera.base.SDCardState.CARD_FULL -> "Full"
        com.autel.common.camera.base.SDCardState.CARD_ERROR -> "Error"
        com.autel.common.camera.base.SDCardState.CARD_PROTECT -> "Write protected"
        com.autel.common.camera.base.SDCardState.FORMATTING -> "Formatting"
        com.autel.common.camera.base.SDCardState.LOW_SPEED_CARD -> "Slow card"
        com.autel.common.camera.base.SDCardState.LOW_SPEED_CARD_STOP_RECORD -> "Slow card — recording stopped"
        com.autel.common.camera.base.SDCardState.UNKNOWN_FILE_SYSTEM_FAT -> "Unknown file system"
        else -> s.name
    }

    private fun freeText(mb: Long?): String = when {
        mb == null -> "Not known"
        mb >= 1024 -> String.format(java.util.Locale.US, "%.1f GB", mb / 1024.0)
        else -> "$mb MB"
    }

    /**
     * Why the Format button is not available, or null when it is.
     *
     * The reasons are separate strings because the pilot needs to know WHICH one applies — a
     * disabled button with no reason is the fault the exterior-lights button already taught us.
     */
    private fun formatBlockedReason(): String? {
        val state = AutelProductHolder.sdCardState
        return when {
            AutelProductHolder.xt706 == null -> "No aircraft"
            AutelTakBridge.airborne -> "Not while the aircraft is flying"
            AutelProductHolder.isRecording -> "Not while recording"
            state == null -> "Waiting for the camera"
            state == com.autel.common.camera.base.SDCardState.NO_CARD -> "No card"
            state == com.autel.common.camera.base.SDCardState.CARD_PROTECT -> "Card is write protected"
            state == com.autel.common.camera.base.SDCardState.FORMATTING -> "Formatting"
            else -> null
        }
    }

    private fun renderSdCard() {
        val stateView = findViewById<TextView>(R.id.sdCardState) ?: return
        val freeView = findViewById<TextView>(R.id.sdCardFree)
        val status = findViewById<TextView>(R.id.sdCardStatus)
        val button = findViewById<android.widget.Button>(R.id.sdCardFormatButton)

        stateView.text = sdStateText(AutelProductHolder.sdCardState)
        freeView.text = freeText(AutelProductHolder.sdFreeMb)

        val blocked = formatBlockedReason()
        button.isEnabled = blocked == null
        button.alpha = if (blocked == null) 1.0f else 0.45f
        // The status line carries the reason, and ALSO the fact that the camera is not writing
        // to this card — formatting an SD the camera is not recording to is legal but almost
        // never what the pilot meant.
        val notTarget = AutelProductHolder.storageTarget ==
            com.autel.common.camera.media.SaveLocation.FLASH_CARD
        val text = when {
            blocked != null -> blocked
            notTarget -> "The camera is recording to internal storage, not this card."
            else -> ""
        }
        status.text = text
        // GONE when it has nothing to say: an empty line still costs its height, and the height
        // is what sections 0 and 1 have none of. See the layout note.
        status.visibility = if (text.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
    }

    /**
     * ⚠ IRREVERSIBLE, AND ON A PUBLIC-SAFETY AIRFRAME THE FILES MAY BE EVIDENCE.
     *
     * Thus: the aircraft is named, the free space is quoted so the pilot can see whether the card
     * holds anything, and the positive button says what it does rather than "OK". The result
     * reports what the CAMERA did — the same rule the exterior-lights button follows, because
     * this camera has answered success for things it did not do (see startRecordVerified).
     */
    private fun confirmFormatSdCard() {
        val cam = AutelProductHolder.xt706 ?: return
        android.app.AlertDialog.Builder(this, R.style.TakDialogTheme_Destructive)
            .setTitle("Format the memory card?")
            .setMessage(
                "This erases everything on the SD card in the aircraft. " +
                "Photographs and video cannot be recovered.\n\n" +
                "Free space now: ${freeText(AutelProductHolder.sdFreeMb)}")
            .setPositiveButton("Format") { _, _ ->
                // ⚠ RE-CHECKED AT THE MOMENT OF THE ACTION. The button's enabled state comes from
                // the 1 s tick, and the dialog can stay open across a takeoff. The flag is the
                // bridge's own (see AutelTakBridge.airborne) — the one legal source here: a fresh
                // read would need a second fly-controller listener, and that slot holds one
                // client.
                if (AutelTakBridge.airborne) {
                    Toast.makeText(this, "Not formatted — the aircraft is flying.",
                        Toast.LENGTH_LONG).show()
                    AppLog.i(TAG, "format SD card refused: aircraft flying")
                } else {
                    doFormatSdCard(cam)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun doFormatSdCard(cam: com.autel.sdk.camera.AutelXT706) {
        val button = findViewById<android.widget.Button>(R.id.sdCardFormatButton)
        val status = findViewById<TextView>(R.id.sdCardStatus)
        button.isEnabled = false
        button.alpha = 0.45f
        showSdStatus(status, "Formatting…")
        AppLog.i(TAG, "format SD card requested")
        runCatching {
            cam.formatSDCard(object : com.autel.common.CallbackWithNoParam {
                override fun onSuccess() {
                    AppLog.i(TAG, "format SD card accepted by the camera")
                    // NOT "done". The camera has taken the request; the CARD STATE is what says
                    // it finished, and it arrives on the 2 Hz push that renderSdCard() reads.
                    runOnUiThread { showSdStatus(status, "The camera accepted the request.") }
                }
                override fun onFailure(error: com.autel.common.error.AutelError?) {
                    AppLog.w(TAG, "format SD card refused: ${error?.description}")
                    runOnUiThread { showSdStatus(status, "The aircraft did not format the card.") }
                }
            })
        }.onFailure {
            AppLog.w(TAG, "format SD card threw: ${it.message}")
            runOnUiThread { showSdStatus(status, "The aircraft did not format the card.") }
        }
    }

    /** Sets the status line and makes it visible. It is GONE by default — see the layout. */
    private fun showSdStatus(v: TextView, text: String) {
        v.text = text
        v.visibility = android.view.View.VISIBLE
    }

    private fun slotName(prefs: android.content.SharedPreferences, slot: Int): String =
        prefs.getString(vKey(slot, "name"), "")?.takeIf { it.isNotBlank() } ?: "Server $slot"

    /**
     * The server buttons and the one-line summary — everything in section 2 that the servers
     * screen can change.
     *
     * Derived text only, so it is safe to call on every resume: it attaches no listener and
     * touches no field the pilot is editing.
     */
    private fun paintVideoSummary(prefs: android.content.SharedPreferences) {
        val summary = findViewById<TextView>(R.id.videoSummary) ?: return
        findViewById<android.widget.RadioButton>(R.id.videoServer1)?.text = slotName(prefs, 1)
        findViewById<android.widget.RadioButton>(R.id.videoServer2)?.text = slotName(prefs, 2)

        val slot = activeVideoSlot(prefs)
        val host = prefs.getString(vKey(slot, "host"), "") ?: ""
        if (host.isEmpty()) {
            summary.text = "No video server set. Touch Configure Video Servers."
            return
        }
        val transport = VideoTransport.fromPref(prefs.getString(vKey(slot, "transport"), null))
        val port = prefs.getInt(
            vKey(slot, if (transport == VideoTransport.SRT) "srt_port" else "rtsp_port"),
            transport.defaultPort)
        val other = if (slot == 1) 2 else 1
        // ⚠ THE TEAM'S LEG IS NOT ALWAYS RTSP, so this line reads the
        // ADVERTISE transport of whichever slot the CoT names — not this slot's push
        // transport, and not the RTSP port it used to assume. A summary that named the wrong
        // port is the one failure a pilot cannot see from here.
        fun advPortOf(s: Int): String {
            val proto = VideoTransport.fromPref(prefs.getString(vKey(s, "adv_transport"), null))
            val port = if (proto == VideoTransport.SRT)
                prefs.getInt(vKey(s, "adv_srt_port"), VideoTransport.SRT.defaultPort)
            else prefs.getInt(vKey(s, "rtsp_port"), VideoTransport.RTSP.defaultPort)
            return "${proto.label} $port"
        }
        val team = when (prefs.getString(vKey(slot, "advertise"), "self")) {
            "off" -> "the team gets no video address"
            "other" -> "team plays from ${prefs.getString(vKey(other, "host"), "") ?: ""} " +
                    advPortOf(other)
            else -> "team plays from $host ${advPortOf(slot)}"
        }
        // The path on its own line: it is the name the server sees, and with the random token
        // on it cannot be read from the broadcast id alone.
        val path = StreamPath.compose(
            prefs.getString(vKey(slot, "streamid"), "") ?: "",
            prefs.getBoolean(vKey(slot, "random_path"), false))
        summary.text = "${slotName(prefs, slot)} · $host · ${transport.label} $port · $team" +
            "\nStream path $path"
    }

    /**
     * Copies the ACTIVE slot onto the plain `video_*` keys that [AutelVideoStreamer] reads.
     * A short name for [VideoServersActivity.mirrorActiveSlot], the one mirror.
     *
     * ⚠ The streamer knows nothing about slots, so an edit that is not mirrored is an edit the
     * stream never sees. Two places change WHICH slot is active — this screen's radios and
     * [AppModeTransition] — and both mirror.
     */
    private fun mirrorActiveSlot(prefs: android.content.SharedPreferences) =
        // ⚠ THIS USED TO BE A SECOND COPY. The two had drifted (this one wrote video_profile and
        // the other did not), and that kind of drift is a live bug. One mirror now — see
        // its doc.
        VideoServersActivity.mirrorActiveSlot(prefs)

    /** Preference key for one field of one video server slot. */
    private fun vKey(slot: Int, base: String) = "video_s${slot}_$base"

    /** The server the video goes to now: 1 or 2. */
    private fun activeVideoSlot(prefs: android.content.SharedPreferences): Int =
        if (prefs.getInt(KEY_V_ACTIVE_SLOT, 1) == 2) 2 else 1

    /**
     * Section 6 — ELEVATED (stored ENHANCED) or BASIC. The mode itself is decided on first run by
     * [AppMode.settle], never here; this screen only offers the change.
     *
     * ⚠ **THE PASSWORD IS ON THIS CONTROL AND IN BOTH DIRECTIONS** (design decision). In
     * Basic the configuration locks take no password at all, so this is the only gate left:
     * without it, a user could reach Basic, untick a lock for free, and return to Elevated with
     * every section editable. And Basic -> Elevated is NOT the safe direction — it re-narrows the
     * audience, and a team that can see the video loses it — so it asks too. A deliberate
     * departure from [ConfigLock]'s own asymmetry, which holds only because locking is safe.
     *
     * ⚠ **REFUSED IN FLIGHT AND WITH A STREAM LIVE — AND CHECKED TWICE.** The
     * transition closes a socket that carries the aircraft's position, camera point and markers,
     * not only the video link: a supervisor on the Elevated channel would lose the TRACK. The
     * first version checked only the airborne flag, although the transition's own doc said "or a
     * stream is live". Asked before the dialog, so nobody types a password for nothing, and again
     * at the moment of the change, so a takeoff while the dialog was open is still caught.
     */
    private fun setupAppModeSection(prefs: android.content.SharedPreferences) {
        paintAppModeSummary(prefs)
        findViewById<Button>(R.id.appModeButton).setOnClickListener {
            val target =
                if (AppMode.of(prefs) == AppMode.ENHANCED) AppMode.BASIC else AppMode.ENHANCED
            AppLog.v(TAG, "tap: Change Mode (-> $target)")
            if (refuseInFlightOrLive()) return@setOnClickListener
            promptForModeChange(prefs, target)
        }
    }

    /**
     * True, with the reason on screen, when the mode must not change now.
     *
     * ⚠ **THE FLYING STATE IS THE BRIDGE'S CACHED FLAG, DELIBERATELY** (design decision).
     * [AutelTakBridge.airborne] is written only by the bridge's fly-controller listener, the flight
     * screen does not stop the bridge, and the flag is never reset — so a link drop or a stopped
     * bridge leaves it at its LAST value, and a flying aircraft goes on reading "flying". A fresh
     * read is not available anyway: it would need a second fly-controller listener, and that slot
     * holds ONE client — the second would take the bridge's telemetry away.
     * ⚠ OPEN: with NOTHING heard since the process started (the application
     * restarted in the air during a link drop) the flag reads false and the change is allowed.
     * What the SDK reports with the aircraft off is not measured.
     */
    private fun refuseInFlightOrLive(): Boolean {
        if (VideoStreamerHolder.isActive) {
            Toast.makeText(this, "Stop LIVE before you change the mode.", Toast.LENGTH_LONG).show()
            AppLog.i(TAG, "mode change refused: a stream is live")
            return true
        }
        if (AutelTakBridge.airborne) {
            Toast.makeText(this, "The aircraft is flying. Land before you change the mode.",
                Toast.LENGTH_LONG).show()
            AppLog.i(TAG, "mode change refused: aircraft flying")
            return true
        }
        return false
    }

    /**
     * Rebuilds this screen from the preferences alone, after a mode change.
     *
     * ⚠ **NOT `recreate()`.** `recreate()` saves and RESTORES view state, as a
     * configuration change does. The restore runs after onCreate, so the radio listeners are
     * already attached, and each radio replays the OLD screen's checked state through setChecked
     * — a hidden parent does not stop it. On the way to BASIC with Server 2 active, the hidden
     * active-server radio replayed "Server 2" and its listener wrote video_active_slot=2 straight
     * back over the transition's forced 1: LIVE then published to the server BASIC hides. The
     * return was undone the same way. Every control here is rebuilt from the preferences anyway,
     * so restored view state can only ever be stale: a new instance with no saved state is the
     * whole fix. (The active-server listener refuses Server 2 in BASIC as well — defence in
     * depth.)
     *
     * A new onCreate also re-runs setupConfigLocks LAST, which is the ordering this screen
     * depends on.
     */
    private fun rebuildWithoutSavedState() {
        val fresh = Intent(intent)
        finish()
        overridePendingTransition(0, 0)
        startActivity(fresh)
        overridePendingTransition(0, 0)
    }

    /** The disclosure, then the password, then the one ordered transition. */
    private fun promptForModeChange(
        prefs: android.content.SharedPreferences,
        target: AppMode,
    ) {
        // Read BEFORE the change: the disclosure describes what is about to happen.
        val facts = AppModeTransition.facts(
            prefs, TakMissionManager.hasElevatedEnrollment(this), target,
            emergencyActive = TakManager.getInstance().isEmergencyBroadcastActive())
        val pw = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
            hint = "Password"
            textSize = 15f
            setTextColor(androidx.core.content.ContextCompat.getColor(
                this@TakConnectActivity, R.color.tp_text_primary))
            setHintTextColor(androidx.core.content.ContextCompat.getColor(
                this@TakConnectActivity, R.color.tp_text_hint))
            setBackgroundResource(R.drawable.bg_dialog_field)
            val pad = (12 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        val wrap = android.widget.FrameLayout(this).apply {
            val padH = (16 * resources.displayMetrics.density).toInt()
            val padV = (8 * resources.displayMetrics.density).toInt()
            setPadding(padH, padV, padH, padV)
            addView(pw)
        }
        android.app.AlertDialog.Builder(this, R.style.TakDialogTheme_Destructive)
            // ⚠ From AppModeTransition.dialogTitle, which a test holds. The first
            // literal here escaped its own template and showed the pilot raw Kotlin.
            .setTitle(AppModeTransition.dialogTitle(target))
            .setMessage(AppModeTransition.describeAudienceChange(target, facts))
            .setView(wrap)
            .setPositiveButton("Change Mode") { _, _ ->
                // The entered text is never logged, right or wrong — the same rule as every
                // other credential in this application.
                if (pw.text.toString() != UNLOCK_PASSWORD) {
                    Toast.makeText(this, "Wrong password", Toast.LENGTH_SHORT).show()
                    AppLog.i(TAG, "mode change refused (wrong password)")
                    return@setPositiveButton
                }
                // The second check, at the moment of the change.
                if (refuseInFlightOrLive()) return@setPositiveButton
                val changed = AppModeTransition.apply(prefs, target) {
                    TakAutoConnect.dialVideoChannelIfAllowed(applicationContext)
                }
                if (changed) rebuildWithoutSavedState()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * What section 6 says, GENERATED from the state — never typed. The first
     * version's "the live-video link goes only to the Elevated account's channel" was a constant,
     * and false on an Elevated controller with no Elevated account connected.
     */
    private fun paintAppModeSummary(prefs: android.content.SharedPreferences) {
        val mode = AppMode.of(prefs)
        val f = AppModeTransition.facts(prefs, TakMissionManager.hasElevatedEnrollment(this), mode)
        // ⚠ Every claim about the video link is conditioned on the ADVERTISEMENT: with it off,
        // nobody gets a link, whatever the accounts — including the state BASIC itself creates
        // when the CoT named Server 2.
        val advertised = f.advertNow != null
        findViewById<TextView>(R.id.appModeSummary).text = when (mode) {
            AppMode.ENHANCED ->
                "Elevated. Two TAK accounts, two video servers, Emergency Broadcast. " + when {
                    !advertised -> "No video address is advertised, so no channel gets a video link."
                    f.elevatedInUse -> "The live-video link goes only to the Elevated account's channel."
                    // Not "no Elevated account": it is set up, only switched off.
                    f.elevatedEnrolled -> "The Elevated account is switched off, so the live-video " +
                        "link goes to every active channel."
                    else -> "No Elevated account is set up, so the live-video link goes to every " +
                        "active channel."
                }
            AppMode.BASIC ->
                "Basic. One TAK account and one video server. Every active channel receives " +
                    "this aircraft" +
                    (if (advertised) " and its video link." else ". No video address is advertised.") +
                    " The locks need no password."
        }
    }

    /**
     * The three lines Pre-Flight keeps of the TAK configuration.
     *
     * ⚠ **GENERATED, NEVER TYPED.** Each line is built from the live state every time this
     * screen appears, so none of them can say something the application stopped doing — which
     * is the failure mode a hand-written summary has, and this app has paid for it twice: the
     * video summary was generated for exactly this reason, and the Field Guide
     * carried a line that outlived what it described.
     *
     * What a pilot needs before a flight is WHO this aircraft is signed in as and WHO can see
     * it. Everything that changes those answers is one tap away in [TakServerActivity].
     */
    private fun paintTakSummary() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val tm = TakManager.getInstance()
        val statusLine = findViewById<TextView>(R.id.takSummaryStatus) ?: return
        val channelLine = findViewById<TextView>(R.id.takSummaryChannels)
        val elevatedLine = findViewById<TextView>(R.id.takSummaryElevated)

        val user = prefs.getString(KEY_USERNAME, "") ?: ""
        val callsign = prefs.getString(KEY_CALLSIGN, "") ?: ""
        when {
            tm.isConnected -> {
                statusLine.text = "Connected as \"$user\"" +
                    if (callsign.isNotEmpty()) " — the aircraft is \"$callsign\"." else "."
                statusLine.setTextColor(androidx.core.content.ContextCompat.getColor(
                    applicationContext, R.color.tp_state_go))
            }
            user.isEmpty() -> {
                statusLine.text = "No TAK server is set up. Touch Configure TAK Server."
                statusLine.setTextColor(androidx.core.content.ContextCompat.getColor(
                    applicationContext, R.color.tp_text_secondary))
            }
            else -> {
                // ⚠ "Not connected" and "not set up" are different answers and must look
                // different. An enrolled controller that cannot reach its server is a fault to
                // go and fix; an unconfigured one is a job not started.
                statusLine.text = "Not connected. Signed in as \"$user\"."
                statusLine.setTextColor(androidx.core.content.ContextCompat.getColor(
                    applicationContext, R.color.tp_state_unknown))
            }
        }
        // The channels come from the SERVER, so there is nothing to show until it answers.
        // Until then the line says that rather than showing an empty list, which would read as
        // "this aircraft reaches nobody".
        channelLine.text = if (tm.isConnected) "Reading the channels…" else ""
        if (tm.isConnected) {
            TakMissionManager.listChannels { chans ->
                val active = chans.filter { it.active }
                channelLine.text = when {
                    chans.isEmpty() -> "This server has no channels."
                    active.isEmpty() -> "No channel is active — nobody receives this aircraft."
                    else -> "Active: " +
                        active.joinToString(", ") { TakMissionManager.channelLabel(it) }
                }
            }
        }
        val enrolled = TakMissionManager.hasElevatedEnrollment(this) &&
            !prefs.getBoolean("chb_logged_out", false)
        // ⚠ EVERY BRANCH IS CONDITIONED ON THE ADVERTISEMENT, as §6 is. With it off no channel
        // gets a link, whatever the accounts, and the two generated lines on this one screen must
        // not disagree. From the same Facts as §6.
        val advertised = AppModeTransition.facts(
            prefs, TakMissionManager.hasElevatedEnrollment(this), AppMode.of(prefs)).advertNow != null
        val noAdvert = "No video address is advertised."
        if (!AppMode.elevatedAccountAllowed(prefs)) {
            // ⚠ BASIC: ONE ACCOUNT, AND THE LINE SAYS WHAT THAT MEANS. Tested FIRST and never
            // keyed off the saved certificates: those survive the switch to Basic on purpose (the
            // mode is a mute, not a removal), so a summary that read them would promise a split
            // that AppModeTransition has already torn down.
            elevatedLine.text = "One TAK account — every active channel receives this aircraft" +
                if (advertised) " and its video." else ". $noAdvert"
        } else if (enrolled && !prefs.getBoolean(KEY_CHB_ENABLED, false)) {
            // ⚠ An enrolled account that is merely switched off is NOT "no Elevated account". A
            // pilot reading that would go looking to enroll one.
            elevatedLine.text = if (advertised) "Elevated account switched off — the video link " +
                "goes to every active channel." else "Elevated account switched off. $noAdvert"
        } else if (enrolled) {
            elevatedLine.text = "Elevated account: reading the channels…"
            TakMissionManager.listElevatedChannels(this) { chans ->
                val active = chans?.filter { it.active }.orEmpty()
                elevatedLine.text = when {
                    active.isEmpty() -> "Elevated account: no active channel — the video link " +
                        "reaches nobody."
                    advertised -> "Video link on: " +
                        active.joinToString(", ") { TakMissionManager.channelLabel(it) }
                    else -> "Elevated account on: " +
                        active.joinToString(", ") { TakMissionManager.channelLabel(it) } +
                        ". $noAdvert"
                }
            }
        } else {
            // No second account. Say what that MEANS rather than leaving a blank: with no
            // split, the video link rides the Standard connection and everybody in its
            // channels can play it.
            elevatedLine.text = if (advertised) "No Elevated account — the video link goes to " +
                "every active channel." else "No Elevated account. $noAdvert"
        }
    }

    /**
     * Locks the active-server toggle without hiding which server is active.
     *
     * LOCKED IS NOT DISABLED — the same rule the channel rows follow. The buttons keep full
     * contrast and their accent tint, and stop taking touches. A pilot must always be able to
     * SEE where the video is going; the lock exists to stop an accidental swap, not to hide the
     * destination.
     */
    private fun lockVideoServerToggle(locked: Boolean) {
        for (id in listOf(R.id.videoServer1, R.id.videoServer2)) {
            findViewById<android.widget.RadioButton>(id)?.apply {
                isClickable = !locked
                isFocusable = !locked
            }
        }
    }

    // ---- 1. Aircraft Settings ----

    /**
     * Flight-safety limits, persisted here and pushed to the aircraft by
     * [FlightLimitsController.applyAtConnect] on the next AIRCRAFT connect. Blank leaves the
     * aircraft's own current setting alone.
     *
     * These used to be pushed from [AutelTakBridge], latched on the TAK session — which meant no
     * TAK server, no limits, and no re-apply after an aircraft reconnect. Flight controls are not
     * the TAK bridge's business; they now run from [AutelProductHolder] with the other
     * at-connect settings.
     *
     * ⚠ A value the aircraft rejects is NOT applied and it keeps its previous setting, so the
     * range check below is not cosmetic — see the `limitRangeStatus` wiring.
     */
    // ---- Applying settings to the aircraft ---------------------------------------------------
    //
    // TWO PATHS, BOTH DELIBERATE, NEITHER TIED TO TYPING:
    //   1. On AIRCRAFT CONNECT, automatically (FlightLimitsController.applyAtConnect, driven from
    //      AutelProductHolder).
    //   2. On demand, via "Apply to Aircraft" — resends everything and then READS BACK what the
    //      aircraft actually holds.
    //
    // Editing a field only saves it locally. An earlier revision pushed on a 2s debounce after
    // typing stopped; that was wrong and it was removed. Typing
    // is not intent: a pause mid-edit would push a half-considered value, and a mid-flight change
    // to RTH or max altitude should be an explicit act. It also deletes a whole class of hazard —
    // these fields fire per keystroke, so "200" would have been three writes (2ft, 20ft, 200ft)
    // at the same fly-controller channel whose saturation put an aircraft into a wall.
    //
    // The read-back is the point. Testing proved every layer can lie independently: a write can
    // report OK, the getter can report a value the aircraft does not fly, and an out-of-range
    // value is silently kept at the old setting. "Sent" is not "set".
    private val applyHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingVerify: Runnable? = null
    private var pendingTick: Runnable? = null

    private fun cancelPendingSettingPushes() {
        pendingVerify?.let { applyHandler.removeCallbacks(it) }
        pendingVerify = null
        pendingTick?.let { applyHandler.removeCallbacks(it) }
        pendingTick = null
    }

    /**
     * Resends every aircraft-bound Pre-Flight setting, then reports what the aircraft holds.
     *
     * The push-then-wait-11s-then-verify shape (see the class doc above) has no natural
     * "in progress" signal of its own — the writes are fire-and-forget, so there is nothing to
     * await except the clock. Without visible progress a pilot watching a static line of text
     * for 11 seconds has no way to tell that from a frozen screen, and the natural response is
     * to tap the button again or navigate away mid-push. The button is disabled and a determinate
     * progress bar fills across exactly [VERIFY_DELAY_MS] — a known, bounded wait — with a
     * live countdown in the status text, so the pilot can see it counting down to the verify
     * rather than just running to it.
     */
    private fun applyAllToAircraft(button: Button, progress: android.widget.ProgressBar, status: TextView) {
        if (AutelProductHolder.evo2 == null) {
            status.text = "The aircraft is not connected. The settings are saved. " +
                "Connect the aircraft, then press the button again."
            status.setTextColor(androidx.core.content.ContextCompat.getColor(
                this, R.color.tp_state_caution))
            return
        }
        cancelPendingSettingPushes()
        button.isEnabled = false
        progress.visibility = android.view.View.VISIBLE
        progress.progress = 0
        status.setTextColor(0xFF909090.toInt())

        // One collector for THIS press. The setters below record their refusals into it and the
        // verify reads it 11s later. The writes are fire-and-forget, thus this is the only way a
        // refused write — and two of them have no getter at all — can reach the pilot.
        val cycle = FlightLimitsController.beginApply()

        FlightLimitsController.pushLimitsNow(this)
        FlightLimitsController.pushBatteryNow(this)

        // Verify AFTER the writes have had time to land. The wait used to be 11s because the
        // signal-loss write took a 10s timeout to fail; that write is gone (see
        // FlightLimitsController's note), and the remaining writes acknowledge in well under
        // a second, so the pilot waits about two.
        val startNs = android.os.SystemClock.elapsedRealtime()
        val tick = object : Runnable {
            override fun run() {
                val elapsedMs = android.os.SystemClock.elapsedRealtime() - startNs
                val pct = (elapsedMs * 100 / VERIFY_DELAY_MS).toInt().coerceIn(0, 100)
                progress.progress = pct
                // Ceiling division so the countdown starts at exactly VERIFY_DELAY_MS/1000
                // seconds and never overshoots by rounding up an extra whole second.
                val remainingMs = (VERIFY_DELAY_MS - elapsedMs).coerceAtLeast(0)
                val remainingSec = (remainingMs + 999) / 1000L
                status.text = "Sending the settings to the aircraft… confirming in ${remainingSec}s"
                if (elapsedMs < VERIFY_DELAY_MS) {
                    pendingTick = this
                    applyHandler.postDelayed(this, APPLY_TICK_MS)
                } else {
                    pendingTick = null
                }
            }
        }
        pendingTick = tick
        applyHandler.post(tick)

        val verify = Runnable {
            pendingVerify = null
            // STOP THE COUNTDOWN FIRST. Both runnables come due at VERIFY_DELAY_MS, and the
            // countdown's last frame is posted from the tick before it, so it lands a few
            // milliseconds AFTER this one and used to paint "confirming in 0s" straight over
            // the finished report. The screen then sat on a frozen countdown while the button
            // was live again and the text carried the report's colour — the apply had
            // succeeded and looked hung.
            pendingTick?.let { applyHandler.removeCallbacks(it) }
            pendingTick = null
            // Says what is happening during the read-back, which is not instant: the getters
            // answer in their own time and have their own 12s watchdog.
            status.text = "Confirming the settings with the aircraft…"
            FlightLimitsController.readBack(this, cycle) { report ->
                runOnUiThread {
                    button.isEnabled = true
                    progress.visibility = android.view.View.GONE
                    status.text = report.text
                    // Three states, three colours. Amber is NOT a softer red: it says the
                    // aircraft did not answer, which is a different fact from a refusal and must
                    // not be shown as one.
                    status.setTextColor(androidx.core.content.ContextCompat.getColor(
                        this, when (report.state) {
                            FlightLimitsController.ReportState.CONFIRMED -> R.color.tp_state_go
                            FlightLimitsController.ReportState.UNKNOWN -> R.color.tp_state_unknown
                            FlightLimitsController.ReportState.PROBLEM -> R.color.tp_state_danger
                        }))
                }
            }
        }
        pendingVerify = verify
        applyHandler.postDelayed(verify, VERIFY_DELAY_MS)
    }

    private fun setupDroneSettingsSection() {
        val maxAlt = findViewById<EditText>(R.id.limitMaxAltitude)
        val maxRadius = findViewById<EditText>(R.id.limitMaxRadius)
        val rthAlt = findViewById<EditText>(R.id.limitRthAltitude)

        maxAlt.setText(FlightLimitsController.savedMaxAltitudeFt(this))
        maxRadius.setText(FlightLimitsController.savedMaxRadiusFt(this))
        rthAlt.setText(FlightLimitsController.savedRthAltitudeFt(this))

        // Auto-save on edit, no Save button — matching the blueprint. These are local prefs
        // only; FlightLimitsController pushes them to the aircraft on its next connect, which
        // is what the section's subtitle tells the pilot. (An earlier version of this screen
        // had a Save button that ALSO pushed immediately when connected; both were divergences
        // from the blueprint and are gone.)
        val save = {
            FlightLimitsController.save(
                this, maxAlt.text.toString(), maxRadius.text.toString(), rthAlt.text.toString(),
            )
        }
        // Live range check against the aircraft's own accepted limits. Without this the aircraft
        // rejects an out-of-range value, keeps its old one, and Pre-Flight goes on displaying the
        // number the pilot typed — which once meant 50 ft on screen and a 151 ft RTH in
        // the air. Only possible when an aircraft is connected; says so plainly when it is not.
        val rangeStatus = findViewById<android.widget.TextView>(R.id.limitRangeStatus)
        val refreshRanges = {
            val rth = FlightLimitsController.returnHeightRange()
            val alt = FlightLimitsController.maxHeightRange()
            val rad = FlightLimitsController.maxRangeRange()
            if (rth == null && alt == null && rad == null) {
                rangeStatus.setText("Connect the aircraft to see the limits it accepts.")
                rangeStatus.setTextColor(0xFF909090.toInt())
            } else {
                val problems = mutableListOf<String>()
                fun check(label: String, text: String, r: FlightLimitsController.RangeM?) {
                    r ?: return
                    val ft = text.trim().toIntOrNull() ?: return
                    val m = Math.round(ft / 3.28084).toInt()
                    if (!r.containsM(m)) {
                        problems += "$label $ft ft. It accepts ${r.fromFt} to ${r.toFt} ft"
                    }
                }
                check("Max altitude", maxAlt.text.toString(), alt)
                check("Max distance", maxRadius.text.toString(), rad)
                check("RTH altitude", rthAlt.text.toString(), rth)
                if (problems.isEmpty()) {
                    rangeStatus.setText("The aircraft accepts: " +
                        (alt?.let { "max altitude ${it.fromFt} to ${it.toFt} ft, " } ?: "") +
                        (rad?.let { "max distance ${it.fromFt} to ${it.toFt} ft, " } ?: "") +
                        (rth?.let { "RTH altitude ${it.fromFt} to ${it.toFt} ft" } ?: ""))
                    rangeStatus.setTextColor(0xFF909090.toInt())
                } else {
                    rangeStatus.setText("⚠ The aircraft will refuse " + problems.joinToString("; ") +
                        ". Correct the value. If you do not, the aircraft keeps the setting it has now.")
                    rangeStatus.setTextColor(0xFFFF6B6B.toInt())
                }
            }
        }
        refreshRanges()

        val watcher = object : android.text.TextWatcher {
            // Saves locally and re-checks the range. Does NOT push — typing is not intent.
            // "Apply to Aircraft" is what sends it. See applyAllToAircraft.
            override fun afterTextChanged(s: android.text.Editable?) {
                save()
                refreshRanges()
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        }
        listOf(maxAlt, maxRadius, rthAlt).forEach { it.addTextChangedListener(watcher) }

        setupBatteryLevels()

        val applyStatus = findViewById<TextView>(R.id.limitApplyStatus)
        val applyProgress = findViewById<android.widget.ProgressBar>(R.id.limitApplyProgress)
        val applyButton = findViewById<Button>(R.id.limitApplyButton)
        applyButton.setOnClickListener {
            applyAllToAircraft(applyButton, applyProgress, applyStatus)
        }


    }

    /**
     * Battery levels — the two percentages at which the AIRCRAFT acts on its own.
     *
     * Saved locally on edit and pushed by "Apply to Aircraft", exactly like the numeric limits
     * above. Until now these two had prefs, a push path and defaults of 15/10, but no UI at all:
     * the only way to change them was Autel Explorer, and a value the app pushes on every
     * connect but never shows is a setting the pilot cannot reason about.
     *
     * The validation is the point of this function. [FlightLimitsController.applyBatteryThresholds]
     * already REFUSES to push low <= critical — the aircraft would begin its return and force a
     * landing in the same moment — but refusing at Apply time, in a log line, is too late and
     * invisible. This says so while the pilot is typing, and says what will happen if they leave
     * it: the aircraft keeps what it already has.
     */
    private fun setupBatteryLevels() {
        val low = findViewById<EditText>(R.id.limitLowBattery)
        val crit = findViewById<EditText>(R.id.limitCriticalBattery)
        val status = findViewById<TextView>(R.id.limitBatteryStatus)

        low.setText(FlightLimitsController.savedLowBatteryPct(this))
        crit.setText(FlightLimitsController.savedCriticalBatteryPct(this))

        val refresh = {
            val l = low.text.toString().trim().toIntOrNull()
            val c = crit.text.toString().trim().toIntOrNull()
            when {
                l == null || c == null -> {
                    status.setText("Empty keeps the aircraft's current level.")
                    status.setTextColor(0xFF909090.toInt())
                }
                l <= c -> {
                    status.setText("⚠ Battery Warning ($l%) must be above Battery Critical " +
                        "($c%) — the aircraft would act on both at once. Not sent until corrected.")
                    status.setTextColor(0xFFFF6B6B.toInt())
                }
                // A valid pair says nothing: the two fields already show it. This line exists
                // only to report a problem.
                else -> status.setText("")
            }
        }
        refresh()

        val watcher = object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                FlightLimitsController.saveBatteryLevels(
                    this@TakConnectActivity, low.text.toString(), crit.text.toString(),
                )
                refresh()
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        }
        listOf(low, crit).forEach { it.addTextChangedListener(watcher) }
    }


    // ---- Configuration locks ----

    /**
     * What each lock covers.
     *
     * The TAK list includes **Log Out**, which is not a field: it clears the saved enrollment,
     * so it is destructive and belongs behind the lock. Enroll & Connect
     * is deliberately NOT here — reconnecting is safe, and needing to reconnect is exactly when
     * a pilot must not be fighting a lock.
     */
    /**
     * ⚠ **THE TAK LOCK IS NOT ON THIS SCREEN ANY MORE.** It went with the fields it guards, to
     * [TakServerActivity], and its field list is there. Nothing of the
     * TAK configuration stayed here, so there is nothing left for a lock to protect. The VIDEO
     * lock DID stay, because the active-server toggle it also guards stayed.
     */
    /** Codec and transport are part of WHAT the stream is — the wrong codec breaks playback
     *  outright (CloudTAK cannot play H.265), so they lock with the server fields. The quality profile stays live; see [setupConfigLocks]. The two codec
     *  RadioButtons are listed individually because disabling a RadioGroup does not disable its
     *  children. */
    /**
     * ⚠ EMPTY ON PURPOSE. The video configuration fields are in [VideoServersActivity],
     * so there is nothing on THIS screen for the generic lock to
     * disable. The lock still does its two jobs: `afterChange` stops the active-server toggle
     * taking touches, and the servers screen reads `video_config_locked` when it opens.
     */
    private val videoLockedFields = emptyList<Int>()

    private val batteryLockedFields = listOf(
        R.id.limitLowBattery, R.id.limitCriticalBattery,
    )

    /**
     * "Lock configuration" for the TAK server, video server and battery-level sections: a
     * working setup should not be one stray tap away from being edited on a tailgate.
     *
     * **Locks the FIELDS only.** Enroll & Connect, Log Out, the video QUALITY choice and
     * Apply to Aircraft stay live: needing to reconnect, or to drop to Low on a marginal
     * link, is exactly when a pilot must not be fighting a lock. The lock guards what the
     * configuration IS, not what you do with it. Codec and transport are INSIDE the
     * video lock: they are part of what the stream is — a codec the
     * server can't play is a dead stream, not a tuning choice.
     *
     * Unlocking asks for a password; locking does not. The asymmetry is
     * deliberate — locking is the safe direction and gating it would just train people to
     * dismiss dialogs. The password is a shared, fixed one — see [UNLOCK_PASSWORD] for what it
     * is and is not protecting against.
     */
    private fun setupConfigLocks() {
        // ⚠ The TAK lock is NOT here — it is with the fields it guards, in
        // TakServerActivity.
        ConfigLock.install(
            this, R.id.videoLockConfig, KEY_VIDEO_LOCKED, videoLockedFields,
            "Unlock video server settings?",
            "These fields are locked so a working stream configuration is not changed by " +
                "accident. Editing them can stop your team seeing the video.",
            // The toggle is a radio button pair built in the layout, but it must not be dimmed
            // — see the note on videoLockedFields.
            afterChange = { lockVideoServerToggle(it) },
        )
        ConfigLock.install(
            this, R.id.limitBatteryLock, KEY_BATTERY_LOCKED, batteryLockedFields,
            "Unlock battery levels?",
            "These are the levels at which the aircraft returns and lands on its own. " +
                "A wrong value can force a landing away from the pilot.",
        )
    }

    /**
     * ⚠ **ONE IMPLEMENTATION, IN [ConfigLock].** The two private methods that
     * used to live here — `setupOneLock` and `applyLock` — moved there when [TakServerActivity]
     * took the TAK configuration and needed the same lock. Copying them was the obvious move
     * and the wrong one: both of their rules — locked is not hidden, and a control whose state
     * IS its tick must not be dimmed — came from real faults, and a copy is how one screen
     * keeps a safety rule while the other quietly stops.
     */


    // ---- 4. Elevation Data (DTED) ----

    /** DTED region management — import a region .zip via the system document picker (any file;
     *  DTED extensions are not a registered MIME type so we do not filter), list imported regions
     *  (one row each — never individual tiles, see [DtedStore]), allow deleting a whole region.
     *
     *  ACTION_OPEN_DOCUMENT deliberately: it's the Storage Access Framework, which needs no
     *  storage permission and is the supported path on the Smart Controller V3's Android 11
     *  (scoped storage) — a raw filesystem picker would not be. */
    private fun setupDtedSection() {
        findViewById<Button>(R.id.dtedUploadButton).setOnClickListener {
            AppLog.v(TAG, "tap: Import Region")
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            startActivityForResult(intent, REQUEST_CODE_DTED_PICK)
        }
        findViewById<Button>(R.id.dtedCleanButton).setOnClickListener {
            AppLog.v(TAG, "tap: Clean Unused Tiles")
            if (DtedStore.isImportRunning) {
                Toast.makeText(this, "Wait for the import to finish.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val removed = DtedStore.cleanUnreferencedTiles(this)
            Toast.makeText(this, "Removed $removed unreferenced tile file(s)", Toast.LENGTH_SHORT).show()
        }
        renderDtedRegions()
    }

    /**
     * Import, Clean and Delete follow [DtedStore.isImportRunning], not a button's own state:
     * this screen can be rebuilt during an import (re-opened, or the
     * mode-change rebuild), and the new instance must still hold them down. While an import
     * runs, this re-checks every 2 s and repaints the list when it ends.
     */
    private fun applyDtedBusy() {
        val busy = DtedStore.isImportRunning
        findViewById<Button>(R.id.dtedUploadButton)?.isEnabled = !busy
        findViewById<Button>(R.id.dtedCleanButton)?.isEnabled = !busy
        if (busy) {
            findViewById<TextView>(R.id.dtedStatus)?.let {
                if (!it.text.startsWith("Importing")) it.text = "An import is running…"
            }
            dtedBusyHandler.removeCallbacks(dtedBusyPoll)
            dtedBusyHandler.postDelayed(dtedBusyPoll, 2000)
        }
    }
    private val dtedBusyHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val dtedBusyPoll = Runnable {
        if (isFinishing || isDestroyed) return@Runnable
        if (DtedStore.isImportRunning) applyDtedBusy() else renderDtedRegions()
    }

    private val dtedDateFormat = java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.US)

    /** One compact row per imported region — name/date/file-count/size plus a delete button,
     *  never a per-tile listing (a full region is hundreds of tiles). */
    private fun renderDtedRegions() {
        val container = findViewById<LinearLayout>(R.id.dtedFileList)
        val dtedStatus = findViewById<TextView>(R.id.dtedStatus)
        container.orientation = LinearLayout.VERTICAL
        container.removeAllViews()
        val regions = DtedStore.listRegions(this)
        dtedStatus.text = if (regions.isEmpty()) "No terrain areas are imported."
            else "${regions.size} terrain area(s) imported."
        val busy = DtedStore.isImportRunning
        for (region in regions) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setBackgroundColor(androidx.core.content.ContextCompat.getColor(applicationContext, R.color.tp_surface_dialog))
                setPadding(12, 10, 12, 10)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 6 }
            }
            val info = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            info.addView(TextView(this).apply {
                text = region.name
                setTextColor(Color.WHITE)
                textSize = 15f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            info.addView(TextView(this).apply {
                val mb = region.totalBytes / 1024.0 / 1024.0
                val sizeStr = if (mb >= 1024) "%.1f GB".format(mb / 1024.0) else "%.0f MB".format(mb)
                text = "Imported ${dtedDateFormat.format(java.util.Date(region.importedAtMs))} · " +
                    "${region.fileCount} file(s) · $sizeStr"
                setTextColor(androidx.core.content.ContextCompat.getColor(applicationContext, R.color.tp_text_tertiary))
                textSize = 12f
            })
            row.addView(info)
            row.addView(TextView(this).apply {
                text = "Delete"
                setTextColor(androidx.core.content.ContextCompat.getColor(applicationContext, R.color.tp_btn_danger_dialog))
                textSize = 13f
                setPadding(20, 8, 4, 8)
                isEnabled = !busy
                alpha = if (busy) 0.45f else 1f
                // Asked first: one tap removed a whole region, and re-importing
                // one takes minutes.
                setOnClickListener {
                    AppLog.v(TAG, "tap: delete DTED region ${region.name} (#${region.id})")
                    val mb = region.totalBytes / 1024.0 / 1024.0
                    val size = if (mb >= 1024) "%.1f GB".format(mb / 1024.0) else "%.0f MB".format(mb)
                    android.app.AlertDialog.Builder(this@TakConnectActivity, R.style.TakDialogTheme_Destructive)
                        .setTitle("Delete ${region.name}?")
                        .setMessage("${region.fileCount} file(s), $size. To get it back you must import it again, which takes several minutes.")
                        .setPositiveButton("Delete") { _, _ ->
                            if (!DtedStore.deleteRegion(this@TakConnectActivity, region)) {
                                Toast.makeText(this@TakConnectActivity,
                                    "Wait for the import to finish.", Toast.LENGTH_SHORT).show()
                            }
                            renderDtedRegions()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            })
            container.addView(row)
        }
        applyDtedBusy()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CODE_DTED_PICK || resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        val name = queryDisplayName(uri) ?: "Region-${System.currentTimeMillis()}"
        val dtedStatus = findViewById<TextView>(R.id.dtedStatus)
        val importButton = findViewById<Button>(R.id.dtedUploadButton)
        // ⚠ OFF THE MAIN THREAD. This once ran synchronously here, and a 1.1 GB
        // region (39 IFSAR tiles, 3.16 GB extracted) blacked the window out for minutes —
        // see DtedStore.importAsync. The button is held down for the duration: a second
        // import into the same shared pool while one is running has no defined outcome.
        importButton?.isEnabled = false
        findViewById<Button>(R.id.dtedCleanButton)?.isEnabled = false
        dtedStatus.text = "Importing $name…"
        DtedStore.importAsync(
            applicationContext, uri, name,
            // The count climbs as tiles land, so a working import cannot be mistaken for a
            // dead screen — which is exactly how the fault presented.
            onProgress = { tiles -> dtedStatus.text = "Importing $name… $tiles tile(s)" },
        ) { result ->
            dtedStatus.text = when {
                result.error != null && result.importedCount == 0 ->
                    "The app cannot import $name. ${result.error}"
                result.error != null ->
                    "Imported ${result.importedCount} tile(s) from $name. ${result.error}"
                else -> "Imported ${result.importedCount} tile(s) from $name."
            }
            if (result.importedCount == 0) {
                Toast.makeText(this, dtedStatus.text, Toast.LENGTH_SHORT).show()
            }
            renderDtedRegions()
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        return runCatching {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }
        }.getOrNull()
    }

    // ---- 5. FAA Airspace Ceilings (UASFM) ----

    /** Download UASFM ceilings for an area. Deliberately a manual, explicit action on wifi
     *  rather than anything automatic in flight: the flight screen must never depend on having
     *  a network, and a silent background fetch would be exactly the wrong thing to discover
     *  had failed while airborne. */
    private fun setupUasfmSection() {
        val latField = findViewById<EditText>(R.id.uasfmLat)
        val lonField = findViewById<EditText>(R.id.uasfmLon)
        val radiusField = findViewById<EditText>(R.id.uasfmRadius)
        val uasfmStatus = findViewById<TextView>(R.id.uasfmStatus)
        val downloadBtn = findViewById<Button>(R.id.uasfmDownloadButton)
        val checkBtn = findViewById<Button>(R.id.uasfmCheckButton)

        radiusField.setText("50")
        renderUasfmStatus()

        /** Reads the three fields, or null (with a toast) if they do not make sense. */
        fun readBbox(): UasfmStore.Bbox? {
            val lat = latField.text.toString().trim().toDoubleOrNull()
            val lon = lonField.text.toString().trim().toDoubleOrNull()
            val radius = radiusField.text.toString().trim().toDoubleOrNull()
            if (lat == null || lon == null || lat !in -90.0..90.0 || lon !in -180.0..180.0) {
                Toast.makeText(this, "Enter a valid centre latitude and longitude", Toast.LENGTH_SHORT).show()
                return null
            }
            if (radius == null || radius <= 0 || radius > 500) {
                Toast.makeText(this, "Enter a radius between 1 and 500 miles", Toast.LENGTH_SHORT).show()
                return null
            }
            return UasfmStore.bboxAround(lat, lon, radius)
        }

        findViewById<Button>(R.id.uasfmUseLocationButton).setOnClickListener {
            AppLog.v(TAG, "tap: UASFM Use My Location")
            // Ask for location up front rather than letting the read fail: the manifest declares
            // ACCESS_FINE/COARSE_LOCATION but nothing in this app had ever REQUESTED them at
            // runtime, and on the Smart Controller V3's Android 11 that means the read throws
            // and this button would silently report "no GPS fix" — blaming the hardware for a
            // permission problem. Requesting here (rather than at app start) keeps the prompt
            // attached to the one action that needs it.
            useMyLocationFor(R.id.uasfmLat, R.id.uasfmLon)
        }

        checkBtn.setOnClickListener {
            val bbox = readBbox() ?: return@setOnClickListener
            AppLog.v(TAG, "tap: UASFM Check Size")
            checkBtn.isEnabled = false
            uasfmStatus.text = "Checking…"
            UasfmStore.countAsync(bbox) { result ->
                checkBtn.isEnabled = true
                uasfmStatus.text = when {
                    result.error != null -> "The app cannot reach the FAA service. ${result.error}"
                    result.count == 0 ->
                        "There are no FAA cells in this area. The Part 107 limit of 400 ft applies."
                    else -> "${result.count} cell(s) in this area. Press Download to keep them."
                }
            }
        }

        downloadBtn.setOnClickListener {
            val bbox = readBbox() ?: return@setOnClickListener
            val label = "%.3f, %.3f  ·  %s mi".format(
                latField.text.toString().trim().toDoubleOrNull() ?: 0.0,
                lonField.text.toString().trim().toDoubleOrNull() ?: 0.0,
                radiusField.text.toString().trim(),
            )
            AppLog.i(TAG, "UASFM download starting for $label")
            downloadBtn.isEnabled = false
            uasfmStatus.text = "Downloading…"
            UasfmStore.downloadAsync(
                context = this,
                bbox = bbox,
                areaLabel = label,
                onProgress = { count -> uasfmStatus.text = "Downloading… $count cell(s)" },
                onDone = { result ->
                    downloadBtn.isEnabled = true
                    if (result.error != null) {
                        AppLog.w(TAG, "UASFM download failed: ${result.error}")
                        uasfmStatus.text = result.error
                    } else {
                        // Surface off-grid skips rather than burying them: a non-zero count means
                        // the FAA moved off the 1/120 degree grid this design assumes, and the
                        // pilot would otherwise have coverage holes with no hint why.
                        val warn = if (result.offGridSkipped > 0)
                            "\n⚠ ${result.offGridSkipped} cell(s) skipped — unexpected grid, report this."
                        else ""
                        renderUasfmStatus(extra = warn)
                        Toast.makeText(this, "FAA ceilings downloaded", Toast.LENGTH_SHORT).show()
                    }
                },
            )
        }

        findViewById<Button>(R.id.uasfmClearButton).setOnClickListener {
            AppLog.v(TAG, "tap: UASFM Clear Data")
            UasfmStore.clear(this)
            renderUasfmStatus()
            Toast.makeText(this, "FAA ceiling data cleared", Toast.LENGTH_SHORT).show()
        }
    }

    private fun renderUasfmStatus(extra: String = "") {
        val uasfmStatus = findViewById<TextView>(R.id.uasfmStatus)
        val meta = UasfmStore.meta(this)
        uasfmStatus.text = if (meta == null) {
            "No FAA data is downloaded. The flight screen shows the Part 107 limit of 400 ft."
        } else {
            "${meta.cellCount} cell(s) for ${meta.areaLabel}\n" +
                "Downloaded ${dtedDateFormat.format(java.util.Date(meta.downloadedAtMs))}  ·  " +
                "FAA effective ${meta.effectiveLabel}$extra"
        }
    }

    private fun hasLocationPermission(): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
            androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.ACCESS_COARSE_LOCATION
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /** Fills the UASFM centre fields from the controller's own fix, or explains why it cannot —
     *  distinguishing "no fix yet" from "permission denied", which are different problems with
     *  different fixes and used to look identical to the pilot. */
    /** Which section's "Use My Location" is waiting on the permission prompt. Needed because
     *  two sections share one request code, and filling the wrong pair of fields would look
     *  like the button did nothing. */
    private var pendingLocationTarget: Pair<Int, Int> = R.id.uasfmLat to R.id.uasfmLon

    /**
     * Fills a centre lat/lon from the best position available.
     *
     * **Aircraft first, controller second.** The aircraft is the better source whenever it is
     * connected: its GNSS is running by definition, it is the thing whose airspace you are
     * downloading, and on this hardware the controller's receiver is frequently not running at
     * all (see below).
     *
     * The controller's GPS is a genuine trap. `getLastKnownLocation()` only reads a CACHE, and
     * nothing populates that cache unless something has called `requestLocationUpdates()`. On a
     * Smart Controller where no app has done so, `dumpsys location` shows the gps provider with
     * `mStarted=false`, `request=OFF`, `last location=null` — permanently, outdoors included.
     * The old message here told the pilot to "go outside for a moment", which was confidently
     * wrong advice for a receiver that was never switched on (found with the aircraft connected
     * outdoors).
     */
    private fun fillCentreFromLocation(
        latId: Int = pendingLocationTarget.first,
        lonId: Int = pendingLocationTarget.second,
    ) {
        val hud = TakBridgeHolder.hud()
        val loc: Pair<Double, Double>? = when {
            hud != null && hud.hasFix -> {
                AppLog.i(TAG, "centre from AIRCRAFT position")
                hud.lat to hud.lon
            }
            else -> lastKnownPhoneLocation()?.also {
                AppLog.i(TAG, "centre from CONTROLLER position")
            }
        }
        if (loc == null) {
            Toast.makeText(
                this,
                "No position is available. Connect the aircraft, or type the centre.",
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        findViewById<EditText>(latId).setText("%.4f".format(loc.first))
        findViewById<EditText>(lonId).setText("%.4f".format(loc.second))
    }

    /** Shared by both "Use My Location" buttons: request the permission if needed, remembering
     *  which fields to fill when the answer comes back, otherwise fill immediately. */
    private fun useMyLocationFor(latId: Int, lonId: Int) {
        pendingLocationTarget = latId to lonId
        if (!hasLocationPermission()) {
            AppLog.i(TAG, "location permission not granted — requesting")
            androidx.core.app.ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
                REQUEST_CODE_LOCATION,
            )
            return
        }
        fillCentreFromLocation(latId, lonId)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_CODE_LOCATION) return
        if (hasLocationPermission()) {
            AppLog.i(TAG, "location permission granted")
            fillCentreFromLocation()
        } else {
            AppLog.i(TAG, "location permission denied — centre must be entered manually")
            Toast.makeText(
                this,
                "Location permission denied. Enter the centre latitude/longitude manually.",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    /** Most recent GPS/network fix from the controller, or null. The Smart Controller V3 has
     *  its own GPS, so unlike a phone-tethered RC this is normally available outdoors. */
    private fun lastKnownPhoneLocation(): Pair<Double, Double>? {
        val lm = getSystemService(android.content.Context.LOCATION_SERVICE)
            as android.location.LocationManager
        val loc = runCatching {
            listOf(
                android.location.LocationManager.GPS_PROVIDER,
                android.location.LocationManager.NETWORK_PROVIDER,
            ).mapNotNull { p -> if (lm.isProviderEnabled(p)) lm.getLastKnownLocation(p) else null }
                .maxByOrNull { it.time }
        }.getOrNull() ?: return null
        return loc.latitude to loc.longitude
    }

    companion object {
        private const val TAG = "TakConnectActivity"

        /** How long "Apply to aircraft" waits before reading back. Must clear the slowest write.
         *  Was 11s, set by the signal-loss write's 10s timeout; that write is gone (it set
         *  nothing) and the remaining ones acknowledge in well under a second — measured, the
         *  whole batch answers within ~150ms. Two seconds is that with
         *  room to spare, and the read-back keeps its own 12s watchdog underneath. */
        private const val VERIFY_DELAY_MS = 2000L
        /** Progress-bar/countdown refresh rate while waiting for [VERIFY_DELAY_MS] to elapse. */
        private const val APPLY_TICK_MS = 200L
        private const val REQUEST_CODE_DTED_PICK = 4301
        private const val REQUEST_CODE_LOCATION = 4302
        /** Shared with FlightActivity, which reads the TAK lock to gate the channel dialog. */
        internal const val PREFS = "takpilot2_tak"

        /** Per-section configuration locks — see setupConfigLocks(). */
        internal const val KEY_TAK_LOCKED = "tak_config_locked"
        internal const val KEY_VIDEO_LOCKED = "video_config_locked"
        internal const val KEY_BATTERY_LOCKED = "battery_levels_locked"

        /**
         * The one password that unlocks any locked section.
         *
         * ⚠ **This is a speed bump, not security, and it must not be mistaken for it.** The
         * string ships in the APK in plain text — anyone with the file and `strings`, or with
         * adb, reads it in seconds. That is accepted: the threat model
         * is an average user tapping into settings they should not adjust, not an adversary.
         * Do not "harden" this with hashing or per-device secrets — a stronger lock on the
         * front door of an unlocked house, and it would cost the field-recoverability that a
         * shared fixed password exists to provide.
         *
         * The entered attempt is never logged, right or wrong.
         */
        internal const val UNLOCK_PASSWORD = "takpilot"

        // ⚠ ONLY WHAT THE SUMMARY READS. The rest of the TAK keys went to
        // [TakServerActivity] with the fields that write them, and two
        // copies of one key is the trap this file already names elsewhere. The preference FILE
        // is still the same one — nothing migrated.
        private const val KEY_USERNAME = "username"
        private const val KEY_CALLSIGN = "callsign"
        /** Read by the summary, which says what happens with no Elevated account at all.
         *  "CHB" = "channel B", distinct from the KEY_V_* family below, which means "video
         *  STREAM config" (RTSP/SRT host/port/codec) — a completely different concept. */
        internal const val KEY_CHB_ENABLED = "chb_enabled"

        private const val KEY_V_HOST = "video_host"
        private const val KEY_V_PORT = "video_port"
        private const val KEY_V_USER = "video_user"
        /** Named constant, not a literal. The save site used a bare "video_pass" while the
         *  restore site did not exist at all — a constant makes the pair impossible to miss. */
        private const val KEY_V_PASS = "video_pass"
        private const val KEY_V_STREAMID = "video_streamid"
        // The plain transport, port-per-protocol and passphrase keys (video_transport,
        // video_rtsp_port, video_srt_port, video_srt_passphrase) and the video_adv_* set had
        // constants here for this screen's own copy of the mirror. That copy is gone — see
        // VideoServersActivity.mirrorActiveSlot, which names every key it writes in one place.
        // Do not reintroduce a second set of names for them here.
        private const val KEY_V_PROFILE = "video_profile"
        /** Must match the literal read in AutelVideoStreamer.startFromPrefs. */
        private const val KEY_V_CODEC = "video_codec"

        /** Which of the two video servers is live: 1 or 2. The per-slot fields are keyed by
         *  [vKey] — "video_s1_host" and so on. */
        private const val KEY_V_ACTIVE_SLOT = "video_active_slot"

        /** Guard for [migrateVideoProtocolSplit]. Separate from the slot-migration flag, which
         *  has already run everywhere and cannot carry a second meaning. */
        internal const val KEY_V_PROTO_SPLIT_MIGRATED = "video_port_per_protocol_migrated"

        /** Set once the single-server configuration has been copied into slot 1. See
         *  migrateVideoSlots for why this must never run twice. */
        internal const val KEY_V_SLOTS_MIGRATED = "video_slots_migrated"

        /** Companion twin of the instance [vKey], for the migrations below. */
        private fun slotKey(slot: Int, base: String) = "video_s${slot}_$base"

        /**
         * Moves a single-server configuration into slot 1, once.
         *
         * A controller upgrading from a build that had ONE video server has its
         * settings are on the plain `video_*` keys. Copying them into slot 1 is what stops the
         * upgrade looking like the video configuration was wiped.
         *
         * It runs one time and marks itself done. It must not run again: after the first edit the
         * slot is the truth and the plain keys are only a mirror of it, so copying back would undo
         * whatever the pilot last did on the other server.
         */
        internal fun migrateVideoSlots(prefs: android.content.SharedPreferences) {
            if (prefs.getBoolean(KEY_V_SLOTS_MIGRATED, false)) return
            prefs.edit()
                .putString(slotKey(1, "name"), "Server 1")
                .putString(slotKey(1, "host"), prefs.getString(KEY_V_HOST, "") ?: "")
                .putInt(slotKey(1, "port"), prefs.getInt(KEY_V_PORT, 8554))
                .putString(slotKey(1, "user"), prefs.getString(KEY_V_USER, "") ?: "")
                .putString(slotKey(1, "pass"), prefs.getString(KEY_V_PASS, "") ?: "")
                .putString(slotKey(1, "streamid"), prefs.getString(KEY_V_STREAMID, "") ?: "")
                // The old `video_tcp` boolean is NOT read across. It could only say UDP or TCP,
                // and UDP no longer exists; every upgrading controller starts on RTSP, which is
                // what the single-server build used.
                .putString(slotKey(1, "transport"), VideoTransport.RTSP.prefValue)
                .putString(slotKey(1, "profile"), prefs.getString(KEY_V_PROFILE, "standard") ?: "standard")
                .putString(slotKey(1, "codec"), prefs.getString(KEY_V_CODEC, null) ?: VideoCodec.H264.prefValue)
                // Slot 2 starts empty and inherits only the defaults. A half-filled second server
                // would be worse than an obviously blank one.
                .putString(slotKey(2, "name"), "Server 2")
                .putInt(slotKey(2, "port"), 8554)
                .putString(slotKey(2, "transport"), VideoTransport.RTSP.prefValue)
                .putString(slotKey(2, "profile"), "standard")
                .putString(slotKey(2, "codec"), VideoCodec.H264.prefValue)
                .putInt(KEY_V_ACTIVE_SLOT, 1)
                .putBoolean(KEY_V_SLOTS_MIGRATED, true)
                .apply()
            AppLog.i(TAG, "video config migrated to slot 1")
        }

        /**
         * Second slot migration: ONE port became a port PER PROTOCOL.
         *
         * It needs its own flag because [migrateVideoSlots] may already have run on a controller
         * and will never run again.
         *
         * ⚠ **Which protocol the old port belonged to depends on the slot's transport.** A slot
         * left on RTSP had an RTSP port; a slot already switched to SRT had an SRT ingest port, and
         * its RTSP port was never asked for — the build used the constant 8554 for it, so that is
         * what goes in.
         *
         * The login is NOT split. One pair authorises the publish on either protocol: SRT has no
         * login of its own, and the media server reads the same credentials out of the stream id.
         *
         * ⚠ It also recovers from the SHORT-LIVED SPLIT LAYOUT that two development builds
         * wrote (`rtsp_user`/`rtsp_pass`). Those keys are read back into the one login if the
         * original is empty, so a controller that ran that build does not come up with no
         * credentials.
         */
        internal fun migrateVideoProtocolSplit(prefs: android.content.SharedPreferences) {
            if (prefs.getBoolean(KEY_V_PROTO_SPLIT_MIGRATED, false)) return
            val e = prefs.edit()
            for (slot in 1..2) {
                val oldPort = prefs.getInt(slotKey(slot, "port"), VideoTransport.RTSP.defaultPort)
                val onSrt = VideoTransport.fromPref(prefs.getString(slotKey(slot, "transport"), null)) ==
                        VideoTransport.SRT
                if (!prefs.contains(slotKey(slot, "rtsp_port"))) {
                    e.putInt(slotKey(slot, "rtsp_port"),
                        if (onSrt) VideoTransport.RTSP.defaultPort else oldPort)
                }
                if (!prefs.contains(slotKey(slot, "srt_port"))) {
                    e.putInt(slotKey(slot, "srt_port"),
                        if (onSrt) oldPort else VideoTransport.SRT.defaultPort)
                }
                // Recover a login left behind by the split-layout builds.
                if ((prefs.getString(slotKey(slot, "user"), "") ?: "").isEmpty()) {
                    prefs.getString(slotKey(slot, "rtsp_user"), null)?.takeIf { it.isNotEmpty() }
                        ?.let { e.putString(slotKey(slot, "user"), it) }
                }
                if ((prefs.getString(slotKey(slot, "pass"), "") ?: "").isEmpty()) {
                    prefs.getString(slotKey(slot, "rtsp_pass"), null)?.takeIf { it.isNotEmpty() }
                        ?.let { e.putString(slotKey(slot, "pass"), it) }
                }
                // The passphrase only changed key name.
                if (!prefs.contains(slotKey(slot, "srt_phrase"))) {
                    e.putString(slotKey(slot, "srt_phrase"),
                        prefs.getString(slotKey(slot, "srtpass"), "") ?: "")
                }
            }
            e.putBoolean(KEY_V_PROTO_SPLIT_MIGRATED, true).apply()
            AppLog.i(TAG, "video config migrated to a port per protocol")
        }
    }

    /**
     * Section 7 — obstacle avoidance. Live state, explicit toggles, nothing persisted.
     *
     * Unlike every other section on this screen, NOTHING here is saved or replayed on connect.
     * The switches show what the aircraft currently reports and change it only when touched.
     * See the layout comment for why a stale saved value would be the wrong thing to push at a
     * safety system.
     *
     * Each toggle RE-READS the aircraft afterwards instead of trusting its own success callback.
     * This SDK has already been caught returning success for things it did not do (the camera's
     * setAspectRatio), and a switch that shows the state the pilot asked for rather than the
     * state the aircraft is in would be worse than no switch.
     */
    private fun wireAvoidanceSection() {
        val status = findViewById<android.widget.TextView>(R.id.avoidStatus)
        val system = findViewById<android.widget.CheckBox>(R.id.avoidSystem)
        val rth = findViewById<android.widget.CheckBox>(R.id.avoidRth)
        val landing = findViewById<android.widget.CheckBox>(R.id.avoidLanding)
        val boxes = listOf(system, rth, landing)

        fun render() {
            val connected = AutelProductHolder.isConnected
            val known = AutelAvoidance.systemEnabled != null
            // Simplified Technical English, as with the field guide: short sentences, active
            // voice, one idea each. A pilot reads this on the ground in a hurry.
            status.text = when {
                !connected -> "The aircraft is not connected."
                !known -> "Wait. The aircraft did not send the state yet."
                AutelAvoidance.systemEnabled == true -> "Obstacle avoidance is ON."
                else -> "Obstacle avoidance is OFF."
            }
            status.setTextColor(androidx.core.content.ContextCompat.getColor(this,
                when {
                    // Not connected, or the aircraft has not answered — the UNKNOWN amber,
                    // which is a different fact from OFF and from a refusal.
                    !connected || !known -> R.color.tp_state_unknown
                    AutelAvoidance.systemEnabled == true -> R.color.tp_state_go
                    else -> R.color.tp_state_danger
                }))
            // Set the boxes WITHOUT firing their listeners, or rendering the aircraft's state
            // would look like a pilot toggle and be pushed straight back at it.
            boxes.forEach { it.setOnCheckedChangeListener(null) }
            system.isChecked = AutelAvoidance.systemEnabled == true
            rth.isChecked = AutelAvoidance.avoidDuringRth == true
            landing.isChecked = AutelAvoidance.landingProtect == true
            boxes.forEach { it.isEnabled = connected && known }
            attachListeners()
        }

        fun toggle(which: com.autel.common.flycontroller.visual.VisualSettingSwitchblade,
                   enabled: Boolean) {
            boxes.forEach { it.isEnabled = false }        // no double-taps mid-flight
            // Record the INTENT as well as applying it. This is what gets enforced on every
            // future connect — without it the pilot's choice would last only until Autel's app
            // changed it back.
            AutelAvoidance.saveIntent(this,
                if (which == com.autel.common.flycontroller.visual.VisualSettingSwitchblade.AVOIDANCE_SYSTEM) enabled else system.isChecked,
                if (which == com.autel.common.flycontroller.visual.VisualSettingSwitchblade.RETURN_TO_HOME_AVOIDANCE) enabled else rth.isChecked,
                if (which == com.autel.common.flycontroller.visual.VisualSettingSwitchblade.LANDING_PROTECT) enabled else landing.isChecked)
            AutelAvoidance.setSwitch(which, enabled) { ok ->
                runOnUiThread {
                    if (!ok) android.widget.Toast.makeText(this@TakConnectActivity,
                        "The aircraft did not accept the change.", android.widget.Toast.LENGTH_SHORT).show()
                    render()
                }
            }
        }

        attachListeners = {
            system.setOnCheckedChangeListener { _, v ->
                toggle(com.autel.common.flycontroller.visual.VisualSettingSwitchblade.AVOIDANCE_SYSTEM, v)
            }
            rth.setOnCheckedChangeListener { _, v ->
                toggle(com.autel.common.flycontroller.visual.VisualSettingSwitchblade.RETURN_TO_HOME_AVOIDANCE, v)
            }
            landing.setOnCheckedChangeListener { _, v ->
                toggle(com.autel.common.flycontroller.visual.VisualSettingSwitchblade.LANDING_PROTECT, v)
            }
        }

        render()
        // The state arrives asynchronously after the aircraft syncs, so re-render for a while
        // rather than leaving "waiting…" on screen until the pilot navigates away and back.
        val h = android.os.Handler(android.os.Looper.getMainLooper())
        var ticks = 0
        val poll = object : Runnable {
            override fun run() {
                render()
                if (++ticks < 30 && !isFinishing) h.postDelayed(this, 1000)
            }
        }
        h.postDelayed(poll, 1000)
    }

    /**
     * Control response (Normal / Precision) and the read-only stick mode.
     *
     * Same discipline as the avoidance block above: live values, disabled until the controller
     * reports, changed only on an explicit tap, nothing persisted and nothing pushed at connect.
     *
     * The stick mode is DISPLAY ONLY. The SDK reports USA/CHINA/JAPAN and the mapping to
     * "Mode 1/2/3" is convention rather than anything confirmed on this aircraft — and a wrong
     * mode number in front of a pilot is the sort of error that swaps throttle and pitch.
     */
    private fun wireControlRatesSection() {
        val group = findViewById<android.widget.RadioGroup>(R.id.ratesGroup)
        val normal = findViewById<android.widget.RadioButton>(R.id.ratesNormal)
        val precision = findViewById<android.widget.RadioButton>(R.id.ratesPrecision)
        val stick = findViewById<android.widget.TextView>(R.id.stickModeStatus)

        val stickGroup = findViewById<android.widget.RadioGroup>(R.id.stickModeGroup)
        val stickIds = mapOf("1" to R.id.stickMode1, "2" to R.id.stickMode2, "3" to R.id.stickMode3)

        fun render() {
            val connected = AutelProductHolder.isConnected
            // SEED FROM THE AIRCRAFT the first time. The app must not invent a stick mode — that
            // would swap throttle and pitch for a pilot who never opened this screen — but once
            // seeded it is enforced on every connect so Autel's app cannot change it behind them.
            if (AutelControlRates.savedStickModeId(this).isEmpty() && AutelControlRates.stickMode != null) {
                AutelControlRates.saveStickModeId(this, AutelControlRates.idFor(AutelControlRates.stickMode))
            }
            val chosen = AutelControlRates.savedStickModeId(this)
            stickGroup.setOnCheckedChangeListener(null)
            stickIds[chosen]?.let { stickGroup.check(it) }
            stickIds.values.forEach { findViewById<android.widget.RadioButton>(it).isEnabled = connected }
            stick.text = if (chosen.isEmpty()) "Waiting for the controller."
                else "The controller reports ${AutelControlRates.stickModeLabel()}."
            stickGroup.setOnCheckedChangeListener { _, id ->
                val pick = stickIds.entries.firstOrNull { it.value == id }?.key ?: return@setOnCheckedChangeListener
                AutelControlRates.saveStickModeId(this, pick)
                AutelControlRates.pushStickMode(this) { ok ->
                    runOnUiThread {
                        if (!ok) android.widget.Toast.makeText(this,
                            "The controller did not accept the stick mode.",
                            android.widget.Toast.LENGTH_SHORT).show()
                        render()
                    }
                }
            }
            // Set the radio WITHOUT its listener, or drawing the controller's state would look
            // like a pilot tap and be sent straight back at it.
            group.setOnCheckedChangeListener(null)
            // THE RADIO SHOWS THE PILOT'S CHOICE, NOT THE CONTROLLER'S CURRENT STATE. TAKPilot
            // asserts this setting on every connect, so the saved choice IS what the airframe
            // will be flying — reflecting a read-back here would let a value Autel Explorer left
            // behind appear to be the selection, moments before the app overwrote it anyway.
            group.check(if (AutelControlRates.savedPrecision(this)) R.id.ratesPrecision
                        else R.id.ratesNormal)
            normal.isEnabled = connected
            precision.isEnabled = connected
            group.setOnCheckedChangeListener { _, id ->
                normal.isEnabled = false; precision.isEnabled = false
                val wantPrecision = id == R.id.ratesPrecision
                // Record the INTENT, exactly as the stick mode and the avoidance switches do.
                // Without this, saveSelection() was never called from anywhere: the preference
                // stayed at its default of false, and applyAtConnect then pushed NORMAL to the
                // controller on every connect. A pilot who chose Precision did not merely lose
                // the choice, the app actively undid it each launch.
                AutelControlRates.saveSelection(this, wantPrecision)
                AutelControlRates.setPrecision(this, wantPrecision) { ok ->
                    runOnUiThread {
                        if (!ok) android.widget.Toast.makeText(this,
                            "The controller did not accept the change.",
                            android.widget.Toast.LENGTH_SHORT).show()
                        render()
                    }
                }
            }
        }

        render()
        AutelControlRates.refresh(this) { runOnUiThread { render() } }
        // Values arrive after the controller syncs, so keep re-reading for a short while rather
        // than leaving "wait…" on screen until the pilot navigates away and back.
        val h = android.os.Handler(android.os.Looper.getMainLooper())
        var ticks = 0
        val poll = object : Runnable {
            override fun run() {
                if (AutelControlRates.precisionActive == null && AutelProductHolder.isConnected) {
                    AutelControlRates.refresh(this@TakConnectActivity) { runOnUiThread { render() } }
                }
                if (++ticks < 20 && !isFinishing) h.postDelayed(this, 1500)
            }
        }
        h.postDelayed(poll, 1500)
    }

    private var attachListeners: () -> Unit = {}
}

// NOTE: TakBridgeHolder lives in AutelTakBridge.kt in this port (it wraps AutelTakBridge).
