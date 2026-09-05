package com.voiceime

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** 日志级别 */
enum class LogLevel(val letter: Char) {
    INFO('I'),
    WARN('W'),
    ERROR('E'),
}

/** 一条结构化日志（ts 为毫秒时间戳；回放自磁盘的历史条目 ts 不含年份，仅用于显示） */
data class LogEntry(
    val ts: Long,
    val level: LogLevel,
    val tag: String,
    val msg: String,
)

/**
 * 轻量应用内日志（持久化 + 异步写盘 + 崩溃捕获）：
 * - 结构化条目存内存环形缓冲，主界面"日志"按钮查看，支持按级别过滤；
 * - 异步单协程追加写入 filesDir/logs/voiceime.log：常驻文件句柄 + 每行 flush，
 *   调用线程（含录音/解码线程）永不阻塞；洪峰时 DROP_OLDEST 保最新；
 * - 启动时从磁盘回放最近日志：重启/崩溃后 UI 仍能看到历史；
 * - 1MB × 3 份轮转（voiceime.log / .1 / .2）；
 * - UncaughtExceptionHandler 把崩溃栈 + 最近日志同步写入
 *   logs/crash-<ts>.log（保留最近 5 份）；
 * - 与系统 Logcat 同步输出，方便 adb 排查。
 *
 * 文件行格式（回放按此解析；多行消息的续行并入上一条）：
 *   MM-dd HH:mm:ss.SSS I Tag: message
 */
object AppLog {
    private const val TAG = "VoiceIme"
    private const val MAX_ENTRIES = 2000
    private const val MAX_FILE_BYTES = 1024L * 1024L // 1MB 轮转
    private const val ROLL_FILES = 3
    private const val REPLAY_LINES = 800
    private const val MAX_CRASH_FILES = 5

    private val lineRegex =
        Regex("^(\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}) ([IWE]) ([^:]+): (.*)$")

    private val entries = ArrayDeque<LogEntry>()
    private val entriesLock = Any()

    /** 进程生命周期 scope：写盘协程常驻，不随任何组件销毁 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 异步写盘队列；DROP_OLDEST 保证 trySend 永不阻塞且洪峰时丢最旧 */
    private val channel = Channel<String>(capacity = 1024, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private val fileLock = Any()
    private var logDir: File? = null
    private var out: FileOutputStream? = null

    @Volatile
    private var started = false

    // SimpleDateFormat 非线程安全：用 ThreadLocal 隔离（录音 IO 线程与主线程并发写日志）
    private val fmt = ThreadLocal.withInitial { SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US) }

    fun init(context: Context) {
        val app = context.applicationContext
        logDir = File(app.filesDir, "logs")
        synchronized(this) {
            if (started) return
            started = true
        }
        replayFromDisk()
        installCrashHandler()
        startWriter()
        i(TAG, "──── process start (crash handler installed) ────")
    }

    fun i(tag: String, msg: String) = log(LogLevel.INFO, tag, msg)

    fun w(tag: String, msg: String, tr: Throwable? = null) = log(LogLevel.WARN, tag, msg, tr)

    fun e(tag: String, msg: String, tr: Throwable? = null) = log(LogLevel.ERROR, tag, msg, tr)

    fun log(level: LogLevel, tag: String, msg: String, tr: Throwable? = null) {
        val text = if (tr != null) msg + "\n" + Log.getStackTraceString(tr) else msg
        val entry = LogEntry(System.currentTimeMillis(), level, tag, text)
        synchronized(entriesLock) {
            entries.addLast(entry)
            while (entries.size > MAX_ENTRIES) entries.removeFirst()
        }
        channel.trySend(formatEntry(entry))
        when (level) {
            LogLevel.INFO -> Log.i(tag, msg, tr)
            LogLevel.WARN -> Log.w(tag, msg, tr)
            LogLevel.ERROR -> Log.e(tag, msg, tr)
        }
    }

    /** 单条日志格式化为文本行（文件与 UI 共用） */
    fun formatEntry(entry: LogEntry): String =
        fmt.get().format(Date(entry.ts)) + " " + entry.level.letter + " " + entry.tag + ": " + entry.msg

    /** 内存条目快照（最新的在最后，UI 可按级别过滤） */
    fun entriesSnapshot(): List<LogEntry> = synchronized(entriesLock) { entries.toList() }

    /** 最近日志文本行（最新的在最后） */
    fun snapshot(): List<String> = entriesSnapshot().map { formatEntry(it) }

    fun clear() {
        synchronized(entriesLock) { entries.clear() }
        synchronized(fileLock) {
            runCatching { out?.close() }
            out = null
            val dir = logDir ?: return
            File(dir, "voiceime.log").delete()
            for (i in 1 until ROLL_FILES) File(dir, "voiceime.log.$i").delete()
        }
    }

    // ---------------- 崩溃捕获 ----------------

    /** 崩溃日志文件列表（最新在前） */
    fun crashLogs(): List<File> =
        logDir?.listFiles { _, n -> n.startsWith("crash-") && n.endsWith(".log") }
            ?.sortedByDescending { it.name }
            ?: emptyList()

    fun readCrashLog(f: File): String = runCatching { f.readText() }.getOrDefault("")

    private fun installCrashHandler() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                writeCrashSync(e)
            } catch (_: Throwable) {
                // 崩溃兜底本身不能再抛
            }
            prev?.uncaughtException(t, e)
        }
    }

    /** 崩溃时同步写盘：进程即将消亡，不能走异步 channel，必须直写并关闭 */
    private fun writeCrashSync(e: Throwable) {
        val dir = logDir ?: return
        dir.mkdirs()
        val name = "crash-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".log"
        File(dir, name).printWriter().use { pw ->
            pw.println("thread: " + Thread.currentThread().name)
            pw.println(Log.getStackTraceString(e))
            pw.println("---- 最近日志（内存） ----")
            entriesSnapshot().forEach { pw.println(formatEntry(it)) }
        }
        // 只保留最近 N 份
        dir.listFiles { _, n -> n.startsWith("crash-") && n.endsWith(".log") }
            ?.sortedByDescending { it.name }
            ?.drop(MAX_CRASH_FILES)
            ?.forEach { it.delete() }
    }

    // ---------------- 异步写盘 ----------------

    private fun startWriter() {
        scope.launch {
            for (line in channel) writeToFile(line)
        }
    }

    private fun writeToFile(line: String) {
        val dir = logDir ?: return
        try {
            synchronized(fileLock) {
                val f = File(dir, "voiceime.log")
                if (out != null && f.length() > MAX_FILE_BYTES) rotateLocked(dir)
                if (out == null) {
                    dir.mkdirs()
                    out = FileOutputStream(File(dir, "voiceime.log"), true)
                }
                val o = out!!
                o.write((line + "\n").toByteArray(Charsets.UTF_8))
                o.flush()
            }
        } catch (_: Throwable) {
            // 句柄可能已失效（如清空后文件被删），关闭待下次重开
            synchronized(fileLock) {
                runCatching { out?.close() }
                out = null
            }
        }
    }

    /** 须在 fileLock 内调用：voiceime.log → .1 → .2，丢最旧 */
    private fun rotateLocked(dir: File) {
        runCatching { out?.close() }
        out = null
        File(dir, "voiceime.log.${ROLL_FILES - 1}").delete()
        for (i in ROLL_FILES - 2 downTo 0) {
            val src = if (i == 0) File(dir, "voiceime.log") else File(dir, "voiceime.log.$i")
            if (src.exists()) src.renameTo(File(dir, "voiceime.log.${i + 1}"))
        }
    }

    // ---------------- 磁盘回放 ----------------

    /** 启动时把磁盘最近日志读回内存：重启/崩溃后 UI 仍能看到历史 */
    private fun replayFromDisk() {
        try {
            val dir = logDir ?: return
            val f = File(dir, "voiceime.log")
            if (!f.isFile) return
            val lines = f.readLines()
            if (lines.isEmpty()) return
            val parsed = parseLines(lines.takeLast(REPLAY_LINES))
            synchronized(entriesLock) {
                entries.addAll(parsed)
                while (entries.size > MAX_ENTRIES) entries.removeFirst()
            }
        } catch (_: Throwable) {
            // 回放失败不影响日志功能
        }
    }

    /** 解析日志行；不匹配行首格式的行（多行消息续行/堆栈）并入上一条 */
    private fun parseLines(lines: List<String>): List<LogEntry> {
        val parser = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
        val outList = mutableListOf<LogEntry>()
        for (line in lines) {
            val m = lineRegex.find(line)
            if (m == null) {
                val last = outList.removeLastOrNull() ?: continue
                outList.add(last.copy(msg = last.msg + "\n" + line))
                continue
            }
            val (tsStr, levelStr, tag, msg) = m.destructured
            val ts = try {
                parser.parse(tsStr)?.time ?: 0L
            } catch (_: Throwable) {
                0L
            }
            val level = when (levelStr) {
                "W" -> LogLevel.WARN
                "E" -> LogLevel.ERROR
                else -> LogLevel.INFO
            }
            outList.add(LogEntry(ts, level, tag, msg))
        }
        return outList
    }
}
