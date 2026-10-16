package com.autel.sdksample.tak

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Is the configured media server there, before the pilot presses LIVE?
 *
 * ⚠ **IT ANSWERS "REACHABLE", NOT "WILL ACCEPT MY STREAM", AND THE DIFFERENCE IS NOT A DETAIL.**
 * Both would be useful. Only the first is knowable without publishing: whether the server
 * accepts THIS publisher depends on credentials, on whether the path is allowed to be published
 * to, and on whether it likes the codec — and every one of those is answered by the server
 * during a real publish handshake and not before. A probe that claimed otherwise would be the
 * same lie as a camera reporting OK for a lens it never changed.
 *
 * So: green here means "the server is up and speaking RTSP". **The LIVE pill remains the only
 * authority on whether the stream is actually being taken** — that is what its red and its amber
 * SYNC already say, from the real connection rather than from a guess.
 *
 * ## Why it probes RTSP even when the pilot has chosen SRT
 *
 * SRT is UDP. There is no connect to attempt and no reply to wait for; a probe would have to
 * perform a real SRT handshake, which needs the streaming library and is not a cheap check.
 *
 * But the media server serves RTSP regardless of how anything is published or read. Probing the
 * RTSP port therefore tests THE SERVER, which is what the pilot wants to know, and it works
 * identically for every combination of legs.
 *
 * ⚠ This used to justify itself with "no TAK client plays SRT, so the CoT address is always
 * RTSP". That is no longer true — the read leg can be SRT now (see [VideoTransport]) — but
 * the probe is unaffected: it was never testing the advertised address, only whether the
 * server is up and speaking. It does not test the SRT ingest port, and this is written
 * here so nobody later reads the green light as meaning more than it does.
 *
 * ## Why OPTIONS and not just a TCP connect
 *
 * A bare connect proves only that something accepted a socket — a load balancer, a captive
 * portal, a wrong host that happens to listen. `OPTIONS` makes it prove it speaks RTSP by
 * answering with an RTSP status line. One round trip, no session created, nothing to tear down.
 */
object MediaServerProbe {

    enum class Result {
        /** The server answered RTSP. It is up. It has NOT agreed to take a stream. */
        REACHABLE,
        /** DNS failed, the connection was refused, or the reply was not RTSP. */
        UNREACHABLE,
        /** Nothing configured to probe. */
        NOT_CONFIGURED,
    }

    /** Short enough that a pilot is not kept waiting at the card, long enough for a cellular
     *  round trip. The check runs off the UI thread and repeats, so a slow answer costs a tick,
     *  never a freeze. */
    const val TIMEOUT_MS = 2000

    /**
     * Blocking. **Call from a background thread.**
     *
     * @param host the media server, as configured.
     * @param rtspPort the server's RTSP port — known whichever transport is selected.
     */
    fun probe(host: String, rtspPort: Int): Result {
        if (host.isBlank() || rtspPort <= 0) return Result.NOT_CONFIGURED
        return runCatching {
            Socket().use { sock ->
                sock.connect(InetSocketAddress(host, rtspPort), TIMEOUT_MS)
                sock.soTimeout = TIMEOUT_MS
                // ⚠ NO CREDENTIALS AND NO PATH. This asks the SERVER what it can do, not for
                // permission to do anything: an OPTIONS to the bare root creates no session,
                // needs no authentication, and leaves nothing behind to tear down. Sending the
                // publish path with credentials here would be a login attempt on every refresh
                // of the home screen.
                sock.getOutputStream().apply {
                    write("OPTIONS rtsp://$host:$rtspPort RTSP/1.0\r\nCSeq: 1\r\n\r\n".toByteArray())
                    flush()
                }
                val line = BufferedReader(InputStreamReader(sock.getInputStream()))
                    .readLine().orEmpty()
                // "RTSP/1.0 200 OK" — any RTSP status line proves an RTSP server answered.
                // A 4xx still proves that, so the test is the PROTOCOL and not the code: a
                // server that refuses OPTIONS is still a server that is up.
                if (line.startsWith("RTSP/")) Result.REACHABLE else Result.UNREACHABLE
            }
        }.getOrElse { Result.UNREACHABLE }
    }
}
