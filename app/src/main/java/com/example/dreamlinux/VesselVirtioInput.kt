package com.example.dreamlinux

import kotlin.math.truncate

/**
 * Protocol 39 Android input -> native sender -> vhost-user virtio-input -> Linux evdev/libinput.
 * No guest TCP socket, Python input agent, uinput injection, or networking dependency.
 */
object VesselVirtioInput {
    init { System.loadLibrary("vessel_wayland_presenter") }

    private val fractionLock = Any()
    private var fracX = 0f
    private var fracY = 0f
    private var wheelX = 0f
    private var wheelY = 0f

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

    fun absoluteNormalized(x: Float, y: Float, down: Boolean): Boolean = nativeAbsolute(
        (x.coerceIn(0f, 1f) * 32767f).toInt(),
        (y.coerceIn(0f, 1f) * 32767f).toInt(),
        down,
    )

    fun relative(dx: Float, dy: Float): Boolean {
        val ix: Int
        val iy: Int
        synchronized(fractionLock) {
            fracX += dx
            fracY += dy
            ix = truncate(fracX).toInt()
            iy = truncate(fracY).toInt()
            fracX -= ix
            fracY -= iy
        }
        return (ix == 0 && iy == 0) || nativeRelative(ix, iy)
    }

    fun button(code: Int, down: Boolean): Boolean = nativeButton(code, down)
    fun key(code: Int, down: Boolean): Boolean = nativeKey(code, down)

    fun scrollPrecise(x: Float, y: Float): Boolean {
        val ix: Int
        val iy: Int
        synchronized(fractionLock) {
            wheelX += x
            wheelY += y
            ix = truncate(wheelX).toInt()
            iy = truncate(wheelY).toInt()
            wheelX -= ix
            wheelY -= iy
        }
        return (ix == 0 && iy == 0) || nativeScroll(ix, iy)
    }

    fun tapKey(code: Int) {
        nativeKey(code, true)
        nativeKey(code, false)
    }

    private fun tap(code: Int, shift: Boolean = false) {
        if (shift) nativeKey(42, true)
        nativeKey(code, true)
        nativeKey(code, false)
        if (shift) nativeKey(42, false)
    }

    fun text(value: String) {
        value.forEach { c ->
            val letter = when (c.lowercaseChar()) {
                'a'->30;'b'->48;'c'->46;'d'->32;'e'->18;'f'->33;'g'->34;'h'->35;'i'->23;'j'->36;'k'->37;'l'->38;'m'->50
                'n'->49;'o'->24;'p'->25;'q'->16;'r'->19;'s'->31;'t'->20;'u'->22;'v'->47;'w'->17;'x'->45;'y'->21;'z'->44
                else -> null
            }
            if (letter != null) {
                tap(letter, c.isUpperCase())
                return@forEach
            }
            when (c) {
                '1'->tap(2);'2'->tap(3);'3'->tap(4);'4'->tap(5);'5'->tap(6);'6'->tap(7);'7'->tap(8);'8'->tap(9);'9'->tap(10);'0'->tap(11)
                ' '->tap(57);'\n','\r'->tap(28);'\t'->tap(15);'-'->tap(12);'_'->tap(12,true);'='->tap(13);'+'->tap(13,true)
                '['->tap(26);'{'->tap(26,true);']'->tap(27);'}'->tap(27,true);';'->tap(39);':'->tap(39,true);'\''->tap(40);'"'->tap(40,true)
                '`'->tap(41);'~'->tap(41,true);'\\'->tap(43);'|'->tap(43,true);','->tap(51);'<'->tap(51,true);'.'->tap(52);'>'->tap(52,true);'/'->tap(53);'?'->tap(53,true)
                '!'->tap(2,true);'@'->tap(3,true);'#'->tap(4,true);'$'->tap(5,true);'%'->tap(6,true);'^'->tap(7,true);'&'->tap(8,true);'*'->tap(9,true);'('->tap(10,true);')'->tap(11,true)
            }
        }
    }

    fun resetFractions() = synchronized(fractionLock) {
        fracX = 0f; fracY = 0f; wheelX = 0f; wheelY = 0f
    }

    fun status(): String = runCatching { nativeStatus() }.getOrElse { "virtio-input-error:${it.message}" }

    private fun Map<String, Any>.int(name: String): Int = (this[name] as? Number)?.toInt() ?: 0
    private fun Map<String, Any>.bool(name: String): Boolean = this[name] as? Boolean ?: false
}
