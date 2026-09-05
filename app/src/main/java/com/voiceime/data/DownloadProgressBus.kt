package com.voiceime.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 应用内下载进度总线：镜像 DownloadNotifier 的进度（通知栏与应用内共享同一数据源）。
 * state 为 null 表示当前没有进行中的下载。
 */
object DownloadProgressBus {

    /** 一条下载进度快照 */
    data class State(
        val modelLabel: String,
        val phase: String,
        val downloaded: Long,
        val total: Long,
    ) {
        /** total<=0 时为不确定进度（多文件/HF 镜像） */
        val indeterminate: Boolean get() = total <= 0
    }

    private val _state = MutableStateFlow<State?>(null)
    val state: StateFlow<State?> = _state

    fun update(modelLabel: String, phase: String, downloaded: Long, total: Long) {
        _state.value = State(modelLabel, phase, downloaded, total)
    }

    /** 下载结束（成功或失败都清空，结束态由 Toast/通知呈现） */
    fun clear() {
        _state.value = null
    }
}
