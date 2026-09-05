package com.voiceime

import android.content.Context
import android.content.SharedPreferences

/**
 * 界面/面板可调参数（复刻 [DebugParams] 模式：read/saveAll/reset + 范围校验）。
 * 包含：电平动画开关、动画形态参数、Material You 动态取色、应用界面语言。
 * 修改后面板实时生效（VoiceImeService 经 StateFlow 推送给 Compose 面板）。
 */
object UiParams {

    const val K_LEVEL_ANIMATION = "ui_level_animation"
    const val K_DYNAMIC_COLOR = "ui_dynamic_color"
    const val K_APP_LOCALE = "app_locale"

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

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)

    fun readAnim(context: Context): AnimValues {
        val sp = prefs(context)
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
    fun readAnimAll(context: Context): Map<String, String> {
        val v = readAnim(context)
        return buildMap {
            animFields.forEach { f ->
                put(f.key, if (f.decimal) {
                    val x = when (f.key) {
                        K_WAVE_SPEED -> v.waveSpeed
                        K_PHASE_STEP -> v.phaseStep
                        K_SMOOTHING -> v.smoothing
                        K_NOISE_AMPLITUDE -> v.noiseAmplitude
                        else -> v.noiseFloor
                    }
                    x.toString()
                } else {
                    v.phaseCycleMs.toString()
                })
            }
        }
    }

    /** 数值字段定义：key → (标签, 范围, 是否小数) */
    val animFields: List<Quad> = listOf(
        Quad(K_PHASE_CYCLE_MS, R.string.ui_phase_cycle_ms, 200f, 3000f, false),
        Quad(K_WAVE_SPEED, R.string.ui_wave_speed, 0.2f, 8f, true),
        Quad(K_PHASE_STEP, R.string.ui_phase_step, 0.05f, 1f, true),
        Quad(K_SMOOTHING, R.string.ui_smoothing, 0.05f, 1f, true),
        Quad(K_NOISE_AMPLITUDE, R.string.ui_noise_amplitude, 0.2f, 3f, true),
        Quad(K_NOISE_FLOOR, R.string.ui_noise_floor, 0f, 0.5f, true),
    )

    data class Quad(
        val key: String,
        val labelRes: Int,
        val min: Float,
        val max: Float,
        val decimal: Boolean,
    )

    /** 校验并保存动画参数（不含布尔开关）。返回 null 成功，否则错误文案。 */
    fun saveAnim(
        context: Context,
        values: Map<String, String>,
        onBoolean: (String, Boolean) -> Unit = { _, _ -> },
    ): String? {
        val editor = prefs(context).edit()
        try {
            for (f in animFields) {
                val raw = values[f.key]?.trim().orEmpty()
                val label = context.getString(f.labelRes)
                val v = raw.toFloatOrNull()
                    ?: throw IllegalArgumentException(context.getString(R.string.dbg_err_number, label))
                if (v < f.min || v > f.max) {
                    throw IllegalArgumentException(
                        context.getString(R.string.dbg_err_range, label, f.min.toString(), f.max.toString()),
                    )
                }
                if (f.decimal) editor.putFloat(f.key, v) else editor.putInt(f.key, v.toInt())
            }
        } catch (e: IllegalArgumentException) {
            return e.message
        }
        editor.apply()
        return null
    }

    fun resetAnim(context: Context) {
        prefs(context).edit()
            .remove(K_PHASE_CYCLE_MS).remove(K_WAVE_SPEED).remove(K_PHASE_STEP)
            .remove(K_SMOOTHING).remove(K_NOISE_AMPLITUDE).remove(K_NOISE_FLOOR)
            .apply()
    }

    // ---------------- 布尔开关 / 语言 ----------------

    fun levelAnimation(context: Context): Boolean = prefs(context).getBoolean(K_LEVEL_ANIMATION, true)

    fun setLevelAnimation(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(K_LEVEL_ANIMATION, value).apply()
    }

    fun dynamicColor(context: Context): Boolean = prefs(context).getBoolean(K_DYNAMIC_COLOR, true)

    fun setDynamicColor(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(K_DYNAMIC_COLOR, value).apply()
    }

    fun appLocale(context: Context): String = prefs(context).getString(K_APP_LOCALE, AppLocale.FOLLOW_SYSTEM)
        ?: AppLocale.FOLLOW_SYSTEM

    fun setAppLocale(context: Context, value: String) {
        prefs(context).edit().putString(K_APP_LOCALE, value).apply()
    }
}
