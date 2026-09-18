package com.example.dreamlinux

/**
 * Extra Vessel-side API exported by the same libtermux.so that implements the
 * terminal-emulator JNI ABI. The PTY child records /proc/self/stat after
 * setsid() and before exec; Java claims that immutable birth identity here.
 */
internal object VesselPtyNative {
    init {
        System.loadLibrary("termux")
    }

    private external fun nativeTakeIdentity(pid: Int): String?

    fun takeIdentity(pid: Int): String? =
        if (pid > 1) nativeTakeIdentity(pid) else null
}
