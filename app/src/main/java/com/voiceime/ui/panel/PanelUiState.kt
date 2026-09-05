package com.voiceime.ui.panel

/** 语音面板 UI 状态（录音线程推送，Compose 收集渲染） */
data class PanelUiState(
    val recording: Boolean = false,
    val statusText: String = "",
    val level: Float = 0f,
    val languageLabel: String = "",
)
