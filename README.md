# 墨流 InkFlow

> 给中文长篇小说作者的 AI 写作台 —— **离线优先，数据不出设备**。

一个真正为「写 100 万字」设计过的 Android 应用：把 AI 从聊天框里的陪聊，
变成一条可审计的生产流水线（规划 → 生成 → 一致性检查 → 审阅 → 质量门禁）。

---

## 目录

- [它解决什么问题](#它解决什么问题)
- [技术栈（2026 现行版本）](#技术栈2026-现行版本)
- [架构分层](#架构分层)
- [四层 AI 引擎：离线优先的路由](#四层-ai-引擎离线优先的路由)
- [多智能体写作管线](#多智能体写作管线)
- [端侧 RAG：零依赖的本地向量检索](#端侧-rag零依赖的本地向量检索)
- [文风 DNA：让 AI 写出你的声音](#文风-dna让-ai-写出你的声音)
- [质量评估层](#质量评估层)
- [本地构建与云构建](#本地构建与云构建)
- [安装](#安装)
- [隐私立场](#隐私立场)
- [项目结构](#项目结构)

---

## 它解决什么问题

用通用大模型写长篇小说，作者会稳定地撞上五堵墙：

| 痛点 | 表现 | 墨流的解法 |
|---|---|---|
| **上下文失忆** | 写到 30 万字，AI 忘了第 3 章埋的伏笔 | 端侧向量检索，按当前章节语义召回设定/角色/伏笔/前文 |
| **设定崩坏** | 角色外貌、能力等级前后矛盾 | 一致性检查 Agent + 规则引擎双保险 |
| **AI 腔** | 满屏「总之」「值得一提的是」「空气仿佛凝固」 | 文风 DNA 约束 + 本地启发式 AI 腔检测 |
| **伏笔断线** | 埋了 20 条，回收了 6 条，读者弃书 | 伏笔台账：追踪埋设/推进/回收，逾期自动预警 |
| **隐私顾虑** | 未发布的手稿不敢往云端传 | 默认全离线，联网必须用户显式开启 |

---

## 技术栈（2026 现行版本）

所有版本号均已在 2026-10 实际拉取校验，非凭记忆书写。

### UI 层

| 选型 | 版本 | 为写作带来的价值 |
|---|---|---|
| Jetpack Compose | BOM `2026.09.00`（Compose `1.12.1`） | 声明式 UI，双栏/抽屉布局切换成本低 |
| Compose LazyColumn + **增量文本缓冲** | 同上 | 只渲染可见行。十万字章节按段落切块，滚动不重排 |
| Material 3 | BOM 内 `1.7.x` | 动态取色 + 深色写作主题 |

### 架构模式

| 选型 | 说明 |
|---|---|
| **MVI** | 所有写作状态（正文/大纲/角色/伏笔/质量）收敛为不可变 `WriterUiState`，多面板永远一致 |
| **Kotlin Multiplatform 就绪** | `:core` 是纯 Kotlin JVM 模块，零 Android 依赖，可直接复用到 iOS/Desktop |

### AI 引擎层

| 选型 | 版本 | 定位 |
|---|---|---|
| **Gemini Nano** via ML Kit GenAI | `genai-prompt:1.0.0-beta4`（AICore 系统服务） | 系统托管的小模型，完全离线；Prompt / Summarization / Proofreading / Rewriting 四类开箱能力 |
| **LiteRT-LM** (Google AI Edge) | `litertlm-android:0.16.1` | 用户自导入 `.litertlm` 模型，支持 **GPU / NPU 后端**，长文创作主力 |
| **云端 OpenAI 兼容** | OkHttp `5.5.0` + SSE | 追求质量时的选项：OpenAI / DeepSeek / 通义 / 本地 Ollama |
| **离线模板引擎** | 内置 | 什么模型都没有时的兜底：生成写作骨架，绝不白屏 |

> LiteRT-LM 与 GenAI AAR 的实际 API 签名（`Engine`/`Session`/`GenerateContentRequest.Builder` 等）
> 均通过 `javap` 反编译核对后编写，不是照文档猜的。

### 数据与检索

| 选型 | 版本 | 说明 |
|---|---|---|
| Room | `2.8.5` | 作品 → 分卷 → 章节层级；角色卡 / 世界设定 / 伏笔台账；章节历史版本 |
| DataStore | `1.1.7` | 设置与 API Key（应用私有目录） |
| **自研端侧向量索引** | `:core` 内置 | 哈希技巧 + TF-IDF 稀疏向量 + 倒排索引，**零 native 依赖**，APK 内嵌即用 |

### 构建工具链

| 选型 | 版本 |
|---|---|
| AGP | `9.3.3` |
| Gradle | `9.8.1` |
| Kotlin | `2.3.21` |
| KSP | `2.3.12` |
| compileSdk / targetSdk | `37`（android-37.0） |
| build-tools | `37.0.0` |
| JDK | `17` |

> **AGP 9 的两个坑**（已在 `gradle.properties` 中处理）：
> 1. AGP 9 默认启用新 DSL（`android.newDsl=true`），与 `kotlin.android` 插件 + KSP 尚不兼容 → 显式回退；
> 2. AGP 9 内置 Kotlin 支持与 KSP 冲突 → `android.builtInKotlin=false`，显式应用 Kotlin 插件。

---

## 架构分层

```
┌──────────────────────────────────────────────────────────┐
│  UI 层 · Jetpack Compose + MVI                            │
│  WriterScreen（写作台） / NovelListScreen / SettingsScreen │
│  WriterIntent ──▶ WriterViewModel ──▶ WriterUiState        │
│                        │                                  │
│                        └──▶ WriterEffect（一次性副作用）    │
└────────────────────────┬─────────────────────────────────┘
                         │
┌────────────────────────▼─────────────────────────────────┐
│  引擎路由层 · EngineRouter                                 │
│  Nano → LiteRT-LM → Cloud → Template（失败自动降级）        │
│  按 AiCapability 挑选；每次结果带 engineId + degraded 标记  │
└────────────────────────┬─────────────────────────────────┘
                         │
┌────────────────────────▼─────────────────────────────────┐
│  编排层 · AgentPipeline（纯 Kotlin，55 个单测覆盖）          │
│  规划 → 生成 → 一致性 → 润色 → 校对 → 审阅 → 交接笔记        │
└───────┬──────────────────────────────┬───────────────────┘
        │                              │
┌───────▼──────────────┐   ┌───────────▼───────────────────┐
│ 检索层 NovelContextStore│   │ 质量层 QualityEvaluator        │
│ 端侧向量索引 + 倒排     │   │ Coherence + Fluency + 文风 +   │
│ 设定/角色/伏笔/前文召回  │   │ 连续性 + 伏笔台账（确定性规则）  │
└───────┬──────────────┘   └───────────────────────────────┘
        │
┌───────▼──────────────────────────────────────────────────┐
│  数据层 · Room（作品/分卷/章节/角色/设定/伏笔/历史版本）      │
└──────────────────────────────────────────────────────────┘
```

### 为什么 `:core` 是纯 Kotlin 模块

写作逻辑（检索、文风分析、质量评估、Agent 编排）**完全不依赖 Android**。
带来三个好处：

1. **可测试**：55 个单元测试在 JVM 上毫秒级跑完，不需要模拟器；
2. **可复用**：将来上 iOS/Desktop，`core` 直接编译成 KMP target；
3. **强制解耦**：Android 相关的东西（Context、Room）被挡在外面，架构不会被侵蚀。

---

## 四层 AI 引擎：离线优先的路由

`EngineRouter` 按用户配置的优先级串联引擎，**失败自动降级**，且**从不静默降级**：

```kotlin
// 每次返回都带来源标记
data class AiResponse(
    val text: String,
    val engineId: String,      // 到底是谁生成的
    val degraded: Boolean,     // 是否是兜底产出
)
```

| 优先级 | 引擎 | 离线 | 数据出设备 | 典型用途 |
|---|---|---|---|---|
| 1 | Gemini Nano | ✅ | ❌ | 校对、改写、摘要（系统 AICore） |
| 2 | LiteRT-LM | ✅ | ❌ | 章节生成主力（自导入模型，GPU/NPU） |
| 3 | Cloud | ❌ | ✅ 用户开启后 | 追求质量的定稿章 |
| 4 | Template | ✅ | ❌ | 无模型时生成写作骨架 |

**隐私是默认值，不是选项**：云端引擎只有在用户显式打开开关并填写 API Key 后才参与路由。

---

## 多智能体写作管线

范式参考：DeepWriter（AAAI 2026）的「规划→生成→一致性→审阅」流水线，
叠加 LOOM 的分层认知循环，以及 8-Agent 实现中的**章节交接笔记 + 检查点**机制。

| # | Agent | 职责 | 解决什么 |
|---|---|---|---|
| 1 | 全局规划师 | 分卷/章节结构、三幕节奏 | 长篇结构失控 |
| 1.5 | 世界观架构师 | 生成 Story Bible（世界观/力量体系/角色/伏笔） | 开局设定单薄 |
| 2 | 章节细纲编剧 | 场景/人物/事件/钩子五段式细纲 | 生成前先想清楚 |
| 3 | 正文写作 | 注入文风 DNA + 检索语境 + 交接笔记 | 写出走味的 AI 腔 |
| 4 | 一致性检查员 | 比对设定档案找矛盾，输出结构化 JSON | 设定崩坏 |
| 5 | 润色编辑 | 改病句、删冗余、调节奏，**不改情节** | 文字粗糙 |
| 6 | 校对员 | 错别字/语法/标点，输出结构化修改项 | 细节错误 |
| 7 | 主编审阅 | 开篇吸引力/推进效率/情绪张力/钩子强度 | 读者留存 |
| 8 | 交接笔记 | 把本章压成 400 字，供下一章注入 | **长篇上下文丢失** |

### 关键工程细节

**交接笔记（Handoff Note）** 是长篇不崩的核心机制。
每章写完后自动生成一条压缩摘要（保留推进中的伏笔、角色状态变化、未解决悬念），
下一章写作时注入。同时定期把最近 15 章笔记压缩成「全书前情提要」，
构成 LOOM 式的全局反馈循环。

**输出清洗**（`cleanBody`）：模型爱加开场白和结尾说明，
逐行剥离 `好的，以下是` / `希望这个版本` / `---` 等元话语，
确保只有正文落进作者的手稿。

**流式产出不直接入库**：AI 生成的内容先进入预览区，
用户点「插入正文」才合并 —— **绝不让 AI 文本悄悄污染作者手稿**。

---

## 端侧 RAG：零依赖的本地向量检索

长篇写作的核心矛盾：**模型上下文有限，但作品设定是无限的**。

`VectorIndex` 是自研的端侧向量库，设计取舍很明确：

| 方案 | 召回质量 | APK 体积 | 依赖 | 选择 |
|---|---|---|---|---|
| 神经网络 embedding | 95 分 | +200~400MB | ONNX/TFLite native | ❌ |
| 关键词全文检索 | 60 分 | 0 | 无 | ❌ |
| **哈希技巧 + TF-IDF 稀疏向量 + 倒排索引** | **80 分** | **0** | **无** | ✅ |

> 宁要 0 依赖的 80 分，不要 300MB 的 95 分 —— 这是移动端「离线优先」的关键取舍。

实现要点：

- **分词**：中文取单字 + 相邻 bigram（bigram 承载大部分语义）+ 停用字过滤；英文/数字按连续串；
- **加权**：次线性 TF（`1 + ln(tf)`）抑制高频词，IDF 压制常见词；
- **检索**：只累加倒排命中的桶，不做全量 O(N·D) 点积 —— 这是「毫秒级」的来源；
- **增量**：写完一章立刻 `upsert`，无需重建整库；
- **过滤**：按 `projectId` 元数据过滤，**杜绝跨作品串味**。

检索结果按类型分区注入 Prompt：

```
### 相关设定      → 世界规则、地理、力量体系
### 出场角色      → 性格、目标、关系（一致性基准）
### 待处理伏笔    → 必须照应的悬而未决线索
### 最近正文      → 用原文尾部而非检索摘要，语气衔接最准
```

---

## 文风 DNA：让 AI 写出你的声音

`StyleAnalyzer` 从作者既有章节中蒸馏可量化的写作指纹：

| 维度 | 指标 |
|---|---|
| 句法 | 平均句长、句长标准差（节奏起伏）、短句占比、长句占比 |
| 段落 | 平均段长、对话行占比、每段句数 |
| 修辞 | 每千字比喻密度、情绪句占比、破折号/省略号密度 |
| 用词 | 高频签名词、**过度使用词（禁忌清单）**、叙述人称 |

蒸馏结果直接转成 Prompt 约束块注入每次生成：

```
【文风 DNA · 必须严格模仿】
- 叙述人称：第三人称
- 句长：平均 14.2 字，短句(<=12字)占 48.3%，长句(>=40字)占 4.1%
- 段落：平均每段 62.5 字 / 2.3 句，对话行占 31.0%
- 作者惯用词（自然融入，勿堆砌）：……
- 禁忌清单（严禁连续使用/滥用）：……
```

`StyleAnalyzer.matchScore()` 还能给任意新文本打**文风吻合度**（0–100），
写完一章自动预警「这段走味了」。

---

## 质量评估层

`QualityEvaluator` 是**确定性规则引擎**，不消耗 token、离线可用、结果可复现。
（AI 主编负责语义级问题，规则引擎负责机器能精确判断的问题，两者互补。）

### Coherence（连贯性）

- 角色称呼一致性（孤立简称检测，前后加非汉字断言避免误判「桃李」）
- 时间跳转缺过渡（「第二天」后直接接动作句）
- 代词悬空（连续以「他/她」开头但上句无明确指代对象）
- 数值/等级自相矛盾

### Fluency（流畅度）

- 超长句（>60 字）检测
- 段内用词重复（2-gram 频率偏离）
- 标点规范（连续标点、中文里的半角标点）
- 词汇广度（type-token ratio）
- 段落过长（移动端阅读疲劳）

### 叙事连续性

- 章首与上章尾是否共享人物/场景线索
- 上章结尾的悬念是否被完全遗忘

### 伏笔回收台账

- 逾期未回收（超过计划章节 +3 章，按重要度分级预警）
- 长期停滞（15 章未推进）
- 开放伏笔过载（>25 条）

### 质量门禁

章节状态机 `草稿 → 已润色 → 定稿`。
**升到「定稿」必须通过门禁**：综合评分 ≥ 阈值（默认 70）且无严重级问题（如伏笔逾期）。

---

## 本地构建与云构建

### 为什么必须云构建

Android 的 `aapt2` 官方只发布 **x86_64** 二进制。
本项目在 aarch64 Android 设备上完成开发，本地可以完成
**Kotlin 编译 + KSP（Room）+ 55 个单元测试**，
但 **APK 打包必须在 x86_64 runner 上完成** —— 这就是 GitHub Actions 工作流存在的意义。

### 云端工作流（`.github/workflows/android-ci.yml`）

三个阶段：

1. **test** — 跑 `:core:test`（55 个单元测试），上传测试报告
2. **build** — 构建 Debug + Release APK，上传产物
3. **release** — 打 `v*` tag 时自动创建 GitHub Release 并附上 APK

签名支持：配置以下 secrets 即可自动使用正式签名，**未配置时回退 debug 签名**（保证工作流永远能跑通）：

| Secret | 说明 |
|---|---|
| `KEYSTORE_BASE64` | keystore 文件的 base64 |
| `KEYSTORE_PASSWORD` | keystore 密码 |
| `KEY_ALIAS` | 密钥别名 |
| `KEY_PASSWORD` | 密钥密码 |

生成 keystore 并用 base64 输出：

```bash
keytool -genkeypair -v -keystore release.jks -keyalg RSA -keysize 2048 \
  -validity 10000 -alias inkflow
base64 -w0 release.jks   # 把输出粘进 KEYSTORE_BASE64
```

### 本地构建

```bash
# 前置：JDK 17、Android SDK（platforms;android-37.0 + build-tools;37.0.0）
export ANDROID_HOME=/path/to/android-sdk
echo "sdk.dir=$ANDROID_HOME" > local.properties

./gradlew :core:test          # 单元测试（纯 JVM，秒级）
./gradlew :app:assembleDebug   # 打包 Debug APK
./gradlew :app:assembleRelease # 打包 Release APK（R8 混淆 + 资源压缩）
```

---

## 安装

从 [Releases](https://github.com/PoAaaaaaaaaaaa/InkFlow/releases) 下载最新 APK，或从 Actions 的 Artifacts 中获取构建产物。

- `InkFlow-release-*.apk` — 日常安装
- `InkFlow-debug-*.apk` — 排查问题

系统要求：**Android 8.0（API 26）及以上**，arm64 设备体验最佳。

安装前需在系统设置中允许「安装未知来源应用」。

---

## 隐私立场

这是写小说用的软件，未发布的手稿是最私密的东西之一。

1. **默认全离线**。Gemini Nano 与 LiteRT-LM 都在本机推理，正文不离开设备。
2. **联网必须显式开启**。云端引擎默认关闭，需用户填写自己的 API Key 才参与路由。
3. **API Key 只在本机**。存于应用私有 DataStore，不写日志、不同步、不进构建产物。
4. **不静默降级**。每次生成的来源（本地/云端/兜底）都在界面上如实标注。
5. **AI 文本不自动入库**。生成内容先进预览区，用户确认后才并入正文。
6. **第一章之前**：文风 DNA 与检索索引全部在本地计算，不上传任何样本。

---

## 快速上手

1. **安装**：从 [Releases](https://github.com/PoAaaaaaaaaaaa/InkFlow/releases) 下载 `app-release.apk`（约 48MB）
2. **新建作品**：填作品名、题材、一句话故事、核心设定
3. **规划**：AI 写作面板里点「构建作品蓝图」→ 生成角色卡与伏笔；点「规划全书」→ 生成分卷分章大纲
4. **开写**：逐章「生成整章」→ 预览确认 → 插入正文 → 自己打磨
5. **守住质量**：质检 → 一致性检查 → 交接笔记 → 定稿（过门禁）

详细操作见 [docs/USAGE.md](docs/USAGE.md)，设计取舍见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)。

---

## 已验证状态

| 验证项 | 结果 |
|---|---|
| core 单元测试 | ✅ 55/55 通过（本地 + GitHub Actions 双跑通） |
| Kotlin 编译 + KSP（Room） | ✅ 通过（aarch64 本机） |
| Debug APK 构建 | ✅ 通过（GitHub Actions） |
| Release APK 构建（R8 + 资源压缩） | ✅ 通过（GitHub Actions） |
| GitHub Release 自动发布 | ✅ 已发布 [v1.0.0](https://github.com/PoAaaaaaaaaaaa/InkFlow/releases/tag/v1.0.0) |

> 本地（aarch64 Android）无法完成 APK 打包：Android SDK 的 `aapt2` 官方只提供 x86_64 二进制
> （已用 `file` 验证 ELF machine = `0x3e`）。因此云构建是本项目的必需环节，而非可选项。

---

## 项目结构

```
InkFlow/
├── core/                                  # 纯 Kotlin，零 Android 依赖（KMP 就绪）
│   └── src/main/kotlin/com/inkflow/core/
│       ├── domain/Domain.kt                # 作品/分卷/章节/角色/伏笔/设定 + 字数统计
│       ├── ai/AiEngine.kt                  # 引擎抽象（离线标记、能力集、流式）
│       ├── rag/VectorIndex.kt              # 端侧向量索引（哈希+TF-IDF+倒排）
│       ├── rag/NovelContextStore.kt        # 写作语境仓库，按类型分区检索
│       ├── style/StyleDna.kt               # 文风蒸馏 + 吻合度打分
│       ├── quality/QualityEvaluator.kt     # Coherence/Fluency/连续性/伏笔 + 门禁
│       └── agent/AgentPipeline.kt          # 8-Agent 编排 + 提示词中心
│   └── src/test/kotlin/                    # 55 个单元测试
├── app/
│   └── src/main/java/com/inkflow/app/
│       ├── data/Database.kt                # Room：7 张表 + DAO
│       ├── data/NovelRepository.kt         # 仓库 + 历史版本自动快照
│       ├── data/SettingsStore.kt           # DataStore 设置
│       ├── ai/NanoEngine.kt                # Gemini Nano（AICore）
│       ├── ai/LiteRtLmEngine.kt            # LiteRT-LM（GPU/NPU）
│       ├── ai/CloudEngine.kt               # OpenAI 兼容 + SSE 流式
│       ├── ai/TemplateEngine.kt            # 离线兜底
│       ├── ai/EngineRouter.kt              # 路由与自动降级
│       └── ui/                             # Compose：写作台/书架/设置
│           └── mvi/                        # MVI 契约 + ViewModel
└── .github/workflows/android-ci.yml        # 云构建：测试 → 构建 → 发布
```

---

## 开发状态

| 模块 | 状态 |
|---|---|
| core 引擎（检索/文风/质量/Agent） | ✅ 已完成，55 个单元测试全部通过 |
| 数据层（Room 7 表 + 历史版本回滚） | ✅ 已完成 |
| 四引擎路由 + 自动降级 | ✅ 已完成 |
| Compose UI（写作台/书架/设置） | ✅ 已完成 |
| Kotlin 编译 + KSP 校验 | ✅ 通过（aarch64 本机） |
| APK 打包 | ⏳ 需 x86_64 云端 runner（aapt2 限制） |

### 后续可做

- 接入 NPU 厂商运行时（Qualcomm GenieX）进一步降低长时写作功耗
- WebDAV / 云盘同步与多设备冲突合并
- 导出 EPUB / TXT / 投稿格式
- 章节历史版本的差异对比视图（当前是整版本回滚）
