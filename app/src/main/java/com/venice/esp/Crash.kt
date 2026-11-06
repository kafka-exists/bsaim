package com.venice.esp

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

object Crash {
    private fun file(c: Context) = File(c.filesDir, "crash.txt")

    fun install(c: Context) {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                file(c).writeText("${e.javaClass.name}: ${e.message}\n\n$sw")
            }
            prev?.uncaughtException(t, e)
        }
    }

    fun read(c: Context): String? = runCatching {
        file(c).takeIf { it.exists() }?.readText()
    }.getOrNull()

    fun clear(c: Context) {
        runCatching { file(c).delete() }
    }
}
