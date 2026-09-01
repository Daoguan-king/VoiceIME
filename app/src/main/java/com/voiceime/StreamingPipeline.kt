package com.voiceime

import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.max
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 流式解码管线（从 VoiceImeService 拆出）：
 * - 已完成语音段：有界队列 + 多路并发解码 + 乱序重排，逐行固化；
 * - 滑动窗口预览（官方 Home.kt 移植）：每 previewIntervalMs 对当前段重识别，覆盖预览行。
 *
 * 线程安全：共享状态统一由 [lock] 保护；workerJobs 用 CopyOnWriteArrayList。
 * 所有回调（decode/onPreview）不得在 [lock] 内调用。
 */
class StreamingPipeline(
    private val scope: CoroutineScope,
    private val sessionProvider: () -> Long,
    private val recordingProvider: () -> Boolean,
    private val workersProvider: () -> Int,
    private val previewIntervalMsProvider: () -> Long,
    private val previewCharsProvider: () -> Int,
    private val decode: (FloatArray) -> String,
    private val onPreview: (fixedLines: List<String>, previewLine: String) -> Unit,
) {
    companion object {
        private const val MAX_PARTIAL_QUEUE = 64
        private const val PREVIEW_BACKOFF_BYTES = 12_800 // 0.4s
        private const val MIN_PREVIEW_BYTES = 3_200 // 0.1s

        /** 预览最多显示行数（含省略提示行） */
        const val MAX_LINES = 3
    }

    private val lock = Any()

    // ---- 已完成段（固定行）----
    private val fixedLines = mutableListOf<String>()
    private val partialQueue = ArrayDeque<FloatArray>()
    private val workerJobs = CopyOnWriteArrayList<Job>()
    private var workersStarted = false
    private var nextSegmentSeq = 0L
    private var nextAppendSeq = 0L
    private val pendingTexts = HashMap<Long, String>()

    // ---- 滑动窗口预览 ----
    private val segmentBuffer = ByteArrayOutputStream()
    private var vadFedBytes = 0
    private var speechStartByteOffset = 0

    @Volatile
    private var isSpeechStarted = false

    @Volatile
    private var lastPreviewAtMs = 0L

    @Volatile
    private var lastPreviewText = ""

    private var previewJob: Job? = null

    // ---------------- 会话生命周期 ----------------

    /** 会话开始：启动滑动窗口预览协程 */
    fun onSessionStart() {
        previewJob?.cancel()
        val jobSession = sessionProvider()
        previewJob = scope.launch(Dispatchers.IO) {
            try {
                while (sessionProvider() == jobSession && recordingProvider()) {
                    delay(previewIntervalMsProvider())
                    if (sessionProvider() != jobSession || !recordingProvider() || !isSpeechStarted) {
                        continue
                    }
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - lastPreviewAtMs < previewIntervalMsProvider()) continue
                    val snapshot: ByteArray? = synchronized(lock) {
                        val start = speechStartByteOffset
                        val end = vadFedBytes
                        if (end - start < MIN_PREVIEW_BYTES) null
                        else segmentBuffer.toByteArray().copyOfRange(start, end)
                    }
                    if (snapshot == null) continue
                    lastPreviewAtMs = now
                    try {
                        val text = decode(pcmToFloat(snapshot))
                        if (sessionProvider() == jobSession) {
                            val t = text.trim()
                            if (t.isNotEmpty()) {
                                lastPreviewText = t
                                emitPreview()
                            }
                        }
                    } catch (t: Throwable) {
                        android.util.Log.w("StreamingPipeline", "Preview decode failed", t)
                    }
                }
            } catch (t: Throwable) {
                android.util.Log.w("StreamingPipeline", "Preview worker stopped", t)
            }
        }
    }

    /** 会话结束/重置：停协程、清状态 */
    fun reset() {
        workerJobs.forEach { it.cancel() }
        workerJobs.clear()
        previewJob?.cancel()
        previewJob = null
        synchronized(lock) {
            fixedLines.clear()
            partialQueue.clear()
            pendingTexts.clear()
            nextSegmentSeq = 0L
            nextAppendSeq = 0L
            workersStarted = false
            segmentBuffer.reset()
            vadFedBytes = 0
            speechStartByteOffset = 0
        }
        isSpeechStarted = false
        lastPreviewText = ""
        lastPreviewAtMs = 0L
    }

    // ---------------- 录音线程入口 ----------------

    /** 每块 PCM 追加进当前段缓冲（同时累计已送 VAD 字节数） */
    fun onChunkPcm(raw: ByteArray, len: Int) {
        synchronized(lock) {
            segmentBuffer.write(raw, 0, len)
            vadFedBytes += len
        }
    }

    /** VAD 首次检测到语音：记录滑动窗口起点（回退 0.4s） */
    fun onSpeechDetected() {
        if (isSpeechStarted) return
        isSpeechStarted = true
        synchronized(lock) {
            speechStartByteOffset = max(0, vadFedBytes - PREVIEW_BACKOFF_BYTES)
        }
        lastPreviewAtMs = 0L
    }

    /** VAD 出段：段音频入队（并发解码固化一行），清空滑动窗口 */
    fun onSegmentCompleted(samples: FloatArray) {
        if (samples.isNotEmpty()) {
            synchronized(lock) {
                if (partialQueue.size >= MAX_PARTIAL_QUEUE) partialQueue.removeFirst()
                partialQueue.addLast(samples)
            }
            ensurePartialWorkers()
        }
        isSpeechStarted = false
        lastPreviewText = ""
        synchronized(lock) {
            segmentBuffer.reset()
            vadFedBytes = 0
            speechStartByteOffset = 0
        }
    }

    // ---------------- 段解码：并发 worker + 乱序重排 ----------------

    private fun ensurePartialWorkers() {
        val shouldStart = synchronized(lock) {
            if (workersStarted) false else {
                workersStarted = true
                true
            }
        }
        if (!shouldStart) return
        val jobSession = sessionProvider()
        val workers = workersProvider().coerceIn(1, 8)
        repeat(workers) {
            workerJobs += scope.launch(Dispatchers.IO) {
                try {
                    while (sessionProvider() == jobSession && scope.isActive) {
                        val seg = synchronized(lock) { partialQueue.removeFirstOrNull() }
                            ?: run {
                                delay(30)
                                continue
                            }
                        val seq = synchronized(lock) { nextSegmentSeq++ }
                        try {
                            val text = decode(seg)
                            if (text.isNotBlank() && sessionProvider() == jobSession) {
                                appendOrdered(seq, text.trim(), jobSession)
                            }
                        } catch (t: Throwable) {
                            android.util.Log.w("StreamingPipeline", "Partial decode failed", t)
                        }
                    }
                } catch (t: Throwable) {
                    android.util.Log.w("StreamingPipeline", "Partial worker stopped", t)
                }
            }
        }
    }

    private fun appendOrdered(seq: Long, text: String, jobSession: Long) {
        if (sessionProvider() != jobSession) return
        var toAppend: List<String>? = null
        synchronized(lock) {
            when {
                seq == nextAppendSeq -> {
                    nextAppendSeq++
                    val list = mutableListOf(text)
                    while (pendingTexts.containsKey(nextAppendSeq)) {
                        list.add(pendingTexts.remove(nextAppendSeq)!!)
                        nextAppendSeq++
                    }
                    toAppend = list
                }
                seq > nextAppendSeq -> {
                    if (seq - nextAppendSeq <= workersProvider().coerceIn(1, 8) * 4) {
                        pendingTexts[seq] = text
                    } else {
                        // 乱序跨度超过窗口：丢弃过期段。removeAll 在 Kotlin 迭代器
                        // 语义下边遍历边删是安全的（MutableIterator.remove），
                        // 这里只删 key 小于 seq 的过期项，不会跳过未检查项
                        pendingTexts.keys.removeAll { it < seq }
                        nextAppendSeq = seq
                        pendingTexts.remove(seq)
                        nextAppendSeq++
                        toAppend = listOf(text)
                    }
                }
                else -> Unit
            }
        }
        if (toAppend != null && sessionProvider() == jobSession) {
            synchronized(lock) { fixedLines.addAll(toAppend) }
            emitPreview()
        }
    }

    // ---------------- 预览输出 ----------------

    private fun emitPreview() {
        val fixed: List<String>
        synchronized(lock) { fixed = fixedLines.toList() }
        onPreview(fixed, lastPreviewText)
    }

    private fun pcmToFloat(pcm: ByteArray): FloatArray {
        if (pcm.isEmpty()) return FloatArray(0)
        val n = pcm.size / 2
        val out = FloatArray(n)
        var i = 0
        var pos = 0
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
}
