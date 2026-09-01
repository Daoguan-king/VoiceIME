package com.voiceime

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 轻量应用内日志：内存环形缓冲（主界面"日志"按钮查看）+
 * 追加写入 filesDir/logs/voiceime.log（重启不丢）。
 * 与系统 Logcat 同步输出，方便 adb 排查。
 */
object AppLog {
    private const val TAG = "VoiceIme"
    private const val MAX_ENTRIES = 400
    private const val MAX_FILE_BYTES = 1024L * 1024L // 日志文件 1MB 轮转

    private val entries = ArrayDeque<String>()
    private var file: File? = null
    // SimpleDateFormat 非线程安全：用 ThreadLocal 隔离（录音 IO 线程与主线程并发写日志）
    private val fmt = ThreadLocal.withInitial { SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US) }

    fun init(context: Context) {
        if (file == null) {
            file = File(context.filesDir, "logs/voiceime.log")
        }
    }

    fun i(tag: String, msg: String) = log('I', tag, msg)
    fun w(tag: String, msg: String) = log('W', tag, msg)
    fun e(tag: String, msg: String) = log('E', tag, msg)

    /** 最近日志（主界面展示，最新的在最后） */
    fun snapshot(): List<String> = synchronized(entries) { entries.toList() }

    fun clear() {
        synchronized(entries) { entries.clear() }
        file?.delete()
    }

    private fun log(level: Char, tag: String, msg: String) {
        val line = fmt.get().format(Date()) + " " + level + " " + tag + ": " + msg
        // 内存缓冲与文件写入在同一把锁内串行化，避免多线程交错
        synchronized(entries) {
            entries.addLast(line)
            while (entries.size > MAX_ENTRIES) entries.removeFirst()
            writeFileLocked(line)
        }
        when (level) {
            'I' -> Log.i(tag, msg)
            'W' -> Log.w(tag, msg)
            else -> Log.e(tag, msg)
        }
    }

    /** 追加写文件（须在 synchronized(entries) 内调用）；超过 1MB 自动轮转 */
    private fun writeFileLocked(line: String) {
        try {
            val f = file ?: return
            f.parentFile?.mkdirs()
            if (f.exists() && f.length() > MAX_FILE_BYTES) {
                val old = File(f.parentFile, "voiceime.log.old")
                old.delete()
                f.renameTo(old)
            }
            f.appendText(line + "\n")
        } catch (_: Throwable) {
            // 文件写入失败不影响功能
        }
    }
}
