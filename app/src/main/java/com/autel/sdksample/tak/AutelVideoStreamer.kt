package com.autel.sdksample.tak

import android.content.Context
import android.media.MediaCodec
import com.taklite.util.AppLog
import com.pedro.rtsp.rtsp.Protocol
import com.pedro.rtsp.rtsp.RtspClient
import com.pedro.rtsp.utils.ConnectCheckerRtsp
import com.pedro.srt.srt.SrtClient
import com.pedro.srt.srt.packets.control.handshake.EncryptionType
import java.nio.ByteBuffer

/**
 * AutelVideoStreamer — RTSP or SRT push of the flight screen.
 *
 * **The path: MediaProjection screen capture → H.265 encode → RTSP.** `ScreenCaptureService`
 * obtains the projection and calls [VideoStreamerHolder.startScreenCapture]; [ScreenCaptureEncoder]
 * mirrors the whole flight screen (FPV *plus* HUD, AR markers and map)
 * into an encoder input surface. This means the stream does NOT depend on the aircraft: a lost
 * link, a battery swap, or an aircraft that was never connected all leave the push running, and
 * viewers keep seeing the controller instead of the feed going dead.
 *
 * This matters when reasoning about what the team sees: anything on this screen is in their
 * feed. Black bars, aspect changes and overlays are not local cosmetics.
 *
 * (History: an aircraft-camera path once tapped the SDK's [com.autel.sdk.video.AutelCodecListener]
 * for encoded frames and transcoded them. It was superseded by screen capture and, being unreached
 * in production, removed — along with a latent H.264/H.265 mis-detection in its
 * parameter-set sniffer. Screen capture reads the already-composited screen, so it needs no second
 * codec tap and cannot contend with the on-screen `AutelCodecView`.)
 *
 * The encoder produces SPS/PPS/VPS before the client connects — the RTSP connect worker waits up
 * to 5 s for setVideoInfo — so the encoder is started first and connect follows immediately.
 *
 * ## Two transports, one leg
 *
 * The push leaves as RTSP or as SRT, the pilot's choice ([VideoTransport]). **What the TAK
 * clients are told does not change**: the advertised address is an RTSP address either way,
 * because no TAK client plays SRT. The media server ingests one and serves the other.
 *
 * The two clients are the same author's and their send API is identical, thus [PushClient] wraps
 * both and everything below the selection is transport-blind. The DIFFERENCES are all at setup:
 * SRT has no credential field (they go in the stream id) and no protocol selection.
 *
 * This class implements the callback interface of BOTH clients. The names do not collide
 * (`onConnectionSuccessRtsp` against `onConnectionSuccess`) and every one of them funnels into
 * the same private handler, so the state machine exists once.
 */
class AutelVideoStreamer(
    private val context: Context,
    private val config: VideoConfig,
    private val onStatus: (Boolean, String) -> Unit,
    /** The screen-capture projection. The stream is always a capture of the flight screen. */
    private val mediaProjection: android.media.projection.MediaProjection,
) : ConnectCheckerRtsp, com.pedro.common.ConnectChecker {

    data class VideoConfig(
        val host: String,
        val streamId: String,

        // ONE LOGIN FOR THE SERVER, both protocols. SRT has no login of its own — the SRT
        // handshake carries a stream id, which is an opaque string, and MediaMTX reads a user
        // and a password out of it by its own convention. So this pair authorises the publish
        // whichever transport carries it, and it is also what the CoT address needs.
        val username: String,
        val password: String,

        // ONLY THE PORT DIFFERS BETWEEN THE PROTOCOLS, and SRT adds a passphrase. Each keeps
        // its own value, so a pilot who toggles back and forth finds what they last entered.
        //
        // ⚠ [rtspPort] IS USED WHICHEVER PUBLISH TRANSPORT IS SELECTED, and the screen hides
        // its field under SRT: it is the default read port, so the team can be connecting to it
        // while the video leaves over SRT. It is no longer the ONLY possibility — see
        // [advertiseTransport], which can now put an srt:// address in the CoT instead. It was
        // once the constant 8554, which meant a server serving RTSP on any other
        // port could not be advertised at all.
        val rtspPort: Int = VideoTransport.RTSP.defaultPort,
        val srtPort: Int = VideoTransport.SRT.defaultPort,

        // ---- What the CoT advertises, which is NOT always where the video is pushed ----
        //
        // A media server can be unreachable from where the team is and still be the right
        // place to push: an internal server that forwards to an external one. The push and
        // the advertisement are two independent facts, so these carry the advertised address
        // rather than deriving it from the push (design decision).
        //
        // The screen resolves them — "this server", the other configured server, or nothing —
        // and mirrors the answer here, so this class never has to know a server slot exists.
        //
        // ⚠ [advertiseEnabled] false means NO VIDEO BLOCK IN THE CoT AT ALL. That is the
        // honest state when no address works: a play control that cannot connect reads to the
        // team as a dead feed, which is worse than no control.
        val advertiseEnabled: Boolean = true,
        val advertiseHost: String = "",
        val advertisePort: Int = VideoTransport.RTSP.defaultPort,
        val advertiseUser: String = "",
        val advertisePass: String = "",

        /**
         * How the TEAM READS the stream — the scheme in the CoT. Independent of [transport],
         * which is how the video LEAVES this controller. The two legs are separate and always
         * were; originally only the push leg was selectable.
         *
         * ⚠ **This is a FORWARD-COMPATIBILITY control and [VideoTransport.RTSP] stays the
         * default.** ATAK plays an SRT read today, with the CoT shaped as
         * [advertiseUrl] builds it; TAK Aware CANNOT, and the cause is its
         * bundled MobileVLCKit having no SRT access module compiled in — a vendor build issue
         * that no CoT shape can work around. A deployment that moves this to SRT before its clients
         * are ready advertises an address its viewers fail to open, and a failure to open
         * reads as a dead feed, not a wrong address. Move it when the clients can read it.
         */
        val advertiseTransport: VideoTransport = VideoTransport.RTSP,
        /**
         * The port the team READS SRT on. Separate from [advertisePort] (the RTSP read port)
         * for the same reason [srtPort] is separate from [rtspPort]: they are different ports
         * on the server, and a pilot who toggles back and forth finds what they last entered.
         */
        val advertiseSrtPort: Int = VideoTransport.SRT.defaultPort,
        /**
         * SRT READ passphrase — MediaMTX's `srtReadPassphrase`, which is NOT necessarily
         * [srtPassphrase] (its `srtPublishPassphrase`). Empty when the read path has none.
         *
         * ⚠ Unlike the publish passphrase this one DOES go in a url, because SRT carries it in
         * the stream-id query and there is nowhere else to put it. That is the protocol's
         * design, not a choice here. It still must never be logged — see [advertiseUrlSafe].
         */
        val advertisePassphrase: String = "",

        /** How the video leaves the controller. See [VideoTransport]. */
        val transport: VideoTransport = VideoTransport.RTSP,
        /** SRT only, and MILLISECONDS. The evidence for the number, the units trap and the
         *  field override are all at [VideoTransport.SRT_LATENCY_DEFAULT_MS]. */
        val srtLatencyMs: Int = VideoTransport.SRT_LATENCY_DEFAULT_MS,
        /**
         * SRT only. The media server's `srtPublishPassphrase`, or empty when the server does
         * not use one. This ENCRYPTS THE STREAM and it is a separate secret from the video
         * user and password: those authorise the publish, this one is the key.
         *
         * ⚠ **A server that sets a passphrase refuses an unencrypted publisher outright** —
         * MediaMTX answers `SRT_REJ_PEER` at the handshake, before any credential is looked at,
         * so a missing passphrase looks exactly like wrong credentials. Confirmed against
         * MediaMTX v1.20.0.
         *
         * ⚠ **NEVER LOG THIS AND NEVER PUT IT IN A URL.** It is not part of the stream id, it
         * does not appear in [pushUrl] or [urlSafe], and it must not go in the CoT.
         *
         * ⚠ **IT IS NOT THE READ PASSPHRASE.** The team's side has its own —
         * [advertisePassphrase], MediaMTX's `srtReadPassphrase` — and crossing the two is the
         * mistake that looks exactly like wrong credentials.
         */
        val srtPassphrase: String = "",
        /** Pilot-selected video quality: "low" | "standard" | "high", from the
         *  video_profile pref. Every profile is an on-device encode. */
        val profile: String = "standard",
        /** Pilot-selected codec: "h264" | "h265". See [VideoCodec] for why this is a field
         *  decision rather than a build-time constant. */
        val codec: String = "h264",
        /**
         * Put the per-process random token in the stream path. OFF keeps the path exactly as
         * every earlier version built it. See [StreamPath] for the shape, the token's lifetime
         * and the reason. Read from [StreamPath.PREF_RANDOMIZE].
         */
        val randomizePath: Boolean = false,
    ) {
        val transcodeProfile: TranscodeProfile
            get() = TranscodeProfile.fromPref(profile)

        val videoCodec: VideoCodec
            get() = VideoCodec.fromPref(codec)

        /**
         * THE path. One name flows through the push, the advertisement and the masked preview
         * alike, so the CoT always points at the stream that is live. It is composed in ONE
         * place, [StreamPath.compose] — the `-Low` suffix, the sanitizing and the optional token
         * are all there. Nothing in this class builds a path by hand.
         */
        fun streamPath(): String = StreamPath.compose(streamId, randomizePath)

        /**
         * Where the video goes OUT. The two transports do not agree on where the credentials
         * live, thus this is the one place that knows the difference.
         *
         *  - RTSP: `rtsp://host:port/<path>`. The user and the password are NOT here — they go
         *    to the client with `setAuthorization` and the protocol carries them.
         *  - SRT: `srt://host:port/publish:<path>:<user>:<password>`. SRT has no credential
         *    field of its own. Everything after the port is the STREAM ID, one string, and the
         *    media server reads the publisher, the path and the credentials out of it. With no
         *    username it is `publish:<path>` and the server decides whether to accept that.
         *
         * ⚠ **A colon in the video password breaks the SRT form**, because the colon is what
         * separates the four parts of the stream id. There is no escape for it. [start] logs a
         * warning; the stream will be refused by the server. RTSP is unaffected.
         */
        fun pushUrl(): String = when (transport) {
            VideoTransport.RTSP -> "rtsp://$host:$rtspPort/${streamPath()}"
            VideoTransport.SRT ->
                if (username.isEmpty()) "srt://$host:$srtPort/publish:${streamPath()}"
                else "srt://$host:$srtPort/publish:${streamPath()}:$username:$password"
        }

        /** The port the PUSH uses. The login is [username] either way. */
        val pushPort: Int get() =
            if (transport == VideoTransport.SRT) srtPort else rtspPort

        /**
         * The address that goes in the CoT, for the team to play. Built from the ADVERTISE
         * fields, which are not necessarily this server's own — see them above. An empty string
         * means the pilot turned the advertisement off, and no video block goes in the CoT.
         *
         * ⚠ **IT IS NOT ALWAYS RTSP ANY MORE.** It was, and the reason
         * recorded here was that no TAK client plays SRT. That is no longer true: ATAK plays an
         * SRT read, confirmed against a live server and real clients. [advertiseTransport] selects it, and RTSP remains the
         * default because TAK Aware still cannot — see that field.
         *
         * ⚠ **THE SRT FORM IS NOT A SCHEME SWAP.** An SRT reader is told what it wants in the
         * STREAM ID, not in a path: `?streamid=read:<path>[:<user>:<pass>]`. The `read:` prefix
         * is MediaMTX's own shorthand for a subscriber, the counterpart of the `publish:` that
         * [pushUrl] already builds. And the whole query string has to reach
         * `CotBuilder.appendVideo` intact, because ATAK rebuilds the connection from
         * `ConnectionEntry.path` and not from this url — that is the single most important fact
         * about this form.
         *
         * ⚠ The credentials are NOT url-encoded in the SRT form, unlike the RTSP one. The
         * stream id is one opaque colon-separated string that the media server splits itself;
         * percent-encoding it would make the server look for a user that does not exist. The
         * same colon trap as [pushUrl] therefore applies — a colon in the video password has no
         * escape in either direction.
         */
        fun advertiseUrl(): String {
            if (!advertiseEnabled) return ""
            val h = advertiseHost.ifEmpty { host }
            return when (advertiseTransport) {
                VideoTransport.RTSP -> {
                    val cred = if (advertiseUser.isNotEmpty())
                        "${enc(advertiseUser)}:${enc(advertisePass)}@" else ""
                    "rtsp://$cred$h:$advertisePort/${streamPath()}?tcp"
                }
                VideoTransport.SRT ->
                    "srt://$h:$advertiseSrtPort?streamid=${srtReadStreamId()}${srtReadPhrase()}"
            }
        }

        /**
         * The SRT read stream id, `read:<path>[:<user>:<pass>]`.
         *
         * The credentials are appended only when there is a user, matching [pushUrl]: a path
         * with no authentication must not be asked for an empty one, because MediaMTX reads the
         * trailing colons as a real, empty user and refuses the connection.
         */
        private fun srtReadStreamId(): String =
            if (advertiseUser.isEmpty()) "read:${streamPath()}"
            else "read:${streamPath()}:$advertiseUser:$advertisePass"

        /**
         * `&passphrase=…`, or nothing at all.
         *
         * ⚠ A DANGLING `&passphrase=` IS NOT THE SAME AS NO PASSPHRASE — never emit
         * one. An empty value asks for encryption with an empty key, which a
         * server with no passphrase refuses just as firmly as a wrong one.
         */
        private fun srtReadPhrase(): String =
            if (advertisePassphrase.isEmpty()) "" else "&passphrase=$advertisePassphrase"

        /**
         * The url with the password masked, for the screen and the log.
         *
         * ⚠ **It must distinguish "set" from "empty".** It used to print `user:***@` whenever the
         * USERNAME was non-empty, so a missing password looked identical to a present one — and
         * the Pre-Flight preview, the one place a pilot would check, could not answer the
         * question it exists to answer. A password really was empty once and the screen
         * showed stars for it.
         *
         * **This is the PUSH address, not the playback address.** The two were the same string
         * until SRT arrived. The push address is the one built from the fields on the screen, so
         * it is the one that can show a pilot that a field is wrong; the playback address is
         * derived and goes out in the CoT, and nobody types it anywhere (design decision).
         */
        fun urlSafe(): String {
            val secret = when {
                username.isEmpty() -> ""
                password.isEmpty() -> "(NO PASSWORD)"
                else -> "***"
            }
            return when (transport) {
                VideoTransport.RTSP -> {
                    val who = if (username.isEmpty()) "" else "$username:$secret@"
                    "rtsp://$who$host:$rtspPort/${streamPath()}?tcp"
                }
                VideoTransport.SRT ->
                    if (username.isEmpty()) "srt://$host:$srtPort/publish:${streamPath()}"
                    else "srt://$host:$srtPort/publish:${streamPath()}:$username:$secret"
            }
        }
        /**
         * [advertiseUrl] with both secrets masked, for the Video Servers preview.
         *
         * ⚠ The SRT read form carries the PASSPHRASE IN THE URL, which the push form never
         * does. A preview is read over a pilot's shoulder and screenshotted into training
         * material, thus it is masked here the same as a password. The empty case still reads
         * "(NO PASSWORD)" rather than the same `***`, for the reason [urlSafe] gives: a mask
         * that cannot tell "set" from "unset" cannot answer the question the preview exists for.
         */
        fun advertiseUrlSafe(): String {
            if (!advertiseEnabled) return ""
            val h = advertiseHost.ifEmpty { host }
            return when (advertiseTransport) {
                VideoTransport.RTSP -> {
                    val cred = if (advertiseUser.isEmpty()) "" else
                        "$advertiseUser:${if (advertisePass.isEmpty()) "(NO PASSWORD)" else "***"}@"
                    "rtsp://$cred$h:$advertisePort/${streamPath()}?tcp"
                }
                VideoTransport.SRT -> {
                    val id = if (advertiseUser.isEmpty()) "read:${streamPath()}" else
                        "read:${streamPath()}:$advertiseUser:" +
                            (if (advertisePass.isEmpty()) "(NO PASSWORD)" else "***")
                    val phrase = if (advertisePassphrase.isEmpty()) "" else "&passphrase=***"
                    "srt://$h:$advertiseSrtPort?streamid=$id$phrase"
                }
            }
        }

        private fun enc(s: String): String =
            java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    }

    /**
     * The two push clients behind one set of calls.
     *
     * Only [start] knows which one is in use. Everything after it — the encoder callback, the
     * connection handlers, the teardown — works through this, because the transport is a
     * property of the link and not of the video.
     *
     * [droppedVideoFrames] is the counter that says the SEND QUEUE overflowed, which means the
     * encoder is producing faster than the link can carry. It is the one number that separates
     * "this link is lossy" from "this link is too slow", and it is why the two are logged
     * together. See the frame log in [countFrame].
     */
    private interface PushClient {
        fun connect(url: String)
        fun disconnect()
        fun reConnect(delayMs: Long)
        fun shouldRetry(reason: String): Boolean
        fun setVideoInfo(sps: ByteBuffer, pps: ByteBuffer, vps: ByteBuffer?)
        fun sendVideo(buffer: ByteBuffer, info: MediaCodec.BufferInfo)
        val droppedVideoFrames: Long
    }

    private class RtspPushClient(private val c: RtspClient) : PushClient {
        override fun connect(url: String) = c.connect(url)
        override fun disconnect() = c.disconnect()
        override fun reConnect(delayMs: Long) = c.reConnect(delayMs)
        override fun shouldRetry(reason: String) = c.shouldRetry(reason)
        override fun setVideoInfo(sps: ByteBuffer, pps: ByteBuffer, vps: ByteBuffer?) =
            c.setVideoInfo(sps, pps, vps)
        override fun sendVideo(buffer: ByteBuffer, info: MediaCodec.BufferInfo) =
            c.sendVideo(buffer, info)
        override val droppedVideoFrames: Long get() = c.droppedVideoFrames
    }

    private class SrtPushClient(private val c: SrtClient) : PushClient {
        override fun connect(url: String) = c.connect(url)
        override fun disconnect() = c.disconnect()
        override fun reConnect(delayMs: Long) = c.reConnect(delayMs)
        override fun shouldRetry(reason: String) = c.shouldRetry(reason)
        override fun setVideoInfo(sps: ByteBuffer, pps: ByteBuffer, vps: ByteBuffer?) =
            c.setVideoInfo(sps, pps, vps)
        override fun sendVideo(buffer: ByteBuffer, info: MediaCodec.BufferInfo) =
            c.sendVideo(buffer, info)
        override val droppedVideoFrames: Long get() = c.droppedVideoFrames
    }

    private var client: PushClient? = null
    @Volatile private var streaming = false
    @Volatile private var stopped = false
    private var frameCount = 0

    // ---- What the link is actually doing (see countFrame) ----
    private var lastRateLogMs = 0L
    private var framesAtLastLog = 0
    private var bytesSinceLastLog = 0L
    // ⚠ ACCUMULATED, NOT SNAPSHOT. The library reports a rate about once a SECOND; the link
    // line covers TEN. Keeping only the most recent callback printed one arbitrary second
    // beside a ten-second mean, and video is bursty — the second holding a keyframe carries
    // several times the bytes of its neighbours. That produced readings from 26 to 2497 kbps
    // on a link that was doing neither, which is the opposite of what the line is for.
    //
    // Written on the main thread (the library posts there) and drained on the encoder thread,
    // hence the atomics.
    private val wireBitsSum = java.util.concurrent.atomic.AtomicLong(0)
    private val wireSamples = java.util.concurrent.atomic.AtomicInteger(0)

    private var screenEncoder: ScreenCaptureEncoder? = null

    /**
     * True only when the RTSP session is up **and** we have actually pushed video.
     *
     * Both halves are needed. [streaming] alone means the server accepted our session, which
     * says nothing about whether frames are flowing. A pilot reads the LIVE pill to decide
     * whether the team can see what they see, so it must mean bytes are leaving the controller,
     * not that a socket opened.
     */
    /**
     * When bytes last reached the server's socket: the clients report the wire bitrate once a
     * second, and only AFTER a write succeeds. "Live" needs one in the
     * last [WIRE_STALL_MS]. An RTSP write on a silent path blocks for up to ~15 minutes before
     * the kernel gives up, and all that time the encoder went on counting frames.
     */
    @Volatile private var lastWireMs = 0L

    val isLive: Boolean get() = streaming && frameCount > 0 &&
        android.os.SystemClock.elapsedRealtime() - lastWireMs < WIRE_STALL_MS

    /**
     * Returns false if the stream could not even be attempted, so the caller can drop this
     * instance instead of keeping a dead one. Without that, `streamer != null` stayed true
     * after a failed start and the flight screen's LIVE pill lit up while nothing was being
     * sent — the toolbar claiming the team had video when it did not.
     */
    fun start(): Boolean {
        // Screen capture deliberately does NOT require the aircraft. Mirroring the screen is what
        // makes the push survive a link drop or a battery change: the viewer keeps seeing the
        // controller instead of the feed going dead, and a stream can be brought up before the
        // aircraft is even powered.
        stopped = false

        // ⚠ SRT carries the credentials inside the stream id, and the colon separates its
        // parts. A password with a colon in it produces a stream id the server reads wrongly
        // and refuses, and the pilot sees only "the stream will not start". Say so in the log.
        if (config.transport == VideoTransport.SRT && config.password.contains(':')) {
            AppLog.w(TAG, "the video password contains a colon — SRT cannot carry it, " +
                    "the server will refuse this stream. Change the password or use RTSP.")
        }

        // ⚠ SRT REFUSES A PASSPHRASE OUTSIDE 10–79 CHARACTERS BY THROWING. Caught here rather
        // than at the client, because an exception out of start() takes the LIVE tap with it
        // and the pilot gets no reason at all. Told, not logged: this is a pilot-fixable
        // mistake on the screen they just left.
        if (config.transport == VideoTransport.SRT && config.srtPassphrase.isNotEmpty() &&
            config.srtPassphrase.length !in SRT_PASSPHRASE_MIN..SRT_PASSPHRASE_MAX) {
            AppLog.w(TAG, "SRT passphrase is ${config.srtPassphrase.length} characters — " +
                    "must be $SRT_PASSPHRASE_MIN–$SRT_PASSPHRASE_MAX")
            onStatus(false, "SRT passphrase must be $SRT_PASSPHRASE_MIN–$SRT_PASSPHRASE_MAX characters")
            return false
        }

        val push: PushClient = when (config.transport) {
            VideoTransport.RTSP -> RtspPushClient(RtspClient(this).apply {
                setLogs(false)
                // TCP always. The UDP option went with the checkbox — see [VideoTransport.RTSP].
                setProtocol(Protocol.TCP)
                if (config.username.isNotEmpty()) setAuthorization(config.username, config.password)
                setOnlyVideo(true)
                setReTries(10)
            })
            VideoTransport.SRT -> SrtPushClient(SrtClient(this).apply {
                setLogs(false)
                // Clamped again here, not because the pref path does not clamp, but because
                // this is the last point before the value reaches a 16-bit wire field.
                latencyMs = VideoTransport.clampLatencyMs(config.srtLatencyMs)
                // Encryption. AES-128 is what a passphrase alone selects at the other end:
                // the key length travels in the key-material message, so the publisher picks
                // it and the server follows. Empty means no encryption, which is correct for
                // a server that sets no passphrase and REFUSED BY ONE THAT DOES.
                if (config.srtPassphrase.isNotEmpty()) {
                    setPassphrase(config.srtPassphrase, EncryptionType.AES128)
                }
                // ⚠ NO setAuthorization CALL. It throws in this client — the credentials are
                // already in the url that pushUrl() built. See the note on that function.
                setOnlyVideo(true)
                setReTries(10)
                // The MPEG-TS mux has to know the codec; the default is H.264, so an H.265
                // stream sent without this is muxed under the wrong stream type and no
                // viewer decodes it. RTSP learns the codec from the VPS instead.
                setVideoCodec(
                    if (config.videoCodec.isHevc) com.pedro.common.VideoCodec.H265
                    else com.pedro.common.VideoCodec.H264
                )
            })
        }
        client = push

        // The encoder must be producing parameter sets BEFORE connect — connect()'s worker waits
        // up to 5s for setVideoInfo.
        val enc = ScreenCaptureEncoder(
            context, mediaProjection, config.transcodeProfile, config.videoCodec,
            onEncoded = { buf, bufInfo ->
                val size = bufInfo.size
                push.sendVideo(buf, bufInfo)
                // isLive gates the LIVE pill on frameCount, so without this the pill would sit
                // on amber forever while a screen capture streamed perfectly well.
                frameCount++
                countFrame(size)
            },
            onParamsReady = { spsB, ppsB, vpsB -> push.setVideoInfo(spsB, ppsB, vpsB) },
            // ⚠ THE ENCODER STOPPED AND THE CAPTURE IS ALREADY DOWN. Stop the push too and say
            // so: a stream whose encoder died sends nothing, and the pilot must not be left
            // with a LIVE pill over a feed the team stopped receiving. See
            // ScreenCaptureStallPolicy for why this is not the same as a slow encoder.
            onStalled = { ageMs ->
                AppLog.e(TAG, "screen capture stalled for ${ageMs}ms — stopping the stream")
                runCatching { stop() }
                onStatus(false, "Video stopped: the encoder stalled. Touch LIVE to start again.")
                gaveUp()
            },
            onGone = { message ->
                AppLog.e(TAG, "screen capture gone — stopping the stream")
                if (!stopped) {
                    runCatching { stop() }
                    onStatus(false, message)
                    gaveUp()
                }
            },
        )
        if (!enc.start()) {
            onStatus(false, "Screen capture failed to start")
            client = null
            return false
        }
        screenEncoder = enc

        push.connect(config.pushUrl())
        // ⚠ NEVER LOG pushUrl(). With SRT the credentials are IN that string — the stream id
        // carries them — so logging it would write the video password to app.log, which is
        // shared with the flight records. urlSafe() is the same address with the password
        // masked, and it is the only form that may be logged or shown.
        val lat = if (config.transport == VideoTransport.SRT)
            ", ${VideoTransport.clampLatencyMs(config.srtLatencyMs)}ms buffer" else ""
        AppLog.i(TAG, "push=${config.urlSafe()}  advertise=rtsp://…:${config.rtspPort}" +
                "  [${config.transport.label}$lat, ${config.transcodeProfile.name}: screen capture]")
        onStatus(true, "Starting ${config.transport.label} push → ${config.urlSafe()}")
        return true
    }

    fun stop() {
        stopped = true
        try { client?.disconnect() } catch (t: Throwable) { AppLog.w(TAG, "disconnect: ${t.message}") }
        client = null
        screenEncoder?.release()
        screenEncoder = null
        streaming = false
    }

    /**
     * WHAT THE LINK IS DOING, every [RATE_LOG_INTERVAL_MS], at INFO so it is in `app.log`
     * without Detailed logging on.
     *
     * ⚠ **This exists to answer one question, and only these two numbers together answer it.**
     * A stream that looks bad in the field fails in one of two ways, and they need opposite
     * fixes:
     *
     *  - `drops` climbing means the SEND QUEUE overflowed. The encoder is making more video
     *    than the link can carry. That is BANDWIDTH, and no transport fixes it — the answer is
     *    a lower video quality.
     *  - `drops` at zero with the rate at target, but a torn picture at the far end, means
     *    packets are being LOST on the way. That is what SRT recovers and RTSP does not.
     *
     * Before this line existed neither number left the process: the library counted the drops
     * and nothing read the counter, and the bitrate callback was an empty function. A stream
     * that degraded in flight was invisible afterwards, which is why the question of whether
     * a deployment needs SRT could not be answered from the flight records.
     */
    private fun countFrame(size: Int) {
        // ⚠ ONLY WHILE CONNECTED. The encoder runs from the moment LIVE is tapped, thus a push
        // that never connected still had frames to count, and this line reported "199kbps
        // 7fps" for a stream the server had refused — the most misleading thing it could say
        // to somebody reading the log to find out why there was no video.
        if (!streaming) return
        // THE PROGRESS WATCHDOG. Frames are being made and nothing has reached
        // the socket for WIRE_STALL_MS: the path is dead even though no client said so. Fail
        // it into the normal path — retry, and give up when the retries run out. A reconnect
        // closes the socket, which also frees an RTSP write blocked on it.
        val nowE = android.os.SystemClock.elapsedRealtime()
        if (lastWireMs > 0 && nowE - lastWireMs > WIRE_STALL_MS) {
            lastWireMs = nowE   // once per stall, not once per frame
            AppLog.w(TAG, "no data reached the server for ${WIRE_STALL_MS / 1000} s — treating the link as down")
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                if (!stopped && streaming) handleFailed("no data reached the server for ${WIRE_STALL_MS / 1000} s")
            }
            return
        }
        bytesSinceLastLog += size
        val now = System.currentTimeMillis()
        if (lastRateLogMs == 0L) { lastRateLogMs = now; framesAtLastLog = frameCount; return }
        val elapsed = now - lastRateLogMs
        if (elapsed < RATE_LOG_INTERVAL_MS) return

        val frames = frameCount - framesAtLastLog
        val kbps = (bytesSinceLastLog * 8L) / elapsed          // bytes/ms*8 == kbit/s
        val fps = frames * 1000L / elapsed
        // The library's own measure, averaged over the SAME window as the rate above it. It
        // counts what reaches the socket — protocol headers, the MPEG-TS packing under SRT,
        // and any retransmission — thus it sits above the payload rate. Well above it means
        // the link is repairing itself. Absent until the first callback.
        val wireBits = wireBitsSum.getAndSet(0)
        val wireN = wireSamples.getAndSet(0)
        val wire = if (wireN > 0) " wire=${wireBits / wireN / 1000}kbps" else ""
        AppLog.i(TAG, "link [${config.transport.label}]: ${kbps}kbps$wire  ${fps}fps  " +
                "drops=${client?.droppedVideoFrames ?: 0}  frames=$frameCount")

        lastRateLogMs = now
        framesAtLastLog = frameCount
        bytesSinceLastLog = 0
    }

    // ---- Connection handlers, shared by both transports ----
    //
    // The two clients report the same events under different names. Each override below is one
    // line into one of these, so the behaviour cannot drift between transports.

    private fun handleConnected() {
        // A stopped instance can still hear "connected": SRT's disconnect only LAUNCHES its
        // coroutine, and a handshake answer already posted then arrives after the stop. It
        // must not put the url back on the CoT.
        if (stopped) {
            AppLog.i(TAG, "${config.transport.label} push connected after stop — ignored")
            return
        }
        lastWireMs = android.os.SystemClock.elapsedRealtime()   // the grace period starts here
        streaming = true
        AppLog.i(TAG, "${config.transport.label} push connected")
        // Arm the packetiser with a fresh IDR so a viewer joining now gets a picture without
        // waiting out the 2s GOP. Only the screen encoder can be asked on demand — the
        // aircraft's keyframe cadence is not ours to control.
        screenEncoder?.requestSyncFrame()
        onStatus(true, "Streaming → ${config.urlSafe()}")
    }

    private fun handleFailed(reason: String) {
        // The pilot stopped the push, and the server then closed its side. That is the normal
        // end of a stream and not a fault. Previously this logged "connection failed"
        // and put "Stream failed: Shutdown received from server" on the status line after
        // EVERY stop, which read as a fault that was not there.
        if (stopped) {
            AppLog.i(TAG, "push closed after stop: $reason")
            streaming = false
            return
        }
        AppLog.w(TAG, "connection failed: $reason")
        // ⚠ A REFUSAL IS NOT A DROPPED LINK, so retrying it only hammers the server. In
        // one test a missing SRT passphrase produced SRT_REJ_PEER twelve times over 24
        // seconds, and every attempt was answered the same way — the server had already
        // decided. The RTSP side has always given up at once on its auth error; this is the
        // same rule for the transport that reports the same thing under another name.
        if (NON_RETRYABLE.any { it in reason }) {
            AppLog.w(TAG, "the server refused this stream — not retrying")
            streaming = false
            onStatus(false, "Stream refused by the server — check the passphrase and the login")
            gaveUp()
            return
        }
        val c = client
        if (!stopped && c != null && c.shouldRetry(reason)) {
            // NOT live while it retries: neither client reports a disconnect on this path, so
            // the pill stayed green and the Emergency notice said "video on all channels" for
            // up to ~70 s with nothing going out. handleConnected sets it again.
            // The url stays advertised through the retries (design decision).
            streaming = false
            c.reConnect(2000)
        } else {
            streaming = false
            onStatus(false, "Stream failed: $reason")
            gaveUp()
        }
    }

    /**
     * This instance will send nothing more: the encoder stalled, the server refused, the
     * retries ran out, or the login failed. Hand it back to [VideoStreamerHolder], which drops
     * it and clears the url. Before this, the dead instance stayed parked in the holder, so
     * `isActive` stayed true: the pill sat on amber, one LIVE touch only stopped it, and an
     * Emergency Broadcast start took it for "connecting" and did not restart the stream.
     * Posted, because the clients report on their own threads.
     */
    private fun gaveUp() {
        val me = this
        android.os.Handler(android.os.Looper.getMainLooper()).post { VideoStreamerHolder.dropIfCurrent(me) }
    }

    // ---- ConnectCheckerRtsp (the RTSP client) ----

    override fun onConnectionStartedRtsp(rtspUrl: String) { AppLog.i(TAG, "connecting ${config.urlSafe()}") }
    override fun onConnectionSuccessRtsp() = handleConnected()
    override fun onConnectionFailedRtsp(reason: String) = handleFailed(reason)
    override fun onDisconnectRtsp() { streaming = false; AppLog.i(TAG, "disconnected") }
    override fun onAuthErrorRtsp() { streaming = false; onStatus(false, "Stream auth error (check user/pass)"); gaveUp() }
    override fun onAuthSuccessRtsp() { AppLog.i(TAG, "auth ok") }
    override fun onNewBitrateRtsp(bitrate: Long) = recordWireBitrate(bitrate)

    // ---- ConnectChecker (the SRT client) ----

    override fun onConnectionStarted(url: String) { AppLog.i(TAG, "connecting ${config.urlSafe()}") }
    override fun onConnectionSuccess() = handleConnected()
    override fun onConnectionFailed(reason: String) = handleFailed(reason)
    override fun onDisconnect() { streaming = false; AppLog.i(TAG, "disconnected") }
    /** SRT has no credential exchange of its own — a bad user or password comes back as a
     *  refused connection, not as this. Kept because the interface has it. */
    override fun onAuthError() { streaming = false; onStatus(false, "Stream auth error (check user/pass)"); gaveUp() }
    override fun onAuthSuccess() { AppLog.i(TAG, "auth ok") }
    override fun onNewBitrate(bitrate: Long) = recordWireBitrate(bitrate)

    /** One per-second sample from whichever client is running. Averaged in [countFrame]. */
    private fun recordWireBitrate(bitrate: Long) {
        lastWireMs = android.os.SystemClock.elapsedRealtime()
        wireBitsSum.addAndGet(bitrate)
        wireSamples.incrementAndGet()
    }

    companion object {
        private const val RATE_LOG_INTERVAL_MS = 10_000L

        /** No bytes on the wire for this long, with frames being made, is a dead link. */
        private const val WIRE_STALL_MS = 10_000L

        /** The library's own limits — outside these [SrtClient.setPassphrase] throws. */
        private const val SRT_PASSPHRASE_MIN = 10
        private const val SRT_PASSPHRASE_MAX = 79

        /**
         * Failures where the far end has DECIDED, thus a retry changes nothing.
         *
         * `SRT_REJ_PEER` is the server refusing the publish — a missing or wrong passphrase, a
         * credential it does not accept, or a path it will not take. All three are fixed on a
         * screen, never by waiting.
         */
        private val NON_RETRYABLE = listOf(
            "SRT_REJ_PEER", "Endpoint malformed", "access denied",
        )
        private const val TAG = "AutelVideoStreamer"
    }
}

/** Process-wide holder so the stream survives screen navigation. */
object VideoStreamerHolder {
    private var streamer: AutelVideoStreamer? = null

    /** Notified on every start/stop so UI (e.g. the flight-screen play button) refreshes. */
    @JvmField
    var onStateChanged: Runnable? = null
    private fun notifyState() {
        android.os.Handler(android.os.Looper.getMainLooper()).post { onStateChanged?.run() }
    }

    /**
     * What the flight screen's LIVE pill should show.
     *
     *  - [OFF] — nothing running, including a start that failed outright.
     *  - [CONNECTING] — a start was accepted and the RTSP client is connecting or retrying,
     *    but no video has gone out yet.
     *  - [LIVE] — video is genuinely being pushed.
     */
    enum class State { OFF, CONNECTING, LIVE }

    /** Returns false if the stream could not be started at all. */
    fun start(
        context: Context,
        config: AutelVideoStreamer.VideoConfig,
        onStatus: (Boolean, String) -> Unit,
        projection: android.media.projection.MediaProjection,
    ): Boolean {
        streamer?.stop()
        val s = AutelVideoStreamer(context.applicationContext, config, onStatus, projection)
        // Keep the instance ONLY if it started. A failed start used to leave a dead streamer
        // parked here, which made isActive true and lit the LIVE pill with nothing streaming.
        val ok = s.start()
        streamer = if (ok) s else null
        // ⚠ AND THE OLD ADVERTISEMENT GOES WITH A FAILED START. The
        // line above stopped the previous streamer without clearing its url, so a restart that
        // failed left that url on the bridge with isActive false: every "is anything live?"
        // check passed while the CoT went on naming a server — credentials included — that
        // nothing was streaming to. isActive == false now always means nothing advertised.
        if (!ok) TakBridgeHolder.setVideoUrl(null)
        notifyState()
        return ok
    }

    fun stop() {
        streamer?.stop()
        streamer = null
        TakBridgeHolder.setVideoUrl(null)
        notifyState()
    }

    /** [stop], but only if [s] is still the current streamer — a newer start must not be
     *  stopped by an old instance that gave up late. Main thread. */
    internal fun dropIfCurrent(s: AutelVideoStreamer) {
        if (streamer !== s) return
        AppLog.i("VideoStreamerHolder", "the stream gave up — dropped, url cleared")
        // As the LIVE stop does: the capture service goes with the stream. The flight screen's
        // onStop stops it only while isActive, which is false from here on.
        com.autel.sdksample.TestApplication.getInstance()?.let { ctx ->
            runCatching { ScreenCaptureService.stop(ctx) }
        }
        stop()
    }

    val state: State
        get() {
            val s = streamer ?: return State.OFF
            return if (s.isLive) State.LIVE else State.CONNECTING
        }

    /** True once video is actually going out — what the HUD's "VID" indicator means. */
    val isRunning: Boolean get() = streamer?.isLive == true

    /** A stream is set up and should be torn down by a second tap. NOT "is it working" —
     *  use [state] for anything the pilot reads. */
    val isActive: Boolean get() = streamer != null

    /**
     * Why a start attempt did not result in a stream. Three outcomes, not a boolean: the
     * caller must not tell a pilot to "set up the stream in Pre-Flight Setup" when the stream
     * IS set up and it simply failed to start.
     */
    enum class StartResult { STARTED, NOT_CONFIGURED, FAILED }

    /**
     * Start streaming using the video settings saved by TakConnectActivity, capturing the flight
     * screen via [projection]. Reached from [ScreenCaptureService] once it holds a granted
     * projection (the flight-screen LIVE button requests the projection, which routes here).
     */
    fun startFromPrefs(
        context: Context,
        onStatus: (Boolean, String) -> Unit,
        projection: android.media.projection.MediaProjection,
    ): StartResult {
        val p = context.getSharedPreferences("takpilot2_tak", Context.MODE_PRIVATE)
        val host = p.getString("video_host", "") ?: ""
        val streamId = p.getString("video_streamid", "") ?: ""
        if (host.isEmpty() || streamId.isEmpty()) return StartResult.NOT_CONFIGURED
        val transport = VideoTransport.fromPref(p.getString("video_transport", null))
        val cfg = AutelVideoStreamer.VideoConfig(
            host = host,
            streamId = streamId,
            // ⚠ Keys must match what VideoServersActivity.mirrorActiveSlot WRITES — the one
            // mirror. They are literals here; if one moves, move both.
            //
            // The RTSP set is read whichever transport is selected — it is what the CoT
            // advertises. The SRT set is read too and simply goes unused under RTSP.
            username = p.getString("video_user", "") ?: "",
            password = p.getString("video_pass", "") ?: "",
            rtspPort = p.getInt("video_rtsp_port", VideoTransport.RTSP.defaultPort),
            // Resolved by mirrorActiveSlot: which server the team is pointed at, or none.
            advertiseEnabled = p.getBoolean("video_adv_on", true),
            advertiseHost = p.getString("video_adv_host", "") ?: "",
            advertisePort = p.getInt("video_adv_port", VideoTransport.RTSP.defaultPort),
            advertiseUser = p.getString("video_adv_user", "") ?: "",
            advertisePass = p.getString("video_adv_pass", "") ?: "",
            advertiseTransport =
                VideoTransport.fromPref(p.getString("video_adv_transport", null)),
            advertiseSrtPort =
                p.getInt("video_adv_srt_port", VideoTransport.SRT.defaultPort),
            advertisePassphrase = p.getString("video_adv_srt_phrase", "") ?: "",
            srtPort = p.getInt("video_srt_port", VideoTransport.SRT.defaultPort),
            transport = transport,
            // Read on every start, so a change on the Debug screen takes effect at the next
            // LIVE and not at the next application launch.
            srtLatencyMs = VideoTransport.srtLatencyMs(p),
            // Written by mirrorActiveSlot, same as the password above.
            srtPassphrase = p.getString("video_srt_passphrase", "") ?: "",
            profile = p.getString("video_profile", "standard") ?: "standard",
            codec = p.getString("video_codec", VideoCodec.H264.prefValue)
                ?: VideoCodec.H264.prefValue,
            randomizePath = p.getBoolean(StreamPath.PREF_RANDOMIZE, false),
        )
        // Advertise the CONFIGURED address once a start is attempted.
        //
        // ⚠ This fires on the ATTEMPT, not on a connected stream: start() reports
        // onStatus(true, "Starting RTSP push") immediately after client.connect(), and a
        // failing connection then retries without reporting false. So the address is on the
        // wire while the push is still trying, and it stays there through the retries.
        //
        // THIS IS DELIBERATE (design decision). The CoT carries the address the pilot
        // entered, which is what it should carry, and an RTSP viewer retries against a server
        // that comes up a moment later. An earlier version of this comment claimed the URL was
        // published only after the push succeeded. It never was, and the behaviour it described
        // is not the behaviour that is wanted.
        val ok = start(context, cfg, { st, msg ->
            // An empty url means the pilot turned the advertisement off. Pass null, which is
            // what clears it — CotBuilder omits the video block for either, but null is the
            // state the rest of the app already understands as "no video address".
            if (st) TakBridgeHolder.setVideoUrl(cfg.advertiseUrl().ifEmpty { null })
            onStatus(st, msg)
        }, projection)
        return if (ok) StartResult.STARTED else StartResult.FAILED
    }

    /** Entry point for [ScreenCaptureService] once it holds a granted projection. */
    fun startScreenCapture(
        context: Context,
        projection: android.media.projection.MediaProjection,
        onStatus: (Boolean, String) -> Unit,
    ): StartResult = startFromPrefs(context, onStatus, projection)
}
