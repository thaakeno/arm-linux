package com.example.dreamlinux

import android.app.*
import android.content.*
import android.os.*
import android.content.pm.PackageManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import rikka.shizuku.Shizuku

data class SessionState(
    val connected: Boolean = false,
    val running: Boolean = false,
    val cid: Int = -1,
    val name: String = "",
    val console: String = "",
    val terminal: String = "",
    val message: String = "Connect Shizuku to begin",
    val busy: Boolean = false,
    val vmApiInit: String = "PENDING",
    val vmDataDir: String = "",
    val vmCreation: String = "PENDING",
    val vmBoot: String = "PENDING",
    val connectVsock: String = "PENDING",
    val vsockFdReceived: String = "PENDING",
    val adbHandshake: String = "PENDING",
    val guestCommand: String = "PENDING",
    val reconnect: String = "NOT TESTED",
    val failureStage: String = "none"
)

class VmSessionService : Service() {
    companion object {
        val state = MutableStateFlow(SessionState())
        var active: VmSessionService? = null
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var bridge: IVmBridge? = null
    private var successfulCommands = 0
    private var reconnectProbeArmed = false

    private val args by lazy {
        Shizuku.UserServiceArgs(ComponentName(this, VmBridge::class.java))
            .daemon(false)
            .processNameSuffix("vm_bridge")
            .debuggable(true)
            .version(6)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            bridge = IVmBridge.Stub.asInterface(binder)
            state.value = state.value.copy(connected = true, message = "Managed AVF bridge ready")
        }

        override fun onServiceDisconnected(name: ComponentName) {
            bridge = null
            state.value = state.value.copy(
                connected = false,
                running = false,
                cid = -1,
                message = "Shizuku disconnected. Managed VM state unknown; VM data retained."
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        active = this
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("vm", "Linux session", NotificationManager.IMPORTANCE_LOW)
        )
        val intent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        startForeground(
            1,
            Notification.Builder(this, "vm")
                .setContentTitle("DEV 2 LINUX")
                .setContentText("Managed AVF session")
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .setContentIntent(intent)
                .build()
        )

        scope.launch {
            state.collect { current ->
                withContext(Dispatchers.IO) {
                    java.io.File(filesDir, "verification.json").writeText(
                        JSONObject()
                            .put("source", "device-runtime")
                            .put("running", current.running)
                            .put("name", current.name)
                            .put("vm_api_init", current.vmApiInit)
                            .put("vm_data_dir", current.vmDataDir)
                            .put("vm_creation", current.vmCreation)
                            .put("vm_boot", current.vmBoot)
                            .put("connect_vsock", current.connectVsock)
                            .put("vsock_fd_received", current.vsockFdReceived)
                            .put("adb_handshake", current.adbHandshake)
                            .put("guest_command", current.guestCommand)
                            .put("reconnect", current.reconnect)
                            .put("failure_stage", current.failureStage)
                            .put("debian", "NOT TESTED")
                            .put("gles", "NOT TESTED")
                            .put("console", current.console)
                            .put("terminal", current.terminal)
                            .toString(2)
                    )
                }
            }
        }

        scope.launch {
            while (isActive) {
                delay(2000)
                if (bridge != null && !state.value.busy) refresh()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (bridge == null) {
            try {
                check(Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                    "Authorize Shizuku first"
                }
                Shizuku.bindUserService(args, connection)
            } catch (e: Exception) {
                state.value = state.value.copy(message = e.message ?: "Shizuku unavailable")
            }
        }
        return START_NOT_STICKY
    }

    private fun applyStatus(raw: String) {
        val obj = JSONObject(raw)
        state.value = state.value.copy(
            running = obj.getBoolean("running"),
            cid = obj.optInt("cid", -1),
            name = obj.getString("name"),
            console = obj.optString("log"),
            vmApiInit = obj.optString("vmApiInit", state.value.vmApiInit),
            vmDataDir = obj.optString("vmDataDir", state.value.vmDataDir),
            vmCreation = obj.optString("vmCreation", state.value.vmCreation),
            vmBoot = obj.optString("vmBoot", state.value.vmBoot),
            connectVsock = obj.optString("connectVsock", state.value.connectVsock),
            vsockFdReceived = obj.optString("vsockFdReceived", state.value.vsockFdReceived),
            adbHandshake = obj.optString("adbHandshake", state.value.adbHandshake),
            guestCommand = obj.optString("guestCommand", state.value.guestCommand),
            failureStage = obj.optString("failureStage", state.value.failureStage)
        )
    }

    private suspend fun bridgeString(stage: String, call: () -> String?): String {
        val raw: String? = withContext(Dispatchers.IO) { call() }
        return raw ?: throw IllegalStateException("$stage: bridge returned null instead of a diagnostic result")
    }

    private suspend fun refresh() {
        try {
            val b = bridge ?: return
            applyStatus(bridgeString("status") { b.status() })
        } catch (e: Exception) {
            state.value = state.value.copy(message = e.message ?: "Status unavailable")
        }
    }

    fun startVm() = operation { b ->
        applyStatus(bridgeString("vm_boot") { b.startVm() })
        state.value = state.value.copy(
            reconnect = if (reconnectProbeArmed) "PENDING" else state.value.reconnect,
            message = if (reconnectProbeArmed) {
                "VM restarted. Run a guest command to complete reconnect verification."
            } else {
                "Managed Microdroid is running. Test a guest command next."
            }
        )
    }

    fun stopVm() = operation { b ->
        applyStatus(bridgeString("vm_stop") { b.stopVm() })
        if (successfulCommands > 0) reconnectProbeArmed = true
        state.value = state.value.copy(
            reconnect = if (reconnectProbeArmed) "PENDING" else state.value.reconnect,
            message = "Managed VM stopped; VM data retained"
        )
    }

    fun shell(command: String) = operation { b ->
        val reply = bridgeString("guest_command") { b.guestShell(command) }
        val result = JSONObject(reply)
        check(result.getBoolean("ok")) { result.optString("error", "Guest command failed") }
        val output = result.getString("output")
        successfulCommands++
        refresh()
        val reconnectPassed = reconnectProbeArmed
        if (reconnectPassed) reconnectProbeArmed = false
        state.value = state.value.copy(
            terminal = (state.value.terminal + "\n$ $command\n" + output).takeLast(60000),
            reconnect = if (reconnectPassed) "PASS" else state.value.reconnect,
            message = if (reconnectPassed) {
                "Reconnect verified: guest command succeeded after VM restart."
            } else {
                "Guest command completed through sanctioned vsock FD"
            }
        )
    }

    private fun operation(block: suspend (IVmBridge) -> Unit) {
        if (state.value.busy) return
        val b = bridge ?: return
        state.value = state.value.copy(busy = true)
        scope.launch {
            try {
                block(b)
            } catch (e: Exception) {
                val error = e.message ?: e.javaClass.simpleName
                state.value = state.value.copy(
                    message = error,
                    terminal = (state.value.terminal + "\nERROR: $error\n").takeLast(60000)
                )
                refresh()
            } finally {
                state.value = state.value.copy(busy = false)
            }
        }
    }

    override fun onDestroy() {
        active = null
        scope.cancel()
        if (bridge != null) {
            try { Shizuku.unbindUserService(args, connection, true) } catch (_: Exception) { }
        }
        bridge = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
