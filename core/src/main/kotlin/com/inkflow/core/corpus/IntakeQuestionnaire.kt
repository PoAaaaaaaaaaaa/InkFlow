package com.inkflow.core.corpus

import kotlinx.serialization.Serializable

/**
 * 一个追问。
 *
 * @param field 对应作品字段，答完直接回写，不做二次映射
 * @param kind 问题类型，决定 UI 用什么控件
 * @param why 为什么要问这个。**这一项不能省**——
 *   作者知道「这题影响什么」才会认真答，否则一律跳过。
 */
@Serializable
data class IntakeQuestion(
    val id: String,
    val field: String,
    val question: String,
    val why: String,
    val kind: QuestionKind = QuestionKind.Text,
    val options: List<String> = emptyList(),
    val placeholder: String = "",
    /** 优先级：1 必答（不问 AI 就写不出可用大纲）/ 2 建议 / 3 可选 */
    val priority: Int = 2,
    /** 来源：local 由规则生成，ai 由模型生成 */
    val origin: String = "local",
) {
    val isRequired: Boolean get() = priority == 1
}

@Serializable
enum class QuestionKind {
    /** 单行文本 */
    Text,
    /** 多行文本 */
    LongText,
    /** 单选，从 [IntakeQuestion.options] 里挑 */
    Choice,
    /** 多选 */
    MultiChoice,
}

/**
 * 一次问诊的结果。
 */
@Serializable
data class IntakeSession(
    val projectId: String,
    val questions: List<IntakeQuestion>,
    /** 已回答：field -> answer */
    val answers: Map<String, String> = emptyMap(),
) {
    val pending: List<IntakeQuestion> get() = questions.filter { it.field !in answers }
    val required: List<IntakeQuestion> get() = questions.filter { it.isRequired }
    val answeredCount: Int get() = questions.count { it.field in answers }

    val progress: Double
        get() = if (questions.isEmpty()) 1.0 else answeredCount.toDouble() / questions.size

    /** 必答题是否已答完——只有答完才允许进入蓝图生成。 */
    val readyForBlueprint: Boolean
        get() = required.all { it.field in answers && answers[it.field]!!.isNotBlank() }

    fun answer(field: String, value: String): IntakeSession = copy(answers = answers + (field to value))

    /** 把答案组装成注入提示词的补充设定块。 */
    fun toContextBlock(): String {
        val answered = questions.filter { it.field in answers && answers[it.field]!!.isNotBlank() }
        if (answered.isEmpty()) return ""
        return buildString {
            appendLine("【作者补充说明（直接影响设定与大纲，必须遵守）】")
            answered.forEach { q ->
                val label = q.question.trimEnd('？', '?')
                appendLine("- $label：${answers[q.field]!!.trim()}")
            }
        }
    }
}

/**
 * 建作前提问生成器。
 *
 * 【架构立场：本地规则是主干，AI 是增补】
 * 提问功能在**没有 AI 引擎时也必须完整可用**。理由不是保守，是产品逻辑：
 * 用户刚建作品时最可能的状态就是「还没配模型」，这时候如果提问功能靠 AI，
 * 整个流程就断了。所以：
 *
 *  1. [localQuestions] 用规则扫描用户已填内容，找出真正缺失的维度——纯本地，零延迟；
 *  2. [merge] 把 AI 生成的语义追问合并进来，补充规则抓不到的题材特有问题；
 *  3. AI 不可用时，第 2 步静默跳过，用户在 1 的基础上照常走完流程。
 *
 * 【什么算「缺失」】
 * 不是「字段为空」这么简单。用户写了 200 字的核心设定，但里面全是世界观背景、
 * 没有一个字提到主角是谁——这比空字段更需要追问。所以检测要落在**语义维度**上。
 */
object IntakeQuestionnaire {

    /**
     * 扫描已填内容，生成缺口问题。纯本地，无引擎依赖。
     *
     * @param existing 已有字段：field -> value
     */
    fun localQuestions(existing: Map<String, String>): List<IntakeQuestion> {
        val out = mutableListOf<IntakeQuestion>()
        val title = existing["title"].orEmpty()
        val logline = existing["logline"].orEmpty()
        val premise = existing["premise"].orEmpty()
        val genre = existing["genre"].orEmpty()
        val tone = existing["tone"].orEmpty()
        val audience = existing["audience"].orEmpty()
        val targetWords = existing["targetWords"].orEmpty()
        val all = "$logline\n$premise"

        // ---- 优先级 1：不问就写不出可用大纲 ----

        if (!hasProtagonist(all)) {
            out += IntakeQuestion(
                id = "q_protagonist",
                field = "protagonistProfile",
                question = "主角是谁？他的身份、处境、最想要的东西分别是什么？",
                why = "大纲的每一章都要围着主角转。没有主角，规划 Agent 只能写出一份没有主角的编年史。",
                kind = QuestionKind.LongText,
                placeholder = "例：姜知序，穿越成饿死边缘的奴隶，只想先活过这个冬天",
                priority = 1,
            )
        }

        if (!hasConflict(all)) {
            out += IntakeQuestion(
                id = "q_conflict",
                field = "coreConflict",
                question = "主角要面对的核心矛盾是什么？谁或什么在阻止他？",
                why = "没有对抗就没有情节。这一条决定全书能不能持续产生冲突。",
                kind = QuestionKind.LongText,
                placeholder = "例：老城主把他当消耗品，而全城都在等着看他什么时候死",
                priority = 1,
            )
        }

        if (!hasGoldenFinger(all) && genre != "现实题材") {
            out += IntakeQuestion(
                id = "q_goldenfinger",
                field = "goldenFinger",
                question = "主角有什么别人没有的东西？（天赋、系统、信息差、身份都可以）",
                why = "${genre.ifBlank { "这类题材" }}的追读动力主要来自「他凭什么能做到别人做不到的事」。",
                kind = QuestionKind.LongText,
                placeholder = "例：每天可以发动一次百倍数量暴击，但只能二选一",
                priority = 2,
            )
        }

        if (targetWords.isBlank()) {
            out += IntakeQuestion(
                id = "q_targetwords",
                field = "targetWords",
                question = "计划写多少字？",
                why = "这一项直接决定分几卷、每卷几章、每章多少字。定错了后面很难改。",
                kind = QuestionKind.Choice,
                options = listOf("30 万字（短篇）", "100 万字（中长篇）", "300 万字（长篇）", "500 万字以上（超长篇）"),
                priority = 1,
            )
        }

        // ---- 优先级 2：显著影响质量 ----

        if (!hasEndingDirection(all)) {
            out += IntakeQuestion(
                id = "q_ending",
                field = "endingDirection",
                question = "结局大概是什么方向？（不用具体，给个调子就行）",
                why = "知道终点在哪，规划 Agent 才能在前中期埋对伏笔，而不是一路平推。",
                kind = QuestionKind.Choice,
                options = listOf("主角登顶 / 大团圆", "达成目标但付出代价", "开放式 / 留白", "还没想好，边写边看"),
                priority = 2,
            )
        }

        if (tone.isBlank()) {
            out += IntakeQuestion(
                id = "q_tone",
                field = "tone",
                question = "整体基调偏哪个方向？",
                why = "基调决定 AI 的用词强度与节奏密度，也决定写作深度默认值。",
                kind = QuestionKind.Choice,
                options = listOf("热血", "爽文", "悬疑", "治愈", "虐心", "搞笑", "黑暗", "温情"),
                priority = 2,
            )
        }

        if (!hasWorldRule(premise)) {
            out += IntakeQuestion(
                id = "q_worldrule",
                field = "worldRule",
                question = "这个世界最重要的那条规则是什么？（力量从哪来？代价是什么？）",
                why = "世界观只需要一条硬规则就能立住。规则越硬，后面的冲突越可信。",
                kind = QuestionKind.LongText,
                placeholder = "例：剑修分九境，每突破一境就要斩掉一段记忆",
                priority = 2,
            )
        }

        if (!hasPacingPreference(all)) {
            out += IntakeQuestion(
                id = "q_pacing",
                field = "pacingPreference",
                question = "希望节奏怎么走？",
                why = "节奏偏好会转成写作深度的具体参数：句长、段落长度、心理描写占比。",
                kind = QuestionKind.Choice,
                options = listOf("快节奏，每章都要有进展", "稳扎稳打，允许铺垫", "慢热，重氛围与人物"),
                priority = 2,
            )
        }

        // ---- 优先级 3：锦上添花 ----

        if (!hasRelationship(all)) {
            out += IntakeQuestion(
                id = "q_relationship",
                field = "keyRelationship",
                question = "有没有必须出现的关系线？（搭档、对手、亲人、师徒）",
                why = "关系线是长篇里最稳定的情节发生器，提前定下来能省很多构思成本。",
                kind = QuestionKind.Text,
                placeholder = "例：一个总跟他作对但关键时刻会救他的同乡",
                priority = 3,
            )
        }

        if (!hasTaboo(all)) {
            out += IntakeQuestion(
                id = "q_taboo",
                field = "taboo",
                question = "有什么是你明确不想写的？",
                why = "写进设定后 AI 会主动回避，比事后一章章删改省事得多。",
                kind = QuestionKind.Text,
                placeholder = "例：不写感情线；不写主角杀人；不要大段回忆",
                priority = 3,
            )
        }

        // 已填但过于笼统时，追问具体化
        if (logline.isNotBlank() && logline.length < 20) {
            out += IntakeQuestion(
                id = "q_logline_detail",
                field = "logline",
                question = "一句话故事能再具体一点吗？现在写的是「${logline.take(18)}」",
                why = "这一项会被逐字喂给规划 Agent。越具体，大纲越像是给你这本书写的。",
                kind = QuestionKind.LongText,
                placeholder = "包含「主角 + 困境 + 转折」三要素",
                priority = 2,
            )
        }

        // 总字数与题材严重不匹配时的提醒
        if (audience == "全年龄" && genre.contains("黑暗")) {
            out += IntakeQuestion(
                id = "q_audience_check",
                field = "audienceNote",
                question = "「$genre」配「全年龄」受众，尺度上有什么要求？",
                why = "这两个选择放在一起会限制 AI 的发挥空间，说清楚边界能让它少走弯路。",
                kind = QuestionKind.Text,
                priority = 3,
            )
        }

        return out.sortedBy { it.priority }
    }

    // ------------------------------------------------------------------
    // 语义检测
    // ------------------------------------------------------------------

    private fun hasProtagonist(text: String): Boolean {
        val markers = listOf("主角", "主人公", "他叫", "她叫", "男主", "女主", "少年", "青年", "老者")
        if (markers.any { text.contains(it) }) return true
        // 中文人名模式：2-3 字，前后有「是」「叫」等提示
        return Regex("(叫|名叫|名为|是)[\\u4e00-\\u9fff]{2,3}(，|。|、|的)").containsMatchIn(text)
    }

    private fun hasConflict(text: String): Boolean {
        val markers = listOf(
            "冲突", "对手", "敌人", "敌人是", "对抗", "阻止", "矛盾", "危机",
            "追杀", "复仇", "争夺", "竞争", "敌对", "反派", "boss",
        )
        return markers.any { text.contains(it, ignoreCase = true) }
    }

    private fun hasGoldenFinger(text: String): Boolean {
        val markers = listOf(
            "系统", "天赋", "金手指", "异能", "能力", "重生", "穿越", "传承",
            "血脉", "外挂", "觉醒", "继承", "记忆", "先知", "预知",
        )
        return markers.any { text.contains(it) }
    }

    private fun hasWorldRule(text: String): Boolean {
        val markers = listOf(
            "境界", "等级", "规则", "体系", "法则", "分.*层", "分.*阶", "分.*品",
            "代价", "门槛", "条件", "限制", "力量来源",
        )
        return markers.any { Regex(it).containsMatchIn(text) }
    }

    private fun hasEndingDirection(text: String): Boolean {
        val markers = listOf("结局", "最后", "最终", "终将", "回到", "成为", "登顶", "解放", "牺牲")
        return markers.any { text.contains(it) }
    }

    private fun hasPacingPreference(text: String): Boolean {
        val markers = listOf("节奏", "快节奏", "慢热", "铺垫", "每章", "爽点", "推进")
        return markers.any { text.contains(it) }
    }

    private fun hasRelationship(text: String): Boolean {
        val markers = listOf("搭档", "同伴", "师父", "徒弟", "兄弟", "姐妹", "亲情", "感情线", "对手", "宿敌")
        return markers.any { text.contains(it) }
    }

    private fun hasTaboo(text: String): Boolean {
        val markers = listOf("不写", "不要", "避免", "禁止", "无感情线", "不含")
        return markers.any { text.contains(it) }
    }

    // ------------------------------------------------------------------
    // 合并
    // ------------------------------------------------------------------

    /**
     * 建立一次问诊。
     *
     * @param aiQuestions AI 生成的补充追问，可为空
     */
    fun build(
        projectId: String,
        existing: Map<String, String>,
        aiQuestions: List<IntakeQuestion> = emptyList(),
    ): IntakeSession {
        val local = localQuestions(existing)
        val localFields = local.map { it.field }.toSet()

        // 合并时按 field 去重：本地规则优先。原因是本地的检测逻辑可复现、可测试，
        // 而模型可能就同一个缺口换个说法再问一遍，重复追问会立刻消耗掉用户的耐心。
        val merged = local + aiQuestions.filter { it.field !in localFields && it.field !in existing.keys }

        // 已填内容不为空的字段，不再追问（除非本地规则判定它「填了但不够」）
        val filtered = merged.filter { q ->
            val current = existing[q.field]
            current.isNullOrBlank() || q.id.startsWith("q_")
        }

        return IntakeSession(projectId = projectId, questions = filtered.sortedBy { it.priority })
    }
}
