package com.voiceime

import org.junit.Assert.assertEquals
import org.junit.Test

class EmotionEventTest {

    @Test
    fun format_combinesEmotionAndEvent() {
        assertEquals("〔高兴·笑声〕", EmotionEvent.format("<|HAPPY|>", "<|Laugh|>"))
    }

    @Test
    fun format_skipsNeutralDefaults() {
        assertEquals("", EmotionEvent.format("<|NEUTRAL|>", "<|Speech|>"))
        assertEquals("", EmotionEvent.format("", ""))
    }

    @Test
    fun format_unknownLabelStripsBrackets() {
        assertEquals("〔abc〕", EmotionEvent.format("<abc>", ""))
    }
}
