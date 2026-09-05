package com.voiceime.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voiceime.R
import com.voiceime.TestVoiceController
import com.voiceime.data.DownloadProgressBus
import com.voiceime.ui.SettingsViewModel
import com.voiceime.ui.panel.LevelBar

/** 主页：状态卡 + 模型下载（应用内进度条 + 删除） + 识别选项 + 输入法测试（流式） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: SettingsViewModel, onDownloadRequest: () -> Unit) {
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.nav_home)) }) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            // 状态文案在 UI 层组装，随应用语言即时切换
            val statusText = buildString {
                append(stringResource(if (vm.imeEnabled) R.string.status_ime_enabled else R.string.status_ime_disabled))
                append("\n")
                append(stringResource(if (vm.micGranted) R.string.status_mic_ok else R.string.status_mic_missing))
                append("\n")
                append(stringResource(R.string.label_model))
                append(": ")
                append(stringResource(vm.currentModelSpec.labelRes))
                append("  ")
                append(stringResource(if (vm.modelReady) R.string.status_model_ok else R.string.status_model_missing_short))
            }
            ElevatedCard(Modifier.fillMaxWidth()) {
                Text(
                    statusText,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                )
            }
            ModelSection(vm, onDownloadRequest)
            OptionsSection(vm)
            TestSection()
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun ModelSection(vm: SettingsViewModel, onDownloadRequest: () -> Unit) {
    val context = LocalContext.current
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val progress by DownloadProgressBus.state.collectAsState()

    SectionHeader(stringResource(R.string.home_section_model))
    val familyLabels = vm.familyOptions.map { stringResource(it) }
    PickerDropdown(
        label = stringResource(R.string.label_model),
        options = familyLabels,
        selectedIndex = vm.familyIndex,
        enabled = true,
        onSelect = vm::onFamilySelected,
    )
    Spacer(Modifier.height(8.dp))
    val modelLabels = vm.modelOptions(vm.familyIndex).map { stringResource(it) }
    PickerDropdown(
        label = stringResource(R.string.label_model),
        options = modelLabels,
        selectedIndex = vm.modelIndex,
        enabled = true,
        onSelect = vm::onModelSelected,
    )
    Text(
        stringResource(vm.modelSummaryRes),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )

    progress?.let { p ->
        Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
            Text(
                p.phase + if (p.indeterminate) "" else {
                    val percent = ((p.downloaded * 100) / p.total).toInt().coerceIn(0, 100)
                    "  $percent%"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LinearProgressIndicator(
                progress = { if (p.indeterminate) 0f else (p.downloaded.toFloat() / p.total).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
        }
    }

    Row(Modifier.padding(top = 8.dp)) {
        FilledTonalButton(onClick = onDownloadRequest) {
            Text(stringResource(R.string.btn_download))
        }
        Spacer(Modifier.padding(4.dp))
        OutlinedButton(onClick = {
            // 模型未下载时不弹确认框（避免误提示可删除）
            if (vm.modelReady) {
                showDeleteConfirm = true
            } else {
                android.widget.Toast.makeText(
                    context,
                    R.string.toast_model_not_downloaded,
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
            }
        }) {
            Text(stringResource(R.string.btn_delete_model))
        }
    }

    // 自定义下载源（可选，失败自动回退官方源 / HF 镜像）
    Text(
        stringResource(R.string.label_custom_url),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = 12.dp),
    )
    OutlinedTextField(
        value = vm.customUrl,
        onValueChange = vm::onUrlChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text(stringResource(R.string.custom_url_hint)) },
    )
    Row(Modifier.padding(top = 4.dp)) {
        TextButton(onClick = vm::saveCustomUrl) {
            Text(stringResource(R.string.btn_save_custom_url))
        }
        TextButton(onClick = vm::clearCustomUrl) {
            Text(stringResource(R.string.btn_clear_custom_url))
        }
    }

    if (showDeleteConfirm) {
        // 弹窗内 LocalContext 是 Activity 原始 context（不随应用语言即时切换），文案在调用点解析
        val confirmTitle = stringResource(R.string.delete_confirm_title)
        val confirmMsg = stringResource(R.string.delete_confirm_msg, stringResource(vm.currentModelSpec.labelRes))
        val confirmOk = stringResource(R.string.delete_confirm_ok)
        val confirmClose = stringResource(R.string.logs_close)
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(confirmTitle) },
            text = { Text(confirmMsg) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    vm.deleteModel()
                }) { Text(confirmOk) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(confirmClose)
                }
            },
        )
    }
}

@Composable
private fun OptionsSection(vm: SettingsViewModel) {
    SectionHeader(stringResource(R.string.home_section_options))
    val languageLabels = LocalContext.current.resources
        .getStringArray(R.array.language_labels).toList()
    PickerDropdown(
        label = stringResource(R.string.label_language),
        options = languageLabels,
        selectedIndex = vm.languageIndex,
        enabled = vm.languageEnabled,
        onSelect = vm::onLanguageSelected,
    )
    SwitchRow(
        label = stringResource(R.string.label_emotion_event),
        checked = vm.emotionEvent,
        enabled = vm.emotionEnabled,
        onChange = vm::onEmotionEventChange,
    )
    SwitchRow(
        label = stringResource(R.string.label_auto_switch),
        checked = vm.autoSwitch,
        enabled = true,
        onChange = vm::onAutoSwitchChange,
    )
}

/** 输入法测试卡：流式预览 + 整段结果（不上屏） */
@Composable
private fun TestSection() {
    val context = LocalContext.current
    val state by TestVoiceController.state.collectAsState()

    SectionHeader(stringResource(R.string.home_section_test))
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.test_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (state.recording || state.decoding) {
                LevelBar(
                    level = state.level,
                    recording = state.recording,
                    anim = com.voiceime.UiParams.readAnim(context),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .height(36.dp),
                )
            }

            val previewLines = if (state.preview.isNotBlank()) {
                state.lines + state.preview
            } else {
                state.lines
            }
            if (previewLines.isNotEmpty()) {
                Text(
                    previewLines.takeLast(3).joinToString("\n"),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }

            if (state.decoding) {
                Text(
                    stringResource(R.string.status_recognizing),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            if (state.finalText.isNotEmpty()) {
                Text(
                    stringResource(R.string.test_result),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 12.dp),
                )
                Text(
                    state.finalText,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            state.error?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            Button(
                onClick = { TestVoiceController.toggle(context) },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(52.dp),
            ) {
                Text(
                    stringResource(
                        when {
                            state.recording -> R.string.btn_stop
                            state.decoding -> R.string.status_recognizing
                            else -> R.string.btn_start
                        },
                    ),
                    fontSize = 16.sp,
                )
            }
        }
    }
}
