package com.autel.sdksample.tak

import android.media.MediaCodecInfo

/**
 * The pilot-selectable video codec for the outbound RTSP push.
 *
 * This was once a compile-time constant. It is a pilot choice now because the right
 * answer depends on WHO IS WATCHING, which is a field decision and not a build decision:
 *
 *  - **[H264] is the most compatible.** It plays on the widest range of clients, including
 *    anything that decodes video in a browser.
 *  - **[H265] is the more efficient.** It delivers roughly the quality of H.264 at about half the
 *    bitrate, and [TranscodeProfile] spends that saving on resolution — every tier here carries
 *    one step more than the blueprint because of it. Fewer clients can play it.
 *
 * So neither is correct in general. A pilot on a constrained uplink whose team can decode H.265
 * wants the sharper picture; one who cannot be sure what the team is running wants H.264.
 *
 * **Deliberately no named clients here or on the Pre-Flight screen.** Which player supports which
 * codec changes with every release of that player, and a note naming one is wrong from the day it
 * changes — with no way for this file to find out. The tier names carry the trade; the field
 * carries the specifics.
 *
 * ## Why H.265 failures are hard to diagnose
 *
 * Worth knowing even without naming names: when a client cannot decode H.265 it usually fails at
 * a layer that says nothing about video. A client repackaging the stream for browser playback
 * returned a bare `HTTP 500` on its manifest — no video error, no mention of a codec. Nothing is
 * visible from the controller either: the push, the bitrate and the frame counts all look
 * perfectly healthy, because they are.
 *
 * So if viewers report a broken stream that this end says is fine, **suspect the codec early**,
 * and prove the source from outside before changing anything here:
 * `ffprobe -v error -show_entries stream=codec_name "rtsp://…"`. If that reads the stream, the
 * push and the credentials are good and the fault is in the receiver.
 *
 * ## History, because this has moved four times
 *
 *  - H.265 @ 800kbps: a 2-second pixelated pulse, visible only to stream viewers (the artifact is
 *    created by this re-encode, so it exists only in the outgoing stream).
 *  - Bitrates roughly doubled. Pulse persisted.
 *  - H.264 at the raised bitrates: the picture was judged noticeably worse. Expected — AVC needs
 *    about twice HEVC's bitrate for equal quality, so the doubling only bought back what the
 *    codec gave up. Reverted the same day.
 *  - VBR fixed the pulse outright: the encoder had been starving keyframes under
 *    CBR, I/P went 2.53x -> 14x. Bitrates went back to their original targets.
 *  - Made selectable, after an H.265 stream proved unplayable for a browser-based
 *    viewer while being perfectly healthy on the wire.
 *
 * **If the pulse ever returns, the codec is not the knob.** VBR suppresses it, and it works on
 * both encoders here — I/P measured 51x on H.264. Check the `frame mix:` line in the log first.
 */
enum class VideoCodec(val mime: String, val label: String) {
    /**
     * H.264 / AVC. The compatible choice — the safe default when the viewing clients are not
     * known in advance.
     *
     * ## High profile, with B-frames switched off (design decision)
     *
     * This once asked for **Baseline**. The reason recorded here was B-frames: they
     * reorder the output, which adds latency to a feed that exists to tell a pilot what happens
     * now, and they make the packetiser's timestamps harder. That cost is real.
     *
     * **But the profile does not decide the B-frames.** High PERMITS them; it does not use them.
     * `ScreenCaptureEncoder` now sets `KEY_MAX_B_FRAMES` to 0 with every format it tries, thus
     * the encoder cannot make one whatever the profile allows. The latency property stays.
     *
     * What High adds, and Baseline cannot do:
     *  - **CABAC** entropy coding. Baseline has only CAVLC. CABAC is worth 10 to 15 % of the
     *    bitrate at the same picture, and MORE at a low bitrate.
     *  - **The 8x8 transform.** It helps most on large flat areas and on sharp text — the sky,
     *    the terrain and the HUD readouts, which is what this stream sends.
     *
     * Measured before the change: 960x720 at 15 fps and 800 kbps is 0.077 bits for each pixel.
     * That is a low number, thus the CABAC gain is at the high end of its range.
     *
     * ⚠ **Compatibility does not change.** Every H.264 decoder made in the last 15 years reads
     * High profile; it is what broadcast and streaming use. Baseline is for hardware from before
     * that. This is not the H.265 question — see [H265].
     *
     * Neither profile is load-bearing: the variant ladder in [ScreenCaptureEncoder] gives up
     * profile and level if the encoder refuses them. Look for `full (profile+level…)` in the
     * `screen capture` log line to confirm that High was ACCEPTED, because a silent fall to a
     * lower rung looks the same as a change that did nothing.
     */
    H264("video/avc", "H.264"),

    /** H.265 / HEVC. The efficient choice: better picture for the same bitrate, on the clients
     *  that can decode it. See the class note on why its failures are hard to diagnose. */
    H265("video/hevc", "H.265");

    val isHevc: Boolean get() = this == H265

    /**
     * Profile and level to ASK for.
     *
     * These MUST track the codec. The HEVC and AVC profile constants are separate numeric spaces
     * that happen to collide — `HEVCProfileMain` and `AVCProfileBaseline` are both 1 — so leaving
     * the HEVC values in place while encoding AVC "works" by accident and means nothing. That is
     * exactly the kind of thing that reads as correct for a year.
     *
     * Neither value is load-bearing: the variant ladder in [ScreenCaptureEncoder] drops
     * profile/level entirely if the encoder rejects them, and this SoC's legacy OMX components
     * have rejected them before.
     */
    val profile: Int get() = when (this) {
        H265 -> MediaCodecInfo.CodecProfileLevel.HEVCProfileMain
        // High, not Baseline — see the note on [H264]. Safe only while ScreenCaptureEncoder
        // sets KEY_MAX_B_FRAMES to 0. Do not remove that line and keep this one.
        H264 -> MediaCodecInfo.CodecProfileLevel.AVCProfileHigh
    }

    val level: Int get() = when (this) {
        H265 -> MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel4
        H264 -> MediaCodecInfo.CodecProfileLevel.AVCLevel4
    }

    /** The `video_codec` pref value. Derived, so a codec cannot be saved under a typo. */
    val prefValue: String get() = name.lowercase()

    companion object {
        /**
         * Default [H264]. The safe default is the one whose failure mode is visible: choosing
         * H.264 where H.265 would have worked costs a slightly softer picture, which the pilot
         * can see. Choosing H.265 where it is not supported costs the whole stream for those
         * viewers, and shows nothing at all on the controller.
         */
        fun fromPref(name: String?): VideoCodec =
            values().firstOrNull { it.prefValue == name } ?: H264
    }
}
