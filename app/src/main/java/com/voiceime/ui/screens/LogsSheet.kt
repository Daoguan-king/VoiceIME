package com.voiceime.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voiceime.AppLog
import com.voiceime.LogLevel
import com.voiceime.R

/**
 * 应用内日志弹窗（BottomSheet）：级别过滤 + 复制/分享/清空 + 崩溃日志入口。
 * 注意：弹窗内容运行在独立窗口组合中，其 LocalContext 是 Activity 原始 context
 * （不随应用语言即时切换），因此所有文案在调用点（本组合体）预先解析成字符串传入。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var filterIndex by remember { mutableIntStateOf(0) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var showCrashList by remember { mutableStateOf(false) }

    // 文案在调用点解析（此处 LocalContext 已本地化），弹窗 lambda 直接使用
    val titleText = stringResource(R.string.logs_title)
    val filterLabels = listOf(
        stringResource(R.string.logs_filter_all),
        stringResource(R.string.logs_filter_warn),
        stringResource(R.string.logs_filter_error),
    )
    val copyLabel = stringResource(R.string.logs_copy)
    val shareLabel = stringResource(R.string.logs_share)
    val clearLabel = stringResource(R.string.logs_clear)
    val crashLabel = stringResource(R.string.logs_crash)
    val emptyText = stringResource(R.string.logs_empty)

    val entries = remember(filterIndex, refreshKey) {
        val minLevel = when (filterIndex) {
            1 -> LogLevel.WARN
            2 -> LogLevel.ERROR
            else -> null
        }
        AppLog.entriesSnapshot().filter { minLevel == null || it.level >= minLevel }
    }
    val entriesText = remember(entries) {
        if (entries.isEmpty()) "" else entries.joinToString("\n") { AppLog.formatEntry(it) }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text(titleText, style = MaterialTheme.typography.titleMedium)

            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                filterLabels.forEachIndexed { index, label ->
                    SegmentedButton(
                        selected = filterIndex == index,
                        onClick = { filterIndex = index },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = filterLabels.size),
                    ) {
                        Text(label, fontSize = 12.sp)
                    }
                }
            }

            Row(Modifier.padding(top = 8.dp)) {
                TextButton(onClick = {
                    copyText(context, entriesText)
                    toast(context, R.string.logs_copied)
                }) { Text(copyLabel) }
                TextButton(onClick = { shareText(context, entriesText) }) {
                    Text(shareLabel)
                }
                TextButton(onClick = {
                    AppLog.clear()
                    refreshKey++
                    toast(context, R.string.logs_cleared)
                }) { Text(clearLabel) }
                TextButton(onClick = { showCrashList = true }) {
                    Text(crashLabel)
                }
            }

            LazyColumn(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
            ) {
                if (entries.isEmpty()) {
                    item { Text(emptyText, fontSize = 11.sp) }
                } else {
                    items(entries) { entry ->
                        Text(
                            AppLog.formatEntry(entry),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }
    }

    if (showCrashList) {
        CrashListDialog(onDismiss = { showCrashList = false })
    }
}

/** 崩溃日志文件列表 → 查看/分享（文案同样在调用点解析） */
@Composable
private fun CrashListDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var viewing by remember { mutableStateOf<java.io.File?>(null) }
    val crashes = remember { AppLog.crashLogs() }

    val titleText = stringResource(R.string.logs_crash_title)
    val emptyText = stringResource(R.string.logs_crash_empty)
    val closeLabel = stringResource(R.string.logs_close)
    val shareLabel = stringResource(R.string.logs_share)
    val contentTitle = viewing?.name.orEmpty()
    val contentText = remember(viewing) { viewing?.let { AppLog.readCrashLog(it) }.orEmpty() }

    if (viewing == null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(titleText) },
            text = {
                if (crashes.isEmpty()) {
                    Text(emptyText)
                } else {
                    LazyColumn {
                        items(crashes) { f ->
                            TextButton(onClick = { viewing = f }) {
                                Text(f.name + " (" + (f.length() / 1024) + " KB)", fontSize = 12.sp)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text(closeLabel) }
            },
        )
    } else {
        AlertDialog(
            onDismissRequest = { viewing = null },
            title = { Text(contentTitle) },
            text = {
                LazyColumn {
                    item {
                        Text(
                            contentText,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    shareText(context, contentText)
                }) { Text(shareLabel) }
            },
            dismissButton = {
                TextButton(onClick = { viewing = null }) { Text(closeLabel) }
            },
        )
    }
}

private fun copyText(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("voiceime", text))
}

private fun shareText(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
        // 弹窗内 LocalContext 可能是非 Activity 上下文
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    // createChooser 会生成全新的外层 intent，flag 必须再加一次
    val chooser = Intent.createChooser(send, context.getString(R.string.logs_share))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(chooser)
}

private fun toast(context: Context, resId: Int) {
    android.widget.Toast.makeText(context, resId, android.widget.Toast.LENGTH_SHORT).show()
}
