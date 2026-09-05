package com.voiceime

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.k2fsa.sherpa.onnx.TenVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 主页"输入法测试"控制器（参考 Trime LocalVoiceInputManager 的独立录音控制，
 * 但带流式预览）：AudioRecord + Ten VAD + 复用 [StreamingPipeline]，
 * 识别结果只在本页展示、不上屏。
 *
 * 与 IME 面板互斥：[VoiceImeService.panelRecording] 为 true 时拒绝开始
 * （同进程双 AudioRecord 在部分 ROM 受限）。
 */
object TestVoiceController {

    data class TestUiState(
        val recording: Boolean = false,
        val decoding: Boolean = false,
        val lines: List<String> = emptyList(),
        val preview: String = "",
        val finalText: String = "",
        val error: String? = null,
        val level: Float = 0f,
    )

    private const val SAMPLE_RATE = 16_000
    private const val MAX_SESSION_MS = 300_000L

    private val _state = MutableStateFlow(TestUiState())
    val state: StateFlow<TestUiState> = _state

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var session = 0L

    @Volatile
    private var recording = false

    private var vad: Vad? = null
    private var pipeline: StreamingPipeline? = null
    private var recognizer: VoiceRecognizer? = null
    private var recognizerKey = ""

    private val pcmBuffer = ByteArrayOutputStream()
    private val bufferLock = Any()
    private var sessionStartMs = 0L
    private var hasDetectedSpeech = false
    private var lastSpeechAtMs = 0L

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
        val spec = AsrModels.require(
            context.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
                .getString(Prefs.KEY_MODEL, null),
        )
        val modelDir = VoiceModelManager.resolveModelDir(context, spec)
        if (modelDir == null) {
            _state.value = TestUiState(error = context.getString(R.string.status_model_missing))
            return
        }
        val debug = DebugParams.read(context)
        val language = context.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
            .getString(Prefs.KEY_LANGUAGE, "auto") ?: "auto"
        val emotionEvent = context.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
            .getBoolean(Prefs.KEY_EMOTION_EVENT, false) && spec.supportsEmotionEvent

        val mySession = ++session
        job?.cancel()
        recording = true
        sessionStartMs = android.os.SystemClock.elapsedRealtime()
        pcmBuffer.reset()
        hasDetectedSpeech = false
        lastSpeechAtMs = 0L
        releaseVad()
        initVad(context, debug)

        // 识别器缓存（模型/语言/线程变化时重建）
        val key = "${spec.id}|${modelDir.absolutePath}|$language|${debug.decodeThreads.coerceIn(1, 8)}"
        if (recognizer == null || recognizerKey != key) {
            AppLog.i("TestVoice", "start loading model: ${spec.id}")
            runCatching {
                recognizer?.release()
                recognizer = VoiceRecognizer(
                    modelDir = modelDir,
                    spec = spec,
                    language = language,
                    useItn = true,
                    numThreads = debug.decodeThreads.coerceIn(1, 8),
                ).also { recognizerKey = key }
                AppLog.i("TestVoice", "recognizer created: ${spec.id}")
            }.onFailure {
                _state.value = TestUiState(
                    error = context.getString(R.string.test_load_failed, it.message ?: it.javaClass.simpleName),
                )
                recording = false
                return
            }
        }

        val decode: (FloatArray) -> String = { samples ->
            // 最终复核在 pipeline.reset()（worker 已取消）之后执行，无并发，无需锁
            recognizer?.decode(samples, SAMPLE_RATE)?.text ?: ""
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
        this.pipeline = pipeline

        _state.value = TestUiState(recording = true)

        job = scope.launch {
            try {
                pipeline.onSessionStart()
                recordLoop(debug, pipeline)
                if (session != mySession) return@launch
                _state.update { it.copy(recording = false, decoding = true, preview = "") }
                pipeline.reset()
                val samples = synchronized(bufferLock) { pcmToFloat(pcmBuffer.toByteArray()) }
                val result = recognizer?.decode(samples, SAMPLE_RATE)
                val text = result?.text.orEmpty().trim()
                val rich = if (emotionEvent && text.isNotEmpty()) {
                    text + EmotionEvent.format(result?.emotion.orEmpty(), result?.event.orEmpty())
                } else {
                    text
                }
                _state.update {
                    it.copy(decoding = false, finalText = rich, lines = emptyList())
                }
                AppLog.i("TestVoice", "test recognition done: " + rich.take(60))
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) return@launch
                AppLog.e("TestVoice", "test recognition failed: " + (t.message ?: t.javaClass.simpleName))
                _state.update { it.copy(recording = false, decoding = false, error = t.message) }
            } finally {
                releaseVad()
                pipeline.reset()
                if (session == mySession) recording = false
            }
        }
    }

    /** 录音循环：与 VoiceImeService 相同的 VAD 驱动方式 */
    private suspend fun recordLoop(debug: DebugParams.Values, pipeline: StreamingPipeline) {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) return
        val bufferSize = maxOf(minBuf, SAMPLE_RATE / 1000 * debug.readChunkMs * 2)
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize,
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return
        }
        val buf = ByteArray(bufferSize)
        try {
            record.startRecording()
            while (recording && currentCoroutineContext().isActive) {
                if (android.os.SystemClock.elapsedRealtime() - sessionStartMs > MAX_SESSION_MS) {
                    recording = false
                    break
                }
                val n = record.read(buf, 0, buf.size)
                if (n <= 0) break
                synchronized(bufferLock) { pcmBuffer.write(buf, 0, n) }
                val samples = pcmToFloat(buf, 0, n)
                _state.update { it.copy(level = rmsLevel(samples)) }
                val vad = vad ?: continue
                try {
                    vad.acceptWaveform(samples)
                    pipeline.onChunkPcm(buf, n)
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (vad.isSpeechDetected()) {
                        hasDetectedSpeech = true
                        lastSpeechAtMs = now
                        pipeline.onSpeechDetected()
                    } else if (hasDetectedSpeech && now - lastSpeechAtMs >= debug.autoStopMs) {
                        recording = false
                        break
                    }
                    while (!vad.empty()) {
                        val seg = vad.front()
                        val segSamples = seg.samples
                        vad.pop()
                        pipeline.onSegmentCompleted(segSamples)
                    }
                } catch (t: Throwable) {
                    AppLog.w("TestVoice", "VAD processing failed", t)
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
    }

    private fun initVad(context: Context, debug: DebugParams.Values) {
        try {
            val config = VadModelConfig(
                tenVadModelConfig = TenVadModelConfig(
                    model = "vad/ten-vad.onnx",
                    threshold = debug.vadThreshold,
                    minSilenceDuration = debug.vadMinSilence,
                    minSpeechDuration = debug.vadMinSpeech,
                    windowSize = debug.vadWindowSize,
                    maxSpeechDuration = debug.vadMaxSpeech,
                ),
                sampleRate = SAMPLE_RATE,
                numThreads = 1,
                provider = "cpu",
                debug = false,
            )
            vad = Vad(assetManager = context.assets, config = config)
        } catch (t: Throwable) {
            AppLog.w("TestVoice", "VAD init failed", t)
            vad = null
        }
    }

    private fun releaseVad() {
        try {
            vad?.release()
        } catch (_: Throwable) {
        }
        vad = null
    }

    private fun pcmToFloat(pcm: ByteArray, offset: Int = 0, len: Int = pcm.size): FloatArray {
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

    private fun rmsLevel(samples: FloatArray): Float {
        if (samples.isEmpty()) return 0f
        var sum = 0.0
        for (s in samples) sum += s * s
        val rms = Math.sqrt(sum / samples.size)
        val db = 20.0 * Math.log10(rms.coerceAtLeast(1e-6))
        return ((db + 50.0) / 50.0).toFloat().coerceIn(0f, 1f)
    }
}
