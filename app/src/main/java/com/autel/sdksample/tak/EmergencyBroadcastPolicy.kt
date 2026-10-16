package com.autel.sdksample.tak

import android.content.Context
import com.taklite.client.tak.TakManager
import com.taklite.util.AppLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * What the APPLICATION does when an Emergency Broadcast starts, renews or ends: one
 * line in the active flight's record — see [FlightPathLogger.event]. No "who" (design
 * decision); that is recorded outside the application.
 *
 * Installed once at application start, so it runs whichever screen is open, or none — a
 * broadcast that expires while the pilot is on Pre-Flight Setup must still be recorded.
 * [FlightActivity] only paints; it holds no policy.
 *
 * Nothing else happens at the end of a broadcast. The video link simply goes back to being
 * embedded on the Elevated connection only (`TakManager.videoFor`); the stream path is left
 * alone (a token rotation at the end was built and then removed as more than the feature
 * needs).
 */
object EmergencyBroadcastPolicy {
    private const val TAG = "EmergencyBroadcastPolicy"

    private var installed = false

    private val isoUtcFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    private val listener = TakManager.EmergencyBroadcastListener { _, expiresAtEpochMs, reason ->
        FlightPathLogger.event(eventText(reason, expiresAtEpochMs))
    }

    /** Call once, where the other application-wide singletons are initialised. */
    @JvmStatic
    fun install(@Suppress("UNUSED_PARAMETER") context: Context) {
        if (installed) return
        installed = true
        TakManager.getInstance().addEmergencyBroadcastListener(listener)
        AppLog.i(TAG, "installed")
    }

    /** The flight-record line for one event. Pure, so `EmergencyBroadcastPolicyFormatTest`
     *  can pin it. `expiresAtEpochMs` is 0 for every event but a start or a renew. */
    internal fun eventText(reason: String, expiresAtEpochMs: Long): String =
        if (expiresAtEpochMs > 0L) {
            "emergency-broadcast $reason (expires ${isoUtcFormat.format(Date(expiresAtEpochMs))})"
        } else {
            "emergency-broadcast $reason"
        }
}
