package com.example.dreamlinux

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

fun VmSessionService.runVulkan3DTest() {
    debianConsole(
        "p=$(pgrep -x plasmashell | head -n1 || true); " +
            "[ -n \"\$p\" ] || { echo PLASMA_NOT_RUNNING; exit 2; }; " +
            "export DISPLAY=$(tr '\\0' '\\n' </proc/\$p/environ | sed -n 's/^DISPLAY=//p' | head -n1); " +
            "export DBUS_SESSION_BUS_ADDRESS=$(tr '\\0' '\\n' </proc/\$p/environ | sed -n 's/^DBUS_SESSION_BUS_ADDRESS=//p' | head -n1); " +
            "export XDG_RUNTIME_DIR=$(tr '\\0' '\\n' </proc/\$p/environ | sed -n 's/^XDG_RUNTIME_DIR=//p' | head -n1); " +
            "[ -f /root/venus-env.sh ] && . /root/venus-env.sh || true; " +
            "export VTEST_SOCKET_NAME=/tmp/.venus_test; export VK_DRIVER_FILES=/root/virtio-wsi-test.json; " +
            "command -v vulkaninfo >/dev/null 2>&1 || { echo VULKAN_TOOLS_MISSING; exit 4; }; " +
            "echo '[vessel-3d] Vulkan summary'; vulkaninfo --summary 2>&1 | head -n 90; " +
            "echo '[vessel-3d] launching vkcube'; setsid -f vkcube >/tmp/vessel-vkcube.log 2>&1 </dev/null; echo VKCUBE_LAUNCHED"
    )
}
