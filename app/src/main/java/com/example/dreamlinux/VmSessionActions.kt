package com.example.dreamlinux

/** Linux desktop actions only. No game/benchmark activities live in Vessel. */
fun VmSessionService.launchFirefox() = debianConsole(
    "export DISPLAY=:1; export XDG_RUNTIME_DIR=/tmp/runtime-root; " +
        "[ -f /root/venus-env.sh ] && . /root/venus-env.sh || true; " +
        "export VTEST_SOCKET_NAME=/tmp/.venus_test; export VK_DRIVER_FILES=/root/virtio-wsi-test.json; " +
        "setsid -f firefox-esr --no-remote >/tmp/vessel-firefox.log 2>&1 </dev/null; echo FIREFOX_LAUNCHED"
)

fun VmSessionService.launchDesktopApp(name: String) {
    val safe = when (name) {
        "dolphin" -> "dolphin"
        "kate" -> "kate"
        "okular" -> "okular"
        "kcalc" -> "kcalc"
        else -> return
    }
    debianConsole(
        "export DISPLAY=:1; export XDG_RUNTIME_DIR=/tmp/runtime-root; " +
            "setsid -f $safe >/tmp/vessel-$safe.log 2>&1 </dev/null; echo ${safe.uppercase()}_LAUNCHED"
    )
}

/** Vulkan is retained as a Linux diagnostics probe, not as an APK demo/game. */
fun VmSessionService.runGuestVulkanProbe() = debianConsole(
    "[ -f /root/venus-env.sh ] && . /root/venus-env.sh || true; " +
        "export VTEST_SOCKET_NAME=/tmp/.venus_test; export VK_DRIVER_FILES=/root/virtio-wsi-test.json; " +
        "vulkaninfo --summary 2>&1 | head -n 90"
)
