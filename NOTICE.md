# Notices

`LICENSE` is plain MIT and covers the TAKPilot2 code in this repository. It is kept free of any
other text so that automated licence detection reads it correctly. The terms below sit alongside
it; they do not modify it.

## What the MIT licence does NOT cover

**Autel's SDK sample code.** This project is built on Autel Robotics' Android SDK sample
application (https://github.com/AutelSDK/AndroidAdvanceSample). The files under
`app/src/main/java/com/autel/` outside `com/autel/sdksample/tak/`, the sample's resources, and
`TestApplication.java` come from that sample, in places modified. Autel publishes the sample
without stating a licence. Those files remain Autel Robotics' and this repository grants no
licence to them.

**The Autel SDK binaries.** `app/libs/autel-sdk-release.aar` is the SDK as Autel distributes it in
its public sample repositories. `app/libs/autel-sdk-release-xl726.aar` is a copy of it patched by
`buildsystem/patch-autel-sdk-camera-id.py` so the SDK recognises the EVO II 640T V3 camera; the
script explains the patch and regenerates the file. Both remain Autel Robotics' and are covered
by Autel's SDK terms, not by this repository's licence. You need your own Autel app key to use
them (see `autel-key.properties.example`).

**The map libraries and model files from the sample.** `app/libs/Android_Map3D_SDK_*.jar` and
`app/src/main/jniLibs/armeabi/*.so` are the AMap (AutoNavi) map SDK; `app/src/main/assets/`
holds model files the sample's vision screens load. They are included as the sample ships them
and remain their owners'. The TAKPilot2 flight screen uses neither.

**The vendored SRT client.** `app/src/main/java/com/pedro/srt/` and
`app/src/main/java/com/pedro/common/` are taken from RootEncoder (Copyright pedroSG94), Apache
License 2.0, tag `2.4.7` — https://github.com/pedroSG94/RootEncoder. `com/pedro/srt/` is the whole
`srt` module; `com/pedro/common/` holds the files that module uses. The rest of `common` and the
AV1 parser are not included. A copy of the Apache License 2.0 is at
http://www.apache.org/licenses/LICENSE-2.0

It is vendored as source rather than taken as a dependency so that the changes below could be made
and so that it compiles with Kotlin 1.7.20. Changes, each marked `TAKPILOT2 CHANGE` in the file:

- `SrtClient`: the latency goes into BOTH handshake delay fields (the library sent a fixed
  120 ms receiver delay and a zero sender delay, so a publisher's value governed nothing);
  silence from the server longer than 5 s, or a receive error, is reported as a failed
  connection instead of being ignored; the retry budget is refilled when a connection succeeds,
  so it counts per outage; `setAuthorization` throws with a message saying where the
  credentials go (SRT has no user or password field).
- `CommandsManager`: the retransmit queue is bounded (2048 packets, oldest dropped first).
- `Constants`: `MTU` 1500 -> 1316, because the library does not subtract the IP and UDP headers
  from it; a separate `READ_BUFFER` keeps the receive path at 1500 (`SrtSocket` uses it).
- `BitrateChecker` was a Java interface and is the same interface in Kotlin, so kapt can read it.
- Seven enum files call `values()` in place of the Kotlin 1.9 `entries`.

**The RTSP client** (`com.github.pedroSG94.rtmp-rtsp-stream-client-java:rtsp:2.2.6`) is by the same
author, under the same licence. It is a Maven dependency, not vendored, and unmodified.

## This software commands an aircraft

It is provided "as is" and without warranty of any kind, as `LICENSE` states. Nobody but you has
tested the build you make from this source. The remote pilot is responsible for the safety of the
flight and for compliance with the applicable aviation regulations; test every build on the
ground, against your own TAK server and media server, before flying it.
