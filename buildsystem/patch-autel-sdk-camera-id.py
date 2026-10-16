#!/usr/bin/env python3
"""
Teach the Autel SDK that this aircraft's camera exists.

=============================================================================
WHY THIS EXISTS  (read before touching app/libs/)
=============================================================================
The EVO II 640T V3's camera identifies itself as "XL726" in the CameraType
field of its SystemStatus push. NO published Autel SDK knows that string --
verified against all three of AutelSDK's public repos (dates are their last releases):

    AndroidAdvanceSample (2024-01-13)  XK729 XL705 XL709 XL719 XL720 XL725
                                       XL729 + XT701..XT712   <- what we ship
    AndroidSample        (2023-08-01)  XK729 XL709 + XT701..XT712
                                       (a strict SUBSET of ours)
    MSDK2.0 V2.0.66      (2024-09-23)  XL705 XL709 XL715 XL716 XL720 XL730
                                       XL732 XL736 XL8xx, no XT7xx at all
                                       (different product generation)

The consequence, traced end-to-end through the bytecode and confirmed on
hardware:

  camera reports "XL726"
    -> CameraMessageDisPatcher.transferType() has no mapping for it, so it
       passes through unchanged (it DOES map XL719/XL729/XK729/XL709/XL725
       -> XT709, so XL726 misses by one digit)
    -> CameraProduct.find("XL726") == UNKNOWN
    -> BaseCamera20.getProduct() == UNKNOWN
    -> CameraMessageDisPatcher.notifyConnected() sees UNKNOWN and SUPPRESSES
       the CONNECTED notification (it is guarded -- this part is correct)
    -> internal CameraManager.connectStateChanged() is never called with a
       real product, so currentCamera stays null and CameraManager$2
       substitutes `new UnknownCamera()` as a NULL PLACEHOLDER
    -> the app sees a non-null camera that is not a camera: every command
       fails with "the communication to the aircraft has not been build up",
       and `as? AutelXT706` yields null so IR/zoom/exposure are dead.

And it never self-corrects: the SDK's 3s retry (Observable.interval in
initHandler) only runs while isConnected == false, and setCameraCurrentData()
calls notifyConnected() BEFORE setCameraCurrentDate(), whose success callback
sets isConnected = true. The SDK checks the product exactly once, gets UNKNOWN,
then disables its own retry loop permanently.

That XL726 belongs with XL725 is not a guess from the adjacent number. The
third-party crgrove/automated-drone-image-analysis-tool lists "XL725, XL726"
as the camera IDs of the same airframe -- "Autel / Evo II Dual 640T", RGB and
Thermal -- and its thermal parser handles XL726 explicitly. XL725 already maps
to XT709 in this SDK.

=============================================================================
WHAT THIS DOES
=============================================================================
Repoints the string "XL719" to "XL726" everywhere inside classes.jar.

XL719 is the donor because it is the only alias that appears in BOTH lists we
need fixed, and it already maps to XT709:

    CameraMessageDisPatcher.transferType()   XL719 XL729 XK729 XL709 XL725 -> XT709
    RxAutelBaseCameraImpl.isEvoAdvance()     XL719 XL709 XL720 XL729 XL705 XK729 XL725 -> true

So one 5-byte edit fixes camera enumeration AND the EVO-advance feature test.
Both are needed: they read DIFFERENT fields (cameraModel vs cameraRealType),
so patching one would not have fixed the other.

>>> IT MUST BE A GLOBAL REPLACE, NOT A CameraProduct.class-ONLY EDIT. <<<
Java class files deduplicate UTF-8 constant-pool entries, so the enum constant
NAME, its moduleName VALUE, and the FIELD name are all one shared entry. The
other two classes reach that field by name via getstatic. Renaming it in
CameraProduct alone yields NoSuchFieldError at runtime -- a worse failure than
the one we are fixing.

"XL719" and "XL726" are both 5 bytes, so every offset, the constant-pool
structure, and all class-file lengths survive untouched. That is the whole
reason this is a byte patch rather than a recompile.

=============================================================================
!!! LANDMINE -- READ IF YOU EVER CHANGE AIRCRAFT !!!
=============================================================================
This build can NO LONGER recognise genuine XL719 hardware, and
CameraProduct.XL719.name() now returns "XL726". That is a deliberate trade:
we spend an alias this airframe will never use to buy one it cannot fly
without. If you point this repo at a different Autel camera, revisit this.

The RIGHT fix is an Autel SDK that knows XL726. Ask them for one. When it
arrives, delete this script, drop the patched aar, and point build.gradle back
at the vendor file.

=============================================================================
USAGE
=============================================================================
    python3 buildsystem/patch-autel-sdk-camera-id.py

Reads   app/libs/autel-sdk-release.aar        (pristine vendor file, untouched)
Writes  app/libs/autel-sdk-release-xl726.aar  (what app/build.gradle consumes)

Idempotent: re-running regenerates the output from the pristine input.

It ASSERTS the exact shape of the vendor aar (3 classes, 1 occurrence each,
each a length-5 UTF-8 entry) and ABORTS on any mismatch. If Autel ships a new
aar whose layout differs, this fails LOUDLY at patch time rather than silently
mispatching a binary that then misbehaves in flight.
"""

import shutil
import sys
import zipfile
from pathlib import Path

DONOR = b"XL719"      # alias we sacrifice: maps to XT709, in both lists, unused here
TARGET = b"XL726"     # what the EVO II 640T V3 camera actually reports

# The vendor aar must match this exactly, or we refuse to touch it.
EXPECTED = {
    "com/autel/common/camera/CameraProduct.class": 1,
    "com/autel/camera/communication/http/events/CameraMessageDisPatcher.class": 1,
    "com/autel/internal/camera/RxAutelBaseCameraImpl.class": 1,
}

# A UTF-8 constant-pool entry is: tag 0x01, then a u2 big-endian length.
# Requiring the 2 length bytes 00 05 immediately before the match proves we are
# looking at a standalone "XL719" entry and not a substring of a longer string.
UTF8_LEN5 = b"\x00\x05"

REPO = Path(__file__).resolve().parent.parent
SRC = REPO / "app/libs/autel-sdk-release.aar"
DST = REPO / "app/libs/autel-sdk-release-xl726.aar"


def die(msg):
    sys.exit(f"ABORT: {msg}\n\nThe vendor aar is not the shape this patch expects.\n"
             f"Do NOT hand-edit around this -- re-read the analysis at the top of\n"
             f"{Path(__file__).name} and re-derive the patch against the new aar.")


def patch_classes_jar(jar_bytes):
    """Rewrite DONOR -> TARGET across every class in classes.jar, verifying as we go."""
    src = zipfile.ZipFile(__import__("io").BytesIO(jar_bytes))
    out_buf = __import__("io").BytesIO()
    found = {}

    # Rebuild rather than edit in place: zip central-directory bookkeeping is not
    # worth doing by hand, and entry sizes are unchanged anyway.
    with zipfile.ZipFile(out_buf, "w", zipfile.ZIP_DEFLATED) as out:
        for info in src.infolist():
            data = src.read(info.filename)
            count = data.count(DONOR)
            if count:
                found[info.filename] = count
                # Every occurrence must be a length-5 UTF-8 entry.
                pos = -1
                while True:
                    pos = data.find(DONOR, pos + 1)
                    if pos < 0:
                        break
                    if data[pos - 2:pos] != UTF8_LEN5:
                        die(f"{info.filename}: {DONOR.decode()} at offset {pos} is not a "
                            f"length-5 UTF-8 constant (preceding bytes: "
                            f"{data[pos-2:pos].hex()}). It may be a substring of a longer "
                            f"string -- replacing it would corrupt the class.")
                before = len(data)
                data = data.replace(DONOR, TARGET)
                if len(data) != before:
                    die(f"{info.filename}: length changed during replace "
                        f"({before} -> {len(data)}). This must never happen; "
                        f"{DONOR.decode()} and {TARGET.decode()} are both 5 bytes.")
            out.writestr(info, data)

    if found != EXPECTED:
        die(f"occurrence map mismatch.\n  expected: {EXPECTED}\n  found:    {found}")

    return out_buf.getvalue()


def main():
    if not SRC.is_file():
        die(f"vendor aar not found at {SRC}")

    print(f"reading  {SRC.relative_to(REPO)} ({SRC.stat().st_size:,} bytes)")

    src_zip = zipfile.ZipFile(SRC)
    names = src_zip.namelist()
    if "classes.jar" not in names:
        die("no classes.jar inside the aar")

    # Sanity: the string must not live anywhere else in the aar (res/assets/jni),
    # where a rename would not be matched by a corresponding bytecode change.
    for name in names:
        if name == "classes.jar":
            continue
        if DONOR in src_zip.read(name):
            die(f"{DONOR.decode()} also appears in {name}, outside classes.jar. "
                f"This patch only reasons about class files.")

    tmp = DST.with_suffix(".aar.tmp")
    with zipfile.ZipFile(SRC) as z_in, zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED) as z_out:
        for info in z_in.infolist():
            data = z_in.read(info.filename)
            if info.filename == "classes.jar":
                data = patch_classes_jar(data)
            z_out.writestr(info, data)

    shutil.move(str(tmp), str(DST))
    print(f"wrote    {DST.relative_to(REPO)} ({DST.stat().st_size:,} bytes)")
    print(f"patched  {DONOR.decode()} -> {TARGET.decode()} in "
          f"{len(EXPECTED)} classes, 1 occurrence each")
    print("\nverify with:  python3 buildsystem/patch-autel-sdk-camera-id.py --verify")


def verify():
    """Confirm the emitted aar says XL726 where it should and XL719 nowhere."""
    if not DST.is_file():
        die(f"{DST} not found -- run without --verify first")
    z = zipfile.ZipFile(DST)
    jar = zipfile.ZipFile(__import__("io").BytesIO(z.read("classes.jar")))
    hits, stale = {}, {}
    for name in jar.namelist():
        data = jar.read(name)
        if TARGET in data:
            hits[name] = data.count(TARGET)
        if DONOR in data:
            stale[name] = data.count(DONOR)
    print(f"{TARGET.decode()} present in: {hits}")
    print(f"{DONOR.decode()} remaining in: {stale or 'nothing'}")
    if hits != EXPECTED:
        die(f"expected {TARGET.decode()} in {EXPECTED}, found {hits}")
    if stale:
        die(f"{DONOR.decode()} still present in {stale}")
    print("OK")


if __name__ == "__main__":
    verify() if "--verify" in sys.argv else main()
