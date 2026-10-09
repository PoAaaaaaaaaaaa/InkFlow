package com.inkflow.core

import com.inkflow.core.domain.Chapter
import com.inkflow.core.domain.ChapterStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChapterWordCountTest {

    @Test
    fun countsCjkByChar() {
        assertEquals(4, Chapter.countWords("你好世界"))
    }

    @Test
    fun countsEnglishByWord() {
        assertEquals(3, Chapter.countWords("hello brave world"))
    }

    @Test
    fun countsMixedText() {
        // 「你好」2 字 + world 1 词 + 「世界」2 字 = 5
        assertEquals(5, Chapter.countWords("你好 world 世界"))
    }

    @Test
    fun ignoresPunctuationAndWhitespace() {
        assertEquals(4, Chapter.countWords("你好，世界！！  ")) // 4 个汉字，标点与空格不计
    }

    @Test
    fun emptyTextIsZero() {
        assertEquals(0, Chapter.countWords("   \n  "))
        assertEquals(0, Chapter.countWords(""))
    }

    @Test
    fun digitRunCountsAsOneWord() {
        assertEquals(5, Chapter.countWords("第 2026 年 3 月")) // 第+年+月 3 字，2026 与 3 各算 1 个词
    }
}

class DomainModelTest {

    @Test
    fun overdueForeshadowDetection() {
        val f = com.inkflow.core.domain.Foreshadow(
            id = "f1", projectId = "p", title = "断剑",
            plantedAt = 1, plannedResolveAt = 10,
        )
        assertTrue(!f.isOverdue(10))
        assertTrue(!f.isOverdue(13))
        assertTrue(f.isOverdue(14))  // 计划 10 + 宽限 3 => 第 14 章起预警
    }

    @Test
    fun resolvedForeshadowIsNotOverdue() {
        val f = com.inkflow.core.domain.Foreshadow(
            id = "f1", projectId = "p", title = "断剑",
            plantedAt = 1, plannedResolveAt = 5,
            status = com.inkflow.core.domain.ForeshadowStatus.Resolved,
        )
        assertTrue(!f.isOverdue(50))
    }

    @Test
    fun chapterStatusLabels() {
        assertEquals("草稿", ChapterStatus.Draft.label)
        assertEquals("定稿", ChapterStatus.Locked.label)
    }

    @Test
    fun targetWordsConvertedToWan() {
        val p = com.inkflow.core.domain.Project(id = "p", title = "书", targetWords = 1_000_000)
        assertEquals(100.0, p.targetWordsWan)
    }

    @Suppress("unused")
    private fun unusedChapterRef() = Chapter(id = "c", projectId = "p", title = "t")
}
