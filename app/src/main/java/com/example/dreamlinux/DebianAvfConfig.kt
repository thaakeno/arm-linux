package com.example.dreamlinux

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** Converts Google's official Ferrochrome image config into the hidden AVF custom-image API. */
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
        protectedVm: Boolean,
        requestGraphics: Boolean,
        log: (String) -> Unit,
    ): Any {
        val json = JSONObject(
            File(imageDir, "vm_config.json").readText()
                .replace("\$PAYLOAD_DIR", imageDir.path)
                .replace("\$APP_DATA_DIR", context.dataDir.path)
        )
        val sourceProtected = json.optBoolean("protected", false)
        log("[debian_config] sourceProtected=$sourceProtected requestedProtected=$protectedVm")

        val customBuilderClass = Class.forName(BASE + "VirtualMachineCustomImageConfig\$Builder")
        val custom = customBuilderClass.getConstructor().newInstance()
        AvfReflect.call(custom, "setName", vmName)

        resolved(json, imageDir, "bootloader")?.let { AvfReflect.call(custom, "setBootloaderPath", it) }
        resolved(json, imageDir, "kernel")?.let { AvfReflect.call(custom, "setKernelPath", it) }
        resolved(json, imageDir, "initrd")?.let { AvfReflect.call(custom, "setInitrdPath", it) }
        json.optString("params", "").split(Regex("\\s+")).filter { it.isNotBlank() }
            .forEach { AvfReflect.call(custom, "addParam", it) }

        addDisks(custom, json.optJSONArray("disks") ?: JSONArray(), imageDir)

        // Android's protected-VM path currently rejects the standard network device. Keep the
        // official image's setting on non-pVMs, but force networking off for the pVM probe.
        val requestedNetwork = json.optBoolean("network", true)
        val effectiveNetwork = requestedNetwork && !protectedVm
        AvfReflect.callOptional(custom, "useNetwork", effectiveNetwork)
        AvfReflect.callOptional(custom, "useAutoMemoryBalloon", json.optBoolean("auto_memory_balloon", true))

        if (requestGraphics) {
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
        }

        val customConfig = AvfReflect.call(custom, "build") ?: error("CustomImageConfig build returned null")
        val vmConfigClass = Class.forName(BASE + "VirtualMachineConfig")
        val vmBuilder = Class.forName(BASE + "VirtualMachineConfig\$Builder")
            .getConstructor(Context::class.java).newInstance(context)

        AvfReflect.call(vmBuilder, "setProtectedVm", protectedVm)
        val memoryMiB = json.optLong("memory_mib", 4096L).coerceIn(2048L, 8192L)
        AvfReflect.call(vmBuilder, "setMemoryBytes", memoryMiB * 1024L * 1024L)
        runCatching {
            val topo = vmConfigClass.getField("CPU_TOPOLOGY_MATCH_HOST").getInt(null)
            AvfReflect.callOptional(vmBuilder, "setCpuTopology", topo)
        }
        val console = json.optString("console_input_device", "ttyS0").ifBlank { "ttyS0" }
        AvfReflect.callOptional(vmBuilder, "setConsoleInputDevice", console)
        AvfReflect.call(vmBuilder, "setDebugLevel", vmConfigClass.getField("DEBUG_LEVEL_FULL").getInt(null))
        AvfReflect.call(vmBuilder, "setCustomImageConfig", customConfig)
        AvfReflect.callOptional(vmBuilder, "setVmOutputCaptured", true)
        AvfReflect.callOptional(vmBuilder, "setVmConsoleInputSupported", true)
        AvfReflect.callOptional(vmBuilder, "setConnectVmConsole", false)

        log(
            "[debian_config] protected=$protectedVm memoryMiB=$memoryMiB " +
                "network=$effectiveNetwork graphics=$requestGraphics display=${width}x$height@$refreshRate dpi=$dpi"
        )
        return AvfReflect.call(vmBuilder, "build") ?: error("VirtualMachineConfig build returned null")
    }

    private fun resolved(json: JSONObject, root: File, key: String): String? {
        val value = json.optString(key, "").trim()
        if (value.isBlank() || value == "null") return null
        return if (value.startsWith("/")) value else File(root, value).path
    }

    private fun resolve(value: String?, root: File): String? {
        val path = value?.trim().orEmpty()
        if (path.isBlank() || path == "null") return null
        return if (path.startsWith("/")) path else File(root, path).path
    }

    private fun addDisks(custom: Any, disks: JSONArray, root: File) {
        check(disks.length() > 0) { "Debian vm_config.json contains no disks" }
        val diskClass = Class.forName(BASE + "VirtualMachineCustomImageConfig\$Disk")
        val partitionClass = Class.forName(BASE + "VirtualMachineCustomImageConfig\$Partition")
        for (i in 0 until disks.length()) {
            val d = disks.getJSONObject(i)
            val writable = d.optBoolean("writable", false)
            val image = resolve(d.optString("image", null), root)
            val disk = AvfReflect.staticCall(diskClass, if (writable) "RWDisk" else "RODisk", image)
                ?: error("Disk factory returned null")
            d.optJSONArray("partitions")?.let { parts ->
                for (j in 0 until parts.length()) {
                    val p = parts.getJSONObject(j)
                    val ctor = partitionClass.constructors.firstOrNull { it.parameterCount == 4 }
                        ?: error("Partition constructor not found")
                    val part = ctor.newInstance(
                        p.optString("label", null),
                        resolve(p.optString("path", null), root),
                        writable && p.optBoolean("writable", false),
                        p.optString("guid", null),
                    )
                    AvfReflect.call(disk, "addPartition", part)
                }
            }
            AvfReflect.call(custom, "addDisk", disk)
        }
    }
}
