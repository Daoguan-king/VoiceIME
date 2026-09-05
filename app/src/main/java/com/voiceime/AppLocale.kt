package com.voiceime

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import java.util.Locale

/**
 * 应用界面语言（跟随系统 / 中文 / English）：
 * - Android 13+：走系统 per-app language（LocaleManager），系统设置同步可见；
 * - Android 8–12：持久化选择，经 [wrap] 包装 context 生效（Activity 需 recreate）。
 * MainActivity 与 VoiceImeService 统一在 attachBaseContext 调用 [wrap]，
 * 保证设置页与 IME 面板文案一致。
 */
object AppLocale {
    const val FOLLOW_SYSTEM = "system"
    const val ZH = "zh"
    const val EN = "en"

    val OPTIONS = listOf(FOLLOW_SYSTEM, ZH, EN)

    fun current(context: Context): String = UiParams.appLocale(context)

    fun set(context: Context, value: String) {
        UiParams.setAppLocale(context, value)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val lm = context.getSystemService(LocaleManager::class.java) ?: return
            // 仅切回"跟随系统"时同步系统侧（清空记录）；切到具体语言不调
            // LocaleManager——它会触发系统配置变更导致 Activity 闪屏，
            // 界面语言由 AppLocale.wrap 即时生效，全版本一致
            if (value == FOLLOW_SYSTEM && lm.applicationLocales.isEmpty) {
                return
            }
            if (value == FOLLOW_SYSTEM) {
                lm.applicationLocales = android.os.LocaleList.getEmptyLocaleList()
            }
        }
    }

    /** 包装 context 的语言配置（可显式指定语言值，用于 Compose 层即时切换）；全版本手动包装 */
    fun wrap(context: Context, value: String = current(context)): Context {
        if (value == FOLLOW_SYSTEM) return context
        val locale = Locale.forLanguageTag(if (value == ZH) "zh-CN" else "en")
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        return context.createConfigurationContext(config)
    }

    /** 语言选项的显示标签（选项自身不随语言变化） */
    fun label(value: String): String = when (value) {
        ZH -> "中文"
        EN -> "English"
        else -> "跟随系统 / Follow system"
    }
}
