package com.voiceime

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.TenVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig

/**
 * Ten-VAD 构建与释放（此前在 VoiceImeService 与 TestVoiceController 各有一份
 * initVad/releaseVad，且行为已漂移：Service 版失败回退、测试版吞异常——此处统一）。
 */
object VadFactory {

    private const val MODEL_PATH = "vad/ten-vad.onnx"

    /** 构建 Ten-VAD；失败返回 null（调用方降级为按时间分片出段） */
    fun create(assets: AssetManager, debug: DebugParams.Values, tag: String): Vad? = try {
        val config = VadModelConfig(
            tenVadModelConfig = TenVadModelConfig(
                model = MODEL_PATH,
                threshold = debug.vadThreshold,
                minSilenceDuration = debug.vadMinSilence,
                minSpeechDuration = debug.vadMinSpeech,
                windowSize = debug.vadWindowSize,
                maxSpeechDuration = debug.vadMaxSpeech,
            ),
            sampleRate = AudioCodec.SAMPLE_RATE,
            numThreads = 1,
            provider = "cpu",
            debug = false,
        )
        Vad(assetManager = assets, config = config)
    } catch (t: Throwable) {
        AppLog.w(tag, "VAD init failed, fallback to time-based partial", t)
        null
    }

    /** 释放 VAD（幂等；吞掉释放异常，不影响后续流程） */
    fun release(vad: Vad?) {
        try {
            vad?.release()
        } catch (t: Throwable) {
            AppLog.w("VadFactory", "Failed to release VAD", t)
        }
    }
}
