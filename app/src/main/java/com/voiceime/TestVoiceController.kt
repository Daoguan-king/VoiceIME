package com.voiceime

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 主页"输入法测试"控制器（参考 Trime LocalVoiceInputManager 的独立录音控制，
 * 但带流式预览）：复用 [RecordingEngine]（AudioRecord + Ten VAD + 时间分片降级）
 * 与 [StreamingPipeline]，识别结果只在本页展示、不上屏。
 *
 * 与 IME 面板互斥：[VoiceImeService.panelRecording] 为 true 时拒绝开始
 * （同进程双 AudioRecord 在部分 ROM 受限）。
 */
object TestVoiceController {

    private const val TAG = "TestVoice"

    data class TestUiState(
        val recording: Boolean = false,
        val decoding: Boolean = false,
        val lines: List<String> = emptyList(),
        val preview: String = "",
        val finalText: String = "",
        val error: String? = null,
        val level: Float = 0f,
    )

    private val _state = MutableStateFlow(TestUiState())
    val state: StateFlow<TestUiState> = _state

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val recognizerCache = RecognizerCache(TAG)
    private var job: Job? = null
    private var session = 0L

    @Volatile
    private var recording = false

    fun toggle(context: Context) {
        if (recording || job?.isActive == true) {
            stop()
        } else {
            start(context.applicationContext)
        }
    }

    /** 手动结束：录音循环退出后做整段复核识别 */
    fun stop() {
        recording = false
    }

    private fun start(context: Context) {
        if (VoiceImeService.panelRecording) {
            _state.value = TestUiState(error = context.getString(R.string.test_ime_busy))
            return
        }
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            _state.value = TestUiState(error = context.getString(R.string.status_no_permission))
            return
        }
        val sp = context.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
        val spec = AsrModels.require(sp.getString(Prefs.KEY_MODEL, null))
        val modelDir = VoiceModelManager.resolveModelDir(context, spec)
        if (modelDir == null) {
            _state.value = TestUiState(error = context.getString(R.string.status_model_missing))
            return
        }
        val debug = DebugParams.read(context)
        val language = sp.getString(Prefs.KEY_LANGUAGE, "auto") ?: "auto"
        val emotionEvent = sp.getBoolean(Prefs.KEY_EMOTION_EVENT, false) && spec.supportsEmotionEvent

        val mySession = ++session
        job?.cancel()
        recording = true

        // 识别器缓存（模型/语言/线程变化时重建；旧实例延迟释放，
        // 避免与上次会话残留的在途解码产生 use-after-free）
        val rec = runCatching {
            recognizerCache.acquire(
                newKey = recognizerCache.buildKey(spec, modelDir, language, debug.decodeThreads),
                modelDir = modelDir,
                spec = spec,
                language = language,
                numThreads = debug.decodeThreads,
            )
        }.getOrElse { t ->
            _state.value = TestUiState(
                error = context.getString(R.string.test_load_failed, t.message ?: t.javaClass.simpleName),
            )
            recording = false
            return
        } ?: run {
            recording = false
            return
        }

        val decode: (FloatArray) -> String = { samples ->
            // 最终复核在 pipeline.reset()（worker 已取消）之后执行，无并发，无需锁
            rec.decode(samples, AudioCodec.SAMPLE_RATE).text
        }
        val pipeline = StreamingPipeline(
            scope = scope,
            sessionProvider = { session },
            recordingProvider = { recording },
            workersProvider = { debug.workers },
            previewIntervalMsProvider = { debug.previewIntervalMs },
            previewCharsProvider = { debug.previewChars },
            decode = decode,
            onPreview = { fixed, preview ->
                _state.update {
                    it.copy(lines = fixed, preview = preview)
                }
            },
        )

        _state.value = TestUiState(recording = true)

        job = scope.launch {
            // 引擎随协程创建/销毁（VAD 加载在 IO 线程），取消/异常均经 finally 释放
            val engine = RecordingEngine(
                tag = TAG,
                debug = debug,
                assets = context.assets,
                pipeline = pipeline,
                onLevel = { level -> _state.update { it.copy(level = level) } },
                onStopRequest = { recording = false },
            )
            try {
                pipeline.onSessionStart()
                val pcm = engine.recordLoop(isRunning = { recording })
                if (session != mySession) return@launch
                _state.update { it.copy(recording = false, decoding = true, preview = "") }
                pipeline.reset()
                val result = rec.decode(AudioCodec.pcmToFloat(pcm), AudioCodec.SAMPLE_RATE)
                val text = result.text.trim()
                val rich = if (emotionEvent && text.isNotEmpty()) {
                    text + EmotionEvent.format(result.emotion, result.event)
                } else {
                    text
                }
                _state.update {
                    it.copy(decoding = false, finalText = rich, lines = emptyList())
                }
                AppLog.i(TAG, "test recognition done: " + rich.take(60))
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) return@launch
                AppLog.e(TAG, "test recognition failed: " + (t.message ?: t.javaClass.simpleName))
                _state.update { it.copy(recording = false, decoding = false, error = t.message) }
            } finally {
                engine.close()
                pipeline.reset()
                if (session == mySession) recording = false
            }
        }
    }
}
