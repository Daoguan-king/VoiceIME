package com.voiceime.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.voiceime.PermissionActivity
import com.voiceime.R
import com.voiceime.ui.SettingsViewModel

/** 首次启用引导：四步说明 + 实时状态勾选 */
@Composable
fun OnboardingScreen(vm: SettingsViewModel, onDownloadRequest: () -> Unit) {
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        Text(
            stringResource(R.string.onboard_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.onboard_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))

        StepCard(
            title = stringResource(R.string.step_ime_title),
            desc = stringResource(R.string.step_ime_desc),
            ok = vm.imeEnabled,
            okText = stringResource(R.string.status_ime_enabled),
            failText = stringResource(R.string.status_ime_disabled),
            actionText = stringResource(R.string.btn_open_ime_settings),
            onAction = { context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) },
        )
        StepCard(
            title = stringResource(R.string.step_mic_title),
            desc = stringResource(R.string.step_mic_desc),
            ok = vm.micGranted,
            okText = stringResource(R.string.status_mic_ok),
            failText = stringResource(R.string.status_mic_missing),
            actionText = stringResource(R.string.btn_grant_mic),
            onAction = {
                context.startActivity(
                    Intent(context, PermissionActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            },
        )
        StepCard(
            title = stringResource(R.string.step_model_title),
            desc = stringResource(R.string.step_model_desc),
            ok = vm.modelReady,
            okText = stringResource(R.string.status_model_ok),
            failText = stringResource(R.string.status_model_missing_short),
            actionText = stringResource(R.string.btn_download_model),
            onAction = onDownloadRequest,
        )
        StepCard(
            title = stringResource(R.string.step_trime_title),
            desc = stringResource(R.string.step_trime_desc),
            ok = null,
            okText = "",
            failText = "",
            actionText = null,
            onAction = null,
        )

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = { vm.completeOnboarding() },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.btn_start_use))
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** 单个引导步骤卡：ok = null 表示纯说明步骤（无状态/按钮） */
@Composable
private fun StepCard(
    title: String,
    desc: String,
    ok: Boolean?,
    okText: String,
    failText: String,
    actionText: String?,
    onAction: (() -> Unit)?,
) {
    ElevatedCard(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (ok != null) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (ok) Icons.Filled.CheckCircle else Icons.Filled.Close,
                        contentDescription = null,
                        tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.size(6.dp))
                    Text(
                        if (ok) okText else failText,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                }
            }
            if (actionText != null && onAction != null) {
                Spacer(Modifier.height(8.dp))
                Button(onClick = onAction) { Text(actionText) }
            }
        }
    }
}
