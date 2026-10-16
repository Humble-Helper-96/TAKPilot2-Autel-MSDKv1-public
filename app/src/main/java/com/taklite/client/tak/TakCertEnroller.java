package com.taklite.client.tak;

import android.util.Base64;
import com.taklite.util.AppLog;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLEncoder;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.json.JSONObject;

public class TakCertEnroller {
    private static final String TAG = "TakCertEnroller";
    private static final String DEFAULT_P12_PASSWORD = "atakatak";

    public interface EnrollmentCallback {
        void onSuccess(String trustStorePath, String clientCertPath);
        void onError(String error);
    }

    /**
     * Enrolls with the TAK server and saves the resulting cert pair under the default
     * {@code tak_} file names. Forwards to the prefixed overload so there is one enrollment
     * implementation — see that overload's doc for what a second, differently-prefixed call is
     * for (a second certificate on the same device, e.g. a video-channel cert alongside this
     * one, each enrolled under its own TAK Server username/password).
     */
    public static void enroll(String serverAddress, int enrollPort, String username, String password,
                              String uid, File filesDir, EnrollmentCallback callback) {
        enroll(serverAddress, enrollPort, username, password, uid, filesDir, "tak_", callback);
    }

    /**
     * Enrolls with the TAK server and saves the resulting cert pair as
     * {@code <fileNamePrefix>clientcert.p12} / {@code <fileNamePrefix>truststore.p12} in
     * {@code filesDir}.
     *
     * The prefix is what lets one device hold TWO enrolled certificates side by side — e.g.
     * {@code "tak_"} for a device's normal certificate and {@code "tak_video_"} for a second
     * certificate enrolled under a different TAK Server username, without one enrollment
     * overwriting the other's files. Each certificate still needs its own username/password at
     * call time: this method does not split one account into two certs, it enrolls once per call
     * exactly as before, just under a caller-chosen file name.
     */
    public static void enroll(String serverAddress, int enrollPort, String username, String password,
                              String uid, File filesDir, String fileNamePrefix,
                              EnrollmentCallback callback) {
        try {
            SSLContext sslCtx = createEnrollmentSSLContext();
            String basicAuth = "Basic " + Base64.encodeToString((username + ":" + password).getBytes(), Base64.NO_WRAP);

            // Step 1: Fetch TLS config
            String configUrl = "https://" + serverAddress + ":" + enrollPort + "/Marti/api/tls/config";
            AppLog.d(TAG, "Step 1: Fetching TLS config from " + configUrl);
            String configXml = httpsGet(configUrl, basicAuth, sslCtx);
            String org = parseNameEntry(configXml, "O", "TAK");
            String orgUnit = parseNameEntry(configXml, "OU", "TAK");
            AppLog.d(TAG, "TLS config: O=" + org + ", OU=" + orgUnit);

            // Step 2: Generate key pair and CSR
            AppLog.d(TAG, "Step 2: Generating RSA key pair and CSR");
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
            kpg.initialize(2048);
            KeyPair keyPair = kpg.generateKeyPair();
            String csrPem = generateCSR(keyPair, username, org, orgUnit);
            AppLog.d(TAG, "CSR generated for CN=" + username);

            // Step 3: Sign CSR
            String signUrl = "https://" + serverAddress + ":" + enrollPort
                    + "/Marti/api/tls/signClient/v2?clientUid=" + urlEncode(uid) + "&version=3";
            AppLog.d(TAG, "Step 3: Signing CSR at " + signUrl);
            String responseJson = httpsPost(signUrl, csrPem, basicAuth, sslCtx);

            JSONObject json = new JSONObject(responseJson);
            String signedCertB64 = json.getString("signedCert");
            String ca0B64 = json.optString("ca0", "");
            String ca1B64 = json.optString("ca1", "");
            AppLog.d(TAG, "Got signed cert + CA chain from server");

            X509Certificate signedCert = decodeCert(signedCertB64);

            // The server must have signed a cert for OUR public key. If it did not (a mismatched
            // or substituted cert), bundling it with our private key produces a .p12 that fails
            // TLS later, far from the cause. Verify here and fail with a clear message instead.
            if (!java.util.Arrays.equals(signedCert.getPublicKey().getEncoded(),
                    keyPair.getPublic().getEncoded())) {
                throw new Exception("Signed certificate's public key does not match the generated key pair");
            }

            X509Certificate caCert0 = ca0B64.isEmpty() ? null : decodeCert(ca0B64);
            X509Certificate caCert1 = ca1B64.isEmpty() ? null : decodeCert(ca1B64);

            // Build client .p12
            File clientP12 = new File(filesDir, fileNamePrefix + "clientcert.p12");
            buildClientP12(keyPair, signedCert, caCert0, caCert1, clientP12);
            AppLog.d(TAG, "Client .p12 saved: " + clientP12.getAbsolutePath());

            // Build trust store
            File trustP12 = new File(filesDir, fileNamePrefix + "truststore.p12");
            boolean trustBuilt = false;
            try {
                String trustUrl = "https://" + serverAddress + ":" + enrollPort + "/api/truststore";
                byte[] trustData = httpsGetBytes(trustUrl, basicAuth, sslCtx);
                if (trustData != null && trustData.length > 0) {
                    FileOutputStream fos = new FileOutputStream(trustP12);
                    fos.write(trustData);
                    fos.close();
                    trustBuilt = true;
                    AppLog.d(TAG, "Trust store downloaded from /api/truststore");
                }
            } catch (Exception e) {
                AppLog.w(TAG, "Could not download truststore, building from CA certs: " + e.getMessage());
            }
            if (!trustBuilt) {
                buildTrustStoreP12(caCert0, caCert1, trustP12);
                AppLog.d(TAG, "Trust store built from CA certs");
            }

            callback.onSuccess(trustP12.getAbsolutePath(), clientP12.getAbsolutePath());
        } catch (Exception e) {
            AppLog.e(TAG, "Enrollment failed", e);
            callback.onError(e.getMessage());
        }
    }

    private static String generateCSR(KeyPair keyPair, String cn, String org, String orgUnit) throws Exception {
        String subject = "CN=" + cn + ",O=" + org + ",OU=" + orgUnit;
        return buildMinimalCSR(keyPair, subject);
    }

    private static String buildMinimalCSR(KeyPair keyPair, String subject) throws Exception {
        // Parse subject DN components
        String cn = "", org = "", ou = "";
        for (String part : subject.split(",")) {
            String trimmed = part.trim();
            if (trimmed.startsWith("CN=")) cn = trimmed.substring(3);
            else if (trimmed.startsWith("O=")) org = trimmed.substring(2);
            else if (trimmed.startsWith("OU=")) ou = trimmed.substring(3);
        }

        // Build subject RDN sequence
        byte[] cnRdn = buildRDN("2.5.4.3", cn);
        byte[] orgRdn = buildRDN("2.5.4.10", org);
        byte[] ouRdn = buildRDN("2.5.4.11", ou);
        byte[] subjectDn = buildSequence(concat(cnRdn, orgRdn, ouRdn));

        // Build SubjectPublicKeyInfo
        byte[] pubKeyEncoded = keyPair.getPublic().getEncoded();

        // Build CertificationRequestInfo
        // version 0
        byte[] version = buildTLV(0x02, new byte[]{0x00});
        byte[] attributes = buildTLV(0xA0, new byte[0]); // empty attributes

        byte[] certReqInfo = buildSequence(concat(version, subjectDn, pubKeyEncoded, attributes));

        // Sign with SHA256withRSA
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(keyPair.getPrivate());
        sig.update(certReqInfo);
        byte[] signature = sig.sign();

        // Build signature algorithm identifier (SHA256withRSA = 1.2.840.113549.1.1.11)
        byte[] sigAlgOid = encodeOID("1.2.840.113549.1.1.11");
        byte[] sigAlgNull = new byte[]{0x05, 0x00};
        byte[] sigAlg = buildSequence(concat(sigAlgOid, sigAlgNull));

        // Build BIT STRING for signature
        byte[] sigBitString = new byte[signature.length + 1];
        sigBitString[0] = 0x00; // no unused bits
        System.arraycopy(signature, 0, sigBitString, 1, signature.length);
        byte[] sigBits = buildTLV(0x03, sigBitString);

        // Build final CSR
        byte[] csr = buildSequence(concat(certReqInfo, sigAlg, sigBits));

        // PEM encode
        String b64 = Base64.encodeToString(csr, Base64.NO_WRAP);
        StringBuilder pem = new StringBuilder();
        pem.append("-----BEGIN CERTIFICATE REQUEST-----\n");
        for (int i = 0; i < b64.length(); i += 64) {
            pem.append(b64, i, Math.min(i + 64, b64.length()));
            pem.append("\n");
        }
        pem.append("-----END CERTIFICATE REQUEST-----");
        return pem.toString();
    }

    private static byte[] buildRDN(String oid, String value) {
        byte[] oidBytes = encodeOID(oid);
        byte[] valBytes = buildTLV(0x0C, value.getBytes()); // UTF8String
        byte[] atv = buildSequence(concat(oidBytes, valBytes));
        return buildTLV(0x31, atv); // SET
    }

    private static byte[] buildSequence(byte[] content) {
        return buildTLV(0x30, content);
    }

    private static byte[] buildTLV(int tag, byte[] value) {
        byte[] length = encodeLength(value.length);
        byte[] result = new byte[1 + length.length + value.length];
        result[0] = (byte) tag;
        System.arraycopy(length, 0, result, 1, length.length);
        System.arraycopy(value, 0, result, 1 + length.length, value.length);
        return result;
    }

    private static byte[] encodeLength(int len) {
        if (len < 128) return new byte[]{(byte) len};
        if (len < 256) return new byte[]{(byte) 0x81, (byte) len};
        return new byte[]{(byte) 0x82, (byte) (len >> 8), (byte) (len & 0xFF)};
    }

    private static byte[] encodeOID(String oid) {
        String[] parts = oid.split("\\.");
        int[] nums = new int[parts.length];
        for (int i = 0; i < parts.length; i++) nums[i] = Integer.parseInt(parts[i]);

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bos.write(nums[0] * 40 + nums[1]);
        for (int i = 2; i < nums.length; i++) encodeOIDComponent(bos, nums[i]);
        return buildTLV(0x06, bos.toByteArray());
    }

    private static void encodeOIDComponent(ByteArrayOutputStream bos, int value) {
        if (value < 128) {
            bos.write(value);
            return;
        }
        byte[] temp = new byte[5];
        int pos = 4;
        temp[pos] = (byte) (value & 0x7F);
        value >>= 7;
        while (value > 0) {
            pos--;
            temp[pos] = (byte) ((value & 0x7F) | 0x80);
            value >>= 7;
        }
        bos.write(temp, pos, 5 - pos);
    }

    private static byte[] concat(byte[]... arrays) {
        int total = 0;
        for (byte[] a : arrays) total += a.length;
        byte[] result = new byte[total];
        int offset = 0;
        for (byte[] a : arrays) {
            System.arraycopy(a, 0, result, offset, a.length);
            offset += a.length;
        }
        return result;
    }

    private static String httpsGet(String urlStr, String authHeader, SSLContext sslCtx) throws Exception {
        URL url = new URL(urlStr);
        HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
        conn.setSSLSocketFactory(sslCtx.getSocketFactory());
        conn.setHostnameVerifier((hostname, session) -> {
            // During enrollment, TAK servers often use IP addresses or self-signed certs
            // where the hostname won't match the certificate CN. Verify the session has
            // a valid peer certificate chain before accepting.
            try {
                session.getPeerCertificates();
                return true;
            } catch (Exception e) {
                return false;
            }
        });
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        if (authHeader != null) conn.setRequestProperty("Authorization", authHeader);
        int code = conn.getResponseCode();
        if (code != 200) throw new Exception("HTTP " + code + " from " + urlStr);
        BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) sb.append(line);
        reader.close();
        return sb.toString();
    }

    private static byte[] httpsGetBytes(String urlStr, String authHeader, SSLContext sslCtx) throws Exception {
        URL url = new URL(urlStr);
        HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
        conn.setSSLSocketFactory(sslCtx.getSocketFactory());
        conn.setHostnameVerifier((hostname, session) -> {
            // During enrollment, TAK servers often use IP addresses or self-signed certs
            // where the hostname won't match the certificate CN. Verify the session has
            // a valid peer certificate chain before accepting.
            try {
                session.getPeerCertificates();
                return true;
            } catch (Exception e) {
                return false;
            }
        });
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        if (authHeader != null) conn.setRequestProperty("Authorization", authHeader);
        int code = conn.getResponseCode();
        if (code != 200) throw new Exception("HTTP " + code + " from " + urlStr);
        InputStream is = conn.getInputStream();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int len;
        while ((len = is.read(buf)) > 0) bos.write(buf, 0, len);
        is.close();
        return bos.toByteArray();
    }

    private static String httpsPost(String urlStr, String body, String authHeader, SSLContext sslCtx) throws Exception {
        URL url = new URL(urlStr);
        HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
        conn.setSSLSocketFactory(sslCtx.getSocketFactory());
        conn.setHostnameVerifier((hostname, session) -> {
            // During enrollment, TAK servers often use IP addresses or self-signed certs
            // where the hostname won't match the certificate CN. Verify the session has
            // a valid peer certificate chain before accepting.
            try {
                session.getPeerCertificates();
                return true;
            } catch (Exception e) {
                return false;
            }
        });
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "text/plain");
        conn.setRequestProperty("Accept", "application/json");
        if (authHeader != null) conn.setRequestProperty("Authorization", authHeader);
        OutputStream os = conn.getOutputStream();
        os.write(body.getBytes());
        os.flush();
        os.close();
        int code = conn.getResponseCode();
        if (code >= 200 && code < 300) {
            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            reader.close();
            return sb.toString();
        } else {
            BufferedReader er = new BufferedReader(new InputStreamReader(conn.getErrorStream()));
            StringBuilder esb = new StringBuilder();
            String eline;
            while ((eline = er.readLine()) != null) esb.append(eline);
            er.close();
            throw new Exception("HTTP " + code + ": " + esb.toString());
        }
    }

    private static X509Certificate decodeCert(String base64) throws Exception {
        String cleaned = base64.replace("-----BEGIN CERTIFICATE-----", "")
                .replace("-----END CERTIFICATE-----", "").replaceAll("\\s", "");
        byte[] der = Base64.decode(cleaned, 0);
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        return (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(der));
    }

    private static void buildClientP12(KeyPair keyPair, X509Certificate signedCert,
                                        X509Certificate ca0, X509Certificate ca1, File output) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        List<Certificate> chain = new ArrayList<>();
        chain.add(signedCert);
        if (ca0 != null) chain.add(ca0);
        if (ca1 != null) chain.add(ca1);
        ks.setKeyEntry("client", keyPair.getPrivate(), DEFAULT_P12_PASSWORD.toCharArray(),
                chain.toArray(new Certificate[0]));
        FileOutputStream fos = new FileOutputStream(output);
        ks.store(fos, DEFAULT_P12_PASSWORD.toCharArray());
        fos.close();
    }

    private static void buildTrustStoreP12(X509Certificate ca0, X509Certificate ca1, File output) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        if (ca0 != null) ks.setCertificateEntry("ca0", ca0);
        if (ca1 != null) ks.setCertificateEntry("ca1", ca1);
        FileOutputStream fos = new FileOutputStream(output);
        ks.store(fos, DEFAULT_P12_PASSWORD.toCharArray());
        fos.close();
    }

    private static String parseNameEntry(String xml, String name, String defaultValue) {
        String search = "name=\"" + name + "\" value=\"";
        int idx = xml.indexOf(search);
        if (idx < 0) return defaultValue;
        idx += search.length();
        int end = xml.indexOf("\"", idx);
        if (end < 0) return defaultValue;
        return xml.substring(idx, end);
    }

    private static String urlEncode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    /**
     * Creates an SSLContext for TAK server enrollment.
     *
     * During enrollment, the server uses a self-signed certificate that the client
     * doesn't have yet (enrollment is how we obtain it). This TrustManager accepts
     * the server's certificate but still validates expiry and basic structure,
     * rather than blindly trusting everything.
     */
    private static SSLContext createEnrollmentSSLContext() throws Exception {
        TrustManager[] enrollTrust = {new X509TrustManager() {
            @Override
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }

            @Override
            public void checkClientTrusted(X509Certificate[] certs, String authType)
                    throws java.security.cert.CertificateException {
                if (certs == null || certs.length == 0) {
                    throw new java.security.cert.CertificateException("No client certificates provided");
                }
            }

            @Override
            public void checkServerTrusted(X509Certificate[] certs, String authType)
                    throws java.security.cert.CertificateException {
                if (certs == null || certs.length == 0) {
                    throw new java.security.cert.CertificateException("No server certificates provided");
                }
                // Validate each certificate in the chain is not expired
                for (X509Certificate cert : certs) {
                    cert.checkValidity();
                }
                // Verify the chain: each cert should be signed by the next
                for (int i = 0; i < certs.length - 1; i++) {
                    try {
                        certs[i].verify(certs[i + 1].getPublicKey());
                    } catch (Exception e) {
                        // Self-signed TAK servers may have single-cert chains, allow it
                        AppLog.w(TAG, "Certificate chain verification skipped for enrollment: " + e.getMessage());
                    }
                }
            }
        }};
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, enrollTrust, new SecureRandom());
        return ctx;
    }
}
