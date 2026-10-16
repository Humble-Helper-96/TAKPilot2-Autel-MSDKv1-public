package com.taklite.client.tak;

public class TakUser {
    private String uid;
    private String callsign;
    private double lat;
    private double lon;
    private double alt;
    private String team;
    private String role;
    private long staleTime;
    private long lastUpdateTime = System.currentTimeMillis();
    private String emergencyType;
    private boolean emergencyActive;

    // Drone/video fields
    private String videoUrl;
    private String videoAlias;
    private String sensorModel;
    private double sensorFov = -1;
    private double sensorAzimuth = -1;
    private double sensorRange = -1;
    private boolean drone;
    /** A UAS: the sender said so with a &lt;vehicle&gt; or &lt;_uastool&gt; block. See CotParser. */
    private boolean uas;
    private String operatorUid;
    private String type;   // raw CoT type (e.g. a-f-G-U-C, b-m-p-s-m), for map symbol resolution
    /**
     * A shared MARKER rather than a position report — set at parse by
     * {@link CotParser#isPersistentType}. The stale sweep does not delete these; only an explicit
     * delete, a local delete, or the 72-hour eviction in TakMapMarkers removes them.
     * Never true for air-domain types — see that method for why that matters.
     */
    private boolean persistent;

    /**
     * True for a LIVE TAK CLIENT: a person or a machine that reports its own position. False for
     * a placed marker. Set at parse by {@link CotParser#isLiveClient}, which holds the rule and
     * the measurements behind it. A renderer reads this flag; it does not repeat the test.
     *
     * A live client draws as a team dot. A placed marker draws as a 2525 frame. Neither the CoT
     * type nor the {@code <takv>}/{@code endpoint} marks can decide this alone — see that method.
     * Never true together with {@link #persistent}.
     */
    private boolean liveClient;
    /** <track course> in degrees true, or -1 when the sender did not report one. ADS-B
     *  gateways populate it; most hand-placed markers and many PLIs do not. */
    private double course = -1;
    /** <track speed> in m/s, or -1 when not reported. */
    private double speed = -1;
    /** When the position was true: the event's start (else time), epoch ms; 0 = unknown. */
    private long reportTime = 0;

    public TakUser(String uid, String callsign, double lat, double lon, double alt, String team, String role, long staleTime) {
        this.uid = uid;
        this.callsign = callsign;
        this.lat = lat;
        this.lon = lon;
        this.alt = alt;
        this.team = team;
        this.role = role;
        this.staleTime = staleTime;
    }

    /**
     * True when the sender's stale time has passed. A renderer draws a stale item grey.
     *
     * A PERSISTENT item never reports stale (design decision). The stale sweep already
     * ignores the sender's window for a placed marker, because senders put useless windows on
     * them: CloudTAK about four seconds, TAK Aware ten minutes. If the colour trusted the same
     * window the sweep distrusts, a marker shared through TAK Aware would turn grey ten minutes
     * after it was placed while it stayed on the map for 72 hours. A placed marker keeps its
     * colour until it is deleted or evicted. Live clients and air tracks grey normally.
     */
    public boolean isStale() {
        if (persistent) return false;
        return System.currentTimeMillis() > staleTime;
    }

    public boolean isExpired() {
        return System.currentTimeMillis() > staleTime + 300000;
    }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public boolean isLiveClient() { return liveClient; }
    public void setLiveClient(boolean liveClient) { this.liveClient = liveClient; }

    public boolean isPersistent() { return persistent; }
    public void setPersistent(boolean persistent) { this.persistent = persistent; }

    public String getUid() { return uid; }
    public String getCallsign() { return callsign; }
    public double getLat() { return lat; }
    public double getLon() { return lon; }
    public double getAlt() { return alt; }
    public String getTeam() { return team; }
    public String getRole() { return role; }
    public long getStaleTime() { return staleTime; }
    public long getLastUpdateTime() { return lastUpdateTime; }
    public String getEmergencyType() { return emergencyType; }
    public boolean isEmergencyActive() { return emergencyActive; }

    public void setLat(double lat) { this.lat = lat; }
    public void setLon(double lon) { this.lon = lon; }
    public void setAlt(double alt) { this.alt = alt; }
    public void setCallsign(String callsign) { this.callsign = callsign; }
    public void setTeam(String team) { this.team = team; }
    public void setRole(String role) { this.role = role; }
    public void setStaleTime(long staleTime) { this.staleTime = staleTime; }
    public void setLastUpdateTime(long lastUpdateTime) { this.lastUpdateTime = lastUpdateTime; }
    public void setEmergencyType(String emergencyType) { this.emergencyType = emergencyType; }
    public void setEmergencyActive(boolean emergencyActive) { this.emergencyActive = emergencyActive; }

    // Drone/video methods
    public String getVideoUrl() { return videoUrl; }
    public void setVideoUrl(String videoUrl) { this.videoUrl = videoUrl; }
    public String getVideoAlias() { return videoAlias; }
    public void setVideoAlias(String videoAlias) { this.videoAlias = videoAlias; }
    public String getSensorModel() { return sensorModel; }
    public void setSensorModel(String sensorModel) { this.sensorModel = sensorModel; }
    public double getSensorFov() { return sensorFov; }
    public void setSensorFov(double sensorFov) { this.sensorFov = sensorFov; }
    public double getSensorAzimuth() { return sensorAzimuth; }
    public void setSensorAzimuth(double sensorAzimuth) { this.sensorAzimuth = sensorAzimuth; }
    public double getSensorRange() { return sensorRange; }
    public void setSensorRange(double sensorRange) { this.sensorRange = sensorRange; }
    public boolean hasSensorFov() { return sensorFov >= 0 && sensorAzimuth >= 0 && sensorRange > 0; }
    public boolean isUas() { return uas; }
    public void setUas(boolean uas) { this.uas = uas; }

    public boolean isDrone() { return drone; }
    public void setDrone(boolean drone) { this.drone = drone; }
    public String getOperatorUid() { return operatorUid; }
    public void setOperatorUid(String operatorUid) { this.operatorUid = operatorUid; }
    public boolean hasVideo() { return videoUrl != null && !videoUrl.isEmpty(); }

    public double getCourse() { return course; }
    public void setCourse(double course) { this.course = course; }
    /** True when a real course was reported, so callers never rotate a symbol to a default. */
    public boolean hasCourse() { return course >= 0; }
    public double getSpeed() { return speed; }
    public void setSpeed(double speed) { this.speed = speed; }
    public long getReportTime() { return reportTime; }
    public void setReportTime(long reportTime) { this.reportTime = reportTime; }
}
