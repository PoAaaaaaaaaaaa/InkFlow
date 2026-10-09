package com.inkflow.core.corpus

import kotlinx.serialization.Serializable

/**
 * 感官通道。写作时按这个顺序轮换，避免全文只有一个「看」。
 */
@Serializable
enum class Sense(val label: String) {
    Sight("视觉"),
    Sound("听觉"),
    Smell("嗅觉"),
    Touch("触觉"),
    Temperature("温觉"),
    Body("体感"),
    ;

    companion object {
        fun from(label: String): Sense? = entries.firstOrNull { it.label == label }
    }
}

/**
 * 一个具体化词类。
 *
 * @param generic 被替换掉的抽象说法，如「很生气」
 * @param concrete 具体表现，如「他把杯子放回桌上，放得很轻」
 */
@Serializable
data class VaultEntry(
    val generic: String,
    val sense: Sense,
    val concrete: List<String>,
    /** 适用场景，供检索过滤：现代/古代/科幻/通用 */
    val scope: String = "通用",
)

/**
 * 具体化选词库。
 *
 * **设计立场**：AI 腔的根源不是「用了坏词」，而是**没有用具体的词**。
 * 一个模型在描述愤怒时，如果只能从「愤怒 / 恼怒 / 震怒」里选，
 * 它写出来的东西一定还是报告式的。要让它写出小说，得给它
 * **动作、体感、器物**——这些才是小说的词。
 *
 * 因此本库的组织维度是「抽象 → 具体」，而不是同义词表：
 * 左边是被替换的通用说法，右边是可直接落笔的具体表现。
 *
 * 使用方式见 [CorpusEngine.concreteSuggestions]：按当前段落检测到的抽象说法，
 * 只抽取相关条目注入提示词，避免把整个库塞进上下文。
 */
object SensoryVault {

    // ------------------------------------------------------------------
    // 情绪外化：最核心的一类
    // ------------------------------------------------------------------

    private val emotionEntries = listOf(
        VaultEntry("愤怒", Sense.Body, listOf(
            "把烟按灭在没抽完的位置",
            "放杯子的时候故意放重，然后再放轻",
            "重复对方最后一句话，一字不差",
            "笑了一下，但下巴是绷着的",
            "把椅子往后挪了半寸——不多，够起身就行",
            "说话的声音比平时低，但每个字之间隔得更开",
        )),
        VaultEntry("恐惧", Sense.Body, listOf(
            "发现自己在数呼吸",
            "先看门，再看人",
            "手掌贴到裤子上蹭了一下",
            "把手机握得很紧，然后发现屏幕上全是汗",
            "回答得比问题快",
        )),
        VaultEntry("悲伤", Sense.Body, listOf(
            "把对方的杯子收进水槽，但没洗",
            "反复解锁手机，看一眼，锁上",
            "坐着不动，直到膝盖发麻才换了个姿势",
            "用正常音量说话，但只说短句",
        )),
        VaultEntry("喜悦", Sense.Body, listOf(
            "在原地转了一圈才想起没人看",
            "把消息读了三遍，第三遍才敢信",
            "给一个不熟的人发了条无关的消息",
            "走路时踩了一下台阶边缘，没摔",
        )),
        VaultEntry("紧张", Sense.Body, listOf(
            "反复检查一个已经检查过的东西",
            "把该说的话在心里过一遍，然后说漏了一个词",
            "手指在桌沿上敲，又停",
            "喝完了一杯水，不记得什么时候倒的",
        )),
        VaultEntry("尴尬", Sense.Body, listOf(
            "找了一件根本不需要做的事做",
            "把话题接回去，接得比原话题更远",
            "笑了一声，只有一声",
            "盯着对方身后某个不存在的东西看",
        )),
        VaultEntry("疲惫", Sense.Body, listOf(
            "听不清别人说话，要请对方重复第二遍",
            "坐下的动作比站起来慢很多",
            "在等红灯的时候闭眼，绿灯亮了才睁开",
            "把外套挂在椅背上，挂了两次才挂住",
        )),
        VaultEntry("心虚", Sense.Body, listOf(
            "回答得太详细",
            "把一句真话夹在两句假话中间说",
            "主动提起一个别人根本没问的话题",
            "眼睛看着对方的鼻梁而不是眼睛",
        )),

        // ---- 感官：让环境具体 ----

        VaultEntry("声音/安静", Sense.Sound, listOf(
            "空调外机的声音忽然变得很明显",
            "听见隔壁有人在拧瓶盖",
            "他说话的时候，键盘停了",
            "远处有火车，听不出方向",
        )),
        VaultEntry("气味", Sense.Smell, listOf(
            "烟灰缸里的水味",
            "消毒水和旧木头混在一起",
            "雨还没下，但能闻到土的味道",
            "他身上的洗衣液是别人的牌子",
        )),
        VaultEntry("温度", Sense.Temperature, listOf(
            "后颈先热起来",
            "呼出的气在玻璃上留下痕迹，很快消失",
            "石头台阶是凉的，坐久了变得不那么凉",
            "摸到金属门把手的一瞬间缩了手",
        )),
        VaultEntry("触感", Sense.Touch, listOf(
            "纸上有一个凹下去的印子",
            "衣服领子磨着后颈的一道旧痕",
            "掌心的老茧刮过布料",
            "绳子勒出的痕迹是白的，过一会儿才变红",
        )),
        VaultEntry("光线", Sense.Sight, listOf(
            "光只照到桌子的前半部分，后半部分是暗的",
            "有人走过窗户，房间里暗了一下又亮回来",
            "屏幕的亮度是房间里最亮的东西",
            "灰尘在光里能看见，但看不见在不在动",
        )),
        VaultEntry("移动", Sense.Sight, listOf(
            "他走路的时候左肩比右肩低一点",
            "她把头发往耳后别了一次，后来又别了一次",
            "三个人进门，第一个停住，后面两个也停了",
            "起身的时候椅子腿在地上刮出一声",
        )),
    )

    // ------------------------------------------------------------------
    // 题材限定词库：让环境只属于这一场
    // ------------------------------------------------------------------

    private val genreEntries = listOf(
        // 科幻 / 星际
        VaultEntry("科幻场景", Sense.Sight, listOf(
            "气闸开的时候有压差，耳朵先有反应",
            "舱内的灯是冷白，人的脸看上去像没睡过",
            "屏幕上那行数字末位一直在跳，跳的速度比读数本身有用",
            "金属在真空里不受力，敲上去是闷的",
        ), scope = "科幻"),
        VaultEntry("科幻器物", Sense.Touch, listOf(
            "操作面是磨砂的，指腹按下去有轻微的粘滞感",
            "数据线接头是磁吸的，靠近时会自己吸上去",
            "冷却液管道的外壁结了一层霜，一碰就化",
        ), scope = "科幻"),
        // 古代 / 历史
        VaultEntry("古代场景", Sense.Sight, listOf(
            "廊下的灯没点，纸窗上只剩天光",
            "案上摊着昨夜的纸，边角被茶浸出一圈黄",
            "院子里有人在扫，扫帚声是慢的",
            "腰间的刀鞘磕在门框上，发出一声闷响",
        ), scope = "古代"),
        VaultEntry("古代器物", Sense.Touch, listOf(
            "竹简的绳子松了，翻页时会有一下顿",
            "瓷器内壁有细小的冰裂，手感是涩的",
            "笔尖的墨已经半干，要蘸两次才写得出",
        ), scope = "古代"),
        // 都市 / 现代
        VaultEntry("现代场景", Sense.Sound, listOf(
            "电梯到层的声音比人说话的声音大",
            "外卖电瓶车在楼下停了不到十秒",
            "楼上有人在拖椅子，拖了很久",
        ), scope = "现代"),
        VaultEntry("现代器物", Sense.Sight, listOf(
            "手机屏幕亮了，是群消息，不是他等的那个",
            "工牌挂在胸前，照片比本人年轻五年",
            "车里的味道是新的，什么都没放过",
        ), scope = "现代"),
    )

    val entries: List<VaultEntry> = emotionEntries + genreEntries

    /** 按通用说法检索具体表现。 */
    fun lookup(generic: String): VaultEntry? = entries.firstOrNull { it.generic == generic }

    fun byScope(scope: String): List<VaultEntry> = entries.filter { it.scope == scope }

    fun bySense(sense: Sense): List<VaultEntry> = entries.filter { it.sense == sense }

    /**
     * 生成「具体化选词」提示块。
     *
     * 这是整个语料库注入模型的主入口——不要求模型学词表，
     * 只要求它**从这几十个具体表现里挑，或照着这个颗粒度自己造**。
     * 后半句是关键：如果只说「从下面挑」，模型会照抄；
     * 说「照着这个颗粒度自己造」，它才会真的去写。
     */
    fun promptBlock(
        scope: String = "通用",
        maxEntries: Int = 8,
        perEntry: Int = 3,
    ): String {
        val pool = entries
            .filter { it.scope == "通用" || it.scope == scope }
            .byScopePriority(scope)
            .take(maxEntries)
        if (pool.isEmpty()) return ""
        return buildString {
            appendLine("【具体化选词库 · 照着这个颗粒度写，不要照抄】")
            appendLine("下面每一组，左边是抽象说法，右边是它的具体表现。")
            appendLine("写作时若落到左边这类情绪或场景，**必须**写成右边这种颗粒度的具体动作、器物、体感。")
            appendLine()
            pool.forEach { entry ->
                appendLine("▸ ${entry.generic}（${entry.sense.label}）")
                entry.concrete.take(perEntry).forEach {
                    appendLine("   · $it")
                }
            }
            appendLine()
            appendLine("注意：以上是样例，不是词表。直接照抄会被读者认出来——请写出**属于你这一场**的具体细节。")
        }
    }

    /**
     * 题材条目优先，通用条目兜底——保证任何题材都有料可用，
     * 同时让注入的具体写法尽量贴合当前作品的时代背景。
     *
     * 名字不叫 shuffle：这里**不引入随机性**。同一段 Prompt 每次组装结果一致，
     * 才能复现、才能 A/B。要多样性应该靠调 maxEntries，而不是靠摇骰子。
     */
    private fun List<VaultEntry>.byScopePriority(scope: String): List<VaultEntry> =
        sortedBy { if (it.scope == scope) 0 else 1 }

    val size: Int get() = entries.size
}
