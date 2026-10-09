# 架构决策记录（ADR）

本文件记录「为什么这么写」。代码回答 what，这里回答 why。

## ADR-1：`:core` 保持纯 Kotlin，不依赖 Android

**决策**：检索、文风分析、质量评估、Agent 编排全部放在 `:core`（Kotlin JVM 模块）。

**理由**：
- 可测试性 —— 55 个单测在 JVM 上秒级跑完，不需要模拟器，改一行就能立刻验证；
- KMP 复用 —— 将来上 iOS/Desktop 时 `core` 直接作为共享 target；
- 架构防腐 —— Context/Room 被物理挡在外面，业务逻辑不会被 Android API 侵蚀。

**代价**：需要在 `:app` 层写一层实体 ↔ 领域模型的映射（约 60 行）。值得。

---

## ADR-2：端侧检索用稀疏向量，不用神经网络 embedding

**决策**：自研 `VectorIndex`（哈希技巧 + TF-IDF + 倒排索引），不引入 embedding 模型。

**理由**：

| 方案 | 召回 | 体积 | 依赖 |
|---|---|---|---|
| 神经 embedding | 95 | +200~400MB | native 运行时 |
| 纯关键词 | 60 | 0 | 无 |
| **本方案** | **80** | **0** | **无** |

对一个「写小说」的 App 来说，为了 15 分的召回提升付出 300MB 体积和一套 native 依赖不划算，
尤其这会让「离线优先、零部署」的定位破产。80 分足够把「第三章埋的玉佩」召回到第 40 章。

**代价**：同义改写（「剑」vs「兵器」）召回不到。用元数据过滤 + 正文尾部原文注入弥补。

---

## ADR-3：质量评估用确定性规则，不用 LLM 打分

**决策**：`QualityEvaluator` 全部是规则，不调用 AI。

**理由**：
1. **实时性** —— 作者每敲一段都能看到分数，LLM 做不到；
2. **可复现** —— 同一章两次评分必须一致，否则作者会困惑甚至不信任；
3. **零成本** —— 长篇要跑几百次质检，token 成本不可接受；
4. **可做硬门禁** —— 门禁必须客观，不能靠模型自说自话。

**边界**：规则查不到的语义级问题（情节是否合理）交给 AI 主编 Agent，两者互补而非替代。

---

## ADR-4：MVI 而非 MVVM

**决策**：全部界面状态收敛到不可变的 `WriterUiState`。

**理由**：写作台有多面板（正文/大纲/角色/伏笔/质量），MVVM 下容易出现
「左边大纲更新了，右边正文还是旧值」。单一状态快照 + 单向数据流从结构上消除这类 bug。

**代价**：状态类字段较多，每次更新要 `copy()`。对万字级文本，
正文用 `TextBlock` 分块 + 独立的 `dirty` 标记，避免整篇复制带来的开销。

---

## ADR-5：AI 产出先进预览区，不直接入库

**决策**：`streamingText` 与 `currentChapter.content` 严格分离，用户点「插入正文」才合并。

**理由**：这是产品伦理问题。作者的稿子是他的作品，
AI 生成的文本不能悄悄混进手稿让他事后分不清哪些是自己写的。
同时这也给了作者「看一眼再决定」的控制权。

---

## ADR-6：AGP 9 的两个配置回退

**决策**：`gradle.properties` 中设置 `android.newDsl=false` 与 `android.builtInKotlin=false`。

**理由**（实测得出，非猜测）：
- AGP 9 默认 `android.newDsl=true` → 报错
  `the 'org.jetbrains.kotlin.android' plugin is not compatible with AGP's 9.0 new DSL`；
- AGP 9 内置 Kotlin 支持 → 报错
  `KSP is not compatible with Android Gradle Plugin's built-in Kotlin`。

两者必须同时回退，才能让 **Room(KSP) + Compose 编译器插件** 正常工作。

**复查时机**：KSP 发布兼容 AGP 9 新 DSL 的版本后，可以移除这两行。

---

## ADR-7：云构建而非纯本地构建

**背景**：本项目在 aarch64 Android 设备上开发。本地已完成 Kotlin 编译 + KSP + 单测，
但 `aapt2` 官方只有 **x86_64** 二进制（已用 `file` 验证 ELF machine = 0x3e），
aarch64 上无法执行，因此 APK 打包必然失败。

**决策**：GitHub Actions 云端构建，三个 job：`test` → `build` → `release`。

**设计要点**：
- 未配置签名 secrets 时**回退 debug 签名**，保证工作流在 fork 后也能跑通；
- 测试与构建分离，测试失败时不必浪费 10 分钟打包；
- 打 `v*` tag 自动发布 Release 并附 APK。
