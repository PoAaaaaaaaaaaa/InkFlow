package com.inkflow.core

import com.inkflow.core.domain.Chapter
import com.inkflow.core.domain.Character
import com.inkflow.core.domain.Foreshadow
import com.inkflow.core.domain.Project
import com.inkflow.core.domain.WorldSetting
import com.inkflow.core.rag.NovelContextStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 记忆层（RAG 可观测性）测试。
 *
 * 这些 API 是给「记忆层」界面用的：作者要能亲眼看到 AI 记住了什么。
 * 因此统计口径必须准确 —— 数字对不上比没有数字更伤信任。
 */
class MemoryLayerTest {

    private fun seedStore(): NovelContextStore {
        val store = NovelContextStore()
        store.rebuild(
            projectId = "p1",
            chapters = listOf(
                Chapter(
                    id = "c1", projectId = "p1", title = "初入宗门", order = 1,
                    content = "林逸踏入青云门，抬头望见九千九百级石阶。风很大，他握紧了手中的断剑。",
                    handoffNote = "林逸拜入青云门，得到断剑线索。",
                ),
                Chapter(
                    id = "c2", projectId = "p1", title = "夜探藏经阁", order = 2,
                    content = "深夜，林逸潜入藏经阁，在第七层发现了一卷残破的剑谱。",
                ),
            ),
            characters = listOf(
                Character(
                    id = "ch1", projectId = "p1", name = "林逸", role = "主角",
                    personality = "隐忍、记仇", goal = "查出父亲失踪真相",
                ),
                Character(id = "ch2", projectId = "p1", name = "苏晴", role = "女主"),
            ),
            settings = listOf(
                WorldSetting(id = "s1", projectId = "p1", category = "地理", name = "青云门", content = "天下第一剑宗。"),
                WorldSetting(id = "s2", projectId = "p1", category = "物品", name = "断剑", content = "剑柄刻有上古符文。"),
            ),
            foreshadows = listOf(
                Foreshadow(id = "f1", projectId = "p1", title = "父亲的断剑", detail = "符文有古怪", plantedAt = 1),
            ),
        )
        return store
    }

    @Test
    fun statsCountsAllMemoryTypes() {
        val stats = seedStore().stats("p1")
        assertTrue(stats.totalEntries > 0, "应有记忆条目")
        assertTrue(stats.totalChars > 0, "应有字数统计")
        assertTrue(stats.byKind.isNotEmpty(), "应有类别分布")

        val kinds = stats.byKind.map { it.kind }.toSet()
        assertTrue("角色" in kinds, "应包含角色类别，实际：$kinds")
        assertTrue("设定" in kinds, "应包含设定类别，实际：$kinds")
        assertTrue("伏笔" in kinds, "应包含伏笔类别，实际：$kinds")
        assertTrue("前文" in kinds, "应包含前文类别，实际：$kinds")
    }

    @Test
    fun statsAreIsolatedPerProject() {
        val store = seedStore()
        store.indexCharacter("p2", Character(id = "x", projectId = "p2", name = "别人家的主角"))

        val p1 = store.stats("p1")
        val p2 = store.stats("p2")
        assertTrue(p1.totalEntries > p2.totalEntries, "p1 应有更多记忆")
        assertEquals(1, p2.totalEntries, "p2 只应有 1 条")
    }

    @Test
    fun statsOnEmptyProjectIsEmpty() {
        val stats = NovelContextStore().stats("nonexistent")
        assertTrue(stats.isEmpty)
        assertEquals(0, stats.totalEntries)
    }

    @Test
    fun browseFiltersByKind() {
        val store = seedStore()
        val all = store.browse("p1")
        val chars = store.browse("p1", kind = "角色")

        assertTrue(all.size > chars.size, "全部应多于角色")
        assertTrue(chars.isNotEmpty())
        assertTrue(chars.all { it.kind == "角色" }, "过滤后应只有角色")
        assertTrue(chars.any { it.textPreview.contains("林逸") })
    }

    @Test
    fun browseRespectsPagination() {
        val store = seedStore()
        val page1 = store.browse("p1", limit = 2)
        val page2 = store.browse("p1", offset = 2, limit = 2)

        assertEquals(2, page1.size)
        assertTrue(page1.map { it.id }.intersect(page2.map { it.id }.toSet()).isEmpty(), "分页不应重叠")
    }

    @Test
    fun browseNeverLeaksOtherProjects() {
        val store = seedStore()
        store.indexCharacter("p2", Character(id = "x", projectId = "p2", name = "秘密角色"))
        val entries = store.browse("p1")
        assertTrue(entries.none { it.textPreview.contains("秘密角色") }, "不应看到其他作品的记忆")
    }

    @Test
    fun previewReturnsPromptBlockAndHits() {
        val store = seedStore()
        val preview = store.preview("p1", "林逸 断剑 青云门")

        assertTrue(preview.query.isNotBlank())
        assertTrue(preview.hits.isNotEmpty(), "应有命中")
        assertTrue(preview.promptBlock.isNotBlank(), "应生成可注入的上下文块")
        assertTrue(preview.charCount > 0)
        assertTrue(preview.promptBlock.contains("林逸"), "上下文应包含主角")
    }

    @Test
    fun previewHitsAreSortedByScoreDescending() {
        val store = seedStore()
        val preview = store.preview("p1", "青云门 断剑")
        val scores = preview.hits.map { it.score }
        assertEquals(scores.sortedDescending(), scores, "命中应按相关度降序")
    }

    @Test
    fun previewHitPercentIsBounded() {
        val store = seedStore()
        val preview = store.preview("p1", "林逸")
        preview.hits.forEach { hit ->
            assertTrue(hit.percent in 0..100, "百分比应在 0..100，实际 ${hit.percent}")
        }
    }

    @Test
    fun previewOnEmptyQueryStillReturnsSomething() {
        val store = seedStore()
        // 空查询不应崩溃；召回为空也算正常
        val preview = store.preview("p1", "")
        assertTrue(preview.hits.size >= 0)
    }

    @Test
    fun indexSizeReportsStructure() {
        val stats = seedStore().stats("p1")
        val size = stats.indexSize
        assertTrue(size.entries > 0)
        assertTrue(size.dimensions > 0, "应有倒排维度")
        assertTrue(size.postings >= size.entries, "倒排项应不少于条目数")
        assertTrue(size.avgTermsPerEntry > 0, "应有平均特征词")
        assertTrue(size.compressionRatio in 0.0..1.0, "压缩比应在 0..1")
    }

    @Test
    fun memoryStatsSurviveIncrementalIndexing() {
        val store = seedStore()
        val before = store.stats("p1").totalEntries

        // 写作过程中新增一章，索引应增量更新
        store.indexChapter(
            "p1",
            Chapter(
                id = "c3", projectId = "p1", title = "第三章", order = 3,
                content = "林逸在山道上遇袭，断剑第一次发光。",
            ),
        )
        val after = store.stats("p1").totalEntries
        assertTrue(after > before, "增量索引后条目应增加：$before -> $after")
    }
}

/**
 * 作品模型新增字段（封面 / 视角 / 基调 / 受众 / 归档）的行为测试。
 */
class ProjectExtendedFieldsTest {

    @Test
    fun coverPathDefaultsToEmpty() {
        val p = Project(id = "p", title = "书")
        assertEquals("", p.coverPath)
        assertTrue(!p.archived)
    }

    @Test
    fun narrativePersonDefaultsToThirdPerson() {
        assertEquals("第三人称", Project(id = "p", title = "书").narrativePerson)
    }

    @Test
    fun archivedCanBeToggled() {
        val p = Project(id = "p", title = "书", archived = true)
        assertTrue(p.archived)
        assertTrue(!p.copy(archived = false).archived)
    }

    @Test
    fun wizardFieldsAreCarriedThrough() {
        val p = Project(
            id = "p", title = "青云剑歌", genre = "东方玄幻",
            tone = "热血", audience = "男频", narrativePerson = "第一人称",
            targetWords = 3_000_000L, coverPath = "/data/covers/cover_p.jpg",
        )
        assertEquals("热血", p.tone)
        assertEquals("男频", p.audience)
        assertEquals("第一人称", p.narrativePerson)
        assertEquals(300.0, p.targetWordsWan)
        assertTrue(p.coverPath.isNotBlank())
    }
}
