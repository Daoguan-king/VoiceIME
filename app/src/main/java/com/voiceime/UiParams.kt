package com.voiceime

import android.content.Context

/**
 * 界面/面板可调参数（数值部分与 [DebugParams] 共用 [ParamField]/[ParamStore] 表驱动框架）。
 * 包含：电平动画参数、Material You 动态取色。
 * 应用界面语言的存储归 [AppLocale] 所有（语言属于本地化，不属于 UI 调参）。
 * 修改后面板实时生效（VoiceImeService 经 StateFlow 推送给 Compose 面板）。
 */
object UiParams {

    const val K_LEVEL_ANIMATION = "ui_level_animation"
    const val K_DYNAMIC_COLOR = "ui_dynamic_color"

    const val K_PHASE_CYCLE_MS = "ui_phase_cycle_ms"
    const val K_WAVE_SPEED = "ui_wave_speed"
    const val K_PHASE_STEP = "ui_phase_step"
    const val K_SMOOTHING = "ui_smoothing"
    const val K_NOISE_AMPLITUDE = "ui_noise_amplitude"
    const val K_NOISE_FLOOR = "ui_noise_floor"

    /** 面板电平动画参数（PanelScreen/LevelBar 消费） */
    data class AnimValues(
        val levelAnimation: Boolean = true,
        val phaseCycleMs: Int = 1200,
        val waveSpeed: Float = 1.5f,
        val phaseStep: Float = 0.35f,
        val smoothing: Float = 0.3f,
        val noiseAmplitude: Float = 1.2f,
        val noiseFloor: Float = 0.15f,
    )

    /** 动画数值字段单表（布尔开关不在此列，见下方直接读写） */
    val animFields: List<ParamField<AnimValues>> = listOf(
        ParamField(K_PHASE_CYCLE_MS, R.string.ui_phase_cycle_ms, ParamType.INT, 200.0, 3000.0) { it.phaseCycleMs.toString() },
        ParamField(K_WAVE_SPEED, R.string.ui_wave_speed, ParamType.FLOAT, 0.2, 8.0) { it.waveSpeed.toString() },
        ParamField(K_PHASE_STEP, R.string.ui_phase_step, ParamType.FLOAT, 0.05, 1.0) { it.phaseStep.toString() },
        ParamField(K_SMOOTHING, R.string.ui_smoothing, ParamType.FLOAT, 0.05, 1.0) { it.smoothing.toString() },
        ParamField(K_NOISE_AMPLITUDE, R.string.ui_noise_amplitude, ParamType.FLOAT, 0.2, 3.0) { it.noiseAmplitude.toString() },
        ParamField(K_NOISE_FLOOR, R.string.ui_noise_floor, ParamType.FLOAT, 0.0, 0.5) { it.noiseFloor.toString() },
    )

    fun readAnim(context: Context): AnimValues {
        val sp = context.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
        return AnimValues(
            levelAnimation = sp.getBoolean(K_LEVEL_ANIMATION, true),
            phaseCycleMs = sp.getInt(K_PHASE_CYCLE_MS, 1200),
            waveSpeed = sp.getFloat(K_WAVE_SPEED, 1.5f),
            phaseStep = sp.getFloat(K_PHASE_STEP, 0.35f),
            smoothing = sp.getFloat(K_SMOOTHING, 0.3f),
            noiseAmplitude = sp.getFloat(K_NOISE_AMPLITUDE, 1.2f),
            noiseFloor = sp.getFloat(K_NOISE_FLOOR, 0.15f),
        )
    }

    /** 设置页展示用（字符串形式） */
    fun readAnimAll(context: Context): Map<String, String> =
        ParamStore.readAll(animFields, readAnim(context))

    /** 校验并保存动画数值参数（不含布尔开关）。返回 null 成功，否则错误文案。 */
    fun saveAnim(context: Context, values: Map<String, String>): String? =
        ParamStore.saveAll(context, animFields, values)

    fun resetAnim(context: Context) = ParamStore.reset(context, animFields)

    // ---------------- 布尔开关 ----------------

    fun levelAnimation(context: Context): Boolean =
        context.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
            .getBoolean(K_LEVEL_ANIMATION, true)

    fun setLevelAnimation(context: Context, value: Boolean) {
        context.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(K_LEVEL_ANIMATION, value).apply()
    }

    fun dynamicColor(context: Context): Boolean =
        context.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
            .getBoolean(K_DYNAMIC_COLOR, true)

    fun setDynamicColor(context: Context, value: Boolean) {
        context.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(K_DYNAMIC_COLOR, value).apply()
    }
}
