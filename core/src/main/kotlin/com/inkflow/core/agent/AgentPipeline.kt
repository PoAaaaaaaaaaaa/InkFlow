package com.inkflow.core.agent

import com.inkflow.core.ai.AiCapability
import com.inkflow.core.ai.AiEngine
import com.inkflow.core.ai.AiRequest
import com.inkflow.core.ai.AiResponse
import com.inkflow.core.corpus.CorpusEngine
import com.inkflow.core.corpus.SlopDetector

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

    fun chapterWriteSystem(styleBlock: String, contextBlock: String, corpusBlock: String = "") = buildString {
        appendLine(WRITING_CONSTITUTION)
        // 语料库紧跟宪法：它是「怎么写」层，比文风 DNA 更需要被放在前排
        if (corpusBlock.isNotBlank()) {
            appendLine()
            appendLine(corpusBlock)
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
    ): AgentResult<String> {
        val corpusBlock = if (includeCorpus) {
            CorpusEngine.writingBlock(genre = genre)
        } else {
            CorpusEngine.writingBlock(genre = genre, includeDeconstruct = false)
        }
        val resp = call(
            system = Prompts.chapterWriteSystem(styleBlock, contextBlock, corpusBlock),
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
    ): AgentResult<String> {
        val system = buildString {
            appendLine(Prompts.CONTINUE_SYSTEM)
            // 续写场景下拆书参照意义不大（结构已经定了），但选词与禁忌清单必须带上
            val corpus = CorpusEngine.writingBlock(genre = genre, includeDeconstruct = false)
            if (corpus.isNotBlank()) { appendLine(); appendLine(corpus) }
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

    private fun parseArray(s: String): List<Map<String, Any?>> {
        val out = mutableListOf<Map<String, Any?>>()
        var i = 0
        while (i < s.length) {
            if (s[i] == '{') {
                val end = matchBrace(s, i)
                if (end < 0) break
                parseObject(s.substring(i, end + 1))?.let { out.add(it) }
                i = end + 1
            } else i++
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
