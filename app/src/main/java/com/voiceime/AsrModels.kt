package com.voiceime

/**
 * ASR 模型注册表（两段式：先选类型 [ModelFamily]，再选具体模型 [ModelSpec]）。
 *
 * 新增模型：在 [AsrModels.all] 加一个 [ModelSpec]，并在 [VoiceRecognizer] 的 when
 * 里补充对应的 OfflineModelConfig 配置。
 *
 * 下载源顺序（见 VoiceModelManager.downloadModel）：
 *   自定义 URL → [ModelSource] 逐文件镜像（hf-mirror / modelscope，无需解压）→ 官方压缩包
 *
 * 模型目录布局：/Android/data/<pkg>/files/models/<dirName>/
 */
enum class ModelFamily(val label: String) {
    SENSE_VOICE("SenseVoice"),
    QWEN3_ASR("Qwen3-ASR"),
    MOONSHINE("Moonshine"),
    PARA_FORMER("Paraformer"),
    ZIPFORMER("Zipformer"),
}

/** 识别器构建类型（决定 OfflineModelConfig 的配置分支） */
enum class ModelKind {
    /** SenseVoice：多语言（中/英/日/韩/粤），支持语言选择、ITN、情感/事件 */
    SENSE_VOICE,

    /** Qwen3-ASR：conv_frontend + encoder + decoder + tokenizer 目录 */
    QWEN3_ASR,

    /** Paraformer：单模型文件（model + tokens） */
    PARA_FORMER,

    /** Moonshine v1：preprocessor + encoder + 双 decoder（int8 onnx） */
    MOONSHINE_V1,

    /** Moonshine v2：encoder + mergedDecoder（.ort 量化 / 魔搭 .int8.onnx） */
    MOONSHINE_V2,

    /** Zipformer transducer：encoder + decoder + joiner */
    ZIPFORMER_TRANSDUCER,
}

/** 逐文件镜像源（下载后无需解压，通常比官方 tar.bz2 快） */
data class ModelSource(
    /** 日志显示名，如 hf-mirror / modelscope */
    val name: String,
    /** 文件直链前缀，最终 URL = baseUrl + 文件相对路径 */
    val baseUrl: String,
    /** 需要下载的文件相对路径列表 */
    val files: List<String>,
)

data class ModelSpec(
    /** 唯一 id，持久化到 SharedPreferences */
    val id: String,
    /** 第二段下拉显示名 */
    val label: String,
    /** 一句话介绍（大小/语言/特点），显示在模型下拉下方 */
    val summary: String,
    /** 第一段类型 */
    val family: ModelFamily,
    /** 识别器构建类型 */
    val kind: ModelKind,
    /** 模型目录名（filesDir/models/<dirName>） */
    val dirName: String,
    /** 就绪检测必需的文件（相对模型目录） */
    val files: List<String>,
    /** 需要 tokenizer 子目录（Qwen3 等 LLM 类模型） */
    val tokenizerDir: Boolean = false,
    /** 官方压缩包直链（zip / tar.bz2 / tar.gz，最后回退） */
    val archiveUrl: String? = null,
    /** 逐文件镜像源，按顺序尝试 */
    val sources: List<ModelSource> = emptyList(),
    /** 下载前要求的磁盘空间 */
    val requiredBytes: Long,
    /** 是否支持语言选择（仅 SenseVoice 支持） */
    val supportsLanguage: Boolean = true,
)

object AsrModels {
    const val DEFAULT_ID = "sensevoice-int8"

    private const val HF = "https://hf-mirror.com/csukuangfj2/"
    private const val GH_ASR = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/"

    /** 构造一个 hf-mirror 逐文件源 */
    private fun hfSource(modelDir: String, files: List<String>): ModelSource =
        ModelSource("hf-mirror", HF + modelDir + "/resolve/main/", files)

    // ---------------- SenseVoice ----------------

    val SENSE_VOICE_INT8 = ModelSpec(
        id = DEFAULT_ID,
        label = "small（int8）",
        summary = "约 170 MB · 中/英/日/韩/粤 · 推荐",
        family = ModelFamily.SENSE_VOICE,
        kind = ModelKind.SENSE_VOICE,
        dirName = "sensevoice-int8",
        files = listOf("tokens.txt", "model.int8.onnx"),
        archiveUrl = GH_ASR + "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2",
        sources = listOf(
            hfSource(
                "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17",
                listOf("tokens.txt", "model.int8.onnx"),
            ),
        ),
        requiredBytes = 350L * 1024 * 1024,
    )

    val SENSE_VOICE_FULL = ModelSpec(
        id = "sensevoice-full",
        label = "small（fp32）",
        summary = "约 900 MB · 精度更高 · 慎选",
        family = ModelFamily.SENSE_VOICE,
        kind = ModelKind.SENSE_VOICE,
        dirName = "sensevoice-full",
        files = listOf("tokens.txt", "model.onnx"),
        archiveUrl = GH_ASR + "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17.tar.bz2",
        sources = listOf(
            hfSource(
                "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17",
                listOf("tokens.txt", "model.onnx"),
            ),
        ),
        requiredBytes = 1_000L * 1024 * 1024,
    )

    val SENSE_VOICE_INT8_2025 = ModelSpec(
        id = "sensevoice-int8-2025",
        label = "small（int8）2025-09-09",
        summary = "约 170 MB · 2025 新版",
        family = ModelFamily.SENSE_VOICE,
        kind = ModelKind.SENSE_VOICE,
        dirName = "sensevoice-int8-2025",
        files = listOf("tokens.txt", "model.int8.onnx"),
        archiveUrl = GH_ASR + "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09.tar.bz2",
        sources = listOf(
            hfSource(
                "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09",
                listOf("tokens.txt", "model.int8.onnx"),
            ),
        ),
        requiredBytes = 350L * 1024 * 1024,
    )

    // ---------------- Qwen3-ASR ----------------

    val QWEN3_ASR_INT8 = ModelSpec(
        id = "qwen3-asr-int8",
        label = "0.6B（int8）",
        summary = "约 1 GB · 中英 · 实验性更强识别",
        family = ModelFamily.QWEN3_ASR,
        kind = ModelKind.QWEN3_ASR,
        dirName = "qwen3-asr-int8",
        files = listOf("conv_frontend.onnx", "encoder.int8.onnx", "decoder.int8.onnx"),
        tokenizerDir = true,
        archiveUrl = GH_ASR + "sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25.tar.bz2",
        sources = listOf(
            hfSource(
                "sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25",
                listOf(
                    "conv_frontend.onnx",
                    "encoder.int8.onnx",
                    "decoder.int8.onnx",
                    "tokenizer/vocab.json",
                    "tokenizer/merges.txt",
                    "tokenizer/tokenizer_config.json",
                ),
            ),
        ),
        requiredBytes = 1_500L * 1024 * 1024,
        supportsLanguage = false,
    )

    // ---------------- Moonshine ----------------

    /** v1 共 4 个 onnx + tokens */
    private fun moonshineV1(id: String, label: String, summary: String, modelDir: String, bytes: Long) =
        ModelSpec(
            id = id,
            label = label,
            summary = summary,
            family = ModelFamily.MOONSHINE,
            kind = ModelKind.MOONSHINE_V1,
            dirName = id,
            files = listOf(
                "tokens.txt",
                "preprocess.onnx",
                "encode.int8.onnx",
                "uncached_decode.int8.onnx",
                "cached_decode.int8.onnx",
            ),
            archiveUrl = GH_ASR + modelDir + ".tar.bz2",
            sources = listOf(
                hfSource(
                    modelDir,
                    listOf(
                        "tokens.txt",
                        "preprocess.onnx",
                        "encode.int8.onnx",
                        "uncached_decode.int8.onnx",
                        "cached_decode.int8.onnx",
                    ),
                ),
            ),
            requiredBytes = bytes,
            supportsLanguage = false,
        )

    /** v2 量化（.ort）共 2 个模型 + tokens */
    private fun moonshineV2(id: String, label: String, summary: String, modelDir: String, bytes: Long) =
        ModelSpec(
            id = id,
            label = label,
            summary = summary,
            family = ModelFamily.MOONSHINE,
            kind = ModelKind.MOONSHINE_V2,
            dirName = id,
            files = listOf("tokens.txt", "encoder_model.ort", "decoder_model_merged.ort"),
            archiveUrl = GH_ASR + modelDir + ".tar.bz2",
            sources = listOf(
                hfSource(
                    modelDir,
                    listOf("tokens.txt", "encoder_model.ort", "decoder_model_merged.ort"),
                ),
            ),
            requiredBytes = bytes,
            supportsLanguage = false,
        )

    val MOONSHINE_TINY_EN = moonshineV1(
        "moonshine-tiny-en", "tiny（英文 int8）", "约 150 MB · 英文",
        "sherpa-onnx-moonshine-tiny-en-int8", 500L * 1024 * 1024,
    )

    val MOONSHINE_BASE_EN = moonshineV1(
        "moonshine-base-en", "base（英文 int8）", "约 300 MB · 英文，更准",
        "sherpa-onnx-moonshine-base-en-int8", 800L * 1024 * 1024,
    )

    val MOONSHINE_TINY_KO = moonshineV2(
        "moonshine-tiny-ko", "tiny（韩文量化）", "约 60 MB · 韩文",
        "sherpa-onnx-moonshine-tiny-ko-quantized-2026-02-27", 300L * 1024 * 1024,
    )

    val MOONSHINE_TINY_JA = moonshineV2(
        "moonshine-tiny-ja", "tiny（日文量化）", "约 60 MB · 日文",
        "sherpa-onnx-moonshine-tiny-ja-quantized-2026-02-27", 300L * 1024 * 1024,
    )

    val MOONSHINE_BASE_ZH = moonshineV2(
        "moonshine-base-zh", "base（中文量化）", "约 120 MB · 中文",
        "sherpa-onnx-moonshine-base-zh-quantized-2026-02-27", 400L * 1024 * 1024,
    )

    /** 魔搭社区（ModelScope）镜像：manyeyes 导出的 Moonshine onnx（int8） */
    private fun moonshineMsc(id: String, label: String, summary: String, ownerRepo: String, bytes: Long) =
        ModelSpec(
            id = id,
            label = label,
            summary = summary,
            family = ModelFamily.MOONSHINE,
            kind = ModelKind.MOONSHINE_V2,
            dirName = id,
            files = listOf(
                "tokens.txt",
                "encoder_model.int8.onnx",
                "decoder_model_merged.int8.onnx",
            ),
            // 魔搭特有模型：无官方压缩包，直接走 modelscope 逐文件源
            sources = listOf(
                ModelSource(
                    "modelscope",
                    "https://www.modelscope.cn/models/" + ownerRepo + "/resolve/master/",
                    listOf(
                        "tokens.txt",
                        "encoder_model.int8.onnx",
                        "decoder_model_merged.int8.onnx",
                    ),
                ),
            ),
            requiredBytes = bytes,
            supportsLanguage = false,
        )

    val MOONSHINE_TINY_ZH_MSC = moonshineMsc(
        "moonshine-tiny-zh-msc", "tiny（中文 int8，魔搭）", "约 30 MB · 中文 · 魔搭社区",
        "manyeyes/moonshine-tiny-zh-int8-onnx", 200L * 1024 * 1024,
    )

    val MOONSHINE_BASE_ZH_MSC = moonshineMsc(
        "moonshine-base-zh-msc", "base（中文 int8，魔搭）", "约 100 MB · 中文 · 魔搭社区",
        "manyeyes/moonshine-base-zh-int8-onnx", 400L * 1024 * 1024,
    )

    // ---------------- Paraformer ----------------

    val PARA_FORMER_ZH = ModelSpec(
        id = "paraformer-zh",
        label = "中文（int8）",
        summary = "约 60 MB · 轻量中文 · 快",
        family = ModelFamily.PARA_FORMER,
        kind = ModelKind.PARA_FORMER,
        dirName = "paraformer-zh",
        files = listOf("tokens.txt", "model.int8.onnx"),
        archiveUrl = GH_ASR + "sherpa-onnx-paraformer-zh-2023-09-14.tar.bz2",
        sources = listOf(
            hfSource(
                "sherpa-onnx-paraformer-zh-2023-09-14",
                listOf("tokens.txt", "model.int8.onnx"),
            ),
        ),
        requiredBytes = 150L * 1024 * 1024,
        supportsLanguage = false,
    )

    val PARA_FORMER_ZH_SMALL = ModelSpec(
        id = "paraformer-zh-small",
        label = "中文 small（int8）",
        summary = "约 30 MB · 更小更快，精度略低",
        family = ModelFamily.PARA_FORMER,
        kind = ModelKind.PARA_FORMER,
        dirName = "paraformer-zh-small",
        files = listOf("tokens.txt", "model.int8.onnx"),
        archiveUrl = GH_ASR + "sherpa-onnx-paraformer-zh-small-2024-03-09.tar.bz2",
        sources = listOf(
            hfSource(
                "sherpa-onnx-paraformer-zh-small-2024-03-09",
                listOf("tokens.txt", "model.int8.onnx"),
            ),
        ),
        requiredBytes = 100L * 1024 * 1024,
        supportsLanguage = false,
    )

    // ---------------- Zipformer ----------------

    private fun zipformer(
        id: String,
        label: String,
        summary: String,
        modelDir: String,
        enc: String,
        dec: String,
        join: String,
        bytes: Long,
    ) = ModelSpec(
        id = id,
        label = label,
        summary = summary,
        family = ModelFamily.ZIPFORMER,
        kind = ModelKind.ZIPFORMER_TRANSDUCER,
        dirName = id,
        files = listOf("tokens.txt", enc, dec, join),
        archiveUrl = GH_ASR + modelDir + ".tar.bz2",
        sources = listOf(
            hfSource(modelDir, listOf("tokens.txt", enc, dec, join)),
        ),
        requiredBytes = bytes,
        supportsLanguage = false,
    )

    val ZIPFORMER_ZH_EN = zipformer(
        "zipformer-zh-en", "中英（int8）", "约 200 MB · 中英混合",
        "sherpa-onnx-zipformer-zh-en-2023-11-22",
        "encoder-epoch-34-avg-19.int8.onnx",
        "decoder-epoch-34-avg-19.onnx",
        "joiner-epoch-34-avg-19.int8.onnx",
        800L * 1024 * 1024,
    )

    val ZIPFORMER_KO = zipformer(
        "zipformer-korean", "韩文（int8）", "约 200 MB · 韩文",
        "sherpa-onnx-zipformer-korean-2024-06-24",
        "encoder-epoch-99-avg-1.int8.onnx",
        "decoder-epoch-99-avg-1.onnx",
        "joiner-epoch-99-avg-1.int8.onnx",
        800L * 1024 * 1024,
    )

    val ZIPFORMER_JA = zipformer(
        "zipformer-ja", "日文（int8）", "约 200 MB · 日文（ReazonSpeech）",
        "sherpa-onnx-zipformer-ja-reazonspeech-2024-08-01",
        "encoder-epoch-99-avg-1.int8.onnx",
        "decoder-epoch-99-avg-1.onnx",
        "joiner-epoch-99-avg-1.int8.onnx",
        800L * 1024 * 1024,
    )

    /** 魔搭社区（ModelScope）：pkufool 导出的 zipformer（tokens.txt 在 data/ 子目录） */
    private fun zipformerMsc(
        id: String,
        label: String,
        summary: String,
        ownerRepo: String,
        enc: String,
        dec: String,
        join: String,
        bytes: Long,
    ) = ModelSpec(
        id = id,
        label = label,
        summary = summary,
        family = ModelFamily.ZIPFORMER,
        kind = ModelKind.ZIPFORMER_TRANSDUCER,
        dirName = id,
        files = listOf("data/tokens.txt", enc, dec, join),
        sources = listOf(
            ModelSource(
                "modelscope",
                "https://www.modelscope.cn/models/" + ownerRepo + "/resolve/master/",
                listOf("data/tokens.txt", enc, dec, join),
            ),
        ),
        requiredBytes = bytes,
        supportsLanguage = false,
    )

    val ZIPFORMER_SMALL_MSC = zipformerMsc(
        "zipformer-small-msc", "small（中英粤，魔搭）", "约 60 MB · 中/英/粤 · 魔搭社区",
        "pkufool/zipformer-small",
        "encoder.int8.onnx", "decoder.onnx", "joiner.int8.onnx",
        200L * 1024 * 1024,
    )

    val ZIPFORMER_LARGE_MSC = zipformerMsc(
        "zipformer-large-msc", "large（魔搭）", "约 180 MB · 多语言 · 魔搭社区",
        "pkufool/zipformer-large",
        "encoder.int8.onnx", "decoder.onnx", "joiner.int8.onnx",
        400L * 1024 * 1024,
    )

    // ---------------- 汇总 ----------------

    val all: List<ModelSpec> = listOf(
        SENSE_VOICE_INT8,
        SENSE_VOICE_FULL,
        SENSE_VOICE_INT8_2025,
        QWEN3_ASR_INT8,
        MOONSHINE_TINY_EN,
        MOONSHINE_BASE_EN,
        MOONSHINE_TINY_KO,
        MOONSHINE_TINY_JA,
        MOONSHINE_BASE_ZH,
        MOONSHINE_TINY_ZH_MSC,
        MOONSHINE_BASE_ZH_MSC,
        PARA_FORMER_ZH,
        PARA_FORMER_ZH_SMALL,
        ZIPFORMER_ZH_EN,
        ZIPFORMER_KO,
        ZIPFORMER_JA,
        ZIPFORMER_SMALL_MSC,
        ZIPFORMER_LARGE_MSC,
    )

    fun byId(id: String?): ModelSpec? = all.firstOrNull { it.id == id }

    fun byFamily(family: ModelFamily): List<ModelSpec> = all.filter { it.family == family }

    /** 旧版 KEY_MODEL_VARIANT（small-int8 / small-full）迁移到新模型 id */
    fun byLegacyVariant(variant: String?): ModelSpec? = when (variant) {
        "small-int8" -> SENSE_VOICE_INT8
        "small-full" -> SENSE_VOICE_FULL
        else -> null
    }

    fun require(id: String?): ModelSpec = byId(id) ?: SENSE_VOICE_INT8
}
