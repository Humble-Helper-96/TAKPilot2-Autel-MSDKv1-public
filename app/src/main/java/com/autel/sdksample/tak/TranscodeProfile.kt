package com.autel.sdksample.tak

/**
 * The pilot-selectable video-quality tiers for the outbound RTSP push.
 *
 * Consumed by [ScreenCaptureEncoder], which caps the captured screen to [maxHeight] (a CEILING
 * on the vertical dimension, NOT a format — the screen's aspect ratio is preserved and the width
 * follows from it, so this 4:3 controller at the 768 tier is 1024x768, not 1366x768) and encodes
 * at [fps] / [bitrateBps].
 *
 * ⚠ **[maxHeight] IS SET FROM THE PANEL, NOT FROM A ROUND NUMBER.** LOW and STANDARD divide this
 * controller's 1536-pixel height by 4 and by 2. Read the note on the constants before you change
 * either, and re-do that arithmetic for any other panel — these values do not travel between
 * controllers.
 *
 * **Each tier carries one resolution step MORE than an H.264 ladder would** (480/720/1080 here vs
 * 360/480/720). That extra step is bought with H.265, which delivers roughly the same
 * quality as H.264 at about half the bitrate — the saving spent on resolution rather than
 * bandwidth. (H.264 was tried briefly to chase a keyframe pulse and reverted: the pulse went, but at the same bitrate the picture was visibly worse. See
 * [ScreenCaptureEncoder]'s `OUT_MIME` for the full history.)
 *
 * **Bitrates are set from bits-per-pixel-per-frame, not picked round.** STANDARD and HIGH sit at
 * the same ~0.077 bits/pixel/frame, so moving between those two trades resolution and frame rate
 * WITHOUT changing per-pixel quality. LOW is the deliberate exception at ~0.12 — see the note on
 * the constants. Screen capture makes the top of this ladder matter: HUD text,
 * map labels and AR callouts are sharp high-frequency edges that cost far more bits than camera
 * video and smear first, and an illegible altitude readout defeats the point of streaming the
 * screen at all.
 */
enum class TranscodeProfile(val maxHeight: Int, val fps: Int, val bitrateBps: Int) {
    // STANDARD and HIGH were once doubled to buy keyframe headroom against a 2-SECOND
    // PIXELATED PULSE. That was treating the symptom: the real cause was that the encoder was
    // being asked for CBR, which this chip's HEVC encoder does not even declare (probe:
    // VBR=true CBR=false), so IDRs were quantised down to fit a per-frame budget. Switching to
    // VBR fixed it outright — I/P frame-size ratio 2.53x -> 14x, pulse confirmed
    // gone on the live stream — so the bought headroom is no longer needed and
    // STANDARD/HIGH are back at their original targets. The bandwidth was the point: this is a
    // shared tactical hotspot.
    //
    // LOW IS NOT ON THE SAME CURVE AS THE OTHER TWO, ON PURPOSE. It sits at ~0.12 bits/pixel/frame
    // against STANDARD/HIGH at ~0.077 — richer per pixel, but far lower in TOTAL bitrate, because
    // what makes it the marginal-link tier is the total: it is the one that survives a poor
    // cellular uplink. Small frames at 10fps are cheap enough to afford good pixels.
    //
    // History: 275k originally, raised to 475k when 275k was judged
    // "really bad" in flight (video too degraded to read is not survivability, it is just a smaller
    // stream). Trimmed to 375k to buy back cellular headroom now that VBR spends
    // the budget properly — 275k was NOT retried, since VBR redistributes bits rather than
    // creating them and would not rescue it.
    //
    // ⚠ **375k -> 250k, AND THE REASON IS THE 4:1 SCALE, NOT A NEW OPINION.**
    // Every number above was measured at 640x480. The tier is 512x384 now, which is 36 % fewer
    // pixels, thus 375k became 0.191 bits/pixel — more precision than a frame that small can
    // show. Flown, the picture was judged "VERY poor, due to the very low res",
    // which is the signal that the limit is the RESOLUTION and the extra bits buy nothing.
    //
    // The anchor for 250k is the flown configuration, not a guess:
    //
    //   640x480 @ 375k  = 0.122 bpp   flown for weeks, acceptable      (Baseline profile)
    //   640x480 @ 275k  = 0.090 bpp   "really bad"
    //   512x384 @ 250k  = 0.127 bpp   THIS — slightly ABOVE the flown budget, at 33 % less
    //   512x384 @ 175k  = 0.089 bpp   the floor: this is the "really bad" budget again
    //
    // So the picture keeps the per-pixel budget that was already accepted and gives back a
    // third of the bandwidth — on the one tier where TOTAL bitrate is the whole point. It
    // should in fact look better than the old 375k did, because H.264 now asks for HIGH
    // profile and CABAC is worth another 10 to 15 % (see VideoCodec).
    //
    // ⚠ DO NOT GO BELOW 200k WITHOUT FLYING IT. 175k is the measured "really bad" budget and
    // VBR cannot rescue a budget that is simply too small.
    //
    // LOW is STILL the richer tier per pixel, which is the design above: 0.127 against
    // STANDARD's 0.068. Lowering the total did not flatten the curve.
    //
    // ⚠ So the three tiers no longer share bits/pixel, and LOW can look BETTER per pixel than
    // STANDARD. That is the design, not a bug — do not "fix" it by flattening the curve.
    //
    // ---- INTEGER SCALING (design decision) ----
    //
    // LOW and STANDARD now divide the panel by a WHOLE NUMBER. The controller is 2048x1536, thus:
    //
    //   LOW       1536 / 4 = 384   ->  512x384    (was 480 -> 640x480,  a 3.2 : 1 scale)
    //   STANDARD  1536 / 2 = 768   ->  1024x768   (was 720 -> 960x720,  a 2.133 : 1 scale)
    //
    // A whole-number scale puts each output pixel over an exact block of source pixels: 4 for
    // LOW, 16 for STANDARD... no — 2x2=4 for STANDARD and 4x4=16 for LOW. A fractional scale
    // does not, thus each output pixel takes an uneven share of its neighbours. On camera video
    // that is a small softness. ON TEXT IT IS NOT: the HUD readouts, the map labels and the AR
    // callouts are thin high-contrast strokes, and an uneven share breaks a stroke into light
    // and dark parts that also SHIMMER as the picture moves. That is the worst thing this
    // stream does, and the reason the tiers are set from the panel and not from a round number.
    //
    // ⚠ THESE ARE THE ONLY TWO CLEAN STEPS THAT EXIST HERE, and the arithmetic says so:
    // 2048 = 2^11 (no factor of 3) and 1536 = 2^9 x 3, thus every common divisor is a power of
    // two. A 3:1 scale gives 682.67 x 512 — the height divides and the WIDTH DOES NOT, which is
    // worse than a fractional scale on both axes, because the fault is then different in each
    // direction. The next step, 8:1, is 256x192 and far too small to read.
    //
    // ⚠ HIGH CANNOT BE MADE CLEAN, and it is left alone. 1536 / 1080 is 1.422, and the only
    // whole-number scale above 768 is 1:1 (1536), which is the whole panel at a bandwidth a
    // shared field link does not have. Do not "complete the set".
    //
    // The bits-per-pixel move with the pixels, at the SAME bitrate:
    //   LOW       0.122 -> 0.191   (36 % fewer pixels, thus richer)
    //   STANDARD  0.077 -> 0.068   (14 % more pixels, thus slightly leaner)
    // STANDARD trades a little per-pixel budget for a clean scale. That is the judgement to
    // re-examine if the picture is worse rather than better.
    LOW(384, 10, 250_000),        // marginal/cellular links — lowest total bitrate, 4:1 scale
    STANDARD(768, 15, 800_000),   // default — 2:1 scale, the cleanest reduction available
    HIGH(1080, 15, 1_800_000);    // 1080p. NOT an integer scale — see the note above

    /** The `video_profile` pref value. Derived, so a new tier cannot be saved under a typo. */
    val prefValue: String get() = name.lowercase()

    /**
     * What the pilot sees in a picker. Deliberately JUST the tier name — no resolution, fps or
     * bitrate. Those numbers change (they have three times now), and a menu that restates them
     * is one more place to forget to update; the tiers are already ordered, which is the only
     * thing a pilot needs to choose between them.
     */
    val label: String get() = name.lowercase().replaceFirstChar { it.uppercase() }

    companion object {
        fun fromPref(name: String?): TranscodeProfile =
            values().firstOrNull { it.prefValue == name } ?: STANDARD
    }
}
