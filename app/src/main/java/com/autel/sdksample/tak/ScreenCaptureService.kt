package com.autel.sdksample.tak

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.taklite.util.AppLog

/**
 * Foreground service that hosts the screen-capture RTSP stream.
 *
 * **Android requires a foreground service of type `mediaProjection` to be running BEFORE
 * MediaProjection is used** (API 29+, which covers the Smart Controller's Android 11). So the
 * order here is: start foreground, then build the projection from the permission result, then
 * hand it to [VideoStreamerHolder.startScreenCapture]. The projection lifecycle — and this
 * service — live for as long as the stream does.
 *
 * This app targets SDK 29, where the FOREGROUND_SERVICE_MEDIA_PROJECTION *permission* is not
 * required (SDK 34 would need extra Android 14 handling). The permission is
 * declared in the manifest anyway so a future targetSdk bump does not silently break capture.
 */
class ScreenCaptureService : Service() {

    private var projection: MediaProjection? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            AppLog.i(TAG, "stop requested — tearing down projection + service")
            teardown()
            stopSelf()
            return START_NOT_STICKY
        }

        // Re-encode the SAME projection at whatever profile the prefs now say. This is why the
        // quality picker can apply instantly: the expensive, permission-gated part is the
        // MediaProjection, and it survives a stream stop — VideoStreamerHolder.stop() releases
        // the encoder and its VirtualDisplay but deliberately never calls projection.stop(), so
        // the pilot is not shown a system consent dialog again mid-flight.
        //
        // ⚠ This holds because the app targets SDK 29. From Android 14 a MediaProjection token is
        // SINGLE-USE — a second createVirtualDisplay on it throws — so a targetSdk bump past 33
        // must re-request permission here instead of reusing `projection`.
        if (intent?.action == ACTION_RESTART) {
            val proj = projection
            if (proj == null) {
                AppLog.w(TAG, "restart requested with no live projection — ignoring")
                return START_NOT_STICKY
            }
            AppLog.i(TAG, "restart requested — re-starting stream at the current profile")
            val result = VideoStreamerHolder.startScreenCapture(applicationContext, proj) { ok, msg ->
                AppLog.i(TAG, "stream status: ok=$ok $msg")
                if (!ok) toast(msg)
            }
            if (result != VideoStreamerHolder.StartResult.STARTED) {
                AppLog.w(TAG, "restart refused ($result) — stopping")
                toast("Video stream could not restart")
                teardown()
                stopSelf()
            }
            return START_NOT_STICKY
        }

        AppLog.i(TAG, "starting foreground service (type=mediaProjection, sdk=${Build.VERSION.SDK_INT})")
        startInForeground()

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        val data: Intent? = intent?.getParcelableExtra(EXTRA_DATA)
        if (data == null) {
            AppLog.w(TAG, "no projection data — stopping")
            stopSelf()
            return START_NOT_STICKY
        }

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = runCatching { mpm.getMediaProjection(resultCode, data) }.getOrNull()
        if (proj == null) {
            AppLog.w(TAG, "getMediaProjection returned null — permission unavailable, stopping")
            toast("Screen capture permission unavailable")
            stopSelf()
            return START_NOT_STICKY
        }
        projection = proj
        AppLog.i(TAG, "MediaProjection acquired — handing to VideoStreamerHolder")

        val result = VideoStreamerHolder.startScreenCapture(applicationContext, proj) { ok, msg ->
            AppLog.i(TAG, "stream status: ok=$ok $msg")
            if (!ok) toast(msg)
        }
        if (result != VideoStreamerHolder.StartResult.STARTED) {
            AppLog.w(TAG, "startScreenCapture refused ($result) — stopping")
            toast(
                if (result == VideoStreamerHolder.StartResult.NOT_CONFIGURED)
                    "Configure the video server in Pre-Flight Setup first"
                else "Screen capture could not start"
            )
            teardown()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startInForeground() {
        val channelId = "takpilot2_screen_capture"
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(channelId) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(channelId, "Video Streaming", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("TAKPilot2 — Streaming video")
            .setContentText("Sharing the flight screen to the TAK server")
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    /** App swiped out of recents. A screen capture must not outlive the app that started it —
     *  the pilot has no way to stop it at that point, and it would keep broadcasting their
     *  display. Mirrors [TakForegroundService.onTaskRemoved]. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        AppLog.i(TAG, "app removed from recents — stopping screen capture")
        runCatching { VideoStreamerHolder.stop() }
        teardown()
        stopSelf()
    }

    private fun teardown() {
        runCatching { projection?.stop() }
        projection = null
    }

    override fun onDestroy() {
        AppLog.i(TAG, "onDestroy — projection released, screen capture ended")
        teardown()
        @Suppress("DEPRECATION")
        stopForeground(true)
        super.onDestroy()
    }

    private fun toast(msg: String) {
        android.os.Handler(mainLooper).post {
            Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val NOTIF_ID = 4711
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_DATA = "data"
        private const val ACTION_STOP = "com.autel.sdksample.tak.STOP_SCREEN_CAPTURE"
        private const val ACTION_RESTART = "com.autel.sdksample.tak.RESTART_SCREEN_CAPTURE"

        /** Start capture: call from onActivityResult of the screen-capture permission request. */
        fun start(context: Context, resultCode: Int, data: Intent) {
            val i = Intent(context, ScreenCaptureService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_DATA, data)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        }

        /**
         * Re-start the push at whatever `video_profile` the prefs now hold, reusing the live
         * projection so no permission dialog appears. No-op if capture isn't running.
         */
        fun restart(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, ScreenCaptureService::class.java).setAction(ACTION_RESTART)
                )
            }
        }

        /** Stop capture (also stops the projection). Safe to call when not running. */
        fun stop(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, ScreenCaptureService::class.java).setAction(ACTION_STOP)
                )
            }
        }
    }
}
