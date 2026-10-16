package com.taklite.client.tak;

import com.taklite.util.AppLog;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLEncoder;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/**
 * Automatically fetches and assigns all available TAK server groups
 * after certificate enrollment completes.
 */
public class TakGroupAssigner {
    private static final String TAG = "TakGroupAssigner";

    public interface AssignCallback {
        void onComplete(boolean success, String message);
    }

    /**
     * Fetches all groups from the TAK server and assigns them to the enrolled user.
     * Runs on the calling thread — caller should invoke from a background thread.
     */
    public static void autoAssignAllGroups(String serverAddress, String trustStorePath,
                                            String clientCertPath, String certPw,
                                            String username, String password,
                                            AssignCallback callback) {
        try {
            SSLContext sslCtx = createMutualTlsContext(trustStorePath, clientCertPath, certPw);

            String authHeader = null;
            if (username != null && !username.isEmpty() && password != null && !password.isEmpty()) {
                authHeader = "Basic " + android.util.Base64.encodeToString(
                        (username + ":" + password).getBytes(), android.util.Base64.NO_WRAP);
            }

            // Fetch groups
            List<String> groups = new ArrayList<>();
            if (authHeader != null) {
                try {
                    String json = apiGet("https://" + serverAddress + ":8443/Marti/api/groups/all",
                            sslCtx, authHeader);
                    groups = parseGroups(json);
                    AppLog.d(TAG, "Fetched " + groups.size() + " groups with auth");
                } catch (Exception e) {
                    AppLog.w(TAG, "Groups with auth failed: " + e.getMessage());
                }
            }
            if (groups.isEmpty()) {
                try {
                    String json = apiGet("https://" + serverAddress + ":8443/Marti/api/groups/all",
                            sslCtx, null);
                    groups = parseGroups(json);
                    AppLog.d(TAG, "Fetched " + groups.size() + " groups with cert only");
                } catch (Exception e) {
                    AppLog.w(TAG, "Groups with cert only failed: " + e.getMessage());
                }
            }

            if (groups.isEmpty()) {
                AppLog.w(TAG, "No groups found, skipping assignment");
                if (callback != null) callback.onComplete(false, "No groups found");
                return;
            }

            // Get cert CN for assignment
            String certCN = getCertificateCN(clientCertPath, certPw);
            if (certCN == null || certCN.isEmpty()) {
                AppLog.w(TAG, "Could not determine certificate CN");
                if (callback != null) callback.onComplete(false, "Could not determine certificate CN");
                return;
            }

            // Assign all groups
            boolean success = false;
            for (String group : groups) {
                try {
                    String url = "https://" + serverAddress + ":8443/Marti/api/groups/"
                            + URLEncoder.encode(group, "UTF-8") + "/users";
                    apiPut(url, "[\"" + certCN + "\"]", sslCtx, authHeader);
                    success = true;
                    AppLog.d(TAG, "Assigned group: " + group);
                } catch (Exception e) {
                    AppLog.w(TAG, "Group assign method 1 failed for " + group + ": " + e.getMessage());
                }
            }

            if (!success) {
                try {
                    JSONObject body = new JSONObject();
                    body.put("username", certCN);
                    JSONArray groupArray = new JSONArray();
                    for (String g : groups) {
                        JSONObject gObj = new JSONObject();
                        gObj.put("name", g);
                        gObj.put("direction", "BOTH");
                        groupArray.put(gObj);
                    }
                    body.put("groups", groupArray);
                    apiPut("https://" + serverAddress + ":8443/user-management/api/update-groups",
                            body.toString(), sslCtx, authHeader);
                    success = true;
                    AppLog.d(TAG, "Bulk group assignment succeeded");
                } catch (Exception e) {
                    AppLog.w(TAG, "Bulk group assign failed: " + e.getMessage());
                }
            }

            int count = groups.size();
            if (callback != null) {
                callback.onComplete(success, success
                        ? "Assigned " + count + " groups"
                        : "Group assignment failed");
            }
        } catch (Exception e) {
            AppLog.e(TAG, "Auto-assign groups failed", e);
            if (callback != null) callback.onComplete(false, e.getMessage());
        }
    }

    private static SSLContext createMutualTlsContext(String trustStorePath, String clientCertPath,
                                                      String certPw) throws Exception {
        KeyStore trustStore = KeyStore.getInstance("PKCS12");
        FileInputStream trustIs = new FileInputStream(trustStorePath);
        trustStore.load(trustIs, certPw.toCharArray());
        trustIs.close();

        KeyStore clientStore = KeyStore.getInstance("PKCS12");
        FileInputStream clientIs = new FileInputStream(clientCertPath);
        clientStore.load(clientIs, certPw.toCharArray());
        clientIs.close();

        TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(
                KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(clientStore, certPw.toCharArray());

        SSLContext sslCtx = SSLContext.getInstance("TLS");
        sslCtx.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
        return sslCtx;
    }

    private static String apiGet(String urlStr, SSLContext sslCtx, String authHeader) throws Exception {
        URL url = new URL(urlStr);
        HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
        conn.setSSLSocketFactory(sslCtx.getSocketFactory());
        conn.setHostnameVerifier((h, s) -> {
            try { s.getPeerCertificates(); return true; } catch (Exception e) { return false; }
        });
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Accept", "application/json");
        if (authHeader != null) conn.setRequestProperty("Authorization", authHeader);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        int code = conn.getResponseCode();
        if (code != 200) throw new Exception("HTTP " + code);
        BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) sb.append(line);
        reader.close();
        return sb.toString();
    }

    private static void apiPut(String urlStr, String body, SSLContext sslCtx,
                                String authHeader) throws Exception {
        URL url = new URL(urlStr);
        HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
        conn.setSSLSocketFactory(sslCtx.getSocketFactory());
        conn.setHostnameVerifier((h, s) -> {
            try { s.getPeerCertificates(); return true; } catch (Exception e) { return false; }
        });
        conn.setRequestMethod("PUT");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "application/json");
        if (authHeader != null) conn.setRequestProperty("Authorization", authHeader);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        conn.setDoOutput(true);
        conn.getOutputStream().write(body.getBytes());
        conn.getOutputStream().flush();
        conn.getOutputStream().close();
        int code = conn.getResponseCode();
        if (code < 200 || code >= 300) {
            throw new Exception("HTTP " + code);
        }
    }

    private static List<String> parseGroups(String json) {
        List<String> groups = new ArrayList<>();
        try {
            JSONArray arr;
            if (json.trim().startsWith("{")) {
                JSONObject obj = new JSONObject(json);
                if (obj.has("data")) arr = obj.getJSONArray("data");
                else if (obj.has("groups")) arr = obj.getJSONArray("groups");
                else return groups;
            } else {
                arr = new JSONArray(json);
            }
            for (int i = 0; i < arr.length(); i++) {
                Object item = arr.get(i);
                if (item instanceof JSONObject) {
                    String name = ((JSONObject) item).optString("name", "");
                    if (!name.isEmpty() && !name.startsWith("__")) groups.add(name);
                } else if (item instanceof String) {
                    String name = (String) item;
                    if (!name.startsWith("__")) groups.add(name);
                }
            }
        } catch (Exception e) {
            AppLog.w(TAG, "Failed to parse groups: " + e.getMessage());
        }
        return groups;
    }

    private static String getCertificateCN(String certPath, String password) {
        try {
            KeyStore ks = KeyStore.getInstance("PKCS12");
            FileInputStream fis = new FileInputStream(certPath);
            ks.load(fis, password.toCharArray());
            fis.close();
            Enumeration<String> aliases = ks.aliases();
            while (aliases.hasMoreElements()) {
                Certificate cert = ks.getCertificate(aliases.nextElement());
                if (cert instanceof X509Certificate) {
                    String dn = ((X509Certificate) cert).getSubjectX500Principal().getName();
                    for (String part : dn.split(",")) {
                        String trimmed = part.trim();
                        if (trimmed.startsWith("CN=")) return trimmed.substring(3);
                    }
                }
            }
        } catch (Exception e) {
            AppLog.e(TAG, "Failed to read cert CN", e);
        }
        return null;
    }
}
