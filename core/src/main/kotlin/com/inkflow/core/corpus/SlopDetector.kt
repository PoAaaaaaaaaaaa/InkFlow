package com.inkflow.core.corpus

import kotlinx.serialization.Serializable
import kotlin.math.min

/**
 * 一次 AI 腔命中。
 *
 * @param phrase 命中的原文
 * @param start 在原文中的起始下标，用于 UI 精确定位与一键跳转
 * @param severity 1 提示 / 2 建议 / 3 严重
 */
@Serializable
data class SlopHit(
    val phrase: String,
    val start: Int,
    val category: SlopCategory,
    val severity: Int,
    val why: String,
    val alternatives: List<String> = emptyList(),
    /** 若来自句式匹配，记录模式名 */
    val patternName: String = "",
) {
    val severityLabel: String
        get() = when (severity) {
            3 -> "严重"
            2 -> "建议"
            else -> "提示"
        }
}

/**
 * AI 腔检测报告。
 *
 * 不只看「有几处」，更看「密度」——一万字里出现两次「缓缓地」没问题，
 * 一页里出现六次就是节奏崩了。所以判定必须归一化到字数。
 */
@Serializable
data class SlopReport(
    val charCount: Int,
    val hits: List<SlopHit>,
    /** 每千字命中的加权分值 */
    val score: Double,
    /** 0..100，越高越像 AI。与 score 单调对应，给 UI 用。 */
    val riskLevel: Int,
) {
    val isEmpty: Boolean get() = hits.isEmpty()

    val severeHits: List<SlopHit> get() = hits.filter { it.severity >= 3 }

    fun byCategory(): Map<SlopCategory, Int> = hits.groupingBy { it.category }.eachCount()

    /** 按严重度倒序、同严重度按出现顺序。 */
    fun sorted(): List<SlopHit> = hits.sortedWith(
        compareByDescending<SlopHit> { it.severity }.thenBy { it.start }
    )

    /** 一句话诊断，直接进 UI 顶部横幅。 */
    val verdict: String
        get() = when {
            charCount < 200 -> "样本过短，暂不判定"
            riskLevel >= 70 -> "AI 腔很重（风险 $riskLevel），建议整段重写而不是逐词替换"
            riskLevel >= 45 -> "AI 腔偏高（风险 $riskLevel），重点看标红的 ${severeCount} 处"
            riskLevel >= 25 -> "轻度 AI 腔（风险 $riskLevel），改掉几处套话即可"
            else -> "基本没有 AI 腔（风险 $riskLevel）"
        }

    private val severeCount: Int get() = severeHits.size
}

/**
 * AI 腔检测器。
 *
 * 两层检测：
 *  1. **词层** —— 查 [SlopLexicon.entries]，长词优先匹配，避免「深深地」被拆成「深」；
 *  2. **句式层** —— 查 [SlopLexicon.patterns]，抓词表抓不到的模板结构。
 *
 * 为什么不做同义替换（即「一键去 AI 腔」）：
 * 套话的病灶不是用词，是**信息缺失**。「心中五味杂陈」换不成更好的四字词，
 * 只能换成具体的动作。所以检测器只给出**方向**，改写必须由作者或模型完成。
 */
object SlopDetector {

    /** 每千字命中次数超过此值即判定为「密集」，用于放大风险分。 */
    private const val DENSITY_ALERT = 3.0

    /**
     * 密度归一的下限样本量。
     *
     * 【为什么必须有这个下限】
     * 密度 = 命中数 × 1000 / 字数。若直接按实际字数归一，一段 20 字的文本
     * 只要出现一个「深深地」，算出来就是 100 分/千字——荒谬的爆炸值。
     * 短样本本来就不足以支撑密度推断，所以这里把它按 300 字计：
     * 短文本的分数偏**保守**（低估而非高估），这是正确的失败方向——
     * 误报一个没问题的段落，比漏报更伤作者的信任。
     */
    private const val MIN_SAMPLE_CHARS = 300

    /** 低于此长度连一个套话都装不下，直接不判定。 */
    private const val MIN_DETECT_CHARS = 10

    /**
     * 检测一段文本。
     *
     * @param minSeverity 只报告不低于该严重度的命中；默认为 1（全报）
     */
    fun detect(text: String, minSeverity: Int = 1): SlopReport {
        val clean = text.trim()
        if (clean.length < MIN_DETECT_CHARS) {
            return SlopReport(clean.length, emptyList(), 0.0, 0)
        }

        val hits = ArrayList<SlopHit>()

        // ---- 第一层：词 ----
        for (entry in SlopLexicon.entries) {
            if (entry.severity < minSeverity) continue
            var idx = clean.indexOf(entry.phrase)
            while (idx >= 0) {
                hits.add(
                    SlopHit(
                        phrase = entry.phrase,
                        start = idx,
                        category = entry.category,
                        severity = entry.severity,
                        why = entry.why,
                        alternatives = entry.alternatives,
                    )
                )
                idx = clean.indexOf(entry.phrase, idx + entry.phrase.length)
            }
        }

        // ---- 第二层：句式 ----
        for (pattern in SlopLexicon.patterns) {
            if (pattern.severity < minSeverity) continue
            for (m in pattern.regex.findAll(clean)) {
                // 该区间已被更高严重度的词命中时跳过，避免同一处重复计数
                val covered = hits.any {
                    it.start >= m.range.first && it.start < m.range.last + 1 && it.severity >= pattern.severity
                }
                if (covered) continue
                hits.add(
                    SlopHit(
                        phrase = m.value,
                        start = m.range.first,
                        category = pattern.category,
                        severity = pattern.severity,
                        why = pattern.why,
                        alternatives = pattern.alternatives,
                        patternName = pattern.name,
                    )
                )
            }
        }

        val sorted = dedupeOverlaps(hits)
        val score = weightedScore(sorted, clean.length)
        return SlopReport(
            charCount = clean.length,
            hits = sorted,
            score = score,
            // 密度给「有多严重」，样本量给「这个判断有多可信」，两者相乘才该报告给作者
            riskLevel = riskOf(score, confidenceOf(clean.length)),
        )
    }

    /**
     * 重叠命中去重。
     *
     * 【为什么必须做】词库里有包含关系的条目：「不禁倒吸一口凉气」包含「不禁」，
     * 「深深地看了他一眼」包含「深深地」。若不去重，一句话会被算两次，
     * 密度分数直接翻倍——而作者在 UI 上看到的是同一处被标红两遍。
     *
     * 规则：**长的胜**（更具体），长度相同则严重度高的胜；互不重叠的全部保留。
     */
    private fun dedupeOverlaps(hits: List<SlopHit>): List<SlopHit> {
        if (hits.size <= 1) return hits
        val bySpecificity = hits.sortedWith(
            compareByDescending<SlopHit> { it.phrase.length }.thenByDescending { it.severity }
        )
        val kept = ArrayList<SlopHit>(bySpecificity.size)
        for (h in bySpecificity) {
            val hEnd = h.start + h.phrase.length
            val conflicts = kept.any { k -> h.start < k.start + k.phrase.length && k.start < hEnd }
            if (!conflicts) kept.add(h)
        }
        return kept.sortedBy { it.start }
    }

    /**
     * 加权分值：严重度越高权重越大，再除以字数归一。
     *
     * 权重取 1 / 2 / 4 是有意的非线性——三处「严重」比十二处「提示」
     * 更该触发重写建议，线性加权做不到这一点。
     *
     * 归一化用 `max(实际字数, MIN_SAMPLE_CHARS)`，短样本因此得分偏保守。
     */
    private fun weightedScore(hits: List<SlopHit>, charCount: Int): Double {
        if (charCount <= 0) return 0.0
        val weighted = hits.sumOf {
            when (it.severity) {
                3 -> 4.0
                2 -> 2.0
                else -> 1.0
            }
        }
        return weighted * 1000.0 / maxOf(charCount, MIN_SAMPLE_CHARS)
    }

    /**
     * 把每千字加权分映射到 0..100 的风险等级。
     *
     * 映射曲线分两段：
     *  - 0 → 0，[DENSITY_ALERT]（3.0 分/千字）→ 50：这一段是「有多脏」；
     *  - 超过 3.0 之后以更缓的斜率到 100。
     *
     * 3.0 这个拐点是标定出来的：正常中文小说（含大量对话）跑出来的分值
     * 多数落在 0.5~2.0，超过 3 基本可以肉眼看出走味。
     *
     * @param confidence 样本置信度 0..1，由 [confidenceOf] 按字数给出
     */
    private fun riskOf(score: Double, confidence: Double): Int {
        val raw = when {
            score <= 0.0 -> 0.0
            score <= DENSITY_ALERT -> score / DENSITY_ALERT * 50.0
            else -> 50.0 + min(50.0, (score - DENSITY_ALERT) / DENSITY_ALERT * 25.0)
        }
        return (raw * confidence).toInt().coerceIn(0, 100)
    }

    /**
     * 样本置信度 0..1。
     *
     * 【为什么需要它】密度指标解决「有多脏」，但解决不了「这点样本够不够下结论」。
     * 一段 18 字的文本里出现一个「深深地」，密度算出来是满分级别——
     * 可是凭一句话判定整章走味，是**统计上的越权**。
     *
     * 因此置信度按字数阶梯上升，[MIN_SAMPLE_CHARS]（300 字）之前不满信：
     *  - 300 字及以上 → 1.0（一个完整场景，足以判断）
     *  - 60~300 字   → 线性 0.35 ~ 1.0
     *  - 60 字以下   → 0.35 封底（能报，但永远不可能是「严重」）
     *
     * 这个设计保证短样本**只会被低估，不会被高估**——误报比漏报更伤信任。
     */
    private fun confidenceOf(charCount: Int): Double = when {
        charCount >= MIN_SAMPLE_CHARS -> 1.0
        charCount <= 60 -> 0.35
        else -> 0.35 + (charCount - 60).toDouble() / (MIN_SAMPLE_CHARS - 60) * 0.65
    }

    /**
     * 该位置是否是句首——用于「同一句内多命中」的合并展示。
     * 保持为公开方法：UI 需要在展示时按句分组。
     */
    fun sentenceIndexOf(text: String, offset: Int): Int {
        var idx = 0
        for (i in 0 until offset.coerceAtMost(text.length)) {
            if (text[i] == '。' || text[i] == '！' || text[i] == '？' || text[i] == '\n') idx++
        }
        return idx
    }

    /**
     * 生成注入提示词的负向约束块。
     *
     * 与 [SlopLexicon.blacklistBlock] 的区别：这里会**结合当前文本的实际命中**
     * 做针对性提示，而不是无差别地糊一长串清单。
     */
    fun targetedAdvice(report: SlopReport, maxItems: Int = 6): String {
        if (report.isEmpty) return ""
        val top = report.sorted().take(maxItems)
        return buildString {
            appendLine("【本文已检测到的 AI 腔（改写时必须消除）】")
            top.forEach { hit ->
                appendLine("- 「${hit.phrase}」：${hit.why}")
                if (hit.alternatives.isNotEmpty()) {
                    appendLine("  → 方向：${hit.alternatives.first()}")
                }
            }
        }
    }
}
