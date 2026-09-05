package com.voiceime

import java.io.File

/**
 * 识别器缓存 + 旧实例延迟释放（此前在 VoiceImeService 与 TestVoiceController 各有一份：
 * key 拼接逐字符相同、释放策略不同——测试版立即 release 存在在途解码 use-after-free
 * 的隐患，此处统一为延迟释放，两侧同时获得防护）。
 *
 * 解码本身不持锁（同一识别器可并发解码多个语音段，与官方 Demo 一致）；
 * 创建/重建/延迟释放由 [lock] 保护。
 */
class RecognizerCache(private val tag: String) {

    private val lock = Any()
    private var recognizer: VoiceRecognizer? = null
    private var key = ""

    /** 识别器缓存 key：模型 id | 目录 | 语言 | 解码线程数（任一变化即重建） */
    fun buildKey(spec: ModelSpec, modelDir: File, language: String, numThreads: Int): String =
        "${spec.id}|${modelDir.absolutePath}|$language|${numThreads.coerceIn(1, 8)}"

    /**
     * 取得与 [newKey] 匹配的识别器；不匹配则重建（旧实例延迟释放）。
     * 创建失败时异常原样抛出，已缓存的旧实例不受影响（保持可用）。
     * 返回前在锁内登记在途解码，须与 rec.endDecode() 成对调用（finally 中）。
     */
    fun acquire(
        newKey: String,
        modelDir: File,
        spec: ModelSpec,
        language: String,
        numThreads: Int,
        useItn: Boolean = true,
    ): VoiceRecognizer? = synchronized(lock) {
        val current = if (key == newKey) {
            recognizer
        } else {
            val old = recognizer
            val created = VoiceRecognizer(
                modelDir = modelDir,
                spec = spec,
                language = language,
                useItn = useItn,
                numThreads = numThreads.coerceIn(1, 8),
            )
            recognizer = created
            key = newKey
            // 锁内调用 releaseLater：releaseLater 只检查旧实例自己的在途计数，
            // 不会误释放刚创建的新实例
            old?.let { releaseLater(it) }
            created
        }
        // 关键：在锁内登记在途解码，避免 releaseLater 看到计数为 0 而提前 release
        current?.beginDecode()
        current
    }

    /** 释放当前识别器（onDestroy 等；若有在途解码，由守护线程等待结束后释放） */
    fun releaseAll() = synchronized(lock) {
        recognizer?.let { releaseLater(it) }
        recognizer = null
        key = ""
    }

    /** 旧识别器延迟释放：等它自己实例的在途解码全部结束后再 release（须在锁内调用） */
    private fun releaseLater(old: VoiceRecognizer) {
        if (old.inflightCount() == 0) {
            old.release()
            return
        }
        // 用独立守护线程等待，不随任何协程 scope 取消——否则 Service 销毁/切换模型时
        // 协程被取消，旧识别器永不释放（原生内存泄漏）
        Thread {
            try {
                while (old.inflightCount() > 0) {
                    Thread.sleep(50)
                }
                old.release()
            } catch (t: Throwable) {
                AppLog.w(tag, "Failed to release old recognizer", t)
            }
        }.apply { isDaemon = true }.start()
    }
}
