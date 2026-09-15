package com.example.dreamlinux

/**
 * Android input -> Vessel native sender -> vhost-user virtio-input -> Linux evdev/libinput.
 * No guest TCP socket, Python input agent, uinput injection, or networking dependency.
 */
object VesselVirtioInput {
    init { System.loadLibrary("vessel_wayland_presenter") }

    @JvmStatic private external fun nativeConfigure(touch: String, pointer: String, keyboard: String)
    @JvmStatic private external fun nativeAbsolute(x: Int, y: Int, down: Boolean): Boolean
    @JvmStatic private external fun nativeRelative(dx: Int, dy: Int): Boolean
    @JvmStatic private external fun nativeButton(code: Int, down: Boolean): Boolean
    @JvmStatic private external fun nativeScroll(x: Int, y: Int): Boolean
    @JvmStatic private external fun nativeKey(code: Int, down: Boolean): Boolean
    @JvmStatic private external fun nativeStatus(): String

    fun configure(touch: String, pointer: String, keyboard: String) = nativeConfigure(touch, pointer, keyboard)

    fun send(type: String, values: Map<String, Any>): Boolean = when (type) {
        "abs" -> nativeAbsolute(values.int("x"), values.int("y"), values.bool("down"))
        "rel" -> nativeRelative(values.int("dx"), values.int("dy"))
        "btn" -> nativeButton(values.int("code"), values.bool("down"))
        "scroll" -> nativeScroll(values.int("x"), values.int("y"))
        "key" -> nativeKey(values.int("code"), values.bool("down"))
        else -> false
    }

    fun status(): String = runCatching { nativeStatus() }.getOrElse { "virtio-input-error:${it.message}" }

    private fun Map<String, Any>.int(name: String): Int = (this[name] as? Number)?.toInt() ?: 0
    private fun Map<String, Any>.bool(name: String): Boolean = this[name] as? Boolean ?: false
}
