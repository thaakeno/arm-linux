#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

def read(rel):
    return (ROOT / rel).read_text()

def write(rel, text):
    (ROOT / rel).write_text(text)

def replace_once(rel, old, new):
    text = read(rel)
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{rel}: expected one occurrence, got {count}: {old[:120]!r}")
    write(rel, text.replace(old, new, 1))

def replace_all(rel, old, new, minimum=1):
    text = read(rel)
    count = text.count(old)
    if count < minimum:
        raise SystemExit(f"{rel}: expected >= {minimum} occurrences, got {count}: {old!r}")
    write(rel, text.replace(old, new))

def replace_between(rel, start, end, new_block):
    text = read(rel)
    a = text.find(start)
    if a < 0:
        raise SystemExit(f"{rel}: missing start marker {start!r}")
    b = text.find(end, a)
    if b < 0:
        raise SystemExit(f"{rel}: missing end marker {end!r}")
    write(rel, text[:a] + new_block + text[b:])

replace_once("app/build.gradle.kts", 'versionName = "2.1.0-alpha1"', 'versionName = "2.1.0-alpha2"')

rt = "app/src/main/java/com/example/dreamlinux/VesselRuntimeController.kt"
replace_all(rt, 'v39-self-contained-ahb-syncfd-virtio-input-r6', 'v39-self-contained-ahb-syncfd-virtio-input-r7')
replace_once(rt, 'const val UML_VCPUS = 1', 'const val UML_VCPUS = 6')
replace_once(rt,
    'append("[host] starting UML with ${guestMemoryMb} MiB RAM, $UML_VCPUS vCPU (ARM64 SMP containment)\\n")',
    'append("[host] starting UML with ${guestMemoryMb} MiB RAM, $UML_VCPUS vCPUs\\n")')

replace_once(rt, '''            consoleWriter!!.apply {
                write("$command\\nprintf '$marker:%s\\\\n' \\$?\\n")
                flush()
            }
''', '''            val encoded = Base64.getEncoder().encodeToString(command.toByteArray(Charsets.UTF_8))
            consoleWriter!!.apply {
                // Feed arbitrary user commands to a fresh bash process instead of
                // pasting them directly into the interactive root shell. This
                // preserves quotes, pipes, semicolons and multiline commands.
                write("printf '%s' '$encoded' | base64 -d | /bin/bash; __vessel_rc=\\$?; printf '$marker:%s\\\\n' \\\"\\$__vessel_rc\\\"\\n")
                flush()
            }
''')

replace_between(rt,
    '    private fun plasmaReadyCommand(): String =',
    '    private fun packagePolicyCommand(): String =',
'''    private fun plasmaReadyCommand(): String =
        "missing=''; " +
            "for c in startplasma-x11 Xorg xrandr xinput systemsettings konsole firefox-esr; do command -v \\\"\\$c\\\" >/dev/null 2>&1 || missing=\\\"\\$missing cmd:\\$c\\\"; done; " +
            "for p in qml-module-org-kde-qqc2desktopstyle qml-module-org-kde-kirigami2 qml-module-org-kde-kitemmodels qml-module-org-kde-kquickcontrolsaddons qml-module-qtquick-controls qml-module-qtquick-controls2 qml-module-qtquick-layouts qml-module-qtquick-window2 qml-module-qtquick2 qml-module-qtquick-templates2 qml-module-qtgraphicaleffects plasma-integration libkf5service-data; do " +
            "dpkg-query -W -f='\\${Status}' \\\"\\$p\\\" 2>/dev/null | grep -q 'install ok installed' || missing=\\\"\\$missing pkg:\\$p\\\"; done; " +
            "qml=/usr/lib/aarch64-linux-gnu/qt5/qml; " +
            "test -d /usr/share/icons/breeze || missing=\\\"\\$missing path:/usr/share/icons/breeze\\\"; " +
            "test -f /etc/xdg/menus/kf5-applications.menu || missing=\\\"\\$missing path:/etc/xdg/menus/kf5-applications.menu\\\"; " +
            "test -e /usr/lib/aarch64-linux-gnu/dri/virtio_gpu_dri.so || missing=\\\"\\$missing path:virtio_gpu_dri.so\\\"; " +
            "test -f \\\"\\$qml/QtQuick/Templates.2/qmldir\\\" || missing=\\\"\\$missing qml:QtQuick/Templates.2\\\"; " +
            "test -f \\\"\\$qml/QtGraphicalEffects/qmldir\\\" || missing=\\\"\\$missing qml:QtGraphicalEffects\\\"; " +
            "test -f \\\"\\$qml/org/kde/kirigami.2/qmldir\\\" || missing=\\\"\\$missing qml:org/kde/kirigami.2\\\"; " +
            "test -z \\\"\\$missing\\\" || { echo VESSEL_MISSING_COMPONENTS=\\\"\\$missing\\\"; exit 1; }"

''')

replace_between(rt,
    '    private fun packagePolicyCommand(): String =',
    '    private inner class PackageProgressReporter(',
'''    private fun packagePolicyCommand(): String = """
        install -d -m 755 /usr/sbin
        cat >/usr/sbin/policy-rc.d <<'VESSEL_POLICY'
        #!/bin/sh
        # Vessel UML uses a custom PID 1. Package maintainer scripts must not
        # auto-start system services while the persistent image is provisioned.
        exit 101
        VESSEL_POLICY
        chmod 0755 /usr/sbin/policy-rc.d
        install -d -m 755 /etc/initramfs-tools
        cat >/etc/initramfs-tools/update-initramfs.conf <<'VESSEL_INITRAMFS'
        # Vessel boots the host-provided UML kernel directly; generating a guest
        # initramfs is both useless and can stall package triggers for minutes.
        update_initramfs=no
        backup_initramfs=no
        VESSEL_INITRAMFS
    """.trimIndent()

''')

replace_once(rt,
    '"qml-module-org-kde-qqc2desktopstyle qml-module-org-kde-kirigami2 qml-module-qtquick-controls2 qml-module-qtquick-layouts qml-module-qtquick-window2 qml-module-qtquick2 " +',
    '"qml-module-org-kde-qqc2desktopstyle qml-module-org-kde-kirigami2 qml-module-org-kde-kitemmodels qml-module-org-kde-kquickcontrolsaddons " +\n            "qml-module-qtquick-controls qml-module-qtquick-controls2 qml-module-qtquick-layouts qml-module-qtquick-window2 qml-module-qtquick2 qml-module-qtquick-templates2 qml-module-qtgraphicaleffects plasma-integration libkf5service-data " +')

replace_once(rt, '''        val verify = guestBlocking(plasmaReadyCommand(), 30)
        check(verify.first == 0) { "Plasma packages installed, but required desktop/QML/icon components are still missing" }
        progress("plasma_ready", 71, "KDE Plasma workstation ready")
''', '''        progress("plasma_verify", 71, "Validating Plasma QML, menus and GPU runtime")
        val verify = guestBlocking(plasmaReadyCommand(), 30)
        val missing = verify.second.lineSequence()
            .firstOrNull { it.contains("VESSEL_MISSING_COMPONENTS=") }
            ?.substringAfter("VESSEL_MISSING_COMPONENTS=")?.trim().orEmpty()
        if (verify.first != 0) {
            append("[plasma] runtime validation failed ${verify.second.takeLast(6000)}\\n")
            error("Plasma runtime validation failed: ${missing.ifBlank { "see Runtime log" }}")
        }
        progress("plasma_ready", 72, "KDE Plasma workstation ready")
''')

replace_once(rt, '''        append("[input] Xorg/libinput attached all Vessel devices\\n")
        applyDisplayModeBlocking()
    }
''', '''        append("[input] Xorg/libinput attached all Vessel devices\\n")
        applyDisplayModeBlocking()
        val shellCheck = guestBlocking(
            "for i in \\$(seq 1 120); do pgrep -u vessel -x plasmashell >/dev/null && pgrep -u vessel -x kwin_x11 >/dev/null && break; sleep .1; done; " +
                "pgrep -u vessel -x plasmashell >/dev/null && pgrep -u vessel -x kwin_x11 >/dev/null || { tail -120 /tmp/vessel-plasma.log 2>/dev/null; exit 44; }; " +
                "sleep .5; if grep -Eqi 'FullRepresentation unavailable|NormalPage unavailable|module .* is not installed' /tmp/vessel-plasma.log 2>/dev/null; then tail -160 /tmp/vessel-plasma.log; exit 45; fi",
            30,
        )
        check(shellCheck.first == 0) { "Plasma shell/QML validation failed: ${shellCheck.second.takeLast(8000)}" }
    }
''')

svc = "app/src/main/java/com/example/dreamlinux/VmSessionService.kt"
replace_all(svc, 'v39-self-contained-ahb-syncfd-virtio-input-r6', 'v39-self-contained-ahb-syncfd-virtio-input-r7')
replace_once(svc, 'val vcpus: Int = 1,', 'val vcpus: Int = 6,')
replace_once(svc, '''            "kate" to "Kate",
            "libreoffice-writer" to "LibreOffice Writer",
            "vlc" to "VLC",
''', '''            "kate" to "Kate",
''')
replace_once(svc,
    '"qml-module-org-kde-kquickcontrolsaddons", "qml-module-qtquick-controls2", "qml-module-qtquick-layouts",\n            "qml-module-qtquick-window2", "qml-module-qtquick2", "breeze", "breeze-icon-theme", "hicolor-icon-theme",',
    '"qml-module-org-kde-kquickcontrolsaddons", "qml-module-qtquick-controls", "qml-module-qtquick-controls2", "qml-module-qtquick-layouts",\n            "qml-module-qtquick-window2", "qml-module-qtquick2", "qml-module-qtquick-templates2", "qml-module-qtgraphicaleffects",\n            "plasma-integration", "libkf5service-data", "breeze", "breeze-icon-theme", "hicolor-icon-theme",')
replace_once(svc, '                refreshApps("", "POPULAR", "All")\n                refreshSystemStats(silent = true)',
                  '                refreshSystemStats(silent = true)')

replace_between(svc,
    '    private suspend fun ensureWorkstation(op: Long) {',
    '    private suspend fun ensureDesktopProfile(op: Long) {',
'''    private suspend fun ensureWorkstation(op: Long) {
        if (op != operationGeneration) return
        state.value = state.value.copy(
            stage = "workstation_validation",
            progressPercent = 97,
            progressDetail = "Validating complete Plasma workstation",
            message = "Validating desktop components",
        )
        val packages = (DESKTOP_RUNTIME_PACKAGES + DEFAULT_APPS.keys).distinct()
        val packageWords = packages.joinToString(" ") { shellQuote(it) }
        val validation = runtime.guest(
            """
            set -e
            missing=''
            for p in $packageWords; do
              dpkg-query -W -f='${'$'}{Status}' "${'$'}p" 2>/dev/null | grep -q 'install ok installed' || missing="${'$'}missing ${'$'}p"
            done
            if [ -n "${'$'}missing" ]; then echo "VESSEL_MISSING_PACKAGES=${'$'}missing"; exit 31; fi
            test -f /etc/xdg/menus/kf5-applications.menu
            qml=/usr/lib/aarch64-linux-gnu/qt5/qml
            test -f "${'$'}qml/QtQuick/Templates.2/qmldir"
            test -f "${'$'}qml/QtGraphicalEffects/qmldir"
            test -f "${'$'}qml/org/kde/kirigami.2/qmldir"
            test -d /usr/share/icons/breeze
            test -x /usr/bin/systemsettings || test -x /usr/bin/systemsettings5
            install -d -o vessel -g vessel /home/vessel/Desktop /home/vessel/.config
            install -d -m 755 /var/cache/vessel
            printf '%s\\n' 'export MOZ_X11_EGL=1' >/etc/profile.d/vessel-gpu.sh
            chmod 0644 /etc/profile.d/vessel-gpu.sh
            update-desktop-database /usr/share/applications 2>/dev/null || true
            update-mime-database /usr/share/mime 2>/dev/null || true
            gtk-update-icon-cache -f -t /usr/share/icons/hicolor 2>/dev/null || true
            gtk-update-icon-cache -f -t /usr/share/icons/breeze 2>/dev/null || true
            uid=${'$'}(id -u vessel)
            su -l vessel -c "XDG_RUNTIME_DIR=/run/user/${'$'}uid xdg-user-dirs-update" 2>/dev/null || true
            su -l vessel -c "XDG_RUNTIME_DIR=/run/user/${'$'}uid kbuildsycoca5 --noincremental" >/tmp/vessel-sycoca.log 2>&1
            for f in firefox-esr org.kde.konsole org.kde.dolphin systemsettings; do
              src=/usr/share/applications/${'$'}f.desktop
              [ -f "${'$'}src" ] && install -m 755 -o vessel -g vessel "${'$'}src" /home/vessel/Desktop/ || true
            done
            echo VESSEL_WORKSTATION_READY
            """.trimIndent(),
            180,
        )
        if (!validation.optBoolean("ok")) {
            throw IllegalStateException("Workstation validation failed: ${validation.optString("output").takeLast(5000)}")
        }
    }

''')

replace_between(svc,
    '    private suspend fun ensureDesktopProfile(op: Long) {',
    '    fun stopVm() {',
'''    private suspend fun ensureDesktopProfile(op: Long) {
        if (op != operationGeneration) return
        state.value = state.value.copy(
            stage = "desktop_profile",
            progressPercent = 99,
            progressDetail = "Validating live Plasma shell and Kickoff QML",
            message = "Finishing desktop validation",
        )
        val profile = runtime.guest(
            """
            set -e
            marker=/home/vessel/.config/.vessel-workstation-2.1-alpha2
            uid=${'$'}(id -u vessel)
            test -f /etc/xdg/menus/kf5-applications.menu
            test -f /usr/share/plasma/plasmoids/org.kde.plasma.kickoff/contents/ui/FullRepresentation.qml
            test -f /usr/share/plasma/plasmoids/org.kde.plasma.kickoff/contents/ui/NormalPage.qml
            su -l vessel -c "XDG_RUNTIME_DIR=/run/user/${'$'}uid kbuildsycoca5 --noincremental" >/tmp/vessel-sycoca.log 2>&1
            ready=0
            for i in ${'$'}(seq 1 120); do
              if pgrep -u vessel -x plasmashell >/dev/null && pgrep -u vessel -x kwin_x11 >/dev/null; then ready=1; break; fi
              sleep .1
            done
            test "${'$'}ready" = 1
            sleep .5
            if grep -Eqi 'FullRepresentation unavailable|NormalPage unavailable|module .* is not installed' /tmp/vessel-plasma.log 2>/dev/null; then
              tail -160 /tmp/vessel-plasma.log
              exit 45
            fi
            touch "${'$'}marker"
            chown vessel:vessel "${'$'}marker"
            echo VESSEL_PROFILE_READY
            """.trimIndent(),
            60,
        )
        if (!profile.optBoolean("ok")) {
            throw IllegalStateException("Plasma profile/QML validation failed: ${profile.optString("output").takeLast(6000)}")
        }
    }

''')

replace_once(svc, '''        val op = nextOperation()
        state.value = state.value.copy(
''', '''        val op = nextOperation()
        appStore.value = AppStoreState()
        state.value = state.value.copy(
''')
replace_once(svc, '''        if (!state.value.running || !state.value.guestReady) {
            appStore.value = appStore.value.copy(error = "Start Linux to browse Debian apps")
            return
        }
''', '''        if (!state.value.running || !state.value.guestReady) {
            appStore.value = appStore.value.copy(loading = false, error = "Start Linux to browse Debian apps")
            return
        }
        if (state.value.busy || state.value.stage != "ready") {
            appStore.value = appStore.value.copy(loading = false, error = "Finish workstation setup before browsing apps")
            return
        }
''')
replace_once(svc, '            rm -f /var/cache/vessel/app-catalog-v2.json\n', '')

ui = "app/src/main/java/com/example/dreamlinux/VesselActivity.kt"
replace_once(ui, '''        LaunchedEffect(state.guestReady) {
            if (state.guestReady && store.apps.isEmpty() && !store.loading) VmSessionService.active?.refreshApps("", "POPULAR", "All")
        }
''', '''        LaunchedEffect(state.guestReady, state.busy, state.stage) {
            if (state.guestReady && !state.busy && state.stage == "ready" && store.apps.isEmpty() && !store.loading) {
                VmSessionService.active?.refreshApps("", "POPULAR", "All")
            }
        }
''')

presenter_kt = "app/src/main/java/com/example/dreamlinux/VesselWaylandPresenter.kt"
replace_once(presenter_kt, '            ahb == "presenting-ahardwarebuffer" -> "presenting-dmabuf-ahardwarebuffer"',
                          '            ahb == "presenting-ahardwarebuffer" -> "presenting-ahardwarebuffer"')
replace_once(presenter_kt, '        if (s.startsWith("presenting-dmabuf")) everPresented = true',
                          '        if (s.startsWith("presenting-ahardwarebuffer")) everPresented = true')

cpp = "app/src/main/cpp/vessel_ahb_presenter.cpp"
replace_once(cpp, '''    void surface_changed(uint32_t width, uint32_t height) {
        if (!width || !height) return;
        std::lock_guard<std::mutex> guard(lock_);
        surface_width_ = width;
        surface_height_ = height;
        if (device_ && (extent_.width != width || extent_.height != height)) {
            swapchain_dirty_ = true;
            logi("Surface extent changed to " + std::to_string(width) + "x" + std::to_string(height) + "; swapchain recreation queued");
        }
    }
''', '''    void surface_changed(uint32_t width, uint32_t height) {
        if (!width || !height) return;
        std::lock_guard<std::mutex> guard(lock_);
        const bool changed = surface_width_ != width || surface_height_ != height;
        surface_width_ = width;
        surface_height_ = height;
        if (device_ && (changed || extent_.width != width || extent_.height != height)) {
            swapchain_dirty_ = true;
            logi("Surface extent changed to " + std::to_string(width) + "x" + std::to_string(height) + "; recreating swapchain");
            if (recreate_swapchain_locked() && latest_slot_ < FRAME_SLOTS && sources_[latest_slot_].buffer) {
                if (present_locked(-1, 0, latest_slot_, 0, -1, false)) {
                    status_ = "presenting-ahardwarebuffer";
                    logi("repainted retained GPU frame after surface resize");
                }
            }
        }
    }
''')

rebuild = "tools/vessel_native/rebuild_vhost_gpu_ahb.sh"
replace_all(rebuild, 'v39-self-contained-ahb-syncfd-virtio-input-r6', 'v39-self-contained-ahb-syncfd-virtio-input-r7')
replace_once(rebuild, 'echo uml_vcpus=1', 'echo uml_vcpus=6')

wf = ".github/workflows/vessel-self-contained.yml"
replace_all(wf, '2.1.0-alpha1', '2.1.0-alpha2')
replace_all(wf, 'v39-self-contained-ahb-syncfd-virtio-input-r6', 'v39-self-contained-ahb-syncfd-virtio-input-r7')
replace_once(wf, "grep -F 'UML_VCPUS = 1' app/src/main/java/com/example/dreamlinux/VesselRuntimeController.kt",
                 "grep -F 'UML_VCPUS = 6' app/src/main/java/com/example/dreamlinux/VesselRuntimeController.kt")
replace_once(wf, "grep -F 'uml_vcpus=1' app/src/main/assets/vessel/runtime-build.txt",
                 "grep -F 'uml_vcpus=6' app/src/main/assets/vessel/runtime-build.txt")
replace_once(wf,
    "          grep -F 'kde-plasma-desktop' app/src/main/java/com/example/dreamlinux/VmSessionService.kt\n",
    "          grep -F 'kde-plasma-desktop' app/src/main/java/com/example/dreamlinux/VmSessionService.kt\n" +
    "          grep -F 'kf5-applications.menu' app/src/main/java/com/example/dreamlinux/VesselRuntimeController.kt\n" +
    "          grep -F 'update_initramfs=no' app/src/main/java/com/example/dreamlinux/VesselRuntimeController.kt\n" +
    "          grep -F 'qml-module-qtquick-templates2' app/src/main/java/com/example/dreamlinux/VesselRuntimeController.kt\n" +
    "          grep -F 'state.value.stage != \"ready\"' app/src/main/java/com/example/dreamlinux/VmSessionService.kt\n" +
    "          ! grep -Fq 'ARM64 SMP containment' app/src/main/java/com/example/dreamlinux/VesselRuntimeController.kt\n" +
    "          ! grep -Fq '/etc/xdg/menus/plasma-applications.menu' app/src/main/java/com/example/dreamlinux/VesselRuntimeController.kt\n" +
    "          ! grep -Fq '/etc/xdg/menus/plasma-applications.menu' app/src/main/java/com/example/dreamlinux/VmSessionService.kt\n")

svc_text = read(svc)
workstation = svc_text[svc_text.index('    private suspend fun ensureWorkstation'):svc_text.index('    private suspend fun ensureDesktopProfile')]
if 'app-catalog-v2.json' in workstation or 'apt-get ' in workstation:
    raise SystemExit('ensureWorkstation still mutates APT/AppStream state')
if '/etc/xdg/menus/plasma-applications.menu' in read(rt) or '/etc/xdg/menus/plasma-applications.menu' in svc_text:
    raise SystemExit('stale Plasma menu path survived')
if 'UML_VCPUS = 1' in read(rt):
    raise SystemExit('1-vCPU product default survived')
if '2.1.0-alpha1' in read("app/build.gradle.kts"):
    raise SystemExit('alpha1 version survived')

print('Vessel 2.1.0-alpha2 source migration complete')
