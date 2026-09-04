/*
 * SPDX-FileCopyrightText: 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 本地 ASR 模型管理：目录定位、完整性检测、多源下载。
 * 支持 zip / tar.bz2 / tar.gz 压缩包（自动剥离单层顶层目录），
 * 也支持 HF 镜像逐文件下载（含 tokenizer/ 子目录）。
 *
 * 模型目录（默认）：/Android/data/<pkg>/files/models/<dirName>/
 *   —— 外部存储，文件管理器可访问（Android 11+ 部分管理器受限），无需存储权限；
 * 回退目录：filesDir/models/<dirName>/（存储不可用时）
 * 兼容旧布局：models/sensevoice/<variant>/ 或扁平 models/sensevoice/
 */

package com.voiceime

import android.content.Context
import android.os.StatFs
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream

object VoiceModelManager {
    private const val TAG = "VoiceModelManager"

    /**
     * 解压用读取缓冲大小（1MB，≥ bzip2 最大块 900KB）。
     * 必须用大缓冲包装 FileInputStream：commons-compress 的 BZip2 位读取器
     * 逐字节调用 in.read()，直连文件流会退化成"每字节一次 JNI 系统调用"，
     * 解压速度慢一个数量级以上。
     */
    private const val EXTRACT_BUFFER_SIZE = 1024 * 1024

    /**
     * 模型根目录：优先外部存储（/Android/data/<pkg>/files/models，用户可访问），
     * 外部存储不可用时回退应用私有目录。
     */
    fun modelRoot(context: Context): File {
        val external = context.getExternalFilesDir(null)
        return if (external != null) File(external, "models") else File(context.filesDir, "models")
    }

    private fun specDir(context: Context, spec: ModelSpec): File =
        File(modelRoot(context), spec.dirName)

    /**
     * 找到可用的模型目录；找不到返回 null。
     * 外部存储优先，其次内部（兼容旧版下载/手动放置的模型）。
     */
    fun resolveModelDir(context: Context, spec: ModelSpec): File? {
        val bases = buildList {
            context.getExternalFilesDir(null)?.let { add(it) }
            add(context.filesDir)
        }
        for (base in bases) {
            val rels = buildList {
                add("models/${spec.dirName}")
                if (spec.kind == ModelKind.SENSE_VOICE) {
                    val variant = if (spec.id == AsrModels.SENSE_VOICE_FULL.id) "small-full" else "small-int8"
                    add("models/sensevoice/$variant")
                    add("models/sensevoice")
                }
            }
            for (rel in rels) {
                val dir = File(base, rel)
                if (isModelReady(dir, spec)) return dir
            }
        }
        return null
    }

    /**
     * 删除模型文件以节省空间：清理新布局目录 + 兼容的旧 SenseVoice 布局目录。
     * 返回是否有任何目录被删除。已加载进内存的识别器不受影响，
     * 下次录音时会发现模型缺失并自动重新下载。
     */
    fun deleteModel(context: Context, spec: ModelSpec): Boolean {
        val dirs = buildList {
            add(File(modelRoot(context), spec.dirName))
            if (spec.kind == ModelKind.SENSE_VOICE) {
                val variant = if (spec.id == AsrModels.SENSE_VOICE_FULL.id) "small-full" else "small-int8"
                add(File(File(modelRoot(context), "sensevoice"), variant))
                add(File(modelRoot(context), "sensevoice"))
            }
        }.distinct()
        var deleted = false
        for (dir in dirs) {
            if (dir.exists()) {
                AppLog.i(TAG, "删除模型目录: ${dir.absolutePath}")
                dir.deleteRecursively()
                deleted = true
            }
        }
        return deleted
    }

    /** 目录是否包含该模型所需的全部文件（含 tokenizer 目录） */
    fun isModelReady(dir: File, spec: ModelSpec): Boolean {
        if (!dir.isDirectory) return false
        if (spec.files.any { !File(dir, it).isFile }) return false
        if (spec.tokenizerDir) {
            val tok = File(dir, "tokenizer")
            if (!tok.isDirectory) return false
            if (!File(tok, "vocab.json").isFile && !File(tok, "tokenizer.json").isFile) return false
        }
        return true
    }

    /**
     * 把明文 tokens.txt 转成 base64 格式（Moonshine v2 兼容）。
     *
     * 背景：sherpa-onnx 的 Moonshine v2 实现会把 tokens.txt 无条件按 base64
     * 解码（offline-recognizer-moonshine-v2-impl.h → SymbolTable::ApplyBase64Decode），
     * 其 Base64Decode 遇到 <unk> 等特殊 token 的 '<' 会 SHERPA_ONNX_EXIT(-1)
     * 直接杀死进程（无崩溃日志）。官方 Moonshine 模型的 tokens.txt 全部是
     * base64（scripts/moonshine/v2/generate_tokens.py），而 manyeyes 魔搭
     * 镜像给出的是明文 SentencePiece 格式，二者不兼容。
     *
     * 转换规则与官方脚本一致：每个 token 的 UTF-8 字节整体 base64，行内保持
     * "base64 id"。特例：<0xNN> 字节回退 token 不编码字面字符串，而是编码
     * 对应的原始单字节——否则 ApplyBase64Decode 后 SymbolTable 返回字面
     * "<0xA1>" 并出现在识别文本里（SymbolTable 的字节回退检测发生在 base64
     * 解码之前，全 base64 文件不会置位该标志，只能靠 token 本身携带裸字节）。
     *
     * 幂等：明文文件、旧版误转文件（字节 token 编码成了字面 "<0xNN>"）、
     * 已正确的 base64 文件三种状态均可识别并只做必要的重写。
     */
    fun ensureMoonshineBase64Tokens(dir: File) {
        try {
            val f = File(dir, "tokens.txt")
            if (!f.isFile) return
            val lines = f.readLines().filter { it.isNotBlank() }
            if (lines.isEmpty()) return
            val b64Chars = Regex("^[A-Za-z0-9+/]+={0,2}$")
            val byteToken = Regex("^<0x([0-9A-Fa-f]{2})>$")
            val decoder = java.util.Base64.getDecoder()
            val encoder = java.util.Base64.getEncoder()

            fun decodeB64(s: String): ByteArray? =
                if (s.length % 4 == 0 && b64Chars.matches(s)) {
                    try {
                        decoder.decode(s)
                    } catch (_: Throwable) {
                        null
                    }
                } else {
                    null
                }

            // 第一步：每行还原为 (id, 解码后的字节)；allBase64=false 说明是明文文件。
            // 解析规则与 C++ ReadTokens 对齐：字段按空白分隔（多空格合法）；
            // 单字段行是"空 token"约定（FunASR 风格，如 base-zh 词表的
            // " 31353"，ReadTokens 解析为 " "，ApplyBase64Decode 再映射为 ""），
            // 转换时保持空字节即可原样保留该行；三个以上字段 C++ 会直接
            // exit，文件本身损坏，不做转换。
            data class Entry(val id: String, val bytes: ByteArray)
            val entries = mutableListOf<Entry>()
            var allBase64 = true
            for (line in lines) {
                val parts = line.trim().split(' ').filter { it.isNotEmpty() }
                when (parts.size) {
                    1 -> entries.add(Entry(parts[0], ByteArray(0)))
                    2 -> {
                        val sym = parts[0]
                        val decoded = decodeB64(sym)
                        if (decoded == null) allBase64 = false
                        entries.add(Entry(parts[1], decoded ?: sym.toByteArray(Charsets.UTF_8)))
                    }
                    else -> {
                        AppLog.w(TAG, "tokens.txt 存在异常行，跳过 base64 转换: ${dir.name}")
                        return
                    }
                }
            }

            // 第二步：计算目标字节。解码后仍为字面 "<0xNN>"（明文残留或旧版误转）
            // → 修正为原始单字节；明文 token → UTF-8 字节；正确 base64 → 保持
            var needRewrite = false
            val sb = StringBuilder(entries.size * 16)
            for (e in entries) {
                val text = String(e.bytes, Charsets.UTF_8)
                val m = byteToken.find(text)
                val target: ByteArray = when {
                    m != null -> {
                        needRewrite = true
                        byteArrayOf(m.groupValues[1].toInt(16).toByte())
                    }
                    !allBase64 -> {
                        needRewrite = true
                        e.bytes
                    }
                    else -> e.bytes
                }
                sb.append(encoder.encodeToString(target)).append(' ').append(e.id).append('\n')
            }
            if (!needRewrite) return
            // 原子替换，避免转换中途留下残缺文件
            val tmp = File(dir, "tokens.txt.part")
            tmp.writeText(sb.toString())
            if (!tmp.renameTo(f)) {
                tmp.copyTo(f, overwrite = true)
                tmp.delete()
            }
            AppLog.i(TAG, "tokens.txt 已转换为 base64 格式（Moonshine v2 兼容）: ${dir.name}")
        } catch (t: Throwable) {
            AppLog.w(TAG, "tokens.txt base64 转换失败: " + (t.message ?: t.javaClass.simpleName))
        }
    }

    /**
     * 下载模型（多源回退，通知栏显示进度，失败原因写入 AppLog）：
     * 1. 自定义 URL（若提供）→ 压缩包（zip / tar.bz2 / tar.gz，自动识别）；
     * 2. HF 镜像（hf-mirror.com）逐文件下载——无需解压，官方 tar.bz2 的
     *    bzip2 解压很慢，所以镜像优先于官方压缩包；
     * 3. 该模型的官方压缩包直链（最后回退）。
     * 下载前检查磁盘空间，解压后做完整性校验。
     */
    suspend fun downloadModel(context: Context, spec: ModelSpec, customUrl: String?): Boolean =
        withContext(Dispatchers.IO) {
            val root = specDir(context, spec)
            if (isModelReady(root, spec)) {
                AppLog.i(TAG, "模型已就绪，跳过下载: ${spec.id} @ ${root.absolutePath}")
                return@withContext true
            }
            AppLog.i(TAG, "开始下载模型: ${spec.id}（自定义源: ${customUrl ?: "无"}）")
            DownloadNotifier.progress(context, spec, 0, 0, context.getString(R.string.notifier_preparing, spec.label))

            if (!hasEnoughSpace(context, spec.requiredBytes)) {
                val msg = "磁盘空间不足（需要约 " + (spec.requiredBytes / 1048576) + " MB）"
                AppLog.e(TAG, msg)
                DownloadNotifier.failed(context, spec, msg)
                return@withContext false
            }
            root.mkdirs()

            // 路径 1：自定义源（压缩包）
            val custom = customUrl?.takeIf { it.isNotBlank() }
            if (custom != null && tryArchiveSource(context, spec, root, custom)) {
                return@withContext true
            }

            // 路径 2：逐文件镜像源（hf-mirror / modelscope 等，无解压，通常远快于 bzip2 解压）
            for (source in spec.sources) {
                if (trySource(context, spec, root, source)) {
                    return@withContext true
                }
            }

            // 路径 3：官方压缩包（tar.bz2 / zip 回退）
            if (spec.archiveUrl != null && tryArchiveSource(context, spec, root, spec.archiveUrl)) {
                return@withContext true
            }

            val msg = "所有下载源均失败，详见日志"
            AppLog.e(TAG, msg)
            DownloadNotifier.failed(context, spec, msg)
            false
        }

    /** 尝试从一个压缩包源下载+解压+校验；成功返回 true（失败原因已写日志） */
    private fun tryArchiveSource(
        context: Context,
        spec: ModelSpec,
        root: File,
        url: String,
    ): Boolean {
        val archive = File(context.cacheDir, "voiceime-model-" + System.currentTimeMillis() + ".bin")
        try {
            AppLog.i(TAG, "压缩包源: $url")
            val ok = downloadFile(url, archive) { done, total ->
                DownloadNotifier.progress(context, spec, done, total)
            }
            if (!ok) return false
            val hint = extractHint(context, archive)
            DownloadNotifier.progress(context, spec, 0, 0, hint)
            val archiveSize = archive.length()
            extractArchive(archive, root) { done, total ->
                DownloadNotifier.progress(context, spec, done, total, hint)
            }
            archive.delete()
            if (isModelReady(root, spec)) {
                AppLog.i(TAG, "模型下载成功（压缩包）: $url")
                DownloadNotifier.done(context, spec)
                return true
            }
            AppLog.w(TAG, "压缩包解压后校验不完整: $url")
            return false
        } catch (t: Throwable) {
            AppLog.w(TAG, "压缩包源失败: $url - " + (t.message ?: t.javaClass.simpleName))
            return false
        } finally {
            archive.delete()
        }
    }

    /** 逐文件镜像下载（含子目录，如 tokenizer/vocab.json）；成功返回 true */
    private fun trySource(context: Context, spec: ModelSpec, root: File, source: ModelSource): Boolean {
        try {
            var allOk = true
            var failedFile = ""
            for (rel in source.files) {
                val target = File(root, rel)
                target.parentFile?.mkdirs()
                AppLog.i(TAG, "镜像源[${source.name}]文件: $rel")
                val ok = downloadFile(source.baseUrl + rel, target) { done, total ->
                    DownloadNotifier.progress(context, spec, done, total, rel)
                }
                if (!ok) {
                    allOk = false
                    failedFile = rel
                    break
                }
            }
            if (allOk && isModelReady(root, spec)) {
                AppLog.i(TAG, "模型下载成功（镜像 ${source.name}）: ${spec.id}")
                DownloadNotifier.done(context, spec)
                return true
            }
            if (!allOk) AppLog.w(TAG, "镜像源[${source.name}]文件下载失败: $failedFile")
            return false
        } catch (t: Throwable) {
            AppLog.w(TAG, "镜像源[${source.name}]失败: " + (t.message ?: t.javaClass.simpleName))
            return false
        }
    }

    /** 根据压缩包格式返回解压提示文案（bzip2 慢，明确告知） */
    private fun extractHint(context: Context, archive: File): String = when (sniffFormat(archive)) {
        ArchiveFormat.BZIP2 -> context.getString(R.string.notifier_extracting_bzip2)
        else -> context.getString(R.string.notifier_extracting)
    }

    // ---------------- 压缩包解压 ----------------
    // 策略：单遍解压到 root/.extract-xxx/ 临时目录（保留原始相对路径），
    // 解压完成后计算公共顶层目录前缀，再把文件 rename 到目标位置。
    // 临时目录与目标同卷，rename 几乎零成本，避免"两遍解压"造成的巨慢。

    private enum class TarCompression { NONE, GZIP, BZIP2 }

    /** 自动识别格式：zip / tar.bz2 / tar.gz / 裸 tar；onProgress = (已解压消费的压缩字节, 压缩包总字节) */
    private fun extractArchive(archive: File, root: File, onProgress: (Long, Long) -> Unit) {
        root.mkdirs()
        val tmp = File(root, ".extract-" + System.currentTimeMillis())
        tmp.mkdirs()
        try {
            val rels = extractAll(archive, tmp, onProgress)
            relocate(tmp, root, rels)
        } finally {
            tmp.deleteRecursively()
        }
    }

    private fun extractAll(
        archive: File,
        tmp: File,
        onProgress: (Long, Long) -> Unit,
    ): List<String> = when (sniffFormat(archive)) {
        ArchiveFormat.ZIP -> {
            // zip 条目自带压缩，读取很快，不做逐字节进度
            val rels = mutableListOf<String>()
            ZipInputStream(archive.inputStream().buffered()).use { zis ->
                var e = zis.nextEntry
                while (e != null) {
                    if (!e.isDirectory) {
                        val rel = sanitizeRel(e.name)
                        if (rel != null && isWantedFile(rel)) {
                            writeEntry(zis, File(tmp, rel))
                            rels.add(rel)
                        }
                    }
                    zis.closeEntry()
                    e = zis.nextEntry
                }
            }
            rels
        }
        ArchiveFormat.BZIP2 -> extractTar(archive, tmp, TarCompression.BZIP2, onProgress)
        ArchiveFormat.GZIP -> extractTar(archive, tmp, TarCompression.GZIP, onProgress)
        ArchiveFormat.TAR -> extractTar(archive, tmp, TarCompression.NONE, onProgress)
        ArchiveFormat.UNKNOWN -> throw IOException("不支持的压缩包格式: ${archive.name}")
    }

    /** 压缩包格式：根据文件头魔数识别（zip / bzip2 / gzip / 裸 tar） */
    private enum class ArchiveFormat { ZIP, BZIP2, GZIP, TAR, UNKNOWN }

    private fun sniffFormat(archive: File): ArchiveFormat {
        val header = archive.inputStream().buffered().use { ins ->
            val buf = ByteArray(262)
            var off = 0
            while (off < buf.size) {
                val n = ins.read(buf, off, buf.size - off)
                if (n < 0) break
                off += n
            }
            buf
        }
        val isZip = header.size >= 2 && header[0] == 'P'.code.toByte() && header[1] == 'K'.code.toByte()
        val isBzip2 = header.size >= 3 && header[0] == 'B'.code.toByte() &&
            header[1] == 'Z'.code.toByte() && header[2] == 'h'.code.toByte()
        val isGzip = header.size >= 2 && header[0] == 0x1F.toByte() && header[1] == 0x8B.toByte()
        val isTar = header.size >= 262 && String(header, 257, 5) == "ustar"
        return when {
            isZip -> ArchiveFormat.ZIP
            isBzip2 -> ArchiveFormat.BZIP2
            isGzip -> ArchiveFormat.GZIP
            isTar -> ArchiveFormat.TAR
            else -> ArchiveFormat.UNKNOWN
        }
    }

    /**
     * 构造 tar 输入流，返回 (tar 流, 底层字节计数器)。
     * 关键：compressor 流必须架在 BufferedInputStream(counting) 之上——
     * BZip2 的位读取器逐字节调用 in.read()，不缓冲会把 FileInputStream
     * 打成"每字节一次 JNI 系统调用"，这是此前 bzip2 解压缓慢的直接原因。
     */
    private fun tarInput(
        file: File,
        compression: TarCompression,
    ): Pair<TarArchiveInputStream, CountingInputStream> {
        val counting = CountingInputStream(FileInputStream(file))
        val buffered = BufferedInputStream(counting, EXTRACT_BUFFER_SIZE)
        val dec: InputStream = when (compression) {
            TarCompression.NONE -> buffered
            TarCompression.GZIP -> GZIPInputStream(buffered)
            TarCompression.BZIP2 -> BZip2CompressorInputStream(buffered)
        }
        return TarArchiveInputStream(dec) to counting
    }

    private fun extractTar(
        archive: File,
        tmp: File,
        compression: TarCompression,
        onProgress: (Long, Long) -> Unit,
    ): List<String> {
        val rels = mutableListOf<String>()
        val archiveSize = archive.length().coerceAtLeast(1L)
        val (tar, counting) = tarInput(archive, compression)
        tar.use {
            var e = it.nextTarEntry
            while (e != null) {
                if (!e.isDirectory) {
                    val rel = sanitizeRel(e.name)
                    if (rel != null && isWantedFile(rel)) {
                        writeEntry(it, File(tmp, rel)) { onProgress(counting.bytesRead, archiveSize) }
                        rels.add(rel)
                    } else {
                        onProgress(counting.bytesRead, archiveSize)
                    }
                }
                e = it.nextTarEntry
            }
        }
        onProgress(archiveSize, archiveSize)
        return rels
    }

    /** 解压后只需要模型文件 + tokenizer 目录，跳过 test_wavs / README 等 */
    private fun isWantedFile(rel: String): Boolean {
        if (rel.startsWith("tokenizer/")) return true
        val name = rel.substringAfterLast('/')
        // .ort：Moonshine v2 官方包为 encoder_model.ort / decoder_model_merged.ort
        return name == "tokens.txt" || name.endsWith(".onnx") || name.endsWith(".ort")
    }

    /** 归一化条目路径；拒绝绝对路径与路径穿越 */
    private fun sanitizeRel(name: String): String? {
        val rel = name.replace('\\', '/').trimStart('/')
        if (rel.isBlank() || rel.startsWith("../") || rel.contains("/../")) return null
        return rel
    }

    /** 若所有条目共享同一顶层目录，则剥掉该前缀（如 sherpa-onnx-sense-voice-xxx/） */
    private fun commonRootPrefix(names: List<String>): String {
        if (names.isEmpty()) return ""
        val parts = names.map { it.split('/') }
        var len = 0
        outer@ while (len < parts[0].size - 1) {
            val p = parts[0][len]
            for (i in 1 until parts.size) {
                if (parts[i].getOrNull(len) != p) break@outer
            }
            len++
        }
        return if (len == 0) "" else parts[0].take(len).joinToString("/") + "/"
    }

    /** 把临时目录中的文件移动到目标位置（同卷 rename，失败回退复制） */
    private fun relocate(tmp: File, root: File, rels: List<String>) {
        val prefix = commonRootPrefix(rels)
        for (rel in rels) {
            val src = File(tmp, rel)
            val dst = File(root, rel.removePrefix(prefix))
            if (!src.isFile) continue
            dst.parentFile?.mkdirs()
            if (!src.renameTo(dst)) {
                src.copyTo(dst, overwrite = true)
                src.delete()
            }
        }
    }

    /** 底层字节计数流（解压进度用）：统计从磁盘实际读走的字节数 */
    private class CountingInputStream(private val src: InputStream) : InputStream() {
        var bytesRead: Long = 0
            private set

        override fun read(): Int {
            val r = src.read()
            if (r >= 0) bytesRead++
            return r
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val r = src.read(b, off, len)
            if (r > 0) bytesRead += r
            return r
        }

        override fun skip(n: Long): Long {
            val s = src.skip(n)
            if (s > 0) bytesRead += s
            return s
        }

        override fun available(): Int = src.available()

        override fun close() = src.close()
    }

    private fun writeEntry(
        input: InputStream,
        target: File,
        onProgress: (() -> Unit)? = null,
    ) {
        target.parentFile?.mkdirs()
        FileOutputStream(target).use { output ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                output.write(buf, 0, n)
                onProgress?.invoke()
            }
        }
    }

    // ---------------- 下载 ----------------

    /**
     * 下载单个文件到 target；校验 HTTP 状态与 Content-Length。
     * 先写同目录 .part 临时文件、成功后原子 rename：下载中途失败时不会在
     * 模型目录留下半截文件（否则 isModelReady 只查存在性会误判"已就绪"，
     * 加载残缺 onnx 会导致原生层直接退出）。
     * onProgress 回调 (已下载字节, 总字节)；失败原因写入 AppLog。返回是否成功。
     */
    private fun downloadFile(url: String, target: File, onProgress: (Long, Long) -> Unit): Boolean {
        target.parentFile?.mkdirs()
        val part = File(target.parentFile, target.name + ".part")
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        try {
            connection.connect()
            val code = connection.responseCode
            if (code !in 200..299) {
                AppLog.w(TAG, "HTTP $code for $url")
                return false
            }
            val expected = connection.contentLengthLong
            var written = 0L
            connection.inputStream.use { input ->
                FileOutputStream(part).use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        written += n
                        // 每 256KB 更新一次进度；最后不足一块的数据在循环结束后补回调
                        if (written % (256 * 1024) == 0L) {
                            onProgress(written, expected)
                        }
                    }
                }
            }
            // 下载完成：确保进度条到 100%（否则会停在 99% 直到解压结束）
            onProgress(written, expected)
            if (expected > 0 && written != expected) {
                AppLog.w(TAG, "大小不匹配: expected=$expected got=$written url=$url")
                return false
            }
            // 完整才落正式文件名（同卷 rename 原子生效；失败回退复制）
            if (!part.renameTo(target)) {
                part.copyTo(target, overwrite = true)
                part.delete()
            }
            return true
        } catch (t: Throwable) {
            AppLog.w(TAG, "下载异常: $url - " + (t.message ?: t.javaClass.simpleName))
            return false
        } finally {
            part.delete()
            connection.disconnect()
        }
    }

    /** 检查模型目录所在存储的可用空间 */
    private fun hasEnoughSpace(context: Context, requiredBytes: Long): Boolean = try {
        val stat = StatFs(modelRoot(context).absolutePath)
        stat.availableBytes >= requiredBytes
    } catch (t: Throwable) {
        AppLog.w(TAG, "空间检查失败，按通过处理")
        true
    }
}
