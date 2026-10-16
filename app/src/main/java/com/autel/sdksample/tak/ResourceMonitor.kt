package com.autel.sdksample.tak

import android.app.ActivityManager
import android.content.Context
import android.os.Process
import android.os.SystemClock
import com.taklite.client.tak.TakManager
import java.io.File

/**
 * Live memory/CPU/GPU/contact-count diagnostics for the flight-screen overlay (see
 * [AppLog.resourceMonitor]).
 *
 * Added while chasing a sequence of app-process OOM kills near busy airspace: the
 * "known contacts" count (see [TakManager.getTakUsers]) is included deliberately, since a stale
 * CoT-retention bug in [com.taklite.client.tak.CotParser] was found to be holding every distinct
 * ADS-B contact for a MINIMUM of ~10 minutes regardless of how briefly it was actually live — the
 * app showed 161 "known" contacts while a second TAK client showed a handful. That specific bug
 * is fixed, but the count stays on the overlay as the fastest way to see whether it (or a future
 * variant of it) is recurring, alongside the raw memory figures, right up to a crash.
 *
 * CPU/GPU are true instantaneous load, not memory — a `media.swcodec` leak (see [ScreenCaptureEncoder]'s PREFER_SOFTWARE_ENCODER history) was found by
 * watching memory, but a software encoder pegging a core, or a GPU-bound VirtualDisplay path,
 * would show here first and show nowhere in the memory figures at all.
 *
 * GPU is read from this SoC's Adreno sysfs node (`/sys/class/kgsl/kgsl-3d0/`), confirmed readable
 * by an ordinary app process (not just adb shell) on the Smart Controller — verified via
 * `run-as`. That is a device/build property, not an Android guarantee: SELinux locks this down
 * to root on plenty of devices. Every GPU read is wrapped in [runCatching] and the GPU line is
 * omitted entirely (not shown as "—") when unavailable, so this degrades cleanly on hardware
 * that doesn't expose it.
 */
object ResourceMonitor {

    data class Snapshot(
        val sysAvailMb: Int,
        val sysTotalMb: Int,
        val lowMemory: Boolean,
        val appPssMb: Int,
        val heapUsedMb: Int,
        val heapMaxMb: Int,
        val contactCount: Int,
        /** Null on the first sample only — both need a delta against the previous call. */
        val sysCpuPct: Int?,
        val appCpuPct: Int?,
        /** Null if this device/build doesn't expose GPU sysfs to an app process. */
        val gpuBusyPct: Int?,
        val gpuClockMhz: Int?,
    )

    // Previous-sample state for the CPU deltas. SystemClock.elapsedRealtime() (monotonic, immune
    // to RTC/timezone changes) is the wall clock; -1 means "no previous sample yet".
    @Volatile private var lastWallMs: Long = -1
    @Volatile private var lastSysTotalJiffies: Long = -1
    @Volatile private var lastSysIdleJiffies: Long = -1
    @Volatile private var lastAppCpuMs: Long = -1

    fun snapshot(context: Context): Snapshot {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        runCatching { am.getMemoryInfo(memInfo) }

        // Process-wide PSS (Proportional Set Size) — what ActivityManager itself weighs an app's
        // footprint by, so this is the same number the OS's own low-memory decision is based on.
        val pssMb = runCatching {
            am.getProcessMemoryInfo(intArrayOf(Process.myPid())).firstOrNull()
                ?.let { it.totalPss / 1024 } ?: 0
        }.getOrDefault(0)

        val rt = Runtime.getRuntime()
        val heapUsedMb = ((rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)).toInt()
        val heapMaxMb = (rt.maxMemory() / (1024 * 1024)).toInt()

        val contactCount = runCatching { TakManager.getInstance().takUsers.size }.getOrDefault(-1)

        // ---- CPU: delta against the previous snapshot ----
        val nowWallMs = SystemClock.elapsedRealtime()
        val sysJiffies = readSystemCpuJiffies()
        val appCpuMs = Process.getElapsedCpuTime()   // official API: this process's CPU time, in ms

        var sysCpuPct: Int? = null
        var appCpuPct: Int? = null
        if (lastWallMs >= 0) {
            val wallDeltaMs = nowWallMs - lastWallMs
            if (sysJiffies != null && lastSysTotalJiffies >= 0 && wallDeltaMs > 0) {
                val totalDelta = sysJiffies.first - lastSysTotalJiffies
                val idleDelta = sysJiffies.second - lastSysIdleJiffies
                if (totalDelta > 0) {
                    sysCpuPct = (100 * (totalDelta - idleDelta) / totalDelta).toInt().coerceIn(0, 100)
                }
            }
            if (wallDeltaMs > 0) {
                // NOT clamped to 100 — a multi-threaded process can legitimately use more than
                // one core's worth of wall-clock time, same convention `top` uses.
                appCpuPct = (100 * (appCpuMs - lastAppCpuMs) / wallDeltaMs).toInt().coerceAtLeast(0)
            }
        }
        lastWallMs = nowWallMs
        if (sysJiffies != null) { lastSysTotalJiffies = sysJiffies.first; lastSysIdleJiffies = sysJiffies.second }
        lastAppCpuMs = appCpuMs

        return Snapshot(
            sysAvailMb = (memInfo.availMem / (1024 * 1024)).toInt(),
            sysTotalMb = (memInfo.totalMem / (1024 * 1024)).toInt(),
            lowMemory = memInfo.lowMemory,
            appPssMb = pssMb,
            heapUsedMb = heapUsedMb,
            heapMaxMb = heapMaxMb,
            contactCount = contactCount,
            sysCpuPct = sysCpuPct,
            appCpuPct = appCpuPct,
            gpuBusyPct = readGpuBusyPct(),
            gpuClockMhz = readGpuClockMhz(),
        )
    }

    /** (total, idle+iowait) jiffies from /proc/stat's aggregate "cpu " line, or null if unreadable. */
    private fun readSystemCpuJiffies(): Pair<Long, Long>? = runCatching {
        val line = File("/proc/stat").bufferedReader().use { it.readLine() } ?: return null
        // "cpu  57354 18884 56145 282250 1808 6151 3171 0 0 0"
        // fields: user nice system idle iowait irq softirq steal guest guest_nice
        val fields = line.trim().split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
        if (fields.size < 4) return null
        val idle = fields[3] + (fields.getOrElse(4) { 0L })
        val total = fields.sum()
        total to idle
    }.getOrNull()

    private fun readGpuBusyPct(): Int? = runCatching {
        File("/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage")
            .readText().trim().takeWhile { it.isDigit() }.toIntOrNull()
    }.getOrNull()

    private fun readGpuClockMhz(): Int? = runCatching {
        (File("/sys/class/kgsl/kgsl-3d0/gpuclk").readText().trim().toLong() / 1_000_000L).toInt()
    }.getOrNull()

    /**
     * Five short strings for a one-line, evenly-spaced overlay row (SYS / APP / CPU / GPU / TAK),
     * one per fixed cell — see the flight layout's `flightResourceMonitorRow`. Always five
     * entries, in this order, even when a value is unavailable (GPU shows "—" rather than the
     * cell disappearing), so the row's cell positions never shift while it's being watched.
     */
    fun formattedSegments(context: Context): List<String> {
        val s = snapshot(context)
        val lowFlag = if (s.lowMemory) " ⚠" else ""
        val gpu = if (s.gpuBusyPct != null || s.gpuClockMhz != null) {
            (s.gpuBusyPct?.let { "$it%" } ?: "—") + (s.gpuClockMhz?.let { " ${it}MHz" } ?: "")
        } else "—"
        return listOf(
            "SYS ${s.sysAvailMb}/${s.sysTotalMb}MB$lowFlag",
            "APP ${s.appPssMb}MB/${s.heapUsedMb}MB",
            "CPU ${s.sysCpuPct?.let { "$it%" } ?: "—"}/${s.appCpuPct?.let { "$it%" } ?: "—"}",
            "GPU $gpu",
            "TAK ${s.contactCount}",
        )
    }
}
