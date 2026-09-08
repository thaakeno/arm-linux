package com.example.dreamlinux

import android.content.Context
import android.os.Process
import android.system.Os
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** Converts Google's current AVF Debian vm_config.json to hidden framework objects. */
internal object DebianAvfConfig {
    private const val BASE = "android.system.virtualmachine."

    fun build(
        context: Context,
        imageDir: File,
        vmName: String,
        width: Int,
        height: Int,
        dpi: Int,
        refreshRate: Int,
        log: (String) -> Unit,
    ): Any {
        val json = JSONObject(
            File(imageDir, "vm_config.json").readText()
                .replace("\$PAYLOAD_DIR", imageDir.path)
                .replace("\$APP_DATA_DIR", context.dataDir.path)
        )

        val customBuilderClass = Class.forName(BASE + "VirtualMachineCustomImageConfig\$Builder")
        val custom = customBuilderClass.getConstructor().newInstance()
        AvfReflect.call(custom, "setName", vmName)

        resolved(json, imageDir, "bootloader")?.let { AvfReflect.call(custom, "setBootloaderPath", it) }
        resolved(json, imageDir, "kernel")?.let { AvfReflect.call(custom, "setKernelPath", it) }
        resolved(json, imageDir, "initrd")?.let { AvfReflect.call(custom, "setInitrdPath", it) }
        json.optString("params", "")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .forEach { AvfReflect.call(custom, "addParam", it) }

        addDisks(custom, json.optJSONArray("disks") ?: JSONArray(), imageDir)

        // Do not add app-domain virtiofs shares from the Shizuku shell process. In AOSP's
        // SharedPath API, appDomain=true means crosvm is spawned from the caller's app context.
        // That is valid for the privileged Terminal app but SELinux denies executing
        // /apex/com.android.virt/bin/crosvm from uid=2000 shell. Keep Debian launch entirely
        // under VirtualizationService/virtmgr; shared folders can be added later through a
        // non-app-domain path once basic Debian boot/display is proven.
        if (json.optJSONArray("sharedPath") != null) {
            log("Skipping host shared paths for shell bridge; keeping crosvm under virtualizationservice")
        }

        val wantsNetwork = json.optBoolean("network", false)
        AvfReflect.callOptional(custom, "useNetwork", wantsNetwork)
        AvfReflect.callOptional(custom, "useAutoMemoryBalloon", json.optBoolean("auto_memory_balloon", true))

        val gpuBuilder = Class.forName(BASE + "VirtualMachineCustomImageConfig\$GpuConfig\$Builder")
            .getConstructor().newInstance()
        AvfReflect.call(gpuBuilder, "setBackend", "gfxstream")
        AvfReflect.callOptional(gpuBuilder, "setRendererUseEgl", false)
        AvfReflect.callOptional(gpuBuilder, "setRendererUseGles", false)
        AvfReflect.callOptional(gpuBuilder, "setRendererUseGlx", false)
        AvfReflect.callOptional(gpuBuilder, "setRendererUseSurfaceless", true)
        AvfReflect.callOptional(gpuBuilder, "setRendererUseVulkan", true)
        AvfReflect.callOptional(gpuBuilder, "setContextTypes", arrayOf("gfxstream-vulkan", "gfxstream-composer"))
        val gpu = AvfReflect.call(gpuBuilder, "build") ?: error("GpuConfig build returned null")
        AvfReflect.call(custom, "setGpuConfig", gpu)

        val displayBuilder = Class.forName(BASE + "VirtualMachineCustomImageConfig\$DisplayConfig\$Builder")
            .getConstructor().newInstance()
        AvfReflect.call(displayBuilder, "setWidth", width.coerceAtLeast(640))
        AvfReflect.call(displayBuilder, "setHeight", height.coerceAtLeast(480))
        AvfReflect.call(displayBuilder, "setHorizontalDpi", dpi.coerceIn(120, 640))
        AvfReflect.call(displayBuilder, "setVerticalDpi", dpi.coerceIn(120, 640))
        AvfReflect.call(displayBuilder, "setRefreshRate", refreshRate.coerceIn(30, 240))
        val display = AvfReflect.call(displayBuilder, "build") ?: error("DisplayConfig build returned null")
        AvfReflect.call(custom, "setDisplayConfig", display)
        AvfReflect.callOptional(custom, "useKeyboard", true)
        AvfReflect.callOptional(custom, "useMouse", true)
        AvfReflect.callOptional(custom, "useTouch", true)
        AvfReflect.callOptional(custom, "useTrackpad", true)

        val customConfig = AvfReflect.call(custom, "build") ?: error("CustomImageConfig build returned null")
        val vmConfigClass = Class.forName(BASE + "VirtualMachineConfig")
        val vmBuilder = Class.forName(BASE + "VirtualMachineConfig\$Builder")
            .getConstructor(Context::class.java).newInstance(context)

        // This POCO exposes only CAPABILITY_PROTECTED_VM. Google's downloadable Debian config
        // currently defaults to non-protected, which the device rejects before crosvm starts.
        // Force the custom Debian VM through the same protected AVF/Gunyah path proven by Gate A.
        AvfReflect.call(vmBuilder, "setProtectedVm", true)
        AvfReflect.call(vmBuilder, "setMemoryBytes", json.optLong("memory_mib", 4096L).coerceIn(1024L, 12288L) * 1024L * 1024L)

        val cpu = json.optString("cpu_topology", "match_host")
        val cpuValue = when (cpu) {
            "one_cpu" -> runCatching { vmConfigClass.getField("CPU_TOPOLOGY_ONE_CPU").getInt(null) }.getOrNull()
            else -> runCatching { vmConfigClass.getField("CPU_TOPOLOGY_MATCH_HOST").getInt(null) }.getOrNull()
        }
        if (cpuValue != null) AvfReflect.callOptional(vmBuilder, "setCpuTopology", cpuValue)

        val consoleInput = json.optString("console_input_device", "").takeIf { it.isNotBlank() }
        AvfReflect.callOptional(vmBuilder, "setConsoleInputDevice", consoleInput)
        val debugLevel = if (json.optBoolean("debuggable", true))
            vmConfigClass.getField("DEBUG_LEVEL_FULL").getInt(null)
        else vmConfigClass.getField("DEBUG_LEVEL_NONE").getInt(null)
        AvfReflect.call(vmBuilder, "setDebugLevel", debugLevel)
        AvfReflect.call(vmBuilder, "setCustomImageConfig", customConfig)
        AvfReflect.callOptional(vmBuilder, "setVmOutputCaptured", json.optBoolean("console_out", true))
        AvfReflect.callOptional(vmBuilder, "setVmConsoleInputSupported", consoleInput != null)
        AvfReflect.callOptional(vmBuilder, "setConnectVmConsole", json.optBoolean("connect_console", false))

        log("Debian config: protected=true sourceProtected=${json.optBoolean("protected", false)} network=$wantsNetwork gfxstream=true display=${width}x$height@$refreshRate dpi=$dpi")
        return AvfReflect.call(vmBuilder, "build") ?: error("VirtualMachineConfig build returned null")
    }

    private fun resolved(json: JSONObject, root: File, key: String): String? {
        val value = json.optString(key, "").trim()
        if (value.isBlank() || value == "null") return null
        return if (value.startsWith("/")) value else File(root, value).path
    }

    private fun resolvePath(value: String?, root: File): String? {
        val path = value?.trim().orEmpty()
        if (path.isBlank() || path == "null") return null
        return if (path.startsWith("/")) path else File(root, path).path
    }

    private fun addDisks(custom: Any, disks: JSONArray, root: File) {
        check(disks.length() > 0) { "Debian vm_config.json contains no disks" }
        val diskClass = Class.forName(BASE + "VirtualMachineCustomImageConfig\$Disk")
        val partitionClass = Class.forName(BASE + "VirtualMachineCustomImageConfig\$Partition")

        for (i in 0 until disks.length()) {
            val diskJson = disks.getJSONObject(i)
            val diskWritable = diskJson.optBoolean("writable", false)
            val image = resolvePath(diskJson.optString("image", null), root)
            val factory = if (diskWritable) "RWDisk" else "RODisk"
            val disk = AvfReflect.staticCall(diskClass, factory, image)
                ?: error("$factory returned null")

            val partitions = diskJson.optJSONArray("partitions")
            if (partitions != null) {
                for (j in 0 until partitions.length()) {
                    val p = partitions.getJSONObject(j)
                    val label = p.optString("label", null)
                    val path = resolvePath(p.optString("path", null), root)
                    val writable = diskWritable && p.optBoolean("writable", false)
                    val guid = p.optString("guid", null)
                    val ctor = partitionClass.constructors.firstOrNull { it.parameterCount == 4 }
                        ?: error("VirtualMachineCustomImageConfig.Partition constructor not found")
                    val partition = ctor.newInstance(label, path, writable, guid)
                    AvfReflect.call(disk, "addPartition", partition)
                }
            }
            AvfReflect.call(custom, "addDisk", disk)
        }
    }

    @Suppress("unused")
    private fun addSharedPaths(custom: Any, paths: JSONArray?, context: Context, log: (String) -> Unit) {
        if (paths == null) return
        val sharedClass = Class.forName(BASE + "VirtualMachineCustomImageConfig\$SharedPath")
        val constructor = sharedClass.constructors.firstOrNull { it.parameterCount == 10 }
            ?: run {
                log("SharedPath API shape unavailable; continuing without virtiofs")
                return
            }
        for (i in 0 until paths.length()) {
            val raw = paths.getJSONObject(i).optString("sharedPath", "")
            if (raw.isBlank()) continue
            if (raw.contains("/storage/emulated")) {
                log("Skipping optional shared storage mount; all-files access not requested")
                continue
            }
            val path = raw.replace("\$APP_DATA_DIR", context.dataDir.path)
            val socket = File(context.filesDir, "internal.virtiofs")
            if (socket.exists()) socket.delete()
            val hostUid = Process.myUid()
            val hostGid = Os.getgid()
            val shared = constructor.newInstance(
                path,
                hostUid,
                hostGid,
                0,
                0,
                7,
                "internal",
                "internal",
                false,
                "",
            )
            AvfReflect.call(custom, "addSharedPath", shared)
            log("Added non-app-domain virtiofs share $path uid=$hostUid gid=$hostGid")
        }
    }
}
