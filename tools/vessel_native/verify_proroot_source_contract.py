#!/usr/bin/env python3
import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]

def fail(message):
    raise SystemExit("rootfs source contract: " + message)

def kotlin_string(source, name):
    pattern = rf'const val {re.escape(name)}\s*=\s*(?:"([^"]*)"|\n\s*"([^"]*)")'
    match = re.search(pattern, source)
    if not match:
        fail("missing Kotlin constant " + name)
    return next(group for group in match.groups() if group is not None)

gpu_kt = (ROOT / "app/src/main/java/com/example/dreamlinux/VesselDirectGpu.kt").read_text()
desktop_kt = (ROOT / "app/src/main/java/com/example/dreamlinux/VesselProrootCompatibility.kt").read_text()
gpu = json.loads((ROOT / "tools/vessel_proroot/direct_gpu_manifest.json").read_text())
desktop = json.loads((ROOT / "tools/vessel_proroot/desktop_manifest.json").read_text())
builder = (ROOT / "tools/vessel_proroot/build_prebuilt_rootfs.sh").read_text()

checks = [
    ("Mesa version", kotlin_string(gpu_kt, "MESA_VERSION"), gpu["version"]),
    ("Mesa archive SHA", kotlin_string(gpu_kt, "ARCHIVE_SHA256"), gpu["sha256"]),
    ("Mesa archive name", kotlin_string(gpu_kt, "ARCHIVE_NAME"), gpu["archive"]),
    ("Mesa marker", kotlin_string(gpu_kt, "MARKER"), gpu["marker"]),
    ("desktop release", kotlin_string(desktop_kt, "RELEASE"), desktop["release"]),
    ("KWin archive", kotlin_string(desktop_kt, "KWIN_ARCHIVE"), desktop["kwin"]["archive"]),
    ("KWin SHA", kotlin_string(desktop_kt, "KWIN_SHA256"), desktop["kwin"]["sha256"]),
    ("XWayland archive", kotlin_string(desktop_kt, "XWAYLAND_ARCHIVE"), desktop["xwayland"]["archive"]),
    ("XWayland SHA", kotlin_string(desktop_kt, "XWAYLAND_SHA256"), desktop["xwayland"]["sha256"]),
    ("desktop marker", kotlin_string(desktop_kt, "MARKER"), desktop["marker"]),
    ("desktop starter", kotlin_string(desktop_kt, "STARTER"), desktop["starter"]),
    ("desktop probe", kotlin_string(desktop_kt, "PROBE"), desktop["probe"]),
]
for label, android, manifest in checks:
    if android != manifest:
        fail(f"{label} drift: Android={android!r} manifest={manifest!r}")

if "--hard-dereference" not in builder:
    fail("rootfs archive is not flattening Linux hard links")
if '"hardLinksFlattened": True' not in builder:
    fail("rootfs manifest does not declare hardLinksFlattened=true")

print("rootfs source contract OK")
