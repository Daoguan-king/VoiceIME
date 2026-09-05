package com.voiceime

import java.io.File
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [VoiceModelManager.ensureMoonshineBase64Tokens] 的 JVM 单测：
 * 明文→base64 转换、<0xNN> 字节回退特例、幂等与损坏文件跳过。
 */
class MoonshineTokensTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun convert(content: String): String {
        val dir = tmp.newFolder()
        File(dir, "tokens.txt").writeText(content)
        VoiceModelManager.ensureMoonshineBase64Tokens(dir)
        return File(dir, "tokens.txt").readText()
    }

    @Test
    fun plainFile_convertedToBase64() {
        val b64 = Base64.getEncoder()
        val out = convert("<unk> 0\nhello 1\n 31353\n")
        assertEquals(
            "${b64.encodeToString("<unk>".toByteArray())} 0\n" +
                "${b64.encodeToString("hello".toByteArray())} 1\n" +
                "${b64.encodeToString(ByteArray(0))} 31353\n",
            out,
        )
    }

    @Test
    fun literalByteToken_rewrittenToRawByte() {
        // 明文中的 <0xA1>：应编码原始单字节（0xA1）而非字面字符串，
        // 否则 SymbolTable 字节回退后识别文本里会出现 "<0xA1>"
        val out = convert("<0xA1> 5\n")
        val expected = Base64.getEncoder().encodeToString(byteArrayOf(0xA1.toByte())) + " 5\n"
        assertEquals(expected, out)
    }

    @Test
    fun legacyMistranslatedFile_fixedToRawByte() {
        // 旧版误转：文件已是 base64，但字节 token 被编码成了字面 "<0xNN>" → 修正为裸字节
        val b64 = Base64.getEncoder()
        val literal = b64.encodeToString("<0xA1>".toByteArray())
        val out = convert("$literal 5\n")
        val expected = b64.encodeToString(byteArrayOf(0xA1.toByte())) + " 5\n"
        assertEquals(expected, out)
    }

    @Test
    fun alreadyCorrectBase64_leftUnchanged() {
        val b64 = Base64.getEncoder()
        val content = "${b64.encodeToString("hi".toByteArray())} 7\n"
        assertEquals(content, convert(content))
    }

    @Test
    fun malformedLines_skipped() {
        // 三个字段：C++ ReadTokens 会直接 exit，文件本身损坏，不做转换
        val content = "a 1 b\n"
        assertEquals(content, convert(content))
    }

    @Test
    fun conversion_isIdempotent() {
        val first = convert("hello 1\n<0xA1> 5\n")
        assertEquals(first, convert(first))
    }
}
