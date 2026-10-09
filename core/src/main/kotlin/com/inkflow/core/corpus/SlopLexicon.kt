package com.inkflow.core.corpus

import kotlinx.serialization.Serializable

/**
 * AI 腔词条分类。
 *
 * 每一类都不只是「黑名单」，还带一句**为什么**——因为作者要学的是判断力，
 * 不是背一张表。表会过时，判断力不会。
 */
@Serializable
enum class SlopCategory(val label: String, val advice: String) {
    EmotionCliché(
        "情绪套话",
        "别给情绪命名，让读者自己读出来。「他很愤怒」是报告，「他把杯子放回桌上，放得很轻」才是小说。",
    ),
    UniversalScenery(
        "万能环境句",
        "环境句必须只属于这一场。换到任何一本书里都成立的句子，就是废句。",
    ),
    EmptyAdverb(
        "空洞副词",
        "「缓缓地/深深地/静静地」是节奏填充物。删掉之后句子通常更好——这是最好验证的一条。",
    ),
    TransitionAbuse(
        "转折滥用",
        "「然而/与此同时/这一刻」是论文连接词，不是小说连接词。转折要靠事件本身完成。",
    ),
    SummaryVoice(
        "总结腔",
        "小说只呈现，不总结。任何「总之/由此可见」都在把读者推出场景。",
    ),
    TemplateHook(
        "模板钩子",
        "「而这仅仅是开始」已经被用烂。钩子必须落在具体的人、物或数字上。",
    ),
    Parallelism(
        "排比滥用",
        "整齐的三段排比是 AI 最爱的节奏。真人的修辞是不对称的。",
    ),
    AbstractNoun(
        "抽象名词",
        "「某种/一种/那种」是没想清楚的信号。想清楚了自然就写具体了。",
    ),
    BodyCliché(
        "身体反应套话",
        "「瞳孔骤缩/嘴角勾起」是被用烂的漫画分镜。换成这个人独有的反应。",
    ),
    StackedModifier(
        "形容词堆砌",
        "两个以上形容词叠在同一个名词上，至少删掉一个。",
    ),
}

/**
 * 一条 AI 腔词条。
 *
 * @param severity 1 提示（可接受，但高频即报警）/ 2 建议（明显走味）/ 3 严重（一眼 AI）
 * @param alternatives 具体化方向。不是同义词替换——是「往哪个方向写才具体」。
 */
@Serializable
data class SlopEntry(
    val phrase: String,
    val category: SlopCategory,
    val severity: Int,
    val why: String,
    val alternatives: List<String> = emptyList(),
)

/**
 * 中文网络文学的 AI 腔词库。
 *
 * 语料来源：对通用大模型在中文长篇小说场景下高频产出的套话做过归并统计，
 * 再按「能否给出具体化方向」逐条筛选。只收有替代路径的条目——
 * 一条骂了但没法改的黑名单对作者没有价值。
 *
 * 与 [com.inkflow.core.quality.QualityEvaluator] 的分工：
 * 这里是**词层面的证据库**，质量层是**章层面的判定与扣分**。
 */
object SlopLexicon {

    // ------------------------------------------------------------------
    // 一、情绪套话：不给情绪命名，给动作
    // ------------------------------------------------------------------

    private val emotionClichés = listOf(
        SlopEntry(
            "心中五味杂陈", SlopCategory.EmotionCliché, 3,
            "六种味道混在一起等于什么都没说。",
            listOf("写他咽东西时喉咙卡了一下", "写他先笑，笑完才想起不该笑", "写他手指在桌面上敲了三下又停"),
        ),
        SlopEntry(
            "心中涌起一股暖流", SlopCategory.EmotionCliché, 3,
            "'一股'是量词偷懒，'暖流'是体感错位。",
            listOf("写后颈发烫", "写他忽然说不出话，只能点头", "写他把手插进口袋攥紧"),
        ),
        SlopEntry(
            "心情久久不能平静", SlopCategory.EmotionCliché, 3,
            "'久久'和'不能平静'是同一个意思说两遍。",
            listOf("写他躺下又坐起来，第三次点了烟", "写他数着天花板上的裂缝到天亮"),
        ),
        SlopEntry(
            "百感交集", SlopCategory.EmotionCliché, 3,
            "四字成语式的情绪总结，直接把镜头拉远。",
            listOf("选一个最强的感觉写透，其余留白"),
        ),
        SlopEntry(
            "思绪万千", SlopCategory.EmotionCliché, 3,
            "同上。'万千'是数字虚指，信息量为零。",
            listOf("只写此刻他真正在想的那一件事"),
        ),
        SlopEntry(
            "心中掀起惊涛骇浪", SlopCategory.EmotionCliché, 2,
            "把内心比作海，是最老的比喻之一。",
            listOf("写他表面上还在回答问题，但答错了人称"),
        ),
        SlopEntry(
            "一股寒意从脚底升起", SlopCategory.EmotionCliché, 2,
            "身体反应套话的标准型，'从脚底升起'是固定搭配。",
            listOf("写他发现自己握不住杯子", "写他听不见周围的声音了"),
        ),
        SlopEntry(
            "深深地看了他一眼", SlopCategory.EmotionCliché, 2,
            "「深深地」是空洞副词，「看一眼」是万能动作。两者相乘等于零。",
            listOf("写他看了多久（三秒）", "写他看的是哪个部位（领口的血）", "写他看完之后的动作"),
        ),
        SlopEntry(
            "眼神中闪过一丝异样", SlopCategory.BodyCliché, 3,
            "'一丝'+'闪过'+抽象名词，三层套话叠加。",
            listOf("写瞳孔的物理变化", "写他眨眼的频率变了", "写他突然不接了"),
        ),
        SlopEntry(
            "嘴角勾起一抹意味深长的弧度", SlopCategory.BodyCliché, 3,
            "这句话在训练语料里出现的次数比所有真人小说加起来都多。",
            listOf("写他笑了，但眼睛没动", "写他什么都没做，只是把烟按灭了"),
        ),
        SlopEntry(
            "瞳孔骤然收缩", SlopCategory.BodyCliché, 3,
            "漫画分镜的直接转写，文字里不成立。",
            listOf("写他手上的动作停了半拍", "写他重复了对方最后一句话"),
        ),
        SlopEntry(
            "不禁倒吸一口凉气", SlopCategory.BodyCliché, 3,
            "'不禁'是转折滥用，'倒吸一口凉气'是固定搭配。整句零信息。",
            listOf("写他后退了半步", "写他问了一个他知道答案的问题"),
        ),
        SlopEntry(
            "不由自主地", SlopCategory.EmptyAdverb, 2,
            "'不由自主'在替角色免责——好像他不是自己动的。",
            listOf("删掉。动作就是他的选择。"),
        ),
        SlopEntry(
            "情不自禁地", SlopCategory.EmptyAdverb, 2,
            "同上。",
            listOf("删掉。"),
        ),
    )

    // ------------------------------------------------------------------
    // 二、万能环境句：环境必须只属于这一场
    // ------------------------------------------------------------------

    private val sceneryClichés = listOf(
        SlopEntry(
            "空气仿佛凝固了", SlopCategory.UniversalScenery, 3,
            "被引用到失去意义的句子。",
            listOf("写在场的人都在等谁先开口", "写空调的声音变得很明显", "写有人把椅子往后挪了半寸"),
        ),
        SlopEntry(
            "时间仿佛静止", SlopCategory.UniversalScenery, 3,
            "「仿佛」+抽象名词，双重偷懒。",
            listOf("写具体的持续时长", "写期间发生的唯一一件事"),
        ),
        SlopEntry(
            "整个世界都安静了", SlopCategory.UniversalScenery, 3,
            "把镜头拉到宇宙尺度，读者的代入感反而断了。",
            listOf("只写他耳边的声音", "写远处的狗叫忽然听不见了"),
        ),
        SlopEntry(
            "夜色如墨", SlopCategory.UniversalScenery, 2,
            "四字比喻，所有朝代所有题材通用。",
            listOf("写此刻看得见什么、看不见什么", "写光源是什么、它照亮了哪一块"),
        ),
        SlopEntry(
            "月光如水", SlopCategory.UniversalScenery, 3,
            "同上，且比「夜色如墨」更老。",
            listOf("写月光落在什么材质上，是什么颜色"),
        ),
        SlopEntry(
            "夜凉如水", SlopCategory.UniversalScenery, 3,
            "同上。这是语料库里最泛滥的四个字之一。",
            listOf("写温度通过什么感觉到的（石凳、铁栏杆、呼出的气）"),
        ),
        SlopEntry(
            "阳光透过窗户洒进来", SlopCategory.UniversalScenery, 2,
            "「透过窗户洒进来」是模板的后半句，可以接任何地方。",
            listOf("写光落在哪个具体物件上、照出了什么"),
        ),
        SlopEntry(
            "一阵风吹过", SlopCategory.UniversalScenery, 2,
            "风在小说里的唯一作用是制造停顿。如果没在停顿，就别写风。",
            listOf("写风带来了什么气味或声音"),
        ),
    )

    // ------------------------------------------------------------------
    // 三、转折与总结：论文腔
    // ------------------------------------------------------------------

    private val transitionClichés = listOf(
        SlopEntry(
            "值得一提是", SlopCategory.SummaryVoice, 3,
            "这是文摘句式，不是小说句式。",
            listOf("删掉整句，或把这件事融进对话"),
        ),
        SlopEntry(
            "值得一提的是", SlopCategory.SummaryVoice, 3,
            "同上。",
            listOf("删掉。"),
        ),
        SlopEntry(
            "总而言之", SlopCategory.SummaryVoice, 3,
            "小说不做总结。",
            listOf("删掉。"),
        ),
        SlopEntry(
            "总之", SlopCategory.SummaryVoice, 3,
            "'总之'后面的内容如果重要，前面就不该写；如果不重要，这句就不该写。",
            listOf("删掉。"),
        ),
        SlopEntry(
            "不难看出", SlopCategory.SummaryVoice, 3,
            "对读者说话，破坏视角。",
            listOf("删掉。"),
        ),
        SlopEntry(
            "由此可见", SlopCategory.SummaryVoice, 3,
            "同上，论文连接词。",
            listOf("删掉。"),
        ),
        SlopEntry(
            "换句话说", SlopCategory.SummaryVoice, 2,
            "解释性句式。小说里最好的解释是不说。",
            listOf("删掉。"),
        ),
        SlopEntry(
            "与此同时", SlopCategory.TransitionAbuse, 2,
            "多线并进的连接词。用场景分隔或直接切镜更利落。",
            listOf("用空行切镜", "用另一个视角的一个动作开场"),
        ),
        SlopEntry(
            "这一刻", SlopCategory.TransitionAbuse, 2,
            "强调当下的万能前缀，高频出现会让全文变成一个节奏。",
            listOf("直接写那一刻发生的事，不用宣布"),
        ),
        SlopEntry(
            "在这一刻", SlopCategory.TransitionAbuse, 2,
            "同上，且更啰嗦。",
            listOf("删掉。"),
        ),
        SlopEntry(
            "不禁", SlopCategory.TransitionAbuse, 2,
            "'不禁'永远是多余的：既然写了这个动作，就说明他做了。",
            listOf("删掉。"),
        ),
        SlopEntry(
            "然而", SlopCategory.TransitionAbuse, 1,
            "不算错，但一章出现三次以上就是 AI 节奏。",
            listOf("用「但是」替换一半", "用句号断开，让转折隐含在语序里"),
        ),
    )

    // ------------------------------------------------------------------
    // 四、模板钩子：结尾最容易被 AI 写坏
    // ------------------------------------------------------------------

    private val hookClichés = listOf(
        SlopEntry(
            "而这，仅仅只是开始", SlopCategory.TemplateHook, 3,
            "起点评论区已经玩梗玩到烂。",
            listOf("停在最后一个具体动作上", "停在一句没回答的问话上"),
        ),
        SlopEntry(
            "真正的考验，才刚刚开始", SlopCategory.TemplateHook, 3,
            "把钩子的内容全部省略，只剩一句口号。",
            listOf("直接写那个考验的第一个具体表现"),
        ),
        SlopEntry(
            "然而他不知道的是", SlopCategory.TemplateHook, 3,
            "上帝视角预告，是廉价的悬念替代品。",
            listOf("改用信息差：让读者看到他不知道的东西"),
        ),
        SlopEntry(
            "殊不知", SlopCategory.TemplateHook, 3,
            "同上，且是文言残留。",
            listOf("删掉，直接写那个事实"),
        ),
        SlopEntry(
            "命运的齿轮开始转动", SlopCategory.TemplateHook, 3,
            "比喻空洞到可以放进任何一本书。",
            listOf("写一个具体的物理转动：齿轮、阀门、日历"),
        ),
        SlopEntry(
            "一切，都将改变", SlopCategory.TemplateHook, 3,
            "同上。",
            listOf("写改变的第一个可见后果"),
        ),
    )

    // ------------------------------------------------------------------
    // 五、抽象与堆砌
    // ------------------------------------------------------------------

    private val abstractClichés = listOf(
        SlopEntry(
            "某种", SlopCategory.AbstractNoun, 2,
            "'某种'表示作者没想清楚具体是哪种。",
            listOf("想清楚，然后写出来", "如果确实说不清，改写成角色的主观感受"),
        ),
        SlopEntry(
            "一种难以形容的", SlopCategory.AbstractNoun, 3,
            "'难以形容'是在向读者请假。",
            listOf("必须形容出来，这是作者的本职"),
        ),
        SlopEntry(
            "说不清道不明的", SlopCategory.AbstractNoun, 3,
            "同上，且更长。",
            listOf("同上。"),
        ),
        SlopEntry(
            "仿佛整个世界都", SlopCategory.AbstractNoun, 3,
            "把所有情绪放大到宇宙尺度，反而失真。",
            listOf("收回到这一个房间、这一个人"),
        ),
        SlopEntry(
            "深深地", SlopCategory.EmptyAdverb, 2,
            "'深深地'修饰的动词几乎都自带深度。",
            listOf("删掉。"),
        ),
        SlopEntry(
            "缓缓地", SlopCategory.EmptyAdverb, 2,
            "万能源速词。真正需要放慢时，应该靠事件而不是副词。",
            listOf("删掉。", "用「花了三分钟」这样的具体时长"),
        ),
        SlopEntry(
            "静静地", SlopCategory.EmptyAdverb, 2,
            "同上。",
            listOf("删掉。"),
        ),
        SlopEntry(
            "默默地", SlopCategory.EmptyAdverb, 2,
            "同上。",
            listOf("删掉。"),
        ),
        SlopEntry(
            "轻轻地", SlopCategory.EmptyAdverb, 2,
            "同上。",
            listOf("删掉，或写具体力度（只用了两根手指）"),
        ),
        SlopEntry(
            "淡淡的", SlopCategory.EmptyAdverb, 2,
            "'淡淡的'是形容词偷懒的集中营：淡淡的笑容、淡淡的忧伤、淡淡的光。",
            listOf("写颜色/亮度/程度的具体值"),
        ),
        SlopEntry(
            "这一刻，仿佛", SlopCategory.Parallelism, 3,
            "「这一刻」+「仿佛」是 AI 描写转折点的固定开头。",
            listOf("直接写动作", "用一个具体的声音或数字开场"),
        ),
        SlopEntry(
            "不仅是……更是", SlopCategory.Parallelism, 3,
            "递进式的对偶，典型的演讲腔，出现在小说里会立刻出戏。",
            listOf("只写更重要的那一半"),
        ),
        SlopEntry(
            "无尽地", SlopCategory.StackedModifier, 2,
            "'无尽'作为修饰语出现时，读者的感受恰恰是无。",
            listOf("给一个具体的上限"),
        ),
    )

    // ------------------------------------------------------------------
    // 六、句式模板（正则）
    // ------------------------------------------------------------------

    /**
     * 句式级套话。词表抓不到的，靠结构抓。
     */
    val patterns: List<SlopPattern> = listOf(
        SlopPattern(
            "不是……而是……",
            Regex("不是[^。！？\\n]{2,20}，?而是[^。！？\\n]{2,20}[。！？]"),
            SlopCategory.Parallelism,
            2,
            "对偶否定句。一章用两次以上就是 AI 节奏。",
            listOf("只说后半句", "用事实对比，不用句式对比"),
        ),
        SlopPattern(
            "与其说……不如说……",
            Regex("与其说[^。！？\\n]{2,20}[，,]?不如说[^。！？\\n]{2,20}[。！？]"),
            SlopCategory.Parallelism,
            3,
            "评论腔的对偶，直接把叙述者推到台前。",
            listOf("删掉整个判断，只留证据"),
        ),
        SlopPattern(
            "既是……也是……",
            Regex("既是[^。！？\\n]{2,20}[，,]?也是[^。！？\\n]{2,20}[。！？]"),
            SlopCategory.Parallelism,
            2,
            "同上。",
            listOf("选更重要的那一半写透"),
        ),
        SlopPattern(
            "AABB 型叠词堆叠",
            Regex("([\\u4e00-\\u9fff])\\1([\\u4e00-\\u9fff])\\2地"),
            SlopCategory.EmptyAdverb,
            2,
            "「缓缓地/静静地/悄悄地」这类叠词副词，是节奏填充物。",
            listOf("删掉副词，让动作自己承担速度"),
        ),
        SlopPattern(
            "连续三个四字格",
            Regex("([\\u4e00-\\u9fff]{4}[，、]){3,}"),
            SlopCategory.StackedModifier,
            3,
            "四字格连排是公文节奏，不是小说节奏。",
            listOf("拆成长短不齐的短句"),
        ),
        SlopPattern(
            "正如……一样",
            Regex("正如[^。！？\\n]{2,20}(一样|一般)[，,。！？]"),
            SlopCategory.SummaryVoice,
            2,
            "类比解释句式，把读者当外人。",
            listOf("删掉类比，直接写本体"),
        ),
    )

    // ------------------------------------------------------------------

    val entries: List<SlopEntry> =
        emotionClichés + sceneryClichés + transitionClichés + hookClichés + abstractClichés

    /** 按出现顺序排列的词表（长词优先，保证「深深地」先于「深」被匹配）。 */
    val phrases: List<String> = entries.map { it.phrase }.sortedByDescending { it.length }

    private val byPhrase: Map<String, SlopEntry> = entries.associateBy { it.phrase }

    fun lookup(phrase: String): SlopEntry? = byPhrase[phrase]

    fun byCategory(category: SlopCategory): List<SlopEntry> =
        entries.filter { it.category == category }

    /** 生成注入 Prompt 的禁忌清单。按严重度取，避免 Prompt 过长。 */
    fun blacklistBlock(maxSevere: Int = 24, maxAdvice: Int = 6): String = buildString {
        val severe = entries.filter { it.severity >= 3 }.take(maxSevere)
        if (severe.isEmpty()) return@buildString
        appendLine("【AI 腔禁忌清单 · 出现即判定走味】")
        appendLine("严禁使用以下词句：")
        appendLine(severe.joinToString("、") { it.phrase })
        appendLine()
        appendLine("更要紧的是避开它们背后的写法：")
        SlopCategory.entries.take(maxAdvice).forEach { c ->
            appendLine("- ${c.label}：${c.advice}")
        }
    }

    val size: Int get() = entries.size
}

/**
 * 句式级套话模式。
 *
 * 不加 `@Serializable`：`Regex` 没有内置序列化器，且模式是代码常量而非数据，
 * 序列化它没有意义。词条 [SlopEntry] 才需要落库。
 */
data class SlopPattern(
    val name: String,
    val regex: Regex,
    val category: SlopCategory,
    val severity: Int,
    val why: String,
    val alternatives: List<String> = emptyList(),
)
