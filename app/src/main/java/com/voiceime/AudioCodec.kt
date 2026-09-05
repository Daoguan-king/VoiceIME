package com.voiceime

/**
 * 音频采集公共常量与 PCM 工具（此前在 VoiceImeService / TestVoiceController /
 * StreamingPipeline 各复制一份，此处为唯一来源，防止再次漂移）。
 */
object AudioCodec {

    /** 采集/识别采样率（识别器与 VAD 均要求 16 kHz） */
    const val SAMPLE_RATE = 16_000

    /** 单次录音最长时长（5 分钟），防止内存无限增长 */
    const val MAX_SESSION_MS = 300_000L

    /** PCM16（小端）→ 归一化 Float [-1, 1] */
    fun pcmToFloat(pcm: ByteArray, offset: Int = 0, len: Int = pcm.size): FloatArray {
        val n = len / 2
        val out = FloatArray(n)
        var i = 0
        var pos = offset
        while (i < n) {
            val s = (pcm[pos + 1].toInt() shl 8) or (pcm[pos].toInt() and 0xFF)
            var f = s / 32768.0f
            if (f > 1f) f = 1f else if (f < -1f) f = -1f
            out[i] = f
            i++
            pos += 2
        }
        return out
    }

    /** 计算一段采样的 RMS 并映射到 0..1（-50dB ~ 0dB，供电平动画） */
    fun rmsLevel(samples: FloatArray): Float {
        if (samples.isEmpty()) return 0f
        var sum = 0.0
        for (s in samples) sum += s * s
        val rms = Math.sqrt(sum / samples.size)
        val db = 20.0 * Math.log10(rms.coerceAtLeast(1e-6))
        return ((db + 50.0) / 50.0).toFloat().coerceIn(0f, 1f)
    }
}
