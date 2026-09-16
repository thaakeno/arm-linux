#!/usr/bin/env python3
from __future__ import annotations
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


text = CONTROLLER.read_text()

# v47 installed live Wayland files with a second guest RPC immediately after the
# already-large prep RPC. On the physical UML console that extra nested-base64
# write could miss the command completion window and time out before KWin was
# even launched. Keep the live-update lane, but fold every live bootstrap file
# into the existing prep command. The built-in files remain the fallback.
old_live_block = r'''        if (backend == "wayland") {
            val liveFiles = listOf(
                "wayland-session.sh" to "/usr/local/bin/vessel-plasma-session",
                "launch-wayland.py" to "/usr/local/lib/vessel/launch_wayland.py",
            )
            for ((liveName, guestPath) in liveFiles) {
                val liveText = VesselUpdateManager.runtimeScriptOrNull(context, liveName) ?: continue
                val liveB64 = Base64.getEncoder().encodeToString(liveText.toByteArray())
                val installLive = guestBlocking("printf '%s' '$liveB64' | base64 -d >'$guestPath'; chmod 0755 '$guestPath'", 30)
                check(installLive.first == 0) { "Live runtime install failed for $liveName: ${installLive.second.takeLast(6000)}" }
                append("[update] live runtime override installed: $liveName\\n")
            }
            VesselUpdateManager.runtimeScriptOrNull(context, "wayland-postprep.sh")?.let { postPrep ->
                val post = guestBlocking(postPrep, 45)
                check(post.first == 0) { "Live Wayland post-prep failed: ${post.second.takeLast(8000)}" }
            }
'''
new_live_block = '''        if (backend == "wayland") {
'''
text = replace_once(text, old_live_block, new_live_block, "remove post-prep live guest RPCs")

prep_call = '        val prepResult = guestBlocking(prep, 60)\n'
prep_replacement = r'''        var preparedDesktop = prep
        if (backend == "wayland") {
            // Snapshot one live-runtime revision before touching the guest. If the
            // automatic updater promotes a new bundle while we are reading files,
            // discard the partial snapshot and keep the APK's built-in bootstrap.
            val hotRevisionBefore = VesselUpdateManager.currentRuntimeRevision(context)
            val stagedOverrides = listOf(
                "wayland-session.sh" to "/usr/local/bin/vessel-plasma-session",
                "launch-wayland.py" to "/usr/local/lib/vessel/launch_wayland.py",
            ).mapNotNull { (liveName, guestPath) ->
                VesselUpdateManager.runtimeScriptOrNull(context, liveName)?.let { Triple(liveName, guestPath, it) }
            }
            val stagedPostPrep = VesselUpdateManager.runtimeScriptOrNull(context, "wayland-postprep.sh")
            val hotRevisionAfter = VesselUpdateManager.currentRuntimeRevision(context)
            if (hotRevisionBefore == hotRevisionAfter) {
                for ((liveName, guestPath, liveText) in stagedOverrides) {
                    val liveB64 = Base64.getEncoder().encodeToString(liveText.toByteArray())
                    preparedDesktop += "\nprintf '%s' '$liveB64' | base64 -d >'$guestPath'; chmod 0755 '$guestPath'"
                    append("[update] staged $liveName from hot runtime $hotRevisionAfter into desktop prep\\n")
                }
                stagedPostPrep?.let { postPrep ->
                    val postB64 = Base64.getEncoder().encodeToString(postPrep.toByteArray())
                    preparedDesktop += "\nprintf '%s' '$postB64' | base64 -d | /bin/bash"
                    append("[update] staged Wayland post-prep hook from hot runtime $hotRevisionAfter\\n")
                }
            } else {
                append("[update] hot runtime changed during boot snapshot ($hotRevisionBefore -> $hotRevisionAfter); using built-in Wayland bootstrap for this start\\n")
            }
        }
        val prepResult = guestBlocking(preparedDesktop, 90)
'''
text = replace_once(text, prep_call, prep_replacement, "fold live runtime into desktop prep")

# The transport layer already installs a fd-clean Python launcher during prep.
# Keep dispatch tiny: Python daemonizes Plasma with DEVNULL/stdout-to-file and
# returns immediately, then the hot readiness probe owns the long wait.
expected_dispatch = '''            val dispatchResult = guestBlocking("/usr/bin/python3 /usr/local/lib/vessel/launch_wayland.py && echo VESSEL_WAYLAND_DISPATCHED", 45)
'''
if text.count(expected_dispatch) != 1:
    raise SystemExit(f"Wayland Python dispatch anchor missing: {text.count(expected_dispatch)}")

text = text.replace("v47-live-runtime-updater-r1", "v48-prep-folded-live-bootstrap-r1")
CONTROLLER.write_text(text)

if SERVICE.exists():
    SERVICE.write_text(SERVICE.read_text().replace(
        "v47-live-runtime-updater-r1",
        "v48-prep-folded-live-bootstrap-r1",
    ))

print("[alpha8] live Wayland overrides folded into prep; Python detached dispatch preserved")

# Alpha9 consumes the alpha8-generated source and makes the prep RPC the single
# authoritative Wayland startup transaction. This is intentionally chained here
# so every existing CI/native rebuild entry point gets the physical-device fix.
next_patch = Path(__file__).with_name("alpha9_wayland_single_rpc.py")
sys.argv = [str(next_patch), str(ROOT)]
runpy.run_path(str(next_patch), run_name="__main__")

# Alpha9 chains Alpha10. Run the small compile-integrity repair only after that
# finalizer, because Alpha10 intentionally rewrites the experiment config.
compile_fix = Path(__file__).with_name("alpha11_ci_compile_fix.py")
sys.argv = [str(compile_fix), str(ROOT)]
runpy.run_path(str(compile_fix), run_name="__main__")

# Alpha12 fixes the physical-device post-audio boot hang. It must run after
# Alpha10 has introduced the guest control-agent bootstrap and after Alpha11 has
# repaired the generated source. The previous commit added Alpha12 but never
# chained it into the build, so shipped APKs still used the old 20-second
# `nohup ... &` tty transaction.
control_fix = Path(__file__).with_name("alpha12_control_agent_bootstrap_fix.py")
sys.argv = [str(control_fix), str(ROOT)]
runpy.run_path(str(control_fix), run_name="__main__")
