package com.inkflow.core

import com.inkflow.core.agent.AgentPipeline
import com.inkflow.core.ai.AiCapability
import com.inkflow.core.ai.AiEngine
import com.inkflow.core.ai.AiRequest
import com.inkflow.core.ai.AiResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 测试用假引擎：按「提示词包含的关键词」返回预设响应，
 * 从而在不依赖真实模型的前提下验证整条 Agent 管线的编排逻辑。
 */
class FakeEngine(
    private val responses: List<Pair<String, String>>,
    override val id: String = "fake",
    override val displayName: String = "测试引擎",
    override val isOnDevice: Boolean = true,
) : AiEngine {

    val calls = mutableListOf<AiRequest>()

    override fun capabilities(): Set<AiCapability> = AiCapability.entries.toSet()

    override suspend fun isAvailable(): Boolean = true

    override suspend fun complete(request: AiRequest): AiResponse {
        calls += request
        val hit = responses.firstOrNull { request.prompt.contains(it.first) || request.system.contains(it.first) }
        return AiResponse(
            text = hit?.second ?: "默认输出",
            engineId = id,
            latencyMs = 1,
        )
    }

    override fun stream(request: AiRequest): Flow<String> = flow {
        calls += request
        emit("流式")
        emit("输出")
    }
}

class AgentPipelineTest {

    @Test
    fun plannerParsesVolumesAndChapters() = runTest {
        val raw = """
            # 第一卷 初入宗门
            第1章 石阶九千级：林逸拜入青云门，初见苏晴。
            第2章 藏经阁夜话：得知父亲失踪线索。
            # 第二卷 风起北境
            第3章 断魂崖：遭遇伏击，玉佩发光。
        """.trimIndent()
        val engine = FakeEngine(listOf("结构设计师" to raw))
        val pipeline = AgentPipeline(engine)

        val result = pipeline.planNovel(
            projectTitle = "青云剑歌", genre = "东方玄幻", logline = "少年寻父",
            premise = "修真世界", targetWords = 1_000_000L,
        )

        assertEquals(2, result.output.volumes.size, "应解析出 2 卷")
        assertEquals(2, result.output.volumes[0].chapters.size)
        assertEquals("石阶九千级", result.output.volumes[0].chapters[0].title)
        assertEquals(3, result.output.volumes[1].chapters[0].order)
    }

    @Test
    fun fallsBackToSingleVolumeOnBadFormat() = runTest {
        val engine = FakeEngine(listOf("结构设计师" to "随便写点什么，没有章节号。"))
        val pipeline = AgentPipeline(engine)
        val result = pipeline.planNovel("书", "玄幻", "", "", 1_000_000L)
        assertTrue(result.output.volumes.isNotEmpty(), "应降级为单卷")
    }

    @Test
    fun writerCleansPreambleAndTrailingNotes() = runTest {
        val body = "好的，以下是本章正文\n" + "他一脚踹开门。\n血。\n满地都是血。\n---\n希望这个版本符合你的期待。"
        val engine = FakeEngine(listOf("请写出本章正文" to body))
        val pipeline = AgentPipeline(engine)

        val result = pipeline.writeChapter(
            projectTitle = "书", chapterOrder = 1, chapterTitle = "开端",
            outline = "冲突", styleBlock = "", contextBlock = "",
            previousHandoff = "", targetWords = 1000,
        )

        assertTrue(!result.output.contains("好的，以下是"), "应去掉开场白")
        assertTrue(!result.output.contains("希望这个"), "应去掉尾部说明")
        assertTrue(result.output.contains("他一脚踹开门"))
    }

    @Test
    fun injectsStyleDnaAndContextIntoSystemPrompt() = runTest {
        val engine = FakeEngine(listOf("请写出本章正文" to "正文内容"))
        val pipeline = AgentPipeline(engine)
        pipeline.writeChapter(
            projectTitle = "书", chapterOrder = 3, chapterTitle = "夜袭",
            outline = "刺客来袭", styleBlock = "【文风 DNA】平均句长 9 字",
            contextBlock = "### 相关设定\n- 青云门位于北境",
            previousHandoff = "林逸刚得到玉佩", targetWords = 2000,
        )
        val system = engine.calls.last().system
        assertTrue(system.contains("文风 DNA"), "文风块应注入 system")
        assertTrue(system.contains("青云门"), "检索语境应注入 system")
        assertTrue(engine.calls.last().prompt.contains("林逸刚得到玉佩"), "交接笔记应注入 user")
    }

    @Test
    fun parsesConsistencyFindingsFromJson() = runTest {
        val json = """
            ```json
            [
              {"severity":3,"type":"外貌冲突","evidence":"他左眼是蓝色的","conflict":"档案记载右眼蓝","fix":"改为右眼"},
              {"severity":2,"type":"称呼混乱","evidence":"称苏晴为苏师姐","conflict":"档案中为师妹","fix":"统一为师妹"}
            ]
            ```
        """.trimIndent()
        val engine = FakeEngine(listOf("设定审校员" to json))
        val pipeline = AgentPipeline(engine)

        val result = pipeline.checkConsistency("档案……", "正文……")
        assertEquals(2, result.output.size)
        assertEquals(3, result.output[0].severity)
        assertEquals("外貌冲突", result.output[0].type)
    }

    @Test
    fun consistencyReturnsEmptyOnDirtyOutput() = runTest {
        val engine = FakeEngine(listOf("设定审校员" to "我没发现问题，一切正常。"))
        val pipeline = AgentPipeline(engine)
        val result = pipeline.checkConsistency("a", "b")
        assertTrue(result.output.isEmpty())
    }

    @Test
    fun parsesProofreadFixes() = runTest {
        val json = """[{"offset":5,"original":"他一脚揣开门","corrected":"他一脚踹开门","reason":"错别字"}]"""
        val engine = FakeEngine(listOf("校对员" to json))
        val pipeline = AgentPipeline(engine)
        val result = pipeline.proofread("他一脚揣开门")
        assertEquals(1, result.output.size)
        assertEquals("他一脚踹开门", result.output[0].corrected)
    }

    @Test
    fun parsesReviewVerdict() = runTest {
        val json = """
            {"score":78,"verdict":"开篇略慢但钩子扎实","strengths":["对话有个性"],
             "problems":[{"severity":2,"issue":"前三段背景介绍过多","fix":"把背景拆散到动作里"}],
             "hookStrength":82}
        """.trimIndent()
        val engine = FakeEngine(listOf("严苛的网文主编" to json))
        val pipeline = AgentPipeline(engine)
        val result = pipeline.review("第1章", "他推开门。".repeat(50))
        assertEquals(78, result.output.score)
        assertEquals(1, result.output.problems.size)
        assertEquals(82, result.output.hookStrength)
    }

    @Test
    fun reviewIncludesLocalAiFlavorCheck() = runTest {
        val cliche = "总之，" + "值得一提的是，空气仿佛凝固了，他不由自主地深深地叹了口气。".repeat(30)
        val engine = FakeEngine(listOf("严苛的网文主编" to "{}"))
        val pipeline = AgentPipeline(engine)
        val result = pipeline.review("第1章", cliche)
        assertTrue(result.output.aiFlavorRisk > 0, "AI 腔风险应被检出，实际 ${result.output.aiFlavorRisk}")
    }

    @Test
    fun truncatesOverlongHandoffNote() = runTest {
        val long = "伏笔：".repeat(200)
        val engine = FakeEngine(listOf("梗概编辑" to long))
        val pipeline = AgentPipeline(engine, handoffMaxChars = 100)
        val result = pipeline.makeHandoff("第1章", "正文")
        assertTrue(result.output.length <= 100, "应被截断到 100 字内，实际 ${result.output.length}")
    }

    @Test
    fun continuationPromptIncludesExistingTail() = runTest {
        val engine = FakeEngine(listOf("续写" to "他继续往前走。"))
        val pipeline = AgentPipeline(engine)
        val result = pipeline.continueWriting(
            existingTail = "他站在崖边，风很大。",
            instruction = "让他发现脚印", length = 500,
        )
        assertTrue(engine.calls.last().prompt.contains("他站在崖边"))
        assertEquals("他继续往前走。", result.output)
    }

    @Test
    fun globalRecapReturnsEarlyWhenEmpty() = runTest {
        val engine = FakeEngine(emptyList())
        val pipeline = AgentPipeline(engine)
        val result = pipeline.makeGlobalRecap(emptyList())
        assertEquals("", result.output)
        assertTrue(engine.calls.isEmpty(), "无输入不应调用引擎")
    }

    @Test
    fun maxTokensScalesWithTargetLength() = runTest {
        val engine = FakeEngine(listOf("请写出本章正文" to "正文"))
        val pipeline = AgentPipeline(engine)
        pipeline.writeChapter("书", 1, "章", "", "", "", "", targetWords = 3000)
        val tokens = engine.calls.last().maxTokens
        assertTrue(tokens >= 3000, "3000 字目标应至少给 3000 token，实际 $tokens")
    }

    @Test
    fun streamingInterfaceWorks() = runTest {
        val engine = FakeEngine(emptyList())
        val collected = mutableListOf<String>()
        engine.stream(AiRequest(prompt = "测试")).collect { collected += it }
        assertEquals(listOf("流式", "输出"), collected)
    }
}
