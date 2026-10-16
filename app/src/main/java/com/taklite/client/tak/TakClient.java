package com.taklite.client.tak;

import com.taklite.util.AppLog;

import java.io.BufferedWriter;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

public class TakClient extends Thread {
    private static final String TAG = "TakClient";
    private static final long RECONNECT_DELAY_MS = 5000;

    // Hard cap on the pending (not-yet-complete) receive buffer. A single CoT <event> is a few KB;
    // 4 MB is orders of magnitude beyond any legitimate one. If this much data arrives with no
    // closing </event>, the peer is malformed or hostile — drop the connection rather than let the
    // buffer grow without bound and OOM this memory-constrained controller.
    private static final int MAX_RECV_BUFFER_BYTES = 4 * 1024 * 1024;

    private final String serverAddress;
    private final int port;
    private final String trustStorePath;
    private final String trustStorePassword;
    private final String clientCertPath;
    private final String clientCertPassword;
    private final TakClientListener listener;

    private volatile boolean mRun;
    /** Volatile: written by the reader thread in connect/closeConnection, read by the sender. */
    private volatile PrintWriter mBufferOut;
    private InputStream mInputStream;
    private SSLSocket sslSocket;
    private Socket socket;

    /**
     * THE SINGLE WRITER. Every outbound CoT goes through this one FIFO thread.
     *
     * It used to be a new Thread per message, which was wrong three ways:
     *  1. No ordering. Markers re-send under a STABLE uid so the server updates in place, so a
     *     re-aim overtaking its own previous send leaves the marker at the stale position until
     *     something re-sends it. The drone PLI and the camera point in one tick describe one
     *     instant and could likewise cross.
     *  2. Worse than ordering: two println calls on one BufferedWriter can INTERLEAVE, splicing
     *     two events into one malformed XML document on the wire.
     *  3. ~3 CoT every 2s in steady flight is ~2,700 thread creations an hour, on a controller.
     *
     * A single-thread executor is also the pattern TakMissionManager already uses for its own
     * serialised I/O, so this is house style rather than a new idea.
     */
    private final ExecutorService sendExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "TakClient-Send");
        t.setDaemon(true);
        return t;
    });

    public interface TakClientListener {
        void onConnected();
        void onDisconnected();
        void onCotReceived(String xml);
    }

    public TakClient(String serverAddress, int port, String trustStorePath, String trustStorePassword,
                     String clientCertPath, String clientCertPassword, TakClientListener listener) {
        super("TakClient-Thread");
        this.serverAddress = serverAddress;
        this.port = port;
        this.trustStorePath = trustStorePath;
        this.trustStorePassword = trustStorePassword;
        this.clientCertPath = clientCertPath;
        this.clientCertPassword = clientCertPassword;
        this.listener = listener;
        setDaemon(true);
    }

    /**
     * Writes one CoT to the server. Fire and forget, on the single sender thread.
     *
     * ⚠ THE FAILURES HERE USED TO BE COMPLETELY SILENT, which is why a marker that never left
     * the socket and a marker the server rejected looked identical from the log.
     * Two things hid them:
     *
     *  1. A null stream returned with no trace at all.
     *  2. mBufferOut is a PrintWriter, and PrintWriter NEVER THROWS ON WRITE. It swallows the
     *     IOException and sets an internal flag, so the catch below cannot see a broken pipe,
     *     a half-open socket or a failed flush — it only ever caught something like an NPE.
     *     checkError() is the ONLY way to see that failure, and it also clears nothing, so it
     *     is safe to call on every message.
     *
     * The signature stays void on purpose. The write happens on the sender thread after this
     * method has already returned, thus any status handed back to the caller would be a lie.
     */
    public void sendMessage(final String message) {
        if (mBufferOut == null) {
            AppLog.w(TAG, "CoT DROPPED — the output stream is not open (message discarded)");
            return;
        }
        try {
            sendExecutor.execute(() -> {
                // Read the field ONCE into a local. It was read three separate times before,
                // while the reader thread could close() and null it in between — so a send
                // could NPE, or write into an already-closed writer (which PrintWriter
                // swallows) after passing a null check that was true a moment earlier.
                final PrintWriter out = mBufferOut;
                if (out == null) {
                    AppLog.w(TAG, "CoT DROPPED — the connection closed before this message "
                            + "reached the socket (" + message.length() + " chars)");
                    return;
                }
                try {
                    out.println(message);
                    out.flush();
                    // The real failure detector for this stream — see the note above.
                    if (out.checkError()) {
                        AppLog.e(TAG, "CoT WRITE FAILED — the socket reported an error and the "
                                + "message did not go out (" + message.length() + " chars)");
                    }
                } catch (Exception e) {
                    AppLog.e(TAG, "Error sending message", e);
                }
            });
        } catch (RejectedExecutionException e) {
            // stopClient() has shut the sender down; nothing more goes out on this client.
            AppLog.w(TAG, "CoT DROPPED — the client is stopped (" + message.length() + " chars)");
        }
    }

    public void stopClient() {
        mRun = false;
        // No new sends. Anything already queued still runs, finds mBufferOut null once
        // closeConnection has been through, and is logged as dropped rather than vanishing.
        sendExecutor.shutdown();
        // The SOCKET first, and no flush from this thread. The
        // send thread holds the writer's lock for the whole of a blocking socket write, so on a
        // half-open link (Wi-Fi up, backhaul gone) a flush here waited behind it for as long as
        // TCP retries, ~15 min — and the callers include the main thread. Closing the socket
        // makes that write throw, which frees the lock.
        closeConnection(false);
    }

    public boolean isConnected() {
        if (sslSocket != null) {
            return sslSocket.isConnected() && !sslSocket.isClosed();
        }
        if (socket != null) {
            return socket.isConnected() && !socket.isClosed();
        }
        return false;
    }

    @Override
    public void run() {
        mRun = true;
        while (mRun) {
            try {
                connect();
                if (listener != null) listener.onConnected();

                byte[] buf = new byte[8192];
                StringBuilder recvBuffer = new StringBuilder();

                // Each read used to be decoded on its own with new String(buf,…,"UTF-8").
                // A UTF-8 character is up to four bytes and a socket read ends wherever the
                // network says, so any character straddling a chunk boundary was decoded as two
                // separate malformed fragments and became U+FFFD twice — the bytes are
                // DESTROYED at that point, so nothing downstream can repair it. It shows up as
                // mojibake in non-ASCII callsigns and remarks (Chinese, already seen in the
                // field). A decoder carried across reads keeps the split bytes pending until
                // the rest of the character arrives. The <event> framing below is unchanged —
                // it was already correct about partial and multiple events per read.
                CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPLACE)
                        .onUnmappableCharacter(CodingErrorAction.REPLACE);
                ByteBuffer pending = ByteBuffer.allocate(buf.length * 2);
                CharBuffer decoded = CharBuffer.allocate(buf.length + 16);

                while (mRun) {
                    int bytesRead;
                    try {
                        bytesRead = mInputStream.read(buf);
                    } catch (SocketTimeoutException e) {
                        continue;
                    } catch (SocketException e) {
                        AppLog.e(TAG, "Socket error: " + e.getMessage());
                        break;
                    }
                    if (bytesRead == -1) {
                        AppLog.w(TAG, "Server closed connection (EOF)");
                        break;
                    }
                    // Decode with the carried-over decoder: any trailing bytes that do not yet
                    // form a whole character stay in `pending` for the next read.
                    pending.put(buf, 0, bytesRead);
                    pending.flip();
                    CoderResult cr;
                    do {
                        decoded.clear();
                        cr = decoder.decode(pending, decoded, false);
                        decoded.flip();
                        recvBuffer.append(decoded);
                    } while (cr.isOverflow());
                    pending.compact();
                    AppLog.d(TAG, "Raw data received (" + bytesRead + " bytes)");

                    int endIdx;
                    while ((endIdx = recvBuffer.indexOf("</event>")) != -1) {
                        int end = endIdx + "</event>".length();
                        String message = recvBuffer.substring(0, end).trim();
                        recvBuffer.delete(0, end);
                        if (!message.isEmpty() && listener != null) {
                            AppLog.d(TAG, "CoT message complete (" + message.length() + " chars)");
                            listener.onCotReceived(message);
                        }
                    }

                    // After draining every complete event, whatever remains is one incomplete
                    // message. If that tail alone exceeds the cap, no </event> is coming — bail
                    // to the reconnect path instead of buffering unboundedly.
                    if (recvBuffer.length() > MAX_RECV_BUFFER_BYTES) {
                        AppLog.e(TAG, "Receive buffer exceeded " + MAX_RECV_BUFFER_BYTES
                                + " bytes with no complete event — dropping connection");
                        break;
                    }
                }
            } catch (Exception e) {
                AppLog.e(TAG, "Connection error: " + e.getMessage());
            }

            closeConnection();
            if (listener != null) listener.onDisconnected();

            if (mRun) {
                AppLog.d(TAG, "Reconnecting in " + RECONNECT_DELAY_MS + "ms...");
                try {
                    Thread.sleep(RECONNECT_DELAY_MS);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    private void connect() throws Exception {
        InetAddress addr = InetAddress.getByName(serverAddress);
        boolean useTls = trustStorePath != null && !trustStorePath.isEmpty()
                && clientCertPath != null && !clientCertPath.isEmpty();

        if (!useTls) {
            AppLog.e(TAG, "Refusing to connect: no client certificate / trust store present. Plaintext connections are not permitted.");
            throw new SocketException("Not enrolled: TLS certificates required");
        }

        AppLog.d(TAG, "Connecting via TLS to " + serverAddress + ":" + port);
        KeyStore trustStore = KeyStore.getInstance("PKCS12");
        FileInputStream trustIs = new FileInputStream(trustStorePath);
        trustStore.load(trustIs, trustStorePassword.toCharArray());
        trustIs.close();

        KeyStore clientStore = KeyStore.getInstance("PKCS12");
        FileInputStream clientIs = new FileInputStream(clientCertPath);
        clientStore.load(clientIs, clientCertPassword.toCharArray());
        clientIs.close();

        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(clientStore, clientCertPassword.toCharArray());

        SSLContext sslContext = SSLContext.getInstance("TLSv1.3");
        sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);

        SSLSocketFactory factory = sslContext.getSocketFactory();
        sslSocket = (SSLSocket) factory.createSocket(addr, port);
        sslSocket.setSoTimeout(1000);

        // UTF-8 pinned explicitly: CotBuilder writes encoding="UTF-8" into every event, so
        // encoding the bytes with the platform default was declaring one thing and doing
        // another. It happens to be UTF-8 on Android, but nothing guaranteed it, and this is
        // the send-side twin of the split-character receive bug in run().
        mBufferOut = new PrintWriter(new BufferedWriter(
                new OutputStreamWriter(sslSocket.getOutputStream(), StandardCharsets.UTF_8)), true);
        mInputStream = sslSocket.getInputStream();
        AppLog.d(TAG, "Connected to " + serverAddress + ":" + port);
    }

    private void closeConnection() { closeConnection(true); }

    /** @param flushFirst true only on this client's own thread; see {@link #stopClient()}. */
    private void closeConnection(boolean flushFirst) {
        if (flushFirst) {
            try { if (mBufferOut != null) mBufferOut.flush(); } catch (Exception e) {}
        }
        try { if (sslSocket != null) sslSocket.close(); } catch (IOException e) {}
        try { if (socket != null) socket.close(); } catch (IOException e) {}
        try { if (mBufferOut != null) mBufferOut.close(); } catch (Exception e) {}
        try { if (mInputStream != null) mInputStream.close(); } catch (IOException e) {}
        mBufferOut = null;
        mInputStream = null;
        sslSocket = null;
        socket = null;
    }
}
