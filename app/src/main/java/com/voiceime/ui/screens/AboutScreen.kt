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
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.voiceime.AsrModels
import com.voiceime.R
import com.voiceime.VoiceModelManager

/** 关于页：应用信息 / 仓库与许可证 / 已下载模型信息 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen() {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    var models by remember { mutableStateOf<List<Pair<String, Long>>>(emptyList()) }

    // 扫描模型目录，匹配注册表显示名称与磁盘占用
    LaunchedEffect(Unit) {
        val list = mutableListOf<Pair<String, Long>>()
        val root = VoiceModelManager.modelRoot(context)
        root.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
            val size = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            val spec = AsrModels.all.firstOrNull { it.dirName == dir.name }
            // 资源解析放在 UI 线程（LaunchedEffect 在主线程回调）
            val name = spec?.let { context.getString(it.labelRes) } ?: dir.name
            list.add(name to size)
        }
        models = list.sortedBy { it.first }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.nav_about)) }) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            // 应用信息
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        stringResource(R.string.about_version, versionName(context)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    TextButton(onClick = {
                        runCatching { uriHandler.openUri(context.getString(R.string.repo_url)) }
                    }) {
                        Text(context.getString(R.string.repo_url))
                    }
                }
            }

            // 许可证与致谢
            SectionHeader(stringResource(R.string.about_license))
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        stringResource(R.string.about_license_body),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.about_acknowledgements),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // 已下载模型
            SectionHeader(stringResource(R.string.about_downloaded_models))
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    if (models.isEmpty()) {
                        Text(
                            stringResource(R.string.about_no_models),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        models.forEach { (name, size) ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                Text(
                                    name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    formatSize(size),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    Text(
                        stringResource(R.string.label_model_dir) + ": " +
                            VoiceModelManager.modelRoot(context).absolutePath,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

private fun formatSize(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024) String.format("%.2f GB", mb / 1024) else String.format("%.1f MB", mb)
}

private fun versionName(context: android.content.Context): String = try {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    info.versionName ?: ""
} catch (_: Throwable) {
    ""
}
