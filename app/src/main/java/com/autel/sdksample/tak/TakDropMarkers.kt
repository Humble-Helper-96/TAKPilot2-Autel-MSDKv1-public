package com.autel.sdksample.tak

import android.content.Context
import android.graphics.Bitmap
import com.taklite.util.AppLog
import com.autel.sdksample.R
import com.taklite.client.tak.TakManager
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker

/**
 * User-dropped MIL-STD-2525 pins on the flight map, drawn with osmdroid:
 *
 * Tap "Drop Pin" → pick affiliation → tap the map to place → Send-to-TAK / Don't-Send
 * popup. Tap an existing pin → Send / Delete. Pins persist across restarts. If a Data
 * Sync feed is joined, pins auto-publish to the feed (feed-scoped) with no prompt.
 */
object TakDropMarkers {
    private const val TAG = "TakDropMarkers"
    private const val PREFS = "takpilot2_dropped"
    private const val KEY = "pins"

    enum class Affiliation(val id: String, val label: String, val res: Int) {
        FRIENDLY("Friendly", "Friendly", R.drawable.marker_friendly),
        HOSTILE("Hostile", "Hostile", R.drawable.marker_hostile),
        NEUTRAL("Neutral", "Neutral", R.drawable.marker_neutral),
        UNKNOWN("Unknown", "Unknown", R.drawable.marker_unknown),
    }

    private class Pin(
        val key: String,
        var lat: Double,
        var lon: Double,
        var alt: Double,
        var affiliation: Affiliation,
        var name: String,
        var transmitted: Boolean,
        /**
         * CoT uid from the FIRST send — the marker's identity on TAK. Null until sent.
         *
         * Re-using it is what makes move/rename/retype/re-send update the existing marker on
         * every other client instead of scattering duplicates: in CoT the uid *is* the marker.
         * See [TakManager.sendMarkerWithUid].
         */
        var cotUid: String? = null,
        /** The one reticle-tap pin — see [quickPin]. Identified by this flag, not its name,
         *  so renaming it from the markers list can't quietly free the slot. */
        val quick: Boolean = false,
        var marker: Marker? = null,
    )

    /** Read-only snapshot for the markers list panel. */
    data class PinInfo(
        val key: String, val name: String, val affiliation: Affiliation,
        val lat: Double, val lon: Double, val alt: Double,
    )

    private var appContext: Context? = null
    private var map: MapView? = null
    private var tapOverlay: MapEventsOverlay? = null
    private val pins = LinkedHashMap<String, Pin>()

    /** Affiliation selected by the "Drop Pin" UI; the next map tap places a pin of this type. */
    @Volatile private var pendingAffiliation: Affiliation? = null

    /** UI callbacks the flight Activity supplies (it owns the dialogs/toasts). */
    interface Ui {
        fun askSend(affiliationLabel: String, onChoice: (Boolean) -> Unit)
        fun pinMenu(title: String, onSend: () -> Unit, onDelete: () -> Unit, sendLabel: String? = "Send to TAK")
        fun toast(msg: String)
    }
    @Volatile var ui: Ui? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        // Resolve the callsign HERE, not lazily on first read. It used to be picked on first
        // access, which left a window where appContext was still null and the getter returned a
        // fallback — and a marker created in that window kept the wrong name permanently,
        // because pin names are persisted. Observed on hardware.
        AppLog.i(TAG, "quick marker callsign: $QUICK_NAME")
        load()
        healQuickPinName()
    }

    /**
     * Brings a saved quick pin's label into line with this install's callsign.
     *
     * Needed because the label is stored ON the pin: a marker placed before the callsign was
     * resolved (or carried over from a build that used a fixed name) would otherwise keep the
     * old label for ever, which is exactly the confusion the per-install callsign exists to
     * prevent. Keeps the pin's uid, so when it is next sent the team sees the SAME marker
     * relabelled rather than a second one appearing.
     */
    private fun healQuickPinName() {
        val pin = pins.values.firstOrNull { it.quick } ?: return
        if (pin.name == QUICK_NAME) return
        AppLog.i(TAG, "quick marker relabelled: '${pin.name}' -> '$QUICK_NAME'")
        pin.name = QUICK_NAME
        save()
        redrawPin(pin)
    }

    /** Called by TakMapMarkers when the osmdroid map is ready. Wires tap handling + redraws. */
    fun onMapReady(readyMap: MapView) {
        map = readyMap
        val overlay = MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                p ?: return false
                return onMapTap(p)
            }
            override fun longPressHelper(p: GeoPoint?): Boolean = false
        })
        tapOverlay = overlay
        readyMap.overlays.add(0, overlay)   // index 0: markers' own click handlers win first
        redraw()
    }

    fun onMapDestroyed() {
        pins.values.forEach { it.marker = null }
        tapOverlay = null
        map = null
    }

    /**
     * Tap-to-place state. **Currently dormant: nothing calls [beginDrop] since the flight
     * mini-map was locked.** With no pan/zoom there's nothing to aim with, so
     * [FlightActivity]'s drop-pin button places at the camera look-point instead. [onMapTap] therefore always short-circuits to false and map taps fall through
     * to the markers' own click handlers, which is why the [MapEventsOverlay] registration above
     * is harmless rather than a broken affordance.
     *
     * Kept rather than deleted because the machinery is correct and future marker/AR work
     * may want an explicit placement mode again. If it stays unused, delete it.
     */
    val isPlacing: Boolean get() = pendingAffiliation != null

    fun beginDrop(affiliation: Affiliation) { pendingAffiliation = affiliation }
    fun cancelDrop() { pendingAffiliation = null }

    /** Dropped pins as (lat, lon, alt, icon-res, label) — for the AR overlay (when ported). */
    fun pinsForAr(): List<ArPin> = pins.values.map {
        ArPin(it.lat, it.lon, it.alt, it.affiliation.res, it.name)
    }
    data class ArPin(val lat: Double, val lon: Double, val alt: Double, val iconRes: Int, val label: String)

    /** What goes on the wire for a pin's height. CoT's `hae` is height above the ELLIPSOID and
     *  a pin holds DTED MSL, so N is added (without it every receiver drew the pin
     *  ~10 m low). With no N yet, or no elevation, CoT's own "not known", which a receiver
     *  draws on its terrain. Never NaN and never 0.0 — the first is not XML and the second is
     *  the ellipsoid. */
    private fun cotAltitude(alt: Double): Double {
        val n = TakBridgeHolder.geoidSeparationM
        return if (alt.isFinite() && n != null) alt + n
            else com.taklite.client.tak.CotBuilder.UNKNOWN.toDouble()
    }

    private fun onMapTap(p: GeoPoint): Boolean {
        val aff = pendingAffiliation ?: return false
        pendingAffiliation = null
        // A map tap knows no elevation — osmdroid hands back 0.0, which is sea level, and the
        // overlay used to believe it. Unknown is NaN; the overlay places
        // an unknown pin on the terrain under it.
        placeAt(aff, p.latitude, p.longitude, Double.NaN)
        return true
    }

    /**
     * Place a marker at an explicit position (also used by hot keys, which drop at the
     * camera look-point). Draws + persists, then Send/Don't-Send auto-shows — unless a
     * Data Sync feed is joined, in which case it auto-publishes.
     *
     * [autoSend] removes the question for the routes that must not stop for one: the crosshair
     * touch-and-hold and the controller's long press exist for the moment when the pilot has no
     * attention to give to a dialog. The marker button keeps the question, because there the
     * pilot has already stopped to choose a type.
     *
     * @return the name given to the marker, so the caller can say it back to the pilot. The name
     *   comes from [nextMarkerName], which is the ONE shared counter — each route must come
     *   through here rather than build a name of its own.
     */
    fun placeAt(
        aff: Affiliation,
        lat: Double,
        lon: Double,
        alt: Double,
        autoSend: Boolean = false,
    ): String {
        val pin = Pin(
            key = "${aff.id}-${System.nanoTime()}",
            lat = lat, lon = lon, alt = alt,
            affiliation = aff, name = nextMarkerName(), transmitted = false,
        )
        pins[pin.key] = pin
        AppLog.i(TAG, "pin placed: ${pin.key} \"${pin.name}\" (${aff.label}) @ $lat,$lon " +
            "alt=$alt autoSend=$autoSend")
        draw(pin)
        save()
        when {
            autoSend -> sendPin(pin)
            TakMissionManager.joinedFeed != null && TakManager.getInstance().isConnected ->
                sendPin(pin)
            else -> ui?.askSend(aff.label) { send -> if (send) sendPin(pin) }
        }
        return pin.name
    }

    /**
     * Next marker name: the AIRCRAFT's callsign plus a two-digit sequence, e.g. `EVO2-07`.
     *
     * WHY NOT "Friendly Marker". That was the earlier scheme and it does not survive
     * contact with a real shared picture: with two aircraft up, a "Friendly Marker" from one is
     * indistinguishable from a "Friendly Marker" from the other, on everyone else's screen. The
     * name now answers "who dropped this, and in what order" without anyone having to ask.
     *
     * The counter runs 00..99 and wraps (design decision). It deliberately does NOT reset
     * per flight — a number that restarts every takeoff would produce two different `-03`s in
     * one afternoon, which is the collision this scheme exists to prevent. Wrapping at 99 can
     * still collide eventually, but only after a hundred markers, by which point the old ones
     * have passed their 72-hour stale time and left everyone's screen.
     *
     * Falls back to a bare sequence if the bridge has never started (no callsign yet) rather
     * than inventing an aircraft name — a marker labelled with a guessed callsign is worse than
     * one labelled with none.
     */
    private fun nextMarkerName(): String {
        val prefs = appContext?.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
        if (prefs == null) {
            // Not fatal, but it must not pass silently: with no prefs the counter cannot
            // persist, so EVERY marker would be named -00 and they would collide with each
            // other on the shared picture. That is exactly the failure this scheme exists to
            // prevent, so say so loudly rather than shipping quiet duplicates.
            AppLog.w(TAG, "marker sequence unavailable (init() not called?) — naming may collide")
        }
        val n = synchronized(this) {
            val next = ((prefs?.getInt(KEY_MARKER_SEQ, -1) ?: -1) + 1) % 100
            prefs?.edit()?.putInt(KEY_MARKER_SEQ, next)?.apply()
            next
        }
        val seq = "%02d".format(n)
        val cs = TakBridgeHolder.droneCallsign?.trim()?.takeIf { it.isNotEmpty() }
        return if (cs != null) "$cs-$seq" else seq
    }

    private fun onPinTap(pin: Pin): Boolean {
        ui?.pinMenu(pin.affiliation.label,
            onSend = { sendPin(pin) },
            onDelete = { deletePin(pin) })
        return true
    }

    /**
     * Sends (or RE-sends) a pin, always under its own [Pin.cotUid].
     *
     * Reusing the uid is the whole mechanism behind move/rename/retype/re-send: every other TAK
     * client updates the marker it already has rather than drawing a second one. Minting a
     * fresh uid per send — which this used to do via `sendMarker`/`sendMarkerToMission` — would
     * turn a single moved marker into a trail of duplicates on everyone else's picture.
     */
    private fun sendPin(pin: Pin) {
        val tak = TakManager.getInstance()
        if (!tak.isConnected) {
            AppLog.w(TAG, "pin ${pin.key} not sent — TAK not connected")
            ui?.toast("Pin saved locally — not connected to TAK")
            return
        }
        val uid = pin.cotUid ?: TakManager.newMarkerUid()
        val feed = TakMissionManager.joinedFeed
        val sent = tak.sendMarkerWithUid(
            uid, pin.lat, pin.lon, cotAltitude(pin.alt), pin.affiliation.id, pin.name, "", feed)
        if (sent == null) {
            AppLog.w(TAG, "pin ${pin.key} send failed")
            ui?.toast("Pin saved locally — send failed")
            return
        }
        val isFirstSend = pin.cotUid == null
        pin.cotUid = sent
        pin.transmitted = true
        save()
        if (feed != null) {
            // Register the uid in the feed's /contents so it shows for feed subscribers. Only
            // on first send — re-registering an existing uid is a no-op at best.
            if (isFirstSend) TakMissionManager.publishUid(sent)
            AppLog.i(TAG, "pin sent to feed '$feed': ${pin.key} uid=$sent")
            ui?.toast("Sent ${pin.name} to feed '$feed'")
        } else {
            AppLog.i(TAG, "pin sent to TAK: ${pin.key} uid=$sent")
            ui?.toast("Sent ${pin.name} to TAK")
        }
        if (isFirstSend) scheduleRebroadcast(pin)
    }

    /**
     * Re-sends a pin once, [REBROADCAST_DELAY_MS] after its first successful send.
     *
     * WHY. CoT markers are fire-and-forget: a teammate whose ATAK
     * connects thirty seconds after the drop never receives it, and nobody in the air knows
     * that happened. The old remedy was for the pilot to notice and hit Re-send by hand, which
     * means the fix depended on the one person least able to spare the attention.
     *
     * Re-sending under the SAME uid is what makes this safe. Clients that already have the
     * marker move it in place — to the identical position, so nothing visibly happens — and
     * clients that missed it draw it for the first time. Nobody sees a duplicate.
     *
     * Deliberately ONCE, not a repeating heartbeat. One extra packet closes the join window
     * that actually occurs in practice; a permanent re-broadcast of every marker ever dropped
     * would grow without bound for the rest of the flight.
     *
     * Skipped if the pin is deleted before the timer fires, and skipped for the quick marker,
     * which is re-sent on every re-aim anyway.
     */
    private fun scheduleRebroadcast(pin: Pin) {
        if (pin.quick) return
        val key = pin.key
        rebroadcast.postDelayed({
            val live = pins[key] ?: run {
                AppLog.v(TAG, "rebroadcast skipped — pin $key no longer exists")
                return@postDelayed
            }
            val tak = TakManager.getInstance()
            val uid = live.cotUid
            if (uid == null || !tak.isConnected) {
                AppLog.w(TAG, "rebroadcast skipped for $key — uid=$uid connected=${tak.isConnected}")
                return@postDelayed
            }
            // Same uid, current values: this is an UPDATE, not a second marker. Reads whatever
            // the pin holds NOW, so a rename or move inside the delay window is carried too.
            tak.sendMarkerWithUid(uid, live.lat, live.lon, cotAltitude(live.alt), live.affiliation.id,
                live.name, "", TakMissionManager.joinedFeed)
            AppLog.i(TAG, "rebroadcast \"${live.name}\" uid=$uid (catches late-joining clients)")
        }, REBROADCAST_DELAY_MS)
    }

    private val rebroadcast = android.os.Handler(android.os.Looper.getMainLooper())

    /** Long enough for a late client to finish connecting, short enough to still be the same
     *  tactical moment. */
    private const val REBROADCAST_DELAY_MS = 60_000L

    private fun deletePin(pin: Pin) {
        pin.marker?.let { map?.overlays?.remove(it) }
        pins.remove(pin.key)
        map?.invalidate()
        save()
        AppLog.v(TAG, "pin deleted: ${pin.key}")
        ui?.toast("Pin deleted")
    }

    /** True if [uid] is one of OUR dropped pins' CoT uids — the server echoes sent pins back
     *  as ordinary contacts, and the AR overlay must not draw those twice. */
    fun ownsUid(uid: String): Boolean {
        for (p in pins.values) if (p.cotUid == uid) return true
        return false
    }

    // ---- Quick drop (reticle tap) ----

    /** The quick marker's callsign, so the team learns to recognise one "what I'm looking at
     *  right now" marker per aircraft. */
    /**
     * Callsign pool for the quick marker. One is drawn at random on first run and kept for the
     * life of the install — see [quickName].
     *
     * The point is TEAM LEGIBILITY across multiple aircraft. Every install used to place a
     * marker with the same fixed name, so two aircraft flying together put two identically
     * labelled markers on the shared map and nobody could tell whose was whose without opening
     * each one. A per-install callsign makes them distinguishable at a glance.
     *
     * Format is one letter and three digits, deliberately uniform so the labels read as a set
     * and stay short enough to sit under a map icon without crowding it. The letter "H" is not
     * used anywhere in the pool.
     */
    private val QUICK_CALLSIGNS = listOf(
        "E419", "B312", "B320", "A259", "A266", "A239",
        "K023", "B022", "V933", "C709", "F201", "I101",
        "D042", "J092", "A130", "F104", "K087", "L058",
        "S052", "S034", "S051", "M310", "G227", "N379",
    )

    private const val KEY_QUICK_CALLSIGN = "quick_marker_callsign"
    /** Two-digit marker sequence, 00..99, wrapping. Survives restarts on purpose. */
    private const val KEY_MARKER_SEQ = "marker_seq"

    /**
     * This install's quick-marker callsign. Chosen once, then persisted — the same reasoning as
     * the pilot's own uid: an identity that changes under the team is worse than a dull one.
     *
     * Falls back to the first entry if there is no context yet, so a caller that runs before
     * [init] still gets a usable label rather than an empty string.
     */
    val QUICK_NAME: String
        get() {
            cachedQuickName?.let { return it }
            val ctx = appContext ?: run {
                // Should not happen: init() resolves this. Loud, because silently
                // handing back a pool member here is what let a marker be created
                // with a name this install never actually drew.
                AppLog.w(TAG, "quick marker callsign read before init — using fallback")
                return QUICK_CALLSIGNS.first()
            }
            val prefs = ctx.getSharedPreferences(PREFS_CALLSIGN, Context.MODE_PRIVATE)
            var name = prefs.getString(KEY_QUICK_CALLSIGN, null)
            if (name == null || name !in QUICK_CALLSIGNS) {
                name = QUICK_CALLSIGNS.random()
                prefs.edit().putString(KEY_QUICK_CALLSIGN, name).apply()
                AppLog.i(TAG, "quick marker callsign for this install: $name")
            }
            cachedQuickName = name
            return name
        }

    @Volatile private var cachedQuickName: String? = null
    private const val PREFS_CALLSIGN = "takpilot2_quickmarker"

    /** The one reticle-tap pin, or null. Exactly one may exist at a time — that is the whole
     *  point: it's a live pointer the pilot keeps re-aiming, not a trail of numbered pins. */
    fun quickPin(): PinInfo? = pins.values.firstOrNull { it.quick }?.let {
        PinInfo(it.key, it.name, it.affiliation, it.lat, it.lon, it.alt)
    }

    /**
     * Place the quick-drop pin. No-op returning false if one already exists — the caller
     * decides how to tell the pilot, since the answer ("long-press to re-aim it") is UI text.
     * Always [Affiliation.UNKNOWN]: a marker placed in under a second is by definition
     * unverified.
     */
    fun placeQuick(lat: Double, lon: Double, alt: Double): Boolean {
        if (quickPin() != null) return false
        val pin = Pin(
            key = "quick-${System.nanoTime()}",
            lat = lat, lon = lon, alt = alt,
            affiliation = Affiliation.UNKNOWN, name = QUICK_NAME, transmitted = false,
            cotUid = null, quick = true,
        )
        pins[pin.key] = pin
        AppLog.i(TAG, "quick drop placed: ${pin.key} @ $lat,$lon alt=$alt")
        draw(pin)
        save()
        sendPin(pin)
        return true
    }

    /**
     * Re-aim the quick-drop pin at the current look point, keeping its uid so every other TAK
     * client moves the existing marker instead of collecting duplicates. False if there is none.
     */
    fun moveQuick(lat: Double, lon: Double, alt: Double): Boolean {
        val pin = pins.values.firstOrNull { it.quick } ?: return false
        moveToLookPoint(pin.key, lat, lon, alt)
        return true
    }

    // ---- Markers-list management API (drop-pin long-press panel) ----

    /** Newest first. */
    fun listPins(): List<PinInfo> = pins.values.reversed().map {
        PinInfo(it.key, it.name, it.affiliation, it.lat, it.lon, it.alt)
    }

    /** Moves a pin to the camera look-point and re-sends it under its existing uid, so it
     *  MOVES on every other client rather than spawning a second marker. */
    fun moveToLookPoint(key: String, lat: Double, lon: Double, alt: Double) {
        val pin = pins[key] ?: return
        pin.lat = lat; pin.lon = lon; pin.alt = alt
        AppLog.i(TAG, "pin moved: $key -> $lat,$lon alt=$alt")
        redrawPin(pin)
        save()
        if (pin.cotUid != null) sendPin(pin)
    }

    fun rename(key: String, newName: String) {
        val pin = pins[key] ?: return
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return
        pin.name = trimmed
        AppLog.i(TAG, "pin renamed: $key -> $trimmed")
        redrawPin(pin)   // the label is drawn into the icon bitmap, so it must be rebuilt
        save()
        if (pin.cotUid != null) sendPin(pin)
    }

    fun changeType(key: String, aff: Affiliation) {
        val pin = pins[key] ?: return
        pin.affiliation = aff
        AppLog.i(TAG, "pin retyped: $key -> ${aff.label}")
        redrawPin(pin)
        save()
        if (pin.cotUid != null) sendPin(pin)
    }

    fun resend(key: String) {
        val pin = pins[key] ?: return
        sendPin(pin)
    }

    fun delete(key: String) {
        val pin = pins[key] ?: return
        deletePin(pin)
    }

    /**
     * Clears every dropped pin from THIS map. Local-only, deliberately: there is no CoT
     * "delete" that reliably removes a marker from other clients, so each one stays on the
     * server until it goes stale — see CotBuilder's MARKER_STALE_DURATION_MS for the time. The
     * confirmation dialog says so rather than implying a team-wide wipe.
     */
    fun clearAll() {
        val n = pins.size
        for (pin in pins.values) pin.marker?.let { map?.overlays?.remove(it) }
        pins.clear()
        map?.invalidate()
        save()
        AppLog.i(TAG, "cleared all $n dropped pin(s) (local only)")
        ui?.toast("Cleared $n marker(s) from your map")
    }

    /** Rebuilds one pin's map marker in place — needed after anything that changes its
     *  position, label or affiliation, since the icon bitmap bakes in the name and shape. */
    private fun redrawPin(pin: Pin) {
        pin.marker?.let { map?.overlays?.remove(it) }
        pin.marker = null
        draw(pin)
    }

    private fun redraw() {
        for (pin in pins.values) { pin.marker = null; draw(pin) }
    }

    private fun draw(pin: Pin) {
        val m = map ?: return
        try {
            val bmp: Bitmap = TakMapMarkers.makeMilIcon(pin.affiliation.res, pin.name)
            val marker = Marker(m).apply {
                position = GeoPoint(pin.lat, pin.lon, if (pin.alt.isFinite()) pin.alt else 0.0)
                title = pin.name
                icon = android.graphics.drawable.BitmapDrawable(appContext?.resources, bmp)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                setOnMarkerClickListener { _, _ -> onPinTap(pin) }
                infoWindow = null
            }
            m.overlays.add(marker)
            pin.marker = marker
            m.invalidate()
        } catch (e: Exception) {
            AppLog.w(TAG, "draw ${pin.key} failed: ${e.message}")
        }
    }

    // ---- Persistence ----
    private fun save() {
        val ctx = appContext ?: return
        try {
            val arr = JSONArray()
            for (p in pins.values) {
                arr.put(JSONObject().apply {
                    put("key", p.key); put("lat", p.lat); put("lon", p.lon)
                    // JSONObject refuses NaN: an unknown elevation is simply absent.
                    if (p.alt.isFinite()) put("alt", p.alt)
                    put("aff", p.affiliation.id); put("name", p.name); put("tx", p.transmitted)
                    // Persisted so a restart doesn't orphan the marker's TAK identity — without
                    // it, the next re-send/move would mint a new uid and duplicate the marker
                    // on every other client instead of updating the one already there.
                    p.cotUid?.let { put("uid", it) }
                    // Persisted so a restart can't demote the quick pin to an ordinary one —
                    // which would silently make a SECOND quick drop possible.
                    if (p.quick) put("qk", true)
                })
            }
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY, arr.toString()).apply()
        } catch (e: Exception) { AppLog.w(TAG, "save failed: ${e.message}") }
    }

    private fun load() {
        val ctx = appContext ?: return
        try {
            val json = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return
            val arr = JSONArray(json)
            pins.clear()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val aff = Affiliation.values().firstOrNull { it.id == o.getString("aff") } ?: Affiliation.FRIENDLY
                val key = o.getString("key")
                // Absent, or the 0.0 that older files wrote for "unknown", both read as unknown.
                // A pin genuinely at 0 m MSL loses nothing: the overlay looks up the terrain
                // under an unknown pin, which is 0 there too.
                val alt = o.optDouble("alt", Double.NaN).takeIf { it.isFinite() && it != 0.0 } ?: Double.NaN
                pins[key] = Pin(key, o.getDouble("lat"), o.getDouble("lon"), alt,
                    aff, o.optString("name", "Marker"), o.optBoolean("tx", false),
                    cotUid = o.optString("uid", "").takeIf { it.isNotEmpty() },
                    quick = o.optBoolean("qk", false))
            }
        } catch (e: Exception) { AppLog.w(TAG, "load failed: ${e.message}") }
    }
}
