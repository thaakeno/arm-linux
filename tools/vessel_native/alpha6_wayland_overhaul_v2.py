#!/usr/bin/env python3
from __future__ import annotations
import sys
from pathlib import Path

ROOT = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else Path(__file__).resolve().parents[2]

def p(rel: str) -> Path: return ROOT / rel
def rep1(rel: str, old: str, new: str) -> None:
    f=p(rel); s=f.read_text(); n=s.count(old)
    if n != 1: raise SystemExit(f"{rel}: expected 1 anchor, got {n}: {old[:120]!r}")
    f.write_text(s.replace(old,new,1))
def repall(rel: str, old: str, new: str, minimum: int=1) -> None:
    f=p(rel); s=f.read_text(); n=s.count(old)
    if n < minimum: raise SystemExit(f"{rel}: expected >= {minimum} anchors, got {n}: {old[:120]!r}")
    f.write_text(s.replace(old,new))
def region(rel: str, start: str, end: str, new: str) -> None:
    f=p(rel); s=f.read_text(); a=s.find(start); b=s.find(end,a+1) if a>=0 else -1
    if a<0 or b<0: raise SystemExit(f"{rel}: region markers missing {start!r} / {end!r}")
    f.write_text(s[:a]+new+s[b:])

# Real production presenter (CMake builds this file, not vessel_ahb_presenter.cpp).
surface=p("app/src/main/cpp/vessel_surface_presenter.cpp")
s= p("tools/vessel_native/vessel_surface_presenter_v6.cpp").read_text()
old='''            if (queue_.size() >= MAX_QUEUED_FRAMES) {\n                lock.unlock();\n                dispose_job(job, job.msg.type == MSG_FRAME);\n                if (job.msg.type == MSG_FRAME) dropped_.fetch_add(1);\n                return job.msg.type == MSG_FRAME;\n            }\n'''
new='''            if (queue_.size() >= MAX_QUEUED_FRAMES) {\n                const bool droppable = job.msg.type == MSG_FRAME;\n                lock.unlock();\n                dispose_job(job, droppable);\n                if (droppable) dropped_.fetch_add(1);\n                return droppable;\n            }\n'''
if old not in s: raise SystemExit("surface v6 queue hotfix anchor missing")
s=s.replace(old,new,1)
surface.write_text(s)

config="app/src/main/java/com/example/dreamlinux/VesselExperimentConfig.kt"
rep1(config,
'''    fun invertPointerY(context: Context): Boolean = prefs(context).getBoolean("invert_pointer_y", false)\n    fun setInvertPointerY(context: Context, value: Boolean) { prefs(context).edit().putBoolean("invert_pointer_y", value).apply() }\n\n    fun reset(context: Context) { prefs(context).edit().clear().apply() }\n''',
'''    fun invertPointerY(context: Context): Boolean = prefs(context).getBoolean("invert_pointer_y", false)\n    fun setInvertPointerY(context: Context, value: Boolean) { prefs(context).edit().putBoolean("invert_pointer_y", value).apply() }\n\n    fun desktopBackend(context: Context): String = prefs(context).getString("desktop_backend", "wayland").let { if (it == "x11") "x11" else "wayland" }\n    fun setDesktopBackend(context: Context, value: String) { prefs(context).edit().putString("desktop_backend", if (value == "x11") "x11" else "wayland").apply() }\n    fun hostGl(context: Context): String = prefs(context).getString("host_gl", "system").let { if (it == "angle") "angle" else "system" }\n    fun setHostGl(context: Context, value: String) { prefs(context).edit().putString("host_gl", if (value == "angle") "angle" else "system").apply() }\n    fun firefoxDmabuf(context: Context): Boolean = prefs(context).getBoolean("firefox_dmabuf", false)\n    fun setFirefoxDmabuf(context: Context, value: Boolean) { prefs(context).edit().putBoolean("firefox_dmabuf", value).apply() }\n\n    fun reset(context: Context) { prefs(context).edit().clear().apply() }\n''')

controller="app/src/main/java/com/example/dreamlinux/VesselRuntimeController.kt"
rep1(controller,'const val REVISION = "v40-native-surface-egl-virtio-input-r1"','const val REVISION = "v41-wayland-async-surface-system-egl-r1"')
rep1(controller,'const val DISPLAY_TRANSPORT = "vhost-user-gpu-ahb-native-surface-v3"','const val DISPLAY_TRANSPORT = "vhost-user-gpu-ahb-async-surface-v6"')
rep1(controller,
'    private val gpuBin get() = File(nativeDir, "libvessel_vhost_gpu.so")\n',
'''    private val gpuSystemBin get() = File(nativeDir, "libvessel_vhost_gpu_system.so")\n    private val gpuAngleBin get() = File(nativeDir, "libvessel_vhost_gpu_angle.so")\n    private val gpuBin get() = if (VesselExperimentConfig.hostGl(context) == "angle") gpuAngleBin else gpuSystemBin\n    private val bridgeSystem get() = File(nativeDir, "libvessel_ahb_bridge_system.so")\n    private val bridgeAngle get() = File(nativeDir, "libvessel_ahb_bridge_angle.so")\n''')
rep1(controller,
'''        umlBin, stubBin, umnetBin, passtBin, gpuBin, inputBin,\n        virglLib, epoxyLib, eglAngle, glesAngle, angleSelector,\n''',
'''        umlBin, stubBin, umnetBin, passtBin, gpuSystemBin, gpuAngleBin, inputBin,\n        bridgeSystem, bridgeAngle, virglLib, epoxyLib, eglAngle, glesAngle, angleSelector,\n''')
rep1(controller,
'        .put("renderer", "KDE Plasma/Xorg -> Mesa VirGL -> vhost-device-gpu -> virglrenderer -> ANGLE -> Adreno")',
'        .put("renderer", "KDE Plasma/${if (VesselExperimentConfig.desktopBackend(context) == "wayland") "Wayland" else "X11"} -> Mesa VirGL -> virglrenderer -> ${VesselExperimentConfig.hostGl(context)} EGL -> async AHB -> SurfaceFlinger")')
rep1(controller,
'        append("[host] starting vhost-device-gpu; display=${displaySocket.absolutePath}\\n")\n',
'        val hostGl = VesselExperimentConfig.hostGl(context)\n        append("[host] starting vhost-device-gpu; display=${displaySocket.absolutePath} hostGl=$hostGl\\n")\n')
rep1(controller,
'''        pb.environment()["LD_LIBRARY_PATH"] = nativeDir.absolutePath\n        pb.environment()["LD_PRELOAD"] = angleSelector.absolutePath\n        pb.environment()["VESSEL_ANGLE_PATH"] = nativeDir.absolutePath\n        pb.environment()["EPOXY_USE_ANGLE"] = "1"\n        pb.environment()["RUST_LOG"] = "info"\n''',
'''        pb.environment()["LD_LIBRARY_PATH"] = nativeDir.absolutePath\n        if (hostGl == "angle") {\n            pb.environment()["LD_PRELOAD"] = angleSelector.absolutePath\n            pb.environment()["VESSEL_ANGLE_PATH"] = nativeDir.absolutePath\n            pb.environment()["EPOXY_USE_ANGLE"] = "1"\n        } else {\n            pb.environment().remove("LD_PRELOAD")\n            pb.environment().remove("VESSEL_ANGLE_PATH")\n            pb.environment().remove("EPOXY_USE_ANGLE")\n        }\n        pb.environment()["VESSEL_HOST_GL"] = hostGl\n        pb.environment()["RUST_LOG"] = "info"\n''')

region(controller,'    private fun plasmaReadyCommand(): String =','    private fun packagePolicyCommand(): String =',r'''    private fun plasmaReadyCommand(): String =
        "missing=''; " +
            "for c in kwin_wayland startplasma-wayland Xwayland startplasma-x11 Xorg xrandr xinput systemsettings konsole firefox-esr; do command -v \"\$c\" >/dev/null 2>&1 || missing=\"\$missing cmd:\$c\"; done; " +
            "for p in kwin-wayland plasma-workspace-wayland xwayland qtwayland5 qml-module-org-kde-qqc2desktopstyle qml-module-org-kde-kirigami2 qml-module-org-kde-kitemmodels qml-module-org-kde-kquickcontrolsaddons qml-module-qtquick-controls qml-module-qtquick-controls2 qml-module-qtquick-layouts qml-module-qtquick-window2 qml-module-qtquick2 qml-module-qtquick-templates2 qml-module-qtgraphicaleffects qml-module-qt-labs-platform plasma-integration plasma-pa kactivitymanagerd libkf5service-data fonts-noto-color-emoji; do " +
            "dpkg-query -W -f='\${Status}' \"\$p\" 2>/dev/null | grep -q 'install ok installed' || missing=\"\$missing pkg:\$p\"; done; " +
            "qml=/usr/lib/aarch64-linux-gnu/qt5/qml; test -d /usr/share/icons/breeze || missing=\"\$missing path:breeze\"; " +
            "test -f /etc/xdg/menus/kf5-applications.menu || missing=\"\$missing path:kf5-menu\"; " +
            "test -e /usr/lib/aarch64-linux-gnu/dri/virtio_gpu_dri.so || missing=\"\$missing path:virtio_gpu_dri.so\"; " +
            "test -f \"\$qml/QtQuick/Templates.2/qmldir\" || missing=\"\$missing qml:Templates.2\"; " +
            "test -f \"\$qml/QtGraphicalEffects/qmldir\" || missing=\"\$missing qml:GraphicalEffects\"; " +
            "test -f \"\$qml/org/kde/kirigami.2/qmldir\" || missing=\"\$missing qml:kirigami\"; " +
            "test -z \"\$missing\" || { echo VESSEL_MISSING_COMPONENTS=\"\$missing\"; exit 1; }"

''')

region(controller,'    private fun ensurePlasma() {','    private fun displayModeCommand(): String {',r'''    private fun ensurePlasma() {
        recoverPackageState()
        progress("plasma", 55, "Checking KDE Plasma Wayland workstation")
        if (guestBlocking(plasmaReadyCommand(), 20).first == 0) {
            append("[plasma] complete KDE Plasma Wayland/Xwayland workstation already ready\n")
            return
        }
        progress("plasma_install", 56, "Installing native Wayland Plasma workstation")
        val reporter = PackageProgressReporter("plasma_install", 56)
        val cmd = packagePolicyCommand() + "\n" +
            "export DEBIAN_FRONTEND=noninteractive SYSTEMD_OFFLINE=1; " +
            "apt-get -o Dpkg::Use-Pty=0 -o APT::Color=0 update && " +
            "apt-get -o Dpkg::Use-Pty=0 -o APT::Color=0 install -y " +
            "kde-plasma-desktop plasma-workspace plasma-desktop kwin-x11 kwin-wayland plasma-workspace-wayland xwayland qtwayland5 wayland-utils systemsettings " +
            "xserver-xorg-core xserver-xorg-input-libinput dbus dbus-x11 udev libinput-tools mesa-utils x11-xserver-utils xinput xcvt " +
            "breeze breeze-icon-theme hicolor-icon-theme desktop-file-utils xdg-user-dirs shared-mime-info menu appstream python3-yaml " +
            "qml-module-org-kde-qqc2desktopstyle qml-module-org-kde-kirigami2 qml-module-org-kde-kitemmodels qml-module-org-kde-kquickcontrolsaddons " +
            "qml-module-qtquick-controls qml-module-qtquick-controls2 qml-module-qtquick-layouts qml-module-qtquick-window2 qml-module-qtquick2 qml-module-qtquick-templates2 qml-module-qtgraphicaleffects qml-module-qt-labs-platform plasma-integration plasma-pa kactivitymanagerd libkf5service-data " +
            "fonts-noto-core fonts-noto-color-emoji fonts-dejavu-core fonts-liberation firefox-esr konsole dolphin ark kcalc okular gwenview kate && dpkg --configure -a && apt-get clean"
        val (rc, out) = guestBlocking(cmd, 2400, reporter::onLine)
        if (rc != 0) { append("[plasma] apt failed rc=$rc ${out.takeLast(6000)}\n"); error("Plasma Wayland package installation failed (apt rc=$rc)") }
        val verify = guestBlocking(plasmaReadyCommand(), 30)
        if (verify.first != 0) { append("[plasma] validation failed ${verify.second.takeLast(6000)}\n"); error("Plasma Wayland runtime validation failed") }
        progress("plasma_ready", 72, "KDE Plasma Wayland workstation ready")
    }

''')

rep1(controller,
'''    private fun applyDisplayModeBlocking(): Boolean {\n        val (rc, out) = guestBlocking(displayModeCommand(), 20)\n        if (rc != 0) append("[display] xrandr rc=$rc ${out.takeLast(2000)}\\n")\n        return rc == 0\n    }\n''',
'''    private fun applyDisplayModeBlocking(): Boolean {\n        if (VesselExperimentConfig.desktopBackend(context) == "wayland") return true\n        val (rc, out) = guestBlocking(displayModeCommand(), 20)\n        if (rc != 0) append("[display] xrandr rc=$rc ${out.takeLast(2000)}\\n")\n        return rc == 0\n    }\n''')

region(controller,'    private fun launchDesktop() {','    private fun displayFailureStatus(status: String): Boolean =',r'''    private fun launchDesktop() {
        val backend = VesselExperimentConfig.desktopBackend(context)
        val dmabuf = if (VesselExperimentConfig.firefoxDmabuf(context)) "true" else "false"
        progress("desktop", 72, "Starting Plasma ${if (backend == "wayland") "Wayland" else "X11 fallback"}")
        val prep = """
            set -e
            mkdir -p /run/dbus /run/user /usr/local/bin /home/vessel/.config /home/vessel/.mozilla/firefox/vessel.default
            mountpoint -q /tmp || mount -t tmpfs -o mode=1777,size=256m tmpfs /tmp
            mkdir -p /run/user
            mountpoint -q /run/user || mount -t tmpfs -o mode=0755,size=64m tmpfs /run/user
            dbus-uuidgen --ensure=/etc/machine-id
            (pgrep -x systemd-udevd >/dev/null || (/lib/systemd/systemd-udevd --daemon 2>/tmp/vessel-udev.log || /usr/lib/systemd/systemd-udevd --daemon 2>/tmp/vessel-udev.log))
            udevadm trigger --action=add || true
            udevadm settle --timeout=10 || true
            test -S /run/dbus/system_bus_socket || dbus-daemon --system --fork
            id -u vessel >/dev/null 2>&1 || useradd -m -s /bin/bash vessel
            for g in video render input; do getent group "${'$'}g" >/dev/null || groupadd "${'$'}g"; done
            usermod -a -G video,render,input vessel
            uid=${'$'}(id -u vessel); gid=${'$'}(id -g vessel)
            mkdir -p /run/user/${'$'}uid; chown ${'$'}uid:${'$'}gid /run/user/${'$'}uid; chmod 700 /run/user/${'$'}uid
            test -c /dev/tty1 || mknod -m 620 /dev/tty1 c 4 1
            rm -f /etc/X11/xorg.conf.d/99-vessel.conf
            cat >/etc/profile.d/vessel-gpu.sh <<'VENV'
            export LIBGL_ALWAYS_SOFTWARE=0
            export GALLIUM_DRIVER=virgl
            export GDK_BACKEND=wayland
            export QT_QPA_PLATFORM=wayland
            export CLUTTER_BACKEND=wayland
            export SDL_VIDEODRIVER=wayland
            export MOZ_ENABLE_WAYLAND=1
            export MOZ_WEBRENDER=1
            export MOZ_ACCELERATED=1
            unset MOZ_X11_EGL
            VENV
            chmod 0644 /etc/profile.d/vessel-gpu.sh
            cat >/home/vessel/.mozilla/firefox/profiles.ini <<'FPROFILES'
            [Profile0]
            Name=Vessel
            IsRelative=1
            Path=vessel.default
            Default=1
            [General]
            StartWithLastProfile=1
            Version=2
            FPROFILES
            cat >/home/vessel/.mozilla/firefox/vessel.default/user.js <<FUSER
            user_pref("gfx.webrender.all", true);
            user_pref("gfx.webrender.compositor", true);
            user_pref("gfx.webrender.compositor.force-enabled", true);
            user_pref("layers.acceleration.force-enabled", true);
            user_pref("widget.dmabuf-textures.enabled", $dmabuf);
            user_pref("widget.dmabuf-webgl.enabled", $dmabuf);
            user_pref("gfx.x11-egl.force-disabled", true);
            user_pref("gl.require-hardware", true);
            user_pref("security.sandbox.content.level", 1);
            FUSER
            chown -R vessel:vessel /home/vessel/.mozilla /home/vessel/.config
        """.trimIndent()
        val prepResult = guestBlocking(prep, 60)
        check(prepResult.first == 0) { "desktop prep failed: ${prepResult.second.takeLast(8000)}" }

        if (backend == "wayland") {
            val session = """
                #!/bin/bash
                set -e
                export XDG_RUNTIME_DIR=/run/user/${'$'}(id -u)
                export XDG_SESSION_TYPE=wayland XDG_SESSION_DESKTOP=KDE XDG_CURRENT_DESKTOP=KDE DESKTOP_SESSION=plasmawayland
                export XDG_SEAT=seat0 XDG_VTNR=1 KDE_FULL_SESSION=true KDE_SESSION_VERSION=5
                export LIBGL_ALWAYS_SOFTWARE=0 GALLIUM_DRIVER=virgl
                export GDK_BACKEND=wayland QT_QPA_PLATFORM=wayland CLUTTER_BACKEND=wayland SDL_VIDEODRIVER=wayland
                export MOZ_ENABLE_WAYLAND=1 MOZ_WEBRENDER=1 MOZ_ACCELERATED=1
                unset MOZ_X11_EGL
                exec startplasma-wayland
            """.trimIndent() + "\n"
            val b64 = Base64.getEncoder().encodeToString(session.toByteArray())
            val launch = "printf '%s' '$b64' | base64 -d >/usr/local/bin/vessel-plasma-session; chmod 755 /usr/local/bin/vessel-plasma-session; " +
                "pkill -u vessel -x kwin_x11 2>/dev/null || true; pkill -u vessel -x kwin_wayland 2>/dev/null || true; pkill -u vessel -x plasmashell 2>/dev/null || true; pkill -x Xorg 2>/dev/null || true; " +
                "rm -f /tmp/.X0-lock /tmp/.X11-unix/X0; " +
                "nohup setsid su -l vessel -c \"XDG_RUNTIME_DIR=/run/user/\$(id -u vessel) XDG_SEAT=seat0 XDG_VTNR=1 dbus-run-session -- /usr/local/bin/vessel-plasma-session\" >/tmp/vessel-plasma.log 2>&1 </dev/tty1 &"
            val lr = guestBlocking(launch, 30)
            check(lr.first == 0) { "Plasma Wayland launch failed: ${lr.second.takeLast(10000)}" }
            val check = guestBlocking("for i in \$(seq 1 240); do pgrep -u vessel -x plasmashell >/dev/null && pgrep -u vessel -x kwin_wayland >/dev/null && break; sleep .1; done; pgrep -u vessel -x plasmashell >/dev/null && pgrep -u vessel -x kwin_wayland >/dev/null || { tail -200 /tmp/vessel-plasma.log 2>/dev/null; exit 44; }; test -S /run/user/\$(id -u vessel)/wayland-0 || { ls -la /run/user/\$(id -u vessel); tail -200 /tmp/vessel-plasma.log; exit 46; }; echo VESSEL_WAYLAND_READY", 45)
            check(check.first == 0) { "Plasma Wayland validation failed: ${check.second.takeLast(10000)}" }
            append("[desktop] native KWin Wayland + rootless Xwayland ready; Xorg/glamor retired\n")
        } else {
            val xprep = "mkdir -p /etc/X11/xorg.conf.d /tmp/.X11-unix; cat >/etc/X11/xorg.conf.d/99-vessel.conf <<'XEOF'\nSection \"Device\"\n Identifier \"Vessel GPU\"\n Driver \"modesetting\"\n Option \"kmsdev\" \"/dev/dri/card0\"\n Option \"AccelMethod\" \"glamor\"\n Option \"SWcursor\" \"true\"\nEndSection\nXEOF\n"
            check(guestBlocking(xprep, 20).first == 0)
            val session = "#!/bin/bash\nexport DISPLAY=:0 XDG_SESSION_TYPE=x11 XDG_SESSION_DESKTOP=KDE XDG_CURRENT_DESKTOP=KDE DESKTOP_SESSION=plasma KDE_FULL_SESSION=true KDE_SESSION_VERSION=5 LIBGL_ALWAYS_SOFTWARE=0 GALLIUM_DRIVER=virgl MOZ_WEBRENDER=1\nexport XDG_RUNTIME_DIR=/run/user/\$(id -u)\nexec startplasma-x11\n"
            val b64 = Base64.getEncoder().encodeToString(session.toByteArray())
            val launch = "printf '%s' '$b64' | base64 -d >/usr/local/bin/vessel-plasma-session; chmod 755 /usr/local/bin/vessel-plasma-session; pkill -u vessel -x kwin_wayland 2>/dev/null || true; pkill -u vessel -x plasmashell 2>/dev/null || true; rm -f /tmp/.X0-lock /tmp/.X11-unix/X0; nohup setsid sh -c 'exec </dev/tty1 >/dev/tty1 2>&1; exec env LIBGL_ALWAYS_SOFTWARE=0 GALLIUM_DRIVER=virgl Xorg :0 -ac -noreset -nolisten tcp -novtswitch -sharevts vt1' >/tmp/vessel-xorg.log 2>&1 & for i in \$(seq 1 160); do test -S /tmp/.X11-unix/X0 && break; sleep .1; done; test -S /tmp/.X11-unix/X0; nohup su -l vessel -c \"DISPLAY=:0 XDG_RUNTIME_DIR=/run/user/\$(id -u vessel) MOZ_WEBRENDER=1 dbus-run-session -- /usr/local/bin/vessel-plasma-session\" >/tmp/vessel-plasma.log 2>&1 </dev/null &"
            val lr = guestBlocking(launch, 50); check(lr.first == 0) { "Plasma X11 fallback launch failed: ${lr.second.takeLast(10000)}" }
            applyDisplayModeBlocking()
            val check = guestBlocking("for i in \$(seq 1 120); do pgrep -u vessel -x plasmashell >/dev/null && pgrep -u vessel -x kwin_x11 >/dev/null && break; sleep .1; done; pgrep -u vessel -x plasmashell >/dev/null && pgrep -u vessel -x kwin_x11 >/dev/null", 30)
            check(check.first == 0) { "Plasma X11 fallback validation failed" }
            append("[desktop] X11 fallback ready\n")
        }
    }

''')

service="app/src/main/java/com/example/dreamlinux/VmSessionService.kt"
rep1(service,'"kde-plasma-desktop", "plasma-workspace", "plasma-desktop", "plasma-framework", "kwin-x11", "systemsettings",','"kde-plasma-desktop", "plasma-workspace", "plasma-desktop", "plasma-framework", "kwin-x11", "kwin-wayland", "plasma-workspace-wayland", "xwayland", "qtwayland5", "systemsettings",')
rep1(service,'if (state.value.running && state.value.guestReady && now - lastStatsAt > 5000) {','if (state.value.running && state.value.guestReady && !state.value.busy && now - lastStatsAt > 5000) {')
rep1(service,
'''                applyState(started)\n                // The Linux machine is usable as soon as the guest shell is ready.\n                // Plasma validation/repair is post-boot work and must not lock the\n                // Terminal or Apps tabs behind one global UI busy bit.\n                state.value = state.value.copy(\n                    busy = false,\n                    stage = "postboot_setup",\n                    progressDetail = "Linux ready · validating desktop extras in background",\n                    message = "Linux ready",\n                )\n                ensureWorkstation(op)\n''',
'''                applyState(started)\n                ensureWorkstation(op)\n''')
repall(service,'displayTransport = "vhost-user-gpu-ahb-native-surface-v3",','displayTransport = "vhost-user-gpu-ahb-async-surface-v6",',2)
repall(service,'runtimeRevision = "v40-native-surface-egl-virtio-input-r1",','runtimeRevision = "v41-wayland-async-surface-system-egl-r1",',2)
rep1(service,'graphics = "KDE Plasma/Xorg → Mesa VirGL → virglrenderer → ANGLE → Android Surface → Adreno",','graphics = if (VesselExperimentConfig.desktopBackend(this) == "wayland") "KDE Plasma/Wayland → Mesa VirGL → virglrenderer → ${VesselExperimentConfig.hostGl(this).uppercase()} EGL → async AHB → SurfaceFlinger" else "KDE Plasma/X11 fallback → Mesa VirGL → virglrenderer → ${VesselExperimentConfig.hostGl(this).uppercase()} EGL → async AHB",')
rep1(service,"printf '%s\\n' 'unset MOZ_X11_EGL' 'export MOZ_WEBRENDER=1' >/etc/profile.d/vessel-gpu.sh","printf '%s\\n' 'unset MOZ_X11_EGL' 'export MOZ_ENABLE_WAYLAND=1' 'export MOZ_WEBRENDER=1' 'export GDK_BACKEND=wayland' 'export QT_QPA_PLATFORM=wayland' >/etc/profile.d/vessel-gpu.sh")
rep1(service,
'''            ready=0\n            for i in ${'$'}(seq 1 120); do\n              if pgrep -u vessel -x plasmashell >/dev/null && pgrep -u vessel -x kwin_x11 >/dev/null; then ready=1; break; fi\n              sleep .1\n            done\n''',
'''            ready=0\n            compositor=${if (VesselExperimentConfig.desktopBackend(this) == "wayland") "kwin_wayland" else "kwin_x11"}\n            for i in ${'$'}(seq 1 120); do\n              if pgrep -u vessel -x plasmashell >/dev/null && pgrep -u vessel -x ${'$'}compositor >/dev/null; then ready=1; break; fi\n              sleep .1\n            done\n''')

activity="app/src/main/java/com/example/dreamlinux/VesselActivity.kt"
rep1(activity,
'        var expPointerY by remember { mutableStateOf(VesselExperimentConfig.invertPointerY(this@VesselActivity)) }\n',
'''        var expPointerY by remember { mutableStateOf(VesselExperimentConfig.invertPointerY(this@VesselActivity)) }\n        var expDesktop by remember { mutableStateOf(VesselExperimentConfig.desktopBackend(this@VesselActivity)) }\n        var expHostGl by remember { mutableStateOf(VesselExperimentConfig.hostGl(this@VesselActivity)) }\n        var expFirefoxDmabuf by remember { mutableStateOf(VesselExperimentConfig.firefoxDmabuf(this@VesselActivity)) }\n''')
rep1(activity,
'''                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {\n                        FilterChip(selected = expFlipY, onClick = { expFlipY = !expFlipY; VesselExperimentConfig.setFlipDisplayY(this@VesselActivity, expFlipY) }, label = { Text("Fix display Y flip") })\n                        FilterChip(selected = expPointerY, onClick = { expPointerY = !expPointerY; VesselExperimentConfig.setInvertPointerY(this@VesselActivity, expPointerY) }, label = { Text("Invert pointer Y") })\n                    }\n                    Text("CPU, RAM, refresh, resolution and display orientation apply on the next Linux start.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)\n''',
'''                    Text("Desktop stack", style = MaterialTheme.typography.labelMedium)\n                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {\n                        FilterChip(selected = expDesktop == "wayland", onClick = { expDesktop = "wayland"; VesselExperimentConfig.setDesktopBackend(this@VesselActivity, "wayland") }, label = { Text("Wayland (default)") })\n                        FilterChip(selected = expDesktop == "x11", onClick = { expDesktop = "x11"; VesselExperimentConfig.setDesktopBackend(this@VesselActivity, "x11") }, label = { Text("X11 fallback") })\n                    }\n                    Text("Host GL", style = MaterialTheme.typography.labelMedium)\n                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {\n                        FilterChip(selected = expHostGl == "system", onClick = { expHostGl = "system"; VesselExperimentConfig.setHostGl(this@VesselActivity, "system") }, label = { Text("System EGL") })\n                        FilterChip(selected = expHostGl == "angle", onClick = { expHostGl = "angle"; VesselExperimentConfig.setHostGl(this@VesselActivity, "angle") }, label = { Text("Bundled ANGLE") })\n                        FilterChip(selected = expFirefoxDmabuf, onClick = { expFirefoxDmabuf = !expFirefoxDmabuf; VesselExperimentConfig.setFirefoxDmabuf(this@VesselActivity, expFirefoxDmabuf) }, label = { Text("Firefox DMA-BUF experimental") })\n                    }\n                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {\n                        FilterChip(selected = expFlipY, onClick = { expFlipY = !expFlipY; VesselExperimentConfig.setFlipDisplayY(this@VesselActivity, expFlipY) }, label = { Text("Fix display Y flip") })\n                        FilterChip(selected = expPointerY, onClick = { expPointerY = !expPointerY; VesselExperimentConfig.setInvertPointerY(this@VesselActivity, expPointerY) }, label = { Text("Invert pointer Y") })\n                    }\n                    Text("Wayland + System EGL are the new defaults. Restart Linux after changing architecture settings.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)\n''')
rep1(activity,
'                        expPointerY = VesselExperimentConfig.invertPointerY(this@VesselActivity)\n',
'''                        expPointerY = VesselExperimentConfig.invertPointerY(this@VesselActivity)\n                        expDesktop = VesselExperimentConfig.desktopBackend(this@VesselActivity)\n                        expHostGl = VesselExperimentConfig.hostGl(this@VesselActivity)\n                        expFirefoxDmabuf = VesselExperimentConfig.firefoxDmabuf(this@VesselActivity)\n''')
rep1(activity,'if (state.guestReady && store.apps.isEmpty() && !store.loading) {','if (state.guestReady && state.stage == "ready" && store.apps.isEmpty() && !store.loading) {')

print("[alpha6-v2] async real presenter + Wayland + System EGL/ANGLE A/B + Firefox Wayland applied")
