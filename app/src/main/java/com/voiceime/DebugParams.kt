package com.voiceime

import android.content.Context
import android.content.SharedPreferences

/**
 * 调试参数：VAD / 解码 / 流式相关可调参数，运行时修改、重新录音即生效。
 * 默认值与 sherpa-onnx 官方 Demo 对齐（ten-vad: threshold 0.5, minSilence 0.25, minSpeech 0.25, window 256）。
 */
object DebugParams {

    const val K_VAD_THRESHOLD = "debug_vad_threshold"
    const val K_VAD_MIN_SILENCE = "debug_vad_min_silence"
    const val K_VAD_MIN_SPEECH = "debug_vad_min_speech"
    const val K_VAD_WINDOW = "debug_vad_window_size"
    const val K_VAD_MAX_SPEECH = "debug_vad_max_speech"
    const val K_THREADS = "debug_decode_threads"
    const val K_WORKERS = "debug_workers"
    const val K_AUTO_STOP_MS = "debug_auto_stop_ms"
    const val K_PREVIEW_CHARS = "debug_preview_chars"
    const val K_READ_CHUNK_MS = "debug_read_chunk_ms"
    const val K_PREVIEW_INTERVAL_MS = "debug_preview_interval_ms"

    data class Values(
        val vadThreshold: Float = 0.5f,
        val vadMinSilence: Float = 0.25f,
        val vadMinSpeech: Float = 0.25f,
        val vadWindowSize: Int = 256,
        val vadMaxSpeech: Float = 10f,
        val decodeThreads: Int = 4,
        val workers: Int = 4,
        val autoStopMs: Long = 2_000L,
        val previewChars: Int = 128,
        val readChunkMs: Int = 64,
        val previewIntervalMs: Long = 250L,
    )

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)

    fun read(context: Context): Values {
        val sp = prefs(context)
        return Values(
            vadThreshold = sp.getFloat(K_VAD_THRESHOLD, 0.5f),
            vadMinSilence = sp.getFloat(K_VAD_MIN_SILENCE, 0.25f),
            vadMinSpeech = sp.getFloat(K_VAD_MIN_SPEECH, 0.25f),
            vadWindowSize = sp.getInt(K_VAD_WINDOW, 256),
            vadMaxSpeech = sp.getFloat(K_VAD_MAX_SPEECH, 10f),
            decodeThreads = sp.getInt(K_THREADS, 4),
            workers = sp.getInt(K_WORKERS, 4),
            autoStopMs = sp.getLong(K_AUTO_STOP_MS, 2_000L),
            previewChars = sp.getInt(K_PREVIEW_CHARS, 128),
            readChunkMs = sp.getInt(K_READ_CHUNK_MS, 64),
            previewIntervalMs = sp.getLong(K_PREVIEW_INTERVAL_MS, 250L),
        )
    }

    /** 设置页展示用的全部参数（字符串形式） */
    fun readAll(context: Context): Map<String, String> {
        val v = read(context)
        return linkedMapOf(
            K_VAD_THRESHOLD to v.vadThreshold.toString(),
            K_VAD_MIN_SILENCE to v.vadMinSilence.toString(),
            K_VAD_MIN_SPEECH to v.vadMinSpeech.toString(),
            K_VAD_WINDOW to v.vadWindowSize.toString(),
            K_VAD_MAX_SPEECH to v.vadMaxSpeech.toString(),
            K_THREADS to v.decodeThreads.toString(),
            K_WORKERS to v.workers.toString(),
            K_AUTO_STOP_MS to v.autoStopMs.toString(),
            K_PREVIEW_CHARS to v.previewChars.toString(),
            K_READ_CHUNK_MS to v.readChunkMs.toString(),
            K_PREVIEW_INTERVAL_MS to v.previewIntervalMs.toString(),
        )
    }

    /** 校验并保存。返回 null 表示成功，否则返回错误提示。 */
    fun saveAll(context: Context, values: Map<String, String>): String? {
        val editor = prefs(context).edit()
        try {
            putFloat(editor, K_VAD_THRESHOLD, values, "VAD 阈值", 0.1f, 0.9f)
            putFloat(editor, K_VAD_MIN_SILENCE, values, "停顿成段时间", 0.1f, 2.0f)
            putFloat(editor, K_VAD_MIN_SPEECH, values, "最短语音时间", 0.1f, 2.0f)
            putInt(editor, K_VAD_WINDOW, values, "VAD 窗口", 256, 512)
            putFloat(editor, K_VAD_MAX_SPEECH, values, "最长语音时间", 0f, 60f)
            putInt(editor, K_THREADS, values, "识别线程数", 1, 8)
            putInt(editor, K_WORKERS, values, "并发解码路数", 1, 8)
            putLong(editor, K_AUTO_STOP_MS, values, "静音自动停止毫秒", 200L, 5_000L)
            putInt(editor, K_PREVIEW_CHARS, values, "预览保留字符数", 32, 300)
            putInt(editor, K_READ_CHUNK_MS, values, "录音读取粒度毫秒", 16, 200)
            putLong(editor, K_PREVIEW_INTERVAL_MS, values, "预览识别间隔毫秒", 50L, 2_000L)
        } catch (e: IllegalArgumentException) {
            return e.message
        }
        editor.apply()
        return null
    }

    fun reset(context: Context) {
        prefs(context).edit()
            .remove(K_VAD_THRESHOLD).remove(K_VAD_MIN_SILENCE).remove(K_VAD_MIN_SPEECH)
            .remove(K_VAD_WINDOW).remove(K_VAD_MAX_SPEECH).remove(K_THREADS)
            .remove(K_WORKERS).remove(K_AUTO_STOP_MS).remove(K_PREVIEW_CHARS)
            .remove(K_READ_CHUNK_MS).remove(K_PREVIEW_INTERVAL_MS)
            .apply()
    }

    private fun putFloat(
        editor: SharedPreferences.Editor,
        key: String,
        values: Map<String, String>,
        label: String,
        min: Float,
        max: Float,
    ) {
        val raw = values[key]?.trim().orEmpty()
        val v = raw.toFloatOrNull() ?: throw IllegalArgumentException("$label 需为数字")
        if (v < min || v > max) throw IllegalArgumentException("$label 需在 $min~$max 之间")
        editor.putFloat(key, v)
    }

    private fun putInt(
        editor: SharedPreferences.Editor,
        key: String,
        values: Map<String, String>,
        label: String,
        min: Int,
        max: Int,
    ) {
        val raw = values[key]?.trim().orEmpty()
        val v = raw.toIntOrNull() ?: throw IllegalArgumentException("$label 需为整数")
        if (v < min || v > max) throw IllegalArgumentException("$label 需在 $min~$max 之间")
        editor.putInt(key, v)
    }

    private fun putLong(
        editor: SharedPreferences.Editor,
        key: String,
        values: Map<String, String>,
        label: String,
        min: Long,
        max: Long,
    ) {
        val raw = values[key]?.trim().orEmpty()
        val v = raw.toLongOrNull() ?: throw IllegalArgumentException("$label 需为整数")
        if (v < min || v > max) throw IllegalArgumentException("$label 需在 $min~$max 之间")
        editor.putLong(key, v)
    }
}
