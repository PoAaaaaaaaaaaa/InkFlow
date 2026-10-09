package com.inkflow.core

import com.inkflow.core.agent.AgentPipeline
import com.inkflow.core.corpus.Blueprint
import com.inkflow.core.corpus.BlueprintStructure
import com.inkflow.core.corpus.DepthProfile
import com.inkflow.core.corpus.DepthResolver
import com.inkflow.core.corpus.DepthTier
import com.inkflow.core.corpus.IntakeQuestionnaire
import com.inkflow.core.corpus.QuestionKind
import com.inkflow.core.corpus.VoiceLibrary
import com.inkflow.core.corpus.VoiceProfile
import com.inkflow.core.ai.AiCapability
import com.inkflow.core.ai.AiEngine
import com.inkflow.core.ai.AiRequest
import com.inkflow.core.ai.AiResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 建作流程四件功能的测试：
 *  1. 提问生成（本地规则必须独立可用）
 *  2. 蓝图展开（结构参数由代码算，不信模型）
 *  3. 声纹提取（特征而非原文）
 *  4. 深度解析（模糊偏好 → 可执行参数）
 */
class IntakeAndBlueprintTest {

    // ==================================================================
    // 一、提问生成
    // ==================================================================

    @Test
    fun emptyInputProducesRequiredQuestions() {
        val qs = IntakeQuestionnaire.localQuestions(mapOf("title" to "测试作品"))
        assertTrue("空白输入必须产生追问", qs.isNotEmpty())
        assertTrue("必须有必答题", qs.any { it.isRequired })
    }

    @Test
    fun everyQuestionExplainsWhy() {
        // 「为什么要问」不能省——作者知道影响什么才会认真答
        val qs = IntakeQuestionnaire.localQuestions(mapOf("title" to "测试"))
        qs.forEach {
            assertTrue("问题「${it.question}」缺少 why", it.why.isNotBlank())
            assertTrue("why 过短：${it.why}", it.why.length >= 15)
        }
    }

    @Test
    fun detectsMissingProtagonist() {
        val qs = IntakeQuestionnaire.localQuestions(
            mapOf(
                "title" to "测试",
                "logline" to "一个关于复仇的故事，发生在遥远的北方雪原上",
                "premise" to "这个世界由九座浮空城构成，每座城都有独立的法则",
                "genre" to "玄幻",
                "targetWords" to "1000000",
            )
        )
        assertTrue("没有主角时必须追问", qs.any { it.field == "protagonistProfile" })
    }

    @Test
    fun detectsPresentProtagonist() {
        val qs = IntakeQuestionnaire.localQuestions(
            mapOf(
                "title" to "测试",
                "logline" to "主角叫姜知序，穿越成奴隶",
                "premise" to "穿越到文明游戏世界，核心冲突是与老城主的对抗",
                "genre" to "科幻",
                "targetWords" to "1000000",
                "tone" to "爽文",
            )
        )
        assertFalse("已写明主角时不应重复追问", qs.any { it.field == "protagonistProfile" })
    }

    @Test
    fun detectsMissingConflict() {
        val qs = IntakeQuestionnaire.localQuestions(
            mapOf(
                "title" to "测试",
                "logline" to "主角叫林叶，回到深山老家躺平",
                "premise" to "他从代码到物理无一不通，打算安静地造点东西",
                "genre" to "都市",
                "targetWords" to "1000000",
            )
        )
        assertTrue("没有对抗时必须追问", qs.any { it.field == "coreConflict" })
    }

    @Test
    fun questionsAreOrderedByPriority() {
        val qs = IntakeQuestionnaire.localQuestions(mapOf("title" to "测试"))
        val priorities = qs.map { it.priority }
        assertEquals("问题必须按优先级排序", priorities.sorted(), priorities)
    }

    @Test
    fun sessionTracksProgressAndReadiness() {
        val session = IntakeQuestionnaire.build("p1", mapOf("title" to "测试"))
        assertTrue("初始状态不应就绪", !session.readyForBlueprint)
        assertEquals(0, session.answeredCount)

        // 答完全部必答题
        var s = session
        session.required.forEach { s = s.answer(it.field, "测试答案内容") }
        assertTrue("必答题答完后应就绪", s.readyForBlueprint)
        assertTrue(s.progress > 0)
    }

    @Test
    fun blankAnswerDoesNotCountAsReady() {
        val session = IntakeQuestionnaire.build("p1", mapOf("title" to "测试"))
        var s = session
        session.required.forEach { s = s.answer(it.field, "   ") }
        assertFalse("空白答案不算已回答", s.readyForBlueprint)
    }

    @Test
    fun sessionContextBlockIncludesAnswers() {
        val session = IntakeQuestionnaire.build("p1", mapOf("title" to "测试"))
            .answer("protagonistProfile", "姜知序，饿死的奴隶")
            .answer("coreConflict", "与老城主对抗")
        val block = session.toContextBlock()
        assertTrue(block.contains("姜知序"))
        assertTrue(block.contains("老城主"))
    }

    @Test
    fun aiQuestionWithSameFieldIsDeduplicated() {
        val existing = mapOf("title" to "测试", "genre" to "玄幻")
        val local = IntakeQuestionnaire.localQuestions(existing)
        val targetField = local.first().field
        val aiDup = com.inkflow.core.corpus.IntakeQuestion(
            id = "q_ai_dup", field = targetField,
            question = "换个说法再问一遍", why = "重复追问会消耗用户耐心",
            origin = "ai",
        )
        val session = IntakeQuestionnaire.build("p1", existing, listOf(aiDup))
        assertEquals(
            "同 field 的 AI 追问应被去重",
            1, session.questions.count { it.field == targetField },
        )
    }

    @Test
    fun aiQuestionSurvivesWhenFieldIsNew() {
        val existing = mapOf("title" to "测试")
        val aiQ = com.inkflow.core.corpus.IntakeQuestion(
            id = "q_ai_new", field = "powerCeiling",
            question = "这个世界的力量上限在哪？", why = "上限决定后期冲突的规模",
            kind = QuestionKind.Choice, options = listOf("单城", "一界", "多元宇宙"),
            origin = "ai",
        )
        val session = IntakeQuestionnaire.build("p1", existing, listOf(aiQ))
        assertTrue("新维度的 AI 追问应保留", session.questions.any { it.field == "powerCeiling" })
    }

    @Test
    fun realGenreSkipsGoldenFingerQuestion() {
        val qs = IntakeQuestionnaire.localQuestions(
            mapOf("title" to "测试", "genre" to "现实题材")
        )
        assertFalse("现实题材不该追问金手指", qs.any { it.field == "goldenFinger" })
    }

    // ==================================================================
    // 二、蓝图结构推导
    // ==================================================================

    @Test
    fun structureScalesWithTargetWords() {
        val small = BlueprintStructure.of(300_000)
        val large = BlueprintStructure.of(3_000_000)
        assertTrue("字数越多章节越多", large.chapterCount > small.chapterCount)
        assertTrue("字数越多卷数越多", large.volumeCount > small.volumeCount)
    }

    @Test
    fun structureChapterCountMatchesTarget() {
        // 章数 × 单章字数 必须能覆盖目标字数，不能少算
        listOf(300_000L, 1_000_000L, 3_000_000L, 5_000_000L).forEach { target ->
            val s = BlueprintStructure.of(target)
            val covered = s.chapterCount.toLong() * s.wordsPerChapter
            assertTrue(
                "目标 $target 字：$s.chapterCount 章 × ${s.wordsPerChapter} 字 = $covered，覆盖不足",
                covered >= target,
            )
        }
    }

    @Test
    fun structureVolumeDivisionIsSane() {
        // 每卷章数应落在商业连载的通行区间，不能出现 3 章一卷或 500 章一卷
        listOf(200_000L, 500_000L, 1_000_000L, 2_000_000L, 4_000_000L).forEach { target ->
            val s = BlueprintStructure.of(target)
            if (s.volumeCount > 1) {
                assertTrue(
                    "目标 $target：每卷 ${s.chaptersPerVolume} 章，超出合理区间",
                    s.chaptersPerVolume in 8..60,
                )
            }
        }
    }

    @Test
    fun shortStoryIsSingleVolume() {
        val s = BlueprintStructure.of(60_000)
        assertEquals("短篇应只有一卷", 1, s.volumeCount)
    }

    @Test
    fun chapterLengthBiasAffectsStructure() {
        val short = BlueprintStructure.of(1_000_000, chapterLengthBias = -1)
        val long = BlueprintStructure.of(1_000_000, chapterLengthBias = 1)
        assertTrue("偏短偏好应产生更多章", short.chapterCount > long.chapterCount)
    }

    @Test
    fun actBreakdownIsProduced() {
        val s = BlueprintStructure.of(1_000_000)
        val acts = s.actBreakdown
        assertTrue(acts.contains("第一幕"))
        assertTrue(acts.contains("第三幕"))
    }

    // ==================================================================
    // 三、声纹提取
    // ==================================================================

    private val punchySample = buildString {
        repeat(60) {
            appendLine("他推开门。")
            appendLine("风灌了进来。")
            appendLine("「你来了。」她说。")
            appendLine("他点头。桌上茶还冒着热气。")
        }
    }

    private val flowingSample = buildString {
        repeat(40) {
            appendLine(
                "在那个被夕阳染成血色的黄昏里，他独自一人站在高高的山岗之上，" +
                    "俯瞰着脚下那片曾经属于他的、如今却已然易主的广袤土地，心中涌起了难以言喻的复杂情绪。"
            )
            appendLine(
                "风从远方吹来，带着草原特有的、混杂着泥土与青草气息的味道，" +
                    "轻轻地拂过他早已不再年轻的面庞，仿佛在无声地诉说着那些被岁月掩埋的往事与遗憾。"
            )
        }
    }

    @Test
    fun extractsShortSentenceVoice() {
        val v = VoiceLibrary.analyze(punchySample, "短句样本")
        assertTrue("应识别出短句为主，实际平均 ${v.avgSentenceLength}", v.avgSentenceLength < 12)
        assertTrue("短句占比应高，实际 ${v.shortRatio}", v.shortRatio > 0.5)
    }

    @Test
    fun extractsLongSentenceVoice() {
        val v = VoiceLibrary.analyze(flowingSample, "长句样本")
        assertTrue("应识别出长句为主，实际平均 ${v.avgSentenceLength}", v.avgSentenceLength > 20)
        assertTrue("长句占比应高，实际 ${v.longRatio}", v.longRatio > 0.2)
    }

    @Test
    fun voiceSeparatesDialogueHeavyFromNarrationHeavy() {
        val dialogueHeavy = VoiceLibrary.analyze(punchySample, "对话多")
        val narrationHeavy = VoiceLibrary.analyze(flowingSample, "叙述多")
        assertTrue(
            "对话占比应能区分两类文本：${dialogueHeavy.dialogueRatio} vs ${narrationHeavy.dialogueRatio}",
            dialogueHeavy.dialogueRatio > narrationHeavy.dialogueRatio,
        )
    }

    @Test
    fun voiceColloquialismDiffersByStyle() {
        val colloquial = VoiceLibrary.analyze(punchySample, "口语")
        val formal = VoiceLibrary.analyze(flowingSample, "书面")
        assertTrue(
            "口语化程度应能区分：${colloquial.colloquialism} vs ${formal.colloquialism}",
            colloquial.colloquialism > formal.colloquialism,
        )
    }

    @Test
    fun lowSampleIsMarkedUnreliable() {
        val v = VoiceLibrary.analyze("他推开门。风很大。" + "他走了出去。".repeat(5), "小样本")
        assertFalse("小样本必须标为不可靠", v.isReliable)
        assertTrue("应给出可信度说明", v.reliabilityLabel.isNotBlank())
    }

    @Test
    fun largeSampleIsMarkedReliable() {
        // 需要超过 1 万字才算可靠样本
        val big = "他推开门，风灌了进来。她坐在桌边，没有说话，只是把杯子往他那边推了推。" + "\n"
        val v = VoiceLibrary.analyze(big.repeat(700), "大样本")
        assertTrue("样本应超过可信门槛，实际 ${v.sampleChars} 字", v.sampleChars >= 10_000)
        assertTrue("大样本应标为可靠，实际 ${v.sampleChars} 字", v.isReliable)
    }

    @Test
    fun promptBlockIncludesReliabilityCaveat() {
        val weak = VoiceLibrary.analyze("他推开门。风很大。".repeat(20), "弱样本")
        val block = weak.toPromptBlock()
        if (weak.sampleChars >= 2000 && !weak.isReliable) {
            assertTrue("不可靠样本必须在提示词里标注", block.contains("样本量偏小"))
        }
    }

    @Test
    fun builtInVoicesAreAllLabelled() {
        // 内置声纹来自公开简介，样本量小——必须诚实标注来源
        VoiceLibrary.builtInProfiles.forEach { v ->
            assertTrue("内置声纹「${v.name}」缺少来源说明", v.source.isNotBlank())
            assertTrue("内置声纹「${v.name}」缺少题材", v.genre.isNotBlank())
        }
    }

    @Test
    fun blendWeightsBySampleSize() {
        val small = VoiceProfile(name = "小", sampleChars = 1_000, avgSentenceLength = 10.0)
        val large = VoiceProfile(name = "大", sampleChars = 100_000, avgSentenceLength = 30.0)
        val blended = VoiceLibrary.blend(listOf(small, large), "混合")
        // 大样本权重应占绝对主导
        assertTrue("混合结果应偏向大样本，实际 ${blended.avgSentenceLength}", blended.avgSentenceLength > 25)
    }

    @Test
    fun bestMatchRespectsDepth() {
        val deep = DepthProfile(level = 92)
        val shallow = DepthProfile(level = 10)
        val deepMatch = VoiceLibrary.bestMatchFor(deep, "现实题材")
        val shallowMatch = VoiceLibrary.bestMatchFor(shallow, "科幻末世")
        assertNotNull(deepMatch)
        assertNotNull(shallowMatch)
        assertTrue(
            "深度档应匹配长句声纹：${deepMatch!!.name} (${deepMatch.avgSentenceLength})",
            deepMatch.avgSentenceLength > shallowMatch!!.avgSentenceLength,
        )
    }

    @Test
    fun importAndRemoveWorks() {
        val text = "他推开门，风灌了进来。她没有回头。" .repeat(300)
        val v = VoiceLibrary.extract(text, "导入测试", source = "单元测试")
        assertTrue("应能建立声纹", v.sampleChars > 1000)
        assertNotNull("应能查到刚导入的声纹", VoiceLibrary.find("导入测试"))
        assertTrue(VoiceLibrary.removeImported("导入测试"))
        assertNull("删除后应查不到", VoiceLibrary.find("导入测试"))
    }

    @Test
    fun metricNotesCoverAllDisplayedMetrics() {
        val notes = VoiceLibrary.metricNotes()
        assertTrue(notes.size >= 5)
        notes.forEach { (k, v) ->
            assertTrue("指标「$k」缺少说明", v.isNotBlank())
        }
    }

    // ==================================================================
    // 四、深度解析
    // ==================================================================

    @Test
    fun tierMappingIsMonotonic() {
        val sorted = DepthTier.entries.sortedBy { it.level }
        assertEquals("档位必须按 level 单调排列", DepthTier.entries.toList(), sorted)
        assertTrue(sorted.first().level < sorted.last().level)
    }

    @Test
    fun tierRoundTrip() {
        DepthTier.entries.forEach {
            assertEquals(it, DepthTier.of(it.level))
        }
    }

    @Test
    fun inferPushesRefreshingGenreShallower() {
        val pulp = DepthProfile.infer("东方玄幻", "爽文", "男频")
        val literary = DepthProfile.infer("现实题材", "悬疑", "青年向")
        assertTrue("爽文玄幻应比现实悬疑浅：${pulp.level} vs ${literary.level}", pulp.level < literary.level)
    }

    @Test
    fun pacingAnswerOverridesInference() {
        val base = DepthResolver.resolve("都市", "热血", "男频", emptyMap())
        val fast = DepthResolver.resolve("都市", "热血", "男频", mapOf("pacingPreference" to "快节奏，每章都要有进展"))
        val slow = DepthResolver.resolve("都市", "热血", "男频", mapOf("pacingPreference" to "慢热，重氛围与人物"))
        assertTrue("快节奏应降低深度档：${base.level} → ${fast.level}", fast.level < base.level)
        assertTrue("慢热应提高深度档：${base.level} → ${slow.level}", slow.level > base.level)
    }

    @Test
    fun readabilityAnswerHasStrongestEffect() {
        val plain = DepthResolver.resolve("玄幻", "热血", "男频", mapOf("readability" to "越通俗越好懂"))
        val deep = DepthResolver.resolve("玄幻", "热血", "男频", mapOf("readability" to "要深度，有回味"))
        assertTrue("通俗表态应显著降低深度：${plain.level} vs ${deep.level}", deep.level - plain.level > 30)
        assertTrue("通俗向必须开类比讲解", plain.analogies)
        assertTrue("深度向必须关类比讲解", !deep.analogies)
    }

    @Test
    fun depthIsClampedToValidRange() {
        val extreme = DepthResolver.resolve(
            "现实", "悬疑", "青年向",
            mapOf("pacingPreference" to "慢热", "readability" to "要深度", "endingDirection" to "开放式"),
        )
        assertTrue("深度必须落在 5..95 内，实际 ${extreme.level}", extreme.level in 5..95)
    }

    @Test
    fun depthProfileProducesExecutableRules() {
        val block = DepthProfile(level = 20).toPromptBlock()
        assertTrue("必须给出句长数值", block.contains("字左右"))
        assertTrue("必须给出段落数值", block.contains("段落"))
        assertTrue("必须说明比喻密度", block.contains("比喻密度"))
        assertTrue("必须说明留白规则", block.contains("留白"))
    }

    @Test
    fun shallowAndDeepProfilesDifferConcretely() {
        val shallow = DepthProfile(level = 10, analogies = true, ambiguity = false).toPromptBlock()
        val deep = DepthProfile(level = 90, analogies = false, ambiguity = true).toPromptBlock()
        assertTrue("浅档应要求短句", shallow.contains("短句"))
        assertTrue("浅档应开类比", shallow.contains("**需要**"))
        assertTrue("浅档应禁止留白", shallow.contains("**不允许**"))
        assertTrue("深档应关类比", deep.contains("**不需要**"))
        assertTrue("深档应允许留白", deep.contains("**允许**"))
    }

    @Test
    fun sentenceLengthTargetGrowsWithLevel() {
        assertTrue(
            DepthProfile(level = 10).sentenceLengthTarget < DepthProfile(level = 90).sentenceLengthTarget
        )
    }

    @Test
    fun chapterLengthBiasResolves() {
        assertEquals(-1, DepthResolver.chapterLengthBias(mapOf("pacingPreference" to "快节奏，每章都要有进展")))
        assertEquals(1, DepthResolver.chapterLengthBias(mapOf("pacingPreference" to "慢热，重氛围与人物")))
        assertEquals(0, DepthResolver.chapterLengthBias(emptyMap()))
    }

    @Test
    fun presetsCoverEveryTier() {
        val presets = DepthProfile.presets
        assertEquals("具名档数量应与枚举一致", DepthTier.entries.size, presets.size)
        DepthTier.entries.forEach { tier ->
            assertTrue("缺少档位 ${tier.label}", presets.any { it.tier == tier })
        }
    }

    // ==================================================================
    // 五、蓝图展开（用假引擎跑通全流程）
    // ==================================================================

    private class BlueprintEngine : AiEngine {
        override val id = "fake"
        override val displayName = "测试引擎"
        override val isOnDevice = true
        override fun capabilities() = setOf(AiCapability.LongFormGeneration, AiCapability.StructuredOutput)
        override suspend fun isAvailable() = true

        override suspend fun complete(request: AiRequest): AiResponse {
            val sys = request.system
            val text = when {
                sys.contains("世界观架构师") -> """
                    [{"category":"力量体系","name":"九境","content":"剑修分九境，每突破一境需斩断一段记忆","tags":"修炼"},
                     {"category":"势力","name":"三大宗门","content":"三百年前联手抹除剑神道统，如今互相制衡","tags":"反派"}]
                """.trimIndent()

                sys.contains("人物设定师") -> """
                    [{"name":"陈默","role":"主角","gender":"男","age":"十九","personality":"沉默但记性好",
                      "background":"剑神转世，记忆被封印","goal":"找回被抹去的道统","arc":"从逃避到承担",
                      "relationships":"与苏离亦敌亦友"}]
                """.trimIndent()

                sys.contains("伏笔设计师") -> """
                    [{"title":"半柄断剑","detail":"主角捡到的断剑会在他愤怒时发热","plantedAt":2,"plannedResolveAt":40,"importance":3},
                     {"title":"缺失的记忆","detail":"他总觉得自己忘了什么重要的事","plantedAt":1,"plannedResolveAt":60,"importance":2}]
                """.trimIndent()

                sys.contains("长篇结构设计师") -> """
                    [{"title":"断剑归鞘","function":"铺垫：建立人物与核心矛盾","synopsis":"主角在废墟中捡到断剑，被卷入宗门争斗"},
                     {"title":"记忆之价","function":"高潮：对决与回收","synopsis":"九境大成之日，他必须面对自己斩掉的记忆"}]
                """.trimIndent()

                sys.contains("章节细纲编剧") -> {
                    val start = Regex("第 (\\d+) 章到").find(request.prompt)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                    val end = Regex("到第 (\\d+) 章").find(request.prompt)?.groupValues?.get(1)?.toIntOrNull() ?: 5
                    (start..end).joinToString(",", "[", "]") { o ->
                        """{"order":$o,"title":"第${o}章测试","event":"主角做了一件事","conflict":"有人阻止他",
                           "emotion":"从平静到紧张","hook":"他发现断剑在发热","foreshadow":"断剑","characters":["陈默"]}"""
                    }
                }

                else -> "{}"
            }
            return AiResponse(text = text, engineId = id)
        }

        override fun stream(request: AiRequest): Flow<String> = flow { emit(complete(request).text) }
    }

    @Test
    fun expandBlueprintProducesCompleteStructure() = runTest {
        val pipeline = AgentPipeline(BlueprintEngine())
        val result = pipeline.expandBlueprint(
            projectId = "p1",
            title = "断剑",
            genre = "武侠仙侠",
            tone = "热血",
            audience = "男频",
            logline = "落魄少年捡到半柄断剑",
            premise = "剑修分九境，每突破一境要斩断记忆",
            targetWords = 300_000,
        )
        val bp = result.output

        assertTrue("应生成世界观设定", bp.settings.isNotEmpty())
        assertTrue("应生成角色", bp.characters.isNotEmpty())
        assertTrue("应生成伏笔", bp.foreshadows.isNotEmpty())
        assertTrue("应生成分卷", bp.volumes.isNotEmpty())
        assertTrue("应生成章节细纲", bp.totalChapters > 0)
    }

    @Test
    fun blueprintChapterNumbersAreContinuous() = runTest {
        val pipeline = AgentPipeline(BlueprintEngine())
        val bp = pipeline.expandBlueprint(
            projectId = "p1", title = "断剑", genre = "武侠", tone = "热血", audience = "男频",
            logline = "少年捡到断剑", premise = "剑修九境", targetWords = 300_000,
        ).output

        val orders = bp.allChapters.map { it.order }
        assertEquals("章节序号不能有缺口", (1..bp.totalChapters).toList(), orders.sorted())
        assertEquals("章节数应与结构参数一致", bp.structure.chapterCount, bp.totalChapters)
    }

    @Test
    fun blueprintForeshadowResolveIsNotTooEarly() = runTest {
        val pipeline = AgentPipeline(BlueprintEngine())
        val bp = pipeline.expandBlueprint(
            projectId = "p1", title = "断剑", genre = "武侠", tone = "热血", audience = "男频",
            logline = "少年捡到断剑", premise = "剑修九境", targetWords = 300_000,
        ).output

        bp.foreshadows.forEach { f ->
            assertTrue(
                "伏笔「${f.title}」回收(${f.plannedResolveAt}) 早于埋设(${f.plantedAt})+3",
                f.plannedResolveAt == 0 || f.plannedResolveAt >= f.plantedAt + 3,
            )
        }
    }

    @Test
    fun blueprintDepthIsResolvedFromAnswers() = runTest {
        val pipeline = AgentPipeline(BlueprintEngine())
        val bp = pipeline.expandBlueprint(
            projectId = "p1", title = "断剑", genre = "玄幻", tone = "爽文", audience = "男频",
            logline = "少年捡到断剑", premise = "剑修九境", targetWords = 300_000,
            answers = mapOf("readability" to "越通俗越好懂"),
        ).output
        assertTrue("通俗表态应体现在深度档里，实际 ${bp.depth.level}", bp.depth.level < 40)
        assertTrue("通俗向应开类比", bp.depth.analogies)
    }

    @Test
    fun blueprintDegradesGracefullyWhenEngineFails() = runTest {
        // 引擎对所有请求都返回空 JSON —— 蓝图必须仍然可用（结构完整、内容待补）
        val brokenEngine = object : AiEngine {
            override val id = "broken"
            override val displayName = "坏引擎"
            override val isOnDevice = true
            override fun capabilities() = setOf(AiCapability.LongFormGeneration)
            override suspend fun isAvailable() = true
            override suspend fun complete(request: AiRequest) = AiResponse("{}", id)
            override fun stream(request: AiRequest): Flow<String> = flow { emit("{}") }
        }
        val bp = AgentPipeline(brokenEngine).expandBlueprint(
            projectId = "p1", title = "断剑", genre = "武侠", tone = "热血", audience = "男频",
            logline = "少年捡到断剑", premise = "剑修九境", targetWords = 300_000,
        ).output

        assertTrue("引擎全坏时结构仍必须完整", bp.isComplete)
        assertEquals("章节数不受引擎影响", bp.structure.chapterCount, bp.totalChapters)
        assertTrue("内容为占位", bp.detailedChapters < bp.totalChapters || bp.characters.isEmpty())
    }

    @Test
    fun blueprintReadableTextContainsAllSections() = runTest {
        val pipeline = AgentPipeline(BlueprintEngine())
        val bp = pipeline.expandBlueprint(
            projectId = "p1", title = "断剑", genre = "武侠", tone = "热血", audience = "男频",
            logline = "少年捡到断剑", premise = "剑修九境", targetWords = 300_000,
        ).output
        val text = bp.toReadableText()
        assertTrue(text.contains("世界观设定"))
        assertTrue(text.contains("角色"))
        assertTrue(text.contains("伏笔台账"))
        assertTrue(text.contains("第1章"))
    }

    @Test
    fun blueprintProgressIsReported() = runTest {
        val pipeline = AgentPipeline(BlueprintEngine())
        val stages = mutableListOf<String>()
        pipeline.expandBlueprint(
            projectId = "p1", title = "断剑", genre = "武侠", tone = "热血", audience = "男频",
            logline = "少年捡到断剑", premise = "剑修九境", targetWords = 300_000,
            onProgress = { stage, _ -> stages += stage },
        )
        assertTrue("应报告多个阶段", stages.size >= 5)
        assertTrue("应报告世界观阶段", stages.any { it.contains("世界观") })
        assertTrue("应报告完成", stages.any { it.contains("完成") })
    }

    @Test
    fun blueprintDetailProgressIsBetweenZeroAndOne() = runTest {
        val pipeline = AgentPipeline(BlueprintEngine())
        val bp = pipeline.expandBlueprint(
            projectId = "p1", title = "断剑", genre = "武侠", tone = "热血", audience = "男频",
            logline = "少年捡到断剑", premise = "剑修九境", targetWords = 300_000,
        ).output
        assertTrue(bp.detailProgress in 0.0..1.0)
    }

    // ==================================================================
    // 六、提问生成的管线集成
    // ==================================================================

    @Test
    fun intakeQuestionsWorkWithBrokenEngine() = runTest {
        val brokenEngine = object : AiEngine {
            override val id = "broken"
            override val displayName = "坏引擎"
            override val isOnDevice = true
            override fun capabilities() = setOf(AiCapability.LongFormGeneration)
            override suspend fun isAvailable() = true
            override suspend fun complete(request: AiRequest) = AiResponse("不是 JSON", id)
            override fun stream(request: AiRequest): Flow<String> = flow { emit("不是 JSON") }
        }
        val result = AgentPipeline(brokenEngine).generateIntakeQuestions(
            title = "测试", genre = "玄幻", logline = "", premise = "", targetWords = "",
        )
        assertTrue("引擎坏掉时本地提问必须照常返回", result.output.isNotEmpty())
        assertTrue("应标记为降级", result.degraded)
    }

    @Test
    fun intakeQuestionsMergeAiResults() = runTest {
        val engine = object : AiEngine {
            override val id = "ai"
            override val displayName = "AI"
            override val isOnDevice = true
            override fun capabilities() = setOf(AiCapability.StructuredOutput)
            override suspend fun isAvailable() = true
            override suspend fun complete(request: AiRequest) = AiResponse(
                """[{"field":"powerCeiling","question":"力量上限在哪？","why":"决定后期冲突规模","kind":"choice","options":["单城","一界"],"priority":2}]""",
                id,
            )
            override fun stream(request: AiRequest): Flow<String> = flow { emit("[]") }
        }
        val result = AgentPipeline(engine).generateIntakeQuestions(
            title = "测试", genre = "玄幻", logline = "主角叫陈默", premise = "冲突：与宗门对抗", targetWords = "1000000",
        )
        assertTrue("AI 追问应被合并", result.output.any { it.field == "powerCeiling" })
        assertTrue("本地追问也应在", result.output.any { it.origin == "local" })
    }
}
