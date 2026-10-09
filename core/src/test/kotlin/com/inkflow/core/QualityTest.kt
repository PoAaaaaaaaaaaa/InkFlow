package com.inkflow.core

import com.inkflow.core.quality.IssueType
import com.inkflow.core.quality.QualityEvaluator
import com.inkflow.core.domain.Chapter
import com.inkflow.core.domain.Character
import com.inkflow.core.domain.Foreshadow
import com.inkflow.core.style.StyleAnalyzer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StyleAnalyzerTest {

    private val shortPunchy = """
    他一脚踹开门。
    血。
    满地都是血。
    「谁干的？」
    「不知道。」她说。
    「那就查。」
    他转身就走，风衣在夜风里翻卷。
    """.trimIndent()

    private val longFlowery = """
    在那个被夕阳染成血色的黄昏里，他独自一人站在高高的山岗之上，俯瞰着脚下那片曾经属于他的、如今却已然易主的广袤土地，心中涌起了难以言喻的复杂情绪。
    风从远方吹来，带着草原特有的、混杂着泥土与青草气息的味道，轻轻地拂过他早已不再年轻的面庞，仿佛在无声地诉说着那些被岁月掩埋的往事与遗憾。
    他想起了很多年前的那个夏天，那时候一切都还那么简单，简单到他以为只要握紧手中的剑，就能够守护住所有他想要守护的东西。
    """.trimIndent()

    @Test
    fun detectsShortSentenceStyle() {
        val dna = StyleAnalyzer.analyze(shortPunchy)
        assertTrue(dna.shortSentenceRatio > 0.5, "短句占比应偏高，实际 ${dna.shortSentenceRatio}")
    }

    @Test
    fun longerSentencesInLongText() {
        val a = StyleAnalyzer.analyze(shortPunchy)
        val b = StyleAnalyzer.analyze(longFlowery)
        assertTrue(b.avgSentenceLength > a.avgSentenceLength, "长句样本平均句长应更大")
        assertTrue(b.longSentenceRatio >= a.longSentenceRatio)
    }

    @Test
    fun unusableWhenSampleTooShort() {
        val dna = StyleAnalyzer.analyze("太短了")
        assertTrue(!dna.isUsable)
    }

    @Test
    fun detectsFirstPerson() {
        val first = "我推开门，看见她坐在窗边。我没有说话，只是看着她。我知道她也在看我，我心里很乱。" +
            "我走过去，坐下，点了一支烟。我问她，你还好吗。她说，还好。我说，那就好。"
        val dna = StyleAnalyzer.analyze(first)
        assertEquals("第一人称", dna.person)
    }

    @Test
    fun detectsThirdPerson() {
        val third = "他推开门，看见她坐在窗边。他没有说话，只是看着她。他知道她也在看他，他心里很乱。" +
            "他走过去，坐下，点了一支烟。他问她，你还好吗。她说，还好。他说，那就好。"
        val dna = StyleAnalyzer.analyze(third)
        assertEquals("第三人称", dna.person)
    }

    @Test
    fun highMatchScoreForSameStyle() {
        val sample = longFlowery + "\n" + longFlowery + "\n" + longFlowery
        val dna = StyleAnalyzer.analyze(sample)
        assertTrue(dna.isUsable)
        val score = StyleAnalyzer.matchScore(longFlowery, dna)
        assertTrue(score >= 70, "同源文本应高分，实际 $score")
    }

    @Test
    fun lowMatchScoreForDifferentStyle() {
        val sample = longFlowery + "\n" + longFlowery + "\n" + longFlowery
        val dna = StyleAnalyzer.analyze(sample)
        val score = StyleAnalyzer.matchScore(shortPunchy, dna)
        assertTrue(score < 70, "风格迥异应低分，实际 $score")
    }

    @Test
    fun promptBlockContainsKeyMetrics() {
        val dna = StyleAnalyzer.analyze(longFlowery + longFlowery)
        val block = dna.toPromptBlock()
        assertTrue(block.contains("文风 DNA"))
        assertTrue(block.contains("平均"))
    }

    @Test
    fun doesNotSplitEllipsis() {
        val sentences = StyleAnalyzer.splitSentences("他犹豫了……然后点了点头。她笑了。")
        assertTrue(sentences.any { it.contains("……") })
    }
}

class QualityEvaluatorTest {

    private fun chapter(content: String, order: Int = 1) = Chapter(
        id = "c$order", projectId = "p", title = "第${order}章", order = order, content = content,
    )

    @Test
    fun healthyTextScoresHigh() {
        val text = buildString {
            repeat(30) { i ->
                appendLine("他推开门，风灌了进来。")
                appendLine("「你来了。」她说。")
                appendLine("他点头，没有说话。桌上的茶还冒着热气。")
                if (i % 5 == 0) appendLine("夜色更深了，远处传来更夫的梆子声。")
            }
        }
        val report = QualityEvaluator.evaluate(chapter(text), foreshadows = emptyList())
        assertTrue(report.totalScore >= 70, "健康文本应 >=70，实际 ${report.totalScore}")
        assertTrue(report.wordCount > 0)
    }

    @Test
    fun detectsOverlongSentence() {
        val longSentence = "他" + "在漫长的岁月里不断地回忆着那些已经逝去的往事和曾经许下的诺言以及所有未能实现的梦想".repeat(4) + "。"
        val text = longSentence + "\n" + longSentence + "\n" + longSentence
        val report = QualityEvaluator.evaluate(chapter(text))
        assertTrue(
            report.issues.any { it.type == IssueType.LongSentence },
            "应检出长句问题，实际问题：${report.issues.map { it.type }}"
        )
    }

    @Test
    fun detectsWordRepetition() {
        val para = "他看着他，他看着他，他看着他说，他看着他的眼睛，他看着他的脸，他看着他的手。".repeat(6)
        val text = para + "\n" + para + "\n" + para
        val report = QualityEvaluator.evaluate(chapter(text))
        assertTrue(report.issues.any { it.type == IssueType.WordRepetition || it.type == IssueType.Vocabulary })
    }

    @Test
    fun overdueForeshadowIsSevere() {
        val foreshadows = listOf(
            Foreshadow(
                id = "f1", projectId = "p", title = "神秘玉佩",
                detail = "主角幼年所得", plantedAt = 2, plannedResolveAt = 10,
                importance = 3,
            )
        )
        val text = "他走在街上。\n" + "路人匆匆而过。\n".repeat(30)
        val report = QualityEvaluator.evaluate(chapter(text, order = 30), foreshadows = foreshadows)
        assertTrue(report.foreshadow.overdue.isNotEmpty(), "应识别逾期伏笔")
        assertTrue(report.issues.any { it.type == IssueType.ForeshadowOverdue })
        assertTrue(report.issues.any { it.severity >= 4 })
    }

    @Test
    fun qualityGateBlocksSevereIssues() {
        val foreshadows = listOf(
            Foreshadow(id = "f1", projectId = "p", title = "断剑", plantedAt = 1, plannedResolveAt = 5, importance = 3)
        )
        val text = "他走着。\n" + "天色渐晚。\n".repeat(40)
        val report = QualityEvaluator.evaluate(chapter(text, order = 40), foreshadows = foreshadows)
        assertTrue(!report.passesGate(minScore = 70), "存在严重问题不应通过门禁：${report.gateReason()}")
    }

    @Test
    fun gateReliesOnScoreWithoutForeshadow() {
        val text = buildString {
            repeat(40) {
                appendLine("他推开门，风灌了进来。")
                appendLine("「你来了。」她说。")
                appendLine("他点头，桌上的茶还冒着热气。")
            }
        }
        val report = QualityEvaluator.evaluate(chapter(text))
        assertTrue(report.passesGate(minScore = 60) || report.totalScore in 0..59, "门禁逻辑应可判定")
    }

    @Test
    fun detectsShortenedCharacterName() {
        val chars = listOf(Character(id = "c", projectId = "p", name = "李明轩", role = "主角"))
        val text = "李 站在门前。李 抬头看了看天。李 叹了口气。" +
            "他走进屋里，坐了下来，思绪万千。".repeat(10)
        val report = QualityEvaluator.evaluate(chapter(text), characters = chars)
        assertTrue(report.coherence.issues.any { it.type == IssueType.CharacterName })
    }

    @Test
    fun lexicalDiversityIsComputable() {
        val diverse = "春风拂柳，夏雨敲荷，秋霜染枫，冬雪压松，四季更迭不休。"
        val monotone = "他走了他走了他走了他走了他走了他走了他走了他走了他走了他走了他走了他走了"
        val a = QualityEvaluator.lexicalDiversity(diverse.repeat(6))
        val b = QualityEvaluator.lexicalDiversity(monotone.repeat(6))
        assertTrue(a > b, "多样文本的 TTR 应高于单调文本：$a vs $b")
    }

    @Test
    fun emptyTextDoesNotCrash() {
        val report = QualityEvaluator.evaluate(chapter(""))
        assertEquals(0, report.wordCount)
        assertTrue(report.totalScore <= 0 || report.totalScore >= 0)
    }

    @Test
    fun detectsSceneBreakBetweenChapters() {
        val prev = "「这把剑……」林逸盯着剑柄上的符文，瞳孔骤缩。他猛地抬头，" +
            "「这不可能！父亲明明说过，此物早已随他一起葬入地底！」\n屋内烛火摇曳，映得他脸色忽明忽暗。"
        // 当前章需 >= 200 字才能进入连续性评估
        val current = ("清晨的阳光洒在集市上，小贩们吆喝叫卖，人来人往好不热闹。" +
            "一个卖糖葫芦的老翁推着车慢慢走过，孩子们跟在后面嬉笑打闹。" +
            "远处酒楼上传来说书先生的声音，讲的正是前朝旧事。" +
            "天气很好，适合出门走走，街上到处都是闲逛的人。").repeat(3)
        val report = QualityEvaluator.evaluate(chapter(current, order = 2), recentTexts = listOf(prev))
        assertTrue(report.continuity.value >= 0, "连续性维度应可评估")
        assertTrue(report.continuity.value < 100, "生硬衔接应扣分，实际 ${report.continuity.value}")
    }


    // ------------------------------------------------------------------
    // AI 腔维度（v1.3.0 新增）
    // ------------------------------------------------------------------

    @Test
    fun aiFlavorDimensionPenalisesClichédText() {
        val cliche = buildString {
            repeat(12) {
                appendLine("她心中五味杂陈，空气仿佛凝固了，月光如水洒在她的脸上。")
                appendLine("他深深地看了她一眼，不禁倒吸一口凉气，瞳孔骤然收缩。")
                appendLine("这一刻，整个世界都安静了，时间仿佛静止，她不由自主地后退了半步。")
            }
        }
        val report = QualityEvaluator.evaluate(chapter(cliche))
        assertTrue(report.issues.any { it.type == IssueType.AiFlavor }, "应检出 AI 腔问题")
        assertTrue(report.aiFlavorRisk > 30, "AI 腔风险应为正，实际 ${report.aiFlavorRisk}")
        assertTrue(report.aiFlavor.value < 70, "AI 腔维度应给低分，实际 ${report.aiFlavor.value}")
    }

    @Test
    fun aiFlavorDimensionRewardsConcreteText() {
        val concrete = buildString {
            repeat(12) {
                appendLine("他把杯子放回桌上，放得很轻，杯底和桌面之间有一下很短的摩擦声。")
                appendLine("窗外的车开过去了，尾灯在墙上亮了一截，然后又暗下去。")
                appendLine("他低头看自己的手，指甲缝里还有昨天修车留下的黑印，洗不干净。")
            }
        }
        val report = QualityEvaluator.evaluate(chapter(concrete))
        assertFalse(report.issues.any { it.type == IssueType.AiFlavor }, "具体文本不应被判定为 AI 腔")
        assertTrue(report.aiFlavorRisk < 30, "AI 腔风险应低，实际 ${report.aiFlavorRisk}")
    }

    @Test
    fun aiFlavorRiskIsMinusOneForTinySample() {
        val report = QualityEvaluator.evaluate(chapter("他推开门。"))
        assertEquals(-1, report.aiFlavorRisk, "样本不足时应返回 -1 而不是 0")
    }

    @Test
    fun aiFlavorAffectsTotalScore() {
        val clean = buildString {
            repeat(12) {
                appendLine("他把烟按灭在没抽完的位置，起身时椅子腿在地上刮出一声。")
                appendLine("外面在下雨，雨点打在铁皮雨棚上，声音很密。")
                appendLine("他数了数桌上的零钱，一共七张，其中两张是破的。")
            }
        }
        val cliche = buildString {
            repeat(12) {
                appendLine("他心中五味杂陈，深深地叹了口气，不由自主地望向窗外。")
                appendLine("空气仿佛凝固了，时间仿佛静止，整个世界都安静了下来。")
                appendLine("这一刻，他仿佛感受到了某种难以形容的情感，思绪万千。")
            }
        }
        val cleanScore = QualityEvaluator.evaluate(chapter(clean)).totalScore
        val clicheScore = QualityEvaluator.evaluate(chapter(cliche)).totalScore
        assertTrue(cleanScore > clicheScore, "套话文本总分应低于具体文本：干净 $cleanScore vs 套话 $clicheScore")
    }

    @Test
    fun aiFlavorIsIndependentOfOtherDimensions() {
        // 情节连贯、标点规范、句长合适，但通篇套话——这正是 AI 腔独立成维的理由
        val text = buildString {
            repeat(15) {
                appendLine("他走进房间，看见了桌上的信，然后坐了下来。")
                appendLine("他心中五味杂陈，深深地叹了口气，不由自主地看着那封信。")
                appendLine("空气仿佛凝固了，时间仿佛静止，整个世界都安静了下来。")
            }
        }
        val report = QualityEvaluator.evaluate(chapter(text))
        assertTrue(report.coherence.value > 60, "连贯性不应因套话而崩")
        assertTrue(report.issues.any { it.type == IssueType.AiFlavor }, "但 AI 腔维度必须报警")
    }
}
