package com.voiceime

import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineMoonshineModelConfig
import com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OfflineQwen3AsrModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import java.io.File

/** 识别结果：文本 + SenseVoice 富文本标签（其他模型为空） */
data class DecodeResult(
    val text: String,
    val emotion: String = "",
    val event: String = "",
)

/**
 * 基于 sherpa-onnx Kotlin API 的离线识别器封装（SenseVoice / Qwen3-ASR /
 * Paraformer / Moonshine / Zipformer 等）。
 * assetManager = null → newFromFile，从文件系统路径加载模型。
 *
 * 新增模型类型：在 [AsrModels] 注册 ModelSpec，并在此 when 里补充对应配置。
 */
class VoiceRecognizer(
    private val modelDir: File,
    private val spec: ModelSpec,
    language: String,
    useItn: Boolean,
    numThreads: Int,
) {
    /** 从 spec.files 推导 tokens.txt 路径（支持子目录，如魔搭 data/tokens.txt） */
    private fun tokensPath(): String {
        val rel = spec.files.firstOrNull { it.endsWith("tokens.txt") } ?: "tokens.txt"
        return File(modelDir, rel).absolutePath
    }

    private val recognizer: OfflineRecognizer = OfflineRecognizer(
        assetManager = null,
        config = OfflineRecognizerConfig().apply {
            this.modelConfig = OfflineModelConfig().apply {
                this.numThreads = numThreads
                this.provider = "cpu"
                this.debug = false
                when (spec.kind) {
                    ModelKind.SENSE_VOICE -> {
                        this.tokens = tokensPath()
                        val modelName = spec.files.firstOrNull { it.startsWith("model.") }
                            ?: "model.int8.onnx"
                        this.senseVoice = OfflineSenseVoiceModelConfig().apply {
                            this.model = File(modelDir, modelName).absolutePath
                            this.language = language
                            this.useInverseTextNormalization = useItn
                        }
                    }

                    ModelKind.QWEN3_ASR -> {
                        this.tokens = ""
                        this.qwen3Asr = OfflineQwen3AsrModelConfig().apply {
                            this.convFrontend = File(modelDir, "conv_frontend.onnx").absolutePath
                            this.encoder = File(modelDir, "encoder.int8.onnx").absolutePath
                            this.decoder = File(modelDir, "decoder.int8.onnx").absolutePath
                            this.tokenizer = File(modelDir, "tokenizer").absolutePath
                        }
                    }

                    ModelKind.PARA_FORMER -> {
                        this.tokens = tokensPath()
                        this.paraformer = OfflineParaformerModelConfig().apply {
                            this.model = File(modelDir, "model.int8.onnx").absolutePath
                        }
                    }

                    ModelKind.MOONSHINE_V1 -> {
                        this.tokens = tokensPath()
                        this.moonshine = OfflineMoonshineModelConfig().apply {
                            this.preprocessor = File(modelDir, "preprocess.onnx").absolutePath
                            this.encoder = File(modelDir, "encode.int8.onnx").absolutePath
                            this.uncachedDecoder = File(modelDir, "uncached_decode.int8.onnx").absolutePath
                            this.cachedDecoder = File(modelDir, "cached_decode.int8.onnx").absolutePath
                        }
                    }

                    ModelKind.MOONSHINE_V2 -> {
                        this.tokens = tokensPath()
                        val enc = spec.files.firstOrNull { it.startsWith("encoder") } ?: "encoder_model.ort"
                        val dec = spec.files.firstOrNull { it.startsWith("decoder") } ?: "decoder_model_merged.ort"
                        this.moonshine = OfflineMoonshineModelConfig().apply {
                            // 官方源为 .ort，魔搭镜像为 .int8.onnx，均按 spec.files 实际文件名加载
                            this.encoder = File(modelDir, enc).absolutePath
                            this.mergedDecoder = File(modelDir, dec).absolutePath
                        }
                    }

                    ModelKind.ZIPFORMER_TRANSDUCER -> {
                        this.tokens = tokensPath()
                        // 各模型文件命名不同（zh-en 为 epoch-34-avg-19，korean/ja 为 epoch-99-avg-1），
                        // 一律按 spec.files 实际文件名加载
                        val enc = spec.files.firstOrNull { it.startsWith("encoder") } ?: "encoder.int8.onnx"
                        val dec = spec.files.firstOrNull { it.startsWith("decoder") } ?: "decoder.onnx"
                        val join = spec.files.firstOrNull { it.startsWith("joiner") } ?: "joiner.int8.onnx"
                        this.transducer = OfflineTransducerModelConfig().apply {
                            this.encoder = File(modelDir, enc).absolutePath
                            this.decoder = File(modelDir, dec).absolutePath
                            this.joiner = File(modelDir, join).absolutePath
                        }
                    }
                }
            }
        },
    )

    /** 对一段音频做离线识别，返回识别文本（可能为空）。 */
    fun decodeText(samples: FloatArray, sampleRate: Int): String =
        decode(samples, sampleRate).text

    /**
     * 对一段音频做离线识别，返回完整结果（含 SenseVoice 的情感/事件标签）。
     * 非 SenseVoice 模型 emotion/event 为空字符串。
     */
    fun decode(samples: FloatArray, sampleRate: Int): DecodeResult {
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, sampleRate)
            recognizer.decode(stream)
            val result = recognizer.getResult(stream)
            return DecodeResult(
                text = result.text,
                emotion = result.emotion ?: "",
                event = result.event ?: "",
            )
        } finally {
            stream.release()
        }
    }

    fun release() {
        recognizer.release()
    }
}
