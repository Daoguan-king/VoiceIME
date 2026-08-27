package com.voiceime

/**
 * SharedPreferences 键常量唯一来源，避免多处硬编码漂移。
 * 调试参数键见 [DebugParams]。
 */
object Prefs {
    const val NAME = "voiceime"

    const val KEY_MODEL_VARIANT = "model_variant"
    const val KEY_LANGUAGE = "language"
    const val KEY_AUTO_SWITCH = "auto_switch_back"
}
