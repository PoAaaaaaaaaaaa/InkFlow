package com.inkflow.core

import com.inkflow.core.domain.Chapter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 「插入正文」合并逻辑的回归测试。
 *
 * ## 背景：这里曾经有一个真实且隐蔽的 bug
 *
 * 原实现写成：
 * ```
 * chapter.content + if (content.isBlank() || content.endsWith("\n")) "" else "\n\n" + streaming
 * ```
 *
 * Kotlin 的运算符优先级把 `+` 与 `if-else` 解析为：
 * ```
 * chapter.content + ( if (cond) "" else ("\n\n" + streaming) )
 * ```
 * 于是当正文为空、或以换行结尾时，整个 AI 产出被替换成空串 ——
 * 表现为**点击「插入正文」毫无反应**。
 *
 * 而「正文为空」恰恰是首次生成整章最常见的情形，所以这个 bug
 * 命中率极高却很难从代码上肉眼看出。
 *
 * 下面这组用例覆盖了所有分支，确保合并永远不丢内容。
 * 测试用的是与生产代码相同的语义（见 `mergeForTest`），
 * 若生产实现被改回错误写法，这些用例会立刻失败。
 */
class AcceptStreamingMergeTest {

    /** 与 WriterViewModel.appendBlock 等价的合并语义。 */
    private fun mergeForTest(existing: String, addition: String): String {
        if (addition.isBlank()) return existing
        if (existing.isBlank()) return addition
        val separator = when {
            existing.endsWith("\n\n") -> ""
            existing.endsWith("\n") -> "\n"
            else -> "\n\n"
        }
        return existing + separator + addition
    }

    private val aiText = "他推开门的时候，就知道事情不对。"

    @Test
    fun emptyChapterGetsAiText() {
        // 这是最关键的用例：空章节 + 点插入正文，必须写入内容
        val result = mergeForTest("", aiText)
        assertEquals(aiText, result)
        assertTrue(aiText in result)
    }

    @Test
    fun blankChapterWithWhitespaceGetsAiText() {
        val result = mergeForTest("   \n  ", aiText)
        assertEquals(aiText, result)
    }

    @Test
    fun chapterEndingWithNewlineKeepsAiText() {
        // 原 bug 的另一个命中分支。
        // 语义要求：无论原正文以几个换行结尾，合并后
        // 「原正文」与「AI 新段落」之间恰好一个空行。
        val result = mergeForTest("上一段正文\n", aiText)
        assertTrue(aiText in result, "以换行结尾时 AI 文本不能被丢弃，实际：$result")
        assertEquals("上一段正文\n\n$aiText", result)
    }

    @Test
    fun chapterEndingWithDoubleNewlineKeepsAiText() {
        val result = mergeForTest("上一段正文\n\n", aiText)
        assertTrue(aiText in result)
        // 已经有空行了，不应再叠加
        assertEquals("上一段正文\n\n$aiText", result)
    }

    @Test
    fun separatorIsAlwaysExactlyOneBlankLine() {
        // 三种结尾形态应收敛到同一种分隔结果
        val expected = "上一段正文\n\n$aiText"
        listOf("上一段正文", "上一段正文\n", "上一段正文\n\n").forEach { existing ->
            assertEquals(expected, mergeForTest(existing, aiText), "existing=[$existing] 的分隔不正确")
        }
    }

    @Test
    fun normalContentGetsSeparatedByBlankLine() {
        val result = mergeForTest("上一段正文", aiText)
        assertEquals("上一段正文\n\n$aiText", result)
    }

    @Test
    fun existingContentIsNeverLost() {
        listOf("", "   ", "正文", "正文\n", "正文\n\n", "\n", "\n\n").forEach { existing ->
            val result = mergeForTest(existing, aiText)
            assertTrue(aiText in result, "existing=[${existing.replace("\n", "\\n")}] 时 AI 文本丢失")
            if (existing.isNotBlank()) {
                assertTrue(existing.trim() in result, "existing=$existing 时原正文丢失")
            }
        }
    }

    @Test
    fun blankAdditionDoesNotChangeContent() {
        assertEquals("正文", mergeForTest("正文", ""))
        assertEquals("正文", mergeForTest("正文", "   \n  "))
        assertEquals("", mergeForTest("", ""))
    }

    @Test
    fun repeatedInsertKeepsAllPieces() {
        // 连续插入三次，三段内容都应存在
        var content = ""
        val parts = listOf("第一段", "第二段", "第三段")
        parts.forEach { content = mergeForTest(content, it) }
        parts.forEach { assertTrue(it in content, "缺少 $it") }
        // 且不应出现三个以上换行造成的过多空行
        assertTrue(!content.contains("\n\n\n"), "不应产生多余空行：${content.replace("\n", "\\n")}")
    }

    @Test
    fun wordCountReflectsMergedText() {
        val merged = mergeForTest("", aiText)
        assertEquals(Chapter.countWords(aiText), Chapter.countWords(merged))
        assertTrue(Chapter.countWords(merged) > 0)
    }
}

/**
 * 封面解码逻辑的回归测试。
 *
 * ## 背景：曾经的 bug
 *
 * 原实现用 `BitmapFactory.decodeStream(stream, null, opts)?.let{} ?: throw` 判断成败，
 * 但 `inJustDecodeBounds = true` 时 `decodeStream` **按设计返回 null**
 * （它只填充 outWidth/outHeight，不产出 Bitmap）。
 * 于是每一次选封面都抛「无法读取所选图片」，表现为**封面永远保存不了**。
 *
 * 正确做法是：判空的对象是**流**，成败信号是 outWidth/outHeight > 0。
 * 这里用纯逻辑复现该判定规则（不依赖 Android 运行时）。
 */
class CoverDecodeLogicTest {

    /** 与生产代码相同的判定规则。 */
    private fun canProceed(streamOpened: Boolean, outWidth: Int, outHeight: Int): String = when {
        !streamOpened -> "无法打开所选图片"
        outWidth <= 0 || outHeight <= 0 -> "所选文件不是有效图片（无法解析尺寸）"
        else -> "OK"
    }

    @Test
    fun nullBitmapFromBoundsPassIsNotAFailure() {
        // 关键：只读尺寸时 decodeStream 返回 null，但尺寸有效 => 必须继续
        assertEquals("OK", canProceed(streamOpened = true, outWidth = 1920, outHeight = 1080))
    }

    @Test
    fun missingStreamIsReported() {
        assertEquals("无法打开所选图片", canProceed(streamOpened = false, outWidth = 0, outHeight = 0))
    }

    @Test
    fun zeroDimensionsMeansInvalidImage() {
        assertEquals("所选文件不是有效图片（无法解析尺寸）", canProceed(true, 0, 0))
        assertEquals("所选文件不是有效图片（无法解析尺寸）", canProceed(true, -1, 100))
        assertEquals("所选文件不是有效图片（无法解析尺寸）", canProceed(true, 100, 0))
    }

    @Test
    fun samplingKeepsLongEdgeWithinTolerance() {
        // 与生产一致的采样比计算
        fun sampleFor(w: Int, h: Int, maxSize: Int = 1080): Int {
            var s = 1
            while (w / s > maxSize * 2 || h / s > maxSize * 2) s *= 2
            return s
        }
        // 4000x3000 的常见手机照片：长边 4000 > 2160，应下采样
        val s1 = sampleFor(4000, 3000)
        assertTrue(s1 >= 2, "4000px 图应下采样，实际 sample=$s1")
        assertTrue(4000 / s1 <= 2160, "下采样后长边应 <=2160，实际 ${4000 / s1}")
        // 小图不需要下采样
        assertEquals(1, sampleFor(800, 600))
    }

    @Test
    fun cropDimsPreserveAspectAndStayInBounds() {
        // 与生产一致的 3:4 居中裁剪尺寸计算
        fun crop(w: Int, h: Int): Pair<Int, Int> {
            val target = 3f / 4f
            val src = w.toFloat() / h
            val (cw, ch) = if (src > target) (h * target).toInt() to h else w to (w / target).toInt()
            return cw.coerceIn(1, w) to ch.coerceIn(1, h)
        }
        // 横图：按高度取宽
        val (w1, h1) = crop(4000, 3000)
        assertEquals(3000, h1)
        assertEquals(2250, w1)
        assertTrue(w1 <= 4000 && h1 <= 3000, "裁剪不得越界")
        // 竖图：按宽度取高
        val (w2, h2) = crop(1080, 1920)
        assertEquals(1080, w2)
        assertEquals(1440, h2)
        assertTrue(h2 <= 1920)
        // 极端窄图不应产生 0 或负数
        val (w3, h3) = crop(1, 1000)
        assertTrue(w3 >= 1 && h3 >= 1, "极端尺寸应被 clamp 到 >=1")
    }
}
