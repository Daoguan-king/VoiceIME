/*
 * SPDX-FileCopyrightText: 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * SenseVoice 本地模型管理：目录定位、完整性检测、多源下载。
 * 模型目录（按优先级）：
 *   1. filesDir/models/sensevoice/<variant>/
 *   2. filesDir/models/sensevoice/            （扁平结构，兼容手动放置）
 *   3. externalFilesDir/models/sensevoice/...
 * 需要文件：tokens.txt + model.int8.onnx（small-int8）或 model.onnx（small-full）。
 */

package com.voiceime

import android.content.Context
import android.os.StatFs
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object VoiceModelManager {
    private const val TAG = "VoiceModelManager"

    const val VARIANT_INT8 = "small-int8"
    const val VARIANT_FULL = "small-full"

    /** 主源：BiBi-Keyboard 维护的镜像 zip（官方 sense-voice int8 模型的打包） */
    private const val INT8_ZIP_URL =
        "https://github.com/BryceWG/BiBi-Keyboard/releases/download/models/" +
            "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.zip"

    private const val FULL_ZIP_URL =
        "https://github.com/BryceWG/BiBi-Keyboard/releases/download/models/" +
            "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17.zip"

    /** 备源：HuggingFace 镜像（hf-mirror.com）单文件下载 */
    private const val HF_MIRROR_BASE =
        "https://hf-mirror.com/csukuangfj2/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main/"

    /** 按模型变体返回主源 zip 地址：small-full 为 fp32 完整版，其余为 int8 */
    fun downloadUrlFor(variant: String): String =
        if (variant == VARIANT_FULL) FULL_ZIP_URL else INT8_ZIP_URL

    fun modelRoot(context: Context): File = File(context.filesDir, "models/sensevoice")

    /**
     * 找到可用的模型目录；找不到返回 null。
     */
    fun resolveModelDir(context: Context, variant: String): File? {
        val roots = buildList {
            add(modelRoot(context))
            context.getExternalFilesDir(null)?.let { add(File(it, "models/sensevoice")) }
        }
        for (root in roots) {
            // 优先 variant 子目录，其次扁平目录
            val candidates = listOf(File(root, variant), root)
            for (dir in candidates) {
                if (isModelDir(dir)) return dir
            }
        }
        return null
    }

    fun isModelDir(dir: File): Boolean =
        File(dir, "tokens.txt").exists() &&
            (File(dir, "model.int8.onnx").exists() || File(dir, "model.onnx").exists())

    /** 按 variant 选择模型文件：small-full 优先 fp32，否则优先 int8，再回退 fp32 */
    fun selectModelFile(dir: File, variant: String): File {
        val int8 = File(dir, "model.int8.onnx")
        val f32 = File(dir, "model.onnx")
        return when {
            variant == VARIANT_FULL && f32.exists() -> f32
            int8.exists() -> int8
            f32.exists() -> f32
            else -> int8 // 报错由调用方处理
        }
    }

    /**
     * 下载模型（多源回退）：
     * 1. 自定义 URL（若提供）→ zip；
     * 2. 默认镜像 zip（BiBi）；
     * 3. HF 镜像单文件（tokens.txt + model.onnx/model.int8.onnx）。
     * 下载前检查磁盘空间，下载后校验 Content-Length 与文件存在性。
     */
    suspend fun downloadModel(context: Context, variant: String, customUrl: String?): Boolean =
        withContext(Dispatchers.IO) {
            val root = modelRoot(context)
            val needed = if (variant == VARIANT_FULL) 1_000L * 1024 * 1024 else 350L * 1024 * 1024
            if (!hasEnoughSpace(context, needed)) {
                Log.e(TAG, "Not enough disk space for model")
                return@withContext false
            }

            // 路径 1/2：zip（自定义或默认镜像）
            val zipCandidates = buildList {
                customUrl?.takeIf { it.isNotBlank() }?.let { add(it) }
                add(downloadUrlFor(variant))
            }
            for (url in zipCandidates) {
                val zip = File(context.cacheDir, "sensevoice-" + System.currentTimeMillis() + ".zip")
                try {
                    if (downloadFile(url, zip)) {
                        unzipToModelRoot(zip, root)
                        zip.delete()
                        if (isModelDir(root) || isModelDir(File(root, variant))) {
                            Log.i(TAG, "Model downloaded from zip: $url")
                            return@withContext true
                        }
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "Zip source failed: $url", t)
                } finally {
                    zip.delete()
                }
            }

            // 路径 3：HF 镜像单文件
            try {
                val modelName = if (variant == VARIANT_FULL) "model.onnx" else "model.int8.onnx"
                val tokensOk = downloadFile(HF_MIRROR_BASE + "tokens.txt", File(root, "tokens.txt"))
                val modelOk = downloadFile(HF_MIRROR_BASE + modelName, File(root, modelName))
                if (tokensOk && modelOk && isModelDir(root)) {
                    Log.i(TAG, "Model downloaded from HF mirror")
                    return@withContext true
                }
            } catch (t: Throwable) {
                Log.w(TAG, "HF mirror source failed", t)
            }

            false
        }

    /** 下载单个文件到 target；校验 HTTP 状态与 Content-Length。返回是否成功。 */
    private fun downloadFile(url: String, target: File): Boolean {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        try {
            connection.connect()
            if (connection.responseCode !in 200..299) {
                Log.w(TAG, "HTTP " + connection.responseCode + " for $url")
                return false
            }
            val expected = connection.contentLengthLong
            var written = 0L
            connection.inputStream.use { input ->
                FileOutputStream(target).use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        written += n
                    }
                }
            }
            if (expected > 0 && written != expected) {
                Log.w(TAG, "Size mismatch for $url: expected=$expected got=$written")
                return false
            }
            return true
        } catch (t: Throwable) {
            Log.w(TAG, "Download failed: $url", t)
            return false
        } finally {
            connection.disconnect()
        }
    }

    private fun hasEnoughSpace(context: Context, requiredBytes: Long): Boolean = try {
        val stat = StatFs(context.filesDir.absolutePath)
        stat.availableBytes >= requiredBytes
    } catch (t: Throwable) {
        Log.w(TAG, "Space check failed, assume ok", t)
        true
    }

    private fun unzipToModelRoot(zipFile: File, root: File) {
        root.mkdirs()
        ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val name = entry.name.substringAfterLast('/')
                // 只需要 tokens.txt 与 onnx 模型文件
                if (name == "tokens.txt" || name.endsWith(".onnx")) {
                    val out = File(root, name)
                    FileOutputStream(out).use { output ->
                        zis.copyTo(output, bufferSize = 64 * 1024)
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }
}
