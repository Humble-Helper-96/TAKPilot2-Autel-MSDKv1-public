package com.autel.sdksample.tak

import android.content.Context
import com.taklite.util.AppLog
import java.security.SecureRandom

/**
 * THE ONE PLACE THAT COMPOSES THE STREAM PATH.
 *
 * The path is the name the media server knows the feed by. It goes out in three places and
 * every one of them must agree, or the push lands under one name and the team is told another:
 *
 *  - the SRT stream id, `publish:<path>:<user>:<pass>` ([AutelVideoStreamer.VideoConfig.pushUrl]);
 *  - the RTSP push address, `rtsp://host:port/<path>` (the same function);
 *  - the CoT `__video` url and its `ConnectionEntry` path
 *    ([AutelVideoStreamer.VideoConfig.advertiseUrl], parsed by `CotBuilder.appendVideo`).
 *
 * [VideoConfig.streamPath] is the only caller of [compose], and every address is built from it.
 * Do not build the path a second time anywhere else.
 *
 * ## The shape
 *
 * ```
 *   <broadcast id>-Low                 randomize OFF — unchanged from every earlier version
 *   <broadcast id>-<token>-Low         randomize ON
 * ```
 *
 * The `-Low` suffix stays LAST: the media server's path rules and its transcode logic key on a
 * name that ends in `-Low`, and per-team rules may key on a `<team>-` prefix the pilot types at
 * the front of the broadcast id. The token goes between the two so that both survive.
 *
 * ## The token, and why it lives in memory only
 *
 * A fixed path is guessable. Anyone who can reach the server with any valid credential, or from
 * an address range the server trusts without one, can open a feed by typing its name. The token
 * makes the name unguessable, thus a feed can be watched only by a client that holds the CURRENT
 * CoT.
 *
 * ⚠ **One token per PROCESS.** [sessionToken] is created on first use from [SecureRandom] and
 * then held for the life of the process. It is never written to a preference or a file, so a
 * restart of the application always yields a new one. It does NOT change when the stream stops
 * and starts, when the SRT link drops and reconnects, when the aircraft lands and takes off, or
 * when an activity is recreated — none of those restart the process. A token that changed
 * mid-session would leave the team holding a CoT that names a feed which no longer exists.
 *
 * ⚠ The CoT video uid must NOT follow the token. `CotBuilder.videoUidFor` strips the token
 * segment before it hashes the url, so ATAK keeps one video alias per aircraft instead of one per
 * flight. Pinned in `StreamPathTest`.
 */
object StreamPath {
    private const val TAG = "StreamPath"

    /** The suffix the media server keys on. Kept for every quality tier — see [compose]. */
    const val SUFFIX = "-Low"

    /** Lowercase hex characters in the token. Eight gives 2^32 names; the server has no list
     *  to enumerate against and a guess costs a connection attempt. */
    const val TOKEN_LENGTH = 8

    /** The preference key for the toggle, on the plain `video_*` keys that the streamer reads.
     *  The per-slot form is `vKey(slot, "random_path")`. */
    const val PREF_RANDOMIZE = "video_random_path"

    /**
     * The token for this process. Made once, on first read, and never stored.
     *
     * `lazy` is synchronized by default, so two threads that race to the first read get the
     * same value.
     */
    val sessionToken: String by lazy {
        val bytes = ByteArray(TOKEN_LENGTH / 2)
        SecureRandom().nextBytes(bytes)
        bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * The path for a broadcast id.
     *
     * [streamId] is sanitized exactly as it always was — surrounding slashes trimmed — and
     * nothing more. The `-Low` suffix is kept for every quality profile, not only "low": it
     * tells the media server the path is already transcoded and must be passed through rather
     * than re-encoded, which is true of all three tiers.
     *
     * [token] defaults to the process token. It is a parameter so that a unit test can pin the
     * shape with a known value; production code never passes it.
     */
    fun compose(streamId: String, randomize: Boolean, token: String = sessionToken): String {
        val id = streamId.trim('/')
        return if (randomize) "$id-$token$SUFFIX" else "$id$SUFFIX"
    }

    /**
     * Logs the path this session will publish under, once, at application start.
     *
     * Reads the active server's mirrored keys, the same ones the streamer reads at LIVE. The
     * password is not part of the path and is not logged. A controller with no video server
     * configured logs that instead of an empty path.
     */
    fun logSessionPath(context: Context) {
        val p = context.getSharedPreferences("takpilot2_tak", Context.MODE_PRIVATE)
        val streamId = p.getString("video_streamid", "") ?: ""
        val randomize = p.getBoolean(PREF_RANDOMIZE, false)
        if (streamId.isEmpty()) {
            AppLog.i(TAG, "no broadcast id configured; stream path not composed")
            return
        }
        val path = compose(streamId, randomize)
        AppLog.i(TAG, "stream path for this session: $path" +
            if (randomize) " (random token, new at each application launch)" else " (fixed)")
    }
}
