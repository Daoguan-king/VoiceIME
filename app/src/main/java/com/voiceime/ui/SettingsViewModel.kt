package com.voiceime.ui

import android.app.Application
import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.voiceime.AppLog
import com.voiceime.AppLocale
import com.voiceime.UiParams
import com.voiceime.AsrModels
import com.voiceime.DebugParams
import com.voiceime.ModelFamily
import com.voiceime.ModelSpec
import com.voiceime.Prefs
import com.voiceime.R
import com.voiceime.VoiceModelManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置页状态与逻辑（从旧 MainActivity 平移，行为保持一致）：
 * - 两段式模型选择（类型 → 具体模型），保存/联动/自定义源切换；
 * - 状态刷新（输入法启用/麦克风/模型就绪）；
 * - 模型下载与删除、调试参数读写校验。
 * 业务规则不改动，只把命令式 UI 更新替换为 Compose 状态。
 */
class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        private const val PREF_ONBOARDING_DONE = "onboarding_done"
    }

    private val context: Context get() = getApplication()
    private val prefs = app.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)

    // ---- 可观察状态 ----
    var onboardingDone by mutableStateOf(prefs.getBoolean(PREF_ONBOARDING_DONE, false))
        private set
    var imeEnabled by mutableStateOf(false)
        private set
    var micGranted by mutableStateOf(false)
        private set
    var modelReady by mutableStateOf(false)
        private set

    var familyIndex by mutableIntStateOf(0)
        private set
    var modelIndex by mutableIntStateOf(0)
        private set
    var modelSummaryRes by mutableIntStateOf(0)
        private set
    var languageIndex by mutableIntStateOf(0)
        private set
    var languageEnabled by mutableStateOf(true)
        private set
    var emotionEvent by mutableStateOf(false)
        private set
    var emotionEnabled by mutableStateOf(true)
        private set
    var autoSwitch by mutableStateOf(true)
        private set
    var customUrl by mutableStateOf("")
        private set
    var debugValues by mutableStateOf<Map<String, String>>(emptyMap())
        private set

    // ---- 界面设置（UiParams） ----
    var levelAnimation by mutableStateOf(true)
        private set
    var dynamicColor by mutableStateOf(true)
        private set
    var appLocaleIndex by mutableIntStateOf(0)
        private set
    var animValues by mutableStateOf<Map<String, String>>(emptyMap())
        private set

    val animFields = UiParams.animFields

    /** 调试参数字段定义（由 [DebugParams.fields] 单表生成，避免三处各写一遍） */
    val debugFields: List<Triple<String, Int, Boolean>> =
        DebugParams.fields.map { Triple(it.key, it.labelRes, it.decimal) }

    private val languageLabels: List<String> =
        context.resources.getStringArray(R.array.language_labels).toList()
    private val languageValues: List<String> =
        context.resources.getStringArray(R.array.language_values).toList()

    val familyOptions: List<Int> = ModelFamily.entries.map { it.labelRes }

    /** 当前选中的模型（删除确认等处取 labelRes） */
    val currentModelSpec: ModelSpec
        get() = AsrModels.byFamily(ModelFamily.entries[familyIndex])
            .getOrNull(modelIndex) ?: currentModelIdSpec()

    fun modelOptions(familyIndex: Int): List<Int> =
        AsrModels.byFamily(ModelFamily.entries[familyIndex]).map { it.labelRes }

    init {
        AppLog.init(context)
        val current = currentModelIdSpec()
        familyIndex = ModelFamily.entries.indexOf(current.family).coerceAtLeast(0)
        val specs = AsrModels.byFamily(current.family)
        modelIndex = specs.indexOfFirst { it.id == current.id }.coerceAtLeast(0)
        languageIndex = languageValues.indexOf(currentLanguage()).coerceAtLeast(0)
        customUrl = prefs.getString(Prefs.customUrlKey(current.id), null).orEmpty()
        modelSummaryRes = current.summaryRes
        emotionEvent = prefs.getBoolean(Prefs.KEY_EMOTION_EVENT, false)
        autoSwitch = prefs.getBoolean(Prefs.KEY_AUTO_SWITCH, true)
        languageEnabled = current.supportsLanguage
        emotionEnabled = current.supportsEmotionEvent
        debugValues = DebugParams.readAll(context)
        levelAnimation = UiParams.levelAnimation(context)
        dynamicColor = UiParams.dynamicColor(context)
        appLocaleIndex = AppLocale.OPTIONS.indexOf(AppLocale.current(context)).coerceAtLeast(0)
        animValues = UiParams.readAnimAll(context)
        refresh()
    }

    // ---------------- 模型选择（两段式） ----------------

    /** 第一段（类型）变更：重建第二段并选中第一个，保存 */
    fun onFamilySelected(index: Int) {
        if (index == familyIndex) return
        familyIndex = index
        modelIndex = 0
        applyModelSelection()
    }

    /** 第二段（具体模型）变更：保存 */
    fun onModelSelected(index: Int) {
        if (index == modelIndex) return
        modelIndex = index
        applyModelSelection()
    }

    private fun applyModelSelection() {
        val specs = AsrModels.byFamily(ModelFamily.entries[familyIndex])
        val spec = specs.getOrNull(modelIndex) ?: specs.firstOrNull() ?: return
        prefs.edit().putString(Prefs.KEY_MODEL, spec.id).apply()
        modelSummaryRes = spec.summaryRes
        languageEnabled = spec.supportsLanguage
        emotionEnabled = spec.supportsEmotionEvent
        customUrl = prefs.getString(Prefs.customUrlKey(spec.id), null).orEmpty()
        refresh()
    }

    fun onLanguageSelected(index: Int) {
        languageIndex = index
        prefs.edit().putString(Prefs.KEY_LANGUAGE, languageValues[index]).apply()
    }

    // ---------------- 自定义下载源 ----------------

    fun onUrlChange(text: String) {
        customUrl = text
    }

    fun saveCustomUrl() {
        prefs.edit().putString(Prefs.customUrlKey(currentModelSpec.id), customUrl.trim()).apply()
        toast(R.string.toast_custom_url_saved)
    }

    fun clearCustomUrl() {
        prefs.edit().putString(Prefs.customUrlKey(currentModelSpec.id), null).apply()
        customUrl = ""
        toast(R.string.toast_custom_url_cleared)
    }

    // ---------------- 开关 ----------------

    fun onEmotionEventChange(checked: Boolean) {
        emotionEvent = checked
        prefs.edit().putBoolean(Prefs.KEY_EMOTION_EVENT, checked).apply()
    }

    fun onAutoSwitchChange(checked: Boolean) {
        autoSwitch = checked
        prefs.edit().putBoolean(Prefs.KEY_AUTO_SWITCH, checked).apply()
    }

    // ---------------- 下载 / 删除 ----------------

    fun startDownload() {
        val spec = currentModelSpec
        val custom = prefs.getString(Prefs.customUrlKey(spec.id), null)
        toast(R.string.toast_download_started)
        viewModelScope.launch {
            val ok = VoiceModelManager.downloadModel(context, spec, custom)
            toast(if (ok) R.string.toast_download_done else R.string.toast_download_failed)
            refresh()
        }
    }

    fun deleteModel() {
        val spec = currentModelSpec
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                VoiceModelManager.deleteModel(context, spec)
            }
            toast(if (ok) R.string.toast_model_deleted else R.string.toast_model_delete_failed)
            refresh()
        }
    }

    // ---------------- 调试参数 ----------------

    fun onDebugChange(key: String, value: String) {
        debugValues = debugValues + (key to value)
    }

    fun saveDebug() {
        val err = DebugParams.saveAll(context, debugValues)
        if (err == null) {
            toast(R.string.debug_saved)
        } else {
            toast(context.getString(R.string.debug_save_failed, err))
        }
        debugValues = DebugParams.readAll(context)
    }

    fun resetDebug() {
        DebugParams.reset(context)
        debugValues = DebugParams.readAll(context)
        toast(R.string.debug_reset_done)
    }

    // ---------------- 界面设置 ----------------

    fun onLevelAnimationChange(checked: Boolean) {
        levelAnimation = checked
        UiParams.setLevelAnimation(context, checked)
    }

    fun onDynamicColorChange(checked: Boolean) {
        dynamicColor = checked
        UiParams.setDynamicColor(context, checked)
    }

    /** 切换应用语言（Compose 层即时生效；Android 13+ 同步系统 per-app language） */
    fun onAppLocaleSelected(index: Int) {
        appLocaleIndex = index
        AppLocale.set(context, AppLocale.OPTIONS[index])
    }

    fun onAnimChange(key: String, value: String) {
        animValues = animValues + (key to value)
    }

    fun saveAnim() {
        val err = UiParams.saveAnim(context, animValues)
        if (err == null) {
            toast(R.string.ui_anim_saved)
        } else {
            // 此前误用 debug_save_failed（复制粘贴遗留），改用界面参数自己的文案
            toast(context.getString(R.string.ui_save_failed, err))
        }
        animValues = UiParams.readAnimAll(context)
    }

    fun resetAnim() {
        UiParams.resetAnim(context)
        animValues = UiParams.readAnimAll(context)
        toast(R.string.ui_anim_reset_done)
    }

    // ---------------- 引导页 ----------------

    fun completeOnboarding() {
        prefs.edit().putBoolean(PREF_ONBOARDING_DONE, true).apply()
        onboardingDone = true
        refresh()
    }

    // ---------------- 状态刷新 ----------------

    fun refresh() {
        val spec = currentModelSpec
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imeEnabled = imm.enabledInputMethodList.any { it.packageName == context.packageName }
        micGranted = context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        modelReady = VoiceModelManager.resolveModelDir(context, spec) != null
    }

    // ---------------- 内部工具 ----------------

    private fun currentModelId(): String =
        AsrModels.byId(prefs.getString(Prefs.KEY_MODEL, null))?.id
            ?: AsrModels.byLegacyVariant(prefs.getString(Prefs.KEY_MODEL_VARIANT, null))?.id
            ?: AsrModels.DEFAULT_ID

    private fun currentModelIdSpec(): ModelSpec = AsrModels.require(currentModelId())

    private fun currentLanguage(): String =
        prefs.getString(Prefs.KEY_LANGUAGE, "auto") ?: "auto"

    private fun toast(resId: Int) {
        Toast.makeText(context, resId, Toast.LENGTH_SHORT).show()
    }

    private fun toast(text: String) {
        Toast.makeText(context, text, Toast.LENGTH_LONG).show()
    }
}
