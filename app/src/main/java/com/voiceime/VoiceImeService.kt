package com.voiceime

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import com.k2fsa.sherpa.onnx.TenVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 供 Trime 调用的本地语音输入法（sherpa-onnx SenseVoice）。
 *
 * 职责边界：本类只做录音采集、VAD 判停、识别器管理与 IME 生命周期/UI；
 * 流式解码（段队列 + 并发 worker + 乱序重排 + 滑动窗口预览）在 [StreamingPipeline]。
 */
class VoiceImeService : InputMethodService() {

    companion object {
        private const val TAG = "VoiceIme"
        private const val SAMPLE_RATE = 16_000

        /** 单次录音最长时长（5 分钟），防止内存无限增长 */
        private const val MAX_SESSION_MS = 300_000L

        // 时间分片降级参数（VAD 不可用时使用）
        private const val PARTIAL_INTERVAL_MS = 700L
        private const val MIN_SEGMENT_BYTES = 19_200
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
    }

    /** 识别器创建/重建锁（解码本身不锁，支持并发解码） */
    private val recognizerLock = Any()

    // 在途解码计数已下沉到 VoiceRecognizer 实例级（inflightCount），
    // 避免全局计数造成的误释放竞态与跨实例相互拖延

    /** 调试参数（每次录音开始时从设置读取） */
    private var debug = DebugParams.Values()

    /** SenseVoice 情感/事件检测（每次录音开始时读取） */
    private var emotionEvent = false

    @Volatile
    private var recording = false

    @Volatile
    private var session = 0L

    private var recordJob: Job? = null
    private var sessionStartMs = 0L

    /** 整段复核用累积音频（仅最终解码读取，上限由 MAX_SESSION_MS 控制） */
    private val pcmBuffer = ByteArrayOutputStream()
    private val bufferLock = Any()

    private var vad: Vad? = null
    private var hasDetectedSpeech = false
    private var lastSpeechAtMs = 0L

    // 时间分片降级（VAD 不可用）用的游标
    private var lastPartialByteOffset = 0
    private var lastPartialAtMs = 0L

    /** 流式解码管线（段固化 + 滑动窗口预览） */
    private val pipeline = StreamingPipeline(
        scope = scope,
        sessionProvider = { session },
        recordingProvider = { recording },
        workersProvider = { debug.workers },
        previewIntervalMsProvider = { debug.previewIntervalMs },
        previewCharsProvider = { debug.previewChars },
        decode = ::decodeText,
        onPreview = ::onPipelinePreview,
    )

    // ---- 当前会话模型 ----
    private var sessionModelDir: File? = null
    private var sessionModelId = ""
    private var sessionRecognizerKey = ""

    /** 已加载的识别器（模型/语言变化时重建） */
    private var recognizer: VoiceRecognizer? = null
    private var recognizerKey = ""

    private var micButton: Button? = null
    private var langButton: Button? = null
    private var statusText: TextView? = null
    private var levelView: LevelView? = null

    /** 底部手势条/虚拟按键安全区：固定 24dp + 系统 insets */
    private val bottomInsetBase: Int by lazy { dp(24) }

    private fun prefs() = getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)

    private val modelSpec: ModelSpec
        get() {
            val sp = prefs()
            return AsrModels.byId(sp.getString(Prefs.KEY_MODEL, null))
                ?: AsrModels.byLegacyVariant(sp.getString(Prefs.KEY_MODEL_VARIANT, null))
                ?: AsrModels.SENSE_VOICE_INT8
        }

    private val language: String
        get() = prefs().getString(Prefs.KEY_LANGUAGE, "auto") ?: "auto"

    private val autoSwitchBack: Boolean
        get() = prefs().getBoolean(Prefs.KEY_AUTO_SWITCH, true)

    override fun onCreateInputView(): View {
        val root = LayoutInflater.from(this).inflate(R.layout.ime_root, null)
        micButton = root.findViewById(R.id.mic_button)
        langButton = root.findViewById(R.id.lang_button)
        statusText = root.findViewById(R.id.status_text)
        levelView = root.findViewById(R.id.level_view)
        micButton?.setOnClickListener { toggle() }
        langButton?.setOnClickListener { cycleLanguage() }
        updateLanguageButton()

        // 全面屏手势/虚拟按键：底部留出导航条安全区
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bottom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                insets.getInsets(WindowInsets.Type.navigationBars()).bottom
            } else {
                @Suppress("DEPRECATION")
                insets.systemWindowInsetBottom
            }
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, bottomInsetBase + bottom)
            insets
        }
        return root
    }

    override fun onStartInputView(editorInfo: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(editorInfo, restarting)
        AppLog.i(TAG, "onStartInputView restarting=$restarting")
        // 仿 Google 语音输入：被切换过来即自动开始录音
        if (!recording) start()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        AppLog.i(TAG, "onFinishInputView finishing=$finishingInput recording=$recording")
        // 离开时丢弃未完成的录音
        if (recording) {
            recording = false
            session++ // 使旧任务的收尾逻辑失效
        }
        recordJob?.cancel()
        resetSessionState()
        micButton?.text = getString(R.string.btn_start)
        micButton?.setBackgroundResource(R.drawable.btn_mic_bg)
        levelView?.setRecording(false)
    }

    private fun toggle() {
        if (recording) stopAndRecognize() else start()
    }

    private fun start() {
        val context = applicationContext
        // 每次录音读取最新调试参数
        debug = DebugParams.read(this)
        // 情感/事件仅原版 SenseVoiceSmall 支持（"2025"实为 WSYue 粤语模型，无此能力）
        emotionEvent = prefs().getBoolean(Prefs.KEY_EMOTION_EVENT, false) && modelSpec.supportsEmotionEvent
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            AppLog.w(TAG, "无麦克风权限，无法开始录音")
            setStatus(getString(R.string.status_no_permission))
            Toast.makeText(this, R.string.status_no_permission, Toast.LENGTH_SHORT).show()
            val intent = Intent(context, PermissionActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return
        }

        val spec = modelSpec
        val modelDir = VoiceModelManager.resolveModelDir(context, spec)
        if (modelDir == null) {
            AppLog.i(TAG, "模型缺失，自动下载: ${spec.id}")
            setStatus(getString(R.string.status_downloading))
            scope.launch {
                val custom = prefs().getString(Prefs.customUrlKey(spec.id), null)
                val ok = VoiceModelManager.downloadModel(context, spec, custom)
                setStatus(getString(if (ok) R.string.status_model_ready else R.string.status_download_failed))
            }
            return
        }

        val mySession = ++session
        recordJob?.cancel()
        recording = true
        sessionStartMs = SystemClock.elapsedRealtime()
        AppLog.i(TAG, "开始录音会话 #$mySession，模型: ${spec.id}，目录: ${modelDir.absolutePath}")
        sessionModelDir = modelDir
        sessionModelId = spec.id
        sessionRecognizerKey = spec.id + "|" + modelDir.absolutePath + "|" + language + "|" + debug.decodeThreads.coerceIn(1, 8)
        resetSessionState()
        initVad()
        setStatus(getString(R.string.status_recording))
        micButton?.text = getString(R.string.btn_stop)
        micButton?.setBackgroundResource(R.drawable.btn_mic_bg_rec)
        levelView?.setRecording(true)

        pipeline.onSessionStart()

        recordJob = scope.launch(Dispatchers.IO) {
            try {
                val pcm = recordAudio()
                if (pcm.isEmpty() || session != mySession) return@launch
                withContext(Dispatchers.Main) { setStatus(getString(R.string.status_recognizing)) }
                val samples = pcmToFloatArray(pcm)
                val result = decode(samples)
                val text = if (emotionEvent && result.text.isNotBlank()) {
                    result.text + EmotionEvent.format(result.emotion, result.event)
                } else {
                    result.text
                }
                withContext(Dispatchers.Main) {
                    if (session != mySession) return@withContext
                    if (text.isNotBlank()) {
                        commit(text.trim())
                    } else {
                        setStatus(getString(R.string.status_empty))
                    }
                }
            } catch (t: Throwable) {
                AppLog.e(TAG, "语音识别失败: " + (t.message ?: t.javaClass.simpleName))
                withContext(Dispatchers.Main) { setStatus(t.message ?: "error") }
            } finally {
                if (session == mySession) {
                    recording = false
                    resetSessionState()
                    releaseVad()
                    // UI 更新必须回到主线程：ValueAnimator 只能在 Looper 线程操作。
                    // 协程被取消时 withContext 会直接抛 CancellationException，
                    // 因此用独立的 Main 协程确保收尾 UI 一定执行。
                    scope.launch(Dispatchers.Main) {
                        if (session == mySession) {
                            micButton?.text = getString(R.string.btn_start)
                            micButton?.setBackgroundResource(R.drawable.btn_mic_bg)
                            levelView?.setRecording(false)
                        }
                    }
                }
            }
        }
    }

    /** 结束录音：录音循环退出后，同一个协程继续做整段识别并上屏 */
    private fun stopAndRecognize() {
        AppLog.i(TAG, "手动结束录音")
        recording = false
    }

    private fun commit(text: String) {
        try {
            currentInputConnection?.commitText(text, 1)
        } catch (t: Throwable) {
            // 输入连接可能已失效（如输入法窗口正在关闭），不影响后续状态
            AppLog.w(TAG, "commitText failed", t)
        }
        AppLog.i(TAG, "识别上屏: " + text.take(60))
        setStatus(getString(R.string.status_done))
        if (autoSwitchBack) switchBackToPreviousIme()
    }

    /** 上屏后自动切回上一个输入法（即 Trime） */
    private fun switchBackToPreviousIme() {
        try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            // 仅启用一个输入法时没有"上一个"可切，直接切换会把键盘收起
            if (imm.enabledInputMethodList.size <= 1) {
                AppLog.i(TAG, "Only one IME enabled, skip switch back")
                return
            }
            // 切回目标候选：排除自己与语音型输入法（mode=voice），只考虑键盘输入法
            val candidates = imm.enabledInputMethodList.filter { info ->
                info.packageName != packageName &&
                    (0 until info.subtypeCount).any { info.getSubtypeAt(it).mode.lowercase() != "voice" }
            }
            // 候选唯一 → 直接显式切换，完全不依赖系统"上一个输入法"记录。
            // switchToLastInputMethod 依赖系统维护的 last-IME 记录，部分 ROM 上该记录
            // 不可靠（指向自己或已失效的 subtype 时返回 false），会概率性切回失败；
            // 显式切换（InputMethodService.switchInputMethod）不依赖该记录，结果确定。
            if (candidates.size == 1) {
                switchInputMethod(candidates[0].id)
                AppLog.i(TAG, "switchInputMethod -> ${candidates[0].id}")
                return
            }
            // 多个候选：先尝试系统记录的"上一个输入法"（最符合用户预期）
            val token = window?.window?.attributes?.token
            if (token != null && imm.switchToLastInputMethod(token)) {
                AppLog.i(TAG, "switchToLastInputMethod -> true")
                return
            }
            // 失败兜底：显式切回首选输入法（优先 Trime，其次第一个键盘输入法）
            val fallback = candidates.firstOrNull { it.packageName.startsWith("com.osfans.trime") }
                ?: candidates.firstOrNull()
            if (fallback == null) {
                AppLog.w(TAG, "No keyboard IME candidate to switch back, skip")
                return
            }
            switchInputMethod(fallback.id)
            AppLog.i(TAG, "fallback switchInputMethod -> ${fallback.id}")
        } catch (t: Throwable) {
            AppLog.w(TAG, "Failed to switch back to previous IME", t)
        }
    }

    // ---------------- 语言快捷切换 ----------------

    private fun cycleLanguage() {
        val values = resources.getStringArray(R.array.language_values)
        val cur = language
        val nextIndex = (values.indexOf(cur) + 1).coerceAtLeast(0) % values.size
        prefs().edit().putString(Prefs.KEY_LANGUAGE, values[nextIndex]).apply()
        updateLanguageButton()
    }

    private fun updateLanguageButton() {
        val values = resources.getStringArray(R.array.language_values)
        val labels = resources.getStringArray(R.array.language_labels)
        val idx = values.indexOf(language).coerceAtLeast(0)
        langButton?.text = getString(R.string.lang_button_prefix) + labels[idx]
    }

    // ---------------- VAD 与流式入口 ----------------

    private fun initVad() {
        releaseVad()
        vad = try {
            val tenConfig = TenVadModelConfig(
                model = "vad/ten-vad.onnx",
                threshold = debug.vadThreshold,
                minSilenceDuration = debug.vadMinSilence,
                minSpeechDuration = debug.vadMinSpeech,
                windowSize = debug.vadWindowSize,
                maxSpeechDuration = debug.vadMaxSpeech,
            )
            val config = VadModelConfig(
                tenVadModelConfig = tenConfig,
                sampleRate = SAMPLE_RATE,
                numThreads = 1,
                provider = "cpu",
                debug = false,
            )
            Vad(assetManager = assets, config = config)
        } catch (t: Throwable) {
            AppLog.w(TAG, "VAD init failed, fallback to time-based partial", t)
            null
        }
    }

    private fun releaseVad() {
        try {
            vad?.release()
        } catch (t: Throwable) {
            AppLog.w(TAG, "Failed to release VAD", t)
        }
        vad = null
    }

    private fun resetSessionState() {
        pipeline.reset()
        synchronized(bufferLock) {
            pcmBuffer.reset()
        }
        hasDetectedSpeech = false
        lastSpeechAtMs = 0L
    }

    /**
     * 每块 PCM 送入 VAD：
     * 1. 音频累积进 pipeline 滑动窗口，首次检测到语音记录回退 0.4s 起点；
     * 2. 连续静音超过阈值 → 自动结束录音；
     * 3. VAD 出段 → pipeline 入队并发解码固化一行。
     */
    private fun processAudioChunk(samples: FloatArray, raw: ByteArray, rawLen: Int) {
        val vad = vad
        if (vad == null) {
            maybePartialTimeBased()
            return
        }
        try {
            vad.acceptWaveform(samples)
            pipeline.onChunkPcm(raw, rawLen)
            val now = SystemClock.elapsedRealtime()
            if (vad.isSpeechDetected()) {
                hasDetectedSpeech = true
                lastSpeechAtMs = now
                pipeline.onSpeechDetected()
            } else if (hasDetectedSpeech && now - lastSpeechAtMs >= debug.autoStopMs) {
                // 静音自动结束
                AppLog.i(TAG, "静音超时自动结束录音")
                recording = false
                return
            }
            // 出队已完成的语音段 → 固化一行
            while (!vad.empty()) {
                val seg = vad.front()
                val segSamples = seg.samples
                vad.pop()
                AppLog.i(TAG, "VAD 出段: ${segSamples.size} samples")
                pipeline.onSegmentCompleted(segSamples)
            }
        } catch (t: Throwable) {
            AppLog.w(TAG, "VAD processing failed", t)
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
        pipeline.onSegmentCompleted(pcmToFloatArray(seg))
    }

    /** pipeline 预览回调（任意线程触发，转主线程刷新 UI） */
    private fun onPipelinePreview(fixed: List<String>, preview: String) {
        val jobSession = session
        scope.launch(Dispatchers.Main) {
            if (session != jobSession || !recording) return@launch
            val lines = if (preview.isNotBlank()) fixed + preview else fixed
            val maxChars = debug.previewChars.coerceIn(32, 300)
            val maxLines = StreamingPipeline.MAX_LINES
            var truncated = false
            var shown = lines
            if (shown.size > maxLines) {
                shown = shown.takeLast(maxLines)
                truncated = true
            }
            if (truncated && shown.size > maxLines - 1) {
                shown = shown.takeLast(maxLines - 1)
            }
            val joined = shown.joinToString("\n") { line ->
                if (line.length > maxChars) line.take(maxChars) + "…" else line
            }
            val display = (if (truncated) "…\n" else "") + joined
            setStatus(getString(R.string.status_partial) + " " + display + "…")
        }
    }

    // ---------------- 录音：16 kHz / 单声道 / PCM16 ----------------

    private suspend fun recordAudio(): ByteArray {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) {
            AppLog.e(TAG, "AudioRecord.getMinBufferSize 异常: $minBuf")
            return ByteArray(0)
        }
        // 读取粒度可调（官方 Demo 为 32ms/512 samples），越小 VAD 响应越快
        val bufferSize = maxOf(minBuf, SAMPLE_RATE / 1000 * debug.readChunkMs * 2)
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize,
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            AppLog.e(TAG, "AudioRecord 初始化失败，无法录音")
            record.release()
            return ByteArray(0)
        }
        val buf = ByteArray(bufferSize)
        try {
            record.startRecording()
            while (recording && currentCoroutineContext().isActive) {
                // 单次录音时长上限，防止内存无限增长
                if (SystemClock.elapsedRealtime() - sessionStartMs > MAX_SESSION_MS) {
                    AppLog.i(TAG, "达到单次录音时长上限，自动结束")
                    recording = false
                    break
                }
                val n = record.read(buf, 0, buf.size)
                when {
                    n > 0 -> {
                        synchronized(bufferLock) { pcmBuffer.write(buf, 0, n) }
                        val samples = pcmToFloatArray(buf, 0, n)
                        levelView?.setLevel(rmsLevel(samples))
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

    // ---- PCM -> Float ----

    private fun pcmToFloatArray(pcm: ByteArray, offset: Int = 0, len: Int = pcm.size): FloatArray {
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

    // ---- 音量电平（供 LevelView 动画） ----

    /** 计算一段采样 RMS 并映射到 0..1（-50dB ~ 0dB） */
    private fun rmsLevel(samples: FloatArray): Float {
        if (samples.isEmpty()) return 0f
        var sum = 0.0
        for (s in samples) sum += s * s
        val rms = Math.sqrt(sum / samples.size)
        val db = 20.0 * Math.log10(rms.coerceAtLeast(1e-6))
        return ((db + 50.0) / 50.0).toFloat().coerceIn(0f, 1f)
    }

    // ---- 识别 ----

    /**
     * 解码：不串行（与官方 Demo 一致，同一识别器可并发解码多个语音段）。
     * 识别器创建/重建用 recognizerLock 保护；重建时旧实例延迟释放，避免 JNI use-after-free。
     */
    private fun decode(samples: FloatArray): DecodeResult {
        val key = sessionRecognizerKey
        val rec = synchronized(recognizerLock) {
            val current = if (recognizerKey == key && recognizer != null) {
                recognizer
            } else {
                val modelDir = sessionModelDir ?: return DecodeResult("")
                val spec = AsrModels.byId(sessionModelId) ?: return DecodeResult("")
                val old = recognizer
                VoiceRecognizer(
                    modelDir = modelDir,
                    spec = spec,
                    language = language,
                    useItn = true,
                    numThreads = debug.decodeThreads.coerceIn(1, 8),
                ).also {
                    recognizer = it
                    recognizerKey = key
                }.let { new ->
                    // 锁内调用 releaseLater：此时本线程尚未登记在途解码，
                    // 但 releaseLater 只检查 old 实例自己的计数，不会误释放 new
                    old?.let { releaseLater(it) }
                    new
                }
            }
            // 关键：在锁内登记在途解码，避免 releaseLater 看到计数为 0 而提前 release
            current?.beginDecode()
            current
        } ?: return DecodeResult("")
        try {
            return rec.decode(samples, SAMPLE_RATE)
        } finally {
            rec.endDecode()
        }
    }

    /** 流式预览只需文本（情感/事件标签只在整段上屏时附加） */
    private fun decodeText(samples: FloatArray): String = decode(samples).text

    /** 旧识别器延迟释放：等它自己实例的在途解码全部结束后再 release（须在锁内调用） */
    private fun releaseLater(old: VoiceRecognizer) {
        if (old.inflightCount() == 0) {
            old.release()
            return
        }
        // 用独立守护线程等待，不随 scope 取消——否则 Service 销毁/切换模型时
        // 协程被取消，旧识别器永不释放（原生内存泄漏）
        Thread {
            try {
                while (old.inflightCount() > 0) {
                    Thread.sleep(50)
                }
                old.release()
            } catch (t: Throwable) {
                AppLog.w(TAG, "Failed to release old recognizer", t)
            }
        }.apply { isDaemon = true }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        AppLog.i(TAG, "onDestroy")
        scope.cancel()
        // 释放当前识别器（若有在途解码则等待其结束，避免 JNI use-after-free）
        synchronized(recognizerLock) {
            recognizer?.let { releaseLater(it) }
            recognizer = null
            recognizerKey = ""
        }
    }

    private fun setStatus(text: String) {
        statusText?.text = text
    }
}
