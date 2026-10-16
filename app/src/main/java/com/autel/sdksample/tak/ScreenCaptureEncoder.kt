package com.autel.sdksample.tak

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Surface
import android.view.WindowManager
import com.taklite.util.AppLog
import java.nio.ByteBuffer

/**
 * Screen-capture encoder for the outbound RTSP push. The codec is the pilot's Pre-Flight choice — see [VideoCodec].
 *
 * MediaProjection mirrors the whole flight screen — FPV, HUD, map, AR markers, toolbar — into a
 * [VirtualDisplay] sized to the profile, straight into the encoder's input Surface. Two
 * consequences matter operationally:
 *
 *  1. **The team sees what the pilot sees**, not just the camera. That is the point of the
 *     design and the reason this exists.
 *  2. **The stream does not depend on the aircraft.** It mirrors the screen, so a lost link,
 *     a battery swap, or an aircraft that was never connected all leave the push running —
 *     viewers keep seeing the controller (map, last telemetry, whatever the screen shows)
 *     instead of the feed going dead. The aircraft-frame path this replaces could not do that:
 *     no aircraft meant no frames meant no stream at all.
 *
 * It is also cheaper than the decode→scale→encode transcoder it supersedes: there is no second
 * decoder (the screen is already composited pixels) and the scaling happens on the GPU in the
 * VirtualDisplay rather than in a CPU downsample loop.
 */
class ScreenCaptureEncoder(
    context: Context,
    private val mediaProjection: MediaProjection,
    private val profile: TranscodeProfile,
    private val codec: VideoCodec,
    private val onEncoded: (ByteBuffer, MediaCodec.BufferInfo) -> Unit,
    private val onParamsReady: (sps: ByteBuffer, pps: ByteBuffer, vps: ByteBuffer?) -> Unit,
    /**
     * The encoder has STOPPED, not merely slowed — see [ScreenCaptureStallPolicy]. The capture
     * has already been torn down by the time this runs; the caller's job is to stop the push
     * and tell the pilot. ⚠ Called from the drain thread, once, never on the main thread.
     */
    private val onStalled: (ageMs: Long) -> Unit = {},
    /**
     * The capture ENDED without our asking: the system stopped the projection (another app
     * took the screen, or the cast control disconnected it), or the codec failed on the drain
     * thread. Previously neither told anybody: the stream, its url and
     * the "Streaming video" notification stayed up with nothing being captured. At most once;
     * called on the main thread (system stop) or the drain thread (codec error).
     */
    private val onGone: (reason: String) -> Unit = {},
) {
    private val goneReported = java.util.concurrent.atomic.AtomicBoolean(false)
    private fun reportGone(reason: String) {
        if (goneReported.compareAndSet(false, true)) runCatching { onGone(reason) }
    }
    private val outMime: String get() = codec.mime
    private var encoder: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var drainThread: Thread? = null
    @Volatile private var running = false
    /**
     * Monotonic mark of the last REAL encoded buffer, for [ScreenCaptureStallPolicy]. Set at
     * start so a codec that never produces anything at all is still caught; -1 only before
     * [start] has run.
     */
    @Volatile private var lastOutputMs = -1L
    private var encFrameCount = 0
    private var encBytesSinceLog = 0L
    /** Monotonic mark for the achieved-rate line. -1 until the first 150-frame boundary. */
    private var lastRateLogMs = -1L

    // I-frame vs P-frame accounting. This ratio — not the mode the encoder reports back — is the
    // ground truth for whether VBR is doing anything: a legacy OMX component will accept
    // BITRATE_MODE_VBR without complaint and still rate-control like CBR. A full intra frame
    // needs roughly 5-10x an average P-frame; CBR here was delivering ~2.5x, which is the
    // starved keyframe that stream viewers saw as a pulse.
    private var iFrameCount = 0
    private var iFrameBytes = 0L
    private var pFrameCount = 0
    private var pFrameBytes = 0L

    private val screenW: Int
    private val screenH: Int
    private val densityDpi: Int
    private val refreshHz: Float

    init {
        @Suppress("DEPRECATION")
        val display = (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
            .defaultDisplay
        val metrics = DisplayMetrics().also { display.getRealMetrics(it) }
        screenW = metrics.widthPixels
        screenH = metrics.heightPixels
        densityDpi = metrics.densityDpi
        // The panel rate is the INPUT rate to the encoder when the frame-rate cap does not
        // apply — see logCapability(). This controller reports 60.0.
        refreshHz = runCatching { display.refreshRate }.getOrDefault(60f).takeIf { it > 1f } ?: 60f
    }

    /** Registered with the projection so a stop from the system side tears this down too. */
    val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            AppLog.i(TAG, "media projection stopped by system")
            release()
            reportGone("Video stopped: the screen capture was ended. Touch LIVE to start again.")
        }
    }

    fun start(): Boolean {
        // Cap the HEIGHT to the profile and let the width follow, preserving the screen's
        // aspect ratio. The controller is 4:3 (2048x1536), so the Standard tier is 960x720 —
        // NOT 1280x720. maxHeight is a ceiling, not a format.
        var targetH = minOf(profile.maxHeight, screenH)
        var targetW = (screenW.toDouble() / screenH * targetH).toInt()
        targetW -= targetW % 2   // even dims for the encoder
        targetH -= targetH % 2

        logCapability(targetW, targetH)

        return runCatching {
            // Intra refresh is asked for FIRST and given up as a whole if nothing configures
            // with it. A component that does not implement KEY_INTRA_REFRESH_PERIOD must not
            // cost us the stream, and the key is not in the variant ladder because it changes
            // the IDR interval too — the two belong together or not at all.
            val (enc, variant) = configureEncoder(targetW, targetH, USE_INTRA_REFRESH)
                ?: (if (USE_INTRA_REFRESH) {
                        AppLog.w(TAG, "no configuration accepted intra refresh — " +
                            "falling back to periodic IDR")
                        configureEncoder(targetW, targetH, false)
                    } else null)
                ?: throw IllegalStateException("no usable encoder configuration")
            val surface = enc.createInputSurface()
            enc.start()
            encoder = enc
            inputSurface = surface

            mediaProjection.registerCallback(projectionCallback, null)
            virtualDisplay = mediaProjection.createVirtualDisplay(
                "TAKPilot2Stream",
                targetW, targetH, densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface, null, null,
            )

            running = true
            drainThread = Thread({ drainLoop() }, "ScreenCaptureEncoder").apply { start() }
            AppLog.i(TAG, "screen capture [${profile.name}] ${codec.label}: " +
                "${screenW}x$screenH -> " +
                "${targetW}x$targetH @ ${profile.fps}fps ${profile.bitrateBps / 1000}kbps, " +
                "${idrIntervalS(USE_INTRA_REFRESH)}s IDR" +
                (if (USE_INTRA_REFRESH) ", intra refresh ${intraRefreshPeriodFrames()}f" else "") +
                " — variant: $variant")
            true
        }.onFailure {
            AppLog.e(TAG, "screen capture start failed: ${it.message}", it)
            release()
        }.getOrDefault(false)
    }

    /**
     * Configures the encoder, dropping optional format keys until one is accepted.
     *
     * **This is not defensive padding — the controller's encoder genuinely rejects the full
     * format.** `OMX.qcom.video.encoder.hevc` on this build is a legacy OMX component (not
     * Codec2), and it failed `configureCodec` with error -38 when handed KEY_PROFILE and
     * KEY_MAX_FPS_TO_ENCODER. Legacy OMX components commonly reject KEY_PROFILE unless a
     * matching KEY_LEVEL is supplied, and often do not implement the API-30 max-fps key at all.
     *
     * The order below drops the least important first, so a device that accepts everything
     * still gets CBR and an explicit profile. Which variant won is logged, so a future device
     * tells us what it supports instead of us guessing again.
     *
     * ⚠ **KEY_MAX_FPS_TO_ENCODER outranks KEY_PROFILE in this ladder.**
     * The profile keys are cosmetic here; the frame-rate cap is the only thing that stops the
     * encoder receiving the panel's 60fps. See the rung comment below.
     */
    /**
     * NO B-FRAMES (design decision).
     *
     * A B-frame is coded from a LATER frame, thus the encoder must hold a frame back before it
     * can send one. A pilot flying from this picture pays that delay, and it also makes the
     * packetiser's timestamps harder.
     *
     * This is what lets [VideoCodec.H264] ask for HIGH profile in place of Baseline. High
     * PERMITS a B-frame; it does not make one, and this stops the encoder from choosing one.
     * Baseline forbade them by construction, which is why it was used before — but it also
     * gave up CABAC and the 8x8 transform, which is real picture quality at no bandwidth.
     *
     * ⚠ IT GOES ON THE PROFILE RUNGS ONLY, AND THAT IS DELIBERATE. On the base format it would
     * be on every rung, thus an encoder that refused this API-29 key would fail EVERY rung and
     * send no video at all — worse than a soft picture. On the rungs that ask for the profile,
     * the pairing is also correct: a rung that gives up the profile gets the encoder's own
     * default (Baseline or Main on the components here), where B-frames were never a risk.
     */
    private fun noBFrames(f: MediaFormat) {
        if (Build.VERSION.SDK_INT >= 29) f.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
    }

    private fun configureEncoder(
        w: Int, h: Int, withIntraRefresh: Boolean,
    ): Pair<MediaCodec, String>? {
        data class Variant(val name: String, val apply: (MediaFormat) -> Unit)

        // Bitrate modes to try, best first. VBR lets a keyframe borrow bits from the cheap
        // P-frames around it instead of being quantised down to fit a per-frame CBR budget;
        // the average is unchanged, so this costs no bandwidth. It is asked for FIRST but is
        // not assumed — bitrateModesFor() only offers it where the encoder declares it, and
        // CBR remains in the ladder underneath so a device that rejects VBR still configures.
        val variants = bitrateModesFor(outMime).flatMap { mode ->
            val label = modeLabel(mode)
            listOf(
                Variant("full (profile+level, $label, max-fps)") { f ->
                    f.setInteger(MediaFormat.KEY_BITRATE_MODE, mode)
                    f.setInteger(MediaFormat.KEY_PROFILE, codec.profile)
                    f.setInteger(MediaFormat.KEY_LEVEL, codec.level)
                    noBFrames(f)
                    if (Build.VERSION.SDK_INT >= 30) {
                        f.setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, profile.fps.toFloat())
                    }
                },
                Variant("no max-fps ($label)") { f ->
                    f.setInteger(MediaFormat.KEY_BITRATE_MODE, mode)
                    f.setInteger(MediaFormat.KEY_PROFILE, codec.profile)
                    f.setInteger(MediaFormat.KEY_LEVEL, codec.level)
                    noBFrames(f)
                },
                // KEEP THE FRAME-RATE CAP WHEN THE PROFILE KEYS GO. In an earlier version the
                // max-fps key was only on the rung above the two profile rungs, thus a
                // component that refused KEY_PROFILE lost the cap with it, although the cap
                // was not the key it refused. That is a costly loss on this controller: the
                // VirtualDisplay gives frames at the panel rate (60Hz), KEY_FRAME_RATE is
                // only a hint to the rate controller on a Surface input, and
                // KEY_MAX_FPS_TO_ENCODER is the only key that makes the framework DISCARD
                // input frames. Without it the encoder receives 60fps while the bitrate
                // budget is set for [TranscodeProfile.fps].
                //
                // The order is now: cap+profile, profile, CAP ALONE, mode alone. The profile
                // keys are the ones to give up first — the file already holds that neither is
                // load-bearing, and the block-rate arithmetic in logCapability() shows what
                // the cap is worth.
                Variant("max-fps, no profile/level ($label)") { f ->
                    f.setInteger(MediaFormat.KEY_BITRATE_MODE, mode)
                    if (Build.VERSION.SDK_INT >= 30) {
                        f.setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, profile.fps.toFloat())
                    }
                },
                Variant("$label only (no profile/level)") { f ->
                    f.setInteger(MediaFormat.KEY_BITRATE_MODE, mode)
                },
            )
        } + Variant("minimal (encoder defaults)") { }

        // WHICH ENCODER, not just which format keys.
        //
        // This controller has exactly one HARDWARE HEVC encoder — OMX.qcom.video.encoder.hevc —
        // and its rate control is the reason stream viewers see a pixelated pulse at every
        // 2s keyframe. The same chip's AVC encoder does not do it, and other Android devices
        // never did it either, so it is this specific component rather than our settings.
        //
        // There is no Codec2 HARDWARE HEVC encoder here (checked against the device's own
        // /vendor/etc/media_codecs*.xml). The only other option is Google's SOFTWARE encoder,
        // c2.android.hevc.encoder, which does its own rate control and spreads keyframe cost
        // properly. We ask for it first and fall back to whatever the platform picks.
        //
        // ⚠ SOFTWARE ENCODING COSTS CPU. On this sdm660 the STANDARD tier (720p15) should be
        // comfortable; the HIGH tier (1080p15) may not keep up, and dropped frames or a hot
        // controller would be the symptom. The winning encoder's NAME is logged — check it
        // before drawing conclusions about picture quality, because a silent fall back to the
        // hardware encoder would look exactly like "software did not help".
        for (name in encoderPreference) {
            for (v in variants) {
                val format = MediaFormat.createVideoFormat(outMime, w, h).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT,
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, profile.bitrateBps)
                    setInteger(MediaFormat.KEY_FRAME_RATE, profile.fps)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, idrIntervalS(withIntraRefresh))
                    if (withIntraRefresh) {
                        setInteger(MediaFormat.KEY_INTRA_REFRESH_PERIOD,
                            intraRefreshPeriodFrames())
                    }
                    v.apply(this)
                }
                val enc = runCatching {
                    if (name == null) MediaCodec.createEncoderByType(outMime)
                    else MediaCodec.createByCodecName(name)
                }.getOrNull() ?: break     // this encoder does not exist — go to the next one
                val ok = runCatching {
                    enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                }.isSuccess
                if (ok) return enc to "${enc.name} / ${v.name}" +
                    (if (withIntraRefresh) " + intra-refresh" else "")
                AppLog.w(TAG, "${name ?: "default"} rejected variant '${v.name}' — trying simpler")
                runCatching { enc.release() }
            }
        }
        return null
    }

    /**
     * What the encoder says it can do at this size, BEFORE we ask it to do it.
     *
     * Added while looking for the cause of stream jitter. The controller's
     * `/vendor/etc/media_codecs_sdm660_v1.xml` gives both hardware encoders the same ceiling —
     * 244800 16x16 blocks per second — and the tiers cost:
     *
     * ```
     *   Low       640x480    1200 blocks/frame   204 fps at the ceiling
     *   Standard  960x720    2700 blocks/frame    91 fps
     *   High     1440x1080   6120 blocks/frame    40 fps
     * ```
     *
     * The panel is 60Hz. Thus an UNCAPPED High tier asks for about 150% of the declared
     * ceiling, and the measured table (`media_codecs_performance_sdm660_v1.xml`) is stricter
     * again — the AVC encoder measures 32-37 fps at 1280x720. So this is not a small overrun.
     *
     * ⚠ **Two DIFFERENT numbers are printed here and they answer different questions.**
     * `achievable` is what the component declares for these exact dimensions; `asking` is the
     * input rate the [android.hardware.display.VirtualDisplay] can deliver, which is the panel
     * rate whether or not the profile asked for less. When `asking` is above `achievable` the
     * encoder is being overrun, and the fix is the frame-rate cap, NOT the bitrate.
     *
     * Every read is optional — [android.media.MediaCodecInfo.VideoCapabilities] throws for a
     * size a component does not support, and a legacy OMX component may not describe itself
     * fully at all. A failure logs and returns; it must never stop a stream from starting.
     */
    private fun logCapability(w: Int, h: Int) {
        runCatching {
            val panelFps = displayRefreshHz()
            var found = false
            for (info in MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos) {
                if (!info.isEncoder || outMime !in info.supportedTypes.map { it.lowercase() }) continue
                if (!isHardware(info)) continue
                val caps = info.getCapabilitiesForType(outMime).videoCapabilities ?: continue
                val achievable = runCatching {
                    caps.getSupportedFrameRatesFor(w, h).upper
                }.getOrNull()
                AppLog.i(TAG, "CAPABILITY: ${info.name} at ${w}x$h — " +
                    "achievable=${achievable?.let { "%.0f".format(it) } ?: "?"}fps, " +
                    "asking=${"%.0f".format(panelFps)}fps (panel), " +
                    "profile wants ${profile.fps}fps" +
                    if (achievable != null && panelFps > achievable)
                        "  ⚠ UNCAPPED INPUT WOULD OVERRUN THIS ENCODER" else "")
                found = true
            }
            if (!found) AppLog.w(TAG, "CAPABILITY: no hardware $outMime encoder described itself")
        }.onFailure { AppLog.w(TAG, "CAPABILITY probe failed: ${it.message}") }
    }

    /** The panel refresh rate — the rate the VirtualDisplay can deliver frames at, thus the
     *  input rate the encoder sees when the frame-rate cap is not applied. 60.0 if unreadable,
     *  which is this controller's value and the common default. */
    private fun displayRefreshHz(): Float = refreshHz

    /**
     * How many frames one complete refresh sweep takes.
     *
     * Two seconds' worth of frames, thus the picture is fully refreshed on the same cadence the
     * IDR used to arrive on. The cost is spread over those frames instead of landing in one.
     */
    private fun intraRefreshPeriodFrames(): Int = profile.fps * 2

    /** Encoders to try, in order. null means "let the platform choose" (normally hardware).
     *
     *  [SW_HEVC_ENCODER] is an H.265 component, so it is only offered when we are encoding H.265
     *  — naming it for an AVC stream would burn a configure attempt on a codec that cannot
     *  produce the requested mime. */
    private val encoderPreference: List<String?> get() =
        if (PREFER_SOFTWARE_ENCODER && codec.isHevc) listOf(SW_HEVC_ENCODER, null) else listOf(null)

    /**
     * Which bitrate modes to offer the encoder, best first.
     *
     * Also logs what every HEVC encoder on the device declares — this controller is a locked
     * vendor build with no public record of its codec declarations, so the answer had to be
     * asked of the hardware rather than looked up. Declaring a mode is necessary but NOT
     * sufficient: watch the I/P size ratio in the drain loop to see whether it changed anything.
     */
    private fun bitrateModesFor(mime: String): List<Int> {
        val vbrOk = runCatching {
            var supported = false
            for (info in MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos) {
                if (!info.isEncoder || mime !in info.supportedTypes.map { it.lowercase() }) continue
                val caps = info.getCapabilitiesForType(mime).encoderCapabilities
                val vbr = caps.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                val cbr = caps.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
                val cq = caps.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ)
                AppLog.i(TAG, "PROBE: ${info.name} VBR=$vbr CBR=$cbr CQ=$cq " +
                    "complexity=${caps.complexityRange}")
                // Only the encoder we will actually get decides the ladder. With
                // PREFER_SOFTWARE_ENCODER false that is the platform's default pick, which is
                // the hardware one; the loop still logs the others for the record.
                if (isHardware(info) && vbr) supported = true
            }
            supported
        }.onFailure { AppLog.w(TAG, "PROBE failed: ${it.message}") }.getOrDefault(false)

        val cbr = MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
        val vbr = MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
        return if (PREFER_VBR && vbrOk) listOf(vbr, cbr) else listOf(cbr)
    }

    /** `isSoftwareOnly()` is API 29; minSdk here is 21, so fall back to the naming convention
     *  (`c2.android.*` / `OMX.google.*` are Google's software components). */
    private fun isHardware(info: MediaCodecInfo): Boolean =
        if (Build.VERSION.SDK_INT >= 29) !info.isSoftwareOnly()
        else !info.name.startsWith("c2.android.") && !info.name.startsWith("OMX.google.")

    private fun modeLabel(mode: Int): String = when (mode) {
        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR -> "VBR"
        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR -> "CBR"
        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ -> "CQ"
        else -> "mode$mode"
    }

    /** Ask the encoder for an IDR now — arms the RTSP packetiser on connect, heals viewers. */
    fun requestSyncFrame() {
        runCatching {
            encoder?.setParameters(Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            })
        }.onFailure { AppLog.w(TAG, "requestSyncFrame failed: ${it.message}") }
    }

    fun release() {
        running = false
        runCatching { mediaProjection.unregisterCallback(projectionCallback) }
        runCatching { virtualDisplay?.release() }; virtualDisplay = null
        // Never join ourselves: the stall path reaches here ON the drain thread, and a self-join
        // only waited the full 500 ms before the codec stopped.
        drainThread?.let { if (it !== Thread.currentThread()) runCatching { it.join(500) } }
        drainThread = null
        runCatching { encoder?.stop() }; runCatching { encoder?.release() }; encoder = null
        runCatching { inputSurface?.release() }; inputSurface = null
    }

    private fun drainLoop() {
        // Armed here rather than at the first frame, so an encoder that produces NOTHING from
        // the moment it starts is caught by the same watchdog as one that stops later.
        lastOutputMs = android.os.SystemClock.elapsedRealtime()

        // The drain loop was originally at DEFAULT thread priority, which put it level
        // with ordinary background work. It must not be: while it waits to be scheduled, the
        // output buffers it has not released are buffers the encoder cannot reuse, thus the
        // encoder stalls and the frames reach the network in bursts. That is jitter created on
        // this end, and it gets worse exactly when the controller is busy.
        //
        // DISPLAY, not URGENT_DISPLAY: this thread must stay below the flight screen's own
        // drawing. A stream that stutters is a fault; a flight screen that stutters is a
        // safety matter.
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY) }
            .onFailure { AppLog.w(TAG, "could not raise drain thread priority: ${it.message}") }

        val enc = encoder ?: return
        val info = MediaCodec.BufferInfo()
        try {
            while (running) {
                val idx = enc.dequeueOutputBuffer(info, 100_000)
                // ⚠ THE WATCHDOG. Checked on the poll that came back empty, which is the only
                // place that can see "nothing is coming out". A stalled encoder stops the
                // release fence on our VirtualDisplay from signalling, and SurfaceFlinger waits
                // on that fence FOR EVER — see ScreenCaptureStallPolicy. Tearing the capture down is how this
                // application stops being the thing that wedges the compositor.
                if (ScreenCaptureStallPolicy.isStalled(
                        running, lastOutputMs, android.os.SystemClock.elapsedRealtime())) {
                    val age = android.os.SystemClock.elapsedRealtime() - lastOutputMs
                    AppLog.e(TAG, "ENCODER STALLED — no output for ${age}ms. Tearing the screen " +
                        "capture down so the VirtualDisplay stops being fed.")
                    running = false
                    // release() joins THIS thread, so it must not be called from here. The
                    // VirtualDisplay is the one thing that has to go, and it has to go first:
                    // it is the producer SurfaceFlinger is composing for.
                    runCatching { virtualDisplay?.release() }; virtualDisplay = null
                    runCatching { onStalled(age) }
                    break
                }
                when {
                    idx == MediaCodec.INFO_TRY_AGAIN_LATER -> continue
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        // The only point at which the encoder's ACTUAL format is legal to read.
                        // Many OMX components do not echo KEY_BITRATE_MODE back at all, so a
                        // missing mode here is not evidence the request was ignored — the I/P
                        // ratio logged below is what settles that.
                        logActualFormat(enc)
                        continue
                    }
                    idx >= 0 -> {
                        val outBuf = enc.getOutputBuffer(idx)
                        if (outBuf != null && info.size > 0) {
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + info.size)
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                handleCodecConfig(outBuf, info)
                            } else {
                                onEncoded(outBuf, info)
                                lastOutputMs = android.os.SystemClock.elapsedRealtime()
                                encFrameCount++
                                encBytesSinceLog += info.size
                                if (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0) {
                                    iFrameCount++; iFrameBytes += info.size
                                } else {
                                    pFrameCount++; pFrameBytes += info.size
                                }
                                if (encFrameCount % 150 == 0) {
                                    logRate()
                                    encBytesSinceLog = 0
                                    logFrameMix()
                                }
                            }
                        }
                        enc.releaseOutputBuffer(idx, false)
                    }
                }
            }
        } catch (t: Throwable) {
            if (running) {
                // The loop is gone, and with it the stall watchdog. Same teardown as a stall:
                // the VirtualDisplay first, because SurfaceFlinger composes for it.
                AppLog.e(TAG, "drain loop error — the screen capture is down: ${t.message}")
                running = false
                runCatching { virtualDisplay?.release() }; virtualDisplay = null
                reportGone("Video stopped: the video encoder failed. Touch LIVE to start again.")
            }
        }
    }

    /** What the encoder says it actually configured. Every key is optional — read defensively. */
    private fun logActualFormat(enc: MediaCodec) {
        runCatching {
            val f = enc.outputFormat
            fun intOf(k: String): String = runCatching { f.getInteger(k).toString() }.getOrDefault("-")
            AppLog.i(TAG, "encoder output format: ${intOf(MediaFormat.KEY_WIDTH)}x" +
                "${intOf(MediaFormat.KEY_HEIGHT)} bitrate=${intOf(MediaFormat.KEY_BIT_RATE)} " +
                "mode=${runCatching { modeLabel(f.getInteger(MediaFormat.KEY_BITRATE_MODE)) }
                    .getOrDefault("not-reported")} " +
                "iFrameInterval=${intOf(MediaFormat.KEY_I_FRAME_INTERVAL)} " +
                "intraRefresh=${intOf(MediaFormat.KEY_INTRA_REFRESH_PERIOD)}")
        }.onFailure { AppLog.w(TAG, "could not read output format: ${it.message}") }
    }

    /**
     * The ACHIEVED frame rate, against the two rates that bracket it.
     *
     * This line used to give a frame COUNT and a byte total, which says nothing about pacing:
     * 150 frames is 150 frames whether they took 10 seconds or 2.5. The elapsed time is what
     * separates the three states that matter, and they need different fixes:
     *
     *  - **about [TranscodeProfile.fps]** — correct. The frame-rate cap is applied.
     *  - **about the panel rate (60)** — THE CAP IS NOT APPLIED. The encoder is doing four
     *    times the work the profile asked for, and the bitrate budget is spread over four
     *    times the frames. Read the `variant:` line to see which rung won.
     *  - **BELOW the profile rate** — the encoder cannot keep up at this size. The tier is too
     *    high for the hardware, and no format key fixes that. Compare with the `CAPABILITY`
     *    line printed at start.
     *
     * Logged at I, not V, because it is the first thing to read when a stream looks uneven and
     * Detailed logging is not always on. It costs one line per 150 frames.
     */
    private fun logRate() {
        val now = SystemClock.elapsedRealtime()
        val since = lastRateLogMs
        lastRateLogMs = now
        if (since <= 0L) return          // first mark — no interval to measure yet
        val elapsedMs = now - since
        if (elapsedMs <= 0L) return
        val fps = 150_000.0 / elapsedMs
        val verdict = when {
            fps > profile.fps * 1.5 -> "  ⚠ ABOVE PROFILE — frame-rate cap not applied"
            fps < profile.fps * 0.8 -> "  ⚠ BELOW PROFILE — encoder not keeping up"
            else -> ""
        }
        AppLog.i(TAG, "[${profile.name}] rate: ${"%.1f".format(fps)}fps achieved " +
            "(profile ${profile.fps}, panel ${"%.0f".format(refreshHz)}), " +
            "${encBytesSinceLog / 1024}KB in last 150$verdict")
    }

    /**
     * The verification that matters: how many bits an I-frame actually gets relative to a P-frame.
     *
     * ~2.5x means the rate controller is starving keyframes (the CBR behaviour that produced the
     * pulse). 5-10x means keyframes are being allowed the bits a full intra frame needs. Logged
     * cumulatively rather than per frame so a long session reads as one trend line instead of a
     * keyframe-rate log spam.
     */
    private fun logFrameMix() {
        if (iFrameCount == 0 || pFrameCount == 0) return
        val iAvg = iFrameBytes / iFrameCount
        val pAvg = pFrameBytes / pFrameCount
        AppLog.i(TAG, "frame mix: I n=$iFrameCount avg=${iAvg / 1024}KB, " +
            "P n=$pFrameCount avg=${pAvg / 1024}KB, I/P=${"%.2f".format(iAvg.toDouble() / pAvg)}x")
    }

    /**
     * Pulls VPS/SPS/PPS out of the encoder's codec-config buffer.
     *
     * H.265 emits THREE parameter-set NALs where H.264 emits two, so this splits every NAL and
     * classifies by header type rather than assuming a count. NALs keep their start codes; the
     * RTSP library strips them itself.
     */
    private fun handleCodecConfig(buf: ByteBuffer, info: MediaCodec.BufferInfo) {
        val bytes = ByteArray(info.size)
        buf.get(bytes)
        var vps: ByteArray? = null
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        for (nal in splitAnnexB(bytes)) {
            val hdr = nal.getOrNull(startCodeLen(nal)) ?: continue
            if (codec.isHevc) {
                when ((hdr.toInt() shr 1) and 0x3F) {
                    32 -> vps = nal
                    33 -> sps = nal
                    34 -> pps = nal
                }
            } else {
                when (hdr.toInt() and 0x1F) {
                    7 -> sps = nal
                    8 -> pps = nal
                }
            }
        }
        val s = sps; val p = pps
        if (s == null || p == null) {
            AppLog.w(TAG, "codec config had no SPS/PPS — not advertising")
            return
        }
        AppLog.i(TAG, "encoder params ready: " +
            (vps?.let { "vps=${it.size}B " } ?: "") + "sps=${s.size}B pps=${p.size}B")
        onParamsReady(ByteBuffer.wrap(s), ByteBuffer.wrap(p), vps?.let { ByteBuffer.wrap(it) })
    }

    private fun startCodeLen(nal: ByteArray): Int =
        if (nal.size >= 4 && nal[0] == Z && nal[1] == Z && nal[2] == Z && nal[3] == O) 4 else 3

    private fun splitAnnexB(bytes: ByteArray): List<ByteArray> {
        val starts = ArrayList<Int>()
        var i = 0
        while (i < bytes.size - 3) {
            if (bytes[i] == Z && bytes[i + 1] == Z) {
                if (bytes[i + 2] == O) { starts.add(i); i += 3; continue }
                if (bytes[i + 2] == Z && bytes[i + 3] == O) { starts.add(i); i += 4; continue }
            }
            i++
        }
        if (starts.isEmpty()) return emptyList()
        return starts.mapIndexed { idx, from ->
            bytes.copyOfRange(from, if (idx + 1 < starts.size) starts[idx + 1] else bytes.size)
        }
    }

    companion object {
        private const val TAG = "ScreenCaptureEncoder"
        private const val Z: Byte = 0
        private const val O: Byte = 1
        /**
         * Seconds between forced IDRs. **Stays at 2 — [PREFER_VBR] is what fixed the pulse.**
         *
         * An earlier fix plan paired VBR with a 2 -> 4 widening, on the theory that VBR
         * might not work on this SoC and halving the number of keyframes would at least halve
         * how OFTEN the pulse could happen. VBR did work (I/P went 2.53x -> 14x, and the pulse
         * was confirmed gone), so the widening was addressing frequency after amplitude
         * was already solved — and it is not free: a viewer joining mid-GOP waits up to one
         * interval for a picture, and a broken prediction chain takes up to one interval to
         * self-heal. Both doubled at 4s. Reverted to 2 rather than kept "for margin".
         *
         * So: if the pulse ever comes back, this is NOT the knob. Check the `frame mix:` I/P
         * ratio first — a return to ~2.5x means the encoder stopped honouring VBR.
         */
        private const val I_FRAME_INTERVAL_S = 2

        /**
         * Ask for VBR instead of CBR when the encoder declares it.
         *
         * Under CBR the rate controller holds every frame to roughly the same size, so an IDR —
         * which has no motion prediction to lean on and legitimately needs 5-10x an average
         * P-frame — gets quantised hard to fit. Measured on this controller at CBR: I/P was only
         * 2.53x, and the resulting keyframe was visibly blockier than its neighbours. VBR lets
         * the keyframe borrow from the cheap frames around it at the SAME average bitrate, so
         * this costs no extra bandwidth — bitrates in [TranscodeProfile] are deliberately
         * unchanged.
         *
         * Not assumed to work: this SoC's `OMX.qcom.video.encoder.hevc` is a legacy OMX
         * component, its media_codecs.xml declares no `bitrate-modes` at all, and Android 11 has
         * no VBR quality floor (API 31+ only), so the mode could be accepted and then ignored.
         * The ladder in configureEncoder falls back to CBR either way. Check the "frame mix"
         * I/P line in the log before believing it did anything.
         */
        private const val PREFER_VBR = true

        /**
         * Intra refresh is ALWAYS ON. It was a Debug-screen switch for one
         * evening, long enough to measure it on both codecs, and the measurements did not
         * leave a case for the switch:
         *
         * ```
         *   H.264 High  1440x1080  keyframe share of bandwidth  39%  ->  6.5%
         *   H.265 Std    960x720   keyframe share of bandwidth        ->  4.5%
         * ```
         *
         * Both hardware encoders accepted it on the FIRST configuration attempt and echoed
         * `intraRefresh=30` back, the frame rate held at 15.0, and the bitrate stayed on
         * target. Nothing regressed, thus a switch would only have offered a worse setting.
         *
         * The fallback in [start] stays: a device whose encoder refuses the key still gets a
         * stream, with periodic IDRs and a warning in the log.
         */
        private const val USE_INTRA_REFRESH = true

        /**
         * How INTRA REFRESH changes the shape of the stream, and why it is a field switch.
         *
         * A normal stream sends one full intra frame every [I_FRAME_INTERVAL_S] seconds. It is
         * very large: measured on this controller at the High tier, an I frame averaged 169 KB
         * against a P frame of 9 KB — 18 times the size, and 39% of ALL the bits in the stream
         * for 3% of the frames. At 1800 kbps that one frame needs about 770 MILLISECONDS of
         * link time, and it is produced in a single 67 ms frame slot.
         *
         * Intra refresh sends the same intra information as a BAND that moves across the
         * picture, a slice of each frame at a time, over [intraRefreshPeriodFrames] frames.
         * The total is similar; the DISTRIBUTION is flat. There is no burst to absorb.
         *
         * ## What this is for, and what it is not for
         *
         * It is for a link that cannot swallow a 770 ms burst every two seconds — which is a
         * cellular uplink, the usual way a controller is connected in the field.
         *
         * ⚠ **It is NOT expected to change anything on a good LAN.** Measured against the
         * media server: `packetsReceivedLoss 0, packetsReceivedRetrans 0,
         * packetsReceivedDrop 0`, RTT 2.18 ms. There is nothing wrong with that path and
         * nothing here can improve it. A LAN test that shows no difference has NOT
         * disproved this; it has only confirmed the LAN was never the problem. The test
         * that means something is over LTE.
         *
         * ## The cost, stated honestly
         *
         * The IDR interval is lengthened (see [idrIntervalS]), thus there is no longer a full
         * sync point every 2 s. `requestSyncFrame()` still asks for one when a viewer connects,
         * so joining is unaffected. What changes is RECOVERY FROM A BREAK with no viewer
         * action: instead of healing at the next 2 s keyframe, the picture heals as the refresh
         * band sweeps past — about [intraRefreshPeriodFrames] frames, which is the same order.
         *
         * ⚠ **Verify with the `frame mix:` line, and read it correctly.** The I/P RATIO does
         * NOT collapse — a periodic IDR is still a full intra frame, thus it stays around
         * 6-7x. What changes is how OFTEN one arrives (every 10 s, not every 2 s) and the P
         * frames growing, which is the intra information spread into them: measured 9KB -> 14KB
         * on H.264 at an unchanged total bitrate. Count the I frames per minute; do not watch
         * the ratio alone. An earlier version of this note predicted a collapse to 1x and was
         * wrong.
         */
        /**
         * Seconds between full IDRs, which depends on whether intra refresh carries the
         * recovery.
         *
         * Without it, 2 s — unchanged, and see [I_FRAME_INTERVAL_S] for why that number is not
         * a knob for the keyframe PULSE.
         *
         * With it, 10 s. Not "never": a periodic IDR is still the cheapest insurance against a
         * decoder that has drifted in a way the refresh band does not repair, and at 10 s it
         * costs one large frame in a hundred and fifty rather than one in thirty.
         */
        private fun idrIntervalS(withIntraRefresh: Boolean): Int =
            if (withIntraRefresh) INTRA_REFRESH_IDR_INTERVAL_S else I_FRAME_INTERVAL_S

        private const val INTRA_REFRESH_IDR_INTERVAL_S = 10

        /**
         * Prefer Google's SOFTWARE HEVC encoder over this chip's hardware one.
         *
         * ⚠ FALSE ON PURPOSE — the software encoder LEAKS. Confirmed live: the
         * `media.swcodec` process (which hosts `c2.android.hevc.encoder`, the software encoder
         * this flag selects) grew from 628MB to 786MB PSS in about 20 seconds of active
         * streaming — device-wide `MemAvailable` was falling in lockstep, ~3.4MB/s. That is far
         * more severe than the artifact this flag was chasing, and is a strong suspect (maybe
         * THE cause) for app-process OOM kills seen during the same testing — a device-wide
         * memory exhaustion event kills several unrelated background services simultaneously
         * (seen more than once), which is what runs out when ANY process — not just this app —
         * leaks enough. Do not flip this back to true without checking `media.swcodec`'s PSS
         * over a sustained streaming session first.
         *
         * It was once set true to test whether the 2s keyframe pulse is the hardware encoder's
         * rate control. Other Android devices (Pixel phones, for example) never showed the
         * pulse, and those may have been software-encoding. That question
         * is still open — a live memory leak is just a worse problem than a cosmetic pulse.
         *
         * Keep the flag rather than deleting the loser: which encoder is in use is the single
         * most useful thing to change when stream quality is in question, and hunting for the
         * call site each time is how this ends up hard-coded by accident.
         */
        private const val PREFER_SOFTWARE_ENCODER = false

        /** Google's software HEVC encoder. Present on this controller; verified against its own
         *  /vendor/etc/media_codecs_google_c2_video.xml. */
        private const val SW_HEVC_ENCODER = "c2.android.hevc.encoder"
        /**
         * The codec itself is no longer decided here — it is a pilot choice in Pre-Flight,
         * passed in as [VideoCodec]. See that enum for the full H.264/H.265 history and for why
         * the right answer depends on whether the team is watching in ATAK or in a browser.
         */
    }
}
