package com.inkflow.core.corpus

import kotlinx.serialization.Serializable

/**
 * 语料库的一次选词结果。
 *
 * 「选词」不是同义替换。给定一个抽象说法，返回的是**可以替换它的具体写法**——
 * 换掉的不是词，是颗粒度。
 */
@Serializable
data class WordChoice(
    /** 被替换的抽象说法 */
    val generic: String,
    /** 通道：视觉/听觉/嗅觉/触觉/温觉/体感 */
    val sense: String,
    /** 候选具体写法 */
    val candidates: List<String>,
    /** 为什么这么换 */
    val rationale: String,
)

/**
 * 语料库引擎：把 [SlopLexicon]、[SensoryVault]、[DeconstructLibrary] 三层
 * 组织成写作时可以真正调用的能力。
 *
 * 三层各司其职：
 *  - **负向** [SlopLexicon]：告诉你别写什么；
 *  - **正向** [SensoryVault]：告诉你可以写什么；
 *  - **参照** [DeconstructLibrary]：告诉你同类作品靠什么留人。
 *
 * 只有负向清单的写作助手会写出「干净但空洞」的文字——
 * 删掉了套话，却不知道该填什么进去。三层必须同时在场。
 */
object CorpusEngine {

    // ------------------------------------------------------------------
    // 一、注入提示词
    // ------------------------------------------------------------------

    /**
     * 组装语料库提示块。这是接入 [com.inkflow.core.agent.AgentPipeline] 的入口。
     *
     * @param genre 作品题材，用于挑选对应题材的具体化词条（古代/现代/科幻）
     * @param includeDeconstruct 是否注入拆书参照。长 Prompt 场景可关掉。
     */
    fun writingBlock(
        genre: String = "",
        includeDeconstruct: Boolean = true,
        maxSlop: Int = 24,
        maxVaultEntries: Int = 8,
    ): String {
        val scope = genreScopeOf(genre)
        val parts = listOfNotNull(
            SensoryVault.promptBlock(scope, maxEntries = maxVaultEntries).ifBlank { null },
            SlopLexicon.blacklistBlock(maxSevere = maxSlop),
            if (includeDeconstruct) DeconstructLibrary.promptBlock().ifBlank { null } else null,
        )
        if (parts.isEmpty()) return ""
        return buildString {
            appendLine("===== 语料库参考（选词用，不是让你照抄）=====")
            appendLine()
            parts.forEach {
                append(it.trimEnd())
                appendLine()
                appendLine()
            }
        }.trimEnd()
    }

    /**
     * 题材 → 词库范围。未识别的题材一律走「通用」，
     * 因为通用词条在任何题材里都不会出错，而错认题材会引入违和的细节。
     */
    fun genreScopeOf(genre: String): String {
        val g = genre.trim()
        if (g.isEmpty()) return "通用"
        return when {
            listOf("科幻", "星际", "末世", "机甲", "赛博", "太空", "未来").any { g.contains(it) } -> "科幻"
            listOf("历史", "古代", "武侠", "仙侠", "玄幻", "宫廷", "江湖", "架空").any { g.contains(it) } -> "古代"
            listOf("都市", "现代", "言情", "职场", "悬疑", "灵异").any { g.contains(it) } -> "现代"
            else -> "通用"
        }
    }

    // ------------------------------------------------------------------
    // 二、选词
    // ------------------------------------------------------------------

    /**
     * 给定抽象说法，返回具体化候选。
     *
     * 用于 UI 上的「这段太平了」按钮：作者选中一句，点一下，
     * 得到 3 个可以替换的具体写法方向。
     */
    fun chooseWords(generic: String, scope: String = "通用"): WordChoice? {
        val entry = SensoryVault.lookup(generic)
            ?: SensoryVault.entries.firstOrNull { it.generic.contains(generic) || generic.contains(it.generic) }
            ?: return null
        return WordChoice(
            generic = entry.generic,
            sense = entry.sense.label,
            candidates = entry.concrete,
            rationale = rationaleOf(entry.sense),
        )
    }

    private fun rationaleOf(sense: Sense): String = when (sense) {
        Sense.Sight -> "用具体的视觉落点替代概括，读者会自动补全画面。"
        Sense.Sound -> "声音是最便宜的场景锚点——一句话就把空间撑起来了。"
        Sense.Smell -> "气味直接绕过理性，是最快建立记忆的通道，但网文里用得最少。"
        Sense.Touch -> "触觉让读者产生身体感，比视觉描述更容易代入。"
        Sense.Temperature -> "温度变化是情绪的生理对应物，写温度等于写情绪，还不用命名情绪。"
        Sense.Body -> "把情绪翻译成可观测的动作，读者自己完成解读——这才是小说的写法。"
    }

    /**
     * 为一段文本推荐可用的具体化方向。
     *
     * 逻辑：先检测这段的 AI 腔，再按命中密度决定给多少方向。
     * 一片干净的文本不需要指导，硬塞建议只会干扰作者。
     */
    fun suggestionsFor(text: String, scope: String = "通用", maxItems: Int = 5): List<WordChoice> {
        val report = SlopDetector.detect(text)
        val wanted = when {
            report.riskLevel >= 60 -> maxItems
            report.riskLevel >= 30 -> (maxItems + 1) / 2
            report.isEmpty -> 0
            else -> 2
        }
        if (wanted == 0) return emptyList()

        val categories = report.hits.map { it.category }.distinct()
        val senses = if (categories.any { it == SlopCategory.EmotionCliché || it == SlopCategory.BodyCliché }) {
            listOf(Sense.Body, Sense.Sound, Sense.Touch, Sense.Temperature, Sense.Sight, Sense.Smell)
        } else {
            listOf(Sense.Sight, Sense.Sound, Sense.Smell, Sense.Touch)
        }

        val picked = ArrayList<WordChoice>(wanted)
        for (sense in senses) {
            if (picked.size >= wanted) break
            SensoryVault.bySense(sense)
                .filter { it.scope == "通用" || it.scope == scope }
                .forEach { entry ->
                    if (picked.size < wanted && picked.none { it.generic == entry.generic }) {
                        picked.add(
                            WordChoice(
                                generic = entry.generic,
                                sense = entry.sense.label,
                                candidates = entry.concrete,
                                rationale = rationaleOf(entry.sense),
                            )
                        )
                    }
                }
        }
        return picked
    }

    // ------------------------------------------------------------------
    // 三、审稿
    // ------------------------------------------------------------------

    /**
     * 对一篇文章做完整的 AI 味审稿。
     *
     * 除了词层检测，还会算一个 [CorpusAudit.concreteness]（具体度）——
     * 只用负向清单是查不出「干净但空洞」的文本的，
     * 因为它一句话都没违规，只是什么都没说。
     */
    fun audit(text: String, genre: String = ""): CorpusAudit {
        val slop = SlopDetector.detect(text)
        val scope = genreScopeOf(genre)
        return CorpusAudit(
            slop = slop,
            concreteness = concretenessOf(text, scope),
            suggestions = suggestionsFor(text, scope),
            scope = scope,
        )
    }

    /**
     * 具体度评分 0..100。
     *
     * 衡量的是「这段文字里有多少东西是能被看见、听见、摸到的」。
     * 算法刻意保持可解释：
     *  1. 统计感官锚点数——具体动词、器物、身体部位、方位词；
     *  2. 统计抽象名词数——情绪词、概念词、万能副词；
     *  3. 按千字归一后取比值。
     *
     * 不做机器学习模型：规则能算的东西不该引入一个不可解释的黑盒，
     * 尤其当作者需要知道「为什么是 42 分」的时候。
     */
    fun concretenessOf(text: String, scope: String = "通用"): ConcretenessScore {
        val clean = text.trim()
        if (clean.length < 60) {
            return ConcretenessScore(0, 0.0, 0.0, 0, "样本过短（不足 60 字）")
        }
        // 与 SlopDetector 一致：短样本按 300 字归一，分数偏保守而非偏爆炸
        val per1k = 1000.0 / maxOf(clean.length, 300)

        // 具体锚点：身体部位 + 器物 + 方位 + 感官动词
        val bodyParts = listOf(
            "手", "手指", "掌心", "指腹", "手腕", "肩", "后颈", "脖子", "喉咙", "下巴",
            "眼睛", "瞳孔", "鼻梁", "嘴唇", "牙齿", "膝盖", "后背", "胸口", "额头",
        )
        val objects = listOf(
            "杯", "烟", "门", "桌", "椅", "窗", "灯", "纸", "笔", "刀", "剑", "车",
            "手机", "屏幕", "钥匙", "石阶", "栏杆", "墙", "地砖", "衣领", "袖口",
        )
        val spatials = listOf(
            "上面", "下面", "左边", "右边", "前面", "后面", "旁边", "对面", "角落", "门口", "窗外",
        )

        var anchors = 0
        for (w in bodyParts + objects + spatials) {
            anchors += countOccurrences(clean, w)
        }
        val anchorRate = anchors * per1k

        // 抽象词：情绪命名 + 概念名词 + 万能副词
        val abstracts = SlopLexicon.entries
            .filter { it.category in setOf(SlopCategory.EmotionCliché, SlopCategory.AbstractNoun, SlopCategory.EmptyAdverb) }
            .sumOf { countOccurrences(clean, it.phrase) }
        val abstractRate = abstracts * per1k

        // 比值映射：锚点密度 8/千字 视为满分基准，抽象词每 1/千字 扣 12 分
        val base = (anchorRate / 8.0 * 100.0).coerceAtMost(100.0)
        val penalty = abstractRate * 12.0
        val score = (base - penalty).coerceIn(0.0, 100.0).toInt()

        val note = when {
            score >= 75 -> "具体度良好，画面靠细节自己成立"
            score >= 50 -> "具体度中等，增加器物与体感可以更实"
            score >= 25 -> "偏抽象，读者需要自己脑补画面"
            else -> "高度抽象，几乎全是概念与情绪命名"
        }

        return ConcretenessScore(
            score = score,
            anchorRate = anchorRate,
            abstractRate = abstractRate,
            anchorCount = anchors,
            note = note,
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

    // ------------------------------------------------------------------

    /** 语料库规模，用于设置页展示与自检。 */
    fun stats(): CorpusStats = CorpusStats(
        slopEntries = SlopLexicon.size,
        slopPatterns = SlopLexicon.patterns.size,
        vaultEntries = SensoryVault.size,
        vaultConcreteCount = SensoryVault.entries.sumOf { it.concrete.size },
        booksTotal = DeconstructLibrary.totalCount,
        booksVerified = DeconstructLibrary.verifiedCount,
    )
}

/**
 * 具体度评分明细。
 */
@Serializable
data class ConcretenessScore(
    val score: Int,
    /** 每千字具体锚点数 */
    val anchorRate: Double,
    /** 每千字抽象词数 */
    val abstractRate: Double,
    val anchorCount: Int,
    val note: String,
) {
    val display: String get() = "$score · $note"
}

/**
 * 一次完整的语料库审稿结果。
 */
@Serializable
data class CorpusAudit(
    val slop: SlopReport,
    val concreteness: ConcretenessScore,
    val suggestions: List<WordChoice>,
    val scope: String,
) {
    /**
     * 综合 AI 味评分 0..100，越高越像 AI。
     *
     * 两个来源加权：套话密度占七成，抽象度占三成。
     * 套话权重更高，因为它是**可被读者直接指认**的症状；
     * 抽象度高只能说明文字还不够好，不等于像 AI。
     */
    val aiFlavorScore: Int
        get() = (slop.riskLevel * 0.7 + (100 - concreteness.score) * 0.3).toInt().coerceIn(0, 100)

    val verdict: String
        get() = when {
            aiFlavorScore >= 70 -> "AI 味很重（$aiFlavorScore 分）· ${slop.verdict}"
            aiFlavorScore >= 45 -> "AI 味偏重（$aiFlavorScore 分）· ${slop.verdict}"
            aiFlavorScore >= 25 -> "略有 AI 味（$aiFlavorScore 分）"
            else -> "读起来像人写的（$aiFlavorScore 分）"
        }
}

/**
 * 语料库自检数据。
 */
@Serializable
data class CorpusStats(
    val slopEntries: Int,
    val slopPatterns: Int,
    val vaultEntries: Int,
    val vaultConcreteCount: Int,
    val booksTotal: Int,
    val booksVerified: Int,
) {
    val summary: String
        get() = "AI 腔词条 $slopEntries 条 + 句式 $slopPatterns 条 · " +
            "具体化词库 $vaultEntries 组共 $vaultConcreteCount 条 · " +
            "拆书 $booksVerified/$booksTotal 本已验证"
}
