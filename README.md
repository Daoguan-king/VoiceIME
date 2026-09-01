<h1 align="center">VoiceIME · 供 Trime 调用的本地语音输入法</h1>

<p align="center">
    <a href="README_EN.md">English</a>
</p>

<p align="center">一款基于 <a href="https://github.com/k2-fsa/sherpa-onnx">sherpa-onnx</a> 的 <b>本地离线语音输入法</b>，
通过注册 <code>imeSubtypeMode="voice"</code> 子类型，让同文输入法（Trime）按下语音键即可切换使用。</p>

## 功能特点

- **本地离线识别** - sherpa-onnx OfflineRecognizer，全程离线，录音与识别均在本机完成
- **多模型支持** - SenseVoice、Qwen3-ASR、Moonshine、Paraformer、Zipformer 共 18 个模型（含魔搭社区镜像），设置页一键下载
- **自定义下载源** - 每个模型可单独填写 zip / tar.bz2 / tar.gz 直链；失败自动回退官方源与 HF 镜像
- **首次启动引导** - 四步说明（启用输入法 / 麦克风权限 / 模型下载 / 同文配置），带实时状态勾选
- **语言可选** - auto / 中文 / 英文 / 日文 / 韩文 / 粤语（SenseVoice 模型）
- **自动切回** - 识别完毕自动切回原输入法，无缝衔接
- **VAD 流式实时预览** - Ten VAD 切段 + 滑动窗口预览（虽然有些拉跨，不过能用）
- **语言快捷切换** - 输入面板一键切换目标语言
- **大按钮面板** - 防止按错

## 系统要求

- Android 8.0 (API 26) 及以上

## 安装

选择对应架构的 APK：
- **arm64-v8a** - 适用于大多数现代手机（**默认构建**）
- **armeabi-v7a** - 适用于旧款 32 位手机
- **x86_64** - 适用于模拟器
- **universal** - 包含所有架构，体积较大

## 构建

需要 Android Studio（或 JDK 17+ 命令行）。Gradle 9.5.1 / AGP 9.3.0（Kotlin 由 AGP 9 内置）。

```shell
# 克隆项目
git clone https://github.com/Daoguan-king/VoiceIME

cd VoiceIME

# 构建 Debug APK（默认 arm64-v8a）
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk

# 指定 ABI 构建
./gradlew :app:assembleDebug -Pabi=armeabi-v7a   # 32 位旧手机
./gradlew :app:assembleDebug -Pabi=x86_64        # 模拟器
./gradlew :app:assembleDebug -Pabi=universal     # 全部架构

# 或构建 Release APK
./gradlew :app:assembleRelease
```

## 安装与使用

1. 安装 APK，首次打开 **VoiceIME** 会显示引导页：
    - ① 打开系统输入法设置，**启用 VoiceIME**（Trime 只枚举已启用的输入法）
    - ② 授予麦克风权限
    - ③ 下载语音模型（SenseVoice int8 约 170 MB；也可手动放入模型目录）
    - ④ 在同文输入法中配置：设置 → 通用 → 首选语音输入法 → 选择 VoiceIME
2. 在同文键盘上触发语音键（默认主题为空格长按；tongwenfeng 主题为 ✾ 键长按；可在主题 yaml 中把任意键绑定为 `VOICE_ASSIST`）
3. 被切换过来后自动开始录音，界面实时预览识别文字；说完点"⏹ 结束"，整段复核后上屏并自动切回 Trime
    - 面板下方的 🌐 按钮可随时切换识别语言（自动/中/英/日/韩/粤，仅 SenseVoice 模型支持）

### 模型下载（应用内一键下载）

设置页采用**两段式选择**：先选模型类型（SenseVoice / Qwen3-ASR / Moonshine / Paraformer / Zipformer），再选该类型下的具体模型。

| 类型 | 模型 | 大小（约） | 语言 |
| --- | --- | --- | --- |
| SenseVoice | small（int8，推荐） | 170 MB | 中 / 英 / 日 / 韩 / 粤 |
| | small（fp32） | 900 MB | 中 / 英 / 日 / 韩 / 粤 |
| | small（int8，2025-09-09 新版） | 170 MB | 中 / 英 / 日 / 韩 / 粤 |
| Qwen3-ASR | 0.6B（int8） | 1 GB | 中 / 英 |
| Moonshine | tiny（英文 int8） | 150 MB | 英文 |
| | base（英文 int8） | 300 MB | 英文 |
| | tiny（韩文量化） | 60 MB | 韩文 |
| | tiny（日文量化） | 60 MB | 日文 |
| | base（中文量化） | 120 MB | 中文 |
| | tiny（中文 int8，魔搭） | 30 MB | 中文 |
| | base（中文 int8，魔搭） | 100 MB | 中文 |
| Paraformer | 中文（int8） | 120 MB | 中文 |
| | 中文 small（int8） | 80 MB | 中文 |
| Zipformer | 中英（int8） | 200 MB | 中 / 英混合 |
| | 韩文（int8） | 200 MB | 韩文 |
| | 日文（int8，ReazonSpeech） | 200 MB | 日文 |
| | small（中英粤，魔搭） | 60 MB | 中 / 英 / 粤 |
| | large（魔搭） | 180 MB | 多语言 |

**下载源顺序**：自定义直链 → 逐文件镜像（HF hf-mirror.com，无需解压）→ 官方压缩包（zip / tar.bz2 / tar.gz）。自定义源在设置页填写，每个模型独立保存。

### 模型目录

`/Android/data/com.voiceime/files/models/<模型id>/`
（即 `/storage/emulated/0/Android/data/com.voiceime/files/models/`，外部存储，文件管理器可直接访问，无需存储权限）

也可以手动放入（从 sherpa-onnx 官方模型页下载解压后，把对应文件放入模型目录即可），各模型所需文件见上表；Qwen3-ASR 额外需要 `tokenizer/` 目录（`vocab.json` / `merges.txt` / `tokenizer_config.json`）。

## 工作原理

1. `res/xml/method.xml` 声明 `android:imeSubtypeMode="voice"` 的 subtype（Trime 的 `InputMethodUtils.voiceInputMethods()` 就是按 `mode == "voice"` 过滤的）
2. Trime 语音键 → `switchInputMethod(id, voiceSubtype)` → 系统切到本输入法
3. `VoiceImeService.onStartInputView()` 自动开始录音（AudioRecord 16 kHz 单声道 PCM16）
4. 每块 PCM 送入 **Ten VAD**（`assets/vad/ten-vad.onnx`，332 KB，打包在 APK 内）切出语音段
5. 每个完成的语音段 → `OfflineRecognizer`（SenseVoice / Qwen3-ASR / Paraformer 等）离线识别 → 界面实时预览
6. 连续静音约 2 秒自动结束（或手动点"结束"）→ 整段复核识别 → `InputConnection.commitText()` 上屏
7. 默认自动 `switchToLastInputMethod()` 切回 Trime

## 常见问题

- **Trime 提示"未安装语音输入"**：确认 VoiceIME 已在系统设置中启用；Trime 设置 → 通用 → 首选语音输入法 选择 `com.voiceime`
- **长按空格没反应**：语音键在主题 yaml 中定义（`VOICE_ASSIST`），不同主题绑定位置不同（如 tongwenfeng 是 ✾ 键长按）
- **识别慢/首次卡顿**：SenseVoice int8 首次加载模型约需 1~3 秒（之后常驻内存）；Qwen3-ASR 模型较大，加载会更慢
- **识别结果带标点/数字**：ITN 已默认开启（`useInverseTextNormalization = true`，SenseVoice）
- **模型下载慢/失败**：官方源（GitHub）与 HF 镜像均失败时，可在设置页填写自定义下载源（国内镜像、网盘直链等），或手动解压放入模型目录
- **模型下载似乎卡死了**：这不一定是你的问题，下载的文件可能是bZ2压缩格式，解压缩很慢，多等一下吧
- **语言选择是灰色的**：当前模型不支持指定语言（如 Qwen3-ASR 自动识别中/英、Paraformer 仅中文），属正常现象
- **为什么这个应用缓存这么大**：这大概率是下载模型失败导致的，进入设置清理缓存即可

## 如何启用同文输入法的语音输入

同文输入法的语音输入由键盘主题提供，现以修改"**标准**"主题的**空格键**为例：

用文件管理器（如 MT管理器）打开路径：

`/Android/data/com.osfans.trime/files/shared/tongwenfeng.trime.yaml`

找到 1105 行（不同版本可能有差异）：

```
      - {click: space, long_click: Mode_switch, swipe_left: "Left", swipe_right: "Right", swipe_up: Schema_switchcn, width: 30, key_back_color: bkg, key_text_color: tkg}
```

将 `Mode_switch` 改为 `VOICE_ASSIST`，保存后重新部署同文输入法即可。

## 技术栈

- Kotlin / Android InputMethodService
- sherpa-onnx（Kotlin API + onnxruntime）
- Ten VAD（ten-vad.onnx）
- Apache Commons Compress（zip / tar.bz2 / tar.gz 解压）

## 致谢

- [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) - 离线语音识别引擎
- [BiBi-Keyboard](https://github.com/BryceWG/BiBi-Keyboard) - sherpa-onnx AAR / 反射封装 / 模型管理参考（`SenseVoiceOnnxManager`、`AudioCaptureManager`、`ModelDownloadService`）
- [Trime（同文输入法）](https://github.com/osfans/trime) - 被适配的输入法宿主
- [Xime](https://github.com/ximeiorg/Xime) - 语音输入参考

## 许可证

MIT License
