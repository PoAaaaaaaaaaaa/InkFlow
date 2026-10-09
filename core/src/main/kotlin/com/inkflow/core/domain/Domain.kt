package com.inkflow.core.domain

import kotlinx.serialization.Serializable

/**
 * 作品：一部小说的根聚合。
 *
 * 层级为 作品 → 分卷 → 章节，与 Room 中的表结构一一对应，
 * 但这里是**纯 Kotlin 领域模型**，不依赖 Android，便于单元测试与将来 KMP 复用。
 */
@Serializable
data class Project(
    val id: String,
    val title: String,
    val author: String = "",
    val genre: String = "",                 // 题材，如「东方玄幻」「都市悬疑」
    val logline: String = "",               // 一句话故事
    val premise: String = "",               // 核心设定/梗概
    val targetWords: Long = 1_000_000L,     // 目标字数
    /** 封面图的本地文件路径（应用私有目录），空表示未设置 */
    val coverPath: String = "",
    /** 写作视角，影响所有 Agent 的生成语气 */
    val narrativePerson: String = "第三人称",
    /** 基调，如「热血」「悬疑」「治愈」 */
    val tone: String = "",
    /** 目标读者，如「男频」「女频」「通用」 */
    val audience: String = "通用",
    /** 是否归档（书架分区展示） */
    val archived: Boolean = false,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
) {
    val targetWordsWan: Double get() = targetWords / 10_000.0
}

@Serializable
data class Volume(
    val id: String,
    val projectId: String,
    val title: String,
    val synopsis: String = "",
    val order: Int = 0,
)

@Serializable
data class Chapter(
    val id: String,
    val projectId: String,
    val volumeId: String? = null,
    val title: String,
    val order: Int = 0,
    /** 正文。长篇可达十万字量级，UI 侧必须走增量缓冲渲染。 */
    val content: String = "",
    /** 本章细纲：写作 Agent 的直接输入。 */
    val outline: String = "",
    /** 章节交接笔记：供下一章写作时注入上下文，降低长篇上下文丢失。 */
    val handoffNote: String = "",
    /** 状态机：draft(草稿) → polished(已润色) → locked(定稿) */
    val status: ChapterStatus = ChapterStatus.Draft,
    val wordCount: Int = 0,
    val qualityScore: Int = -1,
    val updatedAt: Long = 0L,
) {
    companion object {
        /**
         * 中文按字符计、英文按词计的字数统计。
         * 与网文平台口径保持一致，避免作者看到的字数与平台对不上。
         */
        fun countWords(text: String): Int {
            if (text.isBlank()) return 0
            var cjk = 0
            var latinWords = 0
            var inLatinWord = false
            for (ch in text) {
                if (isCjk(ch)) {
                    cjk++
                    inLatinWord = false
                } else if (ch.isLetterOrDigit()) {
                    if (!inLatinWord) {
                        latinWords++
                        inLatinWord = true
                    }
                } else {
                    inLatinWord = false
                }
            }
            return cjk + latinWords
        }

        private fun isCjk(ch: Char): Boolean {
            val code = ch.code
            return (code in 0x4E00..0x9FFF) ||   // 基本汉字
                (code in 0x3400..0x4DBF) ||      // 扩展 A
                (code in 0xF900..0xFAFF)         // 兼容汉字
        }
    }
}

enum class ChapterStatus(val label: String) {
    Draft("草稿"),
    Polished("已润色"),
    Locked("定稿"),
}

/**
 * 角色卡。一致性检查 Agent 的比对基准。
 */
@Serializable
data class Character(
    val id: String,
    val projectId: String,
    val name: String,
    val aliases: String = "",          // 别名/称呼，逗号分隔；用于一致性检查识别同一人
    val role: String = "",             // 主角/配角/反派/龙套
    val gender: String = "",
    val age: String = "",
    val appearance: String = "",
    val personality: String = "",
    val background: String = "",
    val goal: String = "",
    val arc: String = "",              // 人物弧光
    val relationships: String = "",
    val firstAppearChapter: Int = 0,
)

/**
 * 伏笔台账：追踪每条伏笔的埋设、推进与回收。
 *
 * 这是「AI 审阅 Agent 自动检测未回收伏笔」的数据基础——
 * 长篇创作中最容易崩的就是伏笔断线。
 */
@Serializable
data class Foreshadow(
    val id: String,
    val projectId: String,
    val title: String,
    val detail: String = "",
    /** 埋设章节序号（第几章） */
    val plantedAt: Int = 0,
    /** 最近一次推进的章节序号 */
    val advancedAt: Int = 0,
    /** 计划回收的章节序号，0 表示未定 */
    val plannedResolveAt: Int = 0,
    val status: ForeshadowStatus = ForeshadowStatus.Planted,
    val importance: Int = 2,           // 1低 2中 3高
    val notes: String = "",
) {
    /** 是否已经「逾期未回收」：超过计划章节 3 章仍未回收即预警。 */
    fun isOverdue(currentChapter: Int): Boolean =
        status != ForeshadowStatus.Resolved &&
            plannedResolveAt > 0 &&
            currentChapter > plannedResolveAt + 3
}

enum class ForeshadowStatus(val label: String) {
    Planted("已埋设"),
    Advanced("推进中"),
    Resolved("已回收"),
    Abandoned("已废弃"),
}

/**
 * 世界设定条目（设定集）。RAG 检索的重要语料来源。
 */
@Serializable
data class WorldSetting(
    val id: String,
    val projectId: String,
    val category: String = "",   // 地理/势力/力量体系/物品/历史
    val name: String,
    val content: String = "",
    val tags: String = "",
)
