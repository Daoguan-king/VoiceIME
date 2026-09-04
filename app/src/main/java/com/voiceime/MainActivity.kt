package com.voiceime

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.text.InputType
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * VoiceIME 设置页：
 * - 首次启动显示引导（启用输入法 / 麦克风权限 / 模型下载 / 同文配置）；
 * - 主界面支持选择模型变体（int8 / fp32）与识别语言，下载对应模型。
 */
class MainActivity : Activity() {

    companion object {
        private const val PREF_ONBOARDING_DONE = "onboarding_done"
        private const val REQ_NOTIFICATION = 0x5A52
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val prefs by lazy { getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE) }

    private var statusView: TextView? = null
    private var emotionCheck: CheckBox? = null
    // 模型选择器相关 view（buildMainUi 中创建，成员函数联动使用）
    private var familySpinner: Spinner? = null
    private var modelSpinner: Spinner? = null
    private var languageSpinner: Spinner? = null
    private var urlEdit: EditText? = null
    private var modelDescView: TextView? = null
    private var spinnerInitialized = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.init(this)
        if (prefs.getBoolean(PREF_ONBOARDING_DONE, false)) {
            setContentView(buildMainUi())
        } else {
            setContentView(buildOnboardingUi())
        }
    }

    override fun onResume() {
        super.onResume()
        // 从系统设置/权限页返回时刷新状态
        if (prefs.getBoolean(PREF_ONBOARDING_DONE, false)) {
            refreshStatus()
        } else {
            setContentView(buildOnboardingUi())
        }
    }

    // ---------------- 首次启用引导 ----------------

    private fun buildOnboardingUi(): ViewGroup {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }

        content.addView(TextView(this).apply {
            text = getString(R.string.onboard_title)
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
        })
        content.addView(TextView(this).apply {
            text = getString(R.string.onboard_intro)
            textSize = 14f
            setTextColor(getColor(R.color.text_desc))
            setPadding(0, dp(8), 0, dp(16))
        })

        // 1. 启用输入法
        val imeEnabled = isImeEnabled()
        content.addView(sectionTitle(getString(R.string.step_ime_title)))
        content.addView(sectionDesc(getString(R.string.step_ime_desc)))
        content.addView(statusLine(imeEnabled, R.string.status_ime_enabled, R.string.status_ime_disabled))
        content.addView(actionButton(getString(R.string.btn_open_ime_settings)) {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        })

        // 2. 麦克风权限
        val micGranted = hasMicPermission()
        content.addView(sectionTitle(getString(R.string.step_mic_title)))
        content.addView(sectionDesc(getString(R.string.step_mic_desc)))
        content.addView(statusLine(micGranted, R.string.status_mic_ok, R.string.status_mic_missing))
        content.addView(actionButton(getString(R.string.btn_grant_mic)) {
            startActivity(Intent(this@MainActivity, PermissionActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        })

        // 3. 模型下载
        val modelReady = VoiceModelManager.resolveModelDir(this, currentModelSpec()) != null
        content.addView(sectionTitle(getString(R.string.step_model_title)))
        content.addView(sectionDesc(getString(R.string.step_model_desc)))
        content.addView(statusLine(modelReady, R.string.status_model_ok, R.string.status_model_missing_short))
        content.addView(actionButton(getString(R.string.btn_download_model)) {
            startModelDownload(currentModel())
        })

        // 4. 同文配置说明
        content.addView(sectionTitle(getString(R.string.step_trime_title)))
        content.addView(sectionDesc(getString(R.string.step_trime_desc)))

        content.addView(Button(this).apply {
            text = getString(R.string.btn_start_use)
            setOnClickListener {
                prefs.edit().putBoolean(PREF_ONBOARDING_DONE, true).apply()
                setContentView(buildMainUi())
            }
        })

        return ScrollView(this).apply {
            addView(content)
        }
    }

    // ---------------- 主设置界面 ----------------

    private fun buildMainUi(): ViewGroup {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }

        content.addView(TextView(this).apply {
            text = getString(R.string.main_title)
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
        })

        statusView = TextView(this).apply {
            textSize = 14f
            setTextColor(getColor(R.color.text_status))
            setPadding(0, dp(8), 0, dp(16))
        }
        content.addView(statusView)

        // ASR 模型（两段式：第一段选类型，第二段选具体模型）
        content.addView(sectionTitle(getString(R.string.label_model)))
        val families = ModelFamily.entries
        familySpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_item,
                families.map { it.label },
            ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            setPadding(0, dp(2), 0, dp(6))
        }
        content.addView(familySpinner)

        modelSpinner = Spinner(this).apply {
            setPadding(0, dp(6), 0, dp(2))
        }
        content.addView(modelSpinner)
        modelDescView = TextView(this).apply {
            textSize = 12f
            setTextColor(getColor(R.color.text_desc))
            setPadding(0, dp(4), 0, dp(8))
        }
        content.addView(modelDescView)

        // 识别语言（仅支持语言选择的模型可用）
        content.addView(sectionTitle(getString(R.string.label_language)))
        languageSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_item,
                resources.getStringArray(R.array.language_labels),
            ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        }
        content.addView(languageSpinner)
        val languageValues = resources.getStringArray(R.array.language_values)

        // 自定义下载源（可选，zip / tar.bz2 / tar.gz 直链；每个模型独立保存）
        content.addView(sectionTitle(getString(R.string.label_custom_url)))
        urlEdit = EditText(this).apply {
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            hint = getString(R.string.custom_url_hint)
            setSingleLine(true)
        }
        content.addView(urlEdit)
        val urlRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, 0)
        }
        urlRow.addView(Button(this).apply {
            text = getString(R.string.btn_save_custom_url)
            setOnClickListener {
                prefs.edit()
                    .putString(Prefs.customUrlKey(currentModel()), urlEdit?.text.toString().trim())
                    .apply()
                Toast.makeText(this@MainActivity, R.string.toast_custom_url_saved, Toast.LENGTH_SHORT).show()
            }
        })
        urlRow.addView(Button(this).apply {
            text = getString(R.string.btn_clear_custom_url)
            setOnClickListener {
                prefs.edit().putString(Prefs.customUrlKey(currentModel()), null).apply()
                urlEdit?.setText("")
                Toast.makeText(this@MainActivity, R.string.toast_custom_url_cleared, Toast.LENGTH_SHORT).show()
            }
        })
        content.addView(urlRow)

        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        actionRow.addView(actionButton(getString(R.string.btn_download)) {
            startModelDownload(currentModel())
        })
        actionRow.addView(actionButton(getString(R.string.btn_logs)) {
            showLogsDialog()
        })
        actionRow.addView(actionButton(getString(R.string.btn_delete_model)) {
            confirmDeleteModel()
        })
        content.addView(actionRow)

        // 两段式选择器的初始化与联动（逻辑在成员函数中，避免 buildMainUi 继续膨胀）
        initModelSelector(languageValues)

        emotionCheck = CheckBox(this).apply {
            text = getString(R.string.label_emotion_event)
            isChecked = prefs.getBoolean(Prefs.KEY_EMOTION_EVENT, false)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(Prefs.KEY_EMOTION_EVENT, checked).apply()
            }
        }
        content.addView(emotionCheck)

        content.addView(CheckBox(this).apply {
            text = getString(R.string.label_auto_switch)
            isChecked = prefs.getBoolean(Prefs.KEY_AUTO_SWITCH, true)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(Prefs.KEY_AUTO_SWITCH, checked).apply()
            }
        })

        buildDebugSection(content)

        content.addView(TextView(this).apply {
            text = getString(R.string.label_model_dir) + ": " +
                VoiceModelManager.modelRoot(this@MainActivity).absolutePath
            textSize = 12f
            setTextColor(getColor(R.color.text_hint_gray))
            setPadding(0, dp(8), 0, 0)
        })

        refreshStatus()

        return ScrollView(this).apply {
            addView(content)
        }
    }

    // ---------------- 调试参数区 ----------------

    private fun buildDebugSection(content: LinearLayout) {
        content.addView(sectionTitle(getString(R.string.debug_title)))
        content.addView(sectionDesc(getString(R.string.debug_desc)))

        val labels = linkedMapOf(
            DebugParams.K_VAD_THRESHOLD to R.string.dbg_vad_threshold,
            DebugParams.K_VAD_MIN_SILENCE to R.string.dbg_min_silence,
            DebugParams.K_VAD_MIN_SPEECH to R.string.dbg_min_speech,
            DebugParams.K_VAD_WINDOW to R.string.dbg_window,
            DebugParams.K_VAD_MAX_SPEECH to R.string.dbg_max_speech,
            DebugParams.K_THREADS to R.string.dbg_threads,
            DebugParams.K_WORKERS to R.string.dbg_workers,
            DebugParams.K_AUTO_STOP_MS to R.string.dbg_auto_stop,
            DebugParams.K_PREVIEW_CHARS to R.string.dbg_preview_chars,
            DebugParams.K_READ_CHUNK_MS to R.string.dbg_read_chunk,
            DebugParams.K_PREVIEW_INTERVAL_MS to R.string.dbg_preview_interval,
        )
        val editors = HashMap<String, EditText>()
        val current = DebugParams.readAll(this)
        labels.forEach { (key, labelRes) ->
            content.addView(TextView(this).apply {
                text = getString(labelRes)
                textSize = 13f
                setTextColor(getColor(R.color.text_debug_label))
                setPadding(0, dp(8), 0, 0)
            })
            val edit = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                setText(current[key].orEmpty())
            }
            editors[key] = edit
            content.addView(edit)
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        row.addView(Button(this).apply {
            text = getString(R.string.debug_save)
            setOnClickListener {
                val values = editors.mapValues { it.value.text.toString() }
                val err = DebugParams.saveAll(this@MainActivity, values)
                if (err == null) {
                    Toast.makeText(this@MainActivity, R.string.debug_saved, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.debug_save_failed, err),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        })
        row.addView(Button(this).apply {
            text = getString(R.string.debug_reset)
            setOnClickListener {
                DebugParams.reset(this@MainActivity)
                DebugParams.readAll(this@MainActivity).forEach { (key, value) ->
                    editors[key]?.setText(value)
                }
                Toast.makeText(this@MainActivity, R.string.debug_reset_done, Toast.LENGTH_SHORT).show()
            }
        })
        content.addView(row)
    }

    // ---------------- 状态与工具 ----------------

    private fun refreshStatus() {
        val view = statusView ?: return
        val spec = currentModelSpec()
        val ready = VoiceModelManager.resolveModelDir(this, spec) != null
        val sb = StringBuilder()
        sb.append(if (isImeEnabled()) getString(R.string.status_ime_enabled)
        else getString(R.string.status_ime_disabled))
        sb.append(System.lineSeparator())
        sb.append(if (hasMicPermission()) getString(R.string.status_mic_ok)
        else getString(R.string.status_mic_missing))
        sb.append(System.lineSeparator())
        sb.append(getString(R.string.label_model)).append(": ").append(spec.label)
        sb.append("  ").append(if (ready) getString(R.string.status_model_ok)
        else getString(R.string.status_model_missing_short))
        view.text = sb.toString()
    }

    /** 下载当前模型：优先使用该模型保存的自定义源，失败自动回退官方源/HF 镜像 */
    /** 删除当前模型：确认后清理文件（IO 后台执行） */
    private fun confirmDeleteModel() {
        val spec = currentModelSpec()
        val ready = VoiceModelManager.resolveModelDir(this, spec) != null
        if (!ready) {
            toast(R.string.toast_model_not_downloaded)
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_confirm_title)
            .setMessage(getString(R.string.delete_confirm_msg, spec.label))
            .setPositiveButton(R.string.delete_confirm_ok) { _, _ ->
                scope.launch(Dispatchers.IO) {
                    val ok = VoiceModelManager.deleteModel(this@MainActivity, spec)
                    withContext(Dispatchers.Main) {
                        toast(if (ok) R.string.toast_model_deleted else R.string.toast_model_delete_failed)
                        refreshStatus()
                    }
                }
            }
            .setNegativeButton(R.string.logs_close, null)
            .show()
    }

    private fun startModelDownload(modelId: String) {
        val spec = AsrModels.require(modelId)
        val custom = prefs.getString(Prefs.customUrlKey(modelId), null)
        ensureNotificationPermission()
        toast(R.string.toast_download_started)
        scope.launch {
            val ok = VoiceModelManager.downloadModel(this@MainActivity, spec, custom)
            toast(if (ok) R.string.toast_download_done else R.string.toast_download_failed)
            refreshStatus()
        }
    }

    /** 第一段当前选中的类型（以 Spinner 当前选中为准，绝不能从 prefs 推导） */
    private fun selectedFamily(): ModelFamily {
        val pos = familySpinner?.selectedItemPosition ?: 0
        return ModelFamily.entries.getOrNull(pos) ?: ModelFamily.SENSE_VOICE
    }

    /** 按第一段当前类型重建第二段下拉，并选中指定模型（null 选第一个） */
    private fun rebuildModelSpinner(selectId: String?) {
        val specs = AsrModels.byFamily(selectedFamily())
        modelSpinner?.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            specs.map { it.label },
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        modelSpinner?.setSelection(specs.indexOfFirst { it.id == selectId }.coerceAtLeast(0))
    }

    /** 应用当前模型选择：保存、刷新介绍/语言/情感/自定义源 */
    private fun applyModelSelection() {
        val specs = AsrModels.byFamily(selectedFamily())
        val spec = specs.getOrNull(modelSpinner?.selectedItemPosition ?: 0)
            ?: specs.firstOrNull()
            ?: return
        prefs.edit().putString(Prefs.KEY_MODEL, spec.id).apply()
        refreshStatus()
        languageSpinner?.let { updateLanguageState(it) }
        urlEdit?.setText(prefs.getString(Prefs.customUrlKey(spec.id), null).orEmpty())
        modelDescView?.text = spec.summary
        emotionCheck?.let { updateEmotionState(it) }
    }

    /** 初始化两段式选择器与监听（buildMainUi 末尾调用） */
    private fun initModelSelector(languageValues: Array<String>) {
        val current = currentModelSpec()
        familySpinner?.setSelection(ModelFamily.entries.indexOf(current.family).coerceAtLeast(0))
        rebuildModelSpinner(current.id)
        languageSpinner?.setSelection(languageValues.indexOf(currentLanguage()).coerceAtLeast(0))
        urlEdit?.setText(prefs.getString(Prefs.customUrlKey(current.id), null).orEmpty())
        modelDescView?.text = current.summary
        languageSpinner?.let { updateLanguageState(it) }
        emotionCheck?.let { updateEmotionState(it) }

        // 首次布局回调保护：Spinner 挂上监听后会自动回调一次 onItemSelected，
        // 此时不能重建第二段（否则会丢掉初始化选中的具体模型）
        familySpinner?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!spinnerInitialized) {
                    spinnerInitialized = true
                    return
                }
                rebuildModelSpinner(null)
                applyModelSelection()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        modelSpinner?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                applyModelSelection()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        languageSpinner?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                prefs.edit().putString(Prefs.KEY_LANGUAGE, languageValues[position]).apply()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun updateLanguageState(spinner: Spinner) {
        val enabled = currentModelSpec().supportsLanguage
        spinner.isEnabled = enabled
        spinner.alpha = if (enabled) 1f else 0.4f
    }

    /** 情感/事件检测仅原版 SenseVoiceSmall 支持（WSYue 粤语模型无此能力） */
    private fun updateEmotionState(check: CheckBox) {
        val enabled = currentModelSpec().supportsEmotionEvent
        check.isEnabled = enabled
        check.alpha = if (enabled) 1f else 0.4f
    }

    private fun isImeEnabled(): Boolean {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        return imm.enabledInputMethodList.any { it.packageName == packageName }
    }

    private fun hasMicPermission(): Boolean =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** 当前模型 id：新键优先，旧 KEY_MODEL_VARIANT 自动迁移 */
    private fun currentModel(): String {
        val sp = prefs
        return AsrModels.byId(sp.getString(Prefs.KEY_MODEL, null))?.id
            ?: AsrModels.byLegacyVariant(sp.getString(Prefs.KEY_MODEL_VARIANT, null))?.id
            ?: AsrModels.DEFAULT_ID
    }

    private fun currentModelSpec(): ModelSpec = AsrModels.require(currentModel())

    private fun currentLanguage(): String =
        prefs.getString(Prefs.KEY_LANGUAGE, "auto") ?: "auto"

    private fun sectionTitle(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 16f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(16), 0, dp(4))
    }

    private fun sectionDesc(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(getColor(R.color.text_desc))
    }

    private fun statusLine(ok: Boolean, okRes: Int, failRes: Int): TextView =
        TextView(this).apply {
            text = (if (ok) getString(R.string.status_ok) + " " else getString(R.string.status_fail) + " ") +
                getString(if (ok) okRes else failRes)
            textSize = 13f
            setTextColor(if (ok) getColor(R.color.status_ok) else getColor(R.color.status_fail))
        }

    private fun actionButton(text: String, onClick: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            setOnClickListener { onClick() }
        }

    private fun toast(resId: Int) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
    }

    /** Android 13+ 请求通知权限（下载进度条用；拒绝不影响下载） */
    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATION)
        }
    }

    /** 应用内日志弹窗：级别过滤 + 复制/分享 + 崩溃日志查看（含下载失败原因） */
    private fun showLogsDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), 0)
        }

        // 级别过滤
        val filter = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_item,
                listOf(
                    getString(R.string.logs_filter_all),
                    getString(R.string.logs_filter_warn),
                    getString(R.string.logs_filter_error),
                ),
            ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        }
        container.addView(filter)

        // 操作行：复制 / 分享 / 清空 / 崩溃日志（无崩溃时隐藏）
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), 0, dp(8), dp(4))
        }
        val body = TextView(this).apply {
            textSize = 11f
            setTypeface(Typeface.MONOSPACE)
            setTextIsSelectable(true)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        fun currentText(): String {
            val minLevel = when (filter.selectedItemPosition) {
                1 -> LogLevel.WARN
                2 -> LogLevel.ERROR
                else -> null
            }
            val list = AppLog.entriesSnapshot().filter { minLevel == null || it.level >= minLevel }
            return if (list.isEmpty()) {
                getString(R.string.logs_empty)
            } else {
                list.joinToString("\n") { AppLog.formatEntry(it) }
            }
        }
        btnRow.addView(dialogButton(R.string.logs_copy) {
            copyText(currentText())
        })
        btnRow.addView(dialogButton(R.string.logs_share) {
            shareText(currentText())
        })
        btnRow.addView(dialogButton(R.string.logs_clear) {
            AppLog.clear()
            body.text = currentText()
            toast(R.string.logs_cleared)
        })
        if (AppLog.crashLogs().isNotEmpty()) {
            btnRow.addView(dialogButton(R.string.logs_crash) { showCrashLogsDialog() })
        }
        container.addView(btnRow)

        val scroll = ScrollView(this).apply { addView(body) }
        container.addView(scroll)
        body.text = currentText()
        // 最新日志在底部：打开时滚动到底
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }

        filter.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                body.text = currentText()
                scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.logs_title)
            .setView(container)
            .setPositiveButton(R.string.logs_close, null)
            .show()
    }

    private fun showCrashLogsDialog() {
        val crashes = AppLog.crashLogs()
        if (crashes.isEmpty()) {
            toast(R.string.logs_crash_empty)
            return
        }
        val names = crashes.map { it.name + " (" + (it.length() / 1024) + " KB)" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.logs_crash_title)
            .setItems(names) { _, which -> showCrashContentDialog(crashes[which]) }
            .setNegativeButton(R.string.logs_close, null)
            .show()
    }

    private fun showCrashContentDialog(f: File) {
        val body = TextView(this).apply {
            text = AppLog.readCrashLog(f)
            textSize = 11f
            setTypeface(Typeface.MONOSPACE)
            setTextIsSelectable(true)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val scroll = ScrollView(this).apply { addView(body) }
        AlertDialog.Builder(this)
            .setTitle(f.name)
            .setView(scroll)
            .setPositiveButton(R.string.logs_close, null)
            .setNeutralButton(R.string.logs_share) { _, _ -> shareText(AppLog.readCrashLog(f)) }
            .show()
    }

    private fun copyText(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("voiceime", text))
        Toast.makeText(this, R.string.logs_copied, Toast.LENGTH_SHORT).show()
    }

    private fun shareText(text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.logs_share)))
    }

    /** 日志弹窗内的小号按钮 */
    private fun dialogButton(textRes: Int, onClick: () -> Unit): Button =
        Button(this).apply {
            text = getString(textRes)
            textSize = 12f
            minHeight = 0
            minWidth = 0
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener { onClick() }
        }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
