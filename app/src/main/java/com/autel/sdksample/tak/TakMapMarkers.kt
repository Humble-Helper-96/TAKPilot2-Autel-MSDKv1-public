package com.autel.sdksample.tak

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import com.taklite.util.AppLog
import com.autel.sdksample.R
import com.taklite.client.tak.TakManager
import com.taklite.client.tak.TakUser
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

/**
 * Draws inbound TAK CoT contacts/markers on the osmdroid flight map.
 *
 * No cross-module map hook is needed — [FlightActivity] owns the osmdroid [MapView] and hands
 * it here directly via [onMapReady]. This object holds the listener flow, icon generation,
 * persistence of received 2525 markers, and local-hide of inbound markers.
 */
object TakMapMarkers {
    private const val TAG = "TakMapMarkers"

    /** Air-track course is rounded to this before it reaches the icon cache — see [courseBucket]. */
    private const val COURSE_BUCKET_DEG = 15.0

    /**
     * Symbol sizes on the mini-map, in dp.
     *
     * **The map is only 180dp wide on the Smart Controller**, so every dp here is a real share of
     * it. At the previous sizes a 32dp marker plus its label was about 53dp tall — 29% of the map
     * height — and a few markers in one area merged into a single mass.
     *
     * Reduced after WIDE zoom went 13 -> 15. That change quartered
     * the ground area on screen, so the same symbols became four times as dense.
     *
     * Kept as separate constants on purpose: tuning one must not silently move the others.
     */
    private const val MIL_ICON_DP = 14f    // shared markers AND the pilot's own dropped markers
    private const val AIR_ICON_DP = 12f    // ADS-B traffic — context, not something acted on
    private const val PLI_DOT_DP = 10f     // team position dots
    /** Alpha of a stale 2525 frame, 0-255. Grey and faded, with the label still readable.
     *  Shared with ArOverlayView so the map and the AR view age a frame the same way. */
    internal const val STALE_ALPHA = 150
    /** Callsign label under a symbol. Small, because a long callsign makes the bitmap wider than
     *  the icon itself (`w = maxOf(size, labelW)`) and that width is what actually crowds the map. */
    private const val LABEL_SP = 8f

    private var map: MapView? = null
    private val markers = HashMap<String, Marker>()
    private val iconKeys = HashMap<String, String>()
    private val hidden = HashSet<String>()
    private var listenerRegistered = false
    private val iconCache = HashMap<String, BitmapDrawable>()
    private var appContext: Context? = null

    // Received MARKERS we persist so they survive restarts. PLI contacts are NOT persisted — they
    // re-broadcast live and would otherwise ghost. Air tracks are never persisted either; see
    // CotParser.isPersistentType for why that one is a safety guard. Keyed by uid.
    private val savedMarkers = LinkedHashMap<String, SavedMarker>()
    private data class SavedMarker(
        val uid: String, val lat: Double, val lon: Double, val alt: Double,
        val type: String, val callsign: String, val team: String,
        /** When this marker was last heard from. Drives [MARKER_RETENTION_MS] eviction. */
        val lastSeen: Long,
    )

    /**
     * How long a shared marker is kept with no update, before it is evicted from the store.
     *
     * A marker anyone still cares about is being re-broadcast, so it never ages out; only genuinely
     * abandoned ones drop. The bound exists because unbounded contact retention once OOM-killed
     * the flight app in the air, and this store previously had NO cap, NO age field
     * and NO eviction at all — it only ever grew.
     */
    private const val MARKER_RETENTION_MS = 72L * 60 * 60 * 1000

    /**
     * Hard ceiling on stored markers, independent of age. [savedMarkers] is insertion-ordered, so
     * eviction is oldest-first. A second bound on top of the age limit, because a busy net could
     * in principle deliver more markers inside 72 hours than is sensible to hold.
     */
    private const val MAX_SAVED_MARKERS = 1000

    /**
     * Store schema version. Entries carrying it were saved after the persistence rule became
     * "the sender set `archived`"; entries without it were saved by an earlier build whose gate
     * was the 2525 icon lookup, which also accepted platforms and live clients.
     */
    private const val SCHEMA_ARCHIVED_VERIFIED = 1

    /**
     * Conservative re-validation for an entry saved BEFORE `archived` was checked.
     *
     * The old gate accepted anything `milMarkerRes` drew a frame for, which pulled in ADS-B ground
     * vehicles (`a-f-G-E-V`, uid `ICAO-…`) and CloudTAK's own users (`a-f-G-E-V-C`). Left alone
     * those would be restored as permanent, which is the failure this whole change exists to
     * prevent — the old file would make the new rule pointless.
     *
     * A placed marker is a BARE affiliation-plus-domain type: `a-{f,h,n,u}-G` with nothing after
     * it. The trailing qualifiers on `…-G-E-V` say equipment/vehicle — a platform, not a point.
     * Verified against the real store: this keeps all four genuine markers and drops all sixteen
     * platform/client entries.
     */
    private fun isLegacyPlacedMarker(type: String?): Boolean {
        if (type == null) return false
        if (type.startsWith("b-m-p-s-p-")) return false          // SPI, never a placed marker
        if (type.startsWith("b-m-p-")) return true
        val parts = type.split("-")
        if (parts.size != 3 || parts[0] != "a" || parts[2] != "G") return false
        return parts[1] in setOf("f", "h", "n", "u")
    }

    /** Call once (e.g. on app/TAK init) so inbound contacts accumulate before the map opens. */
    fun install(context: Context) {
        appContext = context.applicationContext
        loadSavedMarkers()
        registerListener()
    }

    /** Called by FlightActivity when its osmdroid map is created. */
    fun onMapReady(readyMap: MapView) {
        map = readyMap
        resyncExisting()
        TakDropMarkers.onMapReady(readyMap)
    }

    /** Called by FlightActivity in onDestroy — marker handles belong to the dead map. */
    fun onMapDestroyed() {
        markers.clear()
        iconKeys.clear()
        iconCache.clear()
        map = null
        TakDropMarkers.onMapDestroyed()
    }

    private fun registerListener() {
        if (listenerRegistered) return
        listenerRegistered = true
        TakManager.getInstance().addListener(object : TakManager.TakUserListener {
            override fun onTakUserUpdated(user: TakUser) = upsert(user)
            override fun onTakUserRemoved(uid: String) = onContactAgedOut(uid)
            override fun onTakUserDeleted(uid: String) = forget(uid)
            override fun onTakConnectionChanged(connected: Boolean) {}
        })
    }

    private fun SavedMarker.toUser(): TakUser =
        TakUser(uid, callsign, lat, lon, alt, team, "", Long.MAX_VALUE).also {
            it.type = type
            // Marks it exempt from the stale sweep, exactly as the parser would have. Without
            // this a restored marker would be swept ~10 minutes after the map opened.
            it.isPersistent = true
        }

    /**
     * A contact aged out of [TakManager]'s live map.
     *
     * If we hold a SAVED copy, the marker stays: it aged out of the live contact list, but it was
     * never a track — somebody shared it deliberately, and the only things that should remove it
     * are an explicit delete, a local delete, or the 72-hour eviction.
     *
     * This is the defect the pilot actually saw. The old code called [remove] unconditionally, so a
     * marker still sitting in the saved store was stripped off the map anyway, and did not come
     * back until the flight screen was re-opened.
     */
    private fun onContactAgedOut(uid: String) {
        val saved = savedMarkers[uid]
        if (saved != null && !hidden.contains(uid)) {
            upsert(saved.toUser())
        } else {
            remove(uid)
        }
    }

    /**
     * The network explicitly deleted this item. Forget it everywhere, including the saved store —
     * otherwise a marker the team retracted would come back at the next restart.
     */
    private fun forget(uid: String) {
        AppLog.v(TAG, "inbound marker deleted by the network: $uid")
        remove(uid)
        if (savedMarkers.remove(uid) != null) saveSavedMarkers()
    }

    /** True only while [resyncExisting] replays the saved set — see the guard in [upsert]. */
    private var restoring = false

    private fun resyncExisting() {
        if (map == null) return
        markers.clear()
        iconKeys.clear()
        AppLog.i(TAG, "map ready: restoring ${savedMarkers.size} saved marker(s), " +
            "${TakManager.getInstance().takUsers.count()} live contact(s)")
        try {
            // Restore saved markers onto the map AND back into the live contact map. The AR
            // overlay reads TakManager.takUsers directly, so without the second half a restored
            // marker was on the map and invisible in AR — and a network delete for it would not
            // have matched anything either.
            // finally, not a plain assignment: an exception mid-restore would otherwise leave
            // `restoring` true for the life of the process, silently disabling every later save.
            restoring = true
            try {
                for (s in savedMarkers.values) {
                    if (hidden.contains(s.uid)) continue
                    val u = s.toUser()
                    TakManager.getInstance().restorePersistentUser(u)
                    upsert(u)
                    AppLog.v(TAG, "restored saved marker: ${s.uid} (${s.callsign}) type=${s.type}")
                }
            } finally {
                restoring = false
            }
            for (user in TakManager.getInstance().takUsers) {
                // Same ADS-B ceiling the AR overlay applies — a target on one view and
                // not the other would be worse than either rule on its own.
                if (ArSettings.isAboveAirTrafficCeiling(user.type, user.alt)) {
                    remove(user.uid)     // may already be on the map from below the ceiling
                    continue
                }
                upsert(user)
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "resync failed: ${e.message}")
        }
    }

    private fun upsert(user: TakUser) {
        val m = map ?: return
        // A contact at 0,0 has no position. It is not drawn — and a marker it HAD is taken off.
        // The parser keeps a live client at 0,0 (a teammate with no fix, see
        // CotParser). Each such report refreshes the contact, thus the stale sweep never runs
        // for it. A plain `return` here left that teammate's old dot at their last real
        // position, fresh-coloured, for as long as their receiver stayed cold. The dot comes
        // back at their next real fix.
        if (user.lat == 0.0 && user.lon == 0.0) {
            remove(user.uid)
            return
        }
        // A LOCAL DELETE LASTS UNTIL THE SENDER SHARES THE MARKER AGAIN.
        //
        // Reaching this line with a hidden uid means a NEW inbound message arrived for it:
        // hideInbound calls forgetUser, thus nothing re-drives an old contact through here.
        // A teammate sharing it again is a deliberate act, and it must get through. The pilot
        // deleted one marker from their own map; they did not ask never to be told about it
        // again.
        //
        // ⚠ In an earlier version the delete was permanent, and a re-share was dropped in
        // silence. A teammate could re-send a hazard marker to a pilot who had cleared it an hour
        // before, and that pilot never saw it and got no indication.
        //
        // This also makes the two delete paths agree at last. clearAllShared already refused to
        // add uids to [hidden], for exactly this reason — "A marker that IS shared again
        // returns, which is the correct outcome" — while a single delete did the opposite.
        //
        // The cost: a client that re-broadcasts on a timer would bring a deleted marker back.
        // CloudTAK and TAK Aware send on placement and on edit, not on a cycle — measured over
        // 30 minutes and confirmed again in a later test, where one marker arrived
        // 4 times in 33 minutes and every arrival was a deliberate send.
        if (hidden.contains(user.uid)) {
            AppLog.i(TAG, "shared again — showing a marker deleted here: ${user.uid}")
            hidden.remove(user.uid)
            saveSavedMarkers()
        }
        val pos = GeoPoint(user.lat, user.lon, user.alt)
        val iconKey = iconKeyFor(user)
        try {
            val existing = markers[user.uid]
            if (existing != null) {
                existing.position = pos
                if (iconKeys[user.uid] != iconKey) {
                    existing.icon = iconFor(iconKey, user)
                    iconKeys[user.uid] = iconKey
                }
                existing.title = user.callsign
            } else {
                val marker = Marker(m).apply {
                    position = pos
                    title = user.callsign
                    icon = iconFor(iconKey, user)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    setOnMarkerClickListener { _, _ -> onInboundMarkerTap(user.uid) }
                    infoWindow = null
                }
                m.overlays.add(marker)
                markers[user.uid] = marker
                iconKeys[user.uid] = iconKey
                AppLog.v(TAG, "new inbound marker: ${user.uid} (${user.callsign}) type=${user.type}")
            }
            m.invalidate()
            // Persist what the PARSER judged persistent, not a second opinion about the icon.
            // This used to gate on `milMarkerRes(type) != null`, which is the 2525 frame lookup —
            // so a b-m-p-* map point from ATAK or iTAK was drawn but never saved, and vanished on
            // restart. CotParser.isPersistentType is the single decision now.
            // Only a LIVE event refreshes the store. A restore from disk must not: `restoring`
            // is set while resyncExisting replays the saved set, and without that guard every
            // trip through the flight screen would stamp lastSeen = now and the 72-hour eviction
            // could never fire — a marker would live for ever as long as the app kept opening.
            if (user.isPersistent && !hidden.contains(user.uid) && !restoring) {
                // Re-put so insertion order tracks recency: LinkedHashMap keeps the ORIGINAL
                // position on a plain overwrite, which would make the count-cap evict the most
                // recently refreshed marker instead of the stalest.
                savedMarkers.remove(user.uid)
                savedMarkers[user.uid] = SavedMarker(
                    user.uid, user.lat, user.lon, user.alt,
                    user.type ?: "", user.callsign ?: user.uid, user.team ?: "Cyan",
                    System.currentTimeMillis())
                evictOldMarkers()
                // Coalesced, not immediate — this is the once-per-inbound-event path.
                scheduleSaveSavedMarkers()
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "upsert ${user.uid} failed: ${e.message}")
        }
    }

    /** For the AR overlay / dedupe checks: is this uid locally deleted? */
    fun isHidden(uid: String): Boolean = hidden.contains(uid)

    /** One shared marker, for the flight screen's marker list. */
    data class SharedInfo(
        val uid: String, val name: String, val type: String,
        val lat: Double, val lon: Double, val alt: Double,
    )

    /** Markers other TAK users shared, newest first — the same order [TakDropMarkers.listPins]
     *  uses, so a merged list reads consistently. */
    fun listShared(): List<SharedInfo> =
        savedMarkers.values.reversed()
            .filterNot { hidden.contains(it.uid) }
            .map { SharedInfo(it.uid, it.callsign, it.type, it.lat, it.lon, it.alt) }

    /**
     * Re-broadcasts a marker the TEAM shared, under its OWN uid and its OWN CoT type.
     *
     * Re-sending a received marker is ordinary TAK client behaviour, and the uid is what makes
     * it an update rather than a duplicate — see [TakManager.sendMarkerWithCotType].
     *
     * THE TYPE IS PASSED THROUGH, NOT RE-DERIVED. The shared store admits bare
     * `a-{f,h,n,u}-G` markers and `b-m-p-*` marker points (see [isLegacyPlacedMarker]). Only
     * the first four can be expressed as one of this app's affiliations, so deriving a type
     * would rewrite every ATAK waypoint as a friendly ground marker for the whole team.
     *
     * KNOWN LIMIT, ACCEPTED: [SavedMarker] does not keep the original remarks, so a re-sent
     * marker carries this aircraft's "Dropped by …" instead of whatever the originator wrote.
     * Keeping remarks would mean a new persisted field and a migration of the stored file. This
     * is a deliberate omission, not an oversight.
     *
     * @return true if it went to the server, false if not connected or the uid is unknown.
     */
    fun resendShared(uid: String): Boolean {
        val m = savedMarkers[uid] ?: return false
        val sent = TakManager.getInstance().sendMarkerWithCotType(
            m.uid, m.lat, m.lon, m.alt, m.type, m.callsign, "",
            TakMissionManager.joinedFeed, m.type)
        AppLog.i(TAG, "shared marker re-send: $uid type=${m.type} -> ${sent != null}")
        return sent != null
    }

    /** Removes ONE shared marker from this aircraft only. Public entry point for the marker
     *  list; the map's own tap handler uses the same path. */
    fun deleteShared(uid: String) = hideInbound(uid)

    /**
     * Clears every shared marker from this aircraft.
     *
     * These are NOT added to the locally-deleted set. "Clear my map now" is the intent here.
     *
     * In practice they stay gone: CloudTAK and TAK Aware send a marker when it is placed, not on
     * a repeating cycle (measured over 30 minutes). A marker that IS shared again
     * returns, which is the correct outcome.
     *
     * A single delete behaves the same way — see [upsert]. The two paths used to
     * disagree about the same question, and this one had the right answer.
     */
    fun clearAllShared(): Int {
        val uids = savedMarkers.keys.toList()
        for (uid in uids) {
            remove(uid)
            TakManager.getInstance().forgetUser(uid)
        }
        savedMarkers.clear()
        saveSavedMarkers()
        map?.invalidate()
        AppLog.i(TAG, "cleared ${uids.size} shared marker(s) from this aircraft")
        return uids.size
    }

    /**
     * Tapping an INBOUND marker (from another TAK user) → offer a LOCAL delete (removes it
     * from THIS map only; stays on the server / other clients).
     */
    fun onInboundMarkerTap(uid: String): Boolean {
        val title = markers[uid]?.title ?: uid
        AppLog.v(TAG, "inbound marker tapped: $uid ($title)")
        TakDropMarkers.ui?.pinMenu(title,
            onSend = { /* received markers aren't re-sent from here */ },
            onDelete = { hideInbound(uid) },
            sendLabel = null)
        return true
    }

    /** Hides one inbound marker on THIS map. It comes back if the sender shares it again —
     *  see the note in [upsert]. It does not go to the server or to any other client. */
    private fun hideInbound(uid: String) {
        AppLog.v(TAG, "inbound marker hidden locally: $uid")
        hidden.add(uid)
        markers.remove(uid)?.let { mk -> map?.overlays?.remove(mk) }
        iconKeys.remove(uid)
        savedMarkers.remove(uid)
        // Also drop it from the live contact map. The `hidden` set stops it being DRAWN, but a
        // marker is persistent and therefore exempt from the stale sweep — so without this it
        // would occupy a contact slot for the life of the process, invisible and never released.
        // Small individually; it is the same accumulate-forever shape as the in-flight OOM
        // described at [MARKER_RETENTION_MS].
        TakManager.getInstance().forgetUser(uid)
        saveSavedMarkers()
        map?.invalidate()
        TakDropMarkers.ui?.toast("Marker removed from your map")
    }

    private fun remove(uid: String) {
        try {
            markers.remove(uid)?.let { mk -> map?.overlays?.remove(mk); map?.invalidate() }
        } catch (e: Exception) {
            AppLog.w(TAG, "remove $uid failed: ${e.message}")
        }
        iconKeys.remove(uid)
    }

    // ---- Persistence of received 2525 markers (+ locally-deleted uids) across restarts ----
    private const val PREFS = "takpilot2_recv_markers"

    /**
     * Drops markers nobody has refreshed in [MARKER_RETENTION_MS], then trims to
     * [MAX_SAVED_MARKERS] oldest-first.
     *
     * The store had no bound of any kind before this. Unbounded contact retention once
     * OOM-killed the flight app in the air, so a retention extension has to arrive
     * with its limit, not after it.
     */
    private fun evictOldMarkers() {
        val cutoff = System.currentTimeMillis() - MARKER_RETENTION_MS
        val aged = savedMarkers.entries.filter { it.value.lastSeen < cutoff }.map { it.key }
        for (uid in aged) savedMarkers.remove(uid)

        // Insertion order is recency (see the re-put in upsert), so the head is the stalest.
        while (savedMarkers.size > MAX_SAVED_MARKERS) {
            val oldest = savedMarkers.keys.firstOrNull() ?: break
            savedMarkers.remove(oldest)
        }
        if (aged.isNotEmpty()) AppLog.i(TAG, "evicted ${aged.size} marker(s) unseen for 72h")
    }

    /**
     * Coalesced save, for the high-frequency path only.
     *
     * [saveSavedMarkers] re-serialises the ENTIRE store — up to 1000 objects — into JSON and
     * writes SharedPreferences. `persistIfMarker` called it once per persistent inbound event,
     * which under a joined feed is every marker in the feed every 10 s, continuously, on the
     * main thread.
     *
     * A "has anything changed?" test cannot help here the way it can in [stage]: `lastSeen` is
     * genuinely new on every delivery, so every event is a real change. But it is a change
     * nothing needs on disk promptly — its only reader is the 72-hour eviction, for which
     * seconds of precision are meaningless. So the write is coalesced instead: many events
     * collapse into one save. Discrete pilot and network actions (hide, clear, forget, a
     * re-share) keep calling [saveSavedMarkers] directly and still persist at once.
     */
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var saveScheduled = false
    private val saveRunnable = Runnable {
        saveScheduled = false
        saveSavedMarkers()
    }

    private fun scheduleSaveSavedMarkers() {
        if (saveScheduled) return
        saveScheduled = true
        main.postDelayed(saveRunnable, SAVE_COALESCE_MS)
    }

    /** Long enough that a 10 s feed poll collapses several markers into one write, short enough
     *  that little is lost if the process dies — and what would be lost is only `lastSeen`
     *  freshness, which feeds a 72-hour timer. */
    private const val SAVE_COALESCE_MS = 8_000L

    private fun saveSavedMarkers() {
        val ctx = appContext ?: return
        try {
            val arr = org.json.JSONArray()
            for (s in savedMarkers.values) {
                // JSONObject.put throws on NaN or infinity, and one such entry made EVERY later
                // save fail until the app restarted. A marker with no usable
                // position is skipped; a non-finite height is left out (load defaults it).
                if (!s.lat.isFinite() || !s.lon.isFinite()) continue
                arr.put(org.json.JSONObject().apply {
                    put("uid", s.uid); put("lat", s.lat); put("lon", s.lon)
                    if (s.alt.isFinite()) put("alt", s.alt)
                    put("type", s.type); put("cs", s.callsign); put("team", s.team)
                    put("seen", s.lastSeen)
                    // Provenance: this entry was saved by a build that verified `archived` on the
                    // wire. Entries without it predate that rule — see loadSavedMarkers.
                    put("v", SCHEMA_ARCHIVED_VERIFIED)
                })
            }
            val hid = org.json.JSONArray().apply { hidden.forEach { put(it) } }
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("markers", arr.toString())
                .putString("hidden", hid.toString())
                .apply()
        } catch (e: Exception) { AppLog.w(TAG, "saveSavedMarkers failed: ${e.message}") }
    }

    private fun loadSavedMarkers() {
        val ctx = appContext ?: return
        try {
            val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            p.getString("hidden", null)?.let {
                val h = org.json.JSONArray(it)
                for (i in 0 until h.length()) hidden.add(h.getString(i))
            }
            p.getString("markers", null)?.let {
                val arr = org.json.JSONArray(it)
                savedMarkers.clear()
                val onDisk = arr.length()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val uid = o.getString("uid")
                    if (hidden.contains(uid)) continue
                    // Purge what earlier builds saved and no longer should have. CotParser now
                    // drops METAR at the door, but that does nothing about the 136 stations
                    // already written to this file — they would sit here for ever otherwise.
                    if (uid.startsWith("METAR-")) continue
                    // Re-validate anything saved before `archived` was the gate. Without this the
                    // old file re-introduces exactly what the new rule rejects — ADS-B ground
                    // vehicles and CloudTAK users — and restores them as PERMANENT.
                    val type = o.getString("type")
                    if (o.optInt("v", 0) < SCHEMA_ARCHIVED_VERIFIED
                        && !isLegacyPlacedMarker(type)) continue
                    // An entry written before `seen` existed reads as NOW, not 0. Defaulting to 0
                    // would make every marker the pilot already has look 56 years stale and wipe
                    // the whole store on the first launch after the update.
                    savedMarkers[uid] = SavedMarker(uid, o.getDouble("lat"), o.getDouble("lon"),
                        o.optDouble("alt", 0.0), type,
                        o.optString("cs", uid), o.optString("team", "Cyan"),
                        o.optLong("seen", System.currentTimeMillis()))
                }
                evictOldMarkers()
                // Write back immediately when the load dropped anything (METAR purge, age
                // eviction, a hidden uid). Otherwise the file keeps carrying entries we ignore
                // until a new marker happens to arrive and trigger a save — which on a quiet net
                // may be never.
                if (savedMarkers.size != onDisk) {
                    AppLog.i(TAG, "saved markers: $onDisk on disk -> ${savedMarkers.size} kept")
                    saveSavedMarkers()
                }
            }
        } catch (e: Exception) { AppLog.w(TAG, "loadSavedMarkers failed: ${e.message}") }
    }

    // ---- Icon resolution — matches taklite's createTakMarkerIcon exactly ----

    private val density get() = (appContext?.resources?.displayMetrics?.density ?: 2.5f)

    private fun iconKeyFor(user: TakUser): String {
        // Air tracks key on course alone. The symbol carries no callsign, team colour or stale
        // treatment, so every aircraft at the same course bucket IS the same bitmap — one cache
        // entry per bucket rather than one per aircraft.
        // A UAS is NOT keyed here: it draws a 2525 frame with its callsign under it, so it
        // takes the ordinary key below like every other framed contact.
        if (isAirTrack(user.type) && !user.isUas) {
            return if (user.hasCourse()) "air|${courseBucket(user.course)}" else "air|nocourse"
        }
        val team = (user.team ?: "Cyan").lowercase()
        val stale = if (user.isStale) "S" else "A"
        val drone = if (user.isDrone) "D" else "U"
        // A live client takes a team dot, never a 2525 frame. The parser decides what is a live
        // client — see CotParser.isLiveClient. This key must agree with iconFor.
        val mil = if (user.isLiveClient) 0 else milMarkerRes(user.type) ?: 0
        val uas = if (user.isUas) milAirMarkerRes(user.type) ?: 0 else 0
        // Air tracks bake their course into the bitmap (see makeAirIcon), so the key has to
        // include it — but BUCKETED to COURSE_BUCKET_DEG. Keying on the raw course would mint a
        // fresh bitmap on every position report, since ADS-B course jitters by fractions of a
        // degree; that is an unbounded cache of near-identical bitmaps on the HUD tick.
        val air = when {
            !isAirTrack(user.type) -> "-"
            user.hasCourse() -> "A${courseBucket(user.course)}"
            else -> "AN"
        }
        return "$team|$stale|$drone|$mil|$uas|$air|${user.callsign}"
    }

    /**
     * An inbound contact that is airborne — the CoT type's third field is `A` (air) rather than
     * `G` (ground), e.g. `a-f-A-C-F` from an ADS-B gateway.
     *
     * Same discriminator [ArSettings.categoryFor] uses for the AR overlay's Air Traffic layer,
     * so a contact drawn as an aircraft on the map is the same set the overlay calls air
     * traffic. If one of these changes, change both.
     */
    fun isAirTrack(type: String?): Boolean {
        val parts = type?.split("-").orEmpty()
        return parts.size >= 3 && parts[0] == "a" && parts[2] == "A"
    }

    /** Course rounded to [COURSE_BUCKET_DEG], for the icon cache key. 15 degrees is finer than
     *  a 32px symbol can express and caps the cache at 24 rotations per callsign. */
    private fun courseBucket(course: Double): Int =
        (Math.round(course / COURSE_BUCKET_DEG) * COURSE_BUCKET_DEG).toInt() % 360

    /**
     * MIL-STD-2525 affiliation MARKERS (a-{f,h,n,u}-G, NOT the …-G-U-… unit/PLI form) →
     * frame drawable. Null for PLI/units/drones (those keep the team-colored dot).
     */
    fun milMarkerRes(type: String?): Int? {
        if (type == null) return null
        val parts = type.split("-")
        if (parts.size < 3 || parts[0] != "a" || parts[2] != "G") return null
        val isUnit = parts.size >= 4 && parts[3] == "U"
        if (isUnit) return null
        return when (parts[1]) {
            "f" -> R.drawable.marker_friendly
            "h" -> R.drawable.marker_hostile
            "n" -> R.drawable.marker_neutral
            "u" -> R.drawable.marker_unknown
            else -> null
        }
    }

    /**
     * MIL-STD-2525 affiliation → AIR frame drawable, for a UAS reported by another TAK client.
     *
     * ⚠ **ADS-B TRAFFIC MUST NOT COME THROUGH HERE.** The caller gates on [TakUser.isUas], which
     * the parser sets only when the sender put a `<vehicle>` or `<_uastool>` block on its own
     * report — see CotParser. A manned aircraft from a gateway keeps the white silhouette, which
     * is the whole point of the split: the pilot must be able to tell the other aircraft of the
     * flight from the airliner above it.
     *
     * The frames are the top halves of the ground frames, open at the bottom — the 2525 air
     * shape, and what TAK Aware and CloudTAK draw. Null for a type with no affiliation letter
     * this application knows; the caller then falls back to the silhouette.
     */
    fun milAirMarkerRes(type: String?): Int? {
        val parts = type?.split("-").orEmpty()
        if (parts.size < 3 || parts[0] != "a" || parts[2] != "A") return null
        return when (parts[1]) {
            "f" -> R.drawable.marker_air_friendly
            "h" -> R.drawable.marker_air_hostile
            "n" -> R.drawable.marker_air_neutral
            "u" -> R.drawable.marker_air_unknown
            else -> null
        }
    }

    /** TAK team-name → color, identical to taklite's getTeamColor(). */
    fun teamColor(team: String?): Int {
        if (team == null) return Color.GREEN
        return when (team.lowercase()) {
            "cyan" -> Color.parseColor("#00BCD4")
            "red" -> Color.parseColor("#F44336")
            "blue" -> Color.parseColor("#2196F3")
            "green" -> Color.parseColor("#4CAF50")
            "yellow" -> Color.parseColor("#FFEB3B")
            "white" -> Color.WHITE
            "orange" -> Color.parseColor("#FF9800")
            "magenta" -> Color.parseColor("#E91E63")
            "maroon" -> Color.parseColor("#880E4F")
            "purple" -> Color.parseColor("#9C27B0")
            "dark green" -> Color.parseColor("#2E7D32")
            "teal" -> Color.parseColor("#009688")
            "dark blue" -> Color.parseColor("#1565C0")
            "brown" -> Color.parseColor("#795548")
            else -> Color.GREEN
        }
    }

    private fun iconFor(key: String, user: TakUser): BitmapDrawable {
        iconCache[key]?.let { return it }
        val res = milMarkerRes(user.type)
        val bmp = when {
            // Checked BEFORE milMarkerRes so an air track can never fall through to the plain
            // team dot, which is what made ADS-B traffic indistinguishable from a TAK client.
            // A UAS FROM ANOTHER TAK CLIENT TAKES THE 2525 AIR FRAME AND ITS CALLSIGN, not the
            // silhouette. Checked before the silhouette branch, and gated on isUas so ADS-B
            // traffic falls through to it unchanged. The label is kept here although the
            // silhouette drops it: there are one or two of these in a sortie, not a dozen, and
            // WHICH aircraft it is was the pilot's actual question.
            isAirTrack(user.type) && user.isUas && milAirMarkerRes(user.type) != null ->
                makeMilIcon(milAirMarkerRes(user.type)!!, user.callsign ?: user.uid, user.isStale)
            isAirTrack(user.type) -> makeAirIcon(
                if (user.hasCourse()) R.drawable.ic_air_track
                else R.drawable.ic_air_track_nocourse,
                if (user.hasCourse()) courseBucket(user.course).toDouble() else null,
            )
            // A LIVE CLIENT IS A TEAM DOT, whatever its CoT type says. The parser sets the flag
            // and holds the rule and the measurements — see CotParser.isLiveClient. Do not test
            // the type, takv, endpoint or archived here. Each of those alone gave a wrong icon.
            user.isLiveClient -> makeIcon(user.callsign ?: user.uid, user.team, user.isStale)
            res != null -> makeMilIcon(res, user.callsign ?: user.uid, user.isStale)
            else -> makeIcon(user.callsign ?: user.uid, user.team, user.isStale)
        }
        val d = BitmapDrawable(appContext?.resources, bmp)
        iconCache[key] = d
        return d
    }

    /** Render any drawable resource (incl. vectors) to a square bitmap. */
    fun drawableToBitmap(ctx: Context, resId: Int, sizePx: Int): Bitmap? = try {
        val dr = androidx.core.content.ContextCompat.getDrawable(ctx, resId)
        dr?.let {
            val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            it.setBounds(0, 0, sizePx, sizePx)
            it.draw(c)
            bmp
        }
    } catch (e: Exception) { null }

    /** MIL-STD-2525 affiliation frame + callsign label below. */
    /**
     * Air-track icon: the aircraft glyph turned to [courseDeg]. **No callsign label** — on a
     * mini-map a pilot needs to see that traffic is there and which way it is going, not read
     * its registration (design decision). Dropping the text also stops a dozen ADS-B
     * contacts covering the ground the map exists to show.
     *
     * Rotation is baked into the bitmap rather than applied with `Marker.rotation`. That was
     * originally to keep the label upright; with the label gone it still matters, because
     * `Marker.rotation` would also turn the icon's anchor geometry, and baking it keeps the
     * symbol pivoting cleanly about the aircraft's position.
     *
     * **Direction VERIFIED against live traffic** — watched an ADS-B contact turn
     * on the mini-map and the symbol tracked it correctly. `Canvas.rotate(+course)` is
     * clockwise, which matches compass course. Note this does NOT also verify the own-ship
     * chevron in FlightActivity: that one goes through `Marker.rotation = -heading`, which
     * osmdroid then applies as `-mBearing`, a different path with its own sign convention.
     *
     * [courseDeg] null means the sender reported no course; the caller passes the ringed
     * non-directional drawable in that case and nothing is rotated.
     */
    fun makeAirIcon(resId: Int, courseDeg: Double?): Bitmap {
        val ctx = appContext
        val d = density
        val size = (AIR_ICON_DP * d).toInt()
        val icon = ctx?.let { drawableToBitmap(it, resId, size) }
        // A rotated square needs its diagonal, or the wingtips clip at 45 degrees.
        val box = if (courseDeg != null) (size * 1.42f).toInt() else size

        val bmp = Bitmap.createBitmap(box, box, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        if (icon != null) {
            val left = (box - size) / 2f
            if (courseDeg != null) {
                c.save()
                c.rotate(courseDeg.toFloat(), box / 2f, box / 2f)
                c.drawBitmap(icon, left, left, null)
                c.restore()
            } else {
                c.drawBitmap(icon, left, left, null)
            }
        }
        return bmp
    }

    fun makeMilIcon(resId: Int, callsign: String, isStale: Boolean = false): Bitmap {
        val ctx = appContext
        val d = density
        val size = (MIL_ICON_DP * d).toInt()
        val icon = ctx?.let { drawableToBitmap(it, resId, size) }
        // A stale marker draws grey, the same as a stale team dot in makeIcon. Before this the
        // frame looked the same fresh or stale, and the stale sweep re-drew an identical bitmap.
        val iconPaint = if (isStale) Paint().apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
            alpha = STALE_ALPHA
        } else null

        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = LABEL_SP * d; typeface = Typeface.DEFAULT_BOLD
        }
        val tw = text.measureText(callsign)
        val fm = text.fontMetrics
        val th = fm.descent - fm.ascent
        val gap = (d * 3).toInt(); val padH = (4 * d).toInt(); val padV = (d * 2).toInt()
        val labelW = tw.toInt() + padH * 2
        val labelH = th.toInt() + padV * 2
        val w = maxOf(size, labelW)
        val h = size + gap + labelH

        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        if (icon != null) c.drawBitmap(icon, (w - size) / 2f, 0f, iconPaint)

        val labelLeft = (w - labelW) / 2f
        val labelTop = (size + gap).toFloat()
        c.drawRoundRect(labelLeft, labelTop, labelLeft + labelW, labelTop + labelH, d * 3, d * 3,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(160, 0, 0, 0) })
        c.drawText(callsign, labelLeft + padH, labelTop + padV - fm.ascent, text)
        return bmp
    }

    /** Colored dot + callsign label — 1:1 port of taklite's createTakMarkerIcon. */
    private fun makeIcon(callsign: String, team: String?, isStale: Boolean): Bitmap {
        val color = if (isStale) Color.GRAY else teamColor(team)
        val d = density
        val iconSize = (PLI_DOT_DP * d).toInt()
        val r = iconSize / 2f

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.WHITE
            textSize = LABEL_SP * d
            typeface = Typeface.DEFAULT_BOLD
        }
        val textWidth = textPaint.measureText(callsign)
        val fm = textPaint.fontMetrics
        val textHeight = fm.descent - fm.ascent
        val gap = (d * 3).toInt()
        val textPadH = (d * 3).toInt()
        val textPadV = (d * 1.5f).toInt()
        val labelW = textWidth.toInt() + textPadH * 2
        val labelH = textHeight.toInt() + textPadV * 2
        val bmpWidth = maxOf(iconSize, labelW)
        val bmpHeight = iconSize + gap + labelH

        val bmp = Bitmap.createBitmap(bmpWidth, bmpHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val cx = bmpWidth / 2f
        val cr = r - 1

        canvas.drawCircle(cx, r, cr, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; style = Paint.Style.FILL
        })
        canvas.drawCircle(cx, r, cr, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = d * 1.5f
        })

        val labelLeft = (bmpWidth - labelW) / 2f
        val labelTop = (iconSize + gap).toFloat()
        canvas.drawRoundRect(labelLeft, labelTop, labelLeft + labelW, labelTop + labelH,
            d * 3, d * 3, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Color.argb(140, 0, 0, 0) })
        canvas.drawText(callsign, labelLeft + textPadH, labelTop + textPadV - fm.ascent, textPaint)
        return bmp
    }
}
