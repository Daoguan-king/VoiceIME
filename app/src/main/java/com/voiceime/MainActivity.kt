package com.voiceime

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.voiceime.AppLocale
import com.voiceime.ui.SettingsViewModel
import com.voiceime.ui.VoiceImeApp
import com.voiceime.ui.theme.VoiceImeTheme

/**
 * VoiceIME 设置页入口（Compose / Material 3）：
 * - 首次启动显示引导页，之后显示主设置界面；
 * - 状态与业务逻辑在 [SettingsViewModel]。
 */
class MainActivity : ComponentActivity() {

    private val vm: SettingsViewModel by viewModels()

    override fun attachBaseContext(base: android.content.Context) {
        // 应用界面语言（Android 13+ 由系统 per-app language 接管）
        super.attachBaseContext(com.voiceime.AppLocale.wrap(base))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // 语言经 Compose 层即时切换（配置变更不重建 Activity，避免黑屏）
            val localeValue = AppLocale.OPTIONS[vm.appLocaleIndex]
            val localizedContext = remember(localeValue) {
                AppLocale.wrap(applicationContext, localeValue)
            }
            VoiceImeTheme(dynamicColor = vm.dynamicColor) {
                CompositionLocalProvider(LocalContext provides localizedContext) {
                    VoiceImeApp(vm) {
                        ensureNotificationPermission()
                        vm.startDownload()
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 从系统设置/权限页返回时刷新状态
        vm.refresh()
    }

    /** Android 13+ 请求通知权限（下载进度条用；拒绝不影响下载） */
    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATION)
        }
    }

    companion object {
        private const val REQ_NOTIFICATION = 0x5A52
    }
}
