package com.inkflow.app.ui.mvi

import com.inkflow.core.agent.ConsistencyFinding
import com.inkflow.core.agent.ReviewVerdict
import com.inkflow.core.domain.Chapter
import com.inkflow.core.domain.ChapterStatus
import com.inkflow.core.domain.Foreshadow
import com.inkflow.core.quality.QualityReport
import com.inkflow.core.style.StyleDna

/**
 * 写作台的不可变 UI 状态。
 *
 * 设计要点（MVI）：正文、大纲、AI 产出、质量报告全部收敛到这一个
 * 不可变快照里，多面板永远看到同一份数据，长篇写作不会出现
 * 「左边大纲已更新、右边正文还是旧的」这类状态错乱。
 */
data class WriterUiState(
    val projectId: String = "",
    val projectTitle: String = "",
    val genre: String = "",

    val chapters: List<Chapter> = emptyList(),
    val currentChapter: Chapter? = null,

    /** 正文渲染块。十万字章节按段落切成块交给 LazyColumn，只渲染可见行。 */
    val contentBlocks: List<TextBlock> = emptyList(),
    /** 正文总字数（实时） */
    val wordCount: Int = 0,
    /** 是否有未保存的修改 */
    val dirty: Boolean = false,
    val lastSavedAt: Long = 0L,

    /** AI 流式生成中的增量文本（尚未并入正文，便于用户预览后接受/丢弃） */
    val streamingText: String = "",
    val isGenerating: Boolean = false,
    val generationLabel: String = "",

    val outline: String = "",
    val handoffNote: String = "",

    val qualityReport: QualityReport? = null,
    val consistencyFindings: List<ConsistencyFinding> = emptyList(),
    val reviewVerdict: ReviewVerdict? = null,

    val styleDna: StyleDna? = null,
    val foreshadows: List<Foreshadow> = emptyList(),

    val activeEngineName: String = "",
    val activeEngineId: String = "",
    /** true 表示当前产出由离线兜底引擎提供，UI 必须显著提示 */
    val degraded: Boolean = false,

    /** 本次生成实际注入的检索上下文，供用户核对「AI 到底看到了什么」 */
    val lastContextPreview: String = "",

    val message: String? = null,
    val error: String? = null,
    val loading: Boolean = true,
) {
    val chapterTitle: String get() = currentChapter?.title.orEmpty()
    val chapterOrder: Int get() = currentChapter?.order ?: 0

    val canGenerate: Boolean get() = !isGenerating && currentChapter != null
    val targetWords: Int get() = currentChapter?.let { it.wordCount } ?: 0
}

/** 正文渲染块：段落级切片，LazyColumn 的最小复用单元。 */
data class TextBlock(
    val index: Int,
    /** 全文起始偏移，用于把质量问题定位回原文 */
    val start: Int,
    val text: String,
) {
    val isHeading: Boolean
        get() = text.trimStart().startsWith("第") &&
            (text.contains("章") && text.trim().length < 40)

    companion object {
        /**
         * 把长文本切成块。按空行分段；超长段落再按句号二次切分，
         * 保证单块高度可控，滚动时不会因为一个巨型 block 掉帧。
         */
        fun of(content: String, maxBlockChars: Int = 420): List<TextBlock> {
            if (content.isEmpty()) return emptyList()
            val blocks = ArrayList<TextBlock>(content.length / 200 + 8)
            var cursor = 0
            var index = 0

            fun addBlock(start: Int, text: String) {
                if (text.isEmpty()) return
                blocks.add(TextBlock(index++, start, text))
            }

            var paraStart = 0
            val sb = StringBuilder()
            var i = 0
            while (i <= content.length) {
                val atEnd = i == content.length
                val ch = if (atEnd) '\n' else content[i]
                if (ch == '\n') {
                    if (sb.isNotEmpty()) {
                        val para = sb.toString()
                        if (para.length <= maxBlockChars) {
                            addBlock(paraStart, para)
                        } else {
                            // 超长段落按句末标点二次切分
                            var segStart = 0
                            var j = 0
                            while (j < para.length) {
                                if (para[j] == '。' || para[j] == '！' || para[j] == '？') {
                                    if (j - segStart >= maxBlockChars) {
                                        addBlock(paraStart + segStart, para.substring(segStart, j + 1))
                                        segStart = j + 1
                                    }
                                }
                                j++
                            }
                            if (segStart < para.length) addBlock(paraStart + segStart, para.substring(segStart))
                        }
                        sb.clear()
                    }
                    if (atEnd) break
                    paraStart = i + 1
                    cursor = i + 1
                } else {
                    sb.append(ch)
                }
                i++
            }
            return blocks
        }
    }
}

/**
 * 用户意图（Intent）。所有交互都通过它进入，ViewModel 单点处理。
 */
sealed interface WriterIntent {
    data class Load(val projectId: String, val chapterId: String?) : WriterIntent

    /** 正文增量修改（编辑器每次输入） */
    data class EditContent(val newText: String) : WriterIntent
    data class EditOutline(val outline: String) : WriterIntent
    data class EditHandoff(val note: String) : WriterIntent
    data class RenameChapter(val title: String) : WriterIntent

    data object Save : WriterIntent
    data object ToggleChapterStatus : WriterIntent

    /** 生成整章 */
    data object GenerateChapter : WriterIntent
    /** 续写 */
    data class Continue(val instruction: String = "", val length: Int = 800) : WriterIntent
    data object Polish : WriterIntent
    data object Proofread : WriterIntent
    data object Review : WriterIntent
    data object CheckConsistency : WriterIntent

    /** 接受/丢弃 AI 流式产出 */
    data object AcceptStreaming : WriterIntent
    data object DiscardStreaming : WriterIntent
    data object CancelGeneration : WriterIntent

    data object RunQualityCheck : WriterIntent
    data object GenerateHandoff : WriterIntent
    data object RebuildStyleDna : WriterIntent

    /** 生成全书大纲并写入数据库 */
    data class GenerateOutline(val volumes: Int = 4, val chaptersPerVolume: Int = 25) : WriterIntent
    /** 依据蓝图生成角色与伏笔 */
    data object GenerateStoryBible : WriterIntent

    data class AddChapter(val title: String) : WriterIntent
    data object SelectChapter : WriterIntent

    data object DismissMessage : WriterIntent
    data object DismissError : WriterIntent
}

/** 一次性副作用（导航、Toast、滚动等），不进 State，避免重组时重复触发。 */
sealed interface WriterEffect {
    data class ShowToast(val text: String) : WriterEffect
    data class ScrollToBottom(val animate: Boolean) : WriterEffect
    data class NavigateToQuality(val chapterId: String) : WriterEffect
}
