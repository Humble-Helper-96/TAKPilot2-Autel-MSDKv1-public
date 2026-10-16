package com.taklite.client.tak;

import android.location.Location;
import android.os.Handler;
import android.os.Looper;
import com.taklite.util.AppLog;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class TakManager implements TakClient.TakClientListener {
    private static final String TAG = "TakManager";

    /** What the outbound log calls each connection. The account names the pilot sees in
     *  Pre-Flight, so a log line and the screen agree on which socket is meant. */
    private static final String LINK_STANDARD = "standard";
    private static final String LINK_ELEVATED = "elevated";
    private static final long STALE_CHECK_INTERVAL_MS = 30000;

    private static TakManager instance;

    private TakClient client;
    private String uid;
    private String callsign;
    // Host + cert material retained from connect(), reused by the HTTPS Mission API client.
    private String serverAddress;
    private String trustStorePath;
    private String trustStorePassword;
    private String clientCertPath;
    private String clientCertPassword;
    private String team;
    private String role;
    private boolean connected = false;
    private double lastLat = 0;
    private double lastLon = 0;
    private boolean initialPliSent = false;
    private String activeAlertId;

    // ---- Video-channel split (cert B) ----
    // A second, independent TAK connection, enrolled under its own TAK Server username so it
    // lands in a different channel/group than `client` above. Everything this class sends goes
    // out on BOTH connections identically, except the __video element — see videoFor().
    //
    // WHAT B DOES WITH WHAT IT RECEIVES IS THE CALLER'S CHOICE — see videoChannelAcceptsInbound
    // and the acceptInbound parameter on connectVideoChannel. It defaults to DISCARD, which is
    // the behaviour this connection shipped with.
    // Written on the enroll/auto-connect thread, read on the bridge's callback thread — volatile
    // like the flags beside it.
    private volatile TakClient videoClient;
    private volatile boolean videoConnected = false;
    /**
     * Whether the Elevated connection's INBOUND traffic reaches this controller's contact map
     * and marker layer, or is thrown away.
     *
     * FALSE is the original behaviour and stays the default: B existed only to advertise video
     * to a smaller audience, never to feed the contact map, and a caller that has not thought
     * about the question gets the connection it already had.
     *
     * ⚠ TRUE MEANS EVERY INBOUND MESSAGE IS HANDLED TWICE when the two accounts can both
     * receive from the same people — the server has no idea these two sockets are one
     * controller. That is not the disaster it sounds like: everything downstream is keyed by
     * UID (the contact map is a ConcurrentHashMap on uid, the marker layer replaces by uid), so
     * the second copy lands on the same entry rather than making a second one. The cost is
     * parsing work, not duplicate icons. It becomes a real problem only if something downstream
     * ever starts APPENDING per message instead of replacing per uid.
     *
     * Set with the connection, read on the socket thread — volatile like the flags beside it.
     */
    private volatile boolean videoChannelAcceptsInbound = false;
    /** True once connectVideoChannel() has been CALLED this session — on the attempt, not on a
     *  handshake. That is the design rule: with a video channel configured, cert
     *  A never carries video, whether B is up or not. A cert B the server refuses therefore
     *  advertises video to nobody, and the flight screen's amber "Video link down" notice is
     *  the signal. Stays true across disconnectVideoChannel() (a drop); only clearVideoChannel()
     *  — a removal on purpose — sets it false again. */
    private volatile boolean splitConfigured = false;

    // ---- Emergency Broadcast override ----
    // A pilot-operated, time-limited override that makes EVERY connection carry video, bypassing
    // the split above for a bounded window. See TakManager.videoFor() for the one place this
    // takes effect. It exists because withholding video from the wider audience could cost
    // someone a life-saving piece of information.
    private static final long EMERGENCY_BROADCAST_MS = 15 * 60 * 1000L; // 15 minutes
    private volatile boolean emergencyBroadcastActive = false;
    private volatile long emergencyExpiresAtEpochMs = 0L;
    private volatile Runnable emergencyExpireRunnable;

    // Client identity for CoT's <takv> block — what a TAK server's "Connected Users" panel shows
    // as this client's type/device/version. Defaults are deliberately generic (never a specific
    // app name): this class is a shared core, and hardcoding an identity here would mislabel
    // whichever app never calls setClientIdentity(). See that method's doc.
    private String takvPlatform = "TAK Lite";
    private String takvDevice = "Unknown Device";
    private String takvOs = "Android";
    private String takvVersion = "1.0";

    /**
     * Sets the client identity every outbound PLI reports via {@code <takv>}. Call once at app
     * startup — real values here (not this class's placeholder defaults) are what makes a
     * pilot's own entry in a TAK server's connected-users list say the ACTUAL app, not a
     * leftover generic name from the shared core.
     *
     * Deliberately not hardcoded inside this shared class: {@link TakManager}/{@link CotBuilder}
     * are written to be usable by more than one app, and baking one app's name in here would
     * mislabel any other.
     */
    public void setClientIdentity(String platform, String device, String os, String version) {
        if (platform != null) this.takvPlatform = platform;
        if (device != null) this.takvDevice = device;
        if (os != null) this.takvOs = os;
        if (version != null) this.takvVersion = version;
    }

    private final ConcurrentHashMap<String, TakUser> takUsers = new ConcurrentHashMap<>();
    private final List<TakUserListener> listeners = new ArrayList<>();
    private final List<TakAlertListener> alertListeners = new ArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final Runnable staleCheckRunnable = new Runnable() {
        @Override
        public void run() {
            removeStaleUsers();
            mainHandler.postDelayed(this, STALE_CHECK_INTERVAL_MS);
        }
    };

    public interface TakUserListener {
        void onTakUserUpdated(TakUser user);
        /**
         * The contact aged out. It may still be held somewhere durable (a saved marker), so a
         * listener may legitimately keep showing it — see TakMapMarkers.
         */
        void onTakUserRemoved(String uid);
        /**
         * The network EXPLICITLY deleted this item (a `t-x-d-d`), as opposed to it merely ageing
         * out. Forget it for good, including any persisted copy.
         *
         * The distinction is the whole point of the persistent-marker work: a timeout must not
         * remove a shared marker, but a real delete must — otherwise a marker the team has
         * retracted lives on this aircraft for ever.
         */
        void onTakUserDeleted(String uid);
        void onTakConnectionChanged(boolean connected);
    }

    public interface TakAlertListener {
        void onAlertReceived(String senderUid, String senderCallsign, String alertType, double lat, double lon);
        void onAlertCancelled(String senderUid, String senderCallsign);
    }

    /**
     * Told when the Emergency Broadcast override starts, renews or ends.
     *
     * {@code reason} is one of {@code "started"}, {@code "renewed"}, {@code "cancelled"},
     * {@code "expired"} or {@code "reset-on-reconnect"}. {@code active} is true for the first
     * two. This class deliberately does not write the flight record itself (it has no
     * Android dependency and is a shared core — see the "no SDK import" rule this package
     * follows); a listener in the application layer is the one that persists it.
     * No "who": that is recorded outside the application (design decision).
     */
    public interface EmergencyBroadcastListener {
        void onEmergencyBroadcastChanged(boolean active, long expiresAtEpochMs, String reason);
    }

    private final List<EmergencyBroadcastListener> emergencyListeners = new ArrayList<>();

    public void addEmergencyBroadcastListener(EmergencyBroadcastListener l) {
        synchronized (emergencyListeners) { if (!emergencyListeners.contains(l)) emergencyListeners.add(l); }
    }

    public void removeEmergencyBroadcastListener(EmergencyBroadcastListener l) {
        synchronized (emergencyListeners) { emergencyListeners.remove(l); }
    }

    private void notifyEmergencyBroadcastChanged(boolean active, String reason) {
        long expiresAt = active ? emergencyExpiresAtEpochMs : 0L;
        mainHandler.post(() -> {
            synchronized (emergencyListeners) {
                for (EmergencyBroadcastListener l : emergencyListeners) {
                    try { l.onEmergencyBroadcastChanged(active, expiresAt, reason); }
                    catch (Exception e) { AppLog.w(TAG, "emergency broadcast listener failed: " + e.getMessage()); }
                }
            }
        });
    }

    private TakManager() {}

    public static synchronized TakManager getInstance() {
        if (instance == null) {
            instance = new TakManager();
        }
        return instance;
    }

    public void addListener(TakUserListener listener) {
        synchronized (listeners) {
            if (!listeners.contains(listener)) listeners.add(listener);
        }
    }

    public void removeListener(TakUserListener listener) {
        synchronized (listeners) {
            listeners.remove(listener);
        }
    }

    public void addAlertListener(TakAlertListener listener) {
        synchronized (alertListeners) {
            if (!alertListeners.contains(listener)) alertListeners.add(listener);
        }
    }

    public void removeAlertListener(TakAlertListener listener) {
        synchronized (alertListeners) {
            alertListeners.remove(listener);
        }
    }

    public String getUid() { return uid; }
    public String getCallsign() { return callsign; }
    public String getServerAddress() { return serverAddress; }
    public String getTrustStorePath() { return trustStorePath; }
    public String getTrustStorePassword() { return trustStorePassword; }
    public String getClientCertPath() { return clientCertPath; }
    public String getClientCertPassword() { return clientCertPassword; }

    /**
     * Puts a marker restored from durable storage back into the live contact map, so views that
     * read {@link #getTakUsers()} can see it.
     *
     * Needed because the AR overlay iterates that collection directly, while the map keeps its own
     * saved-marker store — so a marker restored at map-open was drawn on the map and invisible in
     * AR. It also matters for deletes: a `t-x-d-d` is matched against this map.
     *
     * Does NOT overwrite an existing entry — a live CoT is always better than a saved copy — and
     * does not notify listeners, because the caller is the thing that restored it.
     */
    public void restorePersistentUser(TakUser user) {
        if (user == null || user.getUid() == null) return;
        takUsers.putIfAbsent(user.getUid(), user);
    }

    /**
     * Drops a contact from the live map WITHOUT telling listeners.
     *
     * For a caller that is already removing the thing itself — the marker list's Clear All, which
     * strips the overlay and the saved copy directly. Notifying would send it back through
     * onTakUserRemoved and re-add the marker from the store it is in the middle of clearing.
     */
    public void forgetUser(String uid) {
        if (uid != null) takUsers.remove(uid);
    }

    public TakUser findUserByUid(String uid) {
        return takUsers.get(uid);
    }

    public TakUser findUserByCallsign(String callsign) {
        for (TakUser user : takUsers.values()) {
            if (callsign.equals(user.getCallsign())) return user;
        }
        return null;
    }

    public void connect(String uid, String callsign, String team, String role,
                        String address, int port, String trustStorePath, String trustStorePassword,
                        String clientCertPath, String clientCertPassword) {
        disconnect();
        this.uid = uid;
        this.callsign = callsign;
        this.team = team != null ? team : "Cyan";
        this.role = role != null ? role : "Team Member";
        // Retain host + certs so the HTTPS Mission API client (Data Sync) can reuse them.
        this.serverAddress = address;
        this.trustStorePath = trustStorePath;
        this.trustStorePassword = trustStorePassword;
        this.clientCertPath = clientCertPath;
        this.clientCertPassword = clientCertPassword;
        client = new TakClient(address, port, trustStorePath, trustStorePassword, clientCertPath, clientCertPassword, this);
        client.start();
        mainHandler.postDelayed(staleCheckRunnable, STALE_CHECK_INTERVAL_MS);
    }

    public void disconnect() {
        // A reconnect/logout must never leave the override silently running — see
        // EmergencyBroadcastListener's doc. This also covers connect(), which calls disconnect()
        // first.
        endEmergencyBroadcast("reset-on-reconnect");
        mainHandler.removeCallbacks(staleCheckRunnable);
        if (client != null) {
            try { client.stopClient(); } catch (Throwable t) { AppLog.w(TAG, "stopClient: " + t.getMessage()); }
            client = null;
        }
        takUsers.clear();
        connected = false;
        initialPliSent = false;
    }

    /**
     * Connects the SECOND certificate — the video channel. Independent lifecycle from
     * {@link #connect}/{@link #disconnect}: this never touches cert A's socket, and a caller may
     * connect/disconnect this one repeatedly while A stays up throughout.
     *
     * Uses the SAME {@code uid} as cert A (both represent this one aircraft/controller) so a
     * server-side admin can tell at a glance that A and B are the same airframe under two
     * certificates. What this connection does with INBOUND traffic is the caller's choice: the
     * six-argument form throws it away, which is B's original behaviour and still the
     * default; the seven-argument form below takes a flag.
     */
    public void connectVideoChannel(String address, int port, String trustStorePath,
                                    String trustStorePassword, String clientCertPath,
                                    String clientCertPassword) {
        connectVideoChannel(address, port, trustStorePath, trustStorePassword, clientCertPath,
                clientCertPassword, false);
    }

    /**
     * As above, and says what to do with what cert B RECEIVES.
     *
     * @param acceptInbound true to feed B's inbound into this controller's contact map and
     *                      marker layer exactly as cert A's is fed; false to throw it away,
     *                      which is what this connection did before the parameter existed.
     *                      See {@link #videoChannelAcceptsInbound} for the cost of true.
     */
    public void connectVideoChannel(String address, int port, String trustStorePath,
                                    String trustStorePassword, String clientCertPath,
                                    String clientCertPassword, boolean acceptInbound) {
        // Deliberately NOT endEmergencyBroadcast() here: a re-dial of the
        // Elevated socket is not an app-level reconnect, and it must not end a running
        // override. disconnect() — which connect() calls first — is where the reset lives.
        disconnectVideoChannel();
        videoChannelAcceptsInbound = acceptInbound;
        // Each listener answers only for ITS OWN client. A client stopped while still inside
        // connect() reports later; without this check its onDisconnected would write
        // videoConnected = false over the healthy replacement, which would then carry nothing.
        final TakClient[] self = new TakClient[1];
        videoClient = new TakClient(address, port, trustStorePath, trustStorePassword,
                clientCertPath, clientCertPassword, new TakClient.TakClientListener() {
                    // ⚠ onConnected/onDisconnected are B's OWN, deliberately not the manager's.
                    // TakManager.onConnected() sets `connected`, which is cert A's flag and
                    // drives the whole application's "TAK is up" state; routing B's socket
                    // events there would make the Standard connection look alive because the
                    // Elevated one is.
                    @Override public void onConnected() {
                        if (videoClient != self[0]) return;   // a replaced client: ignore
                        videoConnected = true;
                        AppLog.d(TAG, "Video channel connected");
                    }
                    @Override public void onDisconnected() {
                        if (videoClient != self[0]) return;   // a replaced client: ignore
                        videoConnected = false;
                        AppLog.d(TAG, "Video channel disconnected");
                    }
                    @Override public void onCotReceived(String xml) {
                        // Inbound is the caller's choice. Discarding is the default and B's
                        // original behaviour: B existed to advertise video, not to feed this
                        // controller's contact map. Accepting
                        // routes it through the SAME handler cert A's traffic uses, so the two
                        // connections cannot drift in how they read a message.
                        if (videoChannelAcceptsInbound) {
                            TakManager.this.onCotReceived(xml);
                        }
                    }
                });
        self[0] = videoClient;
        videoClient.start();
        splitConfigured = true;
    }

    /** Tears down the video channel only — a DROP. {@code splitConfigured} stays true — fail
     *  closed: cert A must never regain video just because B dropped. See videoFor(). For a
     *  removal on purpose use {@link #clearVideoChannel()}. */
    public void disconnectVideoChannel() {
        if (videoClient != null) {
            try { videoClient.stopClient(); } catch (Throwable t) { AppLog.w(TAG, "stopClient (video): " + t.getMessage()); }
            videoClient = null;
        }
        videoConnected = false;
    }

    /**
     * Removes the video channel ON PURPOSE — the pilot switched it off, or logged out — and
     * returns the application to single-connection behaviour: {@code splitConfigured} goes
     * false, so cert A carries video again.
     *
     * ⚠ {@link #disconnectVideoChannel()} alone leaves {@code splitConfigured} true for the
     * life of the process, thus a switch-off or a logout would silently take video away from
     * EVERY viewer until the application restarted.
     * A drop and a removal are two different things: use that method for a drop, this for a
     * removal.
     */
    public void clearVideoChannel() {
        disconnectVideoChannel();
        splitConfigured = false;
        videoChannelAcceptsInbound = false;
        AppLog.i(TAG, "Video channel removed — single-connection behaviour restored");
    }

    /** True once connectVideoChannel() has ever succeeded this session, whether or not it is
     *  connected RIGHT NOW. This is what videoFor() reads to decide whether A should ever be
     *  stripped of video — see its doc for why that must not flip back off on a mere drop. */
    public boolean isVideoChannelConfigured() {
        return splitConfigured;
    }

    /** True only while the video channel's socket is actually up right now. */
    public boolean isVideoChannelConnected() {
        return videoConnected;
    }

    /**
     * Decides whether ONE outbound message, bound for connection {@code path} ("A" or "B"),
     * should carry {@code url} as its {@code __video} value.
     *
     * Pure and socket-free on purpose — this is the one place the whole split/override policy
     * lives, and it is exercised directly by VideoSplitPolicyTest with no TakClient at all.
     *
     * @param path "A" (the always-present, no-video-by-default connection) or "B" (the video
     *             connection) — a plain string rather than a boolean so a future third path
     *             cannot silently invert the sense of an existing boolean parameter.
     * @param splitConfigured whether a video channel has ever been configured this session
     *                        ({@link #isVideoChannelConfigured()}).
     * @param emergencyActive whether the Emergency Broadcast override is active RIGHT NOW.
     * @param url the video URL this message would carry if nothing restricted it.
     * @return {@code url} if this path should carry it, else {@code null}.
     */
    static String videoFor(String path, boolean splitConfigured, boolean emergencyActive, String url) {
        // Checked FIRST, and returns unconditionally on every path: the override exists
        // specifically to bypass the split below, so nothing after this line may re-narrow it.
        if (emergencyActive) return url;
        if (!splitConfigured) return url; // today's single-connection behaviour, unchanged
        return "B".equals(path) ? url : null; // split active: A never carries video
    }

    /**
     * Starts the Emergency Broadcast override, or — if one is already running — RENEWS it: the
     * clock goes back to the full {@link #EMERGENCY_BROADCAST_MS}. See
     * the class-level note above that constant for what the override is for. Safe to call
     * whether or not the video channel is configured, though the UI should only expose the
     * control when {@link #isVideoChannelConfigured()} — with no split, there is nothing to
     * override.
     */
    public void startOrRenewEmergencyBroadcast() {
        boolean renew = emergencyBroadcastActive;
        emergencyBroadcastActive = true;
        emergencyExpiresAtEpochMs = System.currentTimeMillis() + EMERGENCY_BROADCAST_MS;
        if (emergencyExpireRunnable != null) mainHandler.removeCallbacks(emergencyExpireRunnable);
        emergencyExpireRunnable = () -> endEmergencyBroadcast("expired");
        mainHandler.postDelayed(emergencyExpireRunnable, EMERGENCY_BROADCAST_MS);
        AppLog.i(TAG, "Emergency Broadcast " + (renew ? "RENEWED" : "STARTED")
                + " — video on every connection until " + emergencyExpiresAtEpochMs);
        notifyEmergencyBroadcastChanged(true, renew ? "renewed" : "started");
    }

    /** Stops a running override now. A no-op when none is running. */
    public void cancelEmergencyBroadcast() {
        endEmergencyBroadcast("cancelled");
    }

    public boolean isEmergencyBroadcastActive() {
        return emergencyActiveNow();
    }

    /**
     * The override's state by the WALL CLOCK, which is the bound the notice shows. The Handler
     * timer that normally ends it is uptime-based and does not run in deep sleep, so a send that finds the expiry in the past ends the override itself, with
     * the same "expired" record the timer would have written.
     */
    private boolean emergencyActiveNow() {
        if (!emergencyBroadcastActive) return false;
        if (System.currentTimeMillis() >= emergencyExpiresAtEpochMs) {
            endEmergencyBroadcast("expired");
            return false;
        }
        return true;
    }

    /** 0 when inactive. */
    public long emergencyBroadcastExpiresAtEpochMs() {
        return emergencyBroadcastActive ? emergencyExpiresAtEpochMs : 0L;
    }

    /**
     * The one place that clears Emergency Broadcast state, whatever ended it — a manual cancel,
     * the timer firing, or a reconnect that must not inherit it. A no-op when already inactive,
     * so {@link #connect}/{@link #disconnect}/{@link #connectVideoChannel} can call this
     * unconditionally without checking state first.
     */
    private void endEmergencyBroadcast(String reason) {
        if (!emergencyBroadcastActive) return;
        emergencyBroadcastActive = false;
        emergencyExpiresAtEpochMs = 0L;
        if (emergencyExpireRunnable != null) {
            mainHandler.removeCallbacks(emergencyExpireRunnable);
            emergencyExpireRunnable = null;
        }
        AppLog.i(TAG, "Emergency Broadcast ENDED (" + reason + ")");
        notifyEmergencyBroadcastChanged(false, reason);
    }

    /**
     * CHANNEL SELECTION IS REMOVED (design decision), and this note is why.
     *
     * This class used to hold the channels a pilot picked on TAK Setup and inject
     * {@code <marti><dest group="…" send="true"/></marti>} into every CoT that went through what
     * was then called {@code sendCot} (now {@link #sendOn}/{@link #sendCotToBoth}).
     * IT SILENTLY DESTROYED MARKERS. With channels selected, the server would
     * not route them and simply dropped them; with none selected they arrived at once. Proved on
     * a controller by watching one marker with the block and one without.
     *
     * It would have done the same to an alert — {@link #sendAlert} takes the same path — but
     * NOTHING IN THIS APPLICATION CALLS sendAlert, so no alert was ever lost. That method and
     * its listener are unreachable code carried by this shared core. If an alert control is ever added, this path is what it uses.
     *
     * It was also never applied evenly. The drone PLI and the camera point called
     * {@link TakClient#sendMessage} directly, so they ignored the selection entirely — a pilot
     * who picked channels to LIMIT who saw this aircraft still broadcast its position to
     * everyone. The feature failed in both directions at once. ⚠ **FIXED with the video
     * split**: both methods now build their CoT
     * through {@link #sendCotToBoth}/{@link #videoFor} like everything else in this class, so a
     * video restriction on one connection can no longer be silently ignored by these two.
     *
     * Routing is now left to the certificate's group membership, which is what the server does
     * with no marti block, and what every working message in this application already relied on.
     *
     * DO NOT RE-ADD {@code <dest group>} WITHOUT TESTING A MARKER AND AN ALERT END TO END on a
     * real server. The mechanism a TAK Server actually accepts for client-chosen channel routing
     * is an open question — that is the work this removal defers, not a detail to guess at.
     */

    /**
     * Sends one CoT on ONE specific connection — the guarded, logged, redacted single-connection
     * send every outbound message goes through, generalized from what used to be {@code sendCot}
     * so it can run against either cert A's {@code client} or cert B's {@code videoClient}.
     */
    private boolean sendOn(TakClient target, boolean targetConnected, String xml, String link,
                           String alreadyLogged) {
        if (target == null || !targetConnected) {
            AppLog.w(TAG, "CoT NOT SENT [" + link + "] — client=" + (target == null ? "null" : "present")
                    + " connected=" + targetConnected);
            return false;
        }
        String wire = xml;
        // THE EXACT BYTES handed to the socket. Markers were once reported as not arriving
        // while the PLI did, and there was no way to tell a message that never left the
        // socket from one the server rejected — the application logged nothing it sent. This log
        // is what found the <dest group> block that was destroying them.
        //
        // VERBOSE, not debug. This runs several times a second; the user turns Detailed on
        // in Debug Log to diagnose. AppLog.v only reaches the file when that is set.
        //
        // ⚠ THE SECOND CONNECTION DOES NOT REPEAT THE WIRE. The video split makes this method
        // run TWICE per outbound message, and when both copies logged the whole event (measured
        // on a controller) `CoT OUT` was 30.7 % of a log file that filled 1 MB every
        // 132 s (7.95 KB/s), and AppLog flushes synchronously on the calling thread every 8 KB
        // — about once a second, each flush a FileWriter open/append/close plus a MediaStore
        // write under a global lock. Halving the largest producer is the cheapest fix available.
        // The two wires differ ONLY by the __video block (that is [videoFor]'s whole job), so
        // the second line says which block it carried and nothing else. If the two ever differ
        // for some OTHER reason, [withoutVideoBlock] stops matching and the full wire is logged
        // — the shortening must never be able to hide a real difference.
        boolean full = alreadyLogged == null || !sameButForVideoBlock(alreadyLogged, wire);
        if (full) {
            AppLog.v(TAG, "CoT OUT [" + link + "]: " + redactCredentials(wire));
        } else {
            AppLog.v(TAG, "CoT OUT [" + link + "]: as above, "
                    + (wire.contains("<__video") ? "plus" : "without") + " the __video block");
        }
        target.sendMessage(wire);
        return full;
    }

    /** True when two outbound wires are the same message and differ at most by whether they
     *  carry the {@code __video} block. See the logging note in {@link #sendOn}. */
    static boolean sameButForVideoBlock(String a, String b) {
        if (a == null || b == null) return false;
        String sa = withoutVideoBlock(a);
        String sb = withoutVideoBlock(b);
        return sa != null && sa.equals(sb);
    }

    /**
     * The wire with its {@code __video} element removed, or the wire unchanged when it has
     * none.
     *
     * Prefers the closing tag over the first {@code "/>"}: the nested {@code <ConnectionEntry/>}
     * that makes a feed playable is itself self-closing, so cutting at {@code "/>"} would slice
     * the element in half and leave a fragment that compares unequal to everything. The bare
     * {@code <__video sensor url/>} shape has no closing tag, which is the only case the
     * {@code "/>"} branch is for.
     */
    static String withoutVideoBlock(String xml) {
        if (xml == null) return null;
        int start = xml.indexOf("<__video");
        if (start < 0) return xml;
        int end;
        int close = xml.indexOf("</__video>", start);
        if (close >= 0) {
            end = close + "</__video>".length();
        } else {
            int selfClose = xml.indexOf("/>", start);
            if (selfClose < 0) return xml;
            end = selfClose + 2;
        }
        return xml.substring(0, start) + xml.substring(end);
    }

    /**
     * Sends one CoT on EVERY configured connection: always on cert A, and on cert B too iff a
     * video channel is configured AND currently connected. {@code xmlForA}/{@code xmlForB} are
     * built by the caller (typically identical strings, or differing only in whether they carry
     * {@code __video} — see {@link #videoFor}) so this method stays a pure dispatcher with no
     * video-policy knowledge of its own.
     *
     * Every outbound CoT in this class goes through here now — see the class's safety note on
     * why {@code sendDronePLI}/{@code sendCameraPoint} calling {@code client.sendMessage}
     * directly (bypassing the logged/redacted path, and bypassing this fan-out) was the bug that
     * let a pilot who restricted an aircraft's audience still broadcast its position to everyone.
     */
    private void sendCotToBoth(String xmlForA, String xmlForB) {
        boolean loggedA = sendOn(client, connected, xmlForA, LINK_STANDARD, null);
        // Read the field ONCE, and ask THAT socket — not the manager's flag, which could belong
        // to a replacement client dialled between this read and the send.
        TakClient b = videoClient;
        // Only offer A's wire as the thing to shorten against when A actually LOGGED it. With
        // the Standard connection down, "as above" would point at a line that is not there.
        if (b != null) sendOn(b, videoConnected && b.isConnected(), xmlForB, LINK_ELEVATED,
                loggedA ? xmlForA : null);
    }

    /**
     * Masks {@code user:pass@} in any url inside a string bound for the log.
     *
     * ⚠ EVERY OUTBOUND LOG LINE GOES THROUGH THIS. The pilot PLI carries the video url, and that
     * url carries the media-server password — which is why {@link #sendPilotPLI} deliberately
     * logs no XML at all. This application does not write a credential to the log or to
     * logcat, and a blanket outbound log would undo that silently. Do not log a CoT without it.
     */
    static String redactCredentials(String s) {
        if (s == null) return null;
        return s.replaceAll("://[^:/@\\s\"]+:[^@\\s\"]+@", "://<user>:<pass>@")
                // An SRT advertisement carries its login in the stream id and has no '@':
                // streamid=read:<path>:<user>:<pass>.
                .replaceAll("(streamid=(?:read|publish):[^:&\\s\"<]*):[^:&\\s\"<]*:[^&\\s\"<]*",
                        "$1:<user>:<pass>")
                // and its passphrase, raw or XML-escaped (&amp;passphrase=).
                .replaceAll("(passphrase=)[^&\\s\"<]+", "$1<masked>");
    }

    /**
     * The {@code <takv device="...">} value: hardware model plus the CURRENT callsign, so a
     * teammate can tell two otherwise-identical devices apart in the connected-users list
     * without opening each track. Built fresh at send time (not cached alongside
     * {@link #setClientIdentity}) specifically so it can never go stale if the pilot edits their
     * callsign mid-session — {@link #setClientIdentity} is a one-time startup call, callsign is
     * not.
     */
    private String deviceWithCallsign(String cs) {
        if (cs == null || cs.isEmpty()) return takvDevice;
        return takvDevice + " (" + cs + ")";
    }

    /**
     * The PILOT's marker: the operator on the ground, at the controller's own position.
     *
     * ⚠ **The XML is NOT logged here, unlike the debug line below it used to do.** This message
     * now carries the video url, and that url contains the media-server password. Logging it
     * would write a credential to the log file and to logcat, which this application never
     * does.
     *
     * <p>No {@code team} parameter on purpose: the pilot marker is always {@link #PILOT_TEAM}.
     * The signature used to take one and silently ignore it, which promised callers a choice
     * the method never offered.
     *
     * <p><b>{@code location} may be null: the controller has no fix.</b> The marker still goes
     * out, in the "position not known" form of {@link CotBuilder#buildPLINoFix}. Sending nothing
     * without a fix would leave the pilot out of the contact list of the other clients, and
     * nobody could send them a marker: a controller indoors or with a cold receiver would not
     * be reachable. That is a worse condition
     * than an unknown position.
     *
     * <p>⚠ Know the cost. A published marker REFRESHES: each send puts the stale time forward.
     * Silence let the marker age off; this does not. A "not known" marker stays in every
     * client's list, and at 0,0 on the map of a client that draws it, for as long as the
     * controller has no fix. The real position replaces it at the first fix.
     *
     * <p>This lives HERE and not in the caller so that every app built on the shared core gets
     * the same behaviour.
     */
    public void sendPilotPLI(Location location, String callsign, String role,
                             int battery, String videoUrl) {
        if (client == null || !connected) return;
        boolean emergency = emergencyActiveNow();
        String urlA = videoFor("A", splitConfigured, emergency, videoUrl);
        String urlB = videoFor("B", splitConfigured, emergency, videoUrl);
        String xmlA, xmlB;
        String where;
        if (location != null) {
            lastLat = location.getLatitude();
            lastLon = location.getLongitude();
            double ce = location.hasAccuracy() ? location.getAccuracy() : 0;
            xmlA = CotBuilder.buildPLI(uid, pilotCallsign(callsign), PILOT_TEAM, role, true,
                    location.getLatitude(), location.getLongitude(), location.getAltitude(), ce,
                    location.getBearing(), location.getSpeed(), battery,
                    takvPlatform, deviceWithCallsign(callsign), takvOs, takvVersion, urlA);
            xmlB = java.util.Objects.equals(urlA, urlB) ? xmlA : CotBuilder.buildPLI(uid, pilotCallsign(callsign),
                    PILOT_TEAM, role, true, location.getLatitude(), location.getLongitude(),
                    location.getAltitude(), ce, location.getBearing(), location.getSpeed(), battery,
                    takvPlatform, deviceWithCallsign(callsign), takvOs, takvVersion, urlB);
            where = lastLat + "," + lastLon;
        } else {
            xmlA = CotBuilder.buildPLINoFix(uid, pilotCallsign(callsign), PILOT_TEAM, role, battery,
                    takvPlatform, deviceWithCallsign(callsign), takvOs, takvVersion, urlA);
            xmlB = java.util.Objects.equals(urlA, urlB) ? xmlA : CotBuilder.buildPLINoFix(uid, pilotCallsign(callsign),
                    PILOT_TEAM, role, battery, takvPlatform, deviceWithCallsign(callsign), takvOs,
                    takvVersion, urlB);
            where = "position not known";
        }
        sendCotToBoth(xmlA, xmlB);
        AppLog.d(TAG, "Pilot PLI sent: " + pilotCallsign(callsign) + " @ " + where
                + (videoUrl != null && !videoUrl.isEmpty() ? " (+video)" : ""));
    }

    /**
     * The operator marker's callsign — the aircraft callsign with "-Pilot".
     *
     * Without it the operator marker and the aircraft marker share a name, and a viewer sees the
     * same callsign twice at two positions with no way to tell which is the aircraft.
     */
    public static String pilotCallsign(String callsign) {
        if (callsign == null || callsign.isEmpty()) return "Pilot";
        return callsign.endsWith("-Pilot") ? callsign : callsign + "-Pilot";
    }

    /** The pilot marker is always Cyan (design decision), whatever team the configuration
     *  uses, so the operator is one consistent colour across every aircraft. */
    private static final String PILOT_TEAM = "Cyan";

    public void sendPLI(Location location, String callsign, String team, String role, int battery) {
        if (client != null && connected) {
            lastLat = location.getLatitude();
            lastLon = location.getLongitude();
            String xml = CotBuilder.buildPLI(uid, callsign, team, role,
                    location.getLatitude(), location.getLongitude(), location.getAltitude(),
                    location.getBearing(), location.getSpeed(), battery,
                    takvPlatform, deviceWithCallsign(callsign), takvOs, takvVersion);
            sendCotToBoth(xml, xml);
            AppLog.d(TAG, "PLI sent: " + callsign + " @ " + lastLat + "," + lastLon);
            AppLog.d(TAG, "PLI XML: " + xml);
            if (!initialPliSent) {
                initialPliSent = true;
                AppLog.d(TAG, "First real PLI sent to TAK server");
            }
        }
    }

    /**
     * Send a position report for the AIRCRAFT (drone) as a distinct air track.
     * Independent of the operator's PLI — its own uid/callsign, air-domain CoT type.
     * Safe to call at a high rate (e.g. every 1-2s) while flying.
     */
    public void sendDronePLI(String droneUid, String droneCallsign,
                             double lat, double lon, double hae,
                             double heading, double speed, int battery,
                             String videoUrl, String spiUid,
                             double sensorFov, double sensorVfov, double sensorAzimuth,
                             double sensorElevation, double sensorRange, double northRef,
                             double gimbalRoll, double gimbalPitch, double gimbalYaw,
                             boolean isFlying, int flightTimeSec,
                             int batteryMaxMah, int batteryRemainMah, double voltage) {
        sendDronePLI(droneUid, droneCallsign, lat, lon, hae, heading, speed, battery, videoUrl,
                spiUid, sensorFov, sensorVfov, sensorAzimuth, sensorElevation, sensorRange,
                northRef, gimbalRoll, gimbalPitch, gimbalYaw, isFlying, flightTimeSec,
                batteryMaxMah, batteryRemainMah, voltage, -1, -1);
    }

    /** {@link #sendDronePLI} with the receiver's accuracy, metres (<= 0 = unknown). */
    public void sendDronePLI(String droneUid, String droneCallsign,
                             double lat, double lon, double hae,
                             double heading, double speed, int battery,
                             String videoUrl, String spiUid,
                             double sensorFov, double sensorVfov, double sensorAzimuth,
                             double sensorElevation, double sensorRange, double northRef,
                             double gimbalRoll, double gimbalPitch, double gimbalYaw,
                             boolean isFlying, int flightTimeSec,
                             int batteryMaxMah, int batteryRemainMah, double voltage,
                             double ce, double le) {
        if (client != null && connected) {
            boolean emergency = emergencyActiveNow();
            String urlA = videoFor("A", splitConfigured, emergency, videoUrl);
            String urlB = videoFor("B", splitConfigured, emergency, videoUrl);
            String xmlA = CotBuilder.buildDronePLI(droneUid, droneCallsign,
                    lat, lon, hae, heading, speed, battery,
                    urlA, spiUid,
                    sensorFov, sensorVfov, sensorAzimuth, sensorElevation, sensorRange, northRef,
                    gimbalRoll, gimbalPitch, gimbalYaw,
                    isFlying, flightTimeSec,
                    batteryMaxMah, batteryRemainMah, voltage,
                    this.uid, ce, le);
            String xmlB = java.util.Objects.equals(urlA, urlB) ? xmlA : CotBuilder.buildDronePLI(
                    droneUid, droneCallsign,
                    lat, lon, hae, heading, speed, battery,
                    urlB, spiUid,
                    sensorFov, sensorVfov, sensorAzimuth, sensorElevation, sensorRange, northRef,
                    gimbalRoll, gimbalPitch, gimbalYaw,
                    isFlying, flightTimeSec,
                    batteryMaxMah, batteryRemainMah, voltage,
                    this.uid, ce, le);
            // Was client.sendMessage(xml) directly — bypassed the logged/redacted path AND sent
            // identically to every connection regardless of video policy. See the incident note
            // on sendCotToBoth/sendOn above: that bypass is why a pilot who restricted this
            // aircraft's audience still broadcast its position to everyone.
            sendCotToBoth(xmlA, xmlB);
            AppLog.d(TAG, "Drone PLI sent: " + droneCallsign + " @ " + lat + "," + lon
                    + " alt=" + hae + " hdg=" + heading);
        }
    }

    /**
     * Send the camera slant point (sensor point of interest) — the ground point the
     * drone camera is looking at. Stable uid → updates one live marker.
     */
    public void sendCameraPoint(String spiUid, String droneUid, String callsign,
                                double lat, double lon, double rangeM) {
        sendCameraPoint(spiUid, droneUid, callsign, lat, lon, rangeM, Double.NaN);
    }

    /** {@link #sendCameraPoint} with the point's ellipsoid height, or NaN when not known. */
    public void sendCameraPoint(String spiUid, String droneUid, String callsign,
                                double lat, double lon, double rangeM, double haeM) {
        if (client != null && connected) {
            String xml = CotBuilder.buildSensorPoint(spiUid, droneUid, callsign, lat, lon, rangeM, haeM);
            // Was client.sendMessage(xml) directly — see the incident note on sendCotToBoth/
            // sendOn above. This message carries no video element either way, so the same string
            // goes to both connections.
            sendCotToBoth(xml, xml);
            AppLog.d(TAG, "Camera point sent: " + callsign + " @ " + lat + "," + lon
                    + " range=" + Math.round(rangeM) + "m");
        }
    }

    /** Send the camera footprint polygon (what the camera sees on the ground). */
    public void sendFootprint(String uid, String callsign, double[][] corners) {
        if (client != null && connected && corners != null && corners.length >= 3) {
            String xml = CotBuilder.buildFootprintPolygon(uid, callsign, corners);
            sendCotToBoth(xml, xml);
            AppLog.d(TAG, "Footprint sent: " + callsign + " (" + corners.length + " corners)");
        }
    }

    public void sendAlert(Location location, String alertType) {
        if (client == null || !connected) return;
        String xml = CotBuilder.buildAlert(uid, callsign, team, role,
                location.getLatitude(), location.getLongitude(), location.getAltitude(), alertType);
        sendCotToBoth(xml, xml);
        int uidStart = xml.indexOf("uid=\"") + 5;
        int uidEnd = xml.indexOf("\"", uidStart);
        activeAlertId = xml.substring(uidStart, uidEnd);
        AppLog.d(TAG, "Alert sent: " + alertType + " id=" + activeAlertId);
    }

    public void cancelAlert() {
        if (client == null || !connected || activeAlertId == null) return;
        String xml = CotBuilder.buildAlertCancel(uid, callsign, activeAlertId);
        sendCotToBoth(xml, xml);
        AppLog.d(TAG, "Alert cancelled: " + activeAlertId);
        activeAlertId = null;
    }

    /** Mint a marker uid. Only for a marker's FIRST send — reuse it afterwards, see below. */
    public static String newMarkerUid() {
        return "marker-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** Broadcast a marker CoT (server-wide) under a NEW uid; returns it, or null if not
     *  connected. Use {@link #sendMarkerWithUid} to update a marker that already exists. */
    public String sendMarker(double lat, double lon, double alt, String affiliation,
                             String name, String remarks) {
        return sendMarkerWithUid(newMarkerUid(), lat, lon, alt, affiliation, name, remarks, null);
    }

    /** Send a marker scoped to a Data Sync mission/feed only (NOT server-wide) via a
     *  &lt;marti&gt;&lt;dest mission=…/&gt;&lt;/marti&gt; tag. Returns its uid, or null if not connected. */
    public String sendMarkerToMission(double lat, double lon, double alt, String affiliation,
                                      String name, String remarks, String missionName) {
        return sendMarkerWithUid(newMarkerUid(), lat, lon, alt, affiliation, name, remarks, missionName);
    }

    /**
     * Send a marker CoT under a CALLER-SUPPLIED uid — the one call that can MOVE or otherwise
     * update an existing marker rather than spawning a new one.
     *
     * In CoT the uid <em>is</em> the marker's identity: re-send the same uid with new lat/lon
     * and fresh time/start/stale, and every ATAK/iTAK/TAKAware client moves the existing
     * marker in place. Send a fresh uid instead and they all draw a second marker. So a caller
     * that owns a persistent pin must store the uid from its first send and pass it back here
     * for every subsequent move/rename/retype/re-send.
     *
     * @param missionName Data Sync feed to scope to, or null to broadcast server-wide.
     * @return the uid that was sent, or null if not connected.
     */
    public String sendMarkerWithUid(String markerUid, double lat, double lon, double alt,
                                    String affiliation, String name, String remarks,
                                    String missionName) {
        return sendMarkerWithCotType(markerUid, lat, lon, alt,
                CotBuilder.cotTypeForAffiliation(affiliation), name, remarks, missionName,
                affiliation);
    }

    /**
     * Send a marker CoT under a caller-supplied uid AND a caller-supplied CoT type.
     *
     * Same uid contract as {@link #sendMarkerWithUid} — the uid is the marker's identity, so
     * re-sending one updates the marker rather than spawning a second.
     *
     * USE THIS TO RE-BROADCAST A MARKER THIS APP DID NOT CREATE. A marker received from the
     * team carries its own CoT type, and that type must go back out unchanged: deriving one
     * from an affiliation can only ever produce {@code a-{f,h,n,u}-G}, which would rewrite an
     * ATAK marker point ({@code b-m-p-*}) as a friendly ground marker on every screen in the
     * team.
     *
     * @param affiliationForLog only for the log line; it does not reach the XML.
     * @return the uid that was sent, or null if not connected.
     */
    public String sendMarkerWithCotType(String markerUid, double lat, double lon, double alt,
                                        String cotType, String name, String remarks,
                                        String missionName, String affiliationForLog) {
        if (client == null || !connected) return null;
        String xml = CotBuilder.buildMarkerWithType(uid, callsign, markerUid, cotType,
                lat, lon, alt, name, remarks, missionName, affiliationForLog);
        sendCotToBoth(xml, xml);
        // "QUEUED", not "sent". sendCotToBoth hands the message to a fire-and-forget writer thread and
        // this line runs whatever that thread then does with it. Saying "sent" here once hid a
        // failure: the log said every marker was sent while none arrived. The truth about
        // the wire is in the CoT OUT line above and in TakClient's failure lines.
        AppLog.d(TAG, "Marker queued for send"
                + (missionName != null ? " to mission " + missionName : "")
                + ": " + cotType + " @ " + lat + "," + lon + " id=" + markerUid);
        return markerUid;
    }

    public boolean hasActiveAlert() {
        return activeAlertId != null;
    }

    public boolean isConnected() {
        return connected;
    }

    public Collection<TakUser> getTakUsers() {
        return takUsers.values();
    }

    @Override
    public void onCotReceived(String xml) {
        // REDACT BEFORE TRUNCATING, not after — a teammate's marker can carry a
        // <__video url="rtsp://user:pass@..."> in a PLI, and that url is exactly the kind of
        // thing that shows up in the first 200 chars. Truncating first could still leak a
        // partial credential; redacting the whole string first cannot.
        String redacted = redactCredentials(xml);
        AppLog.d(TAG, "CoT received: " + redacted.substring(0, Math.min(redacted.length(), 200)));
        // t-x-g-c is the server saying "your channels changed, read them again". It arrives
        // whether the change came from this controller or from an administrator in TAK Portal,
        // and it arrives about a tenth of a second after the change — see the group-change
        // listener below for why this is better than a timer.
        // BOTH QUOTE STYLES. TAK Server double-quotes everything it relays, thus one style
        // works today — but TAKAware single-quotes its own CoT, so the style is the sender's
        // habit and not a rule. A quoting change would have stopped channel updates with no
        // error at all. The worst case is mild: without this the screen falls back to reading
        // on open, which is what it did before the listener existed.
        if (xml.contains("type=\"t-x-g-c\"") || xml.contains("type='t-x-g-c'")) {
            AppLog.i(TAG, "group change (t-x-g-c) — the active channels changed on the server");
            notifyGroupsChanged();
        }
        processCoT(xml);
    }

    /**
     * Told when the server's active channels for this certificate change.
     *
     * ⚠ THIS IS A NOTICE, NOT THE STATE. The event carries no channel list — it says only that
     * something changed. A listener must read the channels again to find out what they are.
     */
    public interface GroupChangeListener { void onGroupsChanged(); }

    private final List<GroupChangeListener> groupListeners = new ArrayList<>();

    public void addGroupChangeListener(GroupChangeListener l) {
        synchronized (groupListeners) { if (!groupListeners.contains(l)) groupListeners.add(l); }
    }

    public void removeGroupChangeListener(GroupChangeListener l) {
        synchronized (groupListeners) { groupListeners.remove(l); }
    }

    private void notifyGroupsChanged() {
        mainHandler.post(() -> {
            synchronized (groupListeners) {
                for (GroupChangeListener l : groupListeners) {
                    try { l.onGroupsChanged(); }
                    catch (Exception e) { AppLog.w(TAG, "group listener failed: " + e.getMessage()); }
                }
            }
        });
    }

    @Override
    public void onConnected() {
        connected = true;
        AppLog.d(TAG, "Connected to TAK server");
        if (uid != null) {
            String cs = callsign != null ? callsign : uid;
            // Registration message so the server lists this client and applies channel routing.
            //
            // There is no fix at this point, thus it goes out in the "position not known" form
            // (how="h-g-i-g-o", 0,0, ce/le/hae not known). Something must replace it, or the
            // pilot's callsign sits at 0,0 on the team's map until it goes stale. The bridge
            // sends a pilot PLI on every tick: the real position when the controller has a fix,
            // and this same "not known" form when it does not (see sendPilotPLI).
            String initCot = CotBuilder.buildPLINoFix(uid, pilotCallsign(cs), PILOT_TEAM, role,
                    100, takvPlatform, deviceWithCallsign(cs), takvOs, takvVersion, null);
            client.sendMessage(initCot);
            AppLog.d(TAG, "Initial PLI sent to register with server (position follows)");
        }
        mainHandler.post(() -> {
            synchronized (listeners) {
                for (TakUserListener l : listeners) l.onTakConnectionChanged(true);
            }
        });
    }

    @Override
    public void onDisconnected() {
        connected = false;
        AppLog.d(TAG, "Disconnected from TAK server");
        mainHandler.post(() -> {
            synchronized (listeners) {
                for (TakUserListener l : listeners) l.onTakConnectionChanged(false);
            }
        });
    }

    private void processCoT(String xml) {
        // Each inbound message would otherwise be parsed up to 3x: parseDisconnect, then
        // parseAlert, then parse, unconditionally in sequence — three full fresh parses — even though
        // the three type-sets below are mutually exclusive, so the CoT's own <event type> already
        // determines which single one could possibly succeed. peekEventType is a one-tag sniff,
        // not a full-document parse. A null sniff (couldn't tell — malformed XML, unexpected
        // shape) falls back to trying every branch as before: this can only skip an attempt that
        // was guaranteed to return null anyway, never change which branch ends up handling it.
        String sniffedType = CotParser.peekEventType(xml);
        boolean isDisconnectType = sniffedType == null || "t-x-d-d".equals(sniffedType);
        boolean isAlertType = sniffedType == null
                || "b-a-o-tbl".equals(sniffedType) || "b-a-o-can".equals(sniffedType);

        // Check for disconnect
        if (isDisconnectType) {
            try {
                String disconnectedUid = CotParser.parseDisconnect(xml);
                if (disconnectedUid != null) {
                    AppLog.d(TAG, "User disconnected: " + disconnectedUid);
                    TakUser user = takUsers.get(disconnectedUid);
                    if (user != null && !user.isPersistent()) {
                        // A client went away. Backdate its stale time and let removeStaleUsers()
                        // collect it, so it greys before it disappears.
                        user.setStaleTime(System.currentTimeMillis() - 1);
                        mainHandler.post(() -> {
                            synchronized (listeners) {
                                for (TakUserListener l : listeners) l.onTakUserUpdated(user);
                            }
                        });
                    } else {
                        // A `t-x-d-d` is also TAK's DELETE for a map item. Persistent items are
                        // exempt from the stale sweep, so backdating would never remove them —
                        // delete here.
                        //
                        // Fired even when the uid is NOT in takUsers: a marker restored from the
                        // saved store may not be in memory as a contact, and the team deleting it
                        // must still clear the persisted copy. Listeners treat this as "forget it
                        // for good".
                        takUsers.remove(disconnectedUid);
                        mainHandler.post(() -> {
                            synchronized (listeners) {
                                for (TakUserListener l : listeners) l.onTakUserDeleted(disconnectedUid);
                            }
                        });
                    }
                    return;
                }
            } catch (Exception e) {
                // ignore
            }
        }

        // Check for alert
        CotParser.AlertMessage alert = isAlertType ? CotParser.parseAlert(xml) : null;
        if (alert != null) {
            String senderUid = alert.linkedUid != null ? alert.linkedUid : "";
            if (senderUid.equals(uid)) return;

            if (alert.isCancellation) {
                TakUser sender = findUserByUid(senderUid);
                String cancelCallsign = alert.senderCallsign != null ? alert.senderCallsign : (sender != null ? sender.getCallsign() : senderUid);
                if (sender != null) {
                    sender.setEmergencyActive(false);
                    sender.setEmergencyType(null);
                }
                mainHandler.post(() -> {
                    synchronized (alertListeners) {
                        for (TakAlertListener l : alertListeners) l.onAlertCancelled(senderUid, cancelCallsign);
                    }
                });
            } else {
                TakUser sender = findUserByUid(senderUid);
                if (sender != null) {
                    sender.setEmergencyActive(true);
                    sender.setEmergencyType(alert.alertType);
                }
                mainHandler.post(() -> {
                    synchronized (alertListeners) {
                        for (TakAlertListener l : alertListeners) {
                            l.onAlertReceived(senderUid,
                                    alert.senderCallsign != null ? alert.senderCallsign : senderUid,
                                    alert.alertType, alert.lat, alert.lon);
                        }
                    }
                });
            }
            return;
        }

        // Parse position (includes drone/video detection)
        TakUser user = CotParser.parse(xml);
        if (user == null || user.getUid().equals(uid)) return;

        if (user.isDrone()) {
            AppLog.d(TAG, "Drone detected: " + user.getCallsign()
                    + (user.getSensorModel() != null ? " (" + user.getSensorModel() + ")" : "")
                    + (user.hasVideo() ? " [video]" : ""));
        }

        takUsers.put(user.getUid(), user);
        mainHandler.post(() -> {
            synchronized (listeners) {
                for (TakUserListener l : listeners) l.onTakUserUpdated(user);
            }
        });
    }

    /**
     * Drops contacts whose stale window has passed.
     *
     * ⚠ PERSISTENT ITEMS ARE EXEMPT FROM DELETION. A marker somebody shared is not a track: it does
     * not re-broadcast on a heartbeat, and a sender may declare a stale window that is useless
     * (CloudTAK sends ~3.6 s). Deleting on a timeout made shared markers vanish within minutes of
     * arriving. They still go stale — the icon greys — but only an explicit delete, a local delete
     * or the 72-hour eviction in TakMapMarkers removes them.
     *
     * ⚠ THIS EXEMPTION MUST NEVER REACH AIR TRACKS. Unbounded contact retention has OOM-killed
     * the flight app in the air. {@link CotParser#isPersistentType} is where that is
     * enforced; read it before widening what counts as persistent.
     */
    private void removeStaleUsers() {
        // RETENTION WATCHDOG. This count is the number that mattered in that crash: the map held
        // 161 distinct aircraft while the live picture showed a handful, and the process was
        // OOM-killed in the air. It is cheap (one line per 30 s sweep) and it is the only thing
        // that makes a slow retention leak visible before it becomes a crash.
        //
        // What to look for: `total` should oscillate around the size of the real picture, not
        // climb steadily over a session. `persistent` should track the number of markers actually
        // shared with this aircraft — if it grows with air traffic, the persistence rule has
        // sprung a leak. See CotParser.isPersistentType.
        int persistentCount = 0;
        for (TakUser u : takUsers.values()) if (u != null && u.isPersistent()) persistentCount++;
        AppLog.i(TAG, "contacts held: " + takUsers.size() + " total, "
                + persistentCount + " persistent");

        for (String key : takUsers.keySet()) {
            TakUser user = takUsers.get(key);
            if (user != null) {
                if (user.isPersistent()) {
                    // Never removed here, and never stale: TakUser.isStale is false for a
                    // persistent item, so there is no grey repaint to post. A placed
                    // marker leaves on a delete or on the 72-hour eviction in TakMapMarkers.
                    continue;
                }
                if (user.isExpired()) {
                    takUsers.remove(key);
                    mainHandler.post(() -> {
                        synchronized (listeners) {
                            for (TakUserListener l : listeners) l.onTakUserRemoved(key);
                        }
                    });
                } else if (user.isStale()) {
                    mainHandler.post(() -> {
                        synchronized (listeners) {
                            for (TakUserListener l : listeners) l.onTakUserUpdated(user);
                        }
                    });
                }
            }
        }
    }
}
