package com.voiceime

import android.content.Context

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

    /**
     * 字段单表（key/标签/类型/范围/取值）：
     * readAll / saveAll / reset / 设置页字段列表全部由此生成。
     */
    val fields: List<ParamField<Values>> = listOf(
        ParamField(K_VAD_THRESHOLD, R.string.dbg_vad_threshold, ParamType.FLOAT, 0.1, 0.9) { it.vadThreshold.toString() },
        ParamField(K_VAD_MIN_SILENCE, R.string.dbg_min_silence, ParamType.FLOAT, 0.1, 2.0) { it.vadMinSilence.toString() },
        ParamField(K_VAD_MIN_SPEECH, R.string.dbg_min_speech, ParamType.FLOAT, 0.1, 2.0) { it.vadMinSpeech.toString() },
        ParamField(K_VAD_WINDOW, R.string.dbg_window, ParamType.INT, 256.0, 512.0) { it.vadWindowSize.toString() },
        ParamField(K_VAD_MAX_SPEECH, R.string.dbg_max_speech, ParamType.FLOAT, 0.0, 60.0) { it.vadMaxSpeech.toString() },
        ParamField(K_THREADS, R.string.dbg_threads, ParamType.INT, 1.0, 8.0) { it.decodeThreads.toString() },
        ParamField(K_WORKERS, R.string.dbg_workers, ParamType.INT, 1.0, 8.0) { it.workers.toString() },
        ParamField(K_AUTO_STOP_MS, R.string.dbg_auto_stop, ParamType.LONG, 200.0, 5_000.0) { it.autoStopMs.toString() },
        ParamField(K_PREVIEW_CHARS, R.string.dbg_preview_chars, ParamType.INT, 32.0, 300.0) { it.previewChars.toString() },
        ParamField(K_READ_CHUNK_MS, R.string.dbg_read_chunk, ParamType.INT, 16.0, 200.0) { it.readChunkMs.toString() },
        ParamField(K_PREVIEW_INTERVAL_MS, R.string.dbg_preview_interval, ParamType.LONG, 50.0, 2_000.0) { it.previewIntervalMs.toString() },
    )

    fun read(context: Context): Values {
        val sp = context.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
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
    fun readAll(context: Context): Map<String, String> = ParamStore.readAll(fields, read(context))

    /** 校验并保存。返回 null 表示成功，否则返回错误提示（已本地化）。 */
    fun saveAll(context: Context, values: Map<String, String>): String? =
        ParamStore.saveAll(context, fields, values)

    fun reset(context: Context) = ParamStore.reset(context, fields)
}
