package com.voiceime

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
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

/**
 * VoiceIME 设置页：
 * - 首次启动显示引导（启用输入法 / 麦克风权限 / 模型下载 / 同文配置）；
 * - 主界面支持选择模型变体（int8 / fp32）与识别语言，下载对应模型。
 */
class MainActivity : Activity() {

    companion object {
        private const val PREF_ONBOARDING_DONE = "onboarding_done"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val prefs by lazy { getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE) }

    private var statusView: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        val variant = currentVariant()
        val modelReady = VoiceModelManager.resolveModelDir(this, variant) != null
        content.addView(sectionTitle(getString(R.string.step_model_title)))
        content.addView(sectionDesc(getString(R.string.step_model_desc)))
        content.addView(statusLine(modelReady, R.string.status_model_ok, R.string.status_model_missing_short))
        content.addView(actionButton(getString(R.string.btn_download_model)) {
            startModelDownload(variant)
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

        // 模型变体
        content.addView(sectionTitle(getString(R.string.label_variant)))
        val variantSpinner = Spinner(this)
        val variantValues = resources.getStringArray(R.array.model_variant_values)
        variantSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            resources.getStringArray(R.array.model_variant_labels),
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        variantSpinner.setSelection(variantValues.indexOf(currentVariant()).coerceAtLeast(0))
        variantSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                prefs.edit().putString(Prefs.KEY_MODEL_VARIANT, variantValues[position]).apply()
                refreshStatus()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        content.addView(variantSpinner)

        // 识别语言
        content.addView(sectionTitle(getString(R.string.label_language)))
        val languageSpinner = Spinner(this)
        val languageValues = resources.getStringArray(R.array.language_values)
        languageSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            resources.getStringArray(R.array.language_labels),
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        languageSpinner.setSelection(languageValues.indexOf(currentLanguage()).coerceAtLeast(0))
        languageSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                prefs.edit().putString(Prefs.KEY_LANGUAGE, languageValues[position]).apply()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        content.addView(languageSpinner)

        content.addView(actionButton(getString(R.string.btn_download)) {
            startModelDownload(currentVariant())
        })

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
            setTextColor(Color.parseColor("#888888"))
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
        val variant = currentVariant()
        val ready = VoiceModelManager.resolveModelDir(this, variant) != null
        val sb = StringBuilder()
        sb.append(if (isImeEnabled()) getString(R.string.status_ime_enabled)
        else getString(R.string.status_ime_disabled))
        sb.append(System.lineSeparator())
        sb.append(if (hasMicPermission()) getString(R.string.status_mic_ok)
        else getString(R.string.status_mic_missing))
        sb.append(System.lineSeparator())
        sb.append(getString(R.string.label_variant)).append(": ").append(variant)
        sb.append("  ").append(if (ready) getString(R.string.status_model_ok)
        else getString(R.string.status_model_missing_short))
        view.text = sb.toString()
    }

    private fun startModelDownload(variant: String) {
        toast(R.string.toast_download_started)
        scope.launch {
            val ok = VoiceModelManager.downloadModel(this@MainActivity, variant, null)
            toast(if (ok) R.string.toast_download_done else R.string.toast_download_failed)
            refreshStatus()
        }
    }

    private fun isImeEnabled(): Boolean {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        return imm.enabledInputMethodList.any { it.packageName == packageName }
    }

    private fun hasMicPermission(): Boolean =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun currentVariant(): String =
        prefs.getString(Prefs.KEY_MODEL_VARIANT, VoiceModelManager.VARIANT_INT8)
            ?: VoiceModelManager.VARIANT_INT8

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
            text = (if (ok) getString(R.string.status_ok) + " " else "✗ ") +
                getString(if (ok) okRes else failRes)
            textSize = 13f
            setTextColor(if (ok) Color.parseColor("#2E7D32") else Color.parseColor("#C62828"))
        }

    private fun actionButton(text: String, onClick: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            setOnClickListener { onClick() }
        }

    private fun toast(resId: Int) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
