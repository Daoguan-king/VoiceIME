# VoiceIME —— 供 Trime 调用的本地语音输入法（sherpa-onnx SenseVoice）

把 sherpa-onnx 包装成一个**极简输入法**（IME），注册 `imeSubtypeMode="voice"` 子类型。
同文输入法（Trime）按下 `VOICE_ASSIST` 语音键时会枚举所有带 voice 子类型的输入法并切换过来——
本应用就是被切换的对象。录音、识别、上屏全部在本应用进程内完成，识别完自动切回 Trime。

>由于同文输入法默认配置不会显示语音输入，需自己更改键盘布局配置文件。我是将长按空格改为了语音输入。


## 功能

- ✅ **首次启动引导**：四步说明（启用输入法 / 麦克风权限 / 模型下载 / 同文配置），带实时状态勾选
- ✅ **本地 SenseVoice 识别**：sherpa-onnx OfflineRecognizer，全程离线
- ✅ **模型可选**：small-int8（约 170 MB，推荐）或 small-full（fp32，约 900 MB），设置页一键下载
- ✅ **语言可选**：auto / 中文 / 英文 / 日文 / 韩文 / 粤语（同一个多语言模型，指定识别语言可提高对应语种准确率）
- ✅ **自动切回**：
- ✅ **VAD 流式实时预览**
- ✅ **语言快捷切换**
- ✅ **大按钮面板**

## 工作原理

1. `res/xml/method.xml` 声明 `android:imeSubtypeMode="voice"` 的 subtype
   （Trime 的 `InputMethodUtils.voiceInputMethods()` 就是按 `mode == "voice"` 过滤的）
2. Trime 语音键 → `switchInputMethod(id, voiceSubtype)` → 系统切到本输入法
3. `VoiceImeService.onStartInputView()` 自动开始录音（AudioRecord 16 kHz 单声道 PCM16）
4. 每块 PCM 送入 **Ten VAD**（`assets/vad/ten-vad.onnx`，332 KB，打包在 APK 内）切出语音段
5. 每个完成的语音段 → `OfflineRecognizer`（SenseVoice）离线识别 → 界面实时预览
6. 连续静音约 1.2 秒自动结束（或手动点"结束"）→ 整段复核识别 → `InputConnection.commitText()` 上屏
7. 默认自动 `switchToLastInputMethod()` 切回 Trime

## 构建

需要 Android Studio（或 JDK 17+ 命令行）。Gradle 9.5.1 / AGP 9.3.0（Kotlin 由 AGP 9 内置）。

```bash
cd VoiceIme
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

> 已内置 `app/libs/sherpa-onnx-1.13.4.aar`（arm64-v8a 单 ABI，约 31 MB 原生库）。
> 32 位设备请把 `app/build.gradle.kts` 里 `abiFilters` 改为 `armeabi-v7a` 或两者都加。

## 安装与使用

1. 安装 APK，首次打开 **VoiceIME** 会显示引导页：
   - ① 打开系统输入法设置，**启用 VoiceIME**（Trime 只枚举已启用的输入法）
   - ② 授予麦克风权限
   - ③ 下载 SenseVoice 模型（int8 约 170 MB；也可手动放入模型目录）
   - ④ 在同文输入法中配置：设置 → 通用 → 首选语音输入法 → 选择 VoiceIME
2. 在同文键盘上触发语音键（默认主题为空格长按；tongwenfeng 主题为 **✾ 键长按**；可在主题 yaml 中把任意键绑定为 `VOICE_ASSIST`）
3. 被切换过来后自动开始录音，界面实时预览识别文字；说完点"⏹ 结束"，整段复核后上屏并自动切回 Trime
   - 面板下方的 🌐 按钮可随时切换识别语言（自动/中/英/日/韩/粤）

### 模型目录

`/data/data/com.voiceime/files/models/sensevoice/<variant>/`

需要文件：`tokens.txt` + `model.int8.onnx`（small-int8）或 `model.onnx`（small-full）。
也可从 sherpa-onnx 官方模型页下载 `sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17` 解压放入。

## 常见问题

- **Trime 提示"未安装语音输入"**：确认 VoiceIME 已在系统设置中启用；Trime 设置 → 通用 → 首选语音输入法 选择 `com.voiceime`
- **长按空格没反应**：语音键在主题 yaml 中定义（`VOICE_ASSIST`），不同主题绑定位置不同（如 tongwenfeng 是 ✾ 键长按）
- **识别慢/首次卡顿**：SenseVoice int8 首次加载模型约需 1~3 秒（之后常驻内存）
- **识别结果带标点/数字**：ITN 已默认开启（`useInverseTextNormalization = true`）

## 后续可做

- VAD 自动断句（移植 BiBi 的 `VadDetector` / 伪流式分段预览）
- 模型自定义下载 URL
- 长按说话（按住录音、松手上屏）

## 相关代码出处

- sherpa-onnx AAR / 反射封装 / 模型管理：参考 BiBi-Keyboard 4.4.1（`SenseVoiceOnnxManager`、`AudioCaptureManager`、`ModelDownloadService`）
