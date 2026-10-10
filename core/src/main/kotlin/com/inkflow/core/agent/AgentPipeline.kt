package com.inkflow.core.agent

import com.inkflow.core.ai.AiCapability
import com.inkflow.core.ai.AiEngine
import com.inkflow.core.ai.AiRequest
import com.inkflow.core.ai.AiResponse
import com.inkflow.core.corpus.Blueprint
import com.inkflow.core.corpus.BlueprintStructure
import com.inkflow.core.corpus.ChapterPlan
import com.inkflow.core.corpus.CharacterPlan
import com.inkflow.core.corpus.CorpusEngine
import com.inkflow.core.corpus.DepthProfile
import com.inkflow.core.corpus.DepthResolver
import com.inkflow.core.corpus.ForeshadowPlan
import com.inkflow.core.corpus.IntakeQuestion
import com.inkflow.core.corpus.IntakeQuestionnaire
import com.inkflow.core.corpus.QuestionKind
import com.inkflow.core.corpus.SlopDetector
import com.inkflow.core.corpus.VoiceLibrary
import com.inkflow.core.corpus.VolumePlan
import com.inkflow.core.corpus.WorldSettingPlan

/**
 * 提示词工程中心。
 *
 * 全部提示词集中在一处，便于调优与 A/B；同时保证所有 Agent 共享同一套
 * 「中文网文写作规范」，避免各角色输出风格割裂。
 */
object Prompts {

    /** 所有写作类 Agent 的公共底座。 */
    val WRITING_CONSTITUTION = """
你是一位资深的中文长篇小说家与责任编辑，服务于一部商业连载作品。
你必须遵守以下铁律：
1. 只输出小说正文，不输出任何解释、标题编号复述、Markdown 标记或创作说明。
2. 严格延续已有设定、人物性格与前文伏笔，不得凭空新增重要设定或让角色 OOC。
3. 用具体动作、对话、感官细节推进情节，禁止用概述句代替场景（"他们聊了很久"是错的）。
4. 避免 AI 腔：不写"总之""值得一提的是""仿佛整个世界都"这类空泛套话；不滥用排比与形容词堆砌。
5. 对话要有潜台词与个性差异，不同角色的说话方式必须可区分。
6. 每一段都要有存在理由：推进情节、塑造人物或埋设伏笔，三者至少占其一。
7. 具体优先于概括：写动作、器物、体感，不写情绪名词。禁止给情绪命名后草草了事。
8. 每一句话都必须是这一场独有的。换到别的作品里也成立的句子，就是废句。
"""

    fun outlineSystem(genre: String, targetWords: Long) = """
你是长篇小说结构设计师，专精 $genre 题材的商业连载结构。
输出要求：使用 Markdown 层级列表，直接给出可执行的分卷/章节细纲。
每章细纲需包含：本章核心事件、冲突点、情绪曲线、结尾钩子、埋设或回收的伏笔。
全书目标字数 ${targetWords / 10000} 万字，请按此规模做合理分卷。
不要输出任何前言或解释。
""".trim()

    fun outlineUser(projectTitle: String, logline: String, premise: String, volumeCount: Int, chaptersPerVolume: Int) = """
作品名：《${projectTitle}》
一句话故事：$logline
核心设定：$premise

请设计 $volumeCount 卷结构，每卷 $chaptersPerVolume 章左右。
要求：
- 第一幕 25% 建立人物与核心矛盾并抛出主悬念；
- 第二幕 50% 升级冲突、中点反转、主角跌入最低谷；
- 第三幕 25% 高潮对决与结局回收；
- 每卷结尾必须留一个推动读者追更的大钩子。
""".trim()

    fun chapterOutlineSystem(genre: String) = """
你是章节细纲编剧，为 $genre 题材服务。
输出一份本章细纲，包含 5 个部分：【场景与地点】【出场人物及目的】【核心事件】【情绪与节奏】【结尾钩子】。
细纲要具体到可以被直接扩写成 3000 字正文，禁止空话。
""".trim()

    fun chapterWriteSystem(
        styleBlock: String,
        contextBlock: String,
        corpusBlock: String = "",
        rhythmBlock: String = "",
    ) = buildString {
        appendLine(WRITING_CONSTITUTION)
        // 语料库紧跟宪法：它是「怎么写」层，比文风 DNA 更需要被放在前排
        if (corpusBlock.isNotBlank()) {
            appendLine()
            appendLine(corpusBlock)
        }
        // 节奏校准确切排在文风 DNA 之前，让「深度 > 声纹」的优先级在阅读顺序上先出现
        if (rhythmBlock.isNotBlank()) {
            appendLine()
            appendLine(rhythmBlock)
        }
        if (styleBlock.isNotBlank()) {
            appendLine()
            appendLine(styleBlock)
        }
        if (contextBlock.isNotBlank()) {
            appendLine("===== 作品语境（检索自本地知识库）=====")
            appendLine(contextBlock)
        }
    }

    fun chapterWriteUser(
        projectTitle: String,
        chapterOrder: Int,
        chapterTitle: String,
        outline: String,
        handoff: String,
        targetWords: Int,
        requirements: String,
    ) = buildString {
        appendLine("作品：《${projectTitle}》")
        appendLine("本章：第${chapterOrder}章 ${chapterTitle}")
        if (outline.isNotBlank()) {
            appendLine("本章细纲：")
            appendLine(outline)
        }
        if (handoff.isNotBlank()) {
            appendLine("上一章交接笔记（必须承接）：")
            appendLine(handoff)
        }
        appendLine()
        append("请写出本章正文，约 $targetWords 字。")
        if (requirements.isNotBlank()) {
            append("额外要求：$requirements")
        }
    }.trim()

    val CONTINUE_SYSTEM = """
你正在续写一段已经写好的小说正文。
只输出续写的新内容，不要重复已有文字，不要输出任何说明。
保持与上文完全一致的人称、时态、语气与段落节奏。
"""

    fun continueUser(existingTail: String, instruction: String, length: Int) = """
【已有正文结尾】
$existingTail

【续写要求】
${if (instruction.isBlank()) "自然承接上文，推进情节" else instruction}
续写约 $length 字。
""".trim()

    val POLISH_SYSTEM = """
你是中文小说润色编辑。在**不改变情节走向与人物设定**的前提下优化文字：
- 修正病句、重复用词、标点误用；
- 强化画面感与动作细节，删掉冗余的概述句；
- 调节句长节奏，避免连续长句造成阅读疲劳；
- 删除 AI 腔套话。
直接输出润色后的完整正文，不要输出修改说明。
""".trim()

    val PROOFREAD_SYSTEM = """
你是中文校对员。找出文本中的错别字、别字、语法错误、标点错误与前后不一致。
以 JSON 数组输出，每项格式 {"offset":字符位置,"original":"原文片段","corrected":"修改后","reason":"原因"}。
如果没有问题，输出 []。不要输出任何其他内容。
""".trim()

    val CONSISTENCY_SYSTEM = """
你是小说设定审校员。给定【设定与角色档案】和【待检查正文】，
找出正文中与档案矛盾的地方（人物性格突变、外貌不一致、能力越界、地理/时间冲突、称呼混乱）。
以 JSON 数组输出，每项 {"severity":1到4,"type":"问题类型","evidence":"正文原文片段","conflict":"与档案的哪一条冲突","fix":"修改建议"}。
没有发现问题则输出 []。只输出 JSON。
""".trim()

    fun consistencyUser(context: String, text: String) = """
【设定与角色档案】
$context

【待检查正文】
${text.take(12000)}
""".trim()

    val REVIEW_SYSTEM = """
你是严苛的网文主编，从读者留存角度审稿。评估维度：
1. 开篇吸引力（前 300 字是否抓人）；2. 情节推进效率（是否有注水）；3. 情绪张力；4. 钩子强度；5. 是否存在 AI 腔。
以 JSON 输出：{"score":0到100,"verdict":"一句话总评","strengths":["..."],"problems":[{"severity":1到4,"issue":"...","fix":"..."}],"hookStrength":0到100}
只输出 JSON。
""".trim()

    fun summarySystem(scope: String) = """
你是小说梗概编辑。为${scope}生成交接笔记，供后续章节写作时作为上下文。
要求：保留所有推进中的伏笔、角色状态变化、未解决的悬念；不超过 400 字；纯文本无 Markdown。
""".trim()

    fun summaryUser(title: String, content: String) = """
章节：$title
正文：
${content.take(20000)}

请输出交接笔记。
""".trim()

    val STYLE_EXTRACT_SYSTEM = """
你是文体分析师。阅读样本后，用一段话（150 字内）描述该作者的文体特征：
叙述人称与视角、句子长短习惯、段落节奏、对话与描写的配比、修辞偏好、用词特点。
只输出这段描述，不要分点，不要解释。
""".trim()

    val STORY_BIBLE_SYSTEM = """
你是世界观架构师。依据给定的题材与梗概，生成作品蓝本（Story Bible）。
以 JSON 输出：{"worldview":"世界规则","powerSystem":"力量体系","tone":"基调","themes":["主题"],"characters":[{"name":"","role":"","personality":"","goal":"","arc":"","relationships":""}],"foreshadows":[{"title":"","detail":"","plannedResolveAt":章节序号}]}
人物 4-6 位，伏笔 3-5 条。只输出 JSON。
""".trim()

    fun storyBibleUser(genre: String, logline: String, premise: String) = """
题材：$genre
一句话故事：$logline
核心设定：$premise
""".trim()

    // ------------------------------------------------------------------
    // 建作前提问
    // ------------------------------------------------------------------

    val INTAKE_QUESTION_SYSTEM = """
你是长篇小说策划，负责在动笔前把作者的构思问清楚。
作者已经填了一些内容，你要找出**真正影响大纲质量**但还没说清的缺口，提出追问。

规则：
1. 每个问题必须具体到「答完之后能直接拿去写大纲」，禁止问「你想写什么」这类空问题；
2. 只问作者已填内容里**没有覆盖**的维度，不要重复他已经写过的；
3. 最多 6 个问题，按重要性排序，宁少勿多；
4. 问题要贴合作者写的题材，问出这个题材特有的坑（例如写修真要问境界体系，写悬疑要问真相规模）；
5. 每个问题附一句「为什么要问」，让作者知道这题影响什么。

以 JSON 数组输出，每项：
{"field":"英文短标识","question":"问题正文","why":"为什么要问","kind":"text|longtext|choice","options":["选项1","选项2"],"priority":1到3}
priority：1 = 不问就写不出可用大纲，2 = 显著影响质量，3 = 锦上添花。
只输出 JSON。
""".trim()

    fun intakeQuestionUser(
        genre: String,
        logline: String,
        premise: String,
        title: String,
        targetWords: String,
        knownGaps: String,
    ) = """
作品名：$title
题材：$genre
一句话故事：$logline
核心设定：$premise
目标字数：$targetWords

【本地规则已检出的缺口（不要重复问这些）】
$knownGaps

请补充追问。
""".trim()

    // ------------------------------------------------------------------
    // 蓝图展开
    // ------------------------------------------------------------------

    fun blueprintWorldSystem(depth: DepthProfile, genre: String) = """
你是世界观架构师，为一部 $genre 长篇生成设定集。

写作深度要求（影响设定的复杂度与说明方式）：
${depth.toPromptBlock()}

输出要求：
- 给出 8-14 条设定，覆盖：地理/势力、力量体系（或核心规则）、关键物品、历史事件、社会结构；
- 每条设定都必须能**直接产生冲突**，纯背景板设定不要写；
- 力量体系必须有明确的代价或限制，不能是无限的；
- 说明设定时按上面深度要求控制术语密度与解释方式。

以 JSON 数组输出，每项：
{"category":"地理|势力|力量体系|物品|历史|社会","name":"名称","content":"60-200字说明","tags":"逗号分隔"}
只输出 JSON。
""".trim()

    fun blueprintCharactersSystem(depth: DepthProfile, genre: String) = """
你是人物设定师，为一部 $genre 长篇设计角色。

写作深度要求：
${depth.toPromptBlock()}

输出要求：
- 4-8 位角色，必须包含：主角、主要对手、至少一位关系线人物；
- 每位角色的「目标」必须与主角的目标**存在冲突**，不能是纯助攻；
- 「人物弧光」写清他从什么状态变到什么状态；
- 配角不要写满，留出成长空间。

以 JSON 数组输出，每项：
{"name":"","role":"主角|配角|反派","gender":"","age":"","appearance":"","personality":"","background":"","goal":"","arc":"","relationships":""}
只输出 JSON。
""".trim()

    fun blueprintForeshadowSystem(totalChapters: Int) = """
你是伏笔设计师。为一部共 $totalChapters 章的长篇设计伏笔台账。

输出要求：
- 5-10 条伏笔，覆盖全书三个幕；
- 每条必须标注埋设章节与计划回收章节，回收章节不得早于埋设章节 + 3；
- 高重要度伏笔（3）不超过 3 条；
- 第一幕埋下的伏笔，至少要有一条回收在最后一幕。

以 JSON 数组输出，每项：
{"title":"","detail":"60-150字","plantedAt":章节号,"plannedResolveAt":章节号,"importance":1到3}
只输出 JSON。
""".trim()

    fun blueprintVolumeSystem(depth: DepthProfile, genre: String) = """
你是长篇结构设计师，为一部 $genre 作品写分卷大纲。

写作深度要求：
${depth.toPromptBlock()}

你将收到**已经定好的结构参数**（卷数、每卷章数、每章字数）。
这些数字是硬约束，不要改动，只需按它写出每一卷的内容。

每卷需要：
- 卷标题（有个性，不要「第一卷」这种占位符）；
- 卷功能（铺垫/升级/转折/高潮，对应三幕结构）；
- 卷梗概（100-200 字，说清这一卷的核心事件与结束时主角所处的位置）。

以 JSON 数组输出，每项：
{"title":"","function":"","synopsis":""}
只输出 JSON。
""".trim()

    fun blueprintChaptersSystem(depth: DepthProfile, genre: String) = """
你是章节细纲编剧。为一部 $genre 作品写某一卷的逐章细纲。

写作深度要求：
${depth.toPromptBlock()}

每章细纲必须包含五个部分，缺一不可：
- event：本章核心事件，必须是**可被写成一个场景**的具体事件，不是概述；
- conflict：冲突点，谁在阻止主角、代价是什么；
- emotion：情绪曲线，从什么情绪走到什么情绪；
- hook：结尾钩子，必须落在具体的人、物或数字上，禁止「而这仅仅是开始」这类空话；
- foreshadow：本章埋设或回收的伏笔，没有则留空。

铁律：
- 每章只写一件事，不要把三章的内容压进一章；
- 相邻章节之间必须有推进关系，不能是并列的单元剧（除非结构需要）；
- 每 4-6 章要有一个小高潮。

以 JSON 数组输出，每项：
{"order":章节序号,"title":"","event":"","conflict":"","emotion":"","hook":"","foreshadow":"","characters":["角色名"]}
order 必须从给定的起始序号开始连续编号。只输出 JSON。
""".trim()

    fun blueprintChapterUser(
        projectTitle: String,
        volumeTitle: String,
        volumeSynopsis: String,
        startOrder: Int,
        endOrder: Int,
        previousEnding: String,
        characterNames: String,
        foreshadowList: String,
    ) = """
作品：《$projectTitle》
本卷：$volumeTitle
卷梗概：$volumeSynopsis

请输入第 $startOrder 章到第 $endOrder 章，共 ${endOrder - startOrder + 1} 章的细纲。
${if (previousEnding.isNotBlank()) "\n上一卷结尾的状态（必须承接）：\n$previousEnding\n" else ""}
可用角色：$characterNames

需要照应的伏笔：
$foreshadowList
""".trim()

    /** 注入到所有生成提示词的节奏校准块。 */
    fun rhythmBlock(depth: DepthProfile, genre: String, voiceName: String? = null): String =
        VoiceLibrary.promptBlock(depth, genre, voiceName)
}

/**
 * 多智能体写作管线。
 *
 * 范式来源：DeepWriter（AAAI 2026）的「规划→生成→一致性检查→审阅」流水线，
 * 叠加 LOOM 的分层认知循环（章节级局部循环 + 全书级全局反馈），
 * 并以 8-Agent 参考实现中的**章节交接笔记 + 检查点**机制降低长上下文丢失。
 *
 * 本类完全不依赖 Android，只依赖 [AiEngine]，因此可被单元测试完整覆盖。
 */
class AgentPipeline(
    private val engine: AiEngine,
    /** 章与章之间的交接笔记最大长度 */
    private val handoffMaxChars: Int = 400,
) {

    // ------------------------------------------------------------------
    // Agent 1：全局规划师
    // ------------------------------------------------------------------

    suspend fun planNovel(
        projectTitle: String,
        genre: String,
        logline: String,
        premise: String,
        targetWords: Long,
        volumeCount: Int = 4,
        chaptersPerVolume: Int = 25,
    ): AgentResult<OutlinePlan> {
        val resp = call(
            system = Prompts.outlineSystem(genre, targetWords),
            user = Prompts.outlineUser(projectTitle, logline, premise, volumeCount, chaptersPerVolume),
            maxTokens = 4096,
            temperature = 0.7f,
        )
        val volumes = parseOutline(resp.text, volumeCount, chaptersPerVolume)
        return AgentResult(
            output = OutlinePlan(rawText = resp.text, volumes = volumes),
            engineId = resp.engineId,
            degraded = resp.degraded,
        )
    }

    // ------------------------------------------------------------------
    // Agent 1.5：世界观架构师（蓝图）
    // ------------------------------------------------------------------

    suspend fun buildStoryBible(
        genre: String,
        logline: String,
        premise: String,
    ): AgentResult<String> {
        val resp = call(
            system = Prompts.STORY_BIBLE_SYSTEM,
            user = Prompts.storyBibleUser(genre, logline, premise),
            maxTokens = 2048,
            temperature = 0.8f,
            jsonSchemaHint = "story-bible",
        )
        return AgentResult(resp.text, resp.engineId, resp.degraded)
    }

    // ------------------------------------------------------------------
    // Agent 2：章节细纲编剧
    // ------------------------------------------------------------------

    suspend fun planChapter(
        genre: String,
        projectTitle: String,
        chapterOrder: Int,
        chapterTitle: String,
        contextBlock: String,
        previousHandoff: String,
    ): AgentResult<String> {
        val resp = call(
            system = Prompts.chapterOutlineSystem(genre) + "\n" + contextBlock,
            user = buildString {
                appendLine("作品：《${projectTitle}》")
                appendLine("请为第${chapterOrder}章「${chapterTitle}」写细纲。")
                if (previousHandoff.isNotBlank()) {
                    appendLine("上一章交接笔记：$previousHandoff")
                }
            }.trim(),
            maxTokens = 1024,
            temperature = 0.75f,
        )
        return AgentResult(resp.text.trim(), resp.engineId, resp.degraded)
    }

    // ------------------------------------------------------------------
    // Agent 3：正文写作
    // ------------------------------------------------------------------

    suspend fun writeChapter(
        projectTitle: String,
        chapterOrder: Int,
        chapterTitle: String,
        outline: String,
        styleBlock: String,
        contextBlock: String,
        previousHandoff: String,
        targetWords: Int,
        requirements: String = "",
        /** 作品题材，用于挑选贴合的具体化词条；空表示通用 */
        genre: String = "",
        /** 关闭时只注入 AI 腔禁忌清单，不注入选词样例与拆书参照 */
        includeCorpus: Boolean = true,
        /** 写作深度档。决定句长、段落、比喻密度等可执行参数 */
        depth: DepthProfile = DepthProfile(),
        /** 指定参照的声纹来源作品名；为空时按深度自动匹配 */
        voiceName: String? = null,
    ): AgentResult<String> {
        val corpusBlock = if (includeCorpus) {
            CorpusEngine.writingBlock(genre = genre)
        } else {
            CorpusEngine.writingBlock(genre = genre, includeDeconstruct = false)
        }
        // 节奏校准单独成块并排在 corpusBlock 之后：
        // 它是模型最需要精确执行的数值约束，不能混在「选词参考」里被当成建议
        val rhythm = Prompts.rhythmBlock(depth, genre, voiceName)
        val resp = call(
            system = Prompts.chapterWriteSystem(
                styleBlock = styleBlock,
                contextBlock = contextBlock,
                corpusBlock = corpusBlock,
                rhythmBlock = rhythm,
            ),
            user = Prompts.chapterWriteUser(
                projectTitle, chapterOrder, chapterTitle, outline,
                previousHandoff, targetWords, requirements,
            ),
            maxTokens = maxTokensFor(targetWords),
            temperature = 0.9f,
            stop = listOf("\n第${chapterOrder + 1}章", "\n【", "\n---", "\n（完）"),
        )
        return AgentResult(cleanBody(resp.text), resp.engineId, resp.degraded)
    }

    /**
     * 续写：接受已经写好的正文尾部，只产出新增部分。
     * 这是作者最常用的功能，必须保证「不重复已有文字」。
     */
    suspend fun continueWriting(
        existingTail: String,
        instruction: String,
        length: Int,
        styleBlock: String = "",
        contextBlock: String = "",
        genre: String = "",
        depth: DepthProfile = DepthProfile(),
        voiceName: String? = null,
    ): AgentResult<String> {
        val system = buildString {
            appendLine(Prompts.CONTINUE_SYSTEM)
            // 续写场景下拆书参照意义不大（结构已经定了），但选词与禁忌清单必须带上
            val corpus = CorpusEngine.writingBlock(genre = genre, includeDeconstruct = false)
            if (corpus.isNotBlank()) { appendLine(); appendLine(corpus) }
            val rhythm = Prompts.rhythmBlock(depth, genre, voiceName)
            if (rhythm.isNotBlank()) { appendLine(); appendLine(rhythm) }
            if (styleBlock.isNotBlank()) { appendLine(); appendLine(styleBlock) }
            if (contextBlock.isNotBlank()) {
                appendLine("===== 相关设定 =====")
                appendLine(contextBlock)
            }
        }
        val resp = call(
            system = system,
            user = Prompts.continueUser(existingTail.takeLast(2000), instruction, length),
            maxTokens = maxTokensFor(length),
            temperature = 0.9f,
        )
        return AgentResult(cleanBody(resp.text), resp.engineId, resp.degraded)
    }

    // ------------------------------------------------------------------
    // Agent 4：一致性检查员
    // ------------------------------------------------------------------

    suspend fun checkConsistency(contextBlock: String, text: String): AgentResult<List<ConsistencyFinding>> {
        val resp = call(
            system = Prompts.CONSISTENCY_SYSTEM,
            user = Prompts.consistencyUser(contextBlock, text),
            maxTokens = 1536,
            temperature = 0.2f,
            jsonSchemaHint = "consistency-findings",
        )
        return AgentResult(parseConsistency(resp.text), resp.engineId, resp.degraded)
    }

    // ------------------------------------------------------------------
    // Agent 5：润色编辑
    // ------------------------------------------------------------------

    suspend fun polish(
        text: String,
        styleBlock: String,
        instruction: String = "",
        genre: String = "",
        depth: DepthProfile = DepthProfile(),
    ): AgentResult<String> {
        // 润色时给出**这篇实际命中的**套话，而不是无差别糊一长串清单——
        // 模型看到具体证据才知道要改哪里，看到通用清单只会泛泛重写
        val report = SlopDetector.detect(text)
        val corpus = buildString {
            if (!report.isEmpty) {
                appendLine(SlopDetector.targetedAdvice(report, maxItems = 8))
                appendLine()
            }
            append(positiveLexicon(genre))
        }.trim()
        val system = buildString {
            append(Prompts.POLISH_SYSTEM)
            if (corpus.isNotBlank()) { appendLine(); appendLine(); append(corpus) }
            // 润色是深度控制最有效的位置：结构已经写好，只需要调文字的松紧
            appendLine()
            appendLine(depth.toPromptBlock())
            if (styleBlock.isNotBlank()) { appendLine(); appendLine(styleBlock) }
        }
        val resp = call(
            system = system,
            user = "【待润色正文】\n$text" + if (instruction.isBlank()) "" else "\n\n【额外要求】$instruction",
            maxTokens = maxTokensFor(text.length / 2 + 500),
            temperature = 0.6f,
        )
        return AgentResult(cleanBody(resp.text), resp.engineId, resp.degraded)
    }

    // ------------------------------------------------------------------
    // Agent 6：校对员
    // ------------------------------------------------------------------

    suspend fun proofread(text: String): AgentResult<List<ProofreadFix>> {
        val resp = call(
            system = Prompts.PROOFREAD_SYSTEM,
            user = text.take(8000),
            maxTokens = 1536,
            temperature = 0.1f,
            jsonSchemaHint = "proofread-fixes",
        )
        return AgentResult(parseProofread(resp.text), resp.engineId, resp.degraded)
    }

    // ------------------------------------------------------------------
    // Agent 7：主编审阅
    // ------------------------------------------------------------------

    suspend fun review(chapterTitle: String, text: String): AgentResult<ReviewVerdict> {
        val resp = call(
            system = Prompts.REVIEW_SYSTEM,
            user = "章节：$chapterTitle\n正文：\n${text.take(12000)}",
            maxTokens = 1536,
            temperature = 0.3f,
            jsonSchemaHint = "review-verdict",
        )
        return AgentResult(parseReview(resp.text, text), resp.engineId, resp.degraded)
    }

    // ------------------------------------------------------------------
    // Agent 8：交接笔记（上下文压缩）
    // ------------------------------------------------------------------

    suspend fun makeHandoff(chapterTitle: String, content: String): AgentResult<String> {
        val resp = call(
            system = Prompts.summarySystem("本章"),
            user = Prompts.summaryUser(chapterTitle, content),
            maxTokens = 768,
            temperature = 0.3f,
        )
        var note = resp.text.trim()
        if (note.length > handoffMaxChars) note = note.take(handoffMaxChars)
        return AgentResult(note, resp.engineId, resp.degraded)
    }

    /** 全书级全局反馈：把多章交接笔记压缩成「前情提要」，用于避免长篇跑偏。 */
    suspend fun makeGlobalRecap(handoffs: List<String>): AgentResult<String> {
        if (handoffs.isEmpty()) return AgentResult("", engine.id, false)
        val resp = call(
            system = Prompts.summarySystem("全书已写部分"),
            user = handoffs.joinToString("\n---\n").take(20000),
            maxTokens = 1024,
            temperature = 0.3f,
        )
        return AgentResult(resp.text.trim(), resp.engineId, resp.degraded)
    }

    // ------------------------------------------------------------------
    // 文风蒸馏
    // ------------------------------------------------------------------

    suspend fun describeStyle(sample: String): AgentResult<String> {
        val resp = call(
            system = Prompts.STYLE_EXTRACT_SYSTEM,
            user = sample.take(6000),
            maxTokens = 512,
            temperature = 0.4f,
        )
        return AgentResult(resp.text.trim(), resp.engineId, resp.degraded)
    }

    /** 润色/改写时的正向选词参考。不带拆书——改写阶段不需要结构建议。 */
    private fun positiveLexicon(genre: String): String =
        CorpusEngine.writingBlock(genre = genre, includeDeconstruct = false)

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------


    // ------------------------------------------------------------------
    // 建作前提问（需求 1）
    // ------------------------------------------------------------------

    /**
     * 基于作者已填内容生成追问。
     *
     * 【两层结构】本地规则先算出必答缺口，再让模型补语义追问。
     * 模型不可用或输出无法解析时，**本地结果照常返回**——
     * 提问功能不允许因为引擎问题而整体失效（用户建作品时最可能还没配模型）。
     */
    suspend fun generateIntakeQuestions(
        title: String,
        genre: String,
        logline: String,
        premise: String,
        targetWords: String,
        projectId: String = "",
    ): AgentResult<List<IntakeQuestion>> {
        val existing = mapOf(
            "title" to title,
            "genre" to genre,
            "logline" to logline,
            "premise" to premise,
            "targetWords" to targetWords,
        )

        // 第一层：本地。永远执行，是主干。
        val local = IntakeQuestionnaire.localQuestions(existing)

        // 第二层：AI。失败就跳过，不影响本地结果。
        val ai = runCatching {
            val resp = call(
                system = Prompts.INTAKE_QUESTION_SYSTEM,
                user = Prompts.intakeQuestionUser(
                    genre = genre,
                    logline = logline,
                    premise = premise,
                    title = title,
                    targetWords = targetWords,
                    knownGaps = local.joinToString("\n") { "- ${it.question}" },
                ),
                maxTokens = 1536,
                temperature = 0.7f,
                jsonSchemaHint = "intake-questions",
            )
            parseIntakeQuestions(resp.text)
        }.getOrDefault(emptyList())

        val session = IntakeQuestionnaire.build(projectId, existing, ai)
        return AgentResult(session.questions, engine.id, degraded = ai.isEmpty())
    }

    private fun parseIntakeQuestions(text: String): List<IntakeQuestion> {
        val arr = JsonLite.extractArray(text) ?: return emptyList()
        return arr.mapIndexedNotNull { i, obj ->
            val question = JsonLite.str(obj, "question").orEmpty()
            if (question.isBlank()) return@mapIndexedNotNull null
            val field = JsonLite.str(obj, "field").orEmpty().ifBlank { "ai_$i" }
            IntakeQuestion(
                id = "q_ai_$field",
                field = field,
                question = question,
                why = JsonLite.str(obj, "why").orEmpty().ifBlank { "这一项会影响后续大纲的准确度。" },
                kind = when (JsonLite.str(obj, "kind").orEmpty().lowercase()) {
                    "choice" -> QuestionKind.Choice
                    "multichoice" -> QuestionKind.MultiChoice
                    "longtext" -> QuestionKind.LongText
                    else -> QuestionKind.Text
                },
                options = JsonLite.stringArrayOf(obj, "options"),
                priority = (JsonLite.int(obj, "priority") ?: 2).coerceIn(1, 3),
                origin = "ai",
            )
        }.take(6)
    }

    // ------------------------------------------------------------------
    // 全书蓝图（需求 2）
    // ------------------------------------------------------------------

    /**
     * 展开全书蓝图：世界观 + 角色 + 伏笔 + 分卷 + 逐章细纲。
     *
     * 【为什么结构参数由代码算】
     * 让模型「按 100 万字规划分卷」，它给出的方案从 12 卷到 400 章都出现过，
     * 而且常常自相矛盾（说每卷 30 章，列出来 47 章）。
     * 结构是算术问题，算术归代码；模型只负责往结构里填内容。
     *
     * @param answers 问答答案，会作为补充设定注入
     * @param onProgress 进度回调（阶段名, 0..1），供 UI 显示「正在生成世界观…」
     */
    suspend fun expandBlueprint(
        projectId: String,
        title: String,
        genre: String,
        tone: String,
        audience: String,
        logline: String,
        premise: String,
        targetWords: Long,
        answers: Map<String, String> = emptyMap(),
        voiceName: String? = null,
        onProgress: (String, Double) -> Unit = { _, _ -> },
    ): AgentResult<Blueprint> {
        // 1) 深度档：把用户的模糊偏好翻译成可执行参数
        onProgress("正在解析写作深度…", 0.05)
        val depth = DepthResolver.resolve(genre, tone, audience, answers)

        // 2) 结构：纯算术，不消耗 token
        val structure = BlueprintStructure.of(targetWords, DepthResolver.chapterLengthBias(answers))
        onProgress("已确定结构：${structure.volumeCount} 卷 / ${structure.chapterCount} 章", 0.10)

        val intake = IntakeQuestionnaire.build(projectId, emptyMap()).let { _ ->
            // 只取答案文本，不重新生成问题
            answers.entries.filter { it.value.isNotBlank() }
                .joinToString("\n") { "- ${it.key}：${it.value}" }
        }
        val worldContext = """
作品：《$title》
题材：$genre
基调：$tone
受众：$audience
一句话故事：$logline
核心设定：$premise
${if (intake.isNotBlank()) "\n作者补充说明：\n$intake" else ""}
""".trim()

        val usedEngine = engine.id
        var degraded = false

        // 3) 世界观
        onProgress("正在生成世界观设定…", 0.15)
        val settings = runCatching {
            call(
                system = Prompts.blueprintWorldSystem(depth, genre),
                user = worldContext,
                maxTokens = 3072,
                temperature = 0.75f,
                jsonSchemaHint = "world-settings",
            ).let { resp ->
                degraded = degraded || resp.degraded
                parseSettings(resp.text)
            }
        }.getOrDefault(emptyList())

        // 4) 角色
        onProgress("正在设计角色…", 0.28)
        val characters = runCatching {
            call(
                system = Prompts.blueprintCharactersSystem(depth, genre),
                user = worldContext,
                maxTokens = 3072,
                temperature = 0.8f,
                jsonSchemaHint = "characters",
            ).let { resp ->
                degraded = degraded || resp.degraded
                parseCharacters(resp.text)
            }
        }.getOrDefault(emptyList())

        // 5) 伏笔
        onProgress("正在埋设伏笔…", 0.38)
        val foreshadows = runCatching {
            call(
                system = Prompts.blueprintForeshadowSystem(structure.chapterCount),
                user = worldContext + "\n\n可用角色：" + characters.joinToString("、") { it.name },
                maxTokens = 2048,
                temperature = 0.75f,
                jsonSchemaHint = "foreshadows",
            ).let { resp ->
                degraded = degraded || resp.degraded
                parseForeshadows(resp.text, structure.chapterCount)
            }
        }.getOrDefault(emptyList())

        // 6) 分卷
        onProgress("正在规划分卷…", 0.48)
        val volumeShells = runCatching {
            call(
                system = Prompts.blueprintVolumeSystem(depth, genre),
                user = buildString {
                    appendLine(worldContext)
                    appendLine()
                    appendLine("结构参数（硬约束，不要改动）：")
                    appendLine("- 全书共 ${structure.volumeCount} 卷")
                    appendLine("- 每卷约 ${structure.chaptersPerVolume} 章")
                    appendLine("- 全书共 ${structure.chapterCount} 章，每章约 ${structure.wordsPerChapter} 字")
                    appendLine("- ${structure.actBreakdown}")
                }.trim(),
                maxTokens = 3072,
                temperature = 0.75f,
                jsonSchemaHint = "volumes",
            ).let { resp ->
                degraded = degraded || resp.degraded
                parseVolumeShells(resp.text, structure.volumeCount)
            }
        }.getOrDefault(emptyList())

        // 模型没给出足够卷数时，用占位卷补齐——宁可标题平庸，不能让结构残缺
        val shells = if (volumeShells.size >= structure.volumeCount) {
            volumeShells.take(structure.volumeCount)
        } else {
            volumeShells + (volumeShells.size until structure.volumeCount).map { i ->
                VolumePlan(
                    index = i,
                    title = "第${i + 1}卷",
                    function = actFunctionOf(i, structure.volumeCount),
                    synopsis = "（本卷梗概待补充）",
                )
            }
        }

        val charNames = characters.joinToString("、") { it.name }.ifBlank { "（待定）" }
        val fsList = foreshadows.take(10).joinToString("\n") {
            "- ${it.title}（第 ${it.plantedAt} 章埋设，计划第 ${it.plannedResolveAt} 章回收）"
        }.ifBlank { "（无）" }

        // 7) 逐卷展开章节细纲
        val volumes = mutableListOf<VolumePlan>()
        var order = 1
        var previousEnding = ""
        shells.forEachIndexed { vi, shell ->
            val start = order
            // 末卷吃掉余数：前面各卷取 chaptersPerVolume，最后一段落到总章数上。
            // 这样即使 chaptersPerVolume * volumeCount 与 chapterCount 不整除，
            // 也不会漏章或越界。
            val proposedEnd = start + structure.chaptersPerVolume - 1
            val realEnd = if (vi == shells.lastIndex) structure.chapterCount
            else proposedEnd.coerceAtMost(structure.chapterCount)
            val perVolume = (realEnd - start + 1).coerceAtLeast(1)
            onProgress(
                "正在细化第${vi + 1}卷 / 共${shells.size}卷（第 $start-$realEnd 章）…",
                0.50 + 0.45 * vi / shells.size.coerceAtLeast(1),
            )

            val chapters = runCatching {
                call(
                    system = Prompts.blueprintChaptersSystem(depth, genre),
                    user = Prompts.blueprintChapterUser(
                        projectTitle = title,
                        volumeTitle = shell.title,
                        volumeSynopsis = shell.synopsis,
                        startOrder = start,
                        endOrder = realEnd,
                        previousEnding = previousEnding,
                        characterNames = charNames,
                        foreshadowList = fsList,
                    ),
                    maxTokens = (perVolume * 220).coerceIn(2048, 8192),
                    temperature = 0.78f,
                    jsonSchemaHint = "chapter-plans",
                ).let { resp ->
                    degraded = degraded || resp.degraded
                    parseChapterPlans(resp.text, start, realEnd, structure.wordsPerChapter, charNames)
                }
            }.getOrDefault(emptyList())

            val filled = fillMissingOrders(chapters, start, realEnd, structure.wordsPerChapter)
            previousEnding = filled.lastOrNull()?.let { "${it.title}：${it.summaryLine}" }.orEmpty()

            volumes += shell.copy(index = vi, chapters = filled)
            order = realEnd + 1
        }

        onProgress("蓝图完成", 1.0)

        return AgentResult(
            Blueprint(
                projectId = projectId,
                title = title,
                targetWords = targetWords,
                settings = settings,
                characters = characters,
                foreshadows = foreshadows,
                volumes = volumes,
                structure = structure,
                depth = depth,
                engineId = usedEngine,
                degraded = degraded,
                generatedAt = System.currentTimeMillis(),
            ),
            usedEngine,
            degraded,
        )
    }

    private fun actFunctionOf(index: Int, total: Int): String = when {
        total <= 1 -> "全书"
        index == 0 -> "铺垫：建立人物与核心矛盾"
        index == total - 1 -> "高潮：对决与回收"
        index * 2 < total -> "升级：冲突扩大"
        else -> "转折：中点反转与最低谷"
    }

    /** 保证章节序号连续无缺口。模型漏章时补占位，宁可有骨架也不要断号。 */
    private fun fillMissingOrders(
        chapters: List<ChapterPlan>,
        start: Int,
        end: Int,
        wordsPerChapter: Int,
    ): List<ChapterPlan> {
        val byOrder = chapters.associateBy { it.order }
        return (start..end).map { o ->
            byOrder[o] ?: ChapterPlan(
                order = o,
                title = "第${o}章",
                volumeIndex = 0,
                targetWords = wordsPerChapter,
            )
        }
    }

    private fun parseSettings(text: String): List<WorldSettingPlan> {
        val arr = JsonLite.extractArray(text) ?: return emptyList()
        return arr.mapNotNull { obj ->
            val name = JsonLite.str(obj, "name").orEmpty()
            if (name.isBlank()) return@mapNotNull null
            WorldSettingPlan(
                category = JsonLite.str(obj, "category").orEmpty().ifBlank { "其他" },
                name = name,
                content = JsonLite.str(obj, "content").orEmpty(),
                tags = JsonLite.str(obj, "tags").orEmpty(),
            )
        }
    }

    private fun parseCharacters(text: String): List<CharacterPlan> {
        val arr = JsonLite.extractArray(text) ?: return emptyList()
        return arr.mapNotNull { obj ->
            val name = JsonLite.str(obj, "name").orEmpty()
            if (name.isBlank()) return@mapNotNull null
            CharacterPlan(
                name = name,
                role = JsonLite.str(obj, "role").orEmpty(),
                gender = JsonLite.str(obj, "gender").orEmpty(),
                age = JsonLite.str(obj, "age").orEmpty(),
                appearance = JsonLite.str(obj, "appearance").orEmpty(),
                personality = JsonLite.str(obj, "personality").orEmpty(),
                background = JsonLite.str(obj, "background").orEmpty(),
                goal = JsonLite.str(obj, "goal").orEmpty(),
                arc = JsonLite.str(obj, "arc").orEmpty(),
                relationships = JsonLite.str(obj, "relationships").orEmpty(),
            )
        }
    }

    private fun parseForeshadows(text: String, totalChapters: Int): List<ForeshadowPlan> {
        val arr = JsonLite.extractArray(text) ?: return emptyList()
        return arr.mapNotNull { obj ->
            val title = JsonLite.str(obj, "title").orEmpty()
            if (title.isBlank()) return@mapNotNull null
            val planted = (JsonLite.int(obj, "plantedAt") ?: 1).coerceIn(1, totalChapters)
            val resolve = (JsonLite.int(obj, "plannedResolveAt") ?: 0).coerceIn(0, totalChapters)
            ForeshadowPlan(
                title = title,
                detail = JsonLite.str(obj, "detail").orEmpty(),
                plantedAt = planted,
                // 回收不得早于埋设 +3，否则伏笔等于没用
                plannedResolveAt = if (resolve < planted + 3) (planted + 3).coerceAtMost(totalChapters) else resolve,
                importance = (JsonLite.int(obj, "importance") ?: 2).coerceIn(1, 3),
            )
        }
    }

    private fun parseVolumeShells(text: String, expected: Int): List<VolumePlan> {
        val arr = JsonLite.extractArray(text) ?: return emptyList()
        return arr.mapIndexedNotNull { i, obj ->
            val t = JsonLite.str(obj, "title").orEmpty()
            if (t.isBlank()) return@mapIndexedNotNull null
            VolumePlan(
                index = i,
                title = t,
                synopsis = JsonLite.str(obj, "synopsis").orEmpty(),
                function = JsonLite.str(obj, "function").orEmpty(),
            )
        }.take(expected.coerceAtLeast(1))
    }

    private fun parseChapterPlans(
        text: String,
        start: Int,
        end: Int,
        wordsPerChapter: Int,
        charNames: String,
    ): List<ChapterPlan> {
        val arr = JsonLite.extractArray(text) ?: return emptyList()
        val validNames = charNames.split('、').filter { it.isNotBlank() && it != "（待定）" }.toSet()
        return arr.mapNotNull { obj ->
            val order = JsonLite.int(obj, "order") ?: return@mapNotNull null
            if (order !in start..end) return@mapNotNull null
            val title = JsonLite.str(obj, "title").orEmpty().ifBlank { "第${order}章" }
            ChapterPlan(
                order = order,
                title = title,
                volumeIndex = 0,
                event = JsonLite.str(obj, "event").orEmpty(),
                conflict = JsonLite.str(obj, "conflict").orEmpty(),
                emotionArc = JsonLite.str(obj, "emotion").orEmpty(),
                hook = JsonLite.str(obj, "hook").orEmpty(),
                foreshadow = JsonLite.str(obj, "foreshadow").orEmpty(),
                characters = JsonLite.arrayOf(obj, "characters")
                    .mapNotNull { JsonLite.rawString(it) }
                    .filter { validNames.isEmpty() || it in validNames },
                targetWords = wordsPerChapter,
                raw = buildString {
                    JsonLite.str(obj, "event")?.let { appendLine("事件：$it") }
                    JsonLite.str(obj, "conflict")?.let { appendLine("冲突：$it") }
                    JsonLite.str(obj, "emotion")?.let { appendLine("情绪：$it") }
                    JsonLite.str(obj, "hook")?.let { appendLine("钩子：$it") }
                    JsonLite.str(obj, "foreshadow")?.let { if (it.isNotBlank()) appendLine("伏笔：$it") }
                }.trim(),
            )
        }.sortedBy { it.order }
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private suspend fun call(
        system: String,
        user: String,
        maxTokens: Int,
        temperature: Float,
        stop: List<String> = emptyList(),
        jsonSchemaHint: String? = null,
    ): AiResponse = engine.complete(
        AiRequest(
            system = system,
            prompt = user,
            temperature = temperature,
            maxTokens = maxTokens,
            stop = stop,
            jsonSchemaHint = jsonSchemaHint,
        )
    )

    private fun maxTokensFor(targetWords: Int): Int =
        // 中文 1 字约 1 token（保守按 1.3 估），留 30% 余量
        (targetWords * 1.3f).toInt().coerceIn(512, 8192)

    /** 去掉模型爱加的开场白与 Markdown 装饰，只留正文。 */
    internal fun cleanBody(raw: String): String {
        var t = raw.trim()
        // 去掉 ``` 包裹
        if (t.startsWith("```")) {
            t = t.removePrefix("```").let { if (it.startsWith("markdown") || it.startsWith("md")) it.substringAfter('\n') else it }
            t = t.removeSuffix("```").trim()
        }
        val prefixes = listOf(
            "好的，以下是", "好的，这是", "以下是本章正文", "以下是正文", "这是本章正文",
            "当然可以", "没问题", "根据你的要求",
        )
        for (p in prefixes) {
            if (t.startsWith(p)) {
                t = t.substringAfter('\n', "").trim().ifEmpty { t }
                break
            }
        }
        // 去掉模型自说自话的尾部说明：逐行剥离末尾的元话语
        val metaLine = Regex(
            "^[\\s\\-—*]*(希望(这个|以上|这段|本章)|以上(就)?是|如需|如果(你)?(需要|想要|希望)|" +
                "(本章|全文)(完|结束)|（?本章完）?|注[：:]|说明[：:]|字数[：:])"
        )
        val lines = t.lines().toMutableList()
        while (lines.size > 1) {
            val last = lines.last().trim()
            val stripSep = last.isEmpty() || last.all { it == '-' || it == '—' || it == '*' }
            if (stripSep || metaLine.containsMatchIn(last)) lines.removeAt(lines.size - 1) else break
        }
        t = lines.joinToString("\n").trim()

        // 兜底：正文中段的「---」分隔线之后若全是元话语，整段截掉
        val sepIdx = t.lastIndexOf("\n---\n")
        if (sepIdx > 0) {
            val after = t.substring(sepIdx + 5).trim()
            if (after.isNotEmpty() && metaLine.containsMatchIn(after)) t = t.substring(0, sepIdx).trim()
        }
        return t.trim()
    }

    private fun parseConsistency(text: String): List<ConsistencyFinding> {
        val arr = JsonLite.extractArray(text) ?: return emptyList()
        return arr.mapNotNull { obj ->
            val evidence = JsonLite.str(obj, "evidence").orEmpty()
            if (evidence.isBlank() && JsonLite.str(obj, "conflict").isNullOrBlank()) return@mapNotNull null
            ConsistencyFinding(
                severity = JsonLite.int(obj, "severity") ?: 2,
                type = JsonLite.str(obj, "type").orEmpty().ifBlank { "设定冲突" },
                evidence = evidence,
                conflict = JsonLite.str(obj, "conflict").orEmpty(),
                fix = JsonLite.str(obj, "fix").orEmpty(),
            )
        }
    }

    private fun parseProofread(text: String): List<ProofreadFix> {
        val arr = JsonLite.extractArray(text) ?: return emptyList()
        return arr.mapNotNull { obj ->
            val original = JsonLite.str(obj, "original").orEmpty()
            if (original.isBlank()) return@mapNotNull null
            ProofreadFix(
                offset = JsonLite.int(obj, "offset") ?: -1,
                original = original,
                corrected = JsonLite.str(obj, "corrected").orEmpty(),
                reason = JsonLite.str(obj, "reason").orEmpty(),
            )
        }
    }

    private fun parseReview(text: String, source: String): ReviewVerdict {
        val obj = JsonLite.extractObject(text)
            ?: return ReviewVerdict(score = -1, verdict = text.take(200), aiFlavorRisk = guessAiFlavor(source))
        val problems = (JsonLite.arrayOf(obj, "problems")).mapNotNull { p ->
            val issue = JsonLite.str(p, "issue").orEmpty()
            if (issue.isBlank()) null else ReviewProblem(
                severity = JsonLite.int(p, "severity") ?: 2,
                issue = issue,
                fix = JsonLite.str(p, "fix").orEmpty(),
            )
        }
        return ReviewVerdict(
            score = JsonLite.int(obj, "score") ?: -1,
            verdict = JsonLite.str(obj, "verdict").orEmpty(),
            strengths = JsonLite.arrayOf(obj, "strengths").mapNotNull { JsonLite.str(it, "value") ?: JsonLite.rawString(it) },
            problems = problems,
            hookStrength = JsonLite.int(obj, "hookStrength") ?: -1,
            aiFlavorRisk = guessAiFlavor(source),
        )
    }

    /**
     * 本地 AI 腔检测，作为主编 Agent 的交叉验证（不额外消耗 token）。
     *
     * 委托给 [SlopDetector] 的词库 + 句式两层检测，并叠加**具体度**维度。
     * 早先这里只有 15 个硬编码词，抓不到句式模板（「不是…而是…」），
     * 也完全无法识别「一处套话都没有、但什么具体信息都没说」的段落——
     * 而那恰恰是 AI 文本最常见的形态。
     */
    private fun guessAiFlavor(text: String): Int {
        if (text.length < 60) return 0
        return SlopDetector.detect(text).riskLevel
    }

    private fun parseOutline(raw: String, volumeCount: Int, chaptersPerVolume: Int): List<OutlineVolume> {
        val lines = raw.lines()
        val volumes = mutableListOf<OutlineVolume>()
        var currentVolume: String? = null
        var currentChapters = mutableListOf<OutlineChapter>()

        fun flush() {
            val v = currentVolume ?: return
            volumes.add(OutlineVolume(v, currentChapters.toList()))
            currentChapters = mutableListOf()
        }

        for (line in lines) {
            val t = line.trim()
            if (t.isEmpty()) continue
            val isVolume = Regex("^#{1,3}\\s*第[一二三四五六七八九十\\d]+卷").containsMatchIn(t) ||
                Regex("^第[一二三四五六七八九十\\d]+卷").containsMatchIn(t) ||
                t.startsWith("## ") && t.contains("卷")
            if (isVolume) {
                flush()
                currentVolume = t.trimStart('#', ' ', '-', '*')
                continue
            }
            val chapterMatch = Regex("第\\s*(\\d+)\\s*章[：:、.\\s]*(.*)").find(t)
            if (chapterMatch != null) {
                val order = chapterMatch.groupValues[1].toIntOrNull() ?: (currentChapters.size + 1)
                val rest = chapterMatch.groupValues[2].trim().trimStart('-', '*', ' ')
                // 模型常写成「标题：本章说明」，拆开更利于 UI 展示
                val sepIdx = rest.indexOfFirst { it == '：' || it == ':' }
                val title: String
                val detail: String
                if (sepIdx in 1 until rest.length - 1) {
                    title = rest.substring(0, sepIdx).trim()
                    detail = rest.substring(sepIdx + 1).trim()
                } else {
                    title = rest
                    detail = t.trimStart('-', '*', ' ')
                }
                currentChapters.add(
                    OutlineChapter(order, title.ifBlank { "第${order}章" }, detail)
                )
            } else if (currentVolume != null && currentChapters.isNotEmpty() && t.length > 4) {
                // 续行并入上一章细纲
                val last = currentChapters.removeAt(currentChapters.size - 1)
                currentChapters.add(last.copy(detail = (last.detail + "\n" + t).trim()))
            }
        }
        flush()

        if (volumes.isEmpty()) {
            // 模型没按格式输出：降级为单卷 + 空章节，由用户在 UI 里手动拆
            return listOf(OutlineVolume("第一卷", emptyList()))
        }
        return volumes
    }
}

// ----------------------------------------------------------------------
// 数据类
// ----------------------------------------------------------------------

data class AgentResult<T>(
    val output: T,
    val engineId: String,
    /** true 表示由降级引擎（模板/规则）产出，UI 需提示用户 */
    val degraded: Boolean = false,
)

data class OutlinePlan(val rawText: String, val volumes: List<OutlineVolume>)

data class OutlineVolume(val title: String, val chapters: List<OutlineChapter>)

data class OutlineChapter(val order: Int, val title: String, val detail: String)

data class ConsistencyFinding(
    val severity: Int,
    val type: String,
    val evidence: String,
    val conflict: String,
    val fix: String,
)

data class ProofreadFix(
    val offset: Int,
    val original: String,
    val corrected: String,
    val reason: String,
)

data class ReviewVerdict(
    val score: Int,
    val verdict: String,
    val strengths: List<String> = emptyList(),
    val problems: List<ReviewProblem> = emptyList(),
    val hookStrength: Int = -1,
    /** 本地启发式计算的 AI 腔风险 0..100 */
    val aiFlavorRisk: Int = 0,
)

data class ReviewProblem(val severity: Int, val issue: String, val fix: String)

/**
 * 极简 JSON 解析工具（不引入序列化库，保证 core 模块零第三方依赖）。
 * 只覆盖 Agent 输出场景所需的子集，容错优先：解析失败一律返回 null 由调用方降级。
 */
internal object JsonLite {

    fun extractArray(text: String): List<Map<String, Any?>>? {
        val start = text.indexOf('[')
        val end = text.lastIndexOf(']')
        if (start < 0 || end <= start) return null
        // parseArray 现在保留标量元素（为了 options 这类字符串数组），
        // 这里只挑对象——调用方一律期望对象数组
        return parseArray(text.substring(start, end + 1)).filterIsInstance<Map<String, Any?>>()
    }

    /** 与 [extractArray] 对应，但保留全部元素（含标量），供顶层字符串数组使用。 */
    fun extractRawArray(text: String): List<Any?>? {
        val start = text.indexOf('[')
        val end = text.lastIndexOf(']')
        if (start < 0 || end <= start) return null
        return parseArray(text.substring(start, end + 1))
    }

    fun extractObject(text: String): Map<String, Any?>? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return parseObject(text.substring(start, end + 1))
    }

    fun str(map: Map<String, Any?>, key: String): String? = map[key]?.let { rawString(it) }

    fun int(map: Map<String, Any?>, key: String): Int? = when (val v = map[key]) {
        is Number -> v.toInt()
        is String -> v.trim().toIntOrNull()
        else -> null
    }

    fun rawString(v: Any?): String? = when (v) {
        null -> null
        is String -> v
        is Number, is Boolean -> v.toString()
        is Map<*, *> -> v["value"]?.let { rawString(it) } ?: v.entries.joinToString(",") { "${it.key}=${it.value}" }
        else -> v.toString()
    }

    @Suppress("UNCHECKED_CAST")
    fun arrayOf(map: Map<String, Any?>, key: String): List<Map<String, Any?>> {
        val v = map[key] ?: return emptyList()
        return when (v) {
            is List<*> -> v.mapNotNull { it as? Map<String, Any?> }
            else -> emptyList()
        }
    }

    /**
     * 读取**字符串数组**。
     *
     * 【为什么必须与 [arrayOf] 分开】
     * `arrayOf` 只保留 Map 元素，用于 `[{...},{...}]` 这类对象数组。
     * 但模型输出的字段里有大量**标量数组**，最典型的就是选择题的选项：
     *   "options": ["单城", "一界", "多元宇宙"]
     * 用 `arrayOf` 读它，每个元素都过不了 `as? Map` 的检查，结果是**静默返回空列表**。
     *
     * 这个 bug 曾在真实使用中表现为「问题显示出来了，但下面没有可点的选项」——
     * 因为选项数组为空，UI 端一个控件都渲染不出来。
     * 静默丢数据比抛异常危险得多，所以这里显式区分两种数组类型。
     */
    fun stringArrayOf(map: Map<String, Any?>, key: String): List<String> {
        val v = map[key] ?: return emptyList()
        return when (v) {
            is List<*> -> v.mapNotNull { rawString(it)?.takeIf { s -> s.isNotBlank() } }
            is String -> if (v.isBlank()) emptyList() else listOf(v)
            else -> emptyList()
        }
    }

    /**
     * 解析数组，**保留标量元素**。
     *
     * 【这里修的是一个真实 bug】
     * 第一版只收集 `{...}` 对象，遇到标量（字符串/数字）直接跳过。
     * 于是 `"options": ["单城","一界","多元宇宙"]` 解析出来是**空列表**——
     * 模型输出的那些选择题，到了界面上就只有题干、下面一片空白。
     *
     * 现在标量按 `{"value": 原值}` 包装进结果，与对象元素共存。
     * 读取端用 [stringArrayOf] 取值，对象数组的读取路径（[arrayOf]）不受影响
     * ——它只挑 Map 元素，包装后的标量不会被误取。
     */
    private fun parseArray(raw: String): List<Any?> {
        // 先剥掉外层括号。
        // 【这一步是必须的】否则循环遇到开头的 '[' 会走「嵌套数组」分支，
        // 拿整串再递归一次 —— 同样的输入、同样的函数，直接栈溢出。
        val s = raw.trim().removePrefix("[").removeSuffix("]")
        val out = mutableListOf<Any?>()
        var i = 0
        while (i < s.length) {
            when (val c = s[i]) {
                '{' -> {
                    val end = matchBrace(s, i)
                    if (end < 0) break
                    parseObject(s.substring(i, end + 1))?.let { out.add(it) }
                    i = end + 1
                }
                '[' -> {
                    // 嵌套数组：递归，结果本身作为一个元素
                    val end = matchBracket(s, i)
                    if (end < 0) break
                    out.add(parseArray(s.substring(i, end + 1)))
                    i = end + 1
                }
                '"' -> {
                    val end = findStringEnd(s, i)
                    if (end < 0) break
                    out.add(unescape(s.substring(i + 1, end)))
                    i = end + 1
                }
                ',', ' ', '\n', '\t', '\r', ']' -> i++
                else -> {
                    // 数字 / true / false / null：读到分隔符为止
                    var j = i
                    while (j < s.length && s[j] !in ",]") j++
                    val raw = s.substring(i, j).trim()
                    if (raw.isNotEmpty()) {
                        out.add(
                            raw.toIntOrNull() ?: raw.toDoubleOrNull()
                            ?: raw.toBooleanStrictOrNull() ?: raw
                        )
                    }
                    i = j
                }
            }
        }
        return out
    }

    private fun parseObject(s: String): Map<String, Any?>? {
        val body = s.trim().removePrefix("{").removeSuffix("}")
        val map = linkedMapOf<String, Any?>()
        var i = 0
        while (i < body.length) {
            while (i < body.length && body[i] != '"') i++
            if (i >= body.length) break
            val keyEnd = findStringEnd(body, i)
            if (keyEnd < 0) break
            val key = unescape(body.substring(i + 1, keyEnd))
            i = keyEnd + 1
            while (i < body.length && (body[i] == ' ' || body[i] == ':')) i++
            if (i >= body.length) break
            when (body[i]) {
                '"' -> {
                    val vEnd = findStringEnd(body, i)
                    if (vEnd < 0) break
                    map[key] = unescape(body.substring(i + 1, vEnd))
                    i = vEnd + 1
                }
                '[' -> {
                    val aEnd = matchBracket(body, i)
                    if (aEnd < 0) break
                    map[key] = parseArray(body.substring(i, aEnd + 1))
                    i = aEnd + 1
                }
                '{' -> {
                    val oEnd = matchBrace(body, i)
                    if (oEnd < 0) break
                    map[key] = parseObject(body.substring(i, oEnd + 1))
                    i = oEnd + 1
                }
                else -> {
                    var j = i
                    while (j < body.length && body[j] != ',' && body[j] != '}' && body[j] != ']') j++
                    val raw = body.substring(i, j).trim()
                    map[key] = raw.toIntOrNull() ?: raw.toDoubleOrNull() ?: raw.toBooleanStrictOrNull() ?: raw
                    i = j
                }
            }
            while (i < body.length && body[i] != ',' && body[i] != '"') i++
        }
        return if (map.isEmpty()) null else map
    }

    private fun findStringEnd(s: String, start: Int): Int {
        var i = start + 1
        while (i < s.length) {
            when (s[i]) {
                '\\' -> i += 2
                '"' -> return i
                else -> i++
            }
        }
        return -1
    }

    private fun matchBrace(s: String, start: Int): Int {
        var depth = 0
        var i = start
        while (i < s.length) {
            when (s[i]) {
                '"' -> { val e = findStringEnd(s, i); if (e < 0) return -1; i = e }
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return i }
            }
            i++
        }
        return -1
    }

    private fun matchBracket(s: String, start: Int): Int {
        var depth = 0
        var i = start
        while (i < s.length) {
            when (s[i]) {
                '"' -> { val e = findStringEnd(s, i); if (e < 0) return -1; i = e }
                '[' -> depth++
                ']' -> { depth--; if (depth == 0) return i }
            }
            i++
        }
        return -1
    }

    private fun unescape(s: String): String = s
        .replace("\\n", "\n")
        .replace("\\t", "\t")
        .replace("\\\"", "\"")
        .replace("\\\\", "\\")
        .replace("\\u003c", "<")
        .replace("\\u003e", ">")
}
