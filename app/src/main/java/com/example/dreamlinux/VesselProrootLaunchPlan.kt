package com.example.dreamlinux

/** One host path exposed at a normal absolute path inside the glibc rootfs. */
data class VesselProrootBind(
    val hostPath: String,
    val guestPath: String,
) {
    init {
        require(hostPath.startsWith('/')) { "proroot bind host path must be absolute: $hostPath" }
        require(guestPath.startsWith('/')) { "proroot bind guest path must be absolute: $guestPath" }
        require('\u0000' !in hostPath && '\u0000' !in guestPath) { "proroot bind path contains NUL" }
        require(':' !in guestPath) { "proroot guest bind path cannot contain ':'" }
    }

    fun argument(): String = "$hostPath:$guestPath"
}

data class VesselProrootLaunchPlan(
    val argv: List<String>,
    val environment: Map<String, String>,
    val hostWorkingDirectory: String,
) {
    /**
     * Do not leak an Android/host LD_* setup into glibc programs. proroot finds
     * its companion DSOs through explicit PROROOT_* paths.
     */
    fun applyEnvironment(target: MutableMap<String, String>) {
        VesselProrootContract.HOST_LINKER_ENV.forEach(target::remove)
        target.putAll(environment)
    }
}

/**
 * Pure launch contract shared by the future terminal and desktop backends.
 * Keeping this free of Android classes makes the risky argv/environment shape
 * unit-testable without starting a Linux process.
 */
object VesselProrootContract {
    const val VERSION = "1.2.8"

    val REQUIRED_LIBRARIES = listOf(
        "libproroot.so",
        "libproroot-runtime.so",
        "libproroot-linker.so",
        "libproroot-stub-loader.so",
        "libproroot-bridge.so",
    )

    // Official v1.2.8 binaries. A later packaging phase must verify these before
    // release because modified proroot binaries may not be redistributed.
    val EXPECTED_SHA256 = linkedMapOf(
        "libproroot.so" to "a4e74d75b66cdc02b080adfe863dbf9951c3b30610d77beddc95488d5fe5de01",
        "libproroot-runtime.so" to "8c47a0a7db32d84c179ebb5bf3640f655a3181860ece5886ae44d92858730c34",
        "libproroot-bridge.so" to "1c5bc9537a270e8bf8b1c70222813f57b60b828bfb5503ddf8fe37685092de2f",
        "libproroot-linker.so" to "51a0ec5bfed00e572a0de09e22d9057e2befc386b78e426613d3e0ab03f4ecee",
        "libproroot-stub-loader.so" to "06c6624db3bdc45b9ced151cd781df439a37b47731d244b93e9d6a58cd48cde0",
    )

    val HOST_LINKER_ENV = setOf(
        "LD_PRELOAD",
        "LD_LIBRARY_PATH",
        "LD_AUDIT",
        "LD_DEBUG",
        "LD_CONFIG_FILE",
    )

    fun build(
        launcherPath: String,
        runtimeLibraryDir: String,
        rootfsPath: String,
        prorootTmpPath: String,
        hostWorkingDirectory: String,
        guestWorkingDirectory: String = "/root",
        binds: List<VesselProrootBind>,
        guestArgv: List<String>,
        diagnosticsLogPath: String? = null,
    ): VesselProrootLaunchPlan {
        require(launcherPath.startsWith('/'))
        require(runtimeLibraryDir.startsWith('/'))
        require(rootfsPath.startsWith('/'))
        require(prorootTmpPath.startsWith('/'))
        require(hostWorkingDirectory.startsWith('/'))
        require(guestWorkingDirectory.startsWith('/'))
        require(guestArgv.isNotEmpty()) { "guest argv must not be empty" }

        val argv = buildList {
            add(launcherPath)
            add("-r")
            add(rootfsPath)
            add("-0")
            // This is documented by upstream for Android and is also the DSHA
            // proroot path. It avoids hard-link failures without per-package hacks.
            add("--link2symlink")
            add("-w")
            add(guestWorkingDirectory)
            binds.forEach { bind ->
                add("-b")
                add(bind.argument())
            }
            addAll(guestArgv)
        }

        val env = linkedMapOf(
            "PROROOT_TMP_DIR" to prorootTmpPath,
            "PROROOT_LIB_PATH" to "$runtimeLibraryDir/libproroot-runtime.so",
            "PROROOT_LINKER_PATH" to "$runtimeLibraryDir/libproroot-linker.so",
            "PROROOT_STUB_LOADER" to "$runtimeLibraryDir/libproroot-stub-loader.so",
            "HOME" to "/root",
            "USER" to "root",
            "LOGNAME" to "root",
            "SHELL" to "/bin/bash",
            "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TMPDIR" to "/tmp",
            "XDG_RUNTIME_DIR" to "/run/user/0",
            "LANG" to "C.UTF-8",
            "LC_ALL" to "C.UTF-8",
            "TERM" to "xterm-256color",
            "COLORTERM" to "truecolor",
        )
        if (diagnosticsLogPath != null) {
            require(diagnosticsLogPath.startsWith('/'))
            env["PROROOT_LOG_APPEND"] = diagnosticsLogPath
        }

        return VesselProrootLaunchPlan(argv, env, hostWorkingDirectory)
    }

    fun shell(
        launcherPath: String,
        runtimeLibraryDir: String,
        rootfsPath: String,
        prorootTmpPath: String,
        hostWorkingDirectory: String,
        binds: List<VesselProrootBind>,
        command: String,
        diagnosticsLogPath: String? = null,
    ): VesselProrootLaunchPlan = build(
        launcherPath = launcherPath,
        runtimeLibraryDir = runtimeLibraryDir,
        rootfsPath = rootfsPath,
        prorootTmpPath = prorootTmpPath,
        hostWorkingDirectory = hostWorkingDirectory,
        binds = binds,
        guestArgv = listOf("/bin/bash", "-lc", command),
        diagnosticsLogPath = diagnosticsLogPath,
    )
}
