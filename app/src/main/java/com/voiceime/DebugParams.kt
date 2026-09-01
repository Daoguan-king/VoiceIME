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

    /** 校验并保存。返回 null 表示成功，否则返回错误提示（已本地化）。 */
    fun saveAll(context: Context, values: Map<String, String>): String? {
        val editor = prefs(context).edit()
        try {
            putFloat(context, editor, K_VAD_THRESHOLD, values, R.string.dbg_vad_threshold, 0.1f, 0.9f)
            putFloat(context, editor, K_VAD_MIN_SILENCE, values, R.string.dbg_min_silence, 0.1f, 2.0f)
            putFloat(context, editor, K_VAD_MIN_SPEECH, values, R.string.dbg_min_speech, 0.1f, 2.0f)
            putInt(context, editor, K_VAD_WINDOW, values, R.string.dbg_window, 256, 512)
            putFloat(context, editor, K_VAD_MAX_SPEECH, values, R.string.dbg_max_speech, 0f, 60f)
            putInt(context, editor, K_THREADS, values, R.string.dbg_threads, 1, 8)
            putInt(context, editor, K_WORKERS, values, R.string.dbg_workers, 1, 8)
            putLong(context, editor, K_AUTO_STOP_MS, values, R.string.dbg_auto_stop, 200L, 5_000L)
            putInt(context, editor, K_PREVIEW_CHARS, values, R.string.dbg_preview_chars, 32, 300)
            putInt(context, editor, K_READ_CHUNK_MS, values, R.string.dbg_read_chunk, 16, 200)
            putLong(context, editor, K_PREVIEW_INTERVAL_MS, values, R.string.dbg_preview_interval, 50L, 2_000L)
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
        context: Context,
        editor: SharedPreferences.Editor,
        key: String,
        values: Map<String, String>,
        labelRes: Int,
        min: Float,
        max: Float,
    ) {
        val raw = values[key]?.trim().orEmpty()
        val label = context.getString(labelRes)
        val v = raw.toFloatOrNull()
            ?: throw IllegalArgumentException(context.getString(R.string.dbg_err_number, label))
        if (v < min || v > max) {
            throw IllegalArgumentException(
                context.getString(R.string.dbg_err_range, label, min.toString(), max.toString()),
            )
        }
        editor.putFloat(key, v)
    }

    private fun putInt(
        context: Context,
        editor: SharedPreferences.Editor,
        key: String,
        values: Map<String, String>,
        labelRes: Int,
        min: Int,
        max: Int,
    ) {
        val raw = values[key]?.trim().orEmpty()
        val label = context.getString(labelRes)
        val v = raw.toIntOrNull()
            ?: throw IllegalArgumentException(context.getString(R.string.dbg_err_number, label))
        if (v < min || v > max) {
            throw IllegalArgumentException(
                context.getString(R.string.dbg_err_range, label, min.toString(), max.toString()),
            )
        }
        editor.putInt(key, v)
    }

    private fun putLong(
        context: Context,
        editor: SharedPreferences.Editor,
        key: String,
        values: Map<String, String>,
        labelRes: Int,
        min: Long,
        max: Long,
    ) {
        val raw = values[key]?.trim().orEmpty()
        val label = context.getString(labelRes)
        val v = raw.toLongOrNull()
            ?: throw IllegalArgumentException(context.getString(R.string.dbg_err_number, label))
        if (v < min || v > max) {
            throw IllegalArgumentException(
                context.getString(R.string.dbg_err_range, label, min.toString(), max.toString()),
            )
        }
        editor.putLong(key, v)
    }
}
