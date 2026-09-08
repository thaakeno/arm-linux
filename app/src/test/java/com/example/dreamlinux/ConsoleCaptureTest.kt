package com.example.dreamlinux

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Test

class ConsoleCaptureTest {
    @Test fun splitReadsPreserveUtf8AndCommandMarkers() {
        val expected = "Booting Linux\r\n? Debian ready\n__DONE__:0\n"
        val stream = object : ByteArrayInputStream(expected.toByteArray(Charsets.UTF_8)) {
            override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, minOf(len, 1))
        }
        val actual = StringBuilder()
        ConsoleCapture.read(stream) { actual.append(it) }
        assertEquals(expected, actual.toString())
    }
    @Test fun emptyConsoleDoesNotInventLines() {
        val actual = StringBuilder()
        ConsoleCapture.read(ByteArrayInputStream(byteArrayOf())) { actual.append(it) }
        assertEquals("", actual.toString())
    }
}
