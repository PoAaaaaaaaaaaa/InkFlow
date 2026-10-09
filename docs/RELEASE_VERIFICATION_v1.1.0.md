# v1.1.0 发布验证报告

本报告记录对**已发布的 APK 产物**（而非源码）的功能验证过程与结论。
验证对象：GitHub Release `v1.1.0`，构建自 commit `b2bfa727`。

## 一、产物清单

| 资产 | 大小 | 说明 |
|---|---|---|
| `app-release.apk` | 47.82 MB | R8 混淆 + 资源压缩 |
| `app-debug.apk` | 69.18 MB | 未混淆，用于排障 |

- Release 页：https://github.com/PoAaaaaaaaaaaa/InkFlow/releases/tag/v1.1.0
- CI 记录：run 9（tag v1.1.0）全绿；run 8（main）全绿

## 二、验证方法

R8 会重命名类名，因此**不能靠类名判断功能是否存在**。本报告采用三重证据：

1. **用户可见字符串**：界面文案作为字符串字面量必然保留，R8 不会改动
2. **资源文件**：debug 版不做资源压缩，保留原始文件名
3. **二进制 AXML 解析**：解析 ResourceMap 确认属性引用

## 三、验证结果

### 3.1 用户可见字符串（release APK 的 classes.dex）

从 release APK 提取 `classes.dex`（5,372,110 字节，raw deflate 解压）后检索：

| 功能 | 命中的字符串 |
|---|---|
| 【1】作品删除 | `永久删除` `改为归档` `已归档` `删除作品` `此操作不可撤销` |
| 【2】作品封面 | `封面已更新` `添加封面` `更换封面` `封面保存失败` `从相册选择封面` |
| 【3】明文通信 | `明文 HTTP 传输` `当前使用明文` |
| 【4】创建向导 | `基本信息` `题材与受众` `写作风格` `规模与封面` `作品名 *` `一句话故事` `创建并开始写作` |
| 【5】记忆层 | `记忆层` `召回预览` `索引结构` `记忆总量` `运行召回` `将注入 Prompt 的原文` |
| 【6】模型获取 | `自动获取模型` `测试连接` `从列表选择` `已获取` `快速填充` |

数据库迁移 SQL 亦已打包：
`ALTER TABLE projects ADD COLUMN` / `coverPath` / `narrativePerson` / `archived`

core 层记忆层 API（ProGuard 规则保留 `com.inkflow.core.**`）：
`MemoryStats` `MemoryPreview` `IndexSize` `statsByKind` `browse` `promptBlock`

### 3.2 网络安全配置资源

debug APK 中确认存在 **`res/xml/network_security_config.xml`**（247 字节压缩，原文件名未被压缩）。
解压其二进制 AXML，字符串池包含：

```
base-config · certificates · cleartextTrafficPermitted · network-security-config · trust-anchors
```

### 3.3 清单引用（决定配置是否真正生效）

解析 debug APK 的 `AndroidManifest.xml`（二进制 AXML，10,341 字节）：

- ResourceMap 共 33 个属性 ID
- **`android:networkSecurityConfig` (0x01010527) ✅ 在清单中被引用**

> 这一步是关键：仅资源文件存在不够，必须被清单引用才会生效。

### 3.4 native 库与依赖

| 库 | 架构 | 说明 |
|---|---|---|
| `liblitertlm_jni.so` | arm64-v8a, x86_64 | LiteRT-LM 端侧推理 |
| `libdatastore_shared_counter.so` | 全架构 | DataStore |
| `libandroidx.graphics.path.so` | 全架构 | Compose 图形 |

`META-INF` 中确认打包了：Room、Compose（ui/foundation/material3）、Lifecycle、
Navigation、DataStore、ML Kit GenAI prompt 模块。

## 四、源码侧验证

| 项目 | 结果 |
|---|---|
| core 单元测试 | **69/69 通过**（本地 `--rerun-tasks` 强制全量） |
| Room v1→v2 迁移 | 用**真实 SQLite** 演练：旧数据零丢失，16/16 列与实体 schema 一致 |
| Kotlin 编译 + KSP | 通过（aarch64 本机） |
| CI 云端构建 | test → build(debug+release) → release 全绿 |

## 五、已知限制

- 本地（aarch64 Android）无法打包 APK：Android SDK 的 `aapt2` 官方仅提供 x86_64 二进制
  （已验证 ELF machine = `0x3e`），故云构建为必需环节。
- 本报告无法验证**运行时行为**（如实际点按删除、相册选图），
  仅能证明代码与资源已正确编入产物。运行时验证需在真机安装后人工确认。
