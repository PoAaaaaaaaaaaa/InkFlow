package com.inkflow.core.quality

import com.inkflow.core.domain.Chapter
import com.inkflow.core.domain.Character
import com.inkflow.core.domain.Foreshadow
import com.inkflow.core.corpus.CorpusEngine
import com.inkflow.core.domain.ForeshadowStatus
import com.inkflow.core.style.StyleAnalyzer
import com.inkflow.core.style.StyleDna

/**
 * 质量评估层：Coherence（连贯性）+ Fluency（流畅度）双评估器，
 * 外加伏笔回收台账检查。
 *
 * 全部为**确定性规则**，不依赖 AI 推理：
 *  - 好处 1：离线可用、零延迟、零成本，作者每敲一段都能实时看到；
 *  - 好处 2：可测试、可复现，不会出现「同一章两次评分不同」的困惑；
 *  - 好处 3：可以在质量门禁里作为硬性卡点，而不是靠模型自说自话。
 * AI 审阅 Agent 负责规则查不到的语义级问题，两者互补。
 */
object QualityEvaluator {

    fun evaluate(
        chapter: Chapter,
        characters: List<Character> = emptyList(),
        foreshadows: List<Foreshadow> = emptyList(),
        styleDna: StyleDna? = null,
        recentTexts: List<String> = emptyList(),
        /** 作品题材，用于 AI 腔维度挑选贴合的具体化词条 */
        genre: String = "",
    ): QualityReport {
        val text = chapter.content
        val coherence = coherence(text, characters)
        val fluency = fluency(text)
        val style = styleMatch(text, styleDna)
        val foreshadow = foreshadowCheck(foreshadows, chapter.order)
        val continuity = continuity(text, recentTexts)
        val aiFlavor = aiFlavor(text, genre)

        val issues = coherence.issues + fluency.issues + style.issues +
            foreshadow.issues + continuity.issues + aiFlavor.issues
        val score = weightedScore(coherence, fluency, style, continuity, aiFlavor)

        return QualityReport(
            chapterId = chapter.id,
            chapterOrder = chapter.order,
            totalScore = score,
            coherence = coherence,
            fluency = fluency,
            style = style,
            continuity = continuity,
            aiFlavor = aiFlavor,
            foreshadow = foreshadow,
            aiFlavorRisk = aiFlavorRiskOf(text, genre),
            issues = issues.sortedByDescending { it.severity },
            wordCount = Chapter.countWords(text),
            generatedAt = System.currentTimeMillis(),
        )
    }

    // ------------------------------------------------------------------
    // AI 腔：词层 + 句式层 + 具体度
    // ------------------------------------------------------------------

    /**
     * AI 腔维度评分。
     *
     * 与其他四个维度不同，这一维**不看语义**，只看文本表面：
     * 用了哪些套话、套话密度多高、具体信息密度多低。
     * 它回答的是「这段读起来像不像人写的」，而不是「这段写得好不好」。
     *
     * 之所以要单独成维：AI 腔是一种**独立于其他所有维度**的失败模式。
     * 一段情节连贯、标点规范、句式也符合文风的文字，照样可以是彻底的 AI 腔——
     * 因为它每一句都正确，却没有一句是具体的。
     */
    fun aiFlavor(text: String, genre: String = ""): DimensionScore {
        if (text.length < 60) {
            return DimensionScore(-1.0, "文本过短，无法评估", emptyList())
        }
        val audit = CorpusEngine.audit(text, genre)
        val risk = audit.aiFlavorScore
        // 风险越高分越低：这一维是「反向指标」
        val score = (100 - risk).toDouble()

        val issues = mutableListOf<QualityIssue>()
        if (risk >= 25) {
            val severe = audit.slop.severeHits
            val evidence = severe.take(5).joinToString("、") { it.phrase }
            issues += QualityIssue(
                IssueType.AiFlavor,
                severity = when {
                    risk >= 65 -> 3
                    risk >= 40 -> 2
                    else -> 1
                },
                message = "AI 腔风险 $risk 分" +
                    if (evidence.isNotBlank()) "，命中「$evidence」等 ${audit.slop.hits.size} 处套话" else "",
                suggestion = "具体度 ${audit.concreteness.score} 分。${audit.concreteness.note}；" +
                    "优先改标红的套话——它们不是用词问题，是信息缺失，要替换成具体的动作或器物",
            )
        }
        return DimensionScore(score, "AI 腔风险 $risk", issues)
    }

    /** 单独取风险值，供报告直接展示（与 [aiFlavor] 的分数互为反向）。 */
    private fun aiFlavorRiskOf(text: String, genre: String): Int =
        if (text.length < 60) -1 else CorpusEngine.audit(text, genre).aiFlavorScore

    // ------------------------------------------------------------------
    // Coherence：情节逻辑一致性
    // ------------------------------------------------------------------

    fun coherence(text: String, characters: List<Character>): DimensionScore {
        val issues = mutableListOf<QualityIssue>()
        if (text.length < 100) {
            return DimensionScore(0.0, "文本过短，无法评估", issues)
        }
        var score = 100.0

        // 1) 角色称呼一致性：同一角色被写成孤立简称，或用多个未登记的称呼
        for (c in characters) {
            if (c.name.length < 2) continue
            val aliases = c.aliases.split(',', '，', '/', '|').map { it.trim() }.filter { it.isNotEmpty() }
            val allNames = (listOf(c.name) + aliases).filter { it.isNotBlank() }
            val firstChar = c.name.first().toString()
            if (allNames.contains(firstChar)) continue

            // 用前后非汉字断言，避免把「李」在「桃李」中的正常用法误判进来。
            val suspicious = Regex("(?<![\\u4e00-\\u9fff])${Regex.escape(firstChar)}(?![\\u4e00-\\u9fff])")
                .findAll(text).count()
            if (suspicious <= 2) continue

            val fullNamePresent = text.contains(c.name)
            val aliasPresent = aliases.any { it.isNotBlank() && text.contains(it) }
            when {
                fullNamePresent -> issues += QualityIssue(
                    IssueType.CharacterName,
                    severity = 2,
                    message = "角色「${c.name}」在本章被简称为「${firstChar}」共 $suspicious 次，称呼不统一",
                    suggestion = "统一使用「${c.name}」，或在角色卡把「${firstChar}」登记为别名",
                )
                !aliasPresent -> issues += QualityIssue(
                    IssueType.CharacterName,
                    severity = 2,
                    message = "「${firstChar}」孤立出现 $suspicious 次，但本章未出现完整角色名「${c.name}」，疑似简称",
                    suggestion = "确认是否指角色「${c.name}」；是则补全称，或把「${firstChar}」登记为别名",
                )
                else -> continue
            }
            score -= 6
        }

        // 2) 时间跳转缺过渡：出现「第二天/三年后」却紧接动作句，读者会错位
        val timeJumps = Regex("(第二天|次日|翌日|三年后|十年后|数月后|半晌|片刻后|与此同时)")
        var lastIndex = 0
        for (m in timeJumps.findAll(text)) {
            val after = text.substring(m.range.last + 1, minOf(m.range.last + 60, text.length)).trim()
            // 时转后若没有任何逗号/破折号停顿直接接主谓，读感突兀
            if (after.isNotEmpty() && !after.startsWith("，") && !after.startsWith("—") && after.length > 8) {
                issues += QualityIssue(
                    IssueType.Transition,
                    severity = 1,
                    message = "「${m.value}」之后缺少过渡描写，场景切换略突兀",
                    suggestion = "补一句环境或心理过渡，例如「${m.value}，天刚蒙蒙亮，」",
                    offset = m.range.first,
                )
                score -= 2
            }
            lastIndex = m.range.last
        }
        if (lastIndex == 0 && text.length > 3000) {
            issues += QualityIssue(
                IssueType.Transition,
                severity = 1,
                message = "本章长达 ${text.length} 字却几乎没有时间推进标记，节奏可能拖沓",
                suggestion = "确认本章是否覆盖了足够的事件进度，必要时拆分章节",
            )
            score -= 3
        }

        // 3) 代词悬空：连续出现「他/她」但上一句无明确指代对象
        val sentences = StyleAnalyzer.splitSentences(text)
        var dangling = 0
        for (i in 1 until sentences.size) {
            val s = sentences[i]
            val prev = sentences[i - 1]
            val startsWithPronoun = s.startsWith("他") || s.startsWith("她") || s.startsWith("它") ||
                s.startsWith("他们") || s.startsWith("她们")
            val prevHasProperNoun = prev.contains('「') || prev.contains('“') ||
                Regex("[\\u4e00-\\u9fff]{2,4}(说|道|问|答|笑|叹)").containsMatchIn(prev)
            if (startsWithPronoun && !prevHasProperNoun && prev.length > 20) {
                dangling++
            }
        }
        if (dangling >= 4) {
            issues += QualityIssue(
                IssueType.PronounAmbiguity,
                severity = 2,
                message = "存在 $dangling 处连续以「他/她」开头的句子，指代对象可能模糊",
                suggestion = "在段首用名字替代代词，避免读者反复回读确认主语",
            )
            score -= minOf(12.0, dangling * 2.0)
        }

        // 4) 数字/等级自相矛盾（如「三阶」与「四阶」在同一段描述同一状态）
        val levelPattern = Regex("([一二三四五六七八九十百千0-9]+)(阶|品|级|层|重)")
        val levels = levelPattern.findAll(text).map { it.value }.toList()
        if (levels.size >= 8) {
            val distinct = levels.distinct()
            if (distinct.size > 5) {
                issues += QualityIssue(
                    IssueType.NumericConsistency,
                    severity = 1,
                    message = "本章出现 ${distinct.size} 种不同的等级/层数表述（${distinct.take(5).joinToString("、")}…），建议核对是否自洽",
                    suggestion = "对照世界设定中的力量体系，确保等级描述前后一致",
                )
                score -= 4
            }
        }

        return DimensionScore(score.coerceIn(0.0, 100.0), summary(score, issues.size), issues)
    }

    // ------------------------------------------------------------------
    // Fluency：语法精度、词汇广度、可读性
    // ------------------------------------------------------------------

    fun fluency(text: String): DimensionScore {
        val issues = mutableListOf<QualityIssue>()
        if (text.length < 100) return DimensionScore(0.0, "文本过短，无法评估", issues)
        var score = 100.0

        val sentences = StyleAnalyzer.splitSentences(text)
        if (sentences.isEmpty()) return DimensionScore(0.0, "无法切分句子", issues)

        // 1) 超长句：一口气读不完
        val longSentences = sentences.filter { it.count { c -> !c.isWhitespace() } > 60 }
        if (longSentences.isNotEmpty()) {
            val ratio = longSentences.size.toDouble() / sentences.size
            issues += QualityIssue(
                IssueType.LongSentence,
                severity = if (ratio > 0.15) 3 else 2,
                message = "有 ${longSentences.size} 句超过 60 字（占 ${(ratio * 100).toInt()}%），阅读负担偏重",
                suggestion = "在逗号处断句，或把长定语拆成独立短句",
            )
            score -= minOf(18.0, longSentences.size * 2.5)
        }

        // 2) 重复用词：同一 2-gram 在同一段反复出现
        val paras = text.split(Regex("\\n+")).filter { it.isNotBlank() }
        var repeatIssues = 0
        for (p in paras) {
            if (p.length < 60) continue
            val grams = ArrayList<String>()
            val run = StringBuilder()
            for (ch in p) {
                if (ch.code in 0x4E00..0x9FFF) run.append(ch) else {
                    val s = run.toString()
                    for (i in 0 until (s.length - 1)) grams.add(s.substring(i, i + 2))
                    run.clear()
                }
            }
            val s = run.toString()
            for (i in 0 until (s.length - 1)) grams.add(s.substring(i, i + 2))
            val freq = grams.groupingBy { it }.eachCount().filterValues { it >= 5 && it * 2.0 / grams.size > 0.05 }
            if (freq.isNotEmpty()) {
                repeatIssues++
                if (repeatIssues <= 3) {
                    val top = freq.entries.sortedByDescending { it.value }.take(3).map { "${it.key}(${it.value}次)" }
                    issues += QualityIssue(
                        IssueType.WordRepetition,
                        severity = 1,
                        message = "段落内用词重复：${top.joinToString("、")}",
                        suggestion = "替换为同义表达，或改写句式",
                    )
                }
            }
        }
        score -= minOf(15.0, repeatIssues * 3.0)

        // 3) 标点规范
        val invalidPunct = Regex("[，。！？；：]{2,}").findAll(text).count { it.value.length > 2 }
        if (invalidPunct > 0) {
            issues += QualityIssue(
                IssueType.Punctuation,
                severity = 1,
                message = "发现 $invalidPunct 处连续标点，疑似输入笔误",
                suggestion = "检查并修正多余的标点",
            )
            score -= minOf(8.0, invalidPunct * 1.5)
        }
        val halfWidth = Regex("[\\u4e00-\\u9fff][,;!?]").findAll(text).count()
        if (halfWidth >= 3) {
            issues += QualityIssue(
                IssueType.Punctuation,
                severity = 1,
                message = "中文语境混用了 $halfWidth 处半角标点",
                suggestion = "中文正文建议使用全角标点，提升排版统一度",
            )
            score -= 3
        }

        // 4) 词汇广度：去重 2-gram / 总 2-gram（TTR）
        val ttr = lexicalDiversity(text)
        if (ttr > 0 && ttr < 0.45) {
            issues += QualityIssue(
                IssueType.Vocabulary,
                severity = 2,
                message = "词汇广度偏低（多样性指数 ${"%.2f".format(ttr)}），行文略显单调",
                suggestion = "换用近义词、增加感官细节描写，减少同一动词的重复",
            )
            score -= 8
        }

        // 5) 段落过长
        val longParas = paras.count { it.count { c -> !c.isWhitespace() } > 400 }
        if (longParas > 0) {
            issues += QualityIssue(
                IssueType.ParagraphLength,
                severity = 1,
                message = "有 $longParas 个段落超过 400 字，手机端阅读容易疲劳",
                suggestion = "按动作/情绪/场景转折拆段，移动端建议每段 100-200 字",
            )
            score -= minOf(8.0, longParas * 2.5)
        }

        return DimensionScore(score.coerceIn(0.0, 100.0), summary(score, issues.size), issues)
    }

    /** 词汇多样性（type-token ratio），基于中文 2-gram。 */
    fun lexicalDiversity(text: String): Double {
        val grams = ArrayList<String>(text.length)
        val run = StringBuilder()
        fun flush() {
            val s = run.toString()
            for (i in 0 until (s.length - 1)) grams.add(s.substring(i, i + 2))
            run.clear()
        }
        for (ch in text) {
            if (ch.code in 0x4E00..0x9FFF) run.append(ch) else flush()
        }
        flush()
        if (grams.size < 50) return -1.0
        return grams.distinct().size.toDouble() / grams.size
    }

    // ------------------------------------------------------------------
    // 文风吻合度
    // ------------------------------------------------------------------

    fun styleMatch(text: String, dna: StyleDna?): DimensionScore {
        if (dna == null || !dna.isUsable) {
            return DimensionScore(-1.0, "尚未建立文风 DNA（可在作品设置中从已有章节蒸馏）", emptyList())
        }
        val score = StyleAnalyzer.matchScore(text, dna)
        if (score < 0) return DimensionScore(-1.0, "样本不足", emptyList())
        val issues = mutableListOf<QualityIssue>()
        if (score < 70) {
            issues += QualityIssue(
                IssueType.StyleDrift,
                severity = if (score < 55) 3 else 2,
                message = "文风吻合度 $score 分，与作者既有笔感存在偏差",
                suggestion = "检查句长与段落节奏：原作风平均句长 ${"%.1f".format(dna.avgSentenceLength)} 字，段落 ${"%.1f".format(dna.avgParagraphLength)} 字",
            )
        }
        return DimensionScore(score.toDouble(), "文风吻合度 $score", issues)
    }

    // ------------------------------------------------------------------
    // 叙事连续性（相邻章节）
    // ------------------------------------------------------------------

    fun continuity(text: String, recentTexts: List<String>): DimensionScore {
        if (recentTexts.isEmpty() || text.length < 200) {
            return DimensionScore(-1.0, "无上一章可比对", emptyList())
        }
        val issues = mutableListOf<QualityIssue>()
        var score = 100.0
        val prev = recentTexts.last()
        val prevTail = prev.takeLast(400)
        val head = text.take(400)

        // 衔接：本章开头若与上一章结尾无任何共享实体，读者会有断裂感
        val prevNames = Regex("[\\u4e00-\\u9fff]{2,3}(?=(说|道|问|答|笑|叹|看|走|站|坐))")
            .findAll(prevTail).map { it.value }.toSet()
        if (prevNames.isNotEmpty()) {
            val overlap = prevNames.count { head.contains(it) }
            if (overlap == 0) {
                issues += QualityIssue(
                    IssueType.SceneBreak,
                    severity = 2,
                    message = "本章开头与上一章结尾没有共享人物或场景线索，衔接可能生硬",
                    suggestion = "用上一章的人物/地点/悬念开场，或明确标注场景切换（分隔符）",
                )
                score -= 15
            }
        }

        // 上一章结尾的悬念是否被完全遗忘（连续 2 章未提及关键名词）
        val keyTerms = Regex("[「“]([\\u4e00-\\u9fff]{2,6})[」”]").findAll(prevTail).map { it.groupValues[1] }.distinct().take(5)
        val forgotten = keyTerms.count { !text.contains(it) }
        if (keyTerms.count() >= 2 && forgotten == keyTerms.count()) {
            issues += QualityIssue(
                IssueType.ThreadDropped,
                severity = 2,
                message = "上一章结尾抛出的关键线索（${keyTerms.joinToString("、")}）在本章完全未出现",
                suggestion = "至少让其中一条线索被提及或推进，避免读者觉得断线",
            )
            score -= 10
        }

        return DimensionScore(score.coerceIn(0.0, 100.0), "连续性 ${score.toInt()}", issues)
    }

    // ------------------------------------------------------------------
    // 伏笔回收台账
    // ------------------------------------------------------------------

    fun foreshadowCheck(foreshadows: List<Foreshadow>, currentChapter: Int): ForeshadowReport {
        val active = foreshadows.filter { it.status != ForeshadowStatus.Resolved && it.status != ForeshadowStatus.Abandoned }
        val overdue = active.filter { it.isOverdue(currentChapter) }
        val longIdle = active.filter {
            it.plantedAt > 0 && currentChapter - maxOf(it.plantedAt, it.advancedAt) >= 15
        }
        val issues = mutableListOf<QualityIssue>()

        overdue.forEach {
            issues += QualityIssue(
                IssueType.ForeshadowOverdue,
                severity = if (it.importance >= 3) 4 else 3,
                message = "伏笔「${it.title}」计划第 ${it.plannedResolveAt} 章回收，现已到第 $currentChapter 章仍未回收",
                suggestion = "尽快安排回收节点，或调整计划章节；高重要度伏笔长期悬空会显著伤害读者信任",
            )
        }
        longIdle.forEach {
            if (overdue.none { o -> o.id == it.id }) {
                issues += QualityIssue(
                    IssueType.ForeshadowIdle,
                    severity = 2,
                    message = "伏笔「${it.title}」自第 ${maxOf(it.plantedAt, it.advancedAt)} 章后已 ${currentChapter - maxOf(it.plantedAt, it.advancedAt)} 章未推进",
                    suggestion = "安排一次小推进（提及/误导/部分揭示），维持读者的记忆锚点",
                )
            }
        }
        if (active.size > 25) {
            issues += QualityIssue(
                IssueType.ForeshadowOverload,
                severity = 2,
                message = "当前有 ${active.size} 条伏笔处于开放状态，管理复杂度偏高",
                suggestion = "考虑回收一批低重要度伏笔，或标记部分为「已废弃」",
            )
        }

        return ForeshadowReport(
            total = foreshadows.size,
            active = active.size,
            resolved = foreshadows.count { it.status == ForeshadowStatus.Resolved },
            overdue = overdue,
            longIdle = longIdle,
            issues = issues,
        )
    }

    // ------------------------------------------------------------------

    private fun weightedScore(
        coherence: DimensionScore,
        fluency: DimensionScore,
        style: DimensionScore,
        continuity: DimensionScore,
        aiFlavor: DimensionScore = DimensionScore(-1.0, "", emptyList()),
    ): Int {
        // 缺失维度（-1）不参与加权，避免「没建立文风 DNA」被无端扣分。
        //
        // AI 腔权重 0.20 是追加的，原有四项的相对比例保持不变（0.30/0.30/0.20/0.20），
        // 因此这一维实际占总分的 0.20/1.20 ≈ 17%。给这个量级是因为：
        // 对本书的目标用户（长篇网文作者）而言，「读起来像 AI 写的」是致命伤，
        // 但不该压过连贯性与流畅度这两个更基础的质量维度。
        val parts = listOf(
            coherence.value to 0.30,
            fluency.value to 0.30,
            style.value to 0.20,
            continuity.value to 0.20,
            aiFlavor.value to 0.20,
        ).filter { it.first >= 0 }
        if (parts.isEmpty()) return -1
        val wSum = parts.sumOf { it.second }
        return (parts.sumOf { it.first * it.second } / wSum).toInt().coerceIn(0, 100)
    }

    private fun summary(score: Double, issueCount: Int): String = when {
        score >= 90 -> "优秀 · ${issueCount} 处待优化"
        score >= 75 -> "良好 · ${issueCount} 处待优化"
        score >= 60 -> "合格 · ${issueCount} 处需修改"
        else -> "需重写 · ${issueCount} 处问题"
    }
}

data class DimensionScore(
    /** -1 表示该维度不可评估 */
    val value: Double,
    val summary: String,
    val issues: List<QualityIssue>,
) {
    val displayValue: Int get() = if (value < 0) -1 else value.toInt()
}

data class QualityIssue(
    val type: IssueType,
    val severity: Int,          // 1提示 2建议 3重要 4严重
    val message: String,
    val suggestion: String = "",
    val offset: Int = -1,
) {
    val severityLabel: String get() = when (severity) {
        4 -> "严重"
        3 -> "重要"
        2 -> "建议"
        else -> "提示"
    }
}

enum class IssueType(val label: String) {
    CharacterName("角色称呼"),
    Transition("场景过渡"),
    PronounAmbiguity("代词指代"),
    NumericConsistency("数值一致"),
    LongSentence("长句"),
    WordRepetition("用词重复"),
    Punctuation("标点"),
    Vocabulary("词汇广度"),
    ParagraphLength("段落长度"),
    StyleDrift("文风偏移"),
    SceneBreak("章节衔接"),
    ThreadDropped("线索断裂"),
    ForeshadowOverdue("伏笔逾期"),
    ForeshadowIdle("伏笔停滞"),
    ForeshadowOverload("伏笔过载"),
    AiFlavor("AI 腔"),
}

data class ForeshadowReport(
    val total: Int,
    val active: Int,
    val resolved: Int,
    val overdue: List<Foreshadow>,
    val longIdle: List<Foreshadow>,
    val issues: List<QualityIssue>,
)

data class QualityReport(
    val chapterId: String,
    val chapterOrder: Int,
    val totalScore: Int,
    val coherence: DimensionScore,
    val fluency: DimensionScore,
    val style: DimensionScore,
    val continuity: DimensionScore,
    /** AI 腔维度：值越高越干净。与 [aiFlavorRisk] 互为反向，-1 表示样本不足。 */
    val aiFlavor: DimensionScore = DimensionScore(-1.0, "", emptyList()),
    val foreshadow: ForeshadowReport,
    val issues: List<QualityIssue>,
    val wordCount: Int,
    val generatedAt: Long,
    /** AI 腔风险 0..100，越高越像 AI 写的；-1 表示样本不足未判定 */
    val aiFlavorRisk: Int = -1,
) {
    val blockingIssues: List<QualityIssue> get() = issues.filter { it.severity >= 3 }

    /** 质量门禁：低于阈值或存在严重问题则不允许「定稿」。 */
    fun passesGate(minScore: Int = 70, allowSeverity4: Boolean = false): Boolean {
        if (totalScore in 0 until minScore) return false
        if (!allowSeverity4 && issues.any { it.severity >= 4 }) return false
        return true
    }

    fun gateReason(minScore: Int = 70): String = when {
        totalScore in 0 until minScore -> "综合评分 $totalScore 低于门禁线 $minScore"
        issues.any { it.severity >= 4 } -> "存在 ${issues.count { it.severity >= 4 }} 项严重问题（如伏笔逾期）"
        else -> "通过质量门禁"
    }
}
