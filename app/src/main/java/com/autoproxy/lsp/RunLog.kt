package com.autoproxy.lsp

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object RunLog {
    private const val TAG = "AutoProxyLSP"
    private var file: File? = null
    private val format = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())

    @Synchronized
    fun init(context: Context) {
        file = File(context.filesDir, "run.log")
    }

    fun i(msg: String) = write("I", msg)

    fun w(msg: String) = write("W", msg)

    @Synchronized
    private fun write(level: String, msg: String) {
        Log.println(if (level == "W") Log.WARN else Log.INFO, TAG, msg)
        val f = file ?: return
        try {
            f.appendText("${format.format(Date())} [$level] $msg\n")
            if (f.length() > 200_000) {
                f.writeText(f.readLines().takeLast(300).joinToString("\n") + "\n")
            }
        } catch (_: Exception) {
        }
    }

    fun read(context: Context): String {
        val f = File(context.filesDir, "run.log")
        return try {
            if (f.exists()) f.readLines().takeLast(30).joinToString("\n") else "（暂无日志）"
        } catch (e: Exception) {
            "日志读取失败：${e.message}"
        }
    }

    @Synchronized
    fun clear(context: Context) {
        try {
            File(context.filesDir, "run.log").writeText("")
        } catch (_: Exception) {
        }
    }
}