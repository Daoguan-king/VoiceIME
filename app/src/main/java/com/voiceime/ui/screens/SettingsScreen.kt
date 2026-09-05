package com.voiceime.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.voiceime.AppLocale
import com.voiceime.R
import com.voiceime.UiParams
import com.voiceime.ui.SettingsViewModel

/**
 * 设置页：三个大项（界面设置 / 日志内容 / 语音识别调试参数），内联展开。
 * 模型选择与识别选项在主页。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: SettingsViewModel) {
    var showLogs by remember { mutableStateOf(false) }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.nav_settings)) }) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            AppearanceSection(vm)
            LogsSection { showLogs = true }
            DebugSection(vm)
            Spacer(Modifier.height(32.dp))
        }
    }

    if (showLogs) {
        LogsSheet(onDismiss = { showLogs = false })
    }
}

// ---------------- 大项一：界面设置 ----------------

@Composable
private fun AppearanceSection(vm: SettingsViewModel) {
    val context = LocalContext.current
    SectionHeader(stringResource(R.string.ui_group_appearance))
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            SwitchRow(
                label = stringResource(R.string.label_level_animation),
                checked = vm.levelAnimation,
                enabled = true,
                onChange = vm::onLevelAnimationChange,
            )
            SwitchRow(
                label = stringResource(R.string.label_dynamic_color),
                checked = vm.dynamicColor,
                enabled = true,
                onChange = vm::onDynamicColorChange,
            )

            Text(
                stringResource(R.string.label_app_language),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 12.dp),
            )
            val localeOptions = AppLocale.OPTIONS.map { AppLocale.label(it) }
            PickerDropdown(
                label = stringResource(R.string.label_app_language),
                options = localeOptions,
                selectedIndex = vm.appLocaleIndex,
                enabled = true,
                onSelect = vm::onAppLocaleSelected,
            )

            Text(
                stringResource(R.string.ui_anim_params_title),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 16.dp),
            )
            vm.animFields.forEach { field ->
                OutlinedTextField(
                    value = vm.animValues[field.key].orEmpty(),
                    onValueChange = { vm.onAnimChange(field.key, it) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    singleLine = true,
                    label = { Text(stringResource(field.labelRes)) },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (field.decimal) KeyboardType.Decimal else KeyboardType.Number,
                    ),
                )
            }
            Row(Modifier.padding(top = 8.dp)) {
                FilledTonalButton(onClick = vm::saveAnim) {
                    Text(stringResource(R.string.debug_save))
                }
                Spacer(Modifier.padding(4.dp))
                OutlinedButton(onClick = vm::resetAnim) {
                    Text(stringResource(R.string.debug_reset))
                }
            }
        }
    }
}

// ---------------- 大项二：日志内容 ----------------

@Composable
private fun LogsSection(onOpenLogs: () -> Unit) {
    SectionHeader(stringResource(R.string.ui_group_logs))
    ElevatedCard(Modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = { Text(stringResource(R.string.logs_view)) },
            supportingContent = { Text(stringResource(R.string.logs_view_desc)) },
            trailingContent = {
                Icon(Icons.Filled.KeyboardArrowRight, contentDescription = null)
            },
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenLogs),
        )
    }
}

// ---------------- 大项三：语音识别调试参数 ----------------

@Composable
private fun DebugSection(vm: SettingsViewModel) {
    SectionHeader(stringResource(R.string.ui_group_debug))
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.debug_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            vm.debugFields.forEach { (key, labelRes, decimal) ->
                OutlinedTextField(
                    value = vm.debugValues[key].orEmpty(),
                    onValueChange = { vm.onDebugChange(key, it) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    singleLine = true,
                    label = { Text(stringResource(labelRes)) },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number,
                    ),
                )
            }
            Row(Modifier.padding(top = 8.dp)) {
                FilledTonalButton(onClick = vm::saveDebug) {
                    Text(stringResource(R.string.debug_save))
                }
                Spacer(Modifier.padding(4.dp))
                OutlinedButton(onClick = vm::resetDebug) {
                    Text(stringResource(R.string.debug_reset))
                }
            }
        }
    }
}
