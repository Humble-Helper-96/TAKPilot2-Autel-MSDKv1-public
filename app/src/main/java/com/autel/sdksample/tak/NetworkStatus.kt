package com.autel.sdksample.tak

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager

/**
 * One answer to "does this controller have a usable network?". The home screen's wifi line
 * and the Pre-Flight enrollment check share it, so the two can never disagree.
 *
 * THREE STATES, NOT TWO. The middle state is the important one. A controller can hold a wifi
 * association to a hotspot that has no upstream connection. That state caused the field
 * reports of "TAK enrollment failed": the wifi icon looked correct, but
 * the network went nowhere. Thus CONNECTED means the system CONFIRMED internet access
 * (NET_CAPABILITY_VALIDATED — Android's own reachability probe). An attached SSID alone is
 * not sufficient. Association without validation is its own state. Its text tells the pilot
 * that the network is the problem, not the TAK server.
 *
 * Polled, not listener-driven. The only consumers are Home's 1.5 s refresh loop and one
 * check when the pilot presses Enroll & Connect. A registered NetworkCallback would only be
 * one more object to release. A capability read is cheap.
 */
object NetworkStatus {

    enum class State {
        /** Wifi attached AND the system validated internet access through it. */
        CONNECTED,
        /** Wifi attached but not validated — a hotspot with no upstream, a captive portal. */
        NO_INTERNET,
        /** No wifi association at all. */
        OFF,
    }

    data class Snapshot(
        val state: State,
        /** Bare network name, quotes stripped; null when unknown (no association, or the OS
         *  withheld it — SSID needs runtime location permission on this API level). */
        val ssid: String?,
        /** Signal strength 0..4, WifiManager's own bucketing; -1 when not associated. */
        val level: Int,
    ) {
        /** `▂▄▆█` at full strength; always at least one bar while associated, because a pilot
         *  reading an EMPTY meter next to a green dot sees a contradiction. */
        fun bars(): String =
            if (level < 0) "" else BAR_GLYPHS.substring(0, (level + 1).coerceAtMost(4))
    }

    private const val BAR_GLYPHS = "▂▄▆█"

    /** True when any network (wifi or not) has validated internet — the enrollment
     *  precondition. Kept separate from [read] so the enroll path does not care HOW the
     *  controller reaches the server, only that it can. */
    fun hasInternet(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    fun read(context: Context): Snapshot {
        // ⚠ **ASSOCIATION COMES FROM ConnectivityManager, NOT FROM WifiManager** (field
        // report). This used to decide "associated" from `connectionInfo.networkId != -1`,
        // and on Android 10 and later that call is REDACTED without location permission: the
        // framework hands back networkId -1 whatever the radio is really doing. A controller
        // set up with the permission denied therefore showed a red "WIFI: NOT CONNECTED" while
        // it sat on a validated network with TAK connected and video streaming — measured on
        // wlan0, -54 dBm, VALIDATED, the active default network.
        //
        // NetworkCapabilities needs no permission and describes the transport the system is
        // actually using, so the STATE is read from it. WifiInfo is used only for the SSID and
        // the bars, which genuinely do need the permission and already degrade to "connected"
        // with no name — see [Snapshot.ssid].
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val onWifi = caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        val validated = caps != null &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        if (!onWifi) return Snapshot(State.OFF, null, -1)

        // Cosmetic only, and allowed to fail. A redacted WifiInfo gives "<unknown ssid>" and a
        // meaningless rssi; both are filtered, and the line then reads "WIFI: Connected" with
        // no bars rather than claiming something untrue.
        val wifi = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as WifiManager
        val info = runCatching { wifi.connectionInfo }.getOrNull()
        val ssid = info?.ssid?.trim('"')
            ?.takeUnless { it.isEmpty() || it == "<unknown ssid>" || it == "0x" }
        val level = if (info != null && ssid != null)
            WifiManager.calculateSignalLevel(info.rssi, 5) else -1

        return Snapshot(if (validated) State.CONNECTED else State.NO_INTERNET, ssid, level)
    }
}
