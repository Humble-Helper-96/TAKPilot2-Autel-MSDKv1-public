package com.autel.sdksample.tak

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.autel.sdksample.R
import com.taklite.util.AppLog

/**
 * The set-once video server configuration, kept off Pre-Flight.
 *
 * Pre-Flight keeps the two things that change — the video quality, every flight, and which
 * server is active, per callout. Everything else is entered one time and locked, and twenty
 * such controls in one column made the sub-sections easy to mix up with each other.
 *
 * ## Both servers at once, and each field writes its own
 *
 * On Pre-Flight every field below the server toggle belonged to whichever server that toggle
 * selected, and nothing on screen said which one once the pilot had scrolled into the fields.
 * That is the same fault as a control whose meaning changes with a toggle above it — one
 * fixed twice already elsewhere in this app. Here the two servers are side by side and [bindCard] binds
 * each card to a fixed slot, so there is no active-slot state to get wrong.
 *
 * ## What this screen does NOT own
 *
 *  - **The lock.** It lives on Pre-Flight, where it also guards the active-server toggle, and
 *    unlocking asks for a password. This screen reads it and stops taking touches.
 *  - **Which server is active.** Shown as a badge, changed on Pre-Flight.
 *  - **The preference layout.** Nothing moved; these are the same per-slot keys the previous
 *    screen wrote, so there is no migration.
 *
 * ⚠ **The active slot must be mirrored onto the plain keys after any edit.** [AutelVideoStreamer]
 * reads those, not the slots — see [mirrorActiveSlot], the one mirror.
 */
class VideoServersActivity : AppCompatActivity() {

    private lateinit var prefs: android.content.SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video_servers)

        AppLog.v(TAG, "video servers opened")
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_menu)
        }
        prefs = getSharedPreferences("takpilot2_tak", Context.MODE_PRIVATE)

        val locked = prefs.getBoolean(KEY_VIDEO_LOCKED, false)
        findViewById<TextView>(R.id.vsLockNotice).visibility =
            if (locked) View.VISIBLE else View.GONE

        bindCard(1, locked)
        bindCard(2, locked)

        // ⚠ BASIC HAS ONE VIDEO SERVER, AND THERE ARE TWO DOORS TO THE SECOND ONE.
        //  1. Card 2 itself.
        //  2. CARD 1's "Other" radio under TAK Advertisement Server. Selecting it writes
        //     advertise="other", and the mirror then resolves the WHOLE advertisement — host,
        //     port, login and read passphrase — from the hidden slot. The first version hid only
        //     the card, so a Basic pilot could put a server they could no longer see or edit on
        //     every channel's CoT.
        // A stored "other" cannot be left behind for the second hide to orphan:
        // AppModeTransition forces it to OFF, and AppMode.settle re-asserts that at every start
        // (AppModeTransition.enforceBasicInvariants). This is the UI half only.
        // Done AFTER bindCard, so the binding that fills these views does not undo it.
        if (!AppMode.secondVideoServerAllowed(prefs)) {
            findViewById<View>(R.id.vs2Card).visibility = View.GONE
            findViewById<View>(R.id.vs1AdvOther).visibility = View.GONE
            // Card 1 keeps an end margin for the gap to card 2; with card 2 gone that margin
            // would leave it short of the edge, so it is dropped.
            findViewById<View>(R.id.vs1Card).let { card ->
                (card.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.let {
                    it.marginEnd = 0
                    card.layoutParams = it
                }
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    /**
     * Wires one card to one server slot.
     *
     * The slot is a parameter and never changes for the life of the card, which is the whole
     * reason this screen exists. Every read and every write below names it.
     */
    private fun bindCard(slot: Int, locked: Boolean) {
        fun id(name: String): Int =
            resources.getIdentifier("vs$slot$name", "id", packageName)
        fun <T : View> v(name: String): T = findViewById(id(name))

        val title: TextView = v("Title")
        val activeBadge: TextView = v("Active")
        val name: EditText = v("Name")
        val host: EditText = v("Host")
        val streamId: EditText = v("StreamId")
        val randomPath: CheckBox = v("RandomPath")
        val streamPath: TextView = v("StreamPath")
        val user: EditText = v("User")
        val pass: EditText = v("Pass")
        val codecGroup: RadioGroup = v("CodecGroup")
        val codecHint: TextView = v("CodecHint")
        val transportGroup: RadioGroup = v("TransportGroup")
        val transportHint: TextView = v("TransportHint")
        val rtspBlock: LinearLayout = v("RtspBlock")
        val rtspPort: EditText = v("RtspPort")
        val srtBlock: LinearLayout = v("SrtBlock")
        val srtPort: EditText = v("SrtPort")
        val passphrase: EditText = v("Passphrase")
        val advGroup: RadioGroup = v("AdvGroup")
        val advOther: RadioButton = v("AdvOther")
        val advProtoGroup: RadioGroup = v("AdvProtoGroup")
        val advProtoHint: TextView = v("AdvProtoHint")
        val advSrtBlock: LinearLayout = v("AdvSrtBlock")
        val advSrtPort: EditText = v("AdvSrtPort")
        val advPassphrase: EditText = v("AdvPassphrase")
        val advUrl: TextView = v("AdvUrl")
        val pushUrl: TextView = v("PushUrl")

        /** True while the fields are filled from prefs, so the listeners do not treat the fill
         *  as an edit and write it straight back. Same guard the old screen needed. */
        var loading = true

        fun slotName(s: Int): String =
            prefs.getString(vKey(s, "name"), "")?.takeIf { it.isNotBlank() } ?: "Server $s"

        fun selectedCodec(): VideoCodec =
            if (codecGroup.checkedRadioButtonId == id("CodecH265")) VideoCodec.H265
            else VideoCodec.H264

        fun selectedTransport(): VideoTransport =
            if (transportGroup.checkedRadioButtonId == id("TransportSrt")) VideoTransport.SRT
            else VideoTransport.RTSP

        /** How the TEAM READS it — independent of [selectedTransport], which is the push. */
        fun selectedAdvProto(): VideoTransport =
            if (advProtoGroup.checkedRadioButtonId == id("AdvProtoSrt")) VideoTransport.SRT
            else VideoTransport.RTSP

        fun selectedAdvertise(): String = when (advGroup.checkedRadioButtonId) {
            id("AdvOther") -> ADV_OTHER
            id("AdvOff") -> ADV_OFF
            else -> ADV_SELF
        }

        fun buildConfig(): AutelVideoStreamer.VideoConfig {
            val base = AutelVideoStreamer.VideoConfig(
                host = host.text.toString().trim(),
                streamId = streamId.text.toString().trim(),
                username = user.text.toString().trim(),
                password = pass.text.toString(),
                rtspPort = rtspPort.text.toString().trim().toIntOrNull()
                    ?: VideoTransport.RTSP.defaultPort,
                srtPort = srtPort.text.toString().trim().toIntOrNull()
                    ?: VideoTransport.SRT.defaultPort,
                transport = selectedTransport(),
                srtPassphrase = passphrase.text.toString(),
                codec = selectedCodec().prefValue,
                profile = prefs.getString(vKey(slot, "profile"), "standard") ?: "standard",
                randomizePath = randomPath.isChecked,
            )
            // The advertised address, resolved from the OTHER slot's stored values — never
            // from the other card's live fields. Both cards are on screen, so reading prefs
            // keeps one card's half-typed host out of the other card's advertisement.
            val other = if (slot == 1) 2 else 1
            return when (selectedAdvertise()) {
                ADV_OFF -> base.copy(advertiseEnabled = false)
                // ⚠ THE READ PROTOCOL FOLLOWS THE SERVER, not this card. Advertising through
                // the other server means the team connects to THAT server, so its scheme, its
                // read port and its read passphrase are the ones that have to go in the CoT —
                // the same rule the host, port and login beside them already follow. This
                // card's own "TAK Advertisement Protocol" control describes this card's
                // server, and is read by the other card when IT advertises through this one.
                ADV_OTHER -> base.copy(
                    advertiseEnabled = true,
                    advertiseHost = prefs.getString(vKey(other, "host"), "") ?: "",
                    advertisePort = prefs.getInt(vKey(other, "rtsp_port"),
                        VideoTransport.RTSP.defaultPort),
                    advertiseUser = prefs.getString(vKey(other, "user"), "") ?: "",
                    advertisePass = prefs.getString(vKey(other, "pass"), "") ?: "",
                    advertiseTransport = VideoTransport.fromPref(
                        prefs.getString(vKey(other, "adv_transport"), null)),
                    advertiseSrtPort = prefs.getInt(vKey(other, "adv_srt_port"),
                        VideoTransport.SRT.defaultPort),
                    advertisePassphrase =
                        prefs.getString(vKey(other, "adv_srt_phrase"), "") ?: "",
                )
                else -> base.copy(
                    advertiseEnabled = true,
                    advertiseHost = base.host,
                    advertisePort = base.rtspPort,
                    advertiseUser = base.username,
                    advertisePass = base.password,
                    advertiseTransport = selectedAdvProto(),
                    advertiseSrtPort = advSrtPort.text.toString().trim().toIntOrNull()
                        ?: VideoTransport.SRT.defaultPort,
                    advertisePassphrase = advPassphrase.text.toString(),
                )
            }
        }

        fun refreshDerived() {
            val srt = selectedTransport() == VideoTransport.SRT
            srtBlock.visibility = if (srt) View.VISIBLE else View.GONE
            rtspBlock.visibility = if (srt) View.GONE else View.VISIBLE

            codecHint.text = if (selectedCodec() == VideoCodec.H265)
                "More efficient. Better picture for the bandwidth, but fewer clients play it."
            else
                "Most compatible. Plays on the widest range of clients."

            transportHint.text = if (srt)
                "Low delay on a less reliable network, for example a cellular network."
            else
                "The lowest delay on a reliable network."

            val advSrt = selectedAdvProto() == VideoTransport.SRT
            advSrtBlock.visibility = if (advSrt) View.VISIBLE else View.GONE
            advProtoHint.text = if (advSrt)
                "ATAK only. TAK Aware cannot play SRT yet — leave this on RTSP for a mixed team."
            else
                "Plays on every TAK client. Use this unless the whole team is on ATAK."

            advOther.text = slotName(if (slot == 1) 2 else 1)
            val cfg = buildConfig()
            // ⚠ There was a "The CoT carries host:port" line here and it is GONE (design
            // decision). It restated, less precisely, what the TAK Advertisement Address
            // below already shows in full — and a card that says the same thing twice in two
            // shapes is what made this screen blur together when read quickly.
            advUrl.text =
                if (!cfg.advertiseEnabled) "(no address in the CoT)"
                else if (cfg.advertiseHost.ifEmpty { cfg.host }.isEmpty() ||
                    cfg.streamId.isEmpty())
                    "${selectedAdvProto().scheme}://…  (enter host + identifier)"
                else cfg.advertiseUrlSafe()
            pushUrl.text =
                if (cfg.host.isEmpty() || cfg.streamId.isEmpty())
                    "${cfg.transport.scheme}://…  (enter host + identifier)"
                else cfg.urlSafe()
            // The name the server will see, from the same function that builds every address.
            // With the token on, this is the one place a pilot can read the token before LIVE.
            streamPath.text =
                if (cfg.streamId.isEmpty()) "…  (enter a broadcast id)" else cfg.streamPath()
            title.text = slotName(slot)
        }

        /**
         * ⚠ EVERY FIELD READ IN THE FILL BELOW MUST BE WRITTEN HERE. A field read and not
         * written loses its value the moment anything else on the card is edited. That trap
         * cost a stored video password once already.
         */
        fun save() {
            val cfg = buildConfig()
            prefs.edit()
                .putString(vKey(slot, "name"), name.text.toString().trim())
                .putString(vKey(slot, "host"), cfg.host)
                .putString(vKey(slot, "streamid"), cfg.streamId)
                .putString(vKey(slot, "user"), cfg.username)
                .putString(vKey(slot, "pass"), cfg.password)
                .putInt(vKey(slot, "rtsp_port"), cfg.rtspPort)
                .putInt(vKey(slot, "srt_port"), cfg.srtPort)
                .putString(vKey(slot, "transport"), cfg.transport.prefValue)
                .putString(vKey(slot, "srt_phrase"), cfg.srtPassphrase)
                .putString(vKey(slot, "codec"), cfg.codec)
                .putString(vKey(slot, "advertise"), selectedAdvertise())
                // ⚠ FROM THE CONTROLS, NOT FROM `cfg`. With "Other" selected `cfg` carries the
                // OTHER slot's read settings — that is the whole point of that branch — and
                // writing those back here would overwrite this card's own with its neighbour's
                // the moment anything on the card was touched.
                .putString(vKey(slot, "adv_transport"), selectedAdvProto().prefValue)
                .putInt(vKey(slot, "adv_srt_port"),
                    advSrtPort.text.toString().trim().toIntOrNull()
                        ?: VideoTransport.SRT.defaultPort)
                .putString(vKey(slot, "adv_srt_phrase"), advPassphrase.text.toString())
                .putBoolean(vKey(slot, "random_path"), cfg.randomizePath)
                .apply()
            mirrorActiveSlot(prefs)
            refreshDerived()
            // The other card names this one on its "TAK Advertisement Server" button and may
            // advertise through it, so a change here can change what it shows.
            refreshOtherCard(slot)
        }

        // ---- Fill from the slot ----
        name.setText(prefs.getString(vKey(slot, "name"), "") ?: "")
        host.setText(prefs.getString(vKey(slot, "host"), "") ?: "")
        streamId.setText(prefs.getString(vKey(slot, "streamid"), "") ?: "")
        randomPath.isChecked = prefs.getBoolean(vKey(slot, "random_path"), false)
        user.setText(prefs.getString(vKey(slot, "user"), "") ?: "")
        pass.setText(prefs.getString(vKey(slot, "pass"), "") ?: "")
        rtspPort.setText(prefs.getInt(vKey(slot, "rtsp_port"),
            VideoTransport.RTSP.defaultPort).toString())
        srtPort.setText(prefs.getInt(vKey(slot, "srt_port"),
            VideoTransport.SRT.defaultPort).toString())
        passphrase.setText(prefs.getString(vKey(slot, "srt_phrase"), "") ?: "")
        codecGroup.check(
            if (VideoCodec.fromPref(prefs.getString(vKey(slot, "codec"), null)) == VideoCodec.H265)
                id("CodecH265") else id("CodecH264"))
        transportGroup.check(
            if (VideoTransport.fromPref(prefs.getString(vKey(slot, "transport"), null)) ==
                VideoTransport.SRT) id("TransportSrt") else id("TransportRtsp"))
        advSrtPort.setText(prefs.getInt(vKey(slot, "adv_srt_port"),
            VideoTransport.SRT.defaultPort).toString())
        advPassphrase.setText(prefs.getString(vKey(slot, "adv_srt_phrase"), "") ?: "")
        advProtoGroup.check(
            if (VideoTransport.fromPref(prefs.getString(vKey(slot, "adv_transport"), null)) ==
                VideoTransport.SRT) id("AdvProtoSrt") else id("AdvProtoRtsp"))
        advGroup.check(when (prefs.getString(vKey(slot, "advertise"), ADV_SELF)) {
            ADV_OTHER -> id("AdvOther")
            ADV_OFF -> id("AdvOff")
            else -> id("AdvSelf")
        })
        activeBadge.visibility =
            if (prefs.getInt(KEY_V_ACTIVE_SLOT, 1) == slot) View.VISIBLE else View.GONE
        loading = false
        refreshDerived()

        // Copy the path on a touch. The path is what the pilot checks against the server, and
        // with the token on it is not typeable from memory. Reading it works locked or not.
        streamPath.setOnClickListener {
            val text = streamPath.text?.toString() ?: return@setOnClickListener
            if (text.startsWith("…")) return@setOnClickListener
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("stream path", text))
            android.widget.Toast.makeText(this, "Stream path copied", android.widget.Toast.LENGTH_SHORT)
                .show()
        }

        if (locked) {
            // The lock stops a CHANGE, not the reading: the fields keep full contrast and stop
            // taking touches, exactly as they did on Pre-Flight.
            for (view in listOf<View>(name, host, streamId, user, pass, rtspPort, srtPort,
                passphrase, advSrtPort, advPassphrase)) {
                view.isEnabled = false
            }
            randomPath.apply { isClickable = false; isFocusable = false }
            for (group in listOf(codecGroup, transportGroup, advGroup, advProtoGroup)) {
                for (i in 0 until group.childCount) {
                    group.getChildAt(i).apply { isClickable = false; isFocusable = false }
                }
            }
            return
        }

        val watcher = object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { if (!loading) save() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        }
        listOf(name, host, streamId, user, pass, rtspPort, srtPort, passphrase,
            advSrtPort, advPassphrase)
            .forEach { it.addTextChangedListener(watcher) }
        for (group in listOf(codecGroup, transportGroup, advGroup, advProtoGroup)) {
            group.setOnCheckedChangeListener { _, _ ->
                refreshDerived()
                if (!loading) save()
            }
        }
        randomPath.setOnCheckedChangeListener { _, _ ->
            refreshDerived()
            if (!loading) save()
        }
    }

    /** Repaints the other card, whose name button and advertised address can depend on this
     *  one. Cheap: it only touches derived text, never a field the pilot is typing in. */
    private fun refreshOtherCard(changed: Int) {
        val other = if (changed == 1) 2 else 1
        val otherName = prefs.getString(vKey(changed, "name"), "")
            ?.takeIf { it.isNotBlank() } ?: "Server $changed"
        findViewById<RadioButton>(
            resources.getIdentifier("vs${other}AdvOther", "id", packageName))?.text = otherName
    }

    companion object {
        private const val TAG = "VideoServersActivity"

        /** Preference key for one field of one video server slot. Must match the helper of the
         *  same name in [TakConnectActivity] — the two screens share this store. */
        fun vKey(slot: Int, base: String) = "video_s${slot}_$base"

        const val ADV_SELF = "self"
        const val ADV_OTHER = "other"
        const val ADV_OFF = "off"

        /** Internal, not private: [AppMode] and [AppModeTransition] read and force it. */
        internal const val KEY_V_ACTIVE_SLOT = "video_active_slot"
        private const val KEY_VIDEO_LOCKED = "video_config_locked"

        /**
         * Copies the ACTIVE slot onto the plain `video_*` keys. **THE ONE MIRROR** — this
         * screen, Pre-Flight and [AppModeTransition] all call it.
         *
         * ⚠ The streamer ([VideoStreamerHolder.startFromPrefs]) reads those plain keys and knows
         * nothing about slots, so an edit that is not mirrored is an edit the stream never sees.
         * A pilot can go Home -> Flight -> LIVE without opening this screen or Pre-Flight at
         * all, so nothing may rely on a screen to mirror for it.
         *
         * ## ⚠ THERE WERE TWO OF THESE, AND THEY HAD DRIFTED
         *
         * Pre-Flight carried its own private copy. That copy wrote `video_profile` and this one
         * never did; drift the other way is a live bug (Pre-Flight sending the new server's host
         * with the OLD server's read port and passphrase). This is the union of both copies, and there is now nowhere for a key to
         * be added to one and not the other.
         *
         * ⚠ The active slot is NORMALISED here. A stored 0 or 3 used to mirror `vKey(3, …)` —
         * an empty host — on this screen while Pre-Flight's copy treated it as 1.
         */
        internal fun mirrorActiveSlot(prefs: android.content.SharedPreferences) {
            // ⚠ NOT BEFORE THE SLOTS EXIST. On a controller from
            // before the slots, the plain keys ARE the configuration. If the boot migration
            // failed (its caller is guarded, so a drifted key type stops it part-way), mirroring
            // empty slots over them would blank the host, stream and login. Every screen calls
            // this, so the gate lives here, not at the boot call alone.
            if (!prefs.getBoolean(TakConnectActivity.KEY_V_SLOTS_MIGRATED, false) ||
                !prefs.getBoolean(TakConnectActivity.KEY_V_PROTO_SPLIT_MIGRATED, false)
            ) {
                AppLog.w(TAG, "video slots not migrated — the plain video keys are left alone")
                return
            }
            val slot = if (prefs.getInt(KEY_V_ACTIVE_SLOT, 1) == 2) 2 else 1
            val transport = VideoTransport.fromPref(prefs.getString(vKey(slot, "transport"), null))
            val advertise = prefs.getString(vKey(slot, "advertise"), ADV_SELF)
            val other = if (slot == 1) 2 else 1
            val advOn = advertise != ADV_OFF
            // The advertise-through server: the team connects to whichever server the CoT names.
            val src = if (advertise == ADV_OTHER) other else slot
            prefs.edit()
                // ---- The PUSH, from the active slot ----
                .putString("video_host", prefs.getString(vKey(slot, "host"), "") ?: "")
                .putString("video_streamid", prefs.getString(vKey(slot, "streamid"), "") ?: "")
                .putString("video_user", prefs.getString(vKey(slot, "user"), "") ?: "")
                .putString("video_pass", prefs.getString(vKey(slot, "pass"), "") ?: "")
                .putInt("video_rtsp_port",
                    prefs.getInt(vKey(slot, "rtsp_port"), VideoTransport.RTSP.defaultPort))
                .putInt("video_srt_port",
                    prefs.getInt(vKey(slot, "srt_port"), VideoTransport.SRT.defaultPort))
                .putString("video_transport", transport.prefValue)
                .putString("video_srt_passphrase",
                    prefs.getString(vKey(slot, "srt_phrase"), "") ?: "")
                .putString("video_codec", prefs.getString(vKey(slot, "codec"), null)
                    ?: VideoCodec.H264.prefValue)
                // Only Pre-Flight's old copy wrote this. Per slot, like everything above.
                .putString("video_profile", prefs.getString(vKey(slot, "profile"), "standard")
                    ?: "standard")
                // ---- The ADVERTISEMENT, from the advertise-through slot ----
                // ⚠ Written false, never removed: the streamer defaults video_adv_on to TRUE,
                // so a missing key would turn the advertisement on and fall back to the push host.
                .putBoolean("video_adv_on", advOn)
                .putString("video_adv_host", prefs.getString(vKey(src, "host"), "") ?: "")
                .putInt("video_adv_port",
                    prefs.getInt(vKey(src, "rtsp_port"), VideoTransport.RTSP.defaultPort))
                .putString("video_adv_user", prefs.getString(vKey(src, "user"), "") ?: "")
                .putString("video_adv_pass", prefs.getString(vKey(src, "pass"), "") ?: "")
                // The READ leg travels WITH the host above, from the same slot: the team
                // connects to whichever server the CoT names, so its scheme, read port and read
                // passphrase go together with its host and login.
                .putString("video_adv_transport", VideoTransport.fromPref(
                    prefs.getString(vKey(src, "adv_transport"), null)).prefValue)
                .putInt("video_adv_srt_port",
                    prefs.getInt(vKey(src, "adv_srt_port"), VideoTransport.SRT.defaultPort))
                .putString("video_adv_srt_phrase",
                    prefs.getString(vKey(src, "adv_srt_phrase"), "") ?: "")
                // The token toggle belongs to the PUSH, so it mirrors from the active slot and
                // never from the advertise-through slot — the path is composed where the stream
                // is published.
                .putBoolean(StreamPath.PREF_RANDOMIZE,
                    prefs.getBoolean(vKey(slot, "random_path"), false))
                .apply()
        }
    }
}
