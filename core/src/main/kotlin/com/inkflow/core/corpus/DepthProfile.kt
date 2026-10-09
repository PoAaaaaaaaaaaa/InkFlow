package com.inkflow.core.corpus

import kotlinx.serialization.Serializable

/**
 * 写作深度档位。
 *
 * 【为什么必须有具名档而不是只有一个 0-100 的滑块】
 * 作者说不清「深度 62 分」是什么意思，但立刻能判断自己写的是「爽文」还是「文学向」。
 * 具名档是给人用的接口，[DepthProfile.level] 是给模型用的参数。两者都存在，
 * 由 [DepthProfile.presets] 建立映射。
 */
@Serializable
enum class DepthTier(val label: String, val blurb: String, val level: Int) {
    Pulp(
        "纯爽文",
        "一句话能讲清的信息，绝不写两句话。爽点密度优先，读者不需要动脑。",
        10,
    ),
    Popular(
        "通俗",
        "大众阅读舒适区。口语化叙述，偶尔来一个漂亮的比喻，不追求回味。",
        32,
    ),
    Balanced(
        "平衡",
        "网文主流的厚度。有细节有节奏，爽点与铺垫并重，允许少量留白。",
        55,
    ),
    Layered(
        "有厚度",
        "人物动机与情绪要写到第二层。信息可以延迟给出，让读者自己拼。",
        76,
    ),
    Literary(
        "文学向",
        "形式本身参与表意。留白、象征、多义性都是合法手段，但读者门槛明显抬高。",
        92,
    ),
    ;

    companion object {
        fun of(level: Int): DepthTier =
            entries.minByOrNull { kotlin.math.abs(it.level - level) } ?: Balanced
    }
}

/**
 * 写作深度谱系。
 *
 * 【设计立场】
 * 「深度」这个词在写作指导里被滥用得最厉害——它通常被当成一个形容词丢给模型
 * （「写得更有深度一点」），而模型对此的响应是完全不可预测的：
 * 它可能加形容词堆砌、加比喻、加抒情段，恰好全是最容易变成 AI 腔的东西。
 *
 * 有效的深度控制必须**逐维度给具体指令**。本模块的八个维度全部是可判定的：
 * 每一个都能写出一条模型能执行的规则，而不是一个需要体会的形容词。
 *
 * 同时这里有一个刻意的取舍：**深度不等于好坏**。
 * [DepthTier.Pulp] 不是低配版，它有自己的一套明确要求（节奏压制、信息直给、
 * 零留白），照着写出来的是合格的爽文。把文学向的规则套给爽文只会两头不讨好。
 */
@Serializable
data class DepthProfile(
    /** 0-100，越小越通俗 */
    val level: Int = DepthTier.Balanced.level,

    /**
     * 是否启用「类比讲解」。
     *
     * 通俗向作品的常见需求：出现设定、术语、专业知识时，
     * 用生活化的类比当场解释清楚，不让读者卡住。
     * 深度向则相反——类比会稀释信息，宁可让读者自己消化。
     */
    val analogies: Boolean = true,

    /**
     * 是否允许章节内留白（不把话说完）。
     *
     * 爽文需要「说清楚」，文学向需要「留一半」。
     * 这条一旦设为 true，模型会开始省略解释、隐藏动机，
     * 对追求即时满足的读者是伤害，所以默认关闭。
     */
    val ambiguity: Boolean = false,
) {
    val tier: DepthTier get() = DepthTier.of(level)

    /** 通俗度：与 [level] 反向，用于按通俗度取阈值的场景。 */
    val accessibility: Int get() = 100 - level

    // ------------------------------------------------------------------
    // 逐维度阈值
    // ------------------------------------------------------------------

    /**
     * 目标平均句长（字）。
     *
     * 这是全谱系里**最能被读者直接感知**的一个指标，也是唯一能硬性检查的。
     * 通俗向 12-16 字（一口气读完），文学向 24-34 字（允许从句与插入语）。
     */
    val sentenceLengthTarget: Int get() = 12 + (level * 22) / 100

    /** 隐喻/比喻每千字上限。通俗向要克制（比喻多=阅读减速），深度向放开。 */
    val metaphorPer1kMax: Double get() = 0.6 + level * 0.09

    /** 心理与环境描写的目标段落占比。动作对话为主的爽文这一项必须低。 */
    val introspectionRatio: Double get() = 0.08 + level * 0.0042

    /** 每千字允许出现的、需要背景知识的术语上限。超了就是给读者设门槛。 */
    val jargonPer1kMax: Double get() = 0.4 + level * 0.16

    /** 段落目标字数。手机端阅读，通俗向段落必须更短。 */
    val paragraphLengthTarget: Int get() = 55 + level

    // ------------------------------------------------------------------
    // 生成提示词
    // ------------------------------------------------------------------

    /**
     * 生成可直接注入的深度约束块。
     *
     * 每条都是**可执行的规则**，而不是需要体会的形容词。
     * 这是本模块与「写深一点」这类空泛指令的全部区别。
     */
    fun toPromptBlock(): String = buildString {
        appendLine("【写作深度 · ${tier.label}（$level/100）】")
        appendLine(tier.blurb)
        appendLine()
        appendLine("具体执行标准（必须遵守）：")
        appendLine("- 句长：平均 ${sentenceLengthTarget} 字左右。${sentenceRule()}")
        appendLine("- 段落：平均 ${paragraphLengthTarget} 字。${paragraphRule()}")
        appendLine("- 比喻密度：每千字不超过 ${fmt(metaphorPer1kMax)} 次。${metaphorRule()}")
        appendLine("- 心理与环境描写占比：约 ${(introspectionRatio * 100).toInt()}%。${introspectionRule()}")
        appendLine("- 专业术语：每千字不超过 ${fmt(jargonPer1kMax)} 个。${jargonRule()}")
        if (analogies) {
            appendLine("- 类比讲解：**需要**。凡出现设定、术语、专业概念，当场用生活化的事物解释一遍，" +
                "让没有背景知识的读者也能跟上。解释要短，一句话带过，不要开小课堂。")
        } else {
            appendLine("- 类比讲解：**不需要**。概念直接使用，相信读者能自己理解。" +
                "不要为了照顾读者而降速解释。")
        }
        if (ambiguity) {
            appendLine("- 留白：**允许**。动机可以不写明，结局可以不给答案，情绪可以不说破。" +
                "但每一处留白都必须有明确的指向，不能是含糊其辞。")
        } else {
            appendLine("- 留白：**不允许**。每一件事都要交代清楚：谁做了什么、为什么、结果如何。" +
                "读者不该在章末产生「刚才那段是什么意思」的疑问。")
        }
    }

    private fun sentenceRule(): String = when {
        level < 25 -> "以短句为主，超过 30 字的句子每章不超过 5 处。一句话说一件事。"
        level < 50 -> "长短句交错，允许偶尔的长句用于铺陈，但不能连续出现。"
        level < 75 -> "长短并重，允许用长句承载复杂情境，用短句收束节奏。"
        else -> "长句是主要工具，允许从句、插入语、分号连接。短句只用于关键处的重击。"
    }

    private fun paragraphRule(): String = when {
        level < 25 -> "极短。一个动作或一句对话就可以独立成段。手机屏幕上不要出现整屏的文字。"
        level < 50 -> "短。以 2-3 句为一段，场景切换必须换段。"
        level < 75 -> "中等。允许一个完整的场景描写占满一段。"
        else -> "可长。允许一个段落承载一个完整的心理过程或环境铺陈。"
    }

    private fun metaphorRule(): String = when {
        level < 25 -> "几乎不用。写「像什么」会让读者停顿，爽文不能停顿。"
        level < 50 -> "偶尔。一章一两个即可，且必须是最易懂的那类。"
        level < 75 -> "正常使用。但要避免堆叠，一个喻体写透胜过三个喻体并列。"
        else -> "可以密集。比喻可以承担结构功能，而不只是修饰。"
    }

    private fun introspectionRule(): String = when {
        level < 25 -> "极少。情绪必须外化成动作或对话，禁止大段内心独白。"
        level < 50 -> "少量。可以在关键转折处给一两句心理，但不要展开。"
        level < 75 -> "适量。心理与外部动作应当交替推进。"
        else -> "可以充分展开。内心活动本身就是情节的一部分。"
    }

    private fun jargonRule(): String = when {
        level < 25 -> "尽量零术语。必须用到时，用最日常的说法替代。"
        level < 50 -> "少量。术语必须紧跟一句白话解释。"
        level < 75 -> "正常。假定读者知道该题材的基础概念。"
        else -> "不设限。可以用准确的领域词汇，读者愿意自己查。"
    }

    private fun fmt(d: Double) = String.format("%.1f", d)

    companion object {
        /** 全部具名档，供 UI 直接渲染成选择列表。 */
        val presets: List<DepthProfile>
            get() = DepthTier.entries.map { DepthProfile(level = it.level, analogies = it.level < 60, ambiguity = it.level >= 76) }

        /**
         * 按题材与受众推断一个合理的默认深度。
         *
         * 推断结果是**起点而非终点**——向导里用户可以覆盖。
         * 但只要用户没明确表态，默认值就该是合理的，而不是永远停在中间。
         */
        fun infer(genre: String, tone: String, audience: String): DepthProfile {
            var level = DepthTier.Balanced.level

            // 题材：现实/悬疑类天然偏深，爽文类天然偏浅
            when {
                listOf("现实", "悬疑", "推理", "历史", "军事").any { genre.contains(it) } -> level += 15
                listOf("玄幻", "仙侠", "武侠", "游戏", "竞技", "轻小说").any { genre.contains(it) } -> level -= 12
                listOf("都市", "言情", "科幻", "末世").any { genre.contains(it) } -> level -= 4
            }

            // 基调
            when {
                tone.contains("爽文") -> level -= 20
                tone.contains("搞笑") -> level -= 15
                tone.contains("热血") -> level -= 8
                tone.contains("黑暗") || tone.contains("虐心") -> level += 10
                tone.contains("治愈") || tone.contains("温情") -> level += 6
                tone.contains("悬疑") -> level += 12
            }

            // 受众
            when (audience) {
                "男频" -> level -= 6
                "女频" -> level += 4
                "全年龄" -> level -= 8
                "青年向" -> level += 10
            }

            val clamped = level.coerceIn(5, 95)
            return DepthProfile(
                level = clamped,
                // 通俗向默认开类比，深度向默认关；中间地带开
                analogies = clamped < 70,
                ambiguity = clamped >= 76,
            )
        }
    }
}
