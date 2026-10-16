package com.taklite.util

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Drop-in replacement for android.util.Log across the TAK/Autel bridge code.
 * Always forwards to Log.* (adb logcat keeps working identically); additionally
 * appends to a rotating file under filesDir/logs/ while [enabled] is true, AND
 * mirrors the same lines into a user-accessible archive under Downloads/TAKPilot2
 * Logs — so a day in the field can be pulled off the controller without adb or the
 * in-app export flow. The private working copy (what the Debug screen's Clear/
 * Delete act on) is pruned by age (2h). The public archive is bounded by total
 * size instead — [PUBLIC_ARCHIVE_MAX_BYTES] — since size tracks actual log volume,
 * while wall-clock age doesn't (idle stretches produce no data, so a time window
 * is a poor proxy for "how much log data is this"). Oldest files are deleted
 * first whenever a new archive file would push the folder over the cap.
 *
 * ## Lines are buffered, and the public file stays open
 *
 * Originally each line went to the public archive on its own: one MediaStore stream
 * opened, one line written, the stream closed. On Android 10 each of those is a call to the
 * system media scanner. Measured in flight with logging on: the scanner used 120 % of a CPU
 * core, more than the application, and the screen stuttered. With logging off, the same
 * flight was smooth.
 *
 * Now a line goes to a buffer. The buffer goes to both files when it holds [FLUSH_BYTES], or
 * [FLUSH_INTERVAL_MS] after its first line, whichever is first. The public stream is opened
 * once per archive file and stays open until that file is full, logging is turned off, or a
 * crash is written. Thus the scanner sees one open and one close per file, not one per line.
 *
 * ⚠ THREE THINGS MUST NOT WAIT FOR THE TIMER. An error line (E) and a crash trace (FATAL)
 * flush at once — a trace that waits one second is a trace that the crash can take with it.
 * Turning logging off flushes and closes. The Debug screen's Clear and Delete flush first,
 * or they would clear the file and leave the last second of lines to reappear after it.
 * A process that the system KILLS (an OOM kill has no crash handler) can lose the last
 * [FLUSH_INTERVAL_MS] of lines. That is the accepted cost.
 *
 * Vendor-neutral (JDK + Android framework only) so it can live alongside
 * com.taklite.client.tak without breaking that package's no-SDK-imports rule.
 */
object AppLog {
    private const val PREFS_NAME = "app_log_prefs"
    private const val KEY_ENABLED = "debug_logging_enabled"
    private const val KEY_TAK = "debug_logging_tak"
    private const val KEY_RADAR = "debug_logging_radar"
    private const val KEY_RESOURCE_MONITOR = "debug_resource_monitor"
    private const val ACTIVE_FILE_NAME = "app.log"
    private const val MAX_FILE_SIZE_BYTES = 1L * 1024 * 1024
    private const val RETENTION_MS = 2L * 60 * 60 * 1000
    private const val PUBLIC_SUBFOLDER = "TAKPilot2 Logs"
    private const val PUBLIC_ARCHIVE_MAX_BYTES = 10L * 1024 * 1024
    /** The buffer goes to the files at this size. 8 KB is about 60 lines: at the 2 Hz bridge
     *  tick with the TAK subsystem on, that is one or two seconds of log. */
    private const val FLUSH_BYTES = 8 * 1024
    /** …or this long after the buffer's first line, so a quiet period does not hold lines. */
    private const val FLUSH_INTERVAL_MS = 1000L

    private lateinit var appContext: Context
    private lateinit var prefs: SharedPreferences
    private var initialized = false
    private val writeLock = Any()

    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val fileTimestampFormat = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.US)

    // The buffer and its timer. All of these are read and written under [writeLock].
    private val pending = StringBuilder()
    private var pendingBytes = 0
    private var scheduledFlush: ScheduledFuture<*>? = null
    private val flusher: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "AppLog-flush").apply { isDaemon = true }
    }

    // Public archive state — a new timestamped file per rotation, opened lazily. The stream
    // stays open for the life of the file; see the class note.
    private var publicUri: Uri? = null
    private var publicStream: OutputStream? = null
    private var publicLegacyFile: File? = null
    private var publicBytesWritten: Long = 0

    @JvmStatic
    fun init(context: Context) {
        if (initialized) return
        appContext = context.applicationContext
        prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        initialized = true
        sweepExpiredLogs()
    }

    /**
     * The ONE logging switch. Off by default.
     *
     * It turns on the file sink AND full detail together — see [verbose] for why those stopped
     * being two decisions.
     */
    @JvmStatic
    var enabled: Boolean
        get() = initialized && prefs.getBoolean(KEY_ENABLED, false)
        set(value) {
            if (!initialized) return
            prefs.edit().putBoolean(KEY_ENABLED, value).apply()
            // Off: the buffer goes to the files now, and the public file closes, so the
            // archive holds every line up to the switch and the scanner can index the file.
            if (!value) synchronized(writeLock) { flushLocked(closePublic = true) }
        }

    /** Writes the buffer to both files now. For a caller that is about to read the file. */
    @JvmStatic
    fun flush() {
        synchronized(writeLock) { flushLocked(closePublic = false) }
    }

    /**
     * Detail level — now DERIVED from [enabled] and no longer separately settable.
     *
     * It was its own Debug-screen check box: "Standard" wrote only the bridge/TAK call sites,
     * "Detailed" also wrote the whole-app instrumentation from [v] — screen navigation, button
     * presses, per-tick telemetry. The combination that a pilot actually needs is "logging on,
     * everything captured": the reason to turn logging on at all is that something needs
     * diagnosing, and a log that was running in Standard when the interesting thing happened
     * cannot be made detailed after the fact. Standard mode only ever produced a log that had
     * to be re-created.
     *
     * ⚠ **This makes the log files grow faster**, thus the rotating archive holds LESS TIME
     * than it did. If a diagnosis needs a longer history, raise [PUBLIC_ARCHIVE_MAX_BYTES] —
     * do not bring back a quieter mode, because the quiet log is the one that turns out to be
     * missing the line that mattered.
     *
     * The [takLogging] and [radarLogging] filters are untouched and remain the way to cut
     * volume: they suppress whole SUBSYSTEMS, which is a choice about what is being
     * investigated, rather than a choice to record less about it.
     */
    @JvmStatic
    val verbose: Boolean get() = enabled

    /**
     * Whether TAK/CoT-subsystem lines (see [TAK_TAGS]) reach the log file. Default true.
     * Turned off from the Debug screen when diagnosing the app itself: the CoT bridge pushes
     * on a 2s tick and TakManager/CotParser are chatty, which buries lower-volume app logs
     * (video pipeline, camera, DTED) in the tail view.
     *
     * Only filters the FILE sink — logcat still gets everything, so `adb logcat` is unaffected.
     */
    @JvmStatic
    var takLogging: Boolean
        get() = !initialized || prefs.getBoolean(KEY_TAK, true)
        set(value) {
            if (initialized) prefs.edit().putBoolean(KEY_TAK, value).apply()
        }

    /**
     * Whether obstacle-radar lines (see [RADAR_TAGS]) reach the log file. Default **false**.
     *
     * The radar reports continuously while the aircraft is powered, so these are by far the
     * highest-volume lines in the app — enough to bury everything else in the tail view and to
     * churn the log files. Off by default because the radar is almost
     * never what is being diagnosed; turn it on deliberately when it is.
     *
     * Same contract as [takLogging]: FILE sink only, logcat still receives everything.
     */
    @JvmStatic
    var radarLogging: Boolean
        get() = enabled && prefs.getBoolean(KEY_RADAR, false)
        set(value) {
            if (initialized) prefs.edit().putBoolean(KEY_RADAR, value).apply()
        }

    /**
     * Live memory/contact-count overlay on the flight screen (top-left, under the toolbar).
     * Default false — it is a diagnostic aid for chasing memory-pressure crashes, not something
     * a pilot needs in the way during normal flight. Added after a sequence of
     * app-process OOM kills; see [com.autel.sdksample.tak.ResourceMonitor].
     */
    @JvmStatic
    var resourceMonitor: Boolean
        // Gated on [enabled]: the options under the log switch are OPTIONS
        // OF the log, and a checked box with the switch off still showed the resource row on
        // the flight screen. Off means off for all of them.
        get() = enabled && prefs.getBoolean(KEY_RESOURCE_MONITOR, false)
        set(value) {
            if (initialized) prefs.edit().putBoolean(KEY_RESOURCE_MONITOR, value).apply()
        }

    /**
     * Tags owned by the TAK/CoT side of the app, suppressed when [takLogging] is off.
     *
     * Deliberately an explicit set rather than a "starts with Tak" prefix test: several
     * app-side tags would false-positive on that ("TakPilotHomeActivity" is the home screen,
     * "TakConnectActivity" is the whole Pre-Flight Setup screen incl. drone/map/video/DTED
     * settings), and a prefix rule would silently start eating app logs the moment someone
     * names a new class Tak-something. A tag missing from this set fails OPEN — the line
     * still gets logged — which is the safe direction (extra noise, never silent loss).
     * Add new TAK-subsystem tags here.
     */
    private val TAK_TAGS = setOf(
        "AutelTakBridge",     // telemetry -> CoT push, 2s tick — the loudest of the group
        "TakManager",
        "TakClient",
        "CotParser",
        "TakCertEnroller",
        "TakGroupAssigner",
        "TakMissionClient",
        "TakMissionManager",
        "TakAutoConnect",
        "TakForegroundService",
        "TakMapMarkers",
        "TakDropMarkers",
    )

    /**
     * Obstacle-radar tags, hidden from the log file unless [radarLogging] is on.
     *
     * `AutelAvoidance` carries both the per-report radar distances AND the avoidance switch
     * state. That is deliberate and the switch lines are worth keeping — so the filter is
     * applied per LINE by [isRadarNoise] rather than by tag alone, which would throw away the
     * switch changes along with the noise.
     */
    private val RADAR_TAGS = setOf("AutelAvoidance")

    /**
     * True only for the repeating radar-distance readout, false for everything else.
     *
     * MATCHED ON THE MESSAGE, NOT THE LEVEL. The obvious implementation — hide V/D lines from
     * radar tags — silently does nothing here: `AutelAvoidance` emits its per-report distances at
     * **I** level (`radar(clear) F=[...] R=[...]`), the same level as the switch-state lines that
     * must be kept. Filtering by level would have left the noise exactly where it was.
     *
     * So this matches the one prefix that is the repeating readout, and keeps every other
     * AutelAvoidance line — switches accepted or refused, enforcement, airborne skips, warnings.
     * Those are what someone diagnosing an avoidance problem actually needs.
     *
     * If that log line is ever reworded, this filter stops working. It is a prefix match against
     * the emitter in AutelAvoidance.logRadar.
     */
    private fun isRadarNoise(tag: String, msg: String): Boolean =
        tag in RADAR_TAGS && msg.startsWith("radar(")

    /** Verbose-tier detail log: UI actions, navigation, per-tick internals. Only written
     * to file when both [enabled] and [verbose] are on; always forwarded to Log.d. */
    @JvmStatic
    fun v(tag: String, msg: String) {
        Log.d(tag, msg)
        if (verbose) writeToFile("V", tag, msg)
    }

    @JvmStatic fun d(tag: String, msg: String) { Log.d(tag, msg); writeToFile("D", tag, msg) }
    @JvmStatic fun i(tag: String, msg: String) { Log.i(tag, msg); writeToFile("I", tag, msg) }
    @JvmStatic fun w(tag: String, msg: String) { Log.w(tag, msg); writeToFile("W", tag, msg) }
    @JvmStatic fun w(tag: String, msg: String, tr: Throwable) {
        Log.w(tag, msg, tr)
        writeToFile("W", tag, msg + "\n" + Log.getStackTraceString(tr))
    }
    @JvmStatic fun e(tag: String, msg: String) { Log.e(tag, msg); writeToFile("E", tag, msg) }
    @JvmStatic fun e(tag: String, msg: String, tr: Throwable) {
        Log.e(tag, msg, tr)
        writeToFile("E", tag, msg + "\n" + Log.getStackTraceString(tr))
    }

    /** Writes an uncaught-exception trace directly; caller (crash handler) already gates on [enabled]. */
    @JvmStatic
    fun writeCrash(thread: Thread, tr: Throwable) {
        writeToFile("FATAL", "Crash", "Uncaught exception on ${thread.name}\n${Log.getStackTraceString(tr)}")
    }

    @JvmStatic
    fun activeLogFile(): File = File(logDir(), ACTIVE_FILE_NAME)

    @JvmStatic
    fun clearActive() {
        synchronized(writeLock) {
            // Flush first, or the buffered lines land in the file the pilot just cleared.
            flushLocked(closePublic = false)
            try {
                FileWriter(activeLogFile(), false).use { it.write("") }
            } catch (t: Throwable) {
                // Never let logging itself crash the app.
            }
        }
    }

    @JvmStatic
    fun deleteAll() {
        synchronized(writeLock) {
            flushLocked(closePublic = false)
            try {
                logDir().listFiles()?.forEach { it.delete() }
            } catch (t: Throwable) {
            }
        }
    }

    @JvmStatic
    fun sweepExpiredLogs() {
        if (!initialized) return
        try {
            val cutoff = System.currentTimeMillis() - RETENTION_MS
            logDir().listFiles()?.forEach { f ->
                if (f.lastModified() < cutoff) f.delete()
            }
        } catch (t: Throwable) {
        }
    }

    private fun writeToFile(level: String, tag: String, msg: String) {
        if (!enabled) return
        // FATAL (crash traces) is never filtered — losing a crash to a log-noise setting
        // would be the worst possible failure mode for this switch.
        if (level != "FATAL" && !takLogging && tag in TAK_TAGS) return
        if (level != "FATAL" && !radarLogging && isRadarNoise(tag, msg)) return
        val line = buildString {
            append(timestampFormat.format(Date()))
            append(' ').append(level)
            append('/').append(tag).append(": ").append(msg).append('\n')
        }
        synchronized(writeLock) {
            pending.append(line)
            pendingBytes += line.length
            // E and FATAL go to the files NOW — see the class note. FATAL also closes the
            // public file, because the process is about to end and nothing else will.
            val urgent = level == "E" || level == "FATAL"
            if (urgent || pendingBytes >= FLUSH_BYTES) {
                flushLocked(closePublic = level == "FATAL")
            } else if (scheduledFlush == null) {
                scheduledFlush = flusher.schedule(
                    { synchronized(writeLock) { flushLocked(closePublic = false) } },
                    FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS,
                )
            }
        }
    }

    /**
     * Writes the buffer to both files. Caller holds [writeLock].
     *
     * @param closePublic also close the public stream after the write. The next flush opens a
     *   new archive file. Used when the process is about to end (a crash) or logging is off.
     */
    private fun flushLocked(closePublic: Boolean) {
        scheduledFlush?.cancel(false)
        scheduledFlush = null
        if (pending.isNotEmpty()) {
            val chunk = pending.toString()
            pending.setLength(0)
            pendingBytes = 0
            try {
                val active = activeLogFile()
                if (active.exists() && active.length() > MAX_FILE_SIZE_BYTES) {
                    rotate(active)
                }
                FileWriter(active, true).use { it.append(chunk) }
            } catch (t: Throwable) {
            }
            writePublic(chunk)
        }
        if (closePublic) closePublicLocked()
    }

    /** Closes the public stream. The next write opens a new archive file. Caller holds [writeLock]. */
    private fun closePublicLocked() {
        runCatching { publicStream?.close() }
        publicStream = null
        publicUri = null
    }

    private fun rotate(active: File) {
        val rotated = File(active.parentFile, "app-${fileTimestampFormat.format(Date())}.log")
        active.renameTo(rotated)
    }

    private fun logDir(): File {
        val dir = File(appContext.filesDir, "logs")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    // ---- Public archive: Downloads/TAKPilot2 Logs — capped by total size, not age ----

    /** [chunk] is one or more whole lines — the buffer. Caller holds [writeLock]. */
    private fun writePublic(chunk: String) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) writePublicMediaStore(chunk)
            else writePublicLegacy(chunk)
        } catch (t: Throwable) {
            // Public archive is best-effort — never let it take down the private log path.
            // A stream that failed is dropped; the next flush opens a new file.
            closePublicLocked()
        }
    }

    private fun writePublicMediaStore(chunk: String) {
        val resolver = appContext.contentResolver
        if (publicUri == null) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, "app-${fileTimestampFormat.format(Date())}.log")
                // "application/octet-stream" has no canonical extension for MediaProvider to
                // force onto DISPLAY_NAME (unlike "text/plain" -> .txt), so the .log name sticks.
                put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$PUBLIC_SUBFOLDER")
            }
            publicUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            publicBytesWritten = 0
            enforcePublicArchiveCapMediaStore()
        }
        val uri = publicUri ?: return
        // ONE open per file, not one per line. This is the whole reason for the buffer.
        val out = publicStream
            ?: resolver.openOutputStream(uri, "wa")?.also { publicStream = it }
            ?: return
        val bytes = chunk.toByteArray()
        out.write(bytes)
        out.flush()
        publicBytesWritten += bytes.size
        // Full: close it, so the scanner indexes it, and let the next flush start a new file.
        if (publicBytesWritten > MAX_FILE_SIZE_BYTES) closePublicLocked()
    }

    private fun writePublicLegacy(chunk: String) {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), PUBLIC_SUBFOLDER)
        if (!dir.exists()) dir.mkdirs()
        var f = publicLegacyFile
        if (f == null || f.length() > MAX_FILE_SIZE_BYTES) {
            f = File(dir, "app-${fileTimestampFormat.format(Date())}.log")
            publicLegacyFile = f
            enforcePublicArchiveCapLegacy(dir, keep = f)
        }
        FileWriter(f, true).use { it.append(chunk) }
    }

    /** Deletes the oldest archive entries (by DATE_ADDED) until the folder is back under the cap. */
    private fun enforcePublicArchiveCapMediaStore() {
        val resolver = appContext.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.SIZE)
        val relPathPrefix = "${Environment.DIRECTORY_DOWNLOADS}/$PUBLIC_SUBFOLDER%"
        val entries = ArrayList<Pair<Long, Long>>()   // id, size — oldest first via sort order
        resolver.query(
            collection, projection,
            "${MediaStore.Downloads.RELATIVE_PATH} LIKE ?", arrayOf(relPathPrefix),
            "${MediaStore.Downloads.DATE_ADDED} ASC",
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Downloads._ID)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Downloads.SIZE)
            while (c.moveToNext()) entries.add(c.getLong(idCol) to c.getLong(sizeCol))
        }
        var total = entries.sumOf { it.second }
        for ((id, size) in entries) {
            if (total <= PUBLIC_ARCHIVE_MAX_BYTES) break
            runCatching { resolver.delete(ContentUris.withAppendedId(collection, id), null, null) }
            total -= size
        }
    }

    /** Deletes the oldest archive files (by lastModified) until [dir] is back under the cap. */
    private fun enforcePublicArchiveCapLegacy(dir: File, keep: File) {
        val files = dir.listFiles()?.filter { it != keep }?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() } + keep.length()
        for (f in files) {
            if (total <= PUBLIC_ARCHIVE_MAX_BYTES) break
            val len = f.length()
            if (f.delete()) total -= len
        }
    }
}
