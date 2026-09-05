package com.voiceime

/**
 * SenseVoice 富文本识别（情感 / 声音事件检测）标签格式化。
 *
 * sherpa-onnx 的 SenseVoice 解码结果中，前几个 token 携带情感与事件信息
 * （Kotlin API：OfflineRecognizerResult.getEmotion() / getEvent()），
 * 文本本身已剔除这些标签。开启"情感/事件检测"后，把非默认的标签
 * 以中文形式附加到识别文本末尾，例如：今天真开心〔高兴·笑声〕。
 */
object EmotionEvent {

    /** 情感标签 → 中文；未识别的原样显示（去掉尖括号） */
    private val EMOTIONS = mapOf(
        "<|HAPPY|>" to "高兴",
        "<|SAD|>" to "悲伤",
        "<|ANGRY|>" to "生气",
        "<|NEUTRAL|>" to "平静",
        "<|FEARFUL|>" to "恐惧",
        "<|DISGUSTED|>" to "厌恶",
        "<|SURPRISED|>" to "惊讶",
    )

    /** 声音事件标签 → 中文；未识别的原样显示（去掉尖括号） */
    private val EVENTS = mapOf(
        "<|Speech|>" to "说话",
        "<|BGM|>" to "音乐",
        "<|Applause|>" to "掌声",
        "<|Laugh|>" to "笑声",
        "<|Laughter|>" to "笑声",
        "<|Cry|>" to "哭声",
        "<|Sneeze|>" to "喷嚏",
        "<|Cough|>" to "咳嗽",
        "<|Breath|>" to "呼吸",
    )

    /**
     * 生成附加标签文本；默认状态（平静 / 说话）或全部缺失时返回空串。
     * 例如：<|HAPPY|> + <|Laugh|> → "〔高兴·笑声〕"
     */
    fun format(emotion: String, event: String): String {
        val parts = buildList {
            val e = label(emotion, EMOTIONS)
            if (e != null && e != "平静") add(e)
            val v = label(event, EVENTS)
            if (v != null && v != "说话") add(v)
        }
        return if (parts.isEmpty()) "" else "〔" + parts.joinToString("·") + "〕"
    }

    private fun label(raw: String, map: Map<String, String>): String? {
        if (raw.isBlank()) return null
        return map[raw] ?: raw.trim('<', '>')
    }
}
