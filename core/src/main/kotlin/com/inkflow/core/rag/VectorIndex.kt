package com.inkflow.core.rag

import kotlin.math.ln
import kotlin.math.sqrt

/**
 * 端侧向量库：为长篇写作提供本地 RAG。
 *
 * 设计目标（对齐 ZVec / mobile_rag_engine 的能力定位）：
 *  - **零部署**：纯 Kotlin，无 native 依赖，APK 直接内嵌，冷启动可用；
 *  - **毫秒级**：倒排索引 + 稀疏向量，避免全量 O(N·D) 点积；
 *  - **可增量**：写作过程中新章节随时 upsert，无需重建整库。
 *
 * 向量化采用**哈希技巧 + TF-IDF 稀疏向量**：
 * 中文按字 bigram + 词切分，英文按词。相比需要下载几百 MB 模型的
 * 神经网络 embedding，它在移动端能以 0 成本获得可用的语义召回——这是
 * "离线优先" 的关键取舍：宁要 0 依赖的 80 分，不要 300MB 的 95 分。
 */
class VectorIndex(
    /** 哈希空间维度。1024 在召回率与内存间取得平衡（约 4KB/千条）。 */
    private val dimensions: Int = 1024,
    /** 是否对 idf 加权，长文档语料建议开启 */
    private val useIdf: Boolean = true,
) {
    private data class Entry(
        val id: String,
        val docId: String,
        val text: String,
        val sparse: Map<Int, Float>,
        val norm: Float,
        val metadata: Map<String, String>,
    )

    private val entries = linkedMapOf<String, Entry>()

    /** 倒排索引：维度 -> 命中的条目 id 列表。检索时只扫这些桶。 */
    private val inverted = HashMap<Int, MutableList<String>>()

    /** 文档频次，用于 idf */
    private val docFreq = HashMap<Int, Int>()

    val size: Int get() = entries.size

    /** 增量写入/更新。相同 id 会覆盖，并同步修正倒排索引与 df。 */
    fun upsert(
        id: String,
        text: String,
        docId: String = id,
        metadata: Map<String, String> = emptyMap(),
    ) {
        remove(id)
        val tokens = tokenize(text)
        if (tokens.isEmpty()) return

        val tf = HashMap<Int, Float>()
        for (t in tokens) {
            val h = hash(t)
            tf[h] = (tf[h] ?: 0f) + 1f
        }
        for (h in tf.keys) {
            docFreq[h] = (docFreq[h] ?: 0) + 1
        }
        val sparse = HashMap<Int, Float>(tf.size)
        var sumSq = 0f
        for ((h, raw) in tf) {
            // 次线性 TF：抑制高频词，长章节不至于压垮短条目
            var w = 1f + ln(raw)
            if (useIdf) {
                val df = docFreq[h] ?: 1
                w *= ln(1.0 + (entries.size + 1).toDouble() / df).toFloat()
            }
            sparse[h] = w
            sumSq += w * w
        }
        val norm = sqrt(sumSq).coerceAtLeast(1e-6f)
        val entry = Entry(id, docId, text, sparse, norm, metadata)
        entries[id] = entry
        for (h in sparse.keys) {
            inverted.getOrPut(h) { mutableListOf() }.add(id)
        }
    }

    fun upsertAll(items: List<Triple<String, String, Map<String, String>>>) {
        items.forEach { (id, text, meta) -> upsert(id, text, metadata = meta) }
    }

    fun remove(id: String) {
        val old = entries.remove(id) ?: return
        for (h in old.sparse.keys) {
            inverted[h]?.remove(id)
            val df = (docFreq[h] ?: 1) - 1
            if (df <= 0) docFreq.remove(h) else docFreq[h] = df
        }
    }

    fun clear() {
        entries.clear()
        inverted.clear()
        docFreq.clear()
    }

    fun getText(id: String): String? = entries[id]?.text

    /**
     * 检索最相关的 k 条。
     *
     * @param filter 元数据过滤，例如只要同一作品的条目，避免跨作品串味
     */
    fun search(
        query: String,
        topK: Int = 6,
        filter: (Map<String, String>) -> Boolean = { true },
        minScore: Float = 0.01f,
    ): List<ScoredChunk> {
        val qTokens = tokenize(query)
        if (qTokens.isEmpty() || entries.isEmpty()) return emptyList()

        val qTf = HashMap<Int, Float>()
        for (t in qTokens) {
            val h = hash(t)
            qTf[h] = (qTf[h] ?: 0f) + 1f
        }
        var qSumSq = 0f
        val qSparse = HashMap<Int, Float>(qTf.size)
        for ((h, raw) in qTf) {
            var w = 1f + ln(raw)
            if (useIdf) {
                val df = docFreq[h] ?: 1
                w *= ln(1.0 + (entries.size + 1).toDouble() / df).toFloat()
            }
            qSparse[h] = w
            qSumSq += w * w
        }
        val qNorm = sqrt(qSumSq).coerceAtLeast(1e-6f)

        // 只累加倒排命中的桶 —— 这是「毫秒级」的来源
        val acc = HashMap<String, Float>()
        for ((h, qw) in qSparse) {
            val bucket = inverted[h] ?: continue
            for (eid in bucket) {
                val e = entries[eid] ?: continue
                val dw = e.sparse[h] ?: continue
                acc[eid] = (acc[eid] ?: 0f) + qw * dw
            }
        }

        return acc.entries
            .asSequence()
            .mapNotNull { (eid, dot) ->
                val e = entries[eid] ?: return@mapNotNull null
                if (!filter(e.metadata)) return@mapNotNull null
                val score = dot / (qNorm * e.norm)
                if (score < minScore) return@mapNotNull null
                ScoredChunk(e.id, e.docId, e.text, score, e.metadata)
            }
            .sortedByDescending { it.score }
            .take(topK)
            .toList()
    }

    /** 把检索结果拼成可注入 Prompt 的上下文块。 */
    fun buildContext(query: String, topK: Int = 6, maxChars: Int = 2400): String {
        val hits = search(query, topK)
        if (hits.isEmpty()) return ""
        val sb = StringBuilder()
        for (h in hits) {
            val tag = h.metadata["kind"] ?: "参考"
            val src = h.metadata["source"] ?: ""
            val line = "【$tag${if (src.isNotEmpty()) "·$src" else ""}】${h.text.trim()}\n"
            if (sb.length + line.length > maxChars) break
            sb.append(line)
        }
        return sb.toString()
    }

    private companion object {
        /**
         * 轻量分词：中文取单字 + 相邻 bigram（bigram 承载了大部分语义），
         * 英文/数字按连续串切分。不引入词典，保证 0 依赖与低内存。
         */
        fun tokenize(text: String): List<String> {
            if (text.isBlank()) return emptyList()
            val out = ArrayList<String>(text.length)
            var latin = StringBuilder()

            fun flushLatin() {
                if (latin.length >= 2) out.add(latin.toString().lowercase())
                else if (latin.length == 1) out.add(latin.toString().lowercase())
                latin = StringBuilder()
            }

            var prevCjk: Char? = null
            for (ch in text) {
                if (isCjk(ch)) {
                    flushLatin()
                    val single = ch.toString()
                    if (single.length == 1 && !isStopChar(ch)) out.add(single)
                    prevCjk?.let { out.add("$it$ch") }
                    prevCjk = ch
                } else if (ch.isLetterOrDigit()) {
                    latin.append(ch)
                    if (latin.length >= 24) flushLatin()  // 防超长串
                    prevCjk = null
                } else {
                    flushLatin()
                    prevCjk = null
                }
            }
            flushLatin()
            return out
        }

        private fun isStopChar(ch: Char): Boolean =
            ch in "的了在是我他她它们和与及也就都而及其这那有不会被把从对为以于"

        private fun isCjk(ch: Char): Boolean {
            val c = ch.code
            return (c in 0x4E00..0x9FFF) || (c in 0x3400..0x4DBF)
        }

        /** FNV-1a 哈希，稳定且分布均匀；用 and 掩码映射到 [0, dimensions)。 */
        fun hash(token: String, dimensions: Int = 1024): Int {
            var h = -0x7ee3623b  // 2166136261
            for (ch in token) {
                h = h xor ch.code
                h *= 16777619
            }
            return h and (dimensions - 1)
        }
    }

    // 因 companion 里 hash 需要实例维度，这里做一层实例转发
    private fun hash(token: String): Int = hash(token, dimensions)
}

data class ScoredChunk(
    val id: String,
    val docId: String,
    val text: String,
    val score: Float,
    val metadata: Map<String, String>,
)
