package com.inkflow.core.rag

import com.inkflow.core.domain.Chapter
import com.inkflow.core.domain.Character
import com.inkflow.core.domain.Foreshadow
import com.inkflow.core.domain.ForeshadowStatus
import com.inkflow.core.domain.WorldSetting

/**
 * 写作语境仓库：把一部作品的设定集、角色、伏笔、前文全部灌进 [VectorIndex]，
 * 供续写时自动召回「相关设定 / 前文伏笔 / 角色关系」。
 *
 * 这是长篇不崩的关键：模型上下文有限，但作品设定是无限的，
 * 必须靠检索把「此刻真正相关」的那一小块喂进去。
 */
class NovelContextStore(private val index: VectorIndex = VectorIndex()) {

    fun clear() = index.clear()

    fun size(): Int = index.size

    /** 全量重建索引（导入作品或批量修改设定后调用）。 */
    fun rebuild(
        projectId: String,
        chapters: List<Chapter>,
        characters: List<Character>,
        settings: List<WorldSetting>,
        foreshadows: List<Foreshadow>,
    ) {
        index.clear()
        indexChapterSummaries(projectId, chapters)
        indexCharacters(projectId, characters)
        indexSettings(projectId, settings)
        indexForeshadows(projectId, foreshadows)
    }

    fun indexChapter(projectId: String, chapter: Chapter) {
        if (chapter.content.isBlank() && chapter.outline.isBlank()) return
        // 章节粒度太大不利于精准召回，切成 ~600 字的块
        val chunks = chunk(chapter.content, 600)
        if (chunks.isEmpty()) {
            index.upsert(
                id = "outline:${chapter.id}",
                text = "${chapter.title}\n${chapter.outline}",
                docId = chapter.id,
                metadata = meta(projectId, "细纲", "第${chapter.order}章 ${chapter.title}"),
            )
            return
        }
        chunks.forEachIndexed { i, body ->
            index.upsert(
                id = "ch:${chapter.id}:$i",
                text = body,
                docId = chapter.id,
                metadata = meta(projectId, "前文", "第${chapter.order}章 ${chapter.title}"),
            )
        }
        if (chapter.handoffNote.isNotBlank()) {
            index.upsert(
                id = "handoff:${chapter.id}",
                text = chapter.handoffNote,
                docId = chapter.id,
                metadata = meta(projectId, "交接笔记", "第${chapter.order}章 ${chapter.title}"),
            )
        }
    }

    private fun indexChapterSummaries(projectId: String, chapters: List<Chapter>) {
        chapters.forEach { indexChapter(projectId, it) }
    }

    fun indexCharacter(projectId: String, c: Character) {
        val text = buildString {
            append("角色：").append(c.name)
            if (c.aliases.isNotBlank()) append("（别名：").append(c.aliases).append("）")
            append("\n身份：").append(c.role)
            if (c.gender.isNotBlank() || c.age.isNotBlank()) {
                append("\n").append(c.gender).append(' ').append(c.age)
            }
            if (c.appearance.isNotBlank()) append("\n外貌：").append(c.appearance)
            if (c.personality.isNotBlank()) append("\n性格：").append(c.personality)
            if (c.background.isNotBlank()) append("\n背景：").append(c.background)
            if (c.goal.isNotBlank()) append("\n目标：").append(c.goal)
            if (c.arc.isNotBlank()) append("\n人物弧光：").append(c.arc)
            if (c.relationships.isNotBlank()) append("\n关系：").append(c.relationships)
        }
        index.upsert(
            id = "char:${c.id}",
            text = text,
            docId = c.id,
            metadata = meta(projectId, "角色", c.name),
        )
    }

    private fun indexCharacters(projectId: String, list: List<Character>) {
        list.forEach { indexCharacter(projectId, it) }
    }

    fun indexSetting(projectId: String, s: WorldSetting) {
        index.upsert(
            id = "set:${s.id}",
            text = "[${s.category}] ${s.name}\n${s.content}",
            docId = s.id,
            metadata = meta(projectId, "设定", s.name),
        )
    }

    private fun indexSettings(projectId: String, list: List<WorldSetting>) {
        list.forEach { indexSetting(projectId, it) }
    }

    fun indexForeshadow(projectId: String, f: Foreshadow) {
        val state = when (f.status) {
            ForeshadowStatus.Planted -> "第${f.plantedAt}章埋设，尚未推进"
            ForeshadowStatus.Advanced -> "第${f.plantedAt}章埋设，第${f.advancedAt}章推进"
            ForeshadowStatus.Resolved -> "已在第${f.advancedAt}章回收"
            ForeshadowStatus.Abandoned -> "已废弃"
        }
        index.upsert(
            id = "fs:${f.id}",
            text = "伏笔：${f.title}\n${f.detail}\n状态：$state",
            docId = f.id,
            metadata = meta(projectId, "伏笔", f.title),
        )
    }

    private fun indexForeshadows(projectId: String, list: List<Foreshadow>) {
        list.forEach { indexForeshadow(projectId, it) }
    }

    /**
     * 为「写第 N 章」组装检索式上下文。
     *
     * 关键设计：**同时检索正文与台账**，并按类型分区输出。
     * 这样模型既看得到前文的行文语气，也看得到结构化的硬约束。
     */
    fun buildWritingContext(
        projectId: String,
        query: String,
        recentChapterTexts: List<String> = emptyList(),
        maxChars: Int = 3600,
    ): WritingContext {
        val onlyProject = { m: Map<String, String> -> m["projectId"] == projectId }

        val settings = index.search(query, topK = 5, filter = onlyProject)
            .filter { it.metadata["kind"] == "设定" }
        val chars = index.search(query, topK = 6, filter = onlyProject)
            .filter { it.metadata["kind"] == "角色" }
        val fores = index.search(query, topK = 5, filter = onlyProject)
            .filter { it.metadata["kind"] == "伏笔" }
        val prev = index.search(query, topK = 5, filter = onlyProject)
            .filter { it.metadata["kind"] == "前文" || it.metadata["kind"] == "交接笔记" }

        val sb = StringBuilder()
        fun section(title: String, block: String) {
            if (block.isBlank()) return
            val seg = "### $title\n$block\n"
            if (sb.length + seg.length <= maxChars) sb.append(seg)
        }

        section("相关设定", settings.joinToString("\n") { "- ${it.text.trim()}" })
        section("出场角色", chars.joinToString("\n") { "- ${it.text.trim()}" })
        section("待处理伏笔", fores.joinToString("\n") { "- ${it.text.trim()}" })

        // 最近章节正文优先用「原文尾部」而非检索结果：语气衔接靠原文最准
        val tail = recentChapterTexts.joinToString("\n\n") { it.takeLast(1200) }
        if (tail.isNotBlank()) {
            val seg = "### 最近正文（原文结尾，务必衔接语气）\n$tail\n"
            if (sb.length + seg.length <= maxChars) sb.append(seg)
            else section("相关前文", prev.joinToString("\n") { "- ${it.text.trim()}" })
        } else {
            section("相关前文", prev.joinToString("\n") { "- ${it.text.trim()}" })
        }

        return WritingContext(
            promptBlock = sb.toString(),
            hitSettings = settings.map { it.text },
            hitCharacters = chars.map { it.text },
            hitForeshadows = fores.map { it.text },
            hitPrevious = prev.map { it.text },
        )
    }

    private fun meta(projectId: String, kind: String, source: String) =
        mapOf("projectId" to projectId, "kind" to kind, "source" to source)

    /** 按段落边界切块，尽量不切断句子。 */
    private fun chunk(text: String, size: Int): List<String> {
        if (text.isBlank()) return emptyList()
        val out = ArrayList<String>()
        val paras = text.split('\n')
        val buf = StringBuilder()
        for (p in paras) {
            if (buf.length + p.length + 1 > size && buf.isNotEmpty()) {
                out.add(buf.toString())
                buf.clear()
            }
            if (p.length > size) {
                // 超长段落硬切
                var i = 0
                while (i < p.length) {
                    val end = minOf(i + size, p.length)
                    out.add(p.substring(i, end))
                    i = end
                }
            } else {
                buf.append(p).append('\n')
            }
        }
        if (buf.isNotBlank()) out.add(buf.toString())
        return out
    }
}

data class WritingContext(
    val promptBlock: String,
    val hitSettings: List<String> = emptyList(),
    val hitCharacters: List<String> = emptyList(),
    val hitForeshadows: List<String> = emptyList(),
    val hitPrevious: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = promptBlock.isBlank()
}
