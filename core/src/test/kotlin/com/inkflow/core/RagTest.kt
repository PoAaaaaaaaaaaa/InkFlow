package com.inkflow.core

import com.inkflow.core.domain.Chapter
import com.inkflow.core.domain.Character
import com.inkflow.core.domain.Foreshadow
import com.inkflow.core.domain.ForeshadowStatus
import com.inkflow.core.domain.WorldSetting
import com.inkflow.core.rag.NovelContextStore
import com.inkflow.core.rag.VectorIndex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VectorIndexTest {

    @Test
    fun emptyIndexReturnsNothing() {
        val idx = VectorIndex()
        assertTrue(idx.search("任何查询").isEmpty())
        assertEquals(0, idx.size)
    }

    @Test
    fun recallsSemanticallyRelevantEntry() {
        val idx = VectorIndex()
        idx.upsert("a", "林逸手持青锋剑，站在断魂崖上，风声呜咽。", metadata = mapOf("kind" to "前文"))
        idx.upsert("b", "苏晴在药庐里熬制回春丹，炉火映红了她的脸。", metadata = mapOf("kind" to "前文"))
        idx.upsert("c", "宗门大比将在三日后举行，弟子们摩拳擦掌。", metadata = mapOf("kind" to "前文"))

        val hits = idx.search("林逸 青锋剑 断魂崖", topK = 2)
        assertTrue(hits.isNotEmpty(), "应当有召回结果")
        assertEquals("a", hits.first().id)
    }

    @Test
    fun upsertOverwritesWithoutDoubleCount() {
        val idx = VectorIndex()
        idx.upsert("a", "第一次写入的内容")
        idx.upsert("a", "第二次写入的内容")
        assertEquals(1, idx.size)
        assertEquals("第二次写入的内容", idx.getText("a"))
    }

    @Test
    fun removedEntryIsNotRecalled() {
        val idx = VectorIndex()
        idx.upsert("a", "独一无二的紫电锤")
        idx.upsert("b", "普通的木棍")
        idx.remove("a")
        val hits = idx.search("紫电锤")
        assertTrue(hits.none { it.id == "a" })
    }

    @Test
    fun metadataFilterIsApplied() {
        val idx = VectorIndex()
        idx.upsert("a", "同一段关于龙族的设定描述", metadata = mapOf("projectId" to "p1"))
        idx.upsert("b", "同一段关于龙族的设定描述", metadata = mapOf("projectId" to "p2"))

        // filter 不是最后一个参数，需用具名实参传入
        val hits = idx.search("龙族设定", topK = 5, filter = { it["projectId"] == "p1" })
        assertEquals(1, hits.size)
        assertEquals("a", hits.first().id)
    }

    @Test
    fun buildContextProducesNonEmptyBlock() {
        val idx = VectorIndex()
        idx.upsert("a", "断魂崖位于北境，终年积雪。", metadata = mapOf("kind" to "设定", "source" to "地理"))
        val ctx = idx.buildContext("断魂崖 北境")
        assertTrue(ctx.isNotBlank())
        assertTrue(ctx.contains("设定"))
    }

    @Test
    fun resultsSortedByScoreDesc() {
        val idx = VectorIndex()
        idx.upsert("rel", "青云剑诀是青云门的镇派功法，威力无穷")
        idx.upsert("irrel", "今天的天气不错，适合晒被子")
        val hits = idx.search("青云剑诀 功法", topK = 5)
        assertTrue(hits.size >= 1)
        if (hits.size >= 2) {
            assertTrue(hits[0].score >= hits[1].score)
        }
    }
}

class NovelContextStoreTest {

    private fun chapter(order: Int, title: String, body: String) = Chapter(
        id = "c$order", projectId = "p1", title = title, order = order, content = body,
    )

    @Test
    fun contextIncludesRecalledCharacterAndSetting() {
        val store = NovelContextStore()
        store.rebuild(
            projectId = "p1",
            chapters = listOf(chapter(1, "初入宗门", "林逸踏入青云门，抬头望见九千九百级石阶。")),
            characters = listOf(
                Character(
                    id = "ch1", projectId = "p1", name = "林逸", role = "主角",
                    personality = "隐忍、记仇、护短", goal = "查出父亲失踪真相",
                )
            ),
            settings = listOf(
                WorldSetting(id = "s1", projectId = "p1", category = "地理", name = "青云门", content = "天下第一剑宗，坐落于青云山。")
            ),
            foreshadows = listOf(
                Foreshadow(id = "f1", projectId = "p1", title = "父亲留下的断剑", detail = "剑柄刻有上古符文", plantedAt = 1)
            ),
        )

        assertTrue(store.size() > 0)
        val ctx = store.buildWritingContext("p1", "林逸 青云门 断剑")
        assertTrue(ctx.promptBlock.isNotBlank(), "应生成非空上下文块")
        assertTrue(ctx.promptBlock.contains("林逸"), "应召回主角")
    }

    @Test
    fun noCrossProjectLeakage() {
        val store = NovelContextStore()
        store.indexCharacter("p1", Character(id = "a", projectId = "p1", name = "张三", role = "主角"))
        store.indexCharacter("p2", Character(id = "b", projectId = "p2", name = "张三", role = "主角"))

        val ctx = store.buildWritingContext("p1", "张三")
        val count = Regex("张三").findAll(ctx.promptBlock).count()
        assertTrue(count <= 1, "只应召回当前作品的条目，实际 $count 条")
    }

    @Test
    fun chunkedTextIsStillRetrievable() {
        val store = NovelContextStore()
        val body = (1..80).joinToString("\n") { "第${it}段落，紫霄神雷贯穿天地，万物寂灭。" }
        store.indexChapter("p1", chapter(1, "雷劫", body))
        val ctx = store.buildWritingContext("p1", "紫霄神雷")
        assertTrue(ctx.promptBlock.contains("紫霄神雷"))
    }
}
