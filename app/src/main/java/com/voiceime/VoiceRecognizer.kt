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
import java.util.concurrent.atomic.AtomicInteger

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
    private val language: String,
    private val useItn: Boolean,
    private val numThreads: Int,
) {
    companion object {
        private const val TAG = "VoiceRecognizer"
    }

    /** 本实例在途解码数：release 前必须归零（JNI use-after-free 防护） */
    private val inflight = AtomicInteger(0)

    /** 从 spec.files 推导 tokens.txt 路径（支持子目录，如魔搭 data/tokens.txt） */
    private fun tokensPath(): String {
        val rel = spec.files.firstOrNull { it.endsWith("tokens.txt") } ?: "tokens.txt"
        return File(modelDir, rel).absolutePath
    }

    private val recognizer: OfflineRecognizer = createRecognizer()

    /**
     * 构造识别器（加载模型，可能耗时数秒）。
     * "开始加载模型"/"识别器创建成功"成对落盘：若进程在加载中途死亡
     * （sherpa-onnx 对加载失败会直接 exit，进程静默消失、无崩溃日志），
     * 日志尾迹停在"开始加载"即说明死在模型加载阶段——通常是模型文件
     * 损坏或不兼容，需在设置中删除该模型重新下载。
     */
    private fun createRecognizer(): OfflineRecognizer = try {
        AppLog.i(TAG, "开始加载模型: ${spec.id} @ ${modelDir.absolutePath}, threads=$numThreads, language=$language")
        if (spec.kind == ModelKind.MOONSHINE_V2) {
            // manyeyes 魔搭镜像的 tokens.txt 是明文格式，sherpa-onnx Moonshine v2
            // 会按 base64 解码，遇到 <unk> 等特殊 token 直接 exit 杀死进程；
            // 加载前统一转换（幂等，官方 base64 格式自动跳过）
            VoiceModelManager.ensureMoonshineBase64Tokens(modelDir)
        }
        OfflineRecognizer(
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
        ).also {
            AppLog.i(TAG, "识别器创建成功: ${spec.id}")
        }
    } catch (t: Throwable) {
        AppLog.e(TAG, "模型加载失败（文件可能损坏或不兼容，请在设置中删除该模型后重新下载）: ${spec.id}", t)
        throw t
    }

    /** 对一段音频做离线识别，返回识别文本（可能为空）。 */
    fun decodeText(samples: FloatArray, sampleRate: Int): String =
        decode(samples, sampleRate).text

    /**
     * 对一段音频做离线识别，返回完整结果（含 SenseVoice 的情感/事件标签）。
     * 非 SenseVoice 模型 emotion/event 为空字符串。
     */
    fun decode(samples: FloatArray, sampleRate: Int): DecodeResult {
        val startMs = android.os.SystemClock.elapsedRealtime()
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, sampleRate)
            recognizer.decode(stream)
            val result = recognizer.getResult(stream)
            val costMs = android.os.SystemClock.elapsedRealtime() - startMs
            if (costMs > 5000) {
                AppLog.w(TAG, "解码偏慢: ${costMs}ms（${samples.size / sampleRate}s 音频）")
            }
            return DecodeResult(
                text = result.text,
                emotion = result.emotion ?: "",
                event = result.event ?: "",
            )
        } catch (t: Throwable) {
            AppLog.e(TAG, "解码失败（${samples.size} samples，已耗时 ${android.os.SystemClock.elapsedRealtime() - startMs}ms）", t)
            throw t
        } finally {
            stream.release()
        }
    }

    /** 开始一次解码（须与 [endDecode] 成对调用；在识别器锁内调用） */
    fun beginDecode() {
        inflight.incrementAndGet()
    }

    /** 结束一次解码（finally 中调用） */
    fun endDecode() {
        inflight.decrementAndGet()
    }

    /** 本实例在途解码数 */
    fun inflightCount(): Int = inflight.get()

    fun release() {
        // 先落盘再释放：若 release 触发原生崩溃，日志尾迹能定位到这一步
        AppLog.i(TAG, "识别器释放: ${spec.id}")
        recognizer.release()
    }
}
