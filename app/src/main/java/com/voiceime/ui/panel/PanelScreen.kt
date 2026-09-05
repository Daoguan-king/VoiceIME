package com.voiceime.ui.panel

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voiceime.R
import com.voiceime.UiParams
import kotlin.math.abs
import kotlin.math.max

/**
 * 语音识别面板（替代旧 ime_root.xml + LevelView）：
 * 状态/预览文本 + 电平动画 + 大号开始/结束按钮 + 语言切换 Chip。
 */
@Composable
fun PanelScreen(
    state: PanelUiState,
    anim: UiParams.AnimValues,
    onToggle: () -> Unit,
    onCycleLanguage: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 2.dp) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(top = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = state.statusText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )

            LevelBar(
                level = state.level,
                recording = state.recording,
                anim = anim,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, bottom = 4.dp)
                    .height(36.dp),
            )

            Button(
                onClick = onToggle,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .height(64.dp),
                shape = RoundedCornerShape(32.dp),
                colors = if (state.recording) {
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    )
                } else {
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                },
            ) {
                Text(
                    stringResource(if (state.recording) R.string.btn_stop else R.string.btn_start),
                    fontSize = 20.sp,
                )
            }

            AssistChip(
                onClick = onCycleLanguage,
                label = {
                    Text(
                        stringResource(R.string.lang_button_prefix) + state.languageLabel,
                        fontSize = 13.sp,
                    )
                },
                modifier = Modifier.padding(top = 12.dp),
            )
            Spacer(Modifier.height(4.dp))
        }
    }
}

/** 电平动画（原 LevelView 的 Compose 移植：帧级平滑 + 慢速流动相位，避免高频频闪残影；参数可调） */
@Composable
fun LevelBar(level: Float, recording: Boolean, anim: UiParams.AnimValues, modifier: Modifier = Modifier) {
    // 相位周期可调（默认 1200ms；波形过快会在 OLED 上产生残影）
    val phase by rememberInfiniteTransition(label = "levelBar").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(durationMillis = anim.phaseCycleMs.coerceIn(200, 3000), easing = LinearEasing),
        ),
        label = "phase",
    )
    // 目标电平平滑过渡（录音起停不突变）
    val smoothed by animateFloatAsState(
        targetValue = if (recording) level.coerceIn(0f, 1f) else 0f,
        animationSpec = tween(durationMillis = 200),
        label = "level",
    )
    val primary = MaterialTheme.colorScheme.primary
    val barBrush = Brush.verticalGradient(
        0f to primary.copy(alpha = 0.65f),
        1f to primary,
    )
    // 逐条电平状态（帧级平滑承载在数组上，与原 LevelView 的 levels[] 一致）
    val levels = remember { FloatArray(BAR_COUNT) { IDLE_LEVEL } }

    Canvas(modifier) {
        val target = if (recording) (smoothed * TARGET_RATIO + TARGET_FLOOR).coerceAtMost(1f) else IDLE_LEVEL
        val gap = size.width / (BAR_COUNT * GAP_RATIO)
        val barW = gap * BAR_WIDTH_RATIO
        val maxH = size.height * MAX_HEIGHT_RATIO
        val minBarH = 3.dp.toPx()
        val clusterW = BAR_COUNT * gap
        val startX = (size.width - clusterW) / 2f
        val smoothing = anim.smoothing.coerceIn(0.05f, 1f)
        for (i in 0 until BAR_COUNT) {
            // 动画关闭时所有条同步贴近目标电平（无波动）
            val noise = if (anim.levelAnimation) {
                val wave = ((phase * anim.waveSpeed + i * anim.phaseStep) % 1f)
                abs(wave - 0.5f) * anim.noiseAmplitude + anim.noiseFloor
            } else {
                1f
            }
            val desired = (target * noise).coerceIn(MIN_BAR_LEVEL, 1f)
            // 帧级平滑：每帧向目标靠近（滤掉高频抖动）
            levels[i] += (desired - levels[i]) * smoothing
            val bh = max(minBarH, levels[i] * maxH)
            val y = (size.height - bh) / 2f
            val x = startX + gap * 0.5f + i * gap
            drawRoundRect(
                brush = barBrush,
                topLeft = Offset(x, y),
                size = Size(barW, bh),
                cornerRadius = CornerRadius(barW / 2f),
            )
        }
    }
}

private const val BAR_COUNT = 24
private const val IDLE_LEVEL = 0.08f
private const val MIN_BAR_LEVEL = 0.08f
private const val TARGET_RATIO = 0.9f
private const val TARGET_FLOOR = 0.1f
private const val GAP_RATIO = 1.5f
private const val BAR_WIDTH_RATIO = 0.6f
private const val MAX_HEIGHT_RATIO = 0.92f
