package com.voiceime

import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig

/**
 * 基于 sherpa-onnx Kotlin API 的 SenseVoice 离线识别器封装。
 * 直接使用 AAR 内的公开 API（无反射）：
 * assetManager = null → newFromFile，从文件系统路径加载模型。
 */
class SenseVoiceRecognizer(
    modelPath: String,
    tokensPath: String,
    language: String,
    useItn: Boolean,
    numThreads: Int,
) {
    private val recognizer: OfflineRecognizer = OfflineRecognizer(
        assetManager = null,
        config = OfflineRecognizerConfig().apply {
            this.modelConfig = OfflineModelConfig().apply {
                this.tokens = tokensPath
                this.numThreads = numThreads
                this.provider = "cpu"
                this.debug = false
                this.senseVoice = OfflineSenseVoiceModelConfig().apply {
                    this.model = modelPath
                    this.language = language
                    this.useInverseTextNormalization = useItn
                }
            }
        },
    )

    /** 对一段音频做离线识别，返回识别文本（可能为空）。 */
    fun decode(samples: FloatArray, sampleRate: Int): String {
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, sampleRate)
            recognizer.decode(stream)
            return recognizer.getResult(stream).text
        } finally {
            stream.release()
        }
    }

    fun release() {
        recognizer.release()
    }
}
