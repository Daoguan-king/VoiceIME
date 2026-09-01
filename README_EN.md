<h1 align="center">VoiceIME · Local Voice Input IME for Trime</h1>

<p align="center">
    <a href="README.md">简体中文</a>
</p>

<p align="center">A <b>fully offline local voice input IME</b> powered by <a href="https://github.com/k2-fsa/sherpa-onnx">sherpa-onnx</a>.
It registers an <code>imeSubtypeMode="voice"</code> subtype so Trime (同文输入法) can switch to it with the voice key.</p>

## Features

- **Fully offline ASR** - sherpa-onnx OfflineRecognizer; recording and recognition never leave the device
- **Multiple models** - 18 presets across SenseVoice, Qwen3-ASR, Moonshine, Paraformer and Zipformer (incl. ModelScope mirrors), one-tap download in settings
- **Custom download source** - per-model direct links for zip / tar.bz2 / tar.gz archives; falls back to mirrors, then the official source
- **First-run onboarding** - 4-step guide (enable IME / mic permission / model download / Trime config) with live status
- **Selectable language** - auto / Chinese / English / Japanese / Korean / Cantonese (SenseVoice)
- **Emotion / sound-event tags** - SenseVoice rich transcription (happy, applause, laughter, …), optional
- **Auto switch-back** - returns to the previous IME after committing text
- **VAD streaming preview** - Ten VAD segmentation with a sliding-window live preview
- **Quick language toggle** - one tap on the input panel
- **Big button panel** - hard to mis-tap

## Requirements

- Android 8.0 (API 26) or later

## Installation

Pick the APK for your architecture:
- **arm64-v8a** - most modern phones (**default build**)
- **armeabi-v7a** - older 32-bit phones
- **x86_64** - emulators
- **universal** - all architectures, larger APK

## Build

Requires Android Studio (or JDK 17+ CLI). Gradle 9.5.1 / AGP 9.3.0 (Kotlin is bundled with AGP 9).

```shell
# Clone the project
git clone https://github.com/Daoguan-king/VoiceIME

cd VoiceIME

# Build the debug APK (default: arm64-v8a)
./gradlew :app:assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk

# Pick an ABI
./gradlew :app:assembleDebug -Pabi=armeabi-v7a   # 32-bit phones
./gradlew :app:assembleDebug -Pabi=x86_64        # emulators
./gradlew :app:assembleDebug -Pabi=universal     # all ABIs

# Or build the release APK
./gradlew :app:assembleRelease
```

## Install & Use

1. Install the APK. The first launch shows a 4-step onboarding:
    - ① Enable **VoiceIME** in the system IME settings (Trime only lists enabled IMEs)
    - ② Grant the microphone permission
    - ③ Download a speech model (~170 MB for SenseVoice int8; manual placement also works)
    - ④ In Trime: Settings → General → Preferred voice input → select VoiceIME
2. Press the voice key on the Trime keyboard (long-press space by default; long-press ✾ in the tongwenfeng theme; bind any key to `VOICE_ASSIST` in your theme yaml)
3. Recording starts automatically. Watch the live preview, tap "⏹ 结束" when done; the text is committed and Trime is restored automatically
    - The 🌐 button on the panel switches the recognition language (auto/zh/en/ja/ko/yue, SenseVoice only)

### Model download (in-app)

The settings page uses a **two-stage selector**: first pick a model type (SenseVoice / Qwen3-ASR / Moonshine / Paraformer / Zipformer), then pick the concrete model.

| Type | Model | Size (approx.) | Languages |
| --- | --- | --- | --- |
| SenseVoice | small (int8, recommended) | 170 MB | zh / en / ja / ko / yue |
| | small (fp32) | 900 MB | zh / en / ja / ko / yue |
| | small (int8, 2025-09-09) | 170 MB | zh / en / ja / ko / yue |
| Qwen3-ASR | 0.6B (int8) | 1 GB | zh / en |
| Moonshine | tiny (en, int8) | 150 MB | English |
| | base (en, int8) | 300 MB | English |
| | tiny (ko, quantized) | 60 MB | Korean |
| | tiny (ja, quantized) | 60 MB | Japanese |
| | base (zh, quantized) | 120 MB | Chinese |
| | tiny (zh int8, ModelScope) | 30 MB | Chinese |
| | base (zh int8, ModelScope) | 100 MB | Chinese |
| Paraformer | zh (int8) | 60 MB | Chinese |
| | zh small (int8) | 30 MB | Chinese |
| Zipformer | zh-en (int8) | 200 MB | zh / en mixed |
| | ko (int8) | 200 MB | Korean |
| | ja (int8, ReazonSpeech) | 200 MB | Japanese |
| | small (zh-en-yue, ModelScope) | 60 MB | zh / en / yue |
| | large (ModelScope) | 180 MB | multilingual |

**Download order**: custom link → per-file mirrors (HF hf-mirror.com / ModelScope, no extraction needed) → official archives (zip / tar.bz2 / tar.gz). Custom sources are saved per model in settings.

### Model directory

`/Android/data/com.voiceime/files/models/<model-id>/`
(i.e. `/storage/emulated/0/Android/data/com.voiceime/files/models/` — external storage, accessible from file managers, no storage permission needed)

You can also place the files manually (download from the sherpa-onnx model pages and extract them into the model directory). See the table above for required files; Qwen3-ASR additionally needs a `tokenizer/` directory (`vocab.json` / `merges.txt` / `tokenizer_config.json`).

## How it works

1. `res/xml/method.xml` declares a subtype with `android:imeSubtypeMode="voice"` (Trime's `InputMethodUtils.voiceInputMethods()` filters by `mode == "voice"`)
2. Trime voice key → `switchInputMethod(id, voiceSubtype)` → the system switches to this IME
3. `VoiceImeService.onStartInputView()` starts recording automatically (AudioRecord 16 kHz mono PCM16)
4. Each PCM chunk is fed to **Ten VAD** (`assets/vad/ten-vad.onnx`, 332 KB, bundled in the APK) to segment speech
5. Completed segments go to the `OfflineRecognizer` (SenseVoice / Qwen3-ASR / Paraformer …) for live preview
6. ~2 s of silence ends the session automatically (or tap "结束") → full re-recognition → `InputConnection.commitText()`
7. `switchToLastInputMethod()` switches back to Trime by default

## FAQ

- **Trime says "no voice input installed"** - make sure VoiceIME is enabled in the system IME settings; in Trime choose `com.voiceime` as the preferred voice input
- **Long-press space does nothing** - the voice key is defined in the theme yaml (`VOICE_ASSIST`); its position differs per theme (long-press ✾ in tongwenfeng)
- **Slow first recognition** - SenseVoice int8 takes about 1~3 s to load the model (kept in memory afterwards); Qwen3-ASR is larger and slower to load
- **Punctuation/digits in results** - ITN is on by default (`useInverseTextNormalization = true`, SenseVoice)
- **Model download fails / is slow** - if mirrors and the official source both fail, set a custom download source (domestic mirror, direct link, etc.) or place the files manually
- **Language selector is greyed out** - the current model does not support language selection (e.g. Qwen3-ASR auto-detects zh/en; Paraformer is Chinese-only)

## Enabling voice input in Trime

Trime's voice input comes from the keyboard theme. Example: bind the **space key** of the "standard" theme.

Open the theme file with a file manager (e.g. MT Manager):

`/Android/data/com.osfans.trime/files/shared/tongwenfeng.trime.yaml`

Find line ~1105 (may vary by version):

```
      - {click: space, long_click: Mode_switch, swipe_left: "Left", swipe_right: "Right", swipe_up: Schema_switchcn, width: 30, key_back_color: bkg, key_text_color: tkg}
```

Replace `Mode_switch` with `VOICE_ASSIST`, save, and redeploy Trime.

## Tech stack

- Kotlin / Android InputMethodService
- sherpa-onnx (Kotlin API + onnxruntime)
- Ten VAD (ten-vad.onnx)
- Apache Commons Compress (zip / tar.bz2 / tar.gz)

## Acknowledgements

- [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) - offline speech recognition engine
- [BiBi-Keyboard](https://github.com/BryceWG/BiBi-Keyboard) - reference for the sherpa-onnx AAR / reflection wrapper / model management (`SenseVoiceOnnxManager`, `AudioCaptureManager`, `ModelDownloadService`)
- [Trime (同文输入法)](https://github.com/osfans/trime) - the host IME this project adapts to
- [Xime](https://github.com/ximeiorg/Xime) - reference for voice input and README style

## License

MIT License
