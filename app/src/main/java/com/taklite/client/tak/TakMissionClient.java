package com.taklite.client.tak;

import com.taklite.util.AppLog;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

/**
 * TAK Server "Data Sync" / Mission API client (HTTPS on the cert API port, default 8443).
 *
 * Reuses the same PKCS12 client + trust certs as the CoT stream ({@link TakClient}); pulls host
 * and cert paths from {@link TakManager}. Endpoints (base /Marti/api/missions):
 *   GET    /Marti/api/missions                     list feeds
 *   GET    /Marti/api/missions/{name}              feed metadata
 *   PUT    /Marti/api/missions/{name}?creatorUid=… create feed
 *   DELETE /Marti/api/missions/{name}              delete feed
 *   PUT    /Marti/api/missions/{name}/subscription subscribe   (?uid=…)
 *   DELETE /Marti/api/missions/{name}/subscription unsubscribe (?uid=…)
 *   GET    /Marti/api/missions/{name}/cot          all CoT (<events>…)
 *   GET    /Marti/api/missions/{name}/changes      changes since (poll)
 *   PUT    /Marti/api/missions/{name}/contents     add CoT/marker (JSON body of uids/hashes)
 *
 * All calls are blocking — invoke on a background thread.
 */
public class TakMissionClient {
    private static final String TAG = "TakMissionClient";

    private final String host;
    private final int port;
    private final SSLSocketFactory sslFactory;

    private TakMissionClient(String host, int port, SSLSocketFactory f) {
        this.host = host; this.port = port; this.sslFactory = f;
    }

    /** Build from the certs/host TakManager retained at connect() — cert A. Returns null if not
     *  enrolled. Thin wrapper over {@link #fromCert} so there is one TLS-setup implementation. */
    public static TakMissionClient fromTakManager(int apiPort) {
        TakManager tm = TakManager.getInstance();
        String host = tm.getServerAddress();
        String trust = tm.getTrustStorePath();
        String trustPw = tm.getTrustStorePassword();
        String cert = tm.getClientCertPath();
        String certPw = tm.getClientCertPassword();
        if (host == null || cert == null || trust == null) {
            AppLog.w(TAG, "Not enrolled — no host/certs to build Mission client");
            return null;
        }
        return fromCert(host, apiPort, trust, trustPw, cert, certPw);
    }

    /**
     * Build a Mission client from EXPLICIT cert material rather than reading the
     * {@link TakManager} singleton. This is what lets a caller stand up a Mission client for a
     * SECOND certificate (e.g. a video-channel cert) that TakManager's own getters never expose,
     * without disturbing cert A's state.
     *
     * @return null if the TLS material could not be loaded.
     */
    public static TakMissionClient fromCert(String host, int apiPort,
            String trustStorePath, String trustStorePassword,
            String clientCertPath, String clientCertPassword) {
        if (host == null || clientCertPath == null || trustStorePath == null) {
            AppLog.w(TAG, "fromCert: missing host/certs — cannot build Mission client");
            return null;
        }
        try {
            KeyStore trustStore = KeyStore.getInstance("PKCS12");
            try (FileInputStream in = new FileInputStream(trustStorePath)) {
                trustStore.load(in, trustStorePassword.toCharArray());
            }
            KeyStore clientStore = KeyStore.getInstance("PKCS12");
            try (FileInputStream in = new FileInputStream(clientCertPath)) {
                clientStore.load(in, clientCertPassword.toCharArray());
            }
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(trustStore);
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(clientStore, clientCertPassword.toCharArray());
            SSLContext ctx = SSLContext.getInstance("TLSv1.3");
            ctx.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
            return new TakMissionClient(host, apiPort, ctx.getSocketFactory());
        } catch (Exception e) {
            AppLog.e(TAG, "Failed to build Mission client TLS: " + e.getMessage());
            return null;
        }
    }

    // ---- Feed model ----
    public static class Feed {
        public String name;
        public String description;
        public String creatorUid;
        public String defaultRole;
        public int itemCount;
        public boolean passwordProtected;
    }

    /** GET /Marti/api/missions — list all feeds on the server. */
    public List<Feed> listFeeds() {
        List<Feed> out = new ArrayList<>();
        try {
            // passwordProtected+defaultRole=true make the server return feeds the user hasn't
            // fully joined yet. NOTE: the server still only returns missions in the caller cert's
            // GROUP/channel scope — feeds in channels this cert isn't a member of won't appear
            // (that's a server-side membership thing, not a client bug).
            String body = request("GET", "/Marti/api/missions?passwordProtected=true&defaultRole=true", null, "application/json");
            if (body == null) return out;
            JSONObject root = new JSONObject(body);
            JSONArray data = root.optJSONArray("data");
            if (data == null) return out;
            for (int i = 0; i < data.length(); i++) {
                JSONObject m = data.getJSONObject(i);
                Feed f = new Feed();
                f.name = m.optString("name");
                f.description = m.optString("description");
                f.creatorUid = m.optString("creatorUid");
                f.defaultRole = m.optJSONObject("defaultRole") != null
                        ? m.getJSONObject("defaultRole").optString("type") : m.optString("defaultRole");
                f.passwordProtected = m.optBoolean("passwordProtected", false);
                JSONArray contents = m.optJSONArray("contents");
                JSONArray uids = m.optJSONArray("uids");
                f.itemCount = (contents != null ? contents.length() : 0) + (uids != null ? uids.length() : 0);
                out.add(f);
            }
        } catch (Exception e) {
            AppLog.w(TAG, "listFeeds failed: " + e.getMessage());
        }
        return out;
    }

    /**
     * GET /Marti/api/groups/all — the channels/groups the logged-in user belongs to (the ones
     * that house individuals). TAK returns each group twice (IN and OUT direction); this dedupes
     * by name, skips the implicit __ANON__, and returns a clean sorted list for the "My Channels"
     * picker on the TAK Setup screen.
     */
    /**
     * One channel as the server describes it, for one direction.
     *
     * The server sends ONE RECORD FOR EACH DIRECTION. A channel the certificate can send to and
     * receive from arrives twice, once IN and once OUT. A channel it can only receive from
     * arrives once, OUT. That is the only way to know which channels accept a send, and it is
     * what {@link #listChannels()} folds together.
     */
    public static class Channel {
        public final String name;
        public final int bitpos;
        public boolean canSend;      // an IN record exists
        public boolean canReceive;   // an OUT record exists
        public boolean active;       // switched on for this certificate right now
        public String description;
        Channel(String name, int bitpos) { this.name = name; this.bitpos = bitpos; }
        @Override public String toString() {
            return name + "[bit=" + bitpos + (canSend ? " send" : "") + (canReceive ? " recv" : "")
                    + (active ? " ACTIVE" : " off") + "]";
        }
    }

    /**
     * The channels this certificate has, folded to one entry each.
     *
     * ⚠ THE QUERY PARAMETERS ARE NOT OPTIONAL. Without them the server omits the IN records and
     * every channel looks receive-only — proved against a live server, same certificate, same
     * minute: the bare path returned 2 records and this one returned 3, and the extra record is
     * the IN that says a channel accepts a send.
     */
    public List<Channel> listChannels() {
        java.util.LinkedHashMap<String, Channel> byName = new java.util.LinkedHashMap<>();
        try {
            String body = request("GET", "/Marti/api/groups/all?useCache=true&sendLatestSA=true",
                    null, "application/json");
            if (body == null) return new ArrayList<>();
            JSONArray data = new JSONObject(body).optJSONArray("data");
            if (data == null) return new ArrayList<>();
            for (int i = 0; i < data.length(); i++) {
                JSONObject g = data.getJSONObject(i);
                String name = g.optString("name", null);
                if (name == null || name.isEmpty() || name.startsWith("__")) continue;
                Channel c = byName.get(name);
                if (c == null) { c = new Channel(name, g.optInt("bitpos", -1)); byName.put(name, c); }
                if ("IN".equalsIgnoreCase(g.optString("direction", ""))) c.canSend = true;
                if ("OUT".equalsIgnoreCase(g.optString("direction", ""))) c.canReceive = true;
                // active is per channel, not per direction; either record carries it.
                if (g.optBoolean("active", false)) c.active = true;
                String d = g.optString("description", null);
                if (d != null && !d.isEmpty()) c.description = d;
            }
            AppLog.i(TAG, "channels: " + byName.values());
        } catch (Exception e) {
            AppLog.w(TAG, "listChannels failed: " + e.getMessage());
        }
        return new ArrayList<>(byName.values());
    }

    /**
     * Sets which channels are active for this certificate.
     *
     * ⚠ THE LIST IS ABSOLUTE. The server activates the channels whose bitpos is in the list and
     * deactivates every other one. An empty list switches them all off. Always send the COMPLETE
     * set you want, never a change.
     *
     * ⚠ IT BELONGS TO THE CERTIFICATE, NOT THE AIRCRAFT. Two controllers enrolled as one user
     * share one set, and this call changes it for both.
     *
     * The body shape comes from the server source — {@code PUT /groups/activebits} takes
     * {@code Integer[]} and compares each with {@code group.getBitpos()}. It has not been
     * confirmed against a live server. The status and any error body are logged.
     *
     * @return true when the server answered 2xx.
     */
    public boolean setActiveChannels(List<Integer> bitpos) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < bitpos.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(bitpos.get(i));
        }
        sb.append("]");
        AppLog.i(TAG, "PUT /Marti/api/groups/activebits " + sb);
        int code = requestStatus("PUT", "/Marti/api/groups/activebits", sb.toString(),
                "application/json");
        AppLog.i(TAG, "activebits -> HTTP " + code);
        return code >= 200 && code < 300;
    }

    /** GET /Marti/api/groups/all — the channels/groups this cert can use (to scope a feed). */
    public List<String> listGroups() {
        List<String> out = new ArrayList<>();
        try {
            String body = request("GET", "/Marti/api/groups/all", null, "application/json");
            AppLog.i(TAG, "listGroups body=" + (body == null ? "null" : body.substring(0, Math.min(body.length(), 500))));
            if (body == null) return out;
            JSONObject root = new JSONObject(body);
            JSONArray data = root.optJSONArray("data");
            if (data == null) return out;
            for (int i = 0; i < data.length(); i++) {
                JSONObject g = data.getJSONObject(i);
                String name = g.optString("name", null);
                // Only outbound/usable channels; skip the implicit __ANON__ if present.
                if (name != null && !name.isEmpty()) out.add(name);
            }
        } catch (Exception e) {
            AppLog.w(TAG, "listGroups failed: " + e.getMessage());
        }
        return out;
    }

    /** PUT /Marti/api/missions/{name}?creatorUid=… — create a feed. group=null → default scope. */
    public boolean createFeed(String name, String creatorUid, String description, String defaultRole, String group) {
        String q = "?creatorUid=" + enc(creatorUid)
                + (defaultRole != null ? "&defaultRole=" + enc(defaultRole) : "")
                + (group != null && !group.isEmpty() ? "&group=" + enc(group) : "")
                + (description != null && !description.isEmpty() ? "&description=" + enc(description) : "");
        return ok(requestStatus("PUT", "/Marti/api/missions/" + enc(name) + q, null, null));
    }

    /** DELETE /Marti/api/missions/{name}. */
    public boolean deleteFeed(String name, String creatorUid) {
        return ok(requestStatus("DELETE", "/Marti/api/missions/" + enc(name) + "?creatorUid=" + enc(creatorUid), null, null));
    }

    /** PUT /Marti/api/missions/{name}/subscription?uid=… — join a feed. */
    public boolean subscribe(String name, String uid, String password) {
        String q = "?uid=" + enc(uid) + (password != null && !password.isEmpty() ? "&password=" + enc(password) : "");
        return ok(requestStatus("PUT", "/Marti/api/missions/" + enc(name) + "/subscription" + q, null, null));
    }

    public boolean unsubscribe(String name, String uid) {
        return ok(requestStatus("DELETE", "/Marti/api/missions/" + enc(name) + "/subscription?uid=" + enc(uid), null, null));
    }

    /** GET /Marti/api/missions/{name}/cot — all CoT in the feed, as an &lt;events&gt; XML string. */
    public String getFeedCot(String name) {
        return request("GET", "/Marti/api/missions/" + enc(name) + "/cot", null, "application/xml");
    }

    /** GET /Marti/api/missions/{name}/changes — changes (poll for updates). */
    public String getChanges(String name) {
        return request("GET", "/Marti/api/missions/" + enc(name) + "/changes", null, null);
    }

    /**
     * PUT /Marti/api/missions/{name}/contents — add already-known CoT uids to the feed.
     * ATAK adds a marker by first sending its CoT over the stream (so the server has it by uid),
     * then referencing that uid here. Body: {"uids":["<uid>"]}.
     */
    public boolean addUidToFeed(String name, String uid, String creatorUid) {
        try {
            JSONObject body = new JSONObject();
            JSONArray uids = new JSONArray();
            uids.put(uid);
            body.put("uids", uids);
            String q = creatorUid != null ? "?creatorUid=" + enc(creatorUid) : "";
            return ok(requestStatus("PUT", "/Marti/api/missions/" + enc(name) + "/contents" + q,
                    body.toString(), "application/json"));
        } catch (Exception e) {
            AppLog.w(TAG, "addUidToFeed failed: " + e.getMessage());
            return false;
        }
    }

    // ---- HTTP plumbing ----
    private String request(String method, String path, String body, String accept) {
        int[] status = new int[1];
        return doRequest(method, path, body, accept, status);
    }

    private int requestStatus(String method, String path, String body, String contentType) {
        int[] status = new int[1];
        doRequest(method, path, body, null, status);
        return status[0];
    }

    private String doRequest(String method, String path, String body, String accept, int[] statusOut) {
        HttpsURLConnection c = null;
        try {
            URL url = new URL("https://" + host + ":" + port + path);
            c = (HttpsURLConnection) url.openConnection();
            c.setSSLSocketFactory(sslFactory);
            c.setRequestMethod(method);
            c.setConnectTimeout(8000);
            c.setReadTimeout(12000);
            if (accept != null) c.setRequestProperty("Accept", accept);
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", accept != null ? accept : "application/json");
                try (OutputStream os = c.getOutputStream()) {
                    os.write(body.getBytes("UTF-8"));
                }
            }
            int code = c.getResponseCode();
            if (statusOut != null) statusOut[0] = code;
            InputStream is = (code >= 200 && code < 300) ? c.getInputStream() : c.getErrorStream();
            String resp = readAll(is);
            if (code < 200 || code >= 300) AppLog.w(TAG, method + " " + redactPath(path) + " -> " + code + " : " + snippet(resp));
            return resp;
        } catch (Exception e) {
            AppLog.w(TAG, method + " " + redactPath(path) + " error: " + e.getMessage());
            if (statusOut != null) statusOut[0] = -1;
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    // subscribe() puts the feed password in the query string (?password=...). Every doRequest
    // log line goes through this before it reaches AppLog, so a subscribe failure never writes
    // the plaintext password to logcat or to the exported log file.
    private static String redactPath(String path) {
        if (path == null) return null;
        return path.replaceAll("([?&]password=)[^&\\s]+", "$1<redacted>");
    }

    private static String readAll(InputStream is) {
        if (is == null) return null;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(is, "UTF-8"))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean ok(int code) { return code >= 200 && code < 300; }
    private static String enc(String s) {
        try { return URLEncoder.encode(s == null ? "" : s, "UTF-8"); } catch (Exception e) { return ""; }
    }
    private static String snippet(String s) { return s == null ? "" : s.substring(0, Math.min(s.length(), 200)); }
}
