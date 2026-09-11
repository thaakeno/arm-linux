package com.example.dreamlinux

import android.content.Intent

/** Desktop actions kept outside the service so the UI can launch apps without widening the RPC protocol. */
fun VmSessionService.launchFirefox() {
    debianConsole(
        "p=$(pgrep -x plasmashell | head -n1 || true); " +
            "[ -n \"\$p\" ] || { echo PLASMA_NOT_RUNNING; exit 2; }; " +
            "export DISPLAY=$(tr '\\0' '\\n' </proc/\$p/environ | sed -n 's/^DISPLAY=//p' | head -n1); " +
            "export DBUS_SESSION_BUS_ADDRESS=$(tr '\\0' '\\n' </proc/\$p/environ | sed -n 's/^DBUS_SESSION_BUS_ADDRESS=//p' | head -n1); " +
            "export XDG_RUNTIME_DIR=$(tr '\\0' '\\n' </proc/\$p/environ | sed -n 's/^XDG_RUNTIME_DIR=//p' | head -n1); " +
            "[ -f /root/venus-env.sh ] && . /root/venus-env.sh || true; " +
            "export VTEST_SOCKET_NAME=/tmp/.venus_test; export VK_DRIVER_FILES=/root/virtio-wsi-test.json; " +
            "command -v firefox-esr >/dev/null 2>&1 || { echo FIREFOX_MISSING; exit 3; }; " +
            "setsid -f firefox-esr >/tmp/vessel-firefox.log 2>&1 </dev/null; echo FIREFOX_LAUNCHED"
    )
}

/**
 * Launch the post-VNC display benchmark. This is intentionally a native Android SurfaceView path,
 * so the benchmark is not polluted by RFB decode, Termux:X11, XCB PutImage or guest framebuffer
 * copies. The Linux/Venus scanout will attach to the same Surface path as the native bridge lands.
 */
fun VmSessionService.runVulkan3DTest() {
    startActivity(
        Intent(this, NativeCubeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    )
}

/** Keep the old guest-side Vulkan probe available for diagnostics without making it the UI test. */
fun VmSessionService.runGuestVulkanProbe() {
    debianConsole(
        "[ -f /root/venus-env.sh ] && . /root/venus-env.sh || true; " +
            "export VTEST_SOCKET_NAME=/tmp/.venus_test; export VK_DRIVER_FILES=/root/virtio-wsi-test.json; " +
            "vulkaninfo --summary 2>&1 | head -n 90"
    )
}
