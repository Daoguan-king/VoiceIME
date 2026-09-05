package com.voiceime

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioCodecTest {

    @Test
    fun pcmToFloat_convertsLittleEndianSamples() {
        // 0x0000 → 0.0；0x7FFF → ≈0.99997；0x8000(-32768) → 截断到 -1.0
        val pcm = byteArrayOf(0x00, 0x00, 0xFF.toByte(), 0x7F, 0x00, 0x80.toByte())
        val out = AudioCodec.pcmToFloat(pcm)
        assertEquals(3, out.size)
        assertEquals(0f, out[0], 1e-6f)
        assertEquals(32767f / 32768f, out[1], 1e-6f)
        assertEquals(-1f, out[2], 1e-6f)
    }

    @Test
    fun pcmToFloat_respectsOffsetAndLen() {
        // 第二个采样 0x0010 = 16
        val pcm = byteArrayOf(0x00, 0x00, 0x10, 0x00)
        val out = AudioCodec.pcmToFloat(pcm, offset = 2, len = 2)
        assertEquals(1, out.size)
        assertEquals(16f / 32768f, out[0], 1e-6f)
    }

    @Test
    fun pcmToFloat_emptyInput() {
        assertEquals(0, AudioCodec.pcmToFloat(ByteArray(0)).size)
    }

    @Test
    fun rmsLevel_mapsBounds() {
        assertEquals(0f, AudioCodec.rmsLevel(FloatArray(0)), 1e-6f)
        // 满幅方波 RMS=1 → 0dB → 1.0
        val full = FloatArray(100) { if (it % 2 == 0) 1f else -1f }
        assertEquals(1f, AudioCodec.rmsLevel(full), 1e-3f)
        // 全零 → -inf dB → 0.0
        assertEquals(0f, AudioCodec.rmsLevel(FloatArray(100)), 1e-6f)
    }
}
