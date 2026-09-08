package com.example.dreamlinux

import java.io.InputStream

internal object ConsoleCapture {
    fun read(stream: InputStream, append: (String) -> Unit) {
        val reader = stream.reader(Charsets.UTF_8)
        val buffer = CharArray(4096)
        while (!Thread.currentThread().isInterrupted) {
            val count = reader.read(buffer)
            if (count < 0) break
            if (count > 0) append(String(buffer, 0, count))
        }
    }
}
