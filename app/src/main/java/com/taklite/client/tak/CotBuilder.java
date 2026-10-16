package com.taklite.client.tak;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;

public class CotBuilder {
    private static final SimpleDateFormat COT_DATE_FORMAT;
    private static final String COT_VERSION = "2.0";
    private static final String PLI_TYPE = "a-f-G-U-C";
    /** `how` of a PLI with a real fix: machine, GPS. */
    private static final String PLI_HOW = "m-g";
    /**
     * `how` of a PLI WITHOUT a fix: "garbage in, garbage out". The CoT code that says the point
     * came from nowhere. A receiver that reads `how` can see that this point is not a fix.
     */
    private static final String PLI_HOW_NO_FIX = "h-g-i-g-o";
    /**
     * The CoT value for "not known" in `hae`, `ce` and `le`. A receiver must not use a number
     * that carries this value. It goes on the wire as the literal digits, the same as ATAK sends.
     */
    public static final String UNKNOWN = "9999999";
    private static final long STALE_DURATION_MS = 300000; // 5 minutes — matches ATAK default

    // Drone (air) track: friendly-Air-Military-rotorcraftH-unmanned(Q). ATAK/taklite CotParser
    // detects the air domain ("-A-") as a drone. 60 seconds (chosen from flight experience):
    // long enough that a momentary GPS-lock loss does not blink the aircraft off TAK (15s did
    // — it staled out almost as fast as the telemetry hiccup itself), short enough that a
    // landed or disconnected aircraft leaves the picture inside a minute. 2 minutes kept a
    // ghost aircraft on other screens too long after shutdown.
    private static final String DRONE_TYPE = "a-f-A-M-H-Q";
    private static final long DRONE_STALE_DURATION_MS = 60000; // 60 seconds

    // Pilot-dropped 2525 markers — see the note in buildMarker().
    //
    // 72 HOURS. This is how long a dropped marker survives on OTHER
    // people's screens before their client drops it, so it is an operational choice rather than
    // a technical one: it has to outlast a multi-day callout without leaving last week's markers
    // cluttering the picture. Raised from 14 hours, which did not survive an overnight.
    //
    // Re-sending a marker refreshes its stale time on every client that receives it, so a pin
    // that is still wanted can be kept alive indefinitely from the markers list.
    private static final long MARKER_STALE_DURATION_MS = 72 * 60 * 60 * 1000L; // 72 hours

    // Sensor point of interest — the ground point the drone camera is looking at.
    //
    // ⚠ THE STALE TIME IS THE ONLY THING THIS APPLICATION DOES TO MAKE THE SPI GO AWAY, AND
    // THAT IS A DESIGN DECISION. A `t-x-d-d` delete, sent when the camera
    // stopped having a look-point, was BUILT and then REMOVED: the controller
    // does not tell the server to delete anything, and how long a point survives after it
    // stops being refreshed is the receiving client's retention policy, not the aircraft's.
    // An aircraft that reaches into every EUD on the net and removes a map item is a much
    // larger hammer than the problem, and it is not ours to swing.
    //
    // What this class therefore gives a receiver is exactly two things, and both are pinned by
    // SensorPointRetentionTest: a SHORT stale window, and NOTHING that asks for the point to
    // be kept. There is no <archived/> here and there must never be one — see
    // CotParser.isPersistentType, which is this application's own reader of that same flag.
    // If a client still shows the point after it expires, that is the client's retention and
    // it is settled there, not here.
    private static final String SENSOR_POINT_TYPE = "b-m-p-s-p-i";
    private static final long SENSOR_POINT_STALE_MS = 15000; // 15s — clears if the feed stops

    /**
     * AIRFRAME IDENTITY BROADCAST IN EVERY DRONE CoT.
     *
     * These once carried another vendor's airframe values, inherited from the code this class
     * was ported from and never changed. The effect was not cosmetic: an Autel EVO II was
     * telling every client on the channel it was a different aircraft, so any teammate or log
     * reading the airframe type got the wrong aircraft and the wrong camera.
     *
     * <p>Named constants rather than literals at the three append sites, for two reasons: they
     * are one identity expressed in three attributes and must move together, and this class is
     * otherwise app-agnostic (see the takvPlatform note below). Anything app-specific in here
     * should be obvious rather than buried in a StringBuilder.
     *
     * <p>⚠ These strings go to OTHER PEOPLE'S CLIENTS, which may match on them to decide how to
     * draw the aircraft. If the drone stops rendering correctly in a TAK client after this
     * change, suspect these first — the receiving side may recognise a fixed set of vendor tags.
     */
    private static final String VEHICLE_TYPE_TAG = "_AUTEL_";
    private static final String VEHICLE_TYPE = "AUTEL";
    private static final String SENSOR_MODEL = "EVO2";

    static {
        COT_DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        COT_DATE_FORMAT.setTimeZone(TimeZone.getTimeZone("UTC"));
    }

    /**
     * @param takvPlatform the client-identity string that becomes {@code <takv platform="...">}
     *   — what a TAK server's "Connected Users" panel shows as the client type. Pass whatever
     *   the calling app actually is; this class stays app-agnostic on purpose (another app
     *   built on it must not be labeled as this one).
     * @param takvDevice   the {@code device} attribute — real hardware identity (and whatever
     *   else the caller wants folded in, e.g. the current callsign) helps a teammate tell two
     *   otherwise-identical installs apart in the server's connected-users list.
     * @param takvOs       the {@code os} attribute.
     * @param takvVersion  the {@code version} attribute — the caller's real app version, not a
     *   string owned by this shared class (which has no version of its own to report).
     */
    /** Back-compat overload: no video advertised, accuracy not known. */
    public static String buildPLI(String uid, String callsign, String team, String role,
                                   double lat, double lon, double alt,
                                   double bearing, double speed, int battery,
                                   String takvPlatform, String takvDevice,
                                   String takvOs, String takvVersion) {
        return buildPLI(uid, callsign, team, role, lat, lon, alt, bearing, speed, battery,
                takvPlatform, takvDevice, takvOs, takvVersion, null);
    }

    /**
     * @param videoUrl RTSP url to advertise on THIS marker, or null to omit.
     *
     * The video rides on the OPERATOR marker, not the aircraft (design decision). The stream
     * is a screen capture of the controller, and it keeps running when the aircraft is down — but
     * the drone PLI stops the moment there is no GPS fix, so a video advertised there became
     * unreachable in exactly the case the screen-capture design exists to cover.
     *
     * Back-compat overload: accuracy not known, thus `ce` goes out as {@link #UNKNOWN}.
     */
    public static String buildPLI(String uid, String callsign, String team, String role,
                                   double lat, double lon, double alt,
                                   double bearing, double speed, int battery,
                                   String takvPlatform, String takvDevice,
                                   String takvOs, String takvVersion, String videoUrl) {
        return buildPLI(uid, callsign, team, role, true, lat, lon, alt, 0, bearing, speed,
                battery, takvPlatform, takvDevice, takvOs, takvVersion, videoUrl);
    }

    /**
     * A PLI for a client that has NO position fix.
     *
     * The client must still be in the contact list of the other clients. Nobody can send a marker
     * to a client that is not in the list. Thus the message goes out with
     * the callsign, the endpoint, the team, the battery, the takv block and the video url — all
     * the fields that make the client reachable — and a point that says "not known":
     * <ul>
     *   <li>{@code how="h-g-i-g-o"} in place of {@code m-g}: the point is not a GPS fix.</li>
     *   <li>{@code lat="0.0" lon="0.0"} with {@code hae}, {@code ce} and {@code le} at
     *       {@link #UNKNOWN}.</li>
     *   <li>No {@code <track>} and no {@code <precisionlocation>}: there is no course, no speed
     *       and no GPS source to report.</li>
     * </ul>
     * A PLI with a real fix carries {@code m-g}, the true {@code ce} of the fix and the GPS
     * source. A receiver can tell the two apart from any one of those three fields.
     *
     * ⚠ The receive side must agree. {@code CotParser.parse} keeps a 0,0 event only when it
     * carries {@code <takv>} or a contact endpoint — see the note there.
     */
    public static String buildPLINoFix(String uid, String callsign, String team, String role,
                                        int battery, String takvPlatform, String takvDevice,
                                        String takvOs, String takvVersion, String videoUrl) {
        return buildPLI(uid, callsign, team, role, false, 0, 0, 0, 0, 0, 0, battery,
                takvPlatform, takvDevice, takvOs, takvVersion, videoUrl);
    }

    /**
     * @param positionKnown true for a real fix. False makes the "position not known" form — see
     *   {@link #buildPLINoFix}; the lat/lon/alt/ce/bearing/speed arguments are then ignored.
     * @param ce circular error of the fix in metres, or 0 (or less) when the caller does not know
     *   it. An unknown accuracy goes out as {@link #UNKNOWN}. An earlier version sent
     *   {@link #UNKNOWN} whatever the receiver reported, thus the field told a teammate nothing.
     */
    public static String buildPLI(String uid, String callsign, String team, String role,
                                   boolean positionKnown,
                                   double lat, double lon, double alt, double ce,
                                   double bearing, double speed, int battery,
                                   String takvPlatform, String takvDevice,
                                   String takvOs, String takvVersion, String videoUrl) {
        long now = System.currentTimeMillis();
        String time = formatTime(now);
        String start = time;
        String stale = formatTime(now + STALE_DURATION_MS);

        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        sb.append("<event version=\"").append(COT_VERSION).append("\"");
        sb.append(" type=\"").append(PLI_TYPE).append("\"");
        sb.append(" uid=\"").append(escapeXml(uid)).append("\"");
        sb.append(" how=\"").append(positionKnown ? PLI_HOW : PLI_HOW_NO_FIX).append("\"");
        sb.append(" time=\"").append(time).append("\"");
        sb.append(" start=\"").append(start).append("\"");
        sb.append(" stale=\"").append(stale).append("\"");
        sb.append(">");
        if (positionKnown) {
            sb.append("<point lat=\"").append(lat).append("\"");
            sb.append(" lon=\"").append(lon).append("\"");
            sb.append(" hae=\"").append(alt).append("\"");
            // One decimal. The value arrives as a float from the receiver; as a raw double it
            // printed its float residue, 4.567 as "4.566999912261963".
            sb.append(" ce=\"").append(ce > 0 ? String.format(Locale.US, "%.1f", ce) : UNKNOWN)
              .append("\"");
            sb.append(" le=\"").append(UNKNOWN).append("\" />");
        } else {
            sb.append("<point lat=\"0.0\" lon=\"0.0\"");
            sb.append(" hae=\"").append(UNKNOWN).append("\"");
            sb.append(" ce=\"").append(UNKNOWN).append("\"");
            sb.append(" le=\"").append(UNKNOWN).append("\" />");
        }
        sb.append("<detail>");
        sb.append("<contact callsign=\"").append(escapeXml(callsign)).append("\" endpoint=\"*:-1:stcp\" />");
        sb.append("<__group name=\"").append(escapeXml(team)).append("\"");
        sb.append(" role=\"").append(escapeXml(role)).append("\" />");
        sb.append("<status battery=\"").append(battery).append("\" />");
        if (positionKnown) {
            sb.append("<track course=\"").append(bearing).append("\"");
            sb.append(" speed=\"").append(speed).append("\" />");
            sb.append("<precisionlocation geopointsrc=\"GPS\" altsrc=\"GPS\" />");
        }
        sb.append("<takv device=\"").append(escapeXml(takvDevice)).append("\"");
        sb.append(" os=\"").append(escapeXml(takvOs)).append("\"");
        sb.append(" platform=\"").append(escapeXml(takvPlatform)).append("\"");
        sb.append(" version=\"").append(escapeXml(takvVersion)).append("\" />");
        sb.append("<uid Droid=\"").append(escapeXml(callsign)).append("\" />");
        appendVideo(sb, videoUrl, callsign, null);
        sb.append("</detail>");
        sb.append("</event>");
        return sb.toString();
    }

    /**
     * Build a CoT position report for the AIRCRAFT (drone) as a distinct air track.
     * This is separate from the operator's PLI: it has its own uid, an air-domain type,
     * and a sensor/__video detail block so TAK clients can recognize the drone and its feed.
     *
     * @param uid       stable per-drone uid (e.g. "TAKPilot2-DRONE-<serial>")
     * @param callsign  drone callsign shown on TAK
     * @param lat,lon   aircraft position (deg)
     * @param hae       height above ellipsoid (m)
     * @param heading   true heading / course (deg)
     * @param speed     horizontal ground speed (m/s)
     * @param battery   aircraft battery percent (0-100)
     * @param videoUrl  optional RTSP/stream url to advertise (null/empty to omit)
     */
    public static String buildDronePLI(String uid, String callsign,
                                       double lat, double lon, double hae,
                                       double heading, double speed, int battery,
                                       String videoUrl, String spiUid,
                                       double sensorFov, double sensorVfov, double sensorAzimuth,
                                       double sensorElevation, double sensorRange, double northRef,
                                       double gimbalRoll, double gimbalPitch, double gimbalYaw,
                                       boolean isFlying, int flightTimeSec,
                                       int batteryMaxMah, int batteryRemainMah, double voltage,
                                       String operatorUid) {
        return buildDronePLI(uid, callsign, lat, lon, hae, heading, speed, battery, videoUrl,
                spiUid, sensorFov, sensorVfov, sensorAzimuth, sensorElevation, sensorRange,
                northRef, gimbalRoll, gimbalPitch, gimbalYaw, isFlying, flightTimeSec,
                batteryMaxMah, batteryRemainMah, voltage, operatorUid, -1, -1);
    }

    /**
     * {@link #buildDronePLI} with the receiver's own accuracy (metres, 1 sigma; <= 0 = unknown).
     * The point used to claim ce="0.0" le="0.0" — a PERFECT position — on every message, while
     * the receiver reports 0.5-6 m.
     */
    public static String buildDronePLI(String uid, String callsign,
                                       double lat, double lon, double hae,
                                       double heading, double speed, int battery,
                                       String videoUrl, String spiUid,
                                       double sensorFov, double sensorVfov, double sensorAzimuth,
                                       double sensorElevation, double sensorRange, double northRef,
                                       double gimbalRoll, double gimbalPitch, double gimbalYaw,
                                       boolean isFlying, int flightTimeSec,
                                       int batteryMaxMah, int batteryRemainMah, double voltage,
                                       String operatorUid, double ce, double le) {
        long now = System.currentTimeMillis();
        String time = formatTime(now);
        String stale = formatTime(now + DRONE_STALE_DURATION_MS);

        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        sb.append("<event version=\"").append(COT_VERSION).append("\"");
        sb.append(" type=\"").append(DRONE_TYPE).append("\"");
        sb.append(" uid=\"").append(escapeXml(uid)).append("\"");
        sb.append(" how=\"m-g\"");
        sb.append(" time=\"").append(time).append("\"");
        sb.append(" start=\"").append(time).append("\"");
        sb.append(" stale=\"").append(stale).append("\"");
        sb.append(">");
        sb.append("<point lat=\"").append(lat).append("\"");
        sb.append(" lon=\"").append(lon).append("\"");
        sb.append(" hae=\"").append(hae).append("\"");
        sb.append(" ce=\"").append(ce > 0 ? String.format(Locale.US, "%.1f", ce) : UNKNOWN).append("\"");
        sb.append(" le=\"").append(le > 0 ? String.format(Locale.US, "%.1f", le) : UNKNOWN).append("\" />");
        sb.append("<detail>");
        sb.append("<contact callsign=\"").append(escapeXml(callsign)).append("\" endpoint=\"*:-1:stcp\" />");
        sb.append("<_uastool extendedCot=\"true\" activeRoute=\"false\" />");
        sb.append("<track course=\"").append(heading).append("\"");
        sb.append(" slope=\"0.0\" speed=\"").append(speed).append("\" />");
        if (sensorFov > 0 && sensorRange > 0) {
            sb.append("<sensor");
            sb.append(" elevation=\"").append(sensorElevation).append("\"");
            sb.append(" vfov=\"").append(sensorVfov).append("\"");
            sb.append(" north=\"").append(northRef).append("\"");
            sb.append(" roll=\"0\"");
            sb.append(" range=\"").append((int) Math.round(sensorRange)).append("\"");
            sb.append(" azimuth=\"").append(sensorAzimuth).append("\"");
            sb.append(" model=\"").append(SENSOR_MODEL).append("\"");
            sb.append(" fov=\"").append(sensorFov).append("\"");
            sb.append(" type=\"r-e\"");
            sb.append(" version=\"0.6\" />");
        }
        sb.append("<spatial><attitude");
        sb.append(" roll=\"").append(gimbalRoll).append("\"");
        sb.append(" pitch=\"").append(gimbalPitch).append("\"");
        sb.append(" yaw=\"").append(gimbalYaw).append("\"");
        sb.append(" /><spin roll=\"0.0\" pitch=\"0.0\" yaw=\"0.0\" /></spatial>");
        sb.append("<vehicle goHomeBatteryPercent=\"20\" hal=\"0.0\"");
        sb.append(" flightTimeRemaining=\"0\"");
        sb.append(" typeTag=\"").append(VEHICLE_TYPE_TAG).append("\"");
        sb.append(" batteryRemainingCapacity=\"").append(batteryRemainMah).append("\"");
        sb.append(" isFlying=\"").append(isFlying).append("\"");
        sb.append(" flightTime=\"").append(flightTimeSec).append("\"");
        sb.append(" type=\"").append(VEHICLE_TYPE).append("\"");
        sb.append(" batteryMaxCapacity=\"").append(batteryMaxMah).append("\"");
        sb.append(" voltage=\"").append(String.format("%.2f", voltage)).append("\" />");
        sb.append("<_radio rssi=\"100\" gps=\"true\" />");
        sb.append("<waypointCollection />");
        if (operatorUid != null && !operatorUid.isEmpty()) {
            sb.append("<_route sender=\"").append(escapeXml(operatorUid)).append("\" />");
        }
        sb.append("<commandedData climbRate=\"0.0\" />");
        appendVideo(sb, videoUrl, callsign, spiUid);
        if (operatorUid != null && !operatorUid.isEmpty()) {
            sb.append("<link uid=\"").append(escapeXml(operatorUid)).append("\"");
            sb.append(" type=\"a-f-G-U-C\" relation=\"p-p\" />");
        }
        sb.append("<status battery=\"").append(battery).append("\" />");
        sb.append("</detail>");
        sb.append("</event>");
        return sb.toString();
    }

    /**
     * Sensor point of interest — the ground point the drone camera is looking at.
     * Stable uid so it updates the same marker continuously (a live camera crosshair).
     * Linked back to the drone uid so ATAK can associate it with the aircraft.
     *
     * @param uid        stable per-drone sensor-point uid (e.g. "<drone-uid>-SPI")
     * @param droneUid   the drone's CoT uid, for the association link
     * @param callsign   label shown on TAK
     * @param lat,lon    ground point (deg)
     * @param rangeM     GROUND (horizontal) range to the point (m), for the remarks
     */
    public static String buildSensorPoint(String uid, String droneUid, String callsign,
                                          double lat, double lon, double rangeM) {
        return buildSensorPoint(uid, droneUid, callsign, lat, lon, rangeM, Double.NaN);
    }

    /**
     * {@link #buildSensorPoint} with the point's height above the ELLIPSOID, or NaN when it is
     * not known. The SPI used to go out as hae="0" labelled altsrc="DTED0" — a fabricated
     * height presented as terrain data, ~190 m low at the test site. Unknown now says so,
     * and a receiver puts the point on its own terrain.
     */
    public static String buildSensorPoint(String uid, String droneUid, String callsign,
                                          double lat, double lon, double rangeM, double haeM) {
        boolean haeKnown = !Double.isNaN(haeM) && !Double.isInfinite(haeM);
        long now = System.currentTimeMillis();
        String time = formatTime(now);
        String stale = formatTime(now + SENSOR_POINT_STALE_MS);

        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        sb.append("<event version=\"").append(COT_VERSION).append("\"");
        sb.append(" type=\"").append(SENSOR_POINT_TYPE).append("\"");
        sb.append(" uid=\"").append(escapeXml(uid)).append("\"");
        sb.append(" how=\"m-g\"");
        sb.append(" time=\"").append(time).append("\"");
        sb.append(" start=\"").append(time).append("\"");
        sb.append(" stale=\"").append(stale).append("\"");
        sb.append(">");
        sb.append("<point lat=\"").append(lat).append("\"");
        sb.append(" lon=\"").append(lon).append("\"");
        sb.append(" hae=\"").append(haeKnown ? String.format(Locale.US, "%.1f", haeM) : UNKNOWN)
                .append("\" ce=\"9999999\" le=\"9999999\" />");
        sb.append("<detail>");
        sb.append("<contact callsign=\"").append(escapeXml(callsign)).append("\" />");
        sb.append("<link uid=\"").append(escapeXml(droneUid)).append("\"");
        sb.append(" type=\"").append(DRONE_TYPE).append("\" relation=\"p-p\" />");
        sb.append("<remarks>Camera point, ground range ")
                .append(Math.round(rangeM)).append("m</remarks>");
        sb.append("<precisionlocation geopointsrc=\"CALC\"")
                .append(haeKnown ? " altsrc=\"DTED\"" : "").append(" />");
        sb.append("</detail>");
        sb.append("</event>");
        return sb.toString();
    }

    /**
     * Camera footprint — the polygon on the ground the camera currently sees.
     * A u-d-f free-form drawing (ATAK polygon) whose vertices are the projected FOV
     * corners. Stable uid so it updates the same shape continuously.
     *
     * @param uid      stable footprint uid (e.g. "<drone-uid>-FOV")
     * @param callsign label
     * @param corners  ground corners as {lat, lon} pairs (clockwise); needs >= 3
     */
    public static String buildFootprintPolygon(String uid, String callsign,
                                               double[][] corners) {
        long now = System.currentTimeMillis();
        String time = formatTime(now);
        String stale = formatTime(now + SENSOR_POINT_STALE_MS);

        // Centroid for the <point>.
        double clat = 0, clon = 0;
        for (double[] c : corners) { clat += c[0]; clon += c[1]; }
        clat /= corners.length; clon /= corners.length;

        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        sb.append("<event version=\"").append(COT_VERSION).append("\"");
        sb.append(" type=\"u-d-f\"");
        sb.append(" uid=\"").append(escapeXml(uid)).append("\"");
        sb.append(" how=\"m-g\"");
        sb.append(" time=\"").append(time).append("\"");
        sb.append(" start=\"").append(time).append("\"");
        sb.append(" stale=\"").append(stale).append("\"");
        sb.append(">");
        sb.append("<point lat=\"").append(clat).append("\" lon=\"").append(clon).append("\"");
        sb.append(" hae=\"0\" ce=\"9999999\" le=\"9999999\" />");
        sb.append("<detail>");
        sb.append("<contact callsign=\"").append(escapeXml(callsign)).append("\" />");
        // Polygon vertices (closed: repeat first at the end).
        for (double[] c : corners) {
            sb.append("<link point=\"").append(c[0]).append(",").append(c[1]).append("\" />");
        }
        sb.append("<link point=\"").append(corners[0][0]).append(",").append(corners[0][1]).append("\" />");
        sb.append("<strokeColor value=\"-1\" />");        // white outline
        sb.append("<strokeWeight value=\"3\" />");
        sb.append("<fillColor value=\"533200383\" />");   // translucent cyan fill (0x1F00BFFF)
        sb.append("<labels_on value=\"false\" />");
        sb.append("<remarks>Camera footprint</remarks>");
        sb.append("</detail>");
        sb.append("</event>");
        return sb.toString();
    }

    public static String buildAlert(String uid, String callsign, String team, String role,
                                     double lat, double lon, double alt, String alertType) {
        long now = System.currentTimeMillis();
        String time = formatTime(now);
        String stale = formatTime(now + 300000);
        String alertId = "alert-" + UUID.randomUUID().toString().substring(0, 8);

        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        sb.append("<event version=\"").append(COT_VERSION).append("\"");
        sb.append(" type=\"b-a-o-tbl\"");
        sb.append(" uid=\"").append(escapeXml(alertId)).append("\"");
        sb.append(" how=\"m-a\"");
        sb.append(" time=\"").append(time).append("\"");
        sb.append(" start=\"").append(time).append("\"");
        sb.append(" stale=\"").append(stale).append("\"");
        sb.append(">");
        sb.append("<point lat=\"").append(lat).append("\"");
        sb.append(" lon=\"").append(lon).append("\"");
        sb.append(" hae=\"").append(alt).append("\"");
        sb.append(" ce=\"9999999\" le=\"9999999\" />");
        sb.append("<detail>");
        sb.append("<contact callsign=\"").append(escapeXml(callsign)).append("\" endpoint=\"*:-1:stcp\" />");
        sb.append("<emergency type=\"").append(escapeXml(alertType)).append("\">");
        sb.append(escapeXml(callsign));
        sb.append("</emergency>");
        sb.append("<link uid=\"").append(escapeXml(uid)).append("\"");
        sb.append(" type=\"a-f-G-U-C\" relation=\"p-p\" />");
        sb.append("<remarks source=\"").append(escapeXml(uid)).append("\">");
        sb.append(escapeXml(callsign)).append(" has activated ").append(escapeXml(alertType));
        sb.append("</remarks>");
        sb.append("</detail>");
        sb.append("</event>");
        return sb.toString();
    }

    public static String buildAlertCancel(String uid, String callsign, String alertId) {
        long now = System.currentTimeMillis();
        String time = formatTime(now);
        String stale = formatTime(now + STALE_DURATION_MS);

        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        sb.append("<event version=\"").append(COT_VERSION).append("\"");
        sb.append(" type=\"b-a-o-can\"");
        sb.append(" uid=\"").append(escapeXml(alertId)).append("\"");
        sb.append(" how=\"m-a\"");
        sb.append(" time=\"").append(time).append("\"");
        sb.append(" start=\"").append(time).append("\"");
        sb.append(" stale=\"").append(stale).append("\"");
        sb.append(">");
        sb.append("<point lat=\"0\" lon=\"0\" hae=\"0\" ce=\"9999999\" le=\"9999999\" />");
        sb.append("<detail>");
        sb.append("<contact callsign=\"").append(escapeXml(callsign)).append("\" endpoint=\"*:-1:stcp\" />");
        sb.append("<emergency cancel=\"true\">");
        sb.append(escapeXml(callsign));
        sb.append("</emergency>");
        sb.append("<link uid=\"").append(escapeXml(uid)).append("\"");
        sb.append(" type=\"a-f-G-U-C\" relation=\"p-p\" />");
        sb.append("</detail>");
        sb.append("</event>");
        return sb.toString();
    }

    public static String buildMarker(String senderUid, String senderCallsign, String markerUid,
                                      String affiliation, double lat, double lon, double alt,
                                      String name, String remarks) {
        return buildMarker(senderUid, senderCallsign, markerUid, affiliation, lat, lon, alt, name, remarks, null);
    }

    /**
     * Build a marker CoT. If {@code missionName} is non-null, a
     * {@code <marti><dest mission=".."/></marti>} tag is added so the TAK Server routes it ONLY
     * to that mission/feed's subscribers instead of broadcasting server-wide.
     */
    public static String buildMarker(String senderUid, String senderCallsign, String markerUid,
                                      String affiliation, double lat, double lon, double alt,
                                      String name, String remarks, String missionName) {
        return buildMarkerWithType(senderUid, senderCallsign, markerUid,
                cotTypeForAffiliation(affiliation), lat, lon, alt, name, remarks, missionName,
                affiliation);
    }

    /**
     * The CoT type this application emits for one of its four affiliations.
     *
     * Every marker THIS app places is a bare affiliation-plus-domain type. The trailing
     * qualifiers other clients use (…-G-E-V and the like) say equipment or platform, which a
     * dropped point is not.
     */
    public static String cotTypeForAffiliation(String affiliation) {
        switch (affiliation == null ? "" : affiliation.toLowerCase()) {
            case "hostile":  return "a-h-G";
            case "unknown":  return "a-u-G";
            case "neutral":  return "a-n-G";
            case "friendly":
            default:         return "a-f-G";
        }
    }

    /**
     * Build a marker CoT under an EXPLICIT CoT type, rather than deriving one from an
     * affiliation.
     *
     * WHY THIS EXISTS: a marker this app RE-BROADCASTS was not necessarily made by this app.
     * A marker received from the team can be a bare {@code a-{f,h,n,u}-G} — which the
     * affiliation switch reproduces exactly — or a {@code b-m-p-*} marker point, which it does
     * NOT: every affiliation maps to an {@code a-*} type, so re-sending an ATAK waypoint
     * through the affiliation path would quietly turn it into a friendly ground marker on every
     * screen in the team. The type has to be carried, not re-derived.
     *
     * {@code affiliationForLog} is only for the log line; it has no effect on the XML.
     */
    public static String buildMarkerWithType(String senderUid, String senderCallsign,
                                      String markerUid, String cotType,
                                      double lat, double lon, double alt,
                                      String name, String remarks, String missionName,
                                      String affiliationForLog) {
        long now = System.currentTimeMillis();
        String time = formatTime(now);
        // The lifetime is MARKER_STALE_DURATION_MS. Its declaration above holds the figure and
        // the reason it was chosen; do not repeat the number here, because this comment already
        // outlived one change of it. The value is deliberately unrelated to
        // DRONE_STALE_DURATION_MS (a live track) and to SENSOR_POINT_STALE_MS — a static marker
        // and a moving aircraft want completely different lifetimes.
        String stale = formatTime(now + MARKER_STALE_DURATION_MS);
        String affiliation = affiliationForLog;

        String callsign = (name != null && !name.isEmpty()) ? name : affiliation;
        String remarksText = (remarks != null && !remarks.isEmpty()) ? remarks
                : "Dropped by " + senderCallsign;

        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        sb.append("<event version=\"").append(COT_VERSION).append("\"");
        sb.append(" type=\"").append(escapeXml(cotType)).append("\"");
        sb.append(" uid=\"").append(escapeXml(markerUid)).append("\"");
        sb.append(" how=\"h-g-i-g-o\"");
        sb.append(" time=\"").append(time).append("\"");
        sb.append(" start=\"").append(time).append("\"");
        sb.append(" stale=\"").append(stale).append("\"");
        sb.append(">");
        sb.append("<point lat=\"").append(lat).append("\"");
        sb.append(" lon=\"").append(lon).append("\"");
        sb.append(" hae=\"").append(alt).append("\"");
        sb.append(" ce=\"9999999\" le=\"9999999\" />");
        sb.append("<detail>");
        sb.append("<contact callsign=\"").append(escapeXml(callsign)).append("\" />");
        sb.append("<remarks source=\"").append(escapeXml(senderUid)).append("\">");
        sb.append(escapeXml(remarksText));
        sb.append("</remarks>");
        sb.append("<link uid=\"").append(escapeXml(senderUid)).append("\"");
        sb.append(" type=\"a-f-G-U-C\" relation=\"p-p\" />");
        sb.append("<precisionlocation geopointsrc=\"Human\" altsrc=\"DTED0\" />");
        // Scope to a mission/feed so the server routes it only to that feed's subscribers
        // (no server-wide broadcast). ATAK uses <marti><dest mission="..."/></marti>.
        if (missionName != null && !missionName.isEmpty()) {
            sb.append("<marti><dest mission=\"").append(escapeXml(missionName)).append("\" /></marti>");
        }
        sb.append("</detail>");
        sb.append("</event>");
        return sb.toString();
    }

    public static String generateUid() {
        return "TAKLite-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String formatTime(long millis) {
        synchronized (COT_DATE_FORMAT) {
            return COT_DATE_FORMAT.format(new Date(millis));
        }
    }

    /**
     * The video advertisement, in the shape TAK clients actually parse.
     *
     * <p>⚠ This used to emit {@code <__video sensor=".." url=".."/>} and nothing else. That is
     * self-consistent — our own {@link CotParser} reads exactly those two attributes — but no
     * client can build a player from it, so the marker arrived on ATAK/CloudTAK/TAKAware with no
     * play control and the stream never reached the video manager. Confirmed in flight: the
     * url WAS on the wire (246 of 702 operator PLIs carried it) and still nothing offered to
     * play it.
     *
     * <p>The shape below is the one CloudTAK's CoT library (dfpc-coe/node-CoT) requires: a nested
     * {@code ConnectionEntry} whose {@code uid} and {@code address} are BOTH mandatory. Optional
     * attributes are sent anyway, with ATAK's own defaults, because a client that reads them and
     * finds them missing falls back to values we did not choose.
     *
     * <p>The video uid is derived from the URL, so it is stable across restarts and IDENTICAL on
     * the aircraft and the operator marker. That is deliberate: it is one stream, and two markers
     * advertising it under one uid give a client one video entry referenced twice, not two
     * competing entries for the same feed. ⚠ A per-launch token in the path is NOT part of the
     * uid — see {@link #videoUidFor}.
     *
     * <p>⚠ {@code url} keeps whatever the caller passed, credentials included — that is how the
     * stream authenticates today and removing them would break playback on a server that needs
     * them. {@code address} is the bare host, so a client that builds only from ConnectionEntry
     * gets a clean address. If a feed needs auth and a client uses ConnectionEntry alone, this is
     * where that shows up.
     *
     * @param alias human-readable name for the feed; shown in a client's video manager.
     */
    private static void appendVideo(StringBuilder sb, String videoUrl, String alias, String spiUid) {
        if (videoUrl == null || videoUrl.isEmpty()) return;

        String videoUid = videoUidFor(videoUrl);
        String host = "";
        int port = -1;
        String path = "";
        String protocol = "raw";
        try {
            java.net.URI u = java.net.URI.create(videoUrl);
            if (u.getScheme() != null) protocol = u.getScheme();
            if (u.getHost() != null) host = u.getHost();
            port = u.getPort();
            if (u.getPath() != null) path = u.getPath();
            // ⚠ SRT PUTS THE WHOLE QUERY STRING IN `path`, LEADING "?" INCLUDED. This is the
            // one fact that makes a CoT-advertised SRT feed playable, and it is not a guess:
            // it was recorded against a live server and real clients.
            //
            // ATAK does NOT read `url` for SRT. It rebuilds the connection from this
            // ConnectionEntry, and its parser matches on the literal "?streamid=" text. Given
            // a bare stream name, or the streamid without that prefix, it hands an EMPTY
            // stream id to its native SRT call; the server answers `invalid stream ID ''` and
            // the open fails instantly, every time, whatever `url` says.
            //
            // ⚠ RTSP IS DELIBERATELY LEFT ALONE. Its advertised url carries a "?tcp" query
            // that has never been part of its path, it is the form both clients have played
            // for years, and widening this to every scheme would change the one case that is
            // known to work in order to fix one that does not.
            // getRawQuery, not getQuery: the stream id must reach ATAK byte for byte as it
            // was built, and getQuery percent-DECODES. Nothing in the SRT url is encoded (the
            // stream id is opaque and must not be), so decoding could only corrupt a literal
            // "%" in a passphrase.
            if ("srt".equalsIgnoreCase(protocol) && u.getRawQuery() != null
                    && !u.getRawQuery().isEmpty()) {
                path = "?" + u.getRawQuery();
            }
        } catch (IllegalArgumentException e) {
            // An unparseable url is still worth advertising: `url` carries the whole thing, and
            // `address` falling back to it matches how ATAK advertises non-host feeds.
        }
        if (host.isEmpty()) host = videoUrl;

        sb.append("<__video uid=\"").append(escapeXml(videoUid)).append("\"");
        sb.append(" sensor=\"").append(escapeXml(alias)).append("\"");
        if (spiUid != null && !spiUid.isEmpty()) {
            sb.append(" spi=\"").append(escapeXml(spiUid)).append("\"");
        }
        sb.append(" url=\"").append(escapeXml(videoUrl)).append("\">");
        sb.append("<ConnectionEntry uid=\"").append(escapeXml(videoUid)).append("\"");
        sb.append(" alias=\"").append(escapeXml(alias)).append("\"");
        sb.append(" address=\"").append(escapeXml(host)).append("\"");
        sb.append(" port=\"").append(port).append("\"");
        sb.append(" path=\"").append(escapeXml(path)).append("\"");
        sb.append(" protocol=\"").append(escapeXml(protocol)).append("\"");
        sb.append(" networkTimeout=\"12000\" bufferTime=\"-1\" roverPort=\"-1\"");
        sb.append(" rtspReliable=\"0\" ignoreEmbeddedKLV=\"false\" />");
        sb.append("</__video>");
    }

    /**
     * A stable uid for a feed, derived from its URL.
     *
     * <p>Must not be random: a fresh uid on every position report (one every 2 seconds) would
     * have a client either create a new video entry each time or churn the existing one.
     *
     * <p>⚠ <b>A per-launch token in the path is stripped before the hash.</b> A TAKPilot2
     * application can put a random token in the stream path, so that only a client holding the
     * current CoT can open the feed: {@code <id>-<8 lowercase hex>-Low}. The token changes at
     * every application launch. If it reached the uid, a client would accumulate a new video
     * alias per flight. The uid is therefore computed from the url WITHOUT that segment, which
     * also makes it identical to the uid of the same feed with no token at all. The rule is
     * pinned in {@code CotBuilderTest}. The
     * {@code url} and {@code path} attributes keep the token — they are what a client plays.
     */
    static String videoUidFor(String videoUrl) {
        return UUID.nameUUIDFromBytes(
                videoUidKey(videoUrl).getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .toString();
    }

    /** `-<8 lowercase hex>` directly before `-Low` at the end of the path (a query or a
     *  further segment may follow). Case-sensitive on purpose: callsigns are upper case. */
    private static final java.util.regex.Pattern VIDEO_PATH_TOKEN =
            java.util.regex.Pattern.compile("-[0-9a-f]{8}(-Low)(?=[?/#]|$)");

    /** The url with any per-launch path token removed — what {@link #videoUidFor} hashes. */
    static String videoUidKey(String videoUrl) {
        return VIDEO_PATH_TOKEN.matcher(videoUrl).replaceFirst("$1");
    }

    private static String escapeXml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }
}
