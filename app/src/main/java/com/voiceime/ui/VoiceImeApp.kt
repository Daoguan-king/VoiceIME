package com.voiceime.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.voiceime.R
import com.voiceime.ui.screens.AboutScreen
import com.voiceime.ui.screens.HomeScreen
import com.voiceime.ui.screens.OnboardingScreen
import com.voiceime.ui.screens.SettingsScreen

/** 引导页 / 三页主框架（主页-设置-关于，底部导航栏） */
@Composable
fun VoiceImeApp(vm: SettingsViewModel, onDownloadRequest: () -> Unit) {
    if (!vm.onboardingDone) {
        OnboardingScreen(vm, onDownloadRequest)
        return
    }
    var tab by rememberSaveable { mutableIntStateOf(0) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                val items = listOf(
                    Triple(R.string.nav_home, Icons.Filled.Home, "home"),
                    Triple(R.string.nav_settings, Icons.Filled.Settings, "settings"),
                    Triple(R.string.nav_about, Icons.Filled.Info, "about"),
                )
                items.forEachIndexed { index, (labelRes, icon, tag) ->
                    NavigationBarItem(
                        selected = tab == index,
                        onClick = { tab = index },
                        icon = { Icon(icon, contentDescription = tag) },
                        label = { Text(stringResource(labelRes)) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (tab) {
                0 -> HomeScreen(vm, onDownloadRequest)
                1 -> SettingsScreen(vm)
                else -> AboutScreen()
            }
        }
    }
}
