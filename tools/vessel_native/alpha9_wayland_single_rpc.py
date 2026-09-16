#!/usr/bin/env python3
from __future__ import annotations
import base64
import runpy
import sys
from pathlib import Path

ROOT = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else Path(__file__).resolve().parents[2]
CONTROLLER = ROOT / "app/src/main/java/com/example/dreamlinux/VesselRuntimeController.kt"
SERVICE = ROOT / "app/src/main/java/com/example/dreamlinux/VmSessionService.kt"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one anchor, got {count}: {old[:180]!r}")
    return text.replace(old, new, 1)


# Physical-device v49 proved the complete Wayland desktop is actually alive:
# KWin, plasmashell, a Wayland socket and the AHB presenter all become ready
# before the prep RPC returns. The remaining failure was self-inflicted: after
# __VESSEL_7__:0 Android sent another shell RPC (cleanup) and the interactive
# UML console stopped accepting follow-up commands once the graphical session
# owned the guest. Make the first prep RPC authoritative and never issue a
# second Wayland guest RPC after it has already reported readiness.
FALLBACK_POSTPREP = r'''#!/bin/bash
set -eu

echo VESSEL_WAYLAND_BOOTSTRAP_BEGIN
if ! pgrep -u vessel -x kwin_wayland >/dev/null 2>&1; then
  pkill -u vessel -x kwin_x11 2>/dev/null || true
  pkill -u vessel -x kwin_wayland 2>/dev/null || true
  pkill -u vessel -x plasmashell 2>/dev/null || true
  pkill -x Xorg 2>/dev/null || true
  rm -f /tmp/.X0-lock /tmp/.X11-unix/X0
  find /run/user -maxdepth 2 -type s -name 'wayland-*' -delete 2>/dev/null || true
  : >/tmp/vessel-plasma.log
  /usr/bin/python3 /usr/local/lib/vessel/launch_wayland.py
fi

for i in $(seq 1 600); do
  if pgrep -u vessel -x kwin_wayland >/dev/null 2>&1 && \
     pgrep -u vessel -x plasmashell >/dev/null 2>&1 && \
     find /run/user -maxdepth 2 -type s -name 'wayland-*' -print -quit 2>/dev/null | grep -q .; then
    echo VESSEL_WAYLAND_READY
    exit 0
  fi
  sleep .1
done

echo VESSEL_WAYLAND_DIAG
id vessel || true
dpkg-query -W kwin-wayland kwin-common plasma-workspace-wayland 2>/dev/null || true
ls -l /dev/dri 2>/dev/null || true
ls -la /run/user/* 2>/dev/null || true
ls -ld /tmp/.X11-unix 2>/dev/null || true
dbus-send --system --print-reply --dest=org.freedesktop.DBus / org.freedesktop.DBus.NameHasOwner string:org.freedesktop.ConsoleKit 2>/dev/null || true
cat /tmp/vessel-consolekit.log 2>/dev/null || true
tail -400 /tmp/vessel-plasma.log 2>/dev/null || true
exit 44
'''
FALLBACK_B64 = base64.b64encode(FALLBACK_POSTPREP.encode()).decode()

text = CONTROLLER.read_text()
start = text.find("        var preparedDesktop = prep\n")
end_anchor = "        val prepResult = guestBlocking(preparedDesktop, 90)\n"
end = text.find(end_anchor, start + 1) if start >= 0 else -1
if start < 0 or end < 0:
    raise SystemExit(f"authoritative prep region missing (start={start}, end={end})")
end += len(end_anchor)

new_region = r'''        var preparedDesktop = prep
        var waylandReadyInPrep = false
        if (backend == "wayland") {
            // Take one atomic snapshot of the hot runtime. If an update races
            // this boot, use the APK's built-in bootstrap rather than mixing
            // files from two revisions.
            val hotRevisionBefore = VesselUpdateManager.currentRuntimeRevision(context)
            val stagedOverrides = listOf(
                "wayland-session.sh" to "/usr/local/bin/vessel-plasma-session",
                "launch-wayland.py" to "/usr/local/lib/vessel/launch_wayland.py",
            ).mapNotNull { (liveName, guestPath) ->
                VesselUpdateManager.runtimeScriptOrNull(context, liveName)?.let { Triple(liveName, guestPath, it) }
            }
            val stagedPostPrep = VesselUpdateManager.runtimeScriptOrNull(context, "wayland-postprep.sh")
            val hotRevisionAfter = VesselUpdateManager.currentRuntimeRevision(context)
            var effectivePostPrep: String? = null

            if (hotRevisionBefore == hotRevisionAfter) {
                for ((liveName, guestPath, liveText) in stagedOverrides) {
                    val liveB64 = Base64.getEncoder().encodeToString(liveText.toByteArray())
                    preparedDesktop += "\nprintf '%s' '$liveB64' | base64 -d >'$guestPath'; chmod 0755 '$guestPath'"
                    append("[update] staged $liveName from hot runtime $hotRevisionAfter into desktop prep\\n")
                }
                effectivePostPrep = stagedPostPrep
                if (effectivePostPrep != null) {
                    append("[update] staged authoritative Wayland bootstrap from hot runtime $hotRevisionAfter\\n")
                }
            } else {
                append("[update] hot runtime changed during boot snapshot ($hotRevisionBefore -> $hotRevisionAfter); using built-in Wayland bootstrap\\n")
            }

            if (effectivePostPrep == null) {
                effectivePostPrep = String(Base64.getDecoder().decode("__FALLBACK_B64__"), Charsets.UTF_8)
                append("[update] using built-in authoritative Wayland bootstrap\\n")
            }

            val postB64 = Base64.getEncoder().encodeToString(effectivePostPrep.toByteArray())
            preparedDesktop += "\nprintf '%s' '$postB64' | base64 -d | /bin/bash"
            waylandReadyInPrep = true
        }
        val prepResult = guestBlocking(preparedDesktop, 90)
'''.replace("__FALLBACK_B64__", FALLBACK_B64)
text = text[:start] + new_region + text[end:]

old_after = '''        check(prepResult.first == 0) { "desktop prep failed: ${prepResult.second.takeLast(8000)}" }

        if (backend == "wayland") {
'''
new_after = '''        check(prepResult.first == 0) { "desktop prep failed: ${prepResult.second.takeLast(8000)}" }

        if (backend == "wayland" && waylandReadyInPrep) {
            append("[desktop] authoritative Wayland prep RPC completed; KWin + Plasma + Wayland socket are ready\\n")
            return
        }

        if (backend == "wayland") {
'''
text = replace_once(text, old_after, new_after, "skip obsolete post-ready Wayland RPCs")

text = text.replace("v48-prep-folded-live-bootstrap-r1", "v49-wayland-single-rpc-r1")
CONTROLLER.write_text(text)

if SERVICE.exists():
    SERVICE.write_text(SERVICE.read_text().replace(
        "v48-prep-folded-live-bootstrap-r1",
        "v49-wayland-single-rpc-r1",
    ))

print("[alpha9] Wayland startup is single-RPC and authoritative; obsolete post-ready guest RPCs skipped")

# Physical v49 finally proves the graphics stack. Alpha10 deliberately runs
# last and addresses the remaining product-level issues: post-boot RPC, audio,
# restart cleanup, cursor alpha, 120 Hz defaults, Apps and fullscreen UX.
next_patch = Path(__file__).with_name("alpha10_stability_polish.py")
sys.argv = [str(next_patch), str(ROOT)]
runpy.run_path(str(next_patch), run_name="__main__")
