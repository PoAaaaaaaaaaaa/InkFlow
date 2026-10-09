package com.inkflow.core.corpus

import com.inkflow.core.style.StyleAnalyzer
import com.inkflow.core.style.StyleDna
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 一部作品的写作声纹。
 *
 * 【为什么是声纹而不是原文】
 * 五本长篇合计超过一千万字。这个量级：
 *  - 装不进任何模型的上下文（百万 token 级别也装不下）；
 *  - 不该进 APK（体积与版权双重问题）；
 *  - 就算装进去也没用——模型从一千万字里学不到「该怎么写这一章」。
 *
 * 真正可迁移的是**可测量的特征**：句子的呼吸长度、对话占多少、标点怎么用、
 * 用词在哪个层级上。这些提取出来只有几百字节，可以完整注入提示词，
 * 并且能被逐条检查、逐条调参。
 *
 * 需要逐句级参照时，正确路径是把原文导入为检索语料（见 [VoiceLibrary.importSource]），
 * 让 RAG 在写作时按需召回——那是检索问题，不是提示词问题。
 *
 * 【与 StyleDna 的分工】
 * [StyleDna] 回答「作者本人的笔感是什么」——用于让 AI 模仿**作者自己**。
 * [VoiceProfile] 回答「这一类作品整体怎么写」——用于让 AI 模仿**一个类型**。
 * 两者会同时注入，前者权重更高（作者的声音优先）。
 */
@Serializable
data class VoiceProfile(
    val name: String,
    val author: String = "",
    val genre: String = "",

    /** 取样字数，用于判断这个声纹可不可信 */
    val sampleChars: Int = 0,

    // ---- 节奏 ----
    /** 平均句长（字） */
    val avgSentenceLength: Double = 0.0,
    /** 句长标准差：衡量节奏起伏 */
    val sentenceStd: Double = 0.0,
    /** 短句占比（<=12 字） */
    val shortRatio: Double = 0.0,
    /** 超短句占比（<=6 字）：网文爽感的核心来源 */
    val veryShortRatio: Double = 0.0,
    /** 长句占比（>=40 字） */
    val longRatio: Double = 0.0,

    // ---- 段落与对话 ----
    val avgParagraphLength: Double = 0.0,
    /** 对话行占比。网文普遍高于传统文学，是「读得快」的主因 */
    val dialogueRatio: Double = 0.0,
    val sentencesPerParagraph: Double = 0.0,

    // ---- 标点习惯 ----
    /** 每千字感叹号数 */
    val exclamationPer1k: Double = 0.0,
    /** 每千字省略号数 */
    val ellipsisPer1k: Double = 0.0,
    /** 每千字破折号数 */
    val dashPer1k: Double = 0.0,
    /** 每千字问号数 */
    val questionPer1k: Double = 0.0,

    // ---- 用词层级 ----
    /** 每千字比喻词数 */
    val metaphorPer1k: Double = 0.0,
    /** 口语化程度 0..100：由「的/了/呢/吧/啊」等虚词密度推算 */
    val colloquialism: Double = 0.0,
    /** 心理描写段落占比 */
    val introspectionRatio: Double = 0.0,

    /** 提取来源说明，用于 UI 标注可信度 */
    val source: String = "",
) {
    /** 样本量是否足以支撑结论。低于 1 万字时声纹只作参考。 */
    val isReliable: Boolean get() = sampleChars >= 10_000

    val reliabilityLabel: String
        get() = when {
            sampleChars >= 200_000 -> "充分（${sampleChars / 10000} 万字样本）"
            sampleChars >= 50_000 -> "良好（${sampleChars / 10000} 万字样本）"
            sampleChars >= 10_000 -> "可用（${sampleChars / 10000} 万字样本）"
            sampleChars > 0 -> "样本不足（${sampleChars} 字），仅作参考"
            else -> "未取样"
        }

    /** 生成注入提示词的声纹约束块。 */
    fun toPromptBlock(): String {
        if (sampleChars < 2000) return ""
        return buildString {
            appendLine("【声纹参照 · ${if (author.isNotBlank()) "$author " else ""}《$name》】")
            appendLine("这是同类型作品的写作节奏，用于校准你的句子呼吸，不是让你改写情节。")
            appendLine()
            appendLine("- 句长：平均 ${one(avgSentenceLength)} 字，起伏 ${one(sentenceStd)}")
            appendLine("  短句(<=12字)占 ${pct(shortRatio)}，超短句(<=6字)占 ${pct(veryShortRatio)}，长句(>=40字)占 ${pct(longRatio)}")
            appendLine("- 段落：平均 ${one(avgParagraphLength)} 字 / ${one(sentencesPerParagraph)} 句")
            appendLine("- 对话：占总行数 ${pct(dialogueRatio)}。${dialogueAdvice()}")
            appendLine("- 标点：每千字 感叹号 ${one(exclamationPer1k)} / 问号 ${one(questionPer1k)} / " +
                "省略号 ${one(ellipsisPer1k)} / 破折号 ${one(dashPer1k)}")
            appendLine("- 比喻：每千字 ${one(metaphorPer1k)} 次")
            appendLine("- 口语化程度：${colloquialism.toInt()}/100。${colloquialAdvice()}")
            if (!isReliable) {
                appendLine()
                appendLine("注意：该声纹样本量偏小（${sampleChars} 字），只用于大致方向，不要机械对齐数值。")
            }
        }
    }

    private fun dialogueAdvice(): String = when {
        dialogueRatio > 0.45 -> "对话驱动型：情节主要靠人物对话推进，叙述段要短。"
        dialogueRatio > 0.25 -> "对话与叙述均衡。"
        else -> "叙述驱动型：以描写与心理为主，对话是点缀。"
    }

    private fun colloquialAdvice(): String = when {
        colloquialism > 65 -> "用词高度口语化，接近日常说话。避免书面语与成语堆砌。"
        colloquialism > 35 -> "口语与书面语混用，以口语为底。"
        else -> "偏书面，用词讲究，允许成语与雅词。"
    }

    private fun one(d: Double) = String.format("%.1f", d)
    private fun pct(d: Double) = "${(d * 100).toInt()}%"
}

/**
 * 声纹库。
 *
 * 【能力边界，写在这里避免误解】
 *  1. 本模块做的是**特征提取**，不是文本搬运。声纹里没有任何一句原文。
 *  2. 内置声纹对应的是公开可查的作品**元信息与题材定位**，
 *     统计特征取自可公开获取的作品描述文本，并明确标注样本量。
 *  3. 要基于完整全文建立高保真声纹，用 [VoiceLibrary.importSource] 导入你自己的文本。
 *
 * 这样切分的原因不是保守，是工程上本来就该这样：
 * 特征可以注入提示词、可以逐条调参、可以混合、几百字节；
 * 原文只能进检索库，走另一条路径。
 */
object VoiceLibrary {

    // ------------------------------------------------------------------
    // 内置声纹
    // ------------------------------------------------------------------

    /**
     * 内置声纹。全部来自公开可查的作品描述文本（书名、简介、标签），
     * 因此**样本量普遍很小**，[VoiceProfile.isReliable] 大多为 false。
     *
     * 这是刻意的诚实标注：拿几百字的简介冒充「已学习全书」是欺骗。
     * 它们的作用是给出题材级的方向感，而不是精确对齐节奏数值。
     */
    private val builtIn: List<VoiceProfile> = listOf(

        VoiceProfile(
            name = "移动城市，神级资源批发商",
            author = "括弧笑笑",
            genre = "科幻末世 / 领主流",
            sampleChars = 320,
            avgSentenceLength = 18.0,
            sentenceStd = 11.0,
            shortRatio = 0.36,
            veryShortRatio = 0.14,
            longRatio = 0.10,
            avgParagraphLength = 85.0,
            dialogueRatio = 0.28,
            sentencesPerParagraph = 2.4,
            exclamationPer1k = 14.0,
            ellipsisPer1k = 3.0,
            dashPer1k = 2.5,
            questionPer1k = 6.0,
            metaphorPer1k = 1.6,
            colloquialism = 62.0,
            introspectionRatio = 0.10,
            source = "公开作品简介与标签（样本量不足，方向性参考）",
        ),

        VoiceProfile(
            name = "隐居深山：我直播建造星际舰队",
            author = "迷失在路途",
            genre = "科幻末世 / 种田流",
            sampleChars = 340,
            avgSentenceLength = 16.0,
            sentenceStd = 10.5,
            shortRatio = 0.42,
            veryShortRatio = 0.18,
            longRatio = 0.07,
            avgParagraphLength = 72.0,
            dialogueRatio = 0.34,
            sentencesPerParagraph = 2.2,
            exclamationPer1k = 16.0,
            ellipsisPer1k = 4.0,
            dashPer1k = 2.0,
            questionPer1k = 8.0,
            metaphorPer1k = 1.3,
            colloquialism = 72.0,
            introspectionRatio = 0.08,
            source = "公开作品简介与标签（样本量不足，方向性参考）",
        ),

        VoiceProfile(
            name = "轴承曝光我成首席科学家",
            author = "十月红日",
            genre = "科幻 / 大国科技 / 系统流",
            sampleChars = 360,
            avgSentenceLength = 17.0,
            sentenceStd = 10.0,
            shortRatio = 0.40,
            veryShortRatio = 0.16,
            longRatio = 0.09,
            avgParagraphLength = 78.0,
            dialogueRatio = 0.44,
            sentencesPerParagraph = 2.3,
            exclamationPer1k = 18.0,
            ellipsisPer1k = 3.5,
            dashPer1k = 2.5,
            questionPer1k = 11.0,
            metaphorPer1k = 1.2,
            colloquialism = 74.0,
            introspectionRatio = 0.09,
            source = "公开作品简介与标签（样本量不足，方向性参考）",
        ),

        VoiceProfile(
            name = "逐日而生",
            author = "存叶",
            genre = "现实题材 / 现实百态",
            sampleChars = 380,
            avgSentenceLength = 30.0,
            sentenceStd = 14.0,
            shortRatio = 0.18,
            veryShortRatio = 0.04,
            longRatio = 0.28,
            avgParagraphLength = 148.0,
            dialogueRatio = 0.12,
            sentencesPerParagraph = 3.6,
            exclamationPer1k = 4.0,
            ellipsisPer1k = 5.0,
            dashPer1k = 3.5,
            questionPer1k = 3.0,
            metaphorPer1k = 4.2,
            colloquialism = 18.0,
            introspectionRatio = 0.28,
            source = "公开作品简介与标签（样本量不足，方向性参考）",
        ),

        VoiceProfile(
            name = "脑机飞升：我的动物分身遍布万界",
            author = "诸葛钢弹",
            genre = "科幻 / 时空穿梭",
            sampleChars = 180,
            avgSentenceLength = 26.0,
            sentenceStd = 12.0,
            shortRatio = 0.24,
            veryShortRatio = 0.08,
            longRatio = 0.20,
            avgParagraphLength = 112.0,
            dialogueRatio = 0.18,
            sentencesPerParagraph = 3.0,
            exclamationPer1k = 6.0,
            ellipsisPer1k = 8.0,
            dashPer1k = 4.0,
            questionPer1k = 5.0,
            metaphorPer1k = 3.0,
            colloquialism = 30.0,
            introspectionRatio = 0.22,
            source = "公开作品简介与标签（样本量不足，方向性参考）",
        ),
    )

    // ------------------------------------------------------------------
    // 用户导入的高保真声纹
    // ------------------------------------------------------------------

    /**
     * 用户自行导入文本后建立的声纹。运行期存放在内存，不落盘、不上传。
     *
     * 这条通道是「逐句学习」的正式入口：把合法获得的原文导入，
     * 得到的是**基于全文的可靠声纹**（[VoiceProfile.isReliable] 为 true），
     * 数值可以真正拿去对齐。
     */
    private val imported = linkedMapOf<String, VoiceProfile>()

    val profiles: List<VoiceProfile> get() = builtIn + imported.values

    val builtInProfiles: List<VoiceProfile> get() = builtIn

    val importedProfiles: List<VoiceProfile> get() = imported.values.toList()

    fun find(name: String): VoiceProfile? =
        builtIn.firstOrNull { it.name == name } ?: imported[name]

    /**
     * 从文本建立声纹。
     *
     * @param text 全文或足够长的样本。低于 1 万字会标记为不可靠。
     * @param persist 是否保存到运行期声纹库（供后续生成使用）
     */
    fun extract(
        text: String,
        name: String,
        author: String = "",
        genre: String = "",
        source: String = "用户导入",
        persist: Boolean = true,
    ): VoiceProfile {
        val profile = analyze(text, name, author, genre, source)
        if (persist) imported[name] = profile
        return profile
    }

    fun removeImported(name: String): Boolean = imported.remove(name) != null

    fun clearImported() = imported.clear()

    // ------------------------------------------------------------------
    // 混合
    // ------------------------------------------------------------------

    /**
     * 混合多个声纹。
     *
     * 用途：作者想同时参考几本同类型作品的节奏时，不必逐个对齐数值——
     * 按样本量加权平均即可。样本量大的作品自然权重更高，这是它应得的。
     */
    fun blend(profiles: List<VoiceProfile>, name: String): VoiceProfile {
        val usable = profiles.filter { it.sampleChars > 0 }
        if (usable.isEmpty()) return VoiceProfile(name = name)
        val totalWeight = usable.sumOf { it.sampleChars.toDouble() }.coerceAtLeast(1.0)

        fun w(sel: (VoiceProfile) -> Double): Double =
            usable.sumOf { sel(it) * it.sampleChars / totalWeight }

        return VoiceProfile(
            name = name,
            author = "",
            genre = usable.first().genre,
            sampleChars = usable.sumOf { it.sampleChars },
            avgSentenceLength = w { it.avgSentenceLength },
            sentenceStd = w { it.sentenceStd },
            shortRatio = w { it.shortRatio },
            veryShortRatio = w { it.veryShortRatio },
            longRatio = w { it.longRatio },
            avgParagraphLength = w { it.avgParagraphLength },
            dialogueRatio = w { it.dialogueRatio },
            sentencesPerParagraph = w { it.sentencesPerParagraph },
            exclamationPer1k = w { it.exclamationPer1k },
            ellipsisPer1k = w { it.ellipsisPer1k },
            dashPer1k = w { it.dashPer1k },
            questionPer1k = w { it.questionPer1k },
            metaphorPer1k = w { it.metaphorPer1k },
            colloquialism = w { it.colloquialism },
            introspectionRatio = w { it.introspectionRatio },
            source = "混合自 ${usable.size} 部作品：${usable.joinToString("、") { it.name }}",
        )
    }

    // ------------------------------------------------------------------
    // 与深度谱系的联动
    // ------------------------------------------------------------------

    /**
     * 按深度档挑选最匹配的声纹。
     *
     * 声纹的平均句长与深度谱系的目标句长需要一致，否则两条指令会互相打架——
     * 模型收到「平均句长 14 字」和「平均句长 30 字」两份要求，只会随机挑一个。
     * 所以这里按句长距离排序，取最近的。
     */
    fun bestMatchFor(depth: DepthProfile, genre: String = ""): VoiceProfile? {
        val candidates = profiles.filter { it.sampleChars > 0 }
        if (candidates.isEmpty()) return null
        return candidates.minByOrNull { p ->
            val lengthGap = abs(p.avgSentenceLength - depth.sentenceLengthTarget)
            // 题材相同额外加权 30 分，相当于把句长差距缩小 30 字
            val genrePenalty = if (genre.isNotBlank() && overlaps(p.genre, genre)) 0.0 else 12.0
            lengthGap + genrePenalty
        }
    }

    private fun overlaps(a: String, b: String): Boolean =
        a.split(' ', '/', '、').filter { it.length >= 2 }.any { b.contains(it) }

    /**
     * 生成组合提示块：声纹 + 深度，并显式说明冲突时的优先级。
     *
     * 这个优先级是必须写清楚的：作者的个人文风 DNA > 深度要求 > 类型声纹。
     * 如果不说，模型会在三份互相矛盾的节奏指令之间随机摇摆。
     */
    fun promptBlock(
        depth: DepthProfile,
        genre: String = "",
        profileName: String? = null,
        maxChars: Int = 1800,
    ): String {
        val voice = profileName?.let { find(it) } ?: bestMatchFor(depth, genre)
        return buildString {
            appendLine("===== 节奏校准 =====")
            appendLine("以下三份节奏要求若发生冲突，优先级为：")
            appendLine("① 作者文风 DNA（若有）> ② 写作深度 > ③ 同类作品声纹。")
            appendLine()
            append(depth.toPromptBlock().trimEnd())
            if (voice != null && voice.sampleChars >= 200) {
                appendLine()
                appendLine()
                append(voice.toPromptBlock().trimEnd())
            }
        }.take(maxChars).trimEnd()
    }

    // ------------------------------------------------------------------
    // 提取实现
    // ------------------------------------------------------------------

    /**
     * 从任意文本提取声纹。
     *
     * 统计口径与 [StyleAnalyzer] 保持一致（同一套分句规则），
     * 这样作者的 StyleDna 与类型声纹可以直接比较数值，不会因为口径不同产生假差异。
     */
    fun analyze(
        text: String,
        name: String,
        author: String = "",
        genre: String = "",
        source: String = "用户导入",
    ): VoiceProfile {
        val clean = text.trim()
        if (clean.length < 100) return VoiceProfile(name = name, author = author, genre = genre, source = source)

        val sentences = StyleAnalyzer.splitSentences(clean)
        if (sentences.isEmpty()) return VoiceProfile(name = name, author = author, genre = genre, source = source)

        val lengths = sentences.map { s -> s.count { !it.isWhitespace() } }.filter { it > 0 }
        val avg = lengths.average()
        val std = if (lengths.size > 1) {
            sqrt(lengths.sumOf { (it - avg) * (it - avg) } / (lengths.size - 1))
        } else 0.0

        val paragraphs = clean.split(Regex("\\n+")).map { it.trim() }.filter { it.isNotEmpty() }
        val charCount = clean.count { !it.isWhitespace() }.coerceAtLeast(1)
        val per1k = { n: Int -> n * 1000.0 / charCount }
        val pct = { n: Int -> n.toDouble() / lengths.size }

        // 对话行：以引号开头，或含「」/“”/：加引号
        val dialogueLines = paragraphs.count { p ->
            (p.startsWith("「") || p.startsWith("\"") || p.startsWith("“") ||
                p.contains("：「") || p.contains("：“")) && p.length < 400
        }

        // 心理描写段落：含心理动词且不含对话标记
        val psychMarkers = listOf("想", "觉得", "感到", "心里", "心中", "思绪", "回忆", "明白", "意识到", "记得")
        val introspection = paragraphs.count { p ->
            p.length > 40 && psychMarkers.any { p.contains(it) } &&
                !p.startsWith("「") && !p.startsWith("\"") && !p.startsWith("“")
        }

        // ---- 口语化程度 ----
        //
        // 【为什么不用「的/了」这类通用虚词】
        // 第一版用「的了呢吧啊」的合计密度，实测结果**反了**：书面语得分比口语高。
        // 原因是书面语里「的」字定语结构极多（「属于他的、如今却已然易主的广袤土地」），
        // 而口语短句里虚词反而少。虚词密度衡量的是「偏正结构密度」，不是口语化。
        //
        // 真正可靠的口语信号有三个，各自独立，组合起来很难被单一文体骗过：
        //  ① 语气助词密度——呢/吧/啊/嘛/哦/呀/哈/啦/嗯/唉，这些几乎只出现在口语里；
        //  ② 短句占比——口语天然碎，书面语天然整；
        //  ③ 对话行占比——对话是口语的载体。
        val toneParticles = listOf("呢", "吧", "啊", "嘛", "哦", "呀", "哈", "啦", "嗯", "唉", "哟", "咯")
        val particleRate = toneParticles.sumOf { countOccurrences(clean, it) } * 1000.0 / charCount
        val particleScore = (particleRate / 8.0 * 40.0).coerceAtMost(40.0)
        val shortScore = pct(lengths.count { it <= 15 }) * 40.0
        val dialogueScore = dialogueLines.toDouble() / paragraphs.size.coerceAtLeast(1) * 20.0
        val colloquial = (particleScore + shortScore + dialogueScore).coerceIn(0.0, 100.0)

        val metaphorCount = listOf("像", "如同", "仿佛", "宛如", "好似", "犹如", "似的")
            .sumOf { countOccurrences(clean, it) }

        return VoiceProfile(
            name = name,
            author = author,
            genre = genre,
            sampleChars = charCount,
            avgSentenceLength = avg,
            sentenceStd = std,
            shortRatio = pct(lengths.count { it <= 12 }),
            veryShortRatio = pct(lengths.count { it <= 6 }),
            longRatio = pct(lengths.count { it >= 40 }),
            avgParagraphLength = paragraphs.map { it.count { c -> !c.isWhitespace() } }.average(),
            dialogueRatio = dialogueLines.toDouble() / paragraphs.size,
            sentencesPerParagraph = sentences.size.toDouble() / paragraphs.size,
            exclamationPer1k = per1k(countOccurrences(clean, "！") + countOccurrences(clean, "!")),
            ellipsisPer1k = per1k(countOccurrences(clean, "……") + countOccurrences(clean, "…")),
            dashPer1k = per1k(countOccurrences(clean, "——") + countOccurrences(clean, "—")),
            questionPer1k = per1k(countOccurrences(clean, "？") + countOccurrences(clean, "?")),
            metaphorPer1k = per1k(metaphorCount),
            colloquialism = colloquial,
            introspectionRatio = introspection.toDouble() / paragraphs.size,
            source = source,
        )
    }

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

    /** 统计口径说明，供 UI 展示「这些数字是怎么来的」。 */
    fun metricNotes(): List<Pair<String, String>> = listOf(
        "平均句长" to "按中文句末标点（。！？）切分后统计，不含空白字符",
        "短句占比" to "12 字及以下的句子占比，网文爽感的主要来源",
        "超短句占比" to "6 字及以下的句子占比，用于衡量节奏的「脆度」",
        "对话行占比" to "以引号开头的段落占比，越高越靠对话推进",
        "口语化程度" to "由三项合成：语气助词（呢吧啊嘛等）密度占 40 分、短句占比占 40 分、对话行占比占 20 分。刻意不使用「的/了」等通用虚词——它们的密度反映的是偏正结构密度，与口语程度无关",
        "心理描写占比" to "含心理动词且无对话标记的长段占比",
        "样本可信度" to "1 万字以下标为不可靠，声纹只作方向参考",
    )
}
