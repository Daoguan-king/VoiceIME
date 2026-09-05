package com.voiceime

import android.content.res.AssetManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import com.k2fsa.sherpa.onnx.Vad
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

/**
 * 录音引擎（[VoiceImeService] 与 [TestVoiceController] 共用，替代此前两份复制的录音栈）：
 * - AudioRecord 采集（16 kHz / 单声道 / PCM16），整段 PCM 累积（上限 [AudioCodec.MAX_SESSION_MS]）；
 * - Ten-VAD 驱动：首次检测到语音记回退 0.4s 起点、静音超时自动停止、出段入 pipeline 固化一行；
 * - VAD 不可用时按时间分片降级出段（此前仅 Service 版有此分支，测试版已漂移丢失，此处统一）；
 * - 每块音频回调电平（LevelBar 动画）。
 *
 * 线程模型：[recordLoop] 在单个录音协程内调用；onLevel/onStopRequest 回调来自录音线程，
 * 回调方须保证线程安全（StateFlow.update 天然安全）。
 */
class RecordingEngine(
    private val tag: String,
    private val debug: DebugParams.Values,
    assets: AssetManager,
    private val pipeline: StreamingPipeline,
    private val onLevel: (Float) -> Unit,
    /** VAD 静音超时 / 最长时长触发：调用方置外部停止标志，recordLoop 随后退出 */
    private val onStopRequest: () -> Unit,
) {
    companion object {
        // 时间分片降级参数（VAD 不可用时使用）
        private const val PARTIAL_INTERVAL_MS = 700L
        private const val MIN_SEGMENT_BYTES = 19_200
    }

    private val pcmBuffer = ByteArrayOutputStream()
    private val bufferLock = Any()

    private val vad: Vad? = VadFactory.create(assets, debug, tag)
    private var hasDetectedSpeech = false
    private var lastSpeechAtMs = 0L
    private var sessionStartMs = 0L

    // 时间分片降级（VAD 不可用）用的游标
    private var lastPartialByteOffset = 0
    private var lastPartialAtMs = 0L

    /**
     * 录音循环：直到 [isRunning] 返回 false，或 VAD 静音超时/最长时长触发自动停止。
     * 返回整段累积 PCM（整段复核用，与流式出段共用同一份数据）。
     */
    suspend fun recordLoop(isRunning: () -> Boolean): ByteArray {
        val minBuf = AudioRecord.getMinBufferSize(
            AudioCodec.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) {
            AppLog.e(tag, "AudioRecord.getMinBufferSize invalid: $minBuf")
            return ByteArray(0)
        }
        // 读取粒度可调（官方 Demo 为 32ms/512 samples），越小 VAD 响应越快
        val bufferSize = maxOf(minBuf, AudioCodec.SAMPLE_RATE / 1000 * debug.readChunkMs * 2)
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            AudioCodec.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize,
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            AppLog.e(tag, "AudioRecord init failed")
            record.release()
            return ByteArray(0)
        }
        val buf = ByteArray(bufferSize)
        sessionStartMs = SystemClock.elapsedRealtime()
        try {
            record.startRecording()
            while (isRunning() && currentCoroutineContext().isActive) {
                // 单次录音时长上限，防止内存无限增长
                if (SystemClock.elapsedRealtime() - sessionStartMs > AudioCodec.MAX_SESSION_MS) {
                    AppLog.i(tag, "max session duration reached, auto stop")
                    onStopRequest()
                    break
                }
                val n = record.read(buf, 0, buf.size)
                when {
                    n > 0 -> {
                        synchronized(bufferLock) { pcmBuffer.write(buf, 0, n) }
                        val samples = AudioCodec.pcmToFloat(buf, 0, n)
                        onLevel(AudioCodec.rmsLevel(samples))
                        processAudioChunk(samples, buf, n)
                    }
                    n < 0 -> break
                }
            }
        } finally {
            try {
                record.stop()
            } catch (_: Throwable) {
                // 已停止
            }
            record.release()
        }
        return synchronized(bufferLock) { pcmBuffer.toByteArray() }
    }

    /** 释放 VAD 原生资源；录音协程结束（finally 中）必须调用一次 */
    fun close() {
        VadFactory.release(vad)
    }

    /**
     * 每块 PCM 送入 VAD：
     * 1. 音频累积进 pipeline 滑动窗口，首次检测到语音记录回退 0.4s 起点；
     * 2. 连续静音超过阈值 → 自动结束录音；
     * 3. VAD 出段 → pipeline 入队并发解码固化一行。
     */
    private fun processAudioChunk(samples: FloatArray, raw: ByteArray, rawLen: Int) {
        val v = vad
        if (v == null) {
            maybePartialTimeBased()
            return
        }
        try {
            v.acceptWaveform(samples)
            pipeline.onChunkPcm(raw, rawLen)
            val now = SystemClock.elapsedRealtime()
            if (v.isSpeechDetected()) {
                hasDetectedSpeech = true
                lastSpeechAtMs = now
                pipeline.onSpeechDetected()
            } else if (hasDetectedSpeech && now - lastSpeechAtMs >= debug.autoStopMs) {
                // 静音自动结束
                AppLog.i(tag, "auto stop on silence timeout")
                onStopRequest()
                return
            }
            // 出队已完成的语音段 → 固化一行
            while (!v.empty()) {
                val seg = v.front()
                val segSamples = seg.samples
                v.pop()
                AppLog.i(tag, "VAD segment: ${segSamples.size} samples")
                pipeline.onSegmentCompleted(segSamples)
            }
        } catch (t: Throwable) {
            AppLog.w(tag, "VAD processing failed", t)
        }
    }

    /** 降级方案：按时间分片做预览（VAD 不可用时） */
    private fun maybePartialTimeBased() {
        val now = SystemClock.elapsedRealtime()
        val segment: ByteArray? = synchronized(bufferLock) {
            val total = pcmBuffer.size()
            val pending = total - lastPartialByteOffset
            if (now - lastPartialAtMs >= PARTIAL_INTERVAL_MS && pending >= MIN_SEGMENT_BYTES) {
                val seg = pcmBuffer.toByteArray().copyOfRange(lastPartialByteOffset, total)
                lastPartialByteOffset = total
                lastPartialAtMs = now
                seg
            } else {
                null
            }
        }
        val seg = segment ?: return
        pipeline.onSegmentCompleted(AudioCodec.pcmToFloat(seg))
    }
}
