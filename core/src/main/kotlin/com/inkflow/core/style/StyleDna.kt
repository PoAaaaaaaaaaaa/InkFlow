package com.inkflow.core.style

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 文风 DNA：从作者既有文本中蒸馏出的可量化写作指纹。
 *
 * 让 AI 续写/改稿保持与原作者一致的「声音」，而不是输出千篇一律的 AI 腔。
 * 全部指标都可计算、可复现、可增量更新，因此适配 0 依赖的移动端。
 */
@Serializable
data class StyleDna(
    val sampleWords: Int = 0,

    // ---- 句法层 ----
    /** 平均句长（字） */
    val avgSentenceLength: Double = 0.0,
    /** 句长标准差：衡量节奏起伏，越小越平铺直叙 */
    val sentenceLengthStd: Double = 0.0,
    /** 短句占比（<=12 字），网文爽感的重要来源 */
    val shortSentenceRatio: Double = 0.0,
    /** 长句占比（>=40 字） */
    val longSentenceRatio: Double = 0.0,

    // ---- 段落节奏 ----
    /** 平均段长（字） */
    val avgParagraphLength: Double = 0.0,
    /** 对话行占比：由引号行/总行数估算 */
    val dialogueRatio: Double = 0.0,
    /** 平均每段句数 */
    val sentencesPerParagraph: Double = 0.0,

    // ---- 修辞指纹 ----
    /** 比喻密度：每千字比喻词出现次数 */
    val metaphorPer1k: Double = 0.0,
    /** 感叹/疑问句占比：情绪外放程度 */
    val exclamationRatio: Double = 0.0,
    /** 破折号与省略号密度：留白与停顿习惯 */
    val pauseMarkPer1k: Double = 0.0,

    // ---- 用词层 ----
    /** 高频实词（去掉停用词后的 top 词），作者的「口头禅」 */
    val signatureWords: List<String> = emptyList(),
    /** 过度使用词：出现频率异常高、需要提醒克制的词（禁忌清单候选） */
    val overusedWords: List<String> = emptyList(),
    /** 人称：第一/第三人称判断 */
    val person: String = "第三人称",
) {
    val isUsable: Boolean get() = sampleWords >= 300

    /** 生成可直接注入 Prompt 的文风约束块。 */
    fun toPromptBlock(): String {
        if (!isUsable) return ""
        return buildString {
            appendLine("【文风 DNA · 必须严格模仿】")
            appendLine("- 叙述人称：$person")
            appendLine(
                "- 句长：平均 ${avgSentenceLength.one()} 字，短句(<=12字)占 ${(shortSentenceRatio * 100).one()}%，" +
                    "长句(>=40字)占 ${(longSentenceRatio * 100).one()}%"
            )
            appendLine("- 段落：平均每段 ${avgParagraphLength.one()} 字 / ${sentencesPerParagraph.one()} 句，对话行占 ${(dialogueRatio * 100).one()}%")
            appendLine("- 修辞：每千字比喻 ${metaphorPer1k.one()} 次，情绪句占 ${(exclamationRatio * 100).one()}%")
            if (sentenceLengthStd > 0) {
                appendLine("- 节奏：句长起伏标准差 ${sentenceLengthStd.one()}，${if (sentenceLengthStd < 8) "整体平稳，避免剧烈跳变" else "长短句交错，允许强节奏对比"}")
            }
            if (signatureWords.isNotEmpty()) {
                appendLine("- 作者惯用词（自然融入，勿堆砌）：${signatureWords.take(12).joinToString("、")}")
            }
            if (overusedWords.isNotEmpty()) {
                appendLine("- 禁忌清单（严禁连续使用/滥用）：${overusedWords.take(12).joinToString("、")}")
            }
        }
    }

    private fun Double.one() = String.format("%.1f", this)
}

/**
 * 文风分析器。纯函数式，无副作用，可对任意长度文本运行。
 */
object StyleAnalyzer {

    private val METAPHOR_MARKERS = listOf(
        "像", "如同", "仿佛", "宛如", "好似", "犹如", "似的", "一般无二", "恍若",
    )
    private val STOP_WORDS = setOf(
        "的", "了", "是", "在", "我", "他", "她", "它", "们", "你", "这", "那", "有", "不", "也",
        "就", "都", "而", "与", "和", "被", "把", "对", "为", "从", "到", "一个", "什么", "自己",
        "已经", "可以", "没有", "这个", "那个", "起来", "出来", "知道", "时候", "一样", "还是",
        "然后", "但是", "因为", "所以", "如果", "只是", "然而", "于是", "并且", "似乎", "顿时",
    )

    fun analyze(text: String): StyleDna {
        val clean = text.trim()
        if (clean.length < 50) return StyleDna()

        val paragraphs = clean.split(Regex("\\n+")).map { it.trim() }.filter { it.isNotEmpty() }
        val sentences = splitSentences(clean)
        if (sentences.isEmpty() || paragraphs.isEmpty()) return StyleDna()

        val lengths = sentences.map { countChars(it) }.filter { it > 0 }
        val avg = lengths.average()
        val std = if (lengths.size > 1) {
            sqrt(lengths.sumOf { (it - avg) * (it - avg) } / (lengths.size - 1))
        } else 0.0

        val shortRatio = lengths.count { it <= 12 }.toDouble() / lengths.size
        val longRatio = lengths.count { it >= 40 }.toDouble() / lengths.size

        val paraLens = paragraphs.map { countChars(it) }
        val dialogueLines = paragraphs.count { p ->
            (p.startsWith("「") || p.startsWith("\"") || p.startsWith("“") ||
                p.contains("：「") || p.contains("：“")) && p.length < 400
        }

        val charCount = countChars(clean).coerceAtLeast(1)
        val per1k = { n: Int -> n * 1000.0 / charCount }

        val metaphorCount = METAPHOR_MARKERS.sumOf { m -> countOccurrences(clean, m) }
        val pauseCount = countOccurrences(clean, "——") + countOccurrences(clean, "……") +
            countOccurrences(clean, "…")
        val emoCount = sentences.count { it.trimEnd().endsWith("！") || it.trimEnd().endsWith("？") || it.trimEnd().endsWith("!") || it.trimEnd().endsWith("?") }

        val words = extractWords(clean)
        val freq = words.groupingBy { it }.eachCount()
        val signature = freq.entries
            .filter { it.value >= 3 && it.key.length >= 2 }
            .sortedByDescending { it.value * it.key.length }
            .take(20)
            .map { it.key }

        // 「过度使用」判定：同一词承担了超过 1.2% 的实词量，属于明显的用词单一
        val totalWords = words.size.coerceAtLeast(1)
        val overused = freq.entries
            .filter { it.value >= 5 && it.value.toDouble() / totalWords > 0.012 }
            .sortedByDescending { it.value }
            .take(15)
            .map { it.key }

        return StyleDna(
            sampleWords = charCount,
            avgSentenceLength = avg,
            sentenceLengthStd = std,
            shortSentenceRatio = shortRatio,
            longSentenceRatio = longRatio,
            avgParagraphLength = paraLens.average(),
            dialogueRatio = dialogueLines.toDouble() / paragraphs.size,
            sentencesPerParagraph = sentences.size.toDouble() / paragraphs.size,
            metaphorPer1k = per1k(metaphorCount),
            exclamationRatio = emoCount.toDouble() / sentences.size,
            pauseMarkPer1k = per1k(pauseCount),
            signatureWords = signature,
            overusedWords = overused,
            person = detectPerson(clean),
        )
    }

    /**
     * 文风吻合度打分 0..100。用于「续写是否走味」的自动预警。
     * 权重偏向节奏与句长，因为读者对「读起来顺不顺」最敏感。
     */
    fun matchScore(text: String, dna: StyleDna): Int {
        if (!dna.isUsable || text.length < 100) return -1
        val cur = analyze(text)
        var score = 100.0

        score -= diff(cur.avgSentenceLength, dna.avgSentenceLength, 0.35) * 40
        score -= diff(cur.shortSentenceRatio, dna.shortSentenceRatio, 0.25) * 30
        score -= diff(cur.avgParagraphLength, dna.avgParagraphLength, 0.40) * 25
        score -= diff(cur.dialogueRatio, dna.dialogueRatio, 0.25) * 20
        score -= diff(cur.metaphorPer1k, dna.metaphorPer1k, 3.0) * 15
        score -= diff(cur.sentenceLengthStd, dna.sentenceLengthStd, 8.0) * 10

        // 过度使用的签名词在本段重复出现，视为走味
        val hit = dna.overusedWords.count { text.contains(it) }
        score -= hit * 2.0

        return score.coerceIn(0.0, 100.0).toInt()
    }

    /** 相对偏差，按容差归一化后裁剪到 0..1 */
    private fun diff(a: Double, b: Double, tolerance: Double): Double {
        if (tolerance <= 0) return 0.0
        val d = abs(a - b) / tolerance
        return d.coerceIn(0.0, 1.0)
    }

    fun splitSentences(text: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (ch in text) {
            if (ch == '\n') {
                if (sb.isNotBlank()) out.add(sb.toString().trim())
                sb.clear()
                continue
            }
            sb.append(ch)
            // 只在真正的句末标点断句；省略号「……」与分号「；」表示句中停顿，
            // 若在此切断会把一个句子统计成两个，导致平均句长与节奏指标失真。
            if (ch == '。' || ch == '！' || ch == '？' || ch == '!' || ch == '?') {
                out.add(sb.toString().trim())
                sb.clear()
            }
        }
        if (sb.isNotBlank()) out.add(sb.toString().trim())
        return out.filter { it.isNotEmpty() }
    }

    private fun countChars(s: String): Int = s.count { !it.isWhitespace() }

    private fun countOccurrences(text: String, sub: String): Int {
        if (sub.isEmpty()) return 0
        var count = 0
        var idx = text.indexOf(sub)
        while (idx >= 0) {
            count++
            idx = text.indexOf(sub, idx + sub.length)
        }
        return count
    }

    private fun extractWords(text: String): List<String> {
        val out = ArrayList<String>()
        // 中文 2-gram 作为「词」的近似，英文按词
        val cjkRun = StringBuilder()
        val latin = StringBuilder()

        fun flushCjk() {
            val s = cjkRun.toString()
            if (s.length == 2) out.add(s)
            else if (s.length > 2) {
                for (i in 0 until s.length - 1) out.add(s.substring(i, i + 2))
            }
            cjkRun.clear()
        }

        fun flushLatin() {
            val s = latin.toString().lowercase()
            if (s.length >= 3 && s !in STOP_WORDS) out.add(s)
            latin.clear()
        }

        for (ch in text) {
            when {
                ch.code in 0x4E00..0x9FFF -> {
                    flushLatin()
                    cjkRun.append(ch)
                }
                ch.isLetterOrDigit() -> {
                    flushCjk()
                    latin.append(ch)
                }
                else -> {
                    flushCjk(); flushLatin()
                }
            }
        }
        flushCjk(); flushLatin()
        return out.filter { it !in STOP_WORDS }
    }

    private fun detectPerson(text: String): String {
        val first = countOccurrences(text, "我") + countOccurrences(text, "我们")
        val third = countOccurrences(text, "他") + countOccurrences(text, "她") + countOccurrences(text, "他们")
        return when {
            first > third * 1.5 -> "第一人称"
            third > first * 1.5 -> "第三人称"
            else -> "第三人称（多视角）"
        }
    }
}
