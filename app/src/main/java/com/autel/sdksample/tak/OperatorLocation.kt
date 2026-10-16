package com.autel.sdksample.tak

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import com.taklite.util.AppLog

/**
 * The CONTROLLER's own position — the pilot on the ground, not the aircraft.
 *
 * ## Why this exists
 *
 * The application published a position for the aircraft only. The operator marker was a single
 * registration message sent at connect with hardcoded `0, 0`, and nothing ever replaced it —
 * `TakManager.sendPLI`, the method that takes a real position, had no caller anywhere in the
 * source. So the team saw the pilot's callsign at latitude 0, longitude 0 until it went stale.
 *
 * ## Why `getLastKnownLocation` is not enough
 *
 * That method reads a CACHE. Nothing fills the cache unless some application on the device has
 * asked for position updates. On a controller where no other application does, it returns null
 * for ever, which is what made this look like a permission fault when it was not one.
 * **A real `requestLocationUpdates` is the only thing that fills it.**
 *
 * ## Behaviour
 *
 * Both providers are requested. GPS is accurate but needs a view of the sky and takes time to
 * fix; the network provider is coarse but answers immediately and indoors. The most recent fix
 * from either wins, because for a marker that says "the pilot is here" a coarse position now is
 * more use than an exact one in two minutes.
 *
 * If the permission is not granted this stays silent and [latest] stays null. The caller then
 * publishes the pilot marker in the "position not known" form, so that the controller stays in
 * the contact list of the other clients and can still receive markers (design decision).
 * See `TakManager.sendPilotPLI`. Previously the caller published nothing without a fix.
 */
object OperatorLocation {
    private const val TAG = "OperatorLocation"

    /** Update interval. Matched to the telemetry push so a fix is never much older than the
     *  message that carries it, and a pilot on foot does not move far in two seconds. */
    private const val MIN_INTERVAL_MS = 2_000L
    private const val MIN_DISTANCE_M = 2f

    /**
     * Oldest cached fix the seed below will accept.
     *
     * The cache is device-wide and has no expiry: it can hold a fix from days ago, left by any
     * application at wherever the controller last had a sky view. Publishing that as the pilot's
     * position is worse than publishing nothing, because the marker REFRESHES — every push
     * resets its stale time, so a wrong position never ages off the team's map the way a
     * silence would. Ten minutes keeps the seed's actual purpose (a message before the first
     * fix of THIS session) and rejects the depot-from-last-Tuesday case.
     */
    private const val MAX_SEED_AGE_MS = 10 * 60_000L

    @Volatile private var latestFix: Location? = null
    private var manager: LocationManager? = null
    private var listening = false

    /** Most recent fix from either provider, or null if there has not been one. */
    val latest: Location? get() = latestFix

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val prev = latestFix
            latestFix = location
            // First fix only — after that this would be one line every two seconds for the
            // whole flight, and the position is already in every outgoing message.
            if (prev == null) {
                AppLog.i(TAG, "first controller fix: %.6f,%.6f from %s (±%.0fm)"
                    .format(location.latitude, location.longitude, location.provider,
                        location.accuracy))
            }
        }

        // STILL NO BEHAVIOUR HERE, ON PURPOSE: a provider going briefly unavailable must not
        // discard a good fix — a slightly old pilot position is far more useful than none, and
        // the fix carries its own timestamp for anyone who cares.
        //
        // They log now. If the receiver is turned off mid-flight the fix simply
        // stops refreshing, the pilot marker keeps publishing the last known point, and nothing
        // said why. These two lines are the difference between "the marker stopped following
        // me" and knowing the receiver went away underneath it.
        override fun onProviderEnabled(provider: String) {
            AppLog.i(TAG, "provider $provider is back")
        }
        override fun onProviderDisabled(provider: String) {
            AppLog.w(TAG, "provider $provider went away — the last fix is kept and the pilot " +
                "marker goes on publishing it, thus the marker stops following the pilot")
        }
        @Deprecated("Required by LocationListener below API 29")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** Idempotent. Safe to call whenever TAK connects. */
    @SuppressLint("MissingPermission")   // guarded by hasPermission immediately below
    @Synchronized
    fun start(context: Context) {
        if (listening) return
        if (!hasPermission(context)) {
            AppLog.w(TAG, "no location permission — the operator position will not be published")
            return
        }
        val lm = context.applicationContext
            .getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        manager = lm
        var any = false
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try {
                if (!lm.isProviderEnabled(provider)) {
                    AppLog.i(TAG, "provider $provider is off")
                    continue
                }
                lm.requestLocationUpdates(provider, MIN_INTERVAL_MS, MIN_DISTANCE_M, listener,
                    Looper.getMainLooper())
                // Seed from the cache so the first message does not have to wait for a fix. This
                // is only useful BECAUSE the request above is now filling that cache — and only
                // safe when the cached fix is recent; see MAX_SEED_AGE_MS.
                lm.getLastKnownLocation(provider)?.let {
                    if (latestFix == null &&
                        System.currentTimeMillis() - it.time < MAX_SEED_AGE_MS) latestFix = it
                }
                any = true
            } catch (t: Throwable) {
                AppLog.w(TAG, "requestLocationUpdates($provider) failed: ${t.message}")
            }
        }
        listening = any
        AppLog.i(TAG, if (any) "controller position updates started" else "no provider available")
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    fun stop() {
        if (!listening) return
        runCatching { manager?.removeUpdates(listener) }
            .onFailure { AppLog.w(TAG, "removeUpdates failed: ${it.message}") }
        listening = false
        // Drop the fix rather than keep it for the next session — a restart hours later must
        // not republish where the pilot USED to be. The next start() re-seeds from the system
        // cache, and its age gate decides whether this session's last fix is still usable.
        latestFix = null
        AppLog.i(TAG, "controller position updates stopped")
    }
}
