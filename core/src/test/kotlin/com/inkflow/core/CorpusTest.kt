package com.inkflow.core

import com.inkflow.core.corpus.CorpusEngine
import com.inkflow.core.corpus.DeconstructAspect
import com.inkflow.core.corpus.DeconstructLibrary
import com.inkflow.core.corpus.Sense
import com.inkflow.core.corpus.SensoryVault
import com.inkflow.core.corpus.SlopCategory
import com.inkflow.core.corpus.SlopDetector
import com.inkflow.core.corpus.SlopLexicon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 语料库测试。
 *
 * 覆盖三条线：
 *  1. 词库自身的完整性（不重复、严重度合法、每条都有可执行的替代方向）；
 *  2. 检测器的判定正确性（能抓到、不误伤、密度计算方向正确）；
 *  3. 数据诚实性（未核实的书不得带拆书结论）。
 */
class CorpusTest {

    // ------------------------------------------------------------------
    // 词库完整性
    // ------------------------------------------------------------------

    @Test
    fun lexiconHasNoDuplicatePhrase() {
        val phrases = SlopLexicon.entries.map { it.phrase }
        val dupes = phrases.groupingBy { it }.eachCount().filterValues { it > 1 }
        assertTrue("词条重复：$dupes", dupes.isEmpty())
    }

    @Test
    fun everyEntryHasSeverityInRange() {
        SlopLexicon.entries.forEach {
            assertTrue("「${it.phrase}」严重度越界：${it.severity}", it.severity in 1..3)
        }
        SlopLexicon.patterns.forEach {
            assertTrue("模式「${it.name}」严重度越界：${it.severity}", it.severity in 1..3)
        }
    }

    @Test
    fun everyEntryExplainsWhy() {
        // 一条只骂不改的黑名单对作者没有价值
        SlopLexicon.entries.forEach {
            assertTrue("「${it.phrase}」缺少 why", it.why.isNotBlank())
        }
    }

    @Test
    fun severeEntriesProvideAlternatives() {
        // 严重条目必须给出具体化方向，否则作者只会删掉、不会改写
        val missing = SlopLexicon.entries.filter { it.severity >= 3 && it.alternatives.isEmpty() }
        assertTrue("以下严重词条缺少替代方向：${missing.map { it.phrase }}", missing.isEmpty())
    }

    @Test
    fun phrasesSortedForLongestFirstMatching() {
        val sorted = SlopLexicon.phrases
        for (i in 0 until sorted.size - 1) {
            assertTrue(
                "长词未优先：「${sorted[i]}」应排在「${sorted[i + 1]}」之前",
                sorted[i].length >= sorted[i + 1].length,
            )
        }
    }

    @Test
    fun everyCategoryHasEntries() {
        // 每类都必须有实际可用的话术来源——词条或句式模式，二者至少其一。
        // 「排比滥用」这类结构性套话只存在于句式层，没有对应的单词黑名单，这是有意的。
        SlopCategory.entries.forEach { c ->
            val hasEntries = SlopLexicon.byCategory(c).isNotEmpty()
            val hasPatterns = SlopLexicon.patterns.any { it.category == c }
            assertTrue(
                "分类「${c.label}」既没有词条也没有句式模式",
                hasEntries || hasPatterns,
            )
        }
    }

    @Test
    fun enumAdviceIsUnduplicated() {
        // 每类的建议必须说人话且互不重复，否则注入 Prompt 时只是噪音
        val advice = SlopCategory.entries.map { it.advice }
        assertEquals("分类建议出现重复", advice.size, advice.distinct().size)
        advice.forEach { assertTrue("建议过短：$it", it.length >= 15) }
    }

    // ------------------------------------------------------------------
    // 检测器
    // ------------------------------------------------------------------

    @Test
    fun detectsPlainCliche() {
        // 样本须达到 300 字置信度门槛，否则风险分会被置信度压制——
        // 这是刻意的设计（见 SlopDetector.confidenceOf），不是 bug
        val text = "他看着她，心中五味杂陈。空气仿佛凝固了，月光如水洒在她的脸上。" +
            "他深深地看了她一眼，不禁倒吸一口凉气，整个人不由自主地后退了半步。" +
            "那一刻，他仿佛感受到了某种难以形容的情感，思绪万千，百感交集。" +
            "他静静地站在那里，默默地想着那些无尽地淡去的往事，心中五味杂陈。" +
            "时间仿佛静止，整个世界都安静了。他轻轻地叹了口气，眼神中闪过一丝异样。" +
            "嘴角勾起一抹意味深长的弧度，然而他什么也没说，只是缓缓地转过身去。" +
            "夜色如墨，夜凉如水，一阵风吹过，他深深地吸了一口气，然后开口。" +
            "她站在原地没有动，静静地听着，脑海里一片空白，说不清道不明的感觉涌上来。" +
            "他走了几步又停下，回头看了她一眼，那一眼里有许多东西，却什么也没说。" +
            "她静静地站在原地，看着他渐渐远去的背影，不禁深深地叹了口气，只觉夜凉如水。"
        val report = SlopDetector.detect(text)
        assertTrue("样本应达到置信度门槛，实际 ${report.charCount} 字", report.charCount >= 300)
        assertTrue("应检测到多处命中，实际 ${report.hits.size}", report.hits.size >= 8)
        assertTrue("应包含情绪套话", report.byCategory().containsKey(SlopCategory.EmotionCliché))
        assertTrue("风险应偏高，实际 ${report.riskLevel}", report.riskLevel >= 50)
    }

    @Test
    fun cleanTextScoresLow() {
        val clean = "他把烟按灭在没抽完的位置，起身的时候椅子腿在地上刮出一声。" +
            "外面在下雨，雨点打在铁皮雨棚上，声音很密。" +
            "他数了数桌上的零钱，一共七张，其中两张是破的。" +
            "店员没抬头，他也没说话，两个人就这么待了大概两分钟。"
        val report = SlopDetector.detect(clean)
        assertTrue("干净文本不应被判为高 AI 腔，实际 ${report.riskLevel}", report.riskLevel < 30)
    }

    @Test
    fun overlappingHitsAreDeduplicatedToLongest() {
        // 「不禁倒吸一口凉气」包含「不禁」。一句话只应被标红一次，且保留更具体的那条。
        val text = "他不禁倒吸一口凉气，整个人愣在原地，手里的杯子差点掉下去。"
        val report = SlopDetector.detect(text)
        val atSameSpot = report.hits.filter { it.start == 1 }
        assertEquals("同一处不应重复计分：$atSameSpot", 1, atSameSpot.size)
        assertEquals("应保留更长的条目", "不禁倒吸一口凉气", atSameSpot.first().phrase)
    }

    @Test
    fun nonOverlappingHitsAllSurvive() {
        val text = "他心中五味杂陈，又深深地叹了口气，最后静静地站起身，走了出去。"
        val report = SlopDetector.detect(text)
        val phrases = report.hits.map { it.phrase }
        assertTrue("互不重叠的命中都应保留，实际 $phrases", phrases.size >= 3)
    }

    @Test
    fun detectsSentencePatterns() {
        val text = "这不是结束，而是开始。" +
            "与其说他聪明，不如说他幸运。" +
            "他既是老师，也是学生。他既是朋友，也是对手。"
        val report = SlopDetector.detect(text)
        val patternHits = report.hits.filter { it.patternName.isNotBlank() }
        assertTrue("应命中句式模板，实际 ${patternHits.map { it.patternName }}", patternHits.isNotEmpty())
    }

    @Test
    fun extremelyShortTextIsNotJudged() {
        val report = SlopDetector.detect("太短。")
        assertTrue(report.isEmpty)
        assertEquals(0, report.riskLevel)
    }

    @Test
    fun shortSampleScoresConservatively() {
        // 短样本按 300 字归一：一句话里的一个套话不应该被打成满分
        val report = SlopDetector.detect("他深深地看了她一眼，然后转身离开。")
        assertTrue("短样本命中应被记录", report.hits.isNotEmpty())
        assertTrue("短样本风险必须保守，实际 ${report.riskLevel}", report.riskLevel < 60)
    }

    @Test
    fun hitOffsetsPointToRealText() {
        val text = "前面一些铺垫。空气仿佛凝固了。后面还有一些内容。"
        val report = SlopDetector.detect(text)
        val hit = report.hits.first { it.phrase == "空气仿佛凝固了" }
        assertEquals("空气仿佛凝固了", text.substring(hit.start, hit.start + hit.phrase.length))
    }

    @Test
    fun severityFilterWorks() {
        val text = "然而他走了。然而她留下了。然而一切都变了。然而没人说话。"
        val all = SlopDetector.detect(text, minSeverity = 1)
        val onlySevere = SlopDetector.detect(text, minSeverity = 3)
        assertTrue("低阈值命中数应不少于高阈值", all.hits.size >= onlySevere.hits.size)
        assertTrue("高阈值结果里不应有低严重度", onlySevere.hits.all { it.severity >= 3 })
    }

    @Test
    fun targetedAdviceMentionsActualHits() {
        val text = "她心中五味杂陈，不禁倒吸一口凉气，瞳孔骤然收缩，" +
            "空气仿佛凝固了，时间仿佛静止，整个世界都安静了。"
        val report = SlopDetector.detect(text)
        val advice = SlopDetector.targetedAdvice(report)
        assertTrue("建议应提及实际命中的词", advice.contains("心中五味杂陈"))
        assertTrue("建议应给出改写方向", advice.contains("方向"))
    }

    // ------------------------------------------------------------------
    // 具体化词库
    // ------------------------------------------------------------------

    @Test
    fun vaultEntriesAreConcreteNotSynonyms() {
        // 具体写法应当是可在小说里直接落笔的句子，不是同义压缩词
        SensoryVault.entries.forEach { entry ->
            entry.concrete.forEach { c ->
                assertTrue("「${entry.generic}」的具体写法「$c」太短，疑似同义词", c.length >= 4)
            }
        }
    }

    @Test
    fun vaultCoversEverySense() {
        Sense.entries.forEach { s ->
            assertTrue("通道「${s.label}」没有词条", SensoryVault.bySense(s).isNotEmpty())
        }
    }

    @Test
    fun genreScopeRecognisesCommonGenres() {
        assertEquals("古代", CorpusEngine.genreScopeOf("东方玄幻"))
        assertEquals("科幻", CorpusEngine.genreScopeOf("星际机甲"))
        assertEquals("现代", CorpusEngine.genreScopeOf("都市悬疑"))
        assertEquals("通用", CorpusEngine.genreScopeOf(""))
        assertEquals("通用", CorpusEngine.genreScopeOf("完全没听过的题材"))
    }

    @Test
    fun chooseWordsReturnsCandidates() {
        val choice = CorpusEngine.chooseWords("愤怒")
        assertTrue(choice != null)
        assertTrue(choice!!.candidates.isNotEmpty())
        assertTrue("应说明为什么这么换", choice.rationale.isNotBlank())
    }

    @Test
    fun chooseWordsReturnsNullForUnknown() {
        assertNull(CorpusEngine.chooseWords("量子纠缠退相干"))
    }

    @Test
    fun suggestionsScaleWithProblemSeverity() {
        val clean = "他把水杯放下，看了看窗外。楼下的车开走了，尾灯还亮了一会儿，红灯变绿，又变红。" +
            "他想起还有件事没做，但想不起是什么，就先去洗了个澡。水有点凉，他没调。" +
            "出来的时候毛巾搭在椅背上，他拿起来擦了擦头发，随手扔回了原处。"
        val bad = "她心中五味杂陈，不禁倒吸一口凉气，瞳孔骤然收缩，" +
            "空气仿佛凝固了，时间仿佛静止，整个世界都安静了，月光如水。" +
            "她深深地看了他一眼，眼神中闪过一丝异样，嘴角勾起一抹意味深长的弧度。"
        val cleanCount = CorpusEngine.suggestionsFor(clean).size
        val badCount = CorpusEngine.suggestionsFor(bad).size
        assertTrue("问题文本应得到更多建议：干净 $cleanCount vs 问题 $badCount", badCount > cleanCount)
    }

    // ------------------------------------------------------------------
    // 具体度
    // ------------------------------------------------------------------

    @Test
    fun concreteTextScoresHigherThanAbstract() {
        val concrete = "她把手指按在杯壁上，水是温的。后颈有点热，她伸手把窗户推开一条缝，风从左边进来。" +
            "桌上的钥匙压着一张纸，纸角被水浸过，发黄，摸上去有一点脆。" +
            "她低头看了一眼自己的袖口，扣子掉了一颗，线头还挂着，风一吹就晃。" +
            "楼下的门响了一声，她没回头，只是把杯子往桌子中间挪了挪。"
        val abstract = "她的内心久久不能平静，思绪万千，百感交集，那一刻她仿佛感受到了某种难以形容的情感。" +
            "整个人深深地陷入了无尽的遐想之中，一切都是那么淡淡的、静静的。" +
            "她不禁思考起人生的意义，总而言之，这是一种说不清道不明的状态，一种无法言说的东西。" +
            "她久久地坐在那里，静静地想着，默默地回忆着那些无尽地淡去的往事。"

        val concreteScore = CorpusEngine.concretenessOf(concrete).score
        val abstractScore = CorpusEngine.concretenessOf(abstract).score
        assertTrue(
            "具体文本应得分更高：具体 $concreteScore vs 抽象 $abstractScore",
            concreteScore > abstractScore,
        )
    }

    @Test
    fun auditCombinesBothDirections() {
        val text = "她心中五味杂陈，空气仿佛凝固了，月光如水。她说不清道不明的，深深地叹了口气。" +
            "整个世界都安静了，这一刻，她不禁想起了很多。" +
            "然而她什么也没说，只是静静地站在那里，许久许久。"
        val audit = CorpusEngine.audit(text, "都市")
        assertTrue("应检测到套话", !audit.slop.isEmpty)
        assertTrue("抽象文本具体度应偏低，实际 ${audit.concreteness.score}", audit.concreteness.score < 50)
        assertTrue("综合 AI 味应偏高，实际 ${audit.aiFlavorScore}", audit.aiFlavorScore >= 40)
        assertTrue(audit.verdict.isNotBlank())
    }

    // ------------------------------------------------------------------
    // 拆书库：数据诚实性
    // ------------------------------------------------------------------

    @Test
    fun unverifiedBooksCarryNoFindings() {
        // 核心约束：没核实的书不允许有拆书结论
        DeconstructLibrary.unverifiedTitles.forEach { title ->
            assertNull("未核实书目不应出现在 verified 列表：$title", DeconstructLibrary.card(title))
        }
    }

    @Test
    fun everyVerifiedCardHasSourceAndFindings() {
        DeconstructLibrary.verifiedCards.forEach { card ->
            assertTrue("《${card.title}》标为已验证却没有来源", card.source.isNotBlank())
            assertTrue("《${card.title}》标为已验证却没有拆书结论", card.hasFindings)
            assertTrue("《${card.title}》缺少题材", card.genre.isNotBlank())
        }
    }

    @Test
    fun everyFindingIsTransferable() {
        // 拆书的价值在「可迁移」，没有 transferable 的结论只是读后感
        DeconstructLibrary.verifiedCards.forEach { card ->
            card.findings.forEach { f ->
                assertTrue("《${card.title}》的 ${f.aspect.label} 缺少机制描述", f.mechanism.length >= 20)
                assertTrue("《${card.title}》的 ${f.aspect.label} 缺少证据", f.evidence.length >= 10)
                assertTrue("《${card.title}》的 ${f.aspect.label} 缺少迁移方法", f.transferable.length >= 20)
            }
        }
    }

    @Test
    fun fullListHasEightBooks() {
        assertEquals(8, DeconstructLibrary.totalCount)
        assertEquals(8, DeconstructLibrary.allTitles.distinct().size)
    }

    @Test
    fun libraryCoversMultipleGenres() {
        val genres = DeconstructLibrary.verifiedCards.map { it.genre }
        assertTrue("拆书库应覆盖多种题材，实际 $genres", genres.distinct().size >= 4)
    }

    @Test
    fun promptBlockSkipsCaveatBooksUnlessOpening() {
        // 6 章样本的书不该被拿去指导百万字写作
        val normal = DeconstructLibrary.promptBlock(maxBooks = 5, forOpening = false)
        val opening = DeconstructLibrary.promptBlock(maxBooks = 5, forOpening = true)
        assertFalse("常规模式不应包含带样本量警示的书", normal.contains("脑机飞升"))
        assertTrue("开篇模式应可用带警示的书", opening.contains("脑机飞升"))
        assertTrue("开篇模式结果应更长", opening.length > normal.length)
    }

    // ------------------------------------------------------------------
    // 语料库总装
    // ------------------------------------------------------------------

    @Test
    fun writingBlockContainsAllThreeLayers() {
        val block = CorpusEngine.writingBlock("都市悬疑")
        assertTrue("应包含负向清单", block.contains("AI 腔禁忌清单"))
        assertTrue("应包含正向选词库", block.contains("具体化选词库"))
        assertTrue("应包含拆书参照", block.contains("拆书参照"))
    }

    @Test
    fun writingBlockRespectsGenreScope() {
        val scifi = CorpusEngine.writingBlock("科幻末世")
        assertTrue("科幻题材应带入对应题材词条", scifi.contains("气闸") || scifi.contains("舱内") || scifi.contains("真空"))
    }

    @Test
    fun writingBlockCanOmitDeconstruct() {
        val block = CorpusEngine.writingBlock("都市", includeDeconstruct = false)
        assertFalse("显式关闭时不应注入拆书", block.contains("拆书参照"))
        assertTrue("但仍应保留选词库", block.contains("具体化选词库"))
    }

    @Test
    fun statsCountsAreConsistent() {
        val stats = CorpusEngine.stats()
        assertEquals(SlopLexicon.size, stats.slopEntries)
        assertEquals(SlopLexicon.patterns.size, stats.slopPatterns)
        assertEquals(SensoryVault.size, stats.vaultEntries)
        assertEquals(DeconstructLibrary.totalCount, stats.booksTotal)
        assertEquals(DeconstructLibrary.verifiedCount, stats.booksVerified)
        assertTrue(stats.summary.isNotBlank())
    }

    @Test
    fun aspectCoverageIsDiverse() {
        val aspects = DeconstructLibrary.verifiedCards.flatMap { c -> c.findings.map { it.aspect } }.distinct()
        assertTrue("拆解维度应足够分散，实际 $aspects", aspects.size >= 6)
        assertTrue(aspects.contains(DeconstructAspect.OpeningHook))
        assertTrue(aspects.contains(DeconstructAspect.GoldenFinger))
    }
}
