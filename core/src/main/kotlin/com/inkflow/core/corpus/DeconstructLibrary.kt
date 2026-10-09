package com.inkflow.core.corpus

import kotlinx.serialization.Serializable

/**
 * 一条拆书结论。
 *
 * 拆书的产出**不是**剧情梗概——梗概对写作毫无帮助。
 * 有用的是「这本书靠什么机制留住读者」，因为机制可以迁移到别的题材。
 *
 * @param mechanism 机制本身：可迁移的、与题材无关的做法
 * @param evidence 书中的证据。没有证据的结论只是读后感。
 * @param transferable 迁移到别的题材时怎么用——这一步才是拆书的价值所在
 */
@Serializable
data class DeconstructFinding(
    val aspect: DeconstructAspect,
    val mechanism: String,
    val evidence: String,
    val transferable: String,
)

@Serializable
enum class DeconstructAspect(val label: String) {
    OpeningHook("开篇钩子"),
    GoldenFinger("金手指设计"),
    Pacing("节奏结构"),
    InfoRelease("信息释放"),
    CharacterVoice("角色声音"),
    Hook("章末钩子"),
    ReaderPayoff("爽点兑现"),
    WorldBuilding("世界观铺设"),
    RealityAnchor("真实感锚点"),
}

/**
 * 一本书的拆书卡。
 *
 * @param verified 元数据是否经官方书站页面核对。**为 false 时 [findings] 必须为空**——
 *   没读到就不能拆，编造拆书结论比数据缺失恶劣得多。
 * @param caveat 样本量警示。例如「仅 6 章，只能做开局拆解」。
 *   带这一项的书，其结论适用范围必须被明确限定。
 */
@Serializable
data class DeconstructCard(
    val title: String,
    val author: String = "",
    val genre: String = "",
    val tags: List<String> = emptyList(),
    val logline: String = "",
    val protagonist: String = "",
    val goldenFinger: String = "",
    val scale: String = "",
    val findings: List<DeconstructFinding> = emptyList(),
    val verified: Boolean = false,
    val source: String = "",
    val caveat: String = "",
) {
    val hasFindings: Boolean get() = findings.isNotEmpty()

    fun findingsOf(aspect: DeconstructAspect): List<DeconstructFinding> =
        findings.filter { it.aspect == aspect }
}

/**
 * 拆书作品库。
 *
 * **为什么需要它**：给模型提示词里塞「文笔好一点」是没用的；
 * 塞一本具体的书和它留住读者的机制，模型才知道该往哪个方向使劲。
 * 更关键的是——这些机制是**可迁移**的，换题材照样成立。
 *
 * **数据诚实原则**：
 *  1. 每本书标注 [DeconstructCard.verified]，未核实的条目不提供技法拆解；
 *  2. 样本量不足的书标注 [DeconstructCard.caveat]，结论适用范围被限定；
 *  3. 未核实的书名保留在 [unverifiedTitles] 里显式展示为缺口，不用编造数据填坑。
 */
object DeconstructLibrary {

    // ------------------------------------------------------------------
    // 已验证
    // ------------------------------------------------------------------

    private val verified: List<DeconstructCard> = listOf(

        // ---------------------------------------------------------------
        DeconstructCard(
            title = "移动城市，神级资源批发商",
            author = "括弧笑笑",
            genre = "科幻末世 / 文明游戏 / 领主流",
            tags = listOf("领主文", "穿越", "碾压流", "移动城市", "数据流"),
            logline = "全人类穿越文明游戏，驾驶着移动城市在荒野上驰骋，与人争、与天争、与其他文明争！" +
                "好消息：姜知序觉醒了神级天赋，万物暴击。坏消息：他比其他人晚了十年才穿越。" +
                "更坏的消息：天崩开局，穿越到饿死的奴隶身上，在老城主的压榨下即将中道崩殂。",
            protagonist = "姜知序",
            goldenFinger = "神级天赋「万物暴击」：每天可进行一次百倍数量暴击或十倍品质暴击",
            scale = "215 万字 · 已更新至第 777 章（连载中）",
            verified = true,
            source = "https://www.luyouxs.com/xs/659551/",
            findings = listOf(
                DeconstructFinding(
                    DeconstructAspect.OpeningHook,
                    "三重坏消息的开篇结构：先给希望（觉醒神级天赋），立刻叠加两层绝望（晚十年、体质是饿死的奴隶）。" +
                        "读者在第一章同时拿到「他很强」和「他现在很弱」两个事实，落差本身构成追读动力。",
                    "官方简介即按「好消息 / 坏消息 / 更坏的消息」三层递进展开，把最强与最弱压在同一段里。",
                    "任何带金手指的开篇都可套用：不要只写主角得到什么，要立刻写他因此多了哪个具体的麻烦。" +
                        "落差越大，第一章的抓力越强。",
                ),
                DeconstructFinding(
                    DeconstructAspect.GoldenFinger,
                    "把金手指做成「每日限次的乘法器」而非「无限资源」。百倍数量 / 十倍品质是一次性选择，" +
                        "等于每天给主角出一道选择题——限制条件才是长线剧情的燃料。",
                    "「每天可进行一次百倍数量暴击或十倍品质暴击」，二选一，且每天重置。",
                    "设计能力时优先加**限制**而不是加**上限**。日限次、二选一、有代价，" +
                        "任何一条都能把一个爽点变成几百章的冲突发生器。",
                ),
                DeconstructFinding(
                    DeconstructAspect.ReaderPayoff,
                    "把爽点工业化成可计算的数字：炮弹数量翻百倍这类兑现，读者不需要理解设定就能立刻体会强弱。",
                    "章节标题如「97 倍数量的炮弹」「暴击，雷霆炮弹」，直接用数字承载爽点。",
                    "爽点最好是**可数的**。力量等级、兵力、产能、金额——只要能量化，" +
                        "读者就能自己算出主角有多离谱，不需要作者解释。",
                ),
                DeconstructFinding(
                    DeconstructAspect.WorldBuilding,
                    "世界观靠「载具」承载而非靠叙述：所有设定都挂在移动城市这一个物理对象上，" +
                        "读者只需记住一座城，规则随城池升级自然展开。",
                    "书名与简介均以「移动城市」为核心意象，章节围绕工业型城市、改造部件展开。",
                    "尽量把世界观收进**一个具体的容器**：一艘船、一座塔、一间铺子。" +
                        "容器可变强、可被攻击、有内部结构，设定就能随剧情长出来，不用整章解释。",
                ),
            ),
        ),

        // ---------------------------------------------------------------
        DeconstructCard(
            title = "隐居深山：我直播建造星际舰队",
            author = "迷失在路途",
            genre = "科幻末世 / 种田流（番茄小说）",
            tags = listOf("种田", "直播", "科技", "星际", "幕后大佬", "装逼打脸"),
            logline = "社畜程序员学徒林叶，受不了经理撒气，毅然辞职回到深山老家躺平。" +
                "直到一场雷劈打开了他大脑的「枷锁」——学习？有手就会！从代码到物理，从手工到智械，万物再无秘密。" +
                "搞个 AI 助手成了网络之神，想自建别墅反手弄出地下基地，造个飞行器直接突破反重力。" +
                "算了，不装了！全球直播搞起：月球基地？太空港口？星际舰队？全给你直播造出来！" +
                "全球网友炸锅，各国高层骇然：「这大佬究竟是谁！？」镜头后，社恐宅男林叶抠着脚啃黄瓜：" +
                "「别问，问就是玩具，不值一提。」",
            protagonist = "林叶",
            goldenFinger = "雷劈后大脑「枷锁」打开：学习与技术能力超凡，从代码到物理、从手工到智械无一不通；" +
                "产出 AI 助手「零」、地下基地、反重力飞行器",
            scale = "241.6 万字 · 1133 章 · 连载中，3.6 万人在读",
            verified = true,
            source = "番茄小说官方作品页",
            findings = listOf(
                DeconstructFinding(
                    DeconstructAspect.ReaderPayoff,
                    "直播体：把爽点**外包给观众反应**。主角越是轻描淡写（「别问，问就是玩具」），" +
                        "弹幕与外界反应越夸张，两者之差就是爽感本身。主角不需要自我评价，评价由旁人代劳。",
                    "简介用「全球网友炸锅，各国高层骇然，巨头公司疯狂人肉」承接主角的「不值一提」，" +
                        "并直接点明这是「快乐装逼故事」。",
                    "任何题材都能插入一个旁观者群体（直播间、同僚、敌对势力、后世史书）。" +
                        "让主角的行为保持平常心，把惊叹全部交给旁观者——比让主角自己震惊高级得多。",
                ),
                DeconstructFinding(
                    DeconstructAspect.Pacing,
                    "科技树分层驱动长线：代码 → 物理 → 手工 → 智械 → AI 助手 → 地下基地 → 反重力 → 月球基地 → 星际舰队。" +
                        "每一层都是上一层的成品，读者追更的动力是「下一层会造出什么」，而不是「这一仗谁赢」。",
                    "简介本身即按这条链递进排列；241 万字体量下仍有明确的下一个台阶。",
                    "用「成果链」替代「冲突链」也能撑起百万字。关键是把每一级成果做成**可见的实体**，" +
                        "让读者知道下一级大概长什么样、为什么够得着。",
                ),
                DeconstructFinding(
                    DeconstructAspect.Hook,
                    "身份匿名制造持续悬念：主角「死活不露脸」，全书最大的悬疑不是敌人是谁，" +
                        "而是「这大佬究竟是谁」。这个悬念可以用几百章，且天然带喜剧性。",
                    "简介末句明确承诺「死活不露脸的快乐装逼故事」。",
                    "给主角保留一个**全世界都想知道、只有读者知道**的信息。这类悬念不需要维护，" +
                        "每一次外界猜测都自动续命一次。",
                ),
                DeconstructFinding(
                    DeconstructAspect.OpeningHook,
                    "开局的动力是「反向选择」而非「被迫卷入」：主角主动辞职回深山躺平，" +
                        "把主流社会的失败写成了主动退出。读者在第一章就站在他这一边。",
                    "简介首句「社畜程序员学徒林叶，受不了经理撒气，毅然辞职选择回到深山老家躺平」。",
                    "让主角的第一步是**自己的选择**，哪怕这个选择是逃避。读者对主动角色的容忍度，" +
                        "远高于对被动角色的容忍度。",
                ),
            ),
        ),

        // ---------------------------------------------------------------
        DeconstructCard(
            title = "轴承曝光我成首席科学家",
            author = "十月红日",
            genre = "科幻 / 大国科技 / 系统流（起点中文网）",
            tags = listOf("大国科技", "系统流", "超级科技", "民科梗", "曝光流"),
            logline = "林宇得到科技进化系统，开局制造出耐高温轴承，老爹拿去当样品，被夏科院曝光，" +
                "震惊整个夏国，成为首席科学家。记者：「林院士，国外说你研发的陶瓷基纤维材料是想用在发动机上，是吗？」" +
                "林宇：「纯属污蔑，我可是民科院士，这只是让我们的瓷器摔不碎罢了！」" +
                "记者：「国外传言你正在研发涡轮基循环组合发动机，可以让战斗机速度达到 10 马赫，这是真的吗？」" +
                "林宇：「我研究的明明是 10 马赫的民营私人飞机！」" +
                "下一刻只见林宇身后一座大山一般的庞然大物，缓缓升空。林宇：「我说这是送快递的，你们信吗？」",
            protagonist = "林宇",
            goldenFinger = "科技进化系统：文明等级 0.73，任务链【轩辕】、兑换商城、超时空模拟；" +
                "按系统给定工艺造出 M50PL 材料与耐高温轴承（500℃ / 30000 转每分钟），" +
                "后续扩展到航空发动机与 10 马赫飞行器",
            scale = "约 51.2 万字 · 161 章（作者停更，非正常完结）",
            verified = true,
            source = "起点中文网 / QQ 阅读作品详情页",
            findings = listOf(
                DeconstructFinding(
                    DeconstructAspect.InfoRelease,
                    "「主角台词与事实相反」的信息差结构：主角越否认（「纯属污蔑」「送快递的」），" +
                        "事实越大。读者站在知情者一侧，每一次否认都在替读者积累优越感。",
                    "简介由四轮「记者提问 → 主角否认 → 网友接梗 → 事实打脸」循环构成，" +
                        "主角的每一句狡辩都紧跟着一个物理事实（庞然大物升空）。",
                    "让主角的嘴和事实反着来，是成本最低的爽点装置。要点是**事实必须当场兑现**——" +
                        "下一句就升空，不能隔三章。否认与打脸的距离越短，效果越强。",
                ),
                DeconstructFinding(
                    DeconstructAspect.GoldenFinger,
                    "系统流 + 现实工程学：金手指不给成品，只给**工艺**（铸造与加工标准），" +
                        "主角仍要动手做。这既保留了「努力」的叙事空间，又让技术细节成为可信度来源。",
                    "系统提供铸造与加工工艺，产出物是具体的 M50PL 材料与耐高温轴承，带可验证的工况参数。",
                    "把金手指设计成「给图纸不给成品」，主角就必须过一遍过程。" +
                        "过程里可以塞真实的技术名词与参数，读者会觉得这东西「像真的」。",
                ),
                DeconstructFinding(
                    DeconstructAspect.Hook,
                    "「曝光」是主动的钩子发生器：主角从不自己宣布成就，" +
                        "而是由老爹、夏科院、记者、网友逐级把成果捅出去。每一级曝光都比上一级更公开。",
                    "简介的推进链是：造出轴承 → 老爹拿去当样品 → 夏科院曝光 → 震惊夏国 → 成为首席科学家。",
                    "给成果设计一条**传播链**而不是一条展示链。读者看的是反应逐级放大，" +
                        "不是主角拿着成果挨个炫耀。传播链还能自然引出新的势力。",
                ),
                DeconstructFinding(
                    DeconstructAspect.CharacterVoice,
                    "「民科」人设是现成的喜剧引擎：主角明明是国家队，却自称民科；" +
                        "网友替他护短（「民营私人飞机快一点怎么了」），形成圈层梗。",
                    "「我可是民科院士」「网友：我就喜欢林院士一本正经的胡说八道」。",
                    "给主角一个**与实力相反的自我定位**（民科、业余、随便玩玩），" +
                        "旁人对这个定位的维护会自动变成喜剧。这个梗可以反复用，每次效果不减。",
                ),
            ),
        ),

        // ---------------------------------------------------------------
        DeconstructCard(
            title = "逐日而生",
            author = "存叶",
            genre = "现实题材 / 现实百态（纵横中文网）",
            tags = listOf("现实", "科研", "学霸", "新强国", "主旋律"),
            logline = "新中国初期，面对以美国为首的西方各国的核讹诈，无数先辈前赴后继，" +
                "用他们的心血和汗水，引燃了那一声巨响。进入新时代，后辈们继往开来，在核物理的道路上持续深耕。" +
                "而可控核聚变，则是中国科研人员心中最为闪耀的太阳。" +
                "为了让中国实现可控核聚变商业化，进入无尽能源时代，以陈怀楚为代表的青年科研工作者们扎根科学岛，" +
                "默默坚守，苦苦钻研，誓要将那颗物理学上最闪耀的太阳点燃。",
            protagonist = "陈怀楚",
            goldenFinger = "无金手指（现实题材）。主线为可控核聚变「人造太阳」攻关：" +
                "中科大核物理博士 → 科学岛等离子体所青年科研工作者",
            scale = "24.6 万字 · 78 章 · 已完结，入 2025 年度中国网络文学影响力榜",
            verified = true,
            source = "纵横中文网作品详情页",
            findings = listOf(
                DeconstructFinding(
                    DeconstructAspect.RealityAnchor,
                    "**没有金手指也成立**：用历史对照替代能力升级。" +
                        "1964 年的原子弹与本时代的可控核聚变互文，" +
                        "主角的每一次实验进展都自动获得一个六十年的时间尺度，不需要系统来抬轿子。",
                    "简介首句铺 1964 年那声巨响，结尾回到「陈怀楚好像又看到了 1964 年那道冲天的火光」，" +
                        "两端闭合。",
                    "现实题材的爽感不来自能力，来自**尺度**。给主角的事找到一条历史线索，" +
                        "他的日常工作就会被放大成一代人的任务。这条线索要首尾呼应，形成闭环。",
                ),
                DeconstructFinding(
                    DeconstructAspect.Pacing,
                    "用真实科研节奏压住网文节奏：24.6 万字 / 78 章 ≈ 每章 3150 字，" +
                        "一章一个阶段性节点，不靠打脸推进度，靠数据与实验推进。",
                    "章节总量与体量比值接近标准网文单章长度，说明即便现实题材也未破坏阅读节奏。",
                    "题材再严肃也要守住**单章信息量**。一章一个可交付的进展（一次失败、一组数据、" +
                        "一个零件的攻克），读者才有追更的理由。",
                ),
                DeconstructFinding(
                    DeconstructAspect.WorldBuilding,
                    "把「地点」做成主角：科学岛、等离子体所是真实存在的机构，" +
                        "读者可以用现实知识校验细节，可信度不靠解释而靠索引。",
                    "简介明确点名「科学岛」「等离子体所」，均为现实中的核聚变研究机构。",
                    "用真实地名与机构名替代虚构设定，读者的常识会自动变成你的世界观说明书。" +
                        "代价是细节必须查证——写成外行话会立刻破功。",
                ),
                DeconstructFinding(
                    DeconstructAspect.CharacterVoice,
                    "群像承载主题：主角是「以陈怀楚为代表的青年科研工作者们」中的一个，" +
                        "叙事重心落在群体而非个人英雄，这使故事可以覆盖几十年时间跨度。",
                    "简介主角始终以「们」的句式出现，个人名义挂在集体叙事之下。",
                    "当题材要求长时间跨度时，把视角放到**一群人**身上比放在一个人身上更容易成立。" +
                        "个体的成长撑不过三十年，群体的接续可以。",
                ),
            ),
        ),

        // ---------------------------------------------------------------
        DeconstructCard(
            title = "脑机飞升：我的动物分身遍布万界",
            author = "诸葛钢弹",
            genre = "科幻 / 时空穿梭（创世中文网）",
            tags = listOf("科幻", "时空穿梭", "量子编程", "动物分身", "硬设定"),
            logline = "一个天才且疯狂的少年科学家，发现通过量子编程，可以将程序植入到人脑之中，" +
                "起初他只是想使用这个方法让身体重获健康，但随着深入的研究，" +
                "他发现可以通过量子纠缠给到身边的动物种下子程序，并通过量子特性下达指令……",
            protagonist = "",
            goldenFinger = "量子编程 + 量子纠缠：把程序植入人脑，给身边的动物种下「子程序」，" +
                "再借量子特性远程下达指令",
            scale = "6 章 · 1.7 万字（早期作品，作者页面未披露主角名）",
            verified = true,
            source = "QQ 阅读 / 创世中文网作品详情页",
            caveat = "仅 6 章 1.7 万字，**只能做开局结构拆解**，不能用于长篇节奏分析。" +
                "本卡结论适用于开篇设计，不适用于百万字结构。",
            findings = listOf(
                DeconstructFinding(
                    DeconstructAspect.GoldenFinger,
                    "金手指伪装成硬设定：能力不叫「系统」而叫「量子编程」，" +
                        "并且**先给动机再给能力**——主角最初的目的只是让自己恢复健康，" +
                        "「分身遍布万界」是能力扩张后的自然结果，不是初始愿望。",
                    "简介明确写出动机链条：让身体重获健康 → 量子编程 → 给动物种子程序 → 远程指令。",
                    "写超能力时给一个**小到不起眼的初始动机**，让宏大格局从副作用里长出来。" +
                        "读者对「只想治病，结果统治了万界」的接受度，远高于「一上来就要称霸」。",
                ),
                DeconstructFinding(
                    DeconstructAspect.WorldBuilding,
                    "把能力成长换算成**算力**：控制的动物越多、分身越广，算力池越大。" +
                        "这给「分身流」提供了一个可量化、可比较、可升级的成长轴。",
                    "章节目录含「新的算力池·子程序线程」，显示作者用线程 / 算力池的工程隐喻组织能力体系。",
                    "分身、召唤、傀儡这类「数量型」金手指最怕失控。给它们找一个**统一的计量单位**" +
                        "（算力、带宽、权限），数量就变成了可管理的资源。",
                ),
                DeconstructFinding(
                    DeconstructAspect.OpeningHook,
                    "开局章节以「观测者与濒死之脑」为题，把主角的处境与能力的来源绑在同一件事上：" +
                        "观察自己的濒死大脑，既是设定也是情节。",
                    "目录前两章为「观测者与濒死之脑（上 / 下)」，紧接着是「风声走漏与 Lv1 强化程序」，" +
                        "说明开局的三拍是：装置 → 暴露 → 逃亡。",
                    "开篇别分成「介绍设定」和「开始剧情」两段——让主角在**使用金手指的过程中**" +
                        "把设定交代完。前两章完成能力展示，第三章就进入风险，节奏非常紧凑。",
                ),
            ),
        ),
    )

    // ------------------------------------------------------------------
    // 待补：书名已确认，公开渠道元数据未核实
    // ------------------------------------------------------------------

    /**
     * 用户指定但公开检索未能核实的书名。
     *
     * 保留在此的目的不是占位，而是**明确缺口**：
     * UI 上会显示为「待补全」，而不是拿编造的数据冒充。
     *
     * 核实失败的客观原因：这三个书名在主流搜索引擎上被中文分词切碎
     * （「大宋科技帝国」被拆成「大」，「锻星之歌」被拆成「锻」），
     * 返回结果全是字典与百科噪音，无法通过书名定位到可靠详情页。
     * 可行的补全路径：直接在对应平台的站内搜索页按书名检索。
     */
    val unverifiedTitles: List<String> = listOf(
        "大宋科技帝国",
        "锻星之歌",
        "离线人生",
    )

    /** 完整的 8 本清单，顺序与用户给定一致。 */
    val allTitles: List<String> =
        verified.map { it.title } + unverifiedTitles

    val verifiedCards: List<DeconstructCard> get() = verified

    fun card(title: String): DeconstructCard? = verified.firstOrNull { it.title == title }

    fun byAspect(aspect: DeconstructAspect): List<DeconstructFinding> =
        verified.flatMap { it.findingsOf(aspect) }

    /** 已验证书籍里出现过的题材标签，供新建作品向导推荐。 */
    val allTags: List<String> get() = verified.flatMap { it.tags }.distinct()

    /**
     * 把拆书结论注入提示词。
     *
     * 只取**写作向**维度——「金手指设计」「爽点兑现」这类是结构层，
     * 对正在生成正文的模型没有即时价值，反而稀释指令。
     *
     * 带 [DeconstructCard.caveat] 的书只在开篇场景下选用，避免用 6 章的样本
     * 去指导 100 万字的写作。
     */
    fun promptBlock(
        maxBooks: Int = 3,
        maxFindingsPerBook: Int = 2,
        forOpening: Boolean = false,
    ): String {
        val usable = verified
            .filter { it.hasFindings }
            .filter { forOpening || it.caveat.isBlank() }
            .take(maxBooks)
        if (usable.isEmpty()) return ""

        val writingAspects = setOf(
            DeconstructAspect.OpeningHook,
            DeconstructAspect.CharacterVoice,
            DeconstructAspect.InfoRelease,
            DeconstructAspect.Hook,
            DeconstructAspect.ReaderPayoff,
        )

        return buildString {
            appendLine("【拆书参照 · 同类作品的留人机制，只学机制，不抄情节】")
            appendLine()
            usable.forEach { book ->
                appendLine("《${book.title}》（${book.genre}）")
                val picked = book.findings
                    .filter { it.aspect in writingAspects }
                    .ifEmpty { book.findings }
                    .take(maxFindingsPerBook)
                picked.forEach { f ->
                    appendLine("  · [${f.aspect.label}] ${f.mechanism}")
                    appendLine("    → 用在本文：${f.transferable}")
                }
                appendLine()
            }
        }
    }

    /**
     * 生成「推荐拆书」列表块，供 UI 之外的纯文本场景（导出、日志、控制台）使用。
     */
    fun recommendationText(): String = buildString {
        appendLine("推荐拆书作品（共 ${allTitles.size} 本，已验证 $verifiedCount 本）")
        appendLine()
        allTitles.forEachIndexed { i, title ->
            val card = card(title)
            if (card != null) {
                appendLine("${i + 1}. 《${title}》${card.author} · ${card.genre}")
                appendLine("   体量：${card.scale}")
                appendLine("   拆解维度：${card.findings.joinToString("、") { it.aspect.label }}")
                if (card.caveat.isNotBlank()) {
                    appendLine("   ⚠ ${card.caveat}")
                }
            } else {
                appendLine("${i + 1}. 《${title}》— 元数据待补全（公开检索未核实）")
            }
            appendLine()
        }
    }

    val verifiedCount: Int get() = verified.size
    val totalCount: Int get() = allTitles.size
}
