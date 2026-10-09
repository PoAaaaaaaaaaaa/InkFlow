package com.inkflow.core.corpus

import kotlinx.serialization.Serializable
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * 章节细纲。
 *
 * 五段式是刻意的：它对应 [com.inkflow.core.agent.Prompts.chapterOutlineSystem]
 * 的输出格式，这样模型生成的细纲可以被直接扩写成正文，不需要二次加工。
 */
@Serializable
data class ChapterPlan(
    /** 全书连续序号，从 1 开始 */
    val order: Int,
    val title: String,
    val volumeIndex: Int,

    /** 本章核心事件：这一章必须发生什么 */
    val event: String = "",
    /** 冲突点：谁在阻止，代价是什么 */
    val conflict: String = "",
    /** 情绪曲线：从什么情绪走到什么情绪 */
    val emotionArc: String = "",
    /** 结尾钩子 */
    val hook: String = "",
    /** 本章埋设或回收的伏笔 */
    val foreshadow: String = "",
    /** 出场人物 */
    val characters: List<String> = emptyList(),
    /** 建议字数 */
    val targetWords: Int = 3000,

    /** 细纲全文（模型原始输出），供「观览」页展示 */
    val raw: String = "",
) {
    /** 是否已由模型细化过。false 表示这是结构骨架，内容待生成。 */
    val isDetailed: Boolean get() = raw.isNotBlank() || event.isNotBlank()

    val summaryLine: String
        get() = buildString {
            if (event.isNotBlank()) append(event)
            if (conflict.isNotBlank()) {
                if (isNotEmpty()) append("｜冲突：")
                append(conflict)
            }
        }.ifBlank { "（细纲待生成）" }
}

/**
 * 分卷规划。
 */
@Serializable
data class VolumePlan(
    val index: Int,
    val title: String,
    val synopsis: String = "",
    /** 本卷在全书中承担的功能：铺垫/升级/转折/高潮 */
    val function: String = "",
    val chapters: List<ChapterPlan> = emptyList(),
) {
    val chapterCount: Int get() = chapters.size
    val totalWords: Int get() = chapters.sumOf { it.targetWords }
    val startOrder: Int get() = chapters.firstOrNull()?.order ?: 0
    val endOrder: Int get() = chapters.lastOrNull()?.order ?: 0
}

/**
 * 世界观设定条目。
 */
@Serializable
data class WorldSettingPlan(
    val category: String,
    val name: String,
    val content: String,
    val tags: String = "",
)

/**
 * 全书蓝图。
 *
 * 这是需求 2 的产出物：**可观的完整规划**，包含世界观 + 分卷 + 逐章细纲。
 * 生成后先展示给用户，确认了才写库——理由与 ADR-5 一致：
 * 规划是作品的地基，不能悄悄改掉作者的书架。
 */
@Serializable
data class Blueprint(
    val projectId: String,
    val title: String = "",
    val targetWords: Long = 0L,

    /** 世界观设定 */
    val settings: List<WorldSettingPlan> = emptyList(),
    /** 角色卡草案 */
    val characters: List<CharacterPlan> = emptyList(),
    /** 伏笔台账草案 */
    val foreshadows: List<ForeshadowPlan> = emptyList(),
    /** 分卷与逐章细纲 */
    val volumes: List<VolumePlan> = emptyList(),

    /** 生成时使用的结构参数，供 UI 解释「为什么是这个章数」 */
    val structure: BlueprintStructure = BlueprintStructure(),
    /** 生成时使用的深度档 */
    val depth: DepthProfile = DepthProfile(),
    /** 生成来源：engine id */
    val engineId: String = "",
    val degraded: Boolean = false,
    val generatedAt: Long = 0L,
) {
    val allChapters: List<ChapterPlan> get() = volumes.flatMap { it.chapters }
    val totalChapters: Int get() = allChapters.size
    val detailedChapters: Int get() = allChapters.count { it.isDetailed }
    val plannedWords: Int get() = allChapters.sumOf { it.targetWords }

    val isEmpty: Boolean get() = volumes.isEmpty()
    val isComplete: Boolean get() = volumes.isNotEmpty() && allChapters.isNotEmpty()

    /** 细纲完成度 0..1，UI 用它显示进度条。 */
    val detailProgress: Double
        get() = if (totalChapters == 0) 0.0 else detailedChapters.toDouble() / totalChapters

    fun chapter(order: Int): ChapterPlan? = allChapters.firstOrNull { it.order == order }

    /** 观览用的纯文本导出。 */
    fun toReadableText(): String = buildString {
        appendLine("《$title》全书蓝图")
        appendLine("目标字数：${targetWords / 10000} 万字 · 共 ${volumes.size} 卷 ${totalChapters} 章")
        appendLine()

        if (settings.isNotEmpty()) {
            appendLine("=".repeat(50))
            appendLine("世界观设定")
            appendLine("=".repeat(50))
            settings.groupBy { it.category }.forEach { (cat, list) ->
                appendLine()
                appendLine("【$cat】")
                list.forEach { s ->
                    appendLine("· ${s.name}")
                    s.content.lines().filter { it.isNotBlank() }.forEach { appendLine("    $it") }
                }
            }
            appendLine()
        }

        if (characters.isNotEmpty()) {
            appendLine("=".repeat(50))
            appendLine("角色")
            appendLine("=".repeat(50))
            characters.forEach { c ->
                appendLine()
                appendLine("· ${c.name}（${c.role}）")
                if (c.personality.isNotBlank()) appendLine("    性格：${c.personality}")
                if (c.goal.isNotBlank()) appendLine("    目标：${c.goal}")
                if (c.arc.isNotBlank()) appendLine("    弧光：${c.arc}")
                if (c.relationships.isNotBlank()) appendLine("    关系：${c.relationships}")
            }
            appendLine()
        }

        if (foreshadows.isNotEmpty()) {
            appendLine("=".repeat(50))
            appendLine("伏笔台账")
            appendLine("=".repeat(50))
            foreshadows.forEach { f ->
                appendLine("· ${f.title}（第 ${f.plantedAt} 章埋设，计划第 ${f.plannedResolveAt} 章回收）")
                if (f.detail.isNotBlank()) appendLine("    ${f.detail}")
            }
            appendLine()
        }

        volumes.forEach { v ->
            appendLine("=".repeat(50))
            appendLine("第${v.index + 1}卷 ${v.title}")
            appendLine("=".repeat(50))
            if (v.function.isNotBlank()) appendLine("卷功能：${v.function}")
            if (v.synopsis.isNotBlank()) appendLine("卷梗概：${v.synopsis}")
            appendLine("共 ${v.chapterCount} 章 / 约 ${v.totalWords / 10000.0} 万字")
            appendLine()
            v.chapters.forEach { c ->
                appendLine("第${c.order}章 ${c.title}（约 ${c.targetWords} 字）")
                if (c.event.isNotBlank()) appendLine("  事件：${c.event}")
                if (c.conflict.isNotBlank()) appendLine("  冲突：${c.conflict}")
                if (c.emotionArc.isNotBlank()) appendLine("  情绪：${c.emotionArc}")
                if (c.characters.isNotEmpty()) appendLine("  人物：${c.characters.joinToString("、")}")
                if (c.foreshadow.isNotBlank()) appendLine("  伏笔：${c.foreshadow}")
                if (c.hook.isNotBlank()) appendLine("  钩子：${c.hook}")
                appendLine()
            }
        }
    }
}

/**
 * 角色卡草案。
 */
@Serializable
data class CharacterPlan(
    val name: String,
    val role: String = "",
    val gender: String = "",
    val age: String = "",
    val appearance: String = "",
    val personality: String = "",
    val background: String = "",
    val goal: String = "",
    val arc: String = "",
    val relationships: String = "",
)

/**
 * 伏笔草案。
 */
@Serializable
data class ForeshadowPlan(
    val title: String,
    val detail: String = "",
    val plantedAt: Int = 0,
    val plannedResolveAt: Int = 0,
    val importance: Int = 2,
)

/**
 * 结构参数：由目标字数推导出的分卷分章方案。
 *
 * 【为什么必须由代码算，不能让模型算】
 * 让模型「按 100 万字规划分卷」，它给出的方案从 12 卷到 400 章都出现过，
 * 而且经常自相矛盾（说每卷 30 章，列出来 47 章）。
 * 结构是算术问题，算术归代码；模型只负责往结构里填内容。
 *
 * 参数取值的依据（网文商业连载的通行区间）：
 *  - 单章 2500~4000 字：低于此读者觉得亏，高于此手机端阅读疲劳；
 *  - 单卷 20~40 章：一卷是一个完整的「期待—兑现」周期；
 *  - 三幕比 25% / 50% / 25%：本项目 [Prompts.outlineUser] 已沿用的范式。
 */
@Serializable
data class BlueprintStructure(
    val targetWords: Long = 0L,
    val wordsPerChapter: Int = 3000,
    val chaptersPerVolume: Int = 30,
    val volumeCount: Int = 0,
    val chapterCount: Int = 0,
) {
    val actBreakdown: String
        get() = if (chapterCount == 0) "" else buildString {
            val first = (chapterCount * 0.25).toInt().coerceAtLeast(1)
            val second = (chapterCount * 0.50).toInt().coerceAtLeast(1)
            val third = chapterCount - first - second
            append("第一幕 1-${first} 章（建立人物与核心矛盾）· ")
            append("第二幕 ${first + 1}-${first + second} 章（升级冲突、中点反转、最低谷）· ")
            append("第三幕 ${first + second + 1}-$chapterCount 章（高潮与回收）")
        }

    companion object {
        /**
         * 每卷目标章数。
         *
         * 30 这个值来自商业连载的通行区间：一卷是一个完整的「期待—兑现」周期，
         * 低于 20 章读者还没建立起对角色的投入就要换卷，高于 45 章则单卷内的
         * 节奏容易失控（读者记不住这一卷的目标是什么）。
         */
        private const val TARGET_CHAPTERS_PER_VOLUME = 30

        /**
         * 按目标字数推导结构。
         *
         * @param chapterLengthBias 用户对单章长度的偏好偏移：
         *   -1 偏短（碎片化阅读）、0 标准、+1 偏长（沉浸式阅读）
         */
        fun of(targetWords: Long, chapterLengthBias: Int = 0): BlueprintStructure {
            val perChapter = when (chapterLengthBias) {
                -1 -> 2200
                1 -> 4200
                else -> 3000
            }

            val chapters = ceil(targetWords.toDouble() / perChapter).toInt().coerceAtLeast(1)

            // 卷数按「每卷约 30 章」反推，而不是查表。
            //
            // 【为什么不用查找表】第一版用的是分档表（<=200 章→5 卷、<=400 章→8 卷…），
            // 实测在 400 万字上崩了：算得 16 卷 × 84 章/卷，单卷塞进 84 章，
            // 无论从「一卷一个完整叙事弧」还是读者的阅读耐心看都不成立。
            // 分档表的毛病是它只保证档内正确，跨档就失控。
            // 用连续公式则天然落在 25-45 章/卷的窄带里，与目标字数无关。
            val volumes = maxOf(1, (chapters / TARGET_CHAPTERS_PER_VOLUME.toDouble()).roundToInt())

            // 章数按卷数均分后再微调，保证每卷章数尽量接近
            val perVolume = ceil(chapters.toDouble() / volumes).toInt()

            return BlueprintStructure(
                targetWords = targetWords,
                wordsPerChapter = perChapter,
                chaptersPerVolume = perVolume,
                volumeCount = volumes,
                chapterCount = chapters,
            )
        }
    }
}

/**
 * 由问答结果推导出的写作参数。
 *
 * 这一层把「用户的模糊偏好」翻译成「模型能执行的数字」，
 * 是需求 4 的核心：用户填的是「爽文」「快节奏」，
 * 生成时用的是「平均句长 14 字、段落 62 字、心理描写 8%」。
 */
object DepthResolver {

    /**
     * 从问答答案解出深度档与结构偏好。
     *
     * 输入来自 [IntakeSession.answers]，字段缺失时退回题材/基调推断，
     * 再缺就退回平衡档——三层兜底，保证任何输入都有合理输出。
     */
    fun resolve(
        genre: String,
        tone: String,
        audience: String,
        answers: Map<String, String> = emptyMap(),
    ): DepthProfile {
        val base = DepthProfile.infer(genre, tone, audience)

        // 节奏偏好是最强的信号，直接覆盖推断结果
        val pacing = answers["pacingPreference"].orEmpty()
        val pacingDelta = when {
            pacing.contains("快节奏") -> -18
            pacing.contains("慢热") -> +18
            pacing.contains("稳扎稳打") -> 2
            else -> 0
        }

        // 基调补充
        val toneAnswer = answers["tone"].orEmpty()
        val toneDelta = when {
            toneAnswer.contains("爽文") -> -15
            toneAnswer.contains("搞笑") -> -12
            toneAnswer.contains("悬疑") -> +10
            toneAnswer.contains("黑暗") || toneAnswer.contains("虐心") -> +8
            else -> 0
        }

        // 结局方向：明确「开放/留白」说明作者能接受非直给叙事，抬一档
        val ending = answers["endingDirection"].orEmpty()
        val endingDelta = when {
            ending.contains("开放") || ending.contains("留白") -> +10
            ending.contains("登顶") || ending.contains("大团圆") -> -8
            else -> 0
        }

        // 明确表态「通俗易懂」时给最强信号
        val readability = answers["readability"].orEmpty()
        val readabilityDelta = when {
            readability.contains("通俗") || readability.contains("好懂") || readability.contains("小白") -> -25
            readability.contains("有深度") || readability.contains("回味") -> +22
            else -> 0
        }

        val level = (base.level + pacingDelta + toneDelta + endingDelta + readabilityDelta)
            .coerceIn(5, 95)

        return DepthProfile(
            level = level,
            // 通俗向一定开类比；深度向一定关。中间地带看用户有没有表态。
            analogies = when {
                readabilityDelta < 0 -> true
                readabilityDelta > 0 -> false
                else -> level < 70
            },
            ambiguity = level >= 76,
        )
    }

    /** 单章长度偏好，从节奏答案推导。 */
    fun chapterLengthBias(answers: Map<String, String>): Int {
        val pacing = answers["pacingPreference"].orEmpty()
        return when {
            pacing.contains("快节奏") -> -1
            pacing.contains("慢热") -> 1
            else -> 0
        }
    }
}
