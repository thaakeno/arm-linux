package com.example.dreamlinux

import android.content.Context
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
        requestGraphics: Boolean = true,
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

        // The Shizuku bridge runs as uid=2000. App-domain SharedPath asks AVF to spawn crosvm
        // from the caller domain, which SELinux rejects on production builds. Keep crosvm under
        // virtualizationservice/virtmgr until the guest itself is proven stable.
        if (json.optJSONArray("sharedPath") != null) {
            log("Skipping host shared paths for shell bridge; crosvm stays under virtualizationservice")
        }

        // This target reports CAPABILITY_PROTECTED_VM only. AVF explicitly rejects the ordinary
        // TAP/network feature for pVMs, so never inherit network=true from Google's non-pVM image.
        AvfReflect.call(custom, "useNetwork", false)
        log("Protected Debian: ordinary AVF network disabled (pVM limitation)")
        AvfReflect.callOptional(custom, "useAutoMemoryBalloon", json.optBoolean("auto_memory_balloon", true))

        // Google's current Debian image defaults to the 2D backend. Use that as the safe desktop
        // path on this Snapdragon device: gfxstream/Vulkan has already crashed crosvm in
        // libgfxstream_backend during VkEmulation initialization. Hardware acceleration remains an
        // explicit future/experimental path and is never reported as proven here.
        val graphicsMode = if (requestGraphics) "2d" else "none"
        if (requestGraphics) {
            val gpuBuilder = Class.forName(BASE + "VirtualMachineCustomImageConfig\$GpuConfig\$Builder")
                .getConstructor().newInstance()
            AvfReflect.call(gpuBuilder, "setBackend", "2d")
            val gpu = AvfReflect.call(gpuBuilder, "build") ?: error("GpuConfig build returned null")
            AvfReflect.call(custom, "setGpuConfig", gpu)

            val displayBuilder = Class.forName(BASE + "VirtualMachineCustomImageConfig\$DisplayConfig\$Builder")
                .getConstructor().newInstance()
            AvfReflect.call(displayBuilder, "setWidth", width.coerceAtLeast(640))
            AvfReflect.call(displayBuilder, "setHeight", height.coerceAtLeast(480))
            AvfReflect.call(displayBuilder, "setHorizontalDpi", dpi.coerceIn(120, 640))
            AvfReflect.call(displayBuilder, "setVerticalDpi", dpi.coerceIn(120, 640))
            AvfReflect.call(displayBuilder, "setRefreshRate", refreshRate.coerceIn(30, 120))
            val display = AvfReflect.call(displayBuilder, "build") ?: error("DisplayConfig build returned null")
            AvfReflect.call(custom, "setDisplayConfig", display)
            AvfReflect.callOptional(custom, "useKeyboard", true)
            AvfReflect.callOptional(custom, "useMouse", true)
            AvfReflect.callOptional(custom, "useTouch", true)
            AvfReflect.callOptional(custom, "useTrackpad", true)
        }

        val customConfig = AvfReflect.call(custom, "build") ?: error("CustomImageConfig build returned null")
        val vmConfigClass = Class.forName(BASE + "VirtualMachineConfig")
        val vmBuilder = Class.forName(BASE + "VirtualMachineConfig\$Builder")
            .getConstructor(Context::class.java).newInstance(context)

        // Qualcomm exposes protected AVF but not non-protected AVF on this target. Force the guest
        // through pVM/Gunyah and let the runtime tell us if the Debian kernel itself is pVM-safe.
        AvfReflect.call(vmBuilder, "setProtectedVm", true)
        AvfReflect.call(
            vmBuilder,
            "setMemoryBytes",
            json.optLong("memory_mib", 4096L).coerceIn(1024L, 12288L) * 1024L * 1024L,
        )

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

        log(
            "Debian config: protected=true sourceProtected=${json.optBoolean("protected", false)} " +
                "network=false graphics=$graphicsMode display=${width}x$height@$refreshRate dpi=$dpi"
        )
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
}
