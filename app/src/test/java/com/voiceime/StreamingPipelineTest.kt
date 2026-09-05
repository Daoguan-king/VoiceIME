package com.voiceime

import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [StreamingPipeline] 乱序重排窗口（appendOrdered）的 JVM 单测。
 * 用真实 IO dispatcher + 门闩控制 worker 的解码完成顺序，
 * 验证最终固化行始终按段序号（seq）输出。
 */
class StreamingPipelineTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun tearDown() {
        scope.cancel()
    }

    /** 段索引编码在首采样里，decode 据此分辨是第几段 */
    private fun makePipeline(
        workers: Int,
        decode: (FloatArray) -> String,
        onPreview: (List<String>, String) -> Unit,
    ) = StreamingPipeline(
        scope = scope,
        sessionProvider = { 1L },
        recordingProvider = { true },
        workersProvider = { workers },
        previewIntervalMsProvider = { 250L },
        previewCharsProvider = { 128 },
        decode = decode,
        onPreview = onPreview,
    )

    private fun awaitFixed(
        previews: CopyOnWriteArrayList<List<String>>,
        expected: List<String>,
    ): List<String> {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            val last = previews.lastOrNull()
            if (last != null && last.size >= expected.size && last.take(expected.size) == expected) {
                return last
            }
            Thread.sleep(20)
        }
        throw AssertionError("timeout waiting for fixed lines $expected, got $previews")
    }

    @Test
    fun segments_append_in_sequence_order_despite_out_of_order_decode() {
        val started = Array(3) { CountDownLatch(1) }
        val release = Array(3) { CountDownLatch(1) }
        val previews = CopyOnWriteArrayList<List<String>>()
        val pipeline = makePipeline(
            workers = 2,
            decode = { seg ->
                val idx = seg[0].toInt()
                started[idx].countDown()
                release[idx].await()
                "t$idx"
            },
            onPreview = { fixed, _ -> previews.add(fixed) },
        )

        (0 until 3).forEach { i -> pipeline.onSegmentCompleted(floatArrayOf(i.toFloat())) }
        // 前两段已被两个 worker 取走并挂起（此时 seg2 在队列中）
        assertTrue(started[0].await(5, TimeUnit.SECONDS))
        assertTrue(started[1].await(5, TimeUnit.SECONDS))
        // 先完成 seq1（乱序 → 应暂存），再完成 seq0（应连带吐出 t0、t1），最后 t2
        release[1].countDown()
        Thread.sleep(200)
        release[0].countDown()
        Thread.sleep(200)
        release[2].countDown()

        assertEquals(listOf("t0", "t1", "t2"), awaitFixed(previews, listOf("t0", "t1", "t2")))
        pipeline.reset()
    }

    @Test
    fun segment_gap_beyond_window_drops_stale_and_resyncs() {
        // workers=2 → 乱序容忍窗口 2*4=8；seg0 挂住，seq9 到达时跨度 9 > 8
        // → 视为流断裂：丢弃过期暂存、跳到 seq9；随后补完的 seq0 不得再上屏
        val started0 = CountDownLatch(1)
        val release0 = CountDownLatch(1)
        val previews = CopyOnWriteArrayList<List<String>>()
        val pipeline = makePipeline(
            workers = 2,
            decode = { seg ->
                val idx = seg[0].toInt()
                if (idx == 0) {
                    started0.countDown()
                    release0.await()
                }
                "t$idx"
            },
            onPreview = { fixed, _ -> previews.add(fixed) },
        )

        (0..9).forEach { i -> pipeline.onSegmentCompleted(floatArrayOf(i.toFloat())) }
        assertTrue(started0.await(5, TimeUnit.SECONDS))
        Thread.sleep(2000) // 让另一个 worker 把 seq1..9 全部处理完
        release0.countDown()

        assertEquals(listOf("t9"), awaitFixed(previews, listOf("t9")))
        pipeline.reset()
    }
}
