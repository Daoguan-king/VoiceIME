package com.voiceime

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.voiceime.ui.panel.PanelScreen
import com.voiceime.ui.panel.PanelUiState
import com.voiceime.ui.theme.VoiceImeTheme
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 供 Trime 调用的本地语音输入法（sherpa-onnx SenseVoice）。
 *
 * 职责边界：本类只做 IME 生命周期/UI 接线与会话编排；
 * 录音采集 + VAD 判停 + 电平回调在 [RecordingEngine]，
 * 识别器缓存/延迟释放在 [RecognizerCache]，
 * 流式解码（段队列 + 并发 worker + 乱序重排 + 滑动窗口预览）在 [StreamingPipeline]。
 * UI 为 Compose 面板（Xime 同款接线：Service 实现三 Owner，ComposeView 作输入视图，
 * 录音线程经 [PanelUiState] StateFlow 推送状态）。
 */
class VoiceImeService : InputMethodService(), LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {

    companion object {
        private const val TAG = "VoiceIme"

        /** 面板是否正在录音（应用内"输入法测试"互斥检测用，同进程可见） */
        @Volatile
        var panelRecording = false
            private set
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // ---- Compose 面板三 Owner 接线（Xime 同款） ----
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val serviceViewModelStore = ViewModelStore()

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    override val viewModelStore: ViewModelStore
        get() = serviceViewModelStore

    /** 面板状态：录音线程/主线程均可推送 */
    private val _panelState = MutableStateFlow(PanelUiState())

    /** 面板电平动画参数（设置页修改后经 onStartInputView 刷新生效）。
     *  lazy：构造期 baseContext 未 attach，不能在字段初始化器里读 prefs */
    private val panelAnimParams by lazy { MutableStateFlow(UiParams.readAnim(this)) }

    override fun attachBaseContext(base: Context) {
        // 应用界面语言（AppLocale）对 IME 面板文案同样生效
        super.attachBaseContext(AppLocale.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        savedStateRegistryController.performRestore(null)
        // ComposeView 依赖 ViewTree Owner，挂到 IME 窗口 decorView 上
        window.window?.decorView?.setViewTreeLifecycleOwner(this)
        window.window?.decorView?.setViewTreeSavedStateRegistryOwner(this)
        window.window?.decorView?.setViewTreeViewModelStoreOwner(this)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        updateLanguageLabel()
    }

    /** 调试参数（每次录音开始时从设置读取） */
    private var debug = DebugParams.Values()

    /** SenseVoice 情感/事件检测（每次录音开始时读取） */
    private var emotionEvent = false

    @Volatile
    private var recording = false

    /** recording 赋值统一入口：同步 companion 的 panelRecording（供应用内测试互斥检测） */
    private fun setRecording(value: Boolean) {
        recording = value
        panelRecording = value
    }

    @Volatile
    private var session = 0L

    private var recordJob: Job? = null

    /** 识别器缓存（模型/语言/线程变化时重建，旧实例延迟释放防 JNI use-after-free） */
    private val recognizerCache = RecognizerCache(TAG)

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
        return ComposeView(this).apply {
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool,
            )
            // 全面屏手势/虚拟按键：底部留出导航条安全区（与旧面板一致）
            setOnApplyWindowInsetsListener { v, insets ->
                val bottom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    insets.getInsets(WindowInsets.Type.navigationBars()).bottom
                } else {
                    @Suppress("DEPRECATION")
                    insets.systemWindowInsetBottom
                }
                v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, bottomInsetBase + bottom)
                insets
            }
            setContent {
                val state by _panelState.collectAsState()
                val anim by panelAnimParams.collectAsState()
                VoiceImeTheme(dynamicColor = UiParams.dynamicColor(this@VoiceImeService)) {
                    PanelScreen(
                        state = state,
                        anim = anim,
                        onToggle = ::toggle,
                        onCycleLanguage = ::cycleLanguage,
                    )
                }
            }
        }
    }

    override fun onStartInputView(editorInfo: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(editorInfo, restarting)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        // 设置页可能改过动画参数/开关，显示面板时刷新
        panelAnimParams.value = UiParams.readAnim(this)
        AppLog.i(TAG, "onStartInputView restarting=$restarting")
        // 仿 Google 语音输入：被切换过来即自动开始录音
        if (!recording) start()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        AppLog.i(TAG, "onFinishInputView finishing=$finishingInput recording=$recording")
        // 框架的 super.onDestroy() 也会走到这里，此时生命周期可能已 DESTROYED，
        // 只有仍在 RESUMED 状态才合法回退到 PAUSED
        if (lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        }
        // 离开时丢弃未完成的录音
        if (recording) {
            setRecording(false)
            session++ // 使旧任务的收尾逻辑失效
        }
        recordJob?.cancel()
        pipeline.reset()
        _panelState.update { it.copy(recording = false) }
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
            AppLog.w(TAG, "no mic permission, cannot start recording")
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
            AppLog.i(TAG, "model missing, auto download: ${spec.id}")
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
        setRecording(true)
        AppLog.i(TAG, "recording session #$mySession, model: ${spec.id}, dir: ${modelDir.absolutePath}")
        sessionModelDir = modelDir
        sessionModelId = spec.id
        sessionRecognizerKey = recognizerCache.buildKey(spec, modelDir, language, debug.decodeThreads)
        pipeline.reset()
        _panelState.update {
            it.copy(
                recording = true,
                statusText = getString(R.string.status_recording),
                level = 0f,
            )
        }

        pipeline.onSessionStart()

        // 本次会话的参数快照（协程内读取，避免与下一次 start 的字段赋值竞态）
        val dbg = debug
        val emotion = emotionEvent
        recordJob = scope.launch(Dispatchers.IO) {
            // 引擎随协程创建/销毁（VAD 加载在 IO 线程），取消/异常均经 finally 释放
            val engine = RecordingEngine(
                tag = TAG,
                debug = dbg,
                assets = assets,
                pipeline = pipeline,
                onLevel = { level -> _panelState.update { it.copy(level = level) } },
                onStopRequest = { setRecording(false) },
            )
            try {
                val pcm = engine.recordLoop(isRunning = { recording })
                if (pcm.isEmpty() || session != mySession) return@launch
                withContext(Dispatchers.Main) { setStatus(getString(R.string.status_recognizing)) }
                val samples = AudioCodec.pcmToFloat(pcm)
                val result = decode(samples)
                val text = if (emotion && result.text.isNotBlank()) {
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
                AppLog.e(TAG, "recognition failed: " + (t.message ?: t.javaClass.simpleName))
                withContext(Dispatchers.Main) { setStatus(t.message ?: "error") }
            } finally {
                engine.close()
                if (session == mySession) {
                    setRecording(false)
                    pipeline.reset()
                    // 面板状态复位（StateFlow 线程安全，无需切主线程）
                    _panelState.update { it.copy(recording = false) }
                }
            }
        }
    }

    /** 结束录音：录音循环退出后，同一个协程继续做整段识别并上屏 */
    private fun stopAndRecognize() {
        AppLog.i(TAG, "recording stopped manually")
        setRecording(false)
    }

    private fun commit(text: String) {
        try {
            currentInputConnection?.commitText(text, 1)
        } catch (t: Throwable) {
            // 输入连接可能已失效（如输入法窗口正在关闭），不影响后续状态
            AppLog.w(TAG, "commitText failed", t)
        }
        AppLog.i(TAG, "committed: " + text.take(60))
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
        updateLanguageLabel()
    }

    /** 刷新面板上的语言标签 */
    private fun updateLanguageLabel() {
        val values = resources.getStringArray(R.array.language_values)
        val labels = resources.getStringArray(R.array.language_labels)
        val idx = values.indexOf(language).coerceAtLeast(0)
        _panelState.update { it.copy(languageLabel = labels[idx]) }
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

    // ---- 识别 ----

    /**
     * 解码：不串行（与官方 Demo 一致，同一识别器可并发解码多个语音段）。
     * 识别器创建/重建/延迟释放由 [RecognizerCache] 统一管理。
     */
    private fun decode(samples: FloatArray): DecodeResult {
        val modelDir = sessionModelDir ?: return DecodeResult("")
        val spec = AsrModels.byId(sessionModelId) ?: return DecodeResult("")
        val rec = recognizerCache.acquire(
            newKey = sessionRecognizerKey,
            modelDir = modelDir,
            spec = spec,
            language = language,
            numThreads = debug.decodeThreads,
        ) ?: return DecodeResult("")
        try {
            return rec.decode(samples, AudioCodec.SAMPLE_RATE)
        } finally {
            rec.endDecode()
        }
    }

    /** 流式预览只需文本（情感/事件标签只在整段上屏时附加） */
    private fun decodeText(samples: FloatArray): String = decode(samples).text

    override fun onDestroy() {
        AppLog.i(TAG, "onDestroy")
        // 先走框架销毁（其内部会触发 onFinishInputView → ON_PAUSE，须在 DESTROYED 之前），
        // 再终结本 Service 的生命周期
        super.onDestroy()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        scope.cancel()
        // 释放当前识别器（若有在途解码则等待其结束，避免 JNI use-after-free）
        recognizerCache.releaseAll()
        serviceViewModelStore.clear()
    }

    /** 面板状态文本更新（任意线程可调，StateFlow 线程安全） */
    private fun setStatus(text: String) {
        _panelState.update { it.copy(statusText = text) }
    }
}
