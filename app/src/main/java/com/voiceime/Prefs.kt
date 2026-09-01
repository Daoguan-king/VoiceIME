package com.voiceime

/**
 * SharedPreferences 键常量唯一来源，避免多处硬编码漂移。
 * 调试参数键见 [DebugParams]。
 */
object Prefs {
    const val NAME = "voiceime"

    /** 当前 ASR 模型 id（见 [AsrModels]） */
    const val KEY_MODEL = "model_id"

    /** 旧版键：模型变体（small-int8 / small-full），仅用于迁移 */
    const val KEY_MODEL_VARIANT = "model_variant"

    const val KEY_LANGUAGE = "language"
    const val KEY_AUTO_SWITCH = "auto_switch_back"

    /** SenseVoice 情感/事件（富文本）检测开关，识别文本后附加标签 */
    const val KEY_EMOTION_EVENT = "emotion_event"

    /** 每个模型可单独保存自定义下载源 URL */
    fun customUrlKey(modelId: String): String = "custom_model_url_$modelId"
}
