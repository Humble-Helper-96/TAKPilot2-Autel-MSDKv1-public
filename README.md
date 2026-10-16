# TAKPilot2 — Autel EVO II

A TAK flight application for the Autel EVO II Dual 640T V3 on the Autel Smart Controller V3, built
on Autel Mobile SDK v1.x.

The application:

- flies the aircraft;
- sends the aircraft's position, attitude, battery state and camera footprint to a TAK server as CoT;
- sends the flight screen to a media server as RTSP or SRT;
- puts TAK markers on a map and on the live video (an augmented-reality overlay);
- shows the visible camera, the thermal camera, or the thermal picture in a window on the visible
  picture (PIP);
- can send its live-video link to one TAK channel only, while every other channel still gets the
  position and markers, with a time-limited override that sends the link to every channel.

It is built on Autel's SDK sample application. The TAKPilot2 code is in `com/autel/sdksample/tak/`
and `com/taklite/`; everything else under `com/autel/` is Autel's sample, in places modified.

## 1. Augmented reality: read this first

**The AR overlay is for awareness of an area. It is not accurate for a point.**

It projects with the field of view of the lens on screen, rotates a target into the pitched camera
frame before it projects, and uses DTED terrain for the height of a pin, of the aircraft above sea
level, and for the geoid. The crosshair ground point is solved by a ray march over the terrain.

What remains is mostly the aircraft's magnetometer: a bearing error of 1–6 degrees that changes
with the direction the aircraft faces, so one fixed offset cannot correct it. At 350 m that moves a
marker up to about 27 m to the side. A contact's marker is also only as good as the position its
own device reports. Use the overlay to know where to look, not to pick one building from a row.

## 2. Build

The toolchain is old on purpose, to match the SDK. **Use these versions.**

- JDK 17
- Gradle 7.3.3 (wrapper) and Android Gradle Plugin 7.2.2
- Kotlin 1.7.20
- compileSdk 33, minSdk 21, targetSdk 29

```bash
./gradlew :app:testDebugUnitTest
```

```bash
./gradlew :app:assembleRelease
```

### 2.1 Your Autel app key

Copy `autel-key.properties.example` to `autel-key.properties` at the repository root and fill in
`autelAppKey`. The Autel SDK validates the key online against the application's package name, so
register the `applicationId` in `app/build.gradle` (`org.takpilot2.autel` unless you change it)
with Autel's developer programme. Without a valid key the SDK does not connect to the aircraft.

The AMap and Google Maps keys in the same file are used only by the map screens inherited from
Autel's sample. The TAKPilot2 flight screen draws its map with osmdroid and needs neither.

### 2.2 Signing

Copy `keystore.properties.example` to `keystore.properties` and point it at your own keystore to
sign release builds. Without it, `assembleRelease` produces an unsigned APK and debug builds use the
Android debug key. Both property files are git-ignored. Install `tools/pre-commit` as a git hook to
refuse a commit that stages one:

```bash
cp tools/pre-commit .git/hooks/pre-commit && chmod +x .git/hooks/pre-commit
```

### 2.3 The patched SDK

The app builds against `app/libs/autel-sdk-release-xl726.aar`, a copy of Autel's SDK patched so it
recognises the 640T V3's camera (which reports itself as `XL726`, a model no published SDK knows).
`buildsystem/patch-autel-sdk-camera-id.py` explains the patch and regenerates the file from
`autel-sdk-release.aar`.

## 3. Configuration

The repository contains no server addresses, certificates or credentials. Everything is set in the
application and kept on the device.

- **Pre-Flight Setup** holds the aircraft limits, the video quality, the active video server, the
  DTED terrain tiles and the FAA airspace (UASFM) data, and summarises the TAK connection.
- **TAK Server** holds the server address, ports, accounts (enrolled by certificate) and channels.
- **Video Servers** holds the RTSP or SRT servers, their credentials or passphrases, and how the
  team's clients open the stream. A server can randomize its stream path per application start.
- **Application Mode** is **Elevated** (two TAK accounts — one carries the video link to a single
  channel — two video servers, Emergency Broadcast) or **Basic** (one account, one video server).

## 4. Hardware notes

Developed and tested on an EVO II Dual 640T V3 with a Smart Controller V3 (Android 11, 1024 × 720 dp).

- **The video is a MediaProjection screen capture** of the whole flight screen — camera picture,
  HUD, map and AR overlay — encoded again and sent over RTSP or SRT. H.264 High profile by default;
  H.265 is a choice on the Video Servers screen, for when every client can decode it.
- **The camera zoom is digital only.** The SDK gives only `setDigitalZoomScale`; 100 is 1.0×. The
  camera ignores zoom in PIP and reports success, so the application refuses it there.
- **The camera makes the PIP composite**; the controller receives one stream. The camera does not
  change lens while it records and reports success anyway, so the application refuses the change.
- **The controller's hardware shutter** puts the camera in stills mode and leaves it there. REC
  records from any mode.
- **Autel Explorer** competes for the aircraft link; the application stops its background process
  when it tries to take the link, and changes nothing permanent on the controller.
- The EVO II drifts slowly in a hover by a few degrees of GNSS velocity noise, more near buildings.

## 5. Where the code is

| Package | Contents |
|---|---|
| `com/autel/sdksample/tak/` | The application: flight and home screens, HUD views, camera views and blend format, the Autel-to-TAK bridge, the CoT push, DTED terrain, FAA UASFM data, markers, the screen-capture video, the flight-limit, control-rate and avoidance controllers, the debug log screen and the field guide. |
| `com/taklite/` | The TAK core, with no SDK dependency: the TLS CoT client, CoT build and parse, certificate enrollment, channels and Data Sync. |
| `com/pedro/srt/`, `com/pedro/common/` | The SRT transport, vendored from RootEncoder 2.4.7 (Apache-2.0); see `NOTICE.md`. |
| everything else under `com/autel/` | Autel's SDK sample. |

## 6. License

The TAKPilot2 code is MIT (`LICENSE`). Autel's sample code, the Autel SDK, the AMap libraries and
the sample's model files are not covered by it; the vendored SRT client is Apache-2.0. `NOTICE.md`
gives the details.

This software commands an aircraft. It is provided without warranty. Test every build on the ground
before flying it; the remote pilot is responsible for the flight.
