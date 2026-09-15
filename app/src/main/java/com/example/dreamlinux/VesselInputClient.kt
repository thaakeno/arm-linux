package com.example.dreamlinux

import kotlin.math.truncate

/** Android input goes directly to the in-process Vessel runtime; no loopback bridge. */
object VesselInputClient {
    private val lock = Any()
    private var fracX = 0f
    private var fracY = 0f
    private var wheelX = 0f
    private var wheelY = 0f

    private fun send(type: String, vararg values: Pair<String, Any>) {
        VmSessionService.active?.sendInput(type, mapOf(*values))
    }

    fun absolute(x: Float, y: Float, down: Boolean) = send(
        "abs",
        "x" to (x.coerceIn(0f, 1f) * 32767f).toInt(),
        "y" to (y.coerceIn(0f, 1f) * 32767f).toInt(),
        "down" to down,
    )

    fun relative(dx: Float, dy: Float): Pair<Int, Int> {
        val ix: Int; val iy: Int
        synchronized(lock) {
            fracX += dx; fracY += dy
            ix = truncate(fracX).toInt(); iy = truncate(fracY).toInt()
            fracX -= ix; fracY -= iy
        }
        if (ix != 0 || iy != 0) send("rel", "dx" to ix, "dy" to iy)
        return ix to iy
    }

    fun button(code: Int, down: Boolean) = send("btn", "code" to code, "down" to down)
    fun key(code: Int, down: Boolean) = send("key", "code" to code, "down" to down)

    fun scrollPrecise(x: Float, y: Float) {
        val ix: Int; val iy: Int
        synchronized(lock) {
            wheelX += x; wheelY += y
            ix = truncate(wheelX).toInt(); iy = truncate(wheelY).toInt()
            wheelX -= ix; wheelY -= iy
        }
        if (ix != 0 || iy != 0) send("scroll", "x" to ix, "y" to iy)
    }

    fun scroll(x: Int, y: Int) { if (x != 0 || y != 0) send("scroll", "x" to x, "y" to y) }

    private fun tap(code: Int, shift: Boolean = false) {
        if (shift) key(42, true); key(code, true); key(code, false); if (shift) key(42, false)
    }

    fun text(value: String) {
        value.forEach { c ->
            val l = c.lowercaseChar()
            val letter = when (l) {
                'a'->30;'b'->48;'c'->46;'d'->32;'e'->18;'f'->33;'g'->34;'h'->35;'i'->23;'j'->36;'k'->37;'l'->38;'m'->50;'n'->49;'o'->24;'p'->25;'q'->16;'r'->19;'s'->31;'t'->20;'u'->22;'v'->47;'w'->17;'x'->45;'y'->21;'z'->44;else->null
            }
            if (letter != null) { tap(letter, c.isUpperCase()); return@forEach }
            when(c){
                '1'->tap(2);'2'->tap(3);'3'->tap(4);'4'->tap(5);'5'->tap(6);'6'->tap(7);'7'->tap(8);'8'->tap(9);'9'->tap(10);'0'->tap(11)
                ' '->tap(57);'\n','\r'->tap(28);'\t'->tap(15);'-'->tap(12);'_'->tap(12,true);'='->tap(13);'+'->tap(13,true)
                '['->tap(26);'{'->tap(26,true);']'->tap(27);'}'->tap(27,true);';'->tap(39);':'->tap(39,true);'\''->tap(40);'"'->tap(40,true)
                '`'->tap(41);'~'->tap(41,true);'\\'->tap(43);'|'->tap(43,true);','->tap(51);'<'->tap(51,true);'.'->tap(52);'>'->tap(52,true);'/'->tap(53);'?'->tap(53,true)
                '!'->tap(2,true);'@'->tap(3,true);'#'->tap(4,true);'$'->tap(5,true);'%'->tap(6,true);'^'->tap(7,true);'&'->tap(8,true);'*'->tap(9,true);'('->tap(10,true);')'->tap(11,true)
            }
        }
    }
}
