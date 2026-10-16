package com.autel.sdksample.tak

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * What this controller still has to be granted before the application can do its job.
 *
 * ⚠ **A CONTROLLER FLEW A 12.5-HOUR MISSION WITH LOCATION DENIED AND NOBODY KNEW.** Android
 * denies silently at setup. The aircraft flew, the video streamed and the
 * markers worked — only the PILOT was missing, published at 0,0 in the "position not known"
 * form for the whole mission. The single sign was a log line nobody was reading. The home card
 * now asks this object and says the answer out loud.
 *
 * ## It asks only for what this device actually needs
 *
 * ⚠ **A PERMISSION THE APPLICATION DOES NOT NEED MUST NOT BE COUNTED**, or the line reads
 * DENIED on a controller where nothing is wrong and a pilot learns to ignore it. Measured on
 * Android 11 controllers:
 *
 *  - **Storage is NOT needed.** [com.taklite.util.AppLog] and [FlightPathLogger] both write
 *    through MediaStore from API 29; the `File` path is a pre-Q fallback. That controller had
 *    both storage permissions denied and still wrote the whole mission's logs AND its flight
 *    records. So storage is required here ONLY below API 29, where it genuinely gates writing.
 *  - **Notifications** are only a runtime permission from API 33. Below that the foreground
 *    service posts without asking.
 *
 * Both are computed rather than listed, so a newer controller is handled without this file
 * being edited again.
 */
object AppPermissions {

    /** Location is its own question because other code gates on it directly — the pilot marker,
     *  the home-point reset, and "Use My Location" on Pre-Flight. */
    fun hasLocation(context: Context): Boolean =
        granted(context, Manifest.permission.ACCESS_FINE_LOCATION) ||
            granted(context, Manifest.permission.ACCESS_COARSE_LOCATION)

    /**
     * Everything still missing, in the order the pilot should be asked. Empty means granted.
     *
     * Android shows one dialog per permission in the array, in order, so location comes first:
     * it is the one with a consequence a pilot can be caught by.
     */
    fun missing(context: Context): List<String> {
        val out = mutableListOf<String>()
        if (!hasLocation(context)) {
            out += Manifest.permission.ACCESS_FINE_LOCATION
            out += Manifest.permission.ACCESS_COARSE_LOCATION
        }
        // ⚠ PRE-Q ONLY — see the note on this object. On API 29 and later the log archive and
        // the flight records go through MediaStore and need nothing granted.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            !granted(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        ) {
            out += Manifest.permission.WRITE_EXTERNAL_STORAGE
            out += Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !granted(context, Manifest.permission.POST_NOTIFICATIONS)
        ) {
            out += Manifest.permission.POST_NOTIFICATIONS
        }
        return out
    }

    fun allGranted(context: Context): Boolean = missing(context).isEmpty()

    private fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
